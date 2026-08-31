# Conversation History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist every finished conversation (Start→Stop) to a local database, and let the customer browse, search, view in full, rename, and delete them from a new History screen reached via an icon on the Main screen.

**Architecture:** Room/SQLite persistence behind a `HistoryRepository` interface (mirroring the existing `SettingsStore`/`ConfigRepository` and `AudioSource`/`SpeechRecognizer` interface+impl pattern), a domain `ConversationRecord` kept separate from the Room entity, two new Compose screens (list + detail) wired into the existing `NavHost`, and a save-on-`stop()` hook in `SessionViewModel`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room 2.8.4 (new dependency), KSP 2.3.11 (new plugin, Room's annotation processor), `java.time` for date formatting (no new dependency — available natively at minSdk 26).

**Spec:** `docs/superpowers/specs/2026-08-31-transyncai-conversation-history-design.md`

## Global Constraints

- No retention cap — history is unlimited (spec explicitly supersedes the earlier
  capped-list idea once Room was chosen).
- Room/SQLite stores conversation text **unencrypted on disk** — an explicitly accepted
  tradeoff (spec, "Storage: Room" section). Do not add encryption as part of this plan;
  it's a deliberate, documented gap, not an oversight to silently fix.
- The domain model `ConversationRecord` must stay separate from the Room-annotated
  `ConversationRecordEntity` — Room types never appear in `SessionViewModel`, the UI
  layer, or JVM unit tests.
- Room/DAO code is tested via **instrumented tests** (`androidTest`) using
  `Room.inMemoryDatabaseBuilder(...)` — this project has no Robolectric, so Room cannot
  run in the plain JVM `test` source set.
- Title is editable **only** from the detail screen (not inline in the list).
- History icon: `Icons.Filled.History`, placed in `MainScreen`'s `TopAppBar` actions,
  ordered Context → History → Settings.
- Room version: **2.8.4**. KSP version: **2.3.11** (KSP2 is Kotlin-version-independent;
  2.3.11 supports Kotlin 2.2+, covering this project's Kotlin 2.3.20 — verified against
  the KSP releases page and Android Developers' Room release notes on 2026-09-01).

---

### Task 1: Room dependency setup + persistence layer (Entity, DAO, Database)

**Files:**
- Modify: `build.gradle.kts` (root)
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/petroff/transyncai/history/ConversationRecordEntity.kt`
- Create: `app/src/main/java/com/petroff/transyncai/history/ConversationRecordDao.kt`
- Create: `app/src/main/java/com/petroff/transyncai/history/TransyncDatabase.kt`
- Test: `app/src/androidTest/java/com/petroff/transyncai/history/ConversationRecordDaoTest.kt`

**Interfaces:**
- Produces: `ConversationRecordEntity(id: String, timestamp: Long, title: String?, sourceLanguageName: String, targetLanguageName: String, sourceText: String, translatedText: String)`; `ConversationRecordDao` with `suspend fun getAll(): List<ConversationRecordEntity>`, `suspend fun search(query: String): List<ConversationRecordEntity>`, `suspend fun insert(record: ConversationRecordEntity)`, `suspend fun deleteById(id: String)`, `suspend fun updateTitle(id: String, title: String)`; `TransyncDatabase` (abstract, `RoomDatabase`) with `abstract fun conversationRecordDao(): ConversationRecordDao`.

- [ ] **Step 1: Add the KSP plugin and Room dependencies to the Gradle build**

In `build.gradle.kts` (root), add the KSP plugin declaration to the existing `plugins` block:

```kotlin
plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
```

In `app/build.gradle.kts`, apply the KSP plugin:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
```

And add Room to the existing `dependencies` block, right after the
`kotlinx-coroutines-android` line:

```kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
```

- [ ] **Step 2: Verify the new plugin/dependencies resolve**

Run: `./gradlew help --console=plain`
Expected: `BUILD SUCCESSFUL` — this forces Gradle to resolve the KSP plugin and Room
coordinates without yet compiling anything. If it fails with a "plugin not found" or
"could not resolve" error naming the KSP or Room coordinate, check
https://github.com/google/ksp/releases and https://developer.android.com/jetpack/androidx/releases/room
for the current version and adjust the version string above accordingly, then re-run
this step.

- [ ] **Step 3: Write the failing instrumented test**

Create `app/src/androidTest/java/com/petroff/transyncai/history/ConversationRecordDaoTest.kt`:

```kotlin
package com.petroff.transyncai.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationRecordDaoTest {

    private lateinit var database: TransyncDatabase
    private lateinit var dao: ConversationRecordDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TransyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.conversationRecordDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun record(
        id: String,
        timestamp: Long = 1000L,
        title: String? = null,
        sourceText: String = "hello world",
        translatedText: String = "привет мир",
    ) = ConversationRecordEntity(
        id = id,
        timestamp = timestamp,
        title = title,
        sourceLanguageName = "English",
        targetLanguageName = "Russian",
        sourceText = sourceText,
        translatedText = translatedText,
    )

    // Using runBlocking, not kotlinx.coroutines.test.runTest — this project only declares
    // kotlinx-coroutines-test as testImplementation (JVM unit tests), not
    // androidTestImplementation, and runBlocking needs no extra dependency here (it's
    // already transitively available via kotlinx-coroutines-android).

    @Test
    fun getAllReturnsNewestFirst() = runBlocking {
        dao.insert(record(id = "1", timestamp = 1000L))
        dao.insert(record(id = "2", timestamp = 2000L))

        val all = dao.getAll()

        assertEquals(listOf("2", "1"), all.map { it.id })
    }

    @Test
    fun searchMatchesTitleOrSourceOrTranslatedText() = runBlocking {
        dao.insert(record(id = "1", title = "Billing call", sourceText = "unrelated", translatedText = "unrelated"))
        dao.insert(record(id = "2", title = null, sourceText = "talking about the invoice", translatedText = "unrelated"))
        dao.insert(record(id = "3", title = null, sourceText = "unrelated", translatedText = "разговор о счете"))
        dao.insert(record(id = "4", title = null, sourceText = "nothing matches", translatedText = "ничего"))

        val results = dao.search("invoice")

        assertEquals(listOf("2"), results.map { it.id })
    }

    @Test
    fun deleteByIdRemovesOnlyThatRecord() = runBlocking {
        dao.insert(record(id = "1"))
        dao.insert(record(id = "2"))

        dao.deleteById("1")

        assertEquals(listOf("2"), dao.getAll().map { it.id })
    }

    @Test
    fun updateTitleChangesOnlyTheTitle() = runBlocking {
        dao.insert(record(id = "1", title = null))

        dao.updateTitle("1", "My conversation")

        val updated = dao.getAll().single()
        assertEquals("My conversation", updated.title)
        assertEquals("hello world", updated.sourceText)
    }
}
```

- [ ] **Step 4: Run the test, verify it fails to compile**

Run: `./gradlew compileDebugAndroidTestKotlin --console=plain`
Expected: FAIL — `Unresolved reference` errors for `TransyncDatabase`,
`ConversationRecordDao`, `ConversationRecordEntity` (none exist yet).

- [ ] **Step 5: Write the Entity, DAO, and Database**

Create `app/src/main/java/com/petroff/transyncai/history/ConversationRecordEntity.kt`:

```kotlin
package com.petroff.transyncai.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_records")
data class ConversationRecordEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val title: String?,
    val sourceLanguageName: String,
    val targetLanguageName: String,
    val sourceText: String,
    val translatedText: String,
)
```

Create `app/src/main/java/com/petroff/transyncai/history/ConversationRecordDao.kt`:

```kotlin
package com.petroff.transyncai.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ConversationRecordDao {

    @Query("SELECT * FROM conversation_records ORDER BY timestamp DESC")
    suspend fun getAll(): List<ConversationRecordEntity>

    @Query(
        "SELECT * FROM conversation_records WHERE " +
            "title LIKE '%' || :query || '%' OR " +
            "sourceText LIKE '%' || :query || '%' OR " +
            "translatedText LIKE '%' || :query || '%' " +
            "ORDER BY timestamp DESC"
    )
    suspend fun search(query: String): List<ConversationRecordEntity>

    @Insert
    suspend fun insert(record: ConversationRecordEntity)

    @Query("DELETE FROM conversation_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE conversation_records SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)
}
```

Create `app/src/main/java/com/petroff/transyncai/history/TransyncDatabase.kt`:

```kotlin
package com.petroff.transyncai.history

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ConversationRecordEntity::class], version = 1, exportSchema = false)
abstract class TransyncDatabase : RoomDatabase() {
    abstract fun conversationRecordDao(): ConversationRecordDao
}
```

(`exportSchema = false` — this project has no migration-testing infrastructure and this
plan only ever creates version 1; exporting a schema JSON with nowhere configured to put
it would otherwise fail the build or emit a warning.)

- [ ] **Step 6: Run the test, verify it passes**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.petroff.transyncai.history.ConversationRecordDaoTest --console=plain`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts app/build.gradle.kts app/src/main/java/com/petroff/transyncai/history/ app/src/androidTest/java/com/petroff/transyncai/history/
git commit -m "Add Room persistence layer for conversation history"
```

---

### Task 2: Domain model + HistoryRepository + RoomHistoryRepository

**Files:**
- Create: `app/src/main/java/com/petroff/transyncai/history/ConversationRecord.kt`
- Create: `app/src/main/java/com/petroff/transyncai/history/HistoryRepository.kt`
- Create: `app/src/main/java/com/petroff/transyncai/history/RoomHistoryRepository.kt`
- Test: `app/src/test/java/com/petroff/transyncai/history/ConversationRecordTest.kt`
- Test: `app/src/androidTest/java/com/petroff/transyncai/history/RoomHistoryRepositoryTest.kt`

**Interfaces:**
- Consumes: `ConversationRecordDao`, `ConversationRecordEntity`, `TransyncDatabase` (Task 1).
- Produces: `ConversationRecord(id: String, timestamp: Long, title: String?, sourceLanguageName: String, targetLanguageName: String, sourceText: String, translatedText: String)`; `fun ConversationRecord.displayTitle(): String`; `HistoryRepository` interface with `suspend fun getRecords(): List<ConversationRecord>`, `suspend fun search(query: String): List<ConversationRecord>`, `suspend fun addRecord(record: ConversationRecord)`, `suspend fun deleteRecord(id: String)`, `suspend fun setTitle(id: String, title: String)`; `RoomHistoryRepository(dao: ConversationRecordDao) : HistoryRepository`.

- [ ] **Step 1: Write the failing JVM unit test for `displayTitle()`**

Create `app/src/test/java/com/petroff/transyncai/history/ConversationRecordTest.kt`:

```kotlin
package com.petroff.transyncai.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRecordTest {

    private fun record(title: String?) = ConversationRecord(
        id = "1",
        timestamp = 1756675200000L,
        title = title,
        sourceLanguageName = "English",
        targetLanguageName = "Russian",
        sourceText = "hello",
        translatedText = "привет",
    )

    @Test
    fun `displayTitle returns the explicit title when set`() {
        val result = record(title = "My conversation").displayTitle()

        assertEquals("My conversation", result)
    }

    @Test
    fun `displayTitle falls back to timestamp and language pair when title is null`() {
        val result = record(title = null).displayTitle()

        assertTrue(result.contains("· English → Russian"))
    }

    @Test
    fun `displayTitle falls back to timestamp and language pair when title is blank`() {
        val result = record(title = "   ").displayTitle()

        assertTrue(result.contains("· English → Russian"))
    }
}
```

- [ ] **Step 2: Run the test, verify it fails to compile**

Run: `./gradlew testDebugUnitTest --tests "com.petroff.transyncai.history.ConversationRecordTest" --console=plain`
Expected: FAIL — `Unresolved reference: ConversationRecord`.

- [ ] **Step 3: Write `ConversationRecord.kt`**

Create `app/src/main/java/com/petroff/transyncai/history/ConversationRecord.kt`:

```kotlin
package com.petroff.transyncai.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ConversationRecord(
    val id: String,
    val timestamp: Long,
    val title: String?,
    val sourceLanguageName: String,
    val targetLanguageName: String,
    val sourceText: String,
    val translatedText: String,
)

private val DISPLAY_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

/**
 * The title shown in the History list/detail screens: the customer's own title once
 * they've set one (see HistoryDetailScreen), otherwise a fallback computed from the
 * timestamp and language pair — never stored, so the stored `title` field stays an
 * honest signal of whether the customer actually named this conversation.
 */
fun ConversationRecord.displayTitle(): String {
    val explicitTitle = title?.takeIf { it.isNotBlank() }
    if (explicitTitle != null) return explicitTitle
    val formattedTimestamp = Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(DISPLAY_TIMESTAMP_FORMATTER)
    return "$formattedTimestamp · $sourceLanguageName → $targetLanguageName"
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.petroff.transyncai.history.ConversationRecordTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Write the failing instrumented test for `RoomHistoryRepository`**

Create `app/src/androidTest/java/com/petroff/transyncai/history/RoomHistoryRepositoryTest.kt`:

```kotlin
package com.petroff.transyncai.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomHistoryRepositoryTest {

    private lateinit var database: TransyncDatabase
    private lateinit var repository: HistoryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TransyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomHistoryRepository(database.conversationRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun record(id: String, title: String? = null) = ConversationRecord(
        id = id,
        timestamp = 1000L,
        title = title,
        sourceLanguageName = "English",
        targetLanguageName = "Russian",
        sourceText = "hello world",
        translatedText = "привет мир",
    )

    // runBlocking, not kotlinx.coroutines.test.runTest — see the same note in
    // ConversationRecordDaoTest above (Task 1).

    @Test
    fun addRecordThenGetRecordsRoundTripsAllFields() = runBlocking {
        repository.addRecord(record(id = "1", title = "My title"))

        val all = repository.getRecords()

        assertEquals(1, all.size)
        val loaded = all.first()
        assertEquals("1", loaded.id)
        assertEquals("My title", loaded.title)
        assertEquals("English", loaded.sourceLanguageName)
        assertEquals("Russian", loaded.targetLanguageName)
        assertEquals("hello world", loaded.sourceText)
        assertEquals("привет мир", loaded.translatedText)
    }

    @Test
    fun searchDelegatesToTheDatabase() = runBlocking {
        repository.addRecord(record(id = "1", title = "Billing call"))
        repository.addRecord(record(id = "2", title = "Weather chat"))

        val results = repository.search("Billing")

        assertEquals(listOf("1"), results.map { it.id })
    }

    @Test
    fun deleteRecordRemovesIt() = runBlocking {
        repository.addRecord(record(id = "1"))

        repository.deleteRecord("1")

        assertTrue(repository.getRecords().isEmpty())
    }

    @Test
    fun setTitleUpdatesTheStoredTitle() = runBlocking {
        repository.addRecord(record(id = "1", title = null))

        repository.setTitle("1", "Renamed")

        assertEquals("Renamed", repository.getRecords().single().title)
    }
}
```

- [ ] **Step 6: Run the test, verify it fails to compile**

Run: `./gradlew compileDebugAndroidTestKotlin --console=plain`
Expected: FAIL — `Unresolved reference` for `HistoryRepository` and `RoomHistoryRepository`.

- [ ] **Step 7: Write `HistoryRepository.kt` and `RoomHistoryRepository.kt`**

Create `app/src/main/java/com/petroff/transyncai/history/HistoryRepository.kt`:

```kotlin
package com.petroff.transyncai.history

interface HistoryRepository {
    suspend fun getRecords(): List<ConversationRecord>
    suspend fun search(query: String): List<ConversationRecord>
    suspend fun addRecord(record: ConversationRecord)
    suspend fun deleteRecord(id: String)
    suspend fun setTitle(id: String, title: String)
}
```

Create `app/src/main/java/com/petroff/transyncai/history/RoomHistoryRepository.kt`:

```kotlin
package com.petroff.transyncai.history

class RoomHistoryRepository(private val dao: ConversationRecordDao) : HistoryRepository {

    override suspend fun getRecords(): List<ConversationRecord> = dao.getAll().map { it.toDomain() }

    override suspend fun search(query: String): List<ConversationRecord> = dao.search(query).map { it.toDomain() }

    override suspend fun addRecord(record: ConversationRecord) = dao.insert(record.toEntity())

    override suspend fun deleteRecord(id: String) = dao.deleteById(id)

    override suspend fun setTitle(id: String, title: String) = dao.updateTitle(id, title)

    private fun ConversationRecordEntity.toDomain() = ConversationRecord(
        id = id,
        timestamp = timestamp,
        title = title,
        sourceLanguageName = sourceLanguageName,
        targetLanguageName = targetLanguageName,
        sourceText = sourceText,
        translatedText = translatedText,
    )

    private fun ConversationRecord.toEntity() = ConversationRecordEntity(
        id = id,
        timestamp = timestamp,
        title = title,
        sourceLanguageName = sourceLanguageName,
        targetLanguageName = targetLanguageName,
        sourceText = sourceText,
        translatedText = translatedText,
    )
}
```

- [ ] **Step 8: Run the test, verify it passes**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.petroff.transyncai.history.RoomHistoryRepositoryTest --console=plain`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/history/ConversationRecord.kt app/src/main/java/com/petroff/transyncai/history/HistoryRepository.kt app/src/main/java/com/petroff/transyncai/history/RoomHistoryRepository.kt app/src/test/java/com/petroff/transyncai/history/ app/src/androidTest/java/com/petroff/transyncai/history/RoomHistoryRepositoryTest.kt
git commit -m "Add ConversationRecord domain model and HistoryRepository"
```

---

### Task 3: FakeHistoryRepository + SessionViewModel wiring + AppContainer wiring

**Files:**
- Create: `app/src/test/java/com/petroff/transyncai/session/FakeHistoryRepository.kt`
- Modify: `app/src/main/java/com/petroff/transyncai/session/SessionViewModel.kt`
- Modify: `app/src/main/java/com/petroff/transyncai/session/SessionViewModelFactory.kt`
- Modify: `app/src/main/java/com/petroff/transyncai/AppContainer.kt`
- Modify: `app/src/test/java/com/petroff/transyncai/session/SessionViewModelTest.kt`

**Interfaces:**
- Consumes: `HistoryRepository`, `ConversationRecord` (Task 2).
- Produces: `SessionViewModel(speechRecognizer, translator, configRepository, historyRepository)` (new 4th constructor param); `AppContainer.historyRepository: HistoryRepository`.

- [ ] **Step 1: Write `FakeHistoryRepository`**

Create `app/src/test/java/com/petroff/transyncai/session/FakeHistoryRepository.kt`:

```kotlin
package com.petroff.transyncai.session

import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.history.HistoryRepository

class FakeHistoryRepository : HistoryRepository {
    val addedRecords = mutableListOf<ConversationRecord>()
    private val records = mutableListOf<ConversationRecord>()

    override suspend fun getRecords(): List<ConversationRecord> = records.toList()

    override suspend fun search(query: String): List<ConversationRecord> =
        records.filter {
            it.title?.contains(query, ignoreCase = true) == true ||
                it.sourceText.contains(query, ignoreCase = true) ||
                it.translatedText.contains(query, ignoreCase = true)
        }

    override suspend fun addRecord(record: ConversationRecord) {
        addedRecords.add(record)
        records.add(0, record)
    }

    override suspend fun deleteRecord(id: String) {
        records.removeAll { it.id == id }
    }

    override suspend fun setTitle(id: String, title: String) {
        val index = records.indexOfFirst { it.id == id }
        if (index >= 0) records[index] = records[index].copy(title = title)
    }
}
```

- [ ] **Step 2: Write the failing `SessionViewModel` tests**

In `app/src/test/java/com/petroff/transyncai/session/SessionViewModelTest.kt`, add the
`historyRepository` field and wire it into both `SessionViewModel(...)` construction
sites, then add two new tests.

Add near the other `private lateinit var` fields:

```kotlin
    private lateinit var historyRepository: FakeHistoryRepository
```

In `setUp()`, add `historyRepository = FakeHistoryRepository()` before the
`viewModel = SessionViewModel(...)` line, and change that line to:

```kotlin
        viewModel = SessionViewModel(recognizer, translator, configRepository, historyRepository)
```

In the `canStart is false proactively, before any start attempt, when Both mode has no key`
test, change:

```kotlin
        val freshViewModel = SessionViewModel(FakeSpeechRecognizer(), FakeTranslator(), noKeyRepo)
```

to:

```kotlin
        val freshViewModel = SessionViewModel(FakeSpeechRecognizer(), FakeTranslator(), noKeyRepo, FakeHistoryRepository())
```

Add these two new tests (anywhere after `setUp()`, e.g. right before the
`` `stop stops the recognizer and updates state` `` test):

```kotlin
    @Test
    fun `stop saves a history record with the final transcript and translation`() = runTest(dispatcher) {
        viewModel.start()
        recognizer.emit("hello world", isFinal = true)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.stop()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, historyRepository.addedRecords.size)
        val saved = historyRepository.addedRecords.single()
        assertEquals("English", saved.sourceLanguageName)
        assertEquals("Russian", saved.targetLanguageName)
        assertTrue(saved.sourceText.contains("hello world"))
        assertEquals("translated", saved.translatedText)
    }

    @Test
    fun `stop does not save a history record when nothing was said`() = runTest(dispatcher) {
        viewModel.start()

        viewModel.stop()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(historyRepository.addedRecords.isEmpty())
    }
```

- [ ] **Step 3: Run the tests, verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.petroff.transyncai.session.SessionViewModelTest" --console=plain`
Expected: FAIL to compile — `No value passed for parameter 'historyRepository'`
(the `SessionViewModel` constructor doesn't have that parameter yet).

- [ ] **Step 4: Wire `historyRepository` into `SessionViewModel`**

In `app/src/main/java/com/petroff/transyncai/session/SessionViewModel.kt`, add these
imports:

```kotlin
import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.history.HistoryRepository
import java.util.UUID
```

Change the constructor:

```kotlin
class SessionViewModel(
    private val speechRecognizer: SpeechRecognizer,
    private val translator: Translator,
    private val configRepository: ConfigRepository,
    private val historyRepository: HistoryRepository,
) : ViewModel() {
```

Change `stop()`:

```kotlin
    fun stop() {
        speechRecognizer.stop()
        translationQueue?.close()
        translationQueue = null
        saveHistoryRecordIfNeeded()
        _uiState.update { it.copy(isListening = false) }
    }

    private fun saveHistoryRecordIfNeeded() {
        val state = _uiState.value
        if (state.sourceText.isBlank() && state.translatedText.isBlank()) return
        val pair = configRepository.getConfig().selectedLanguagePair
        val record = ConversationRecord(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            title = null,
            sourceLanguageName = pair.sourceName,
            targetLanguageName = pair.targetName,
            sourceText = state.sourceText,
            translatedText = state.translatedText,
        )
        viewModelScope.launch { historyRepository.addRecord(record) }
    }
```

- [ ] **Step 5: Wire `historyRepository` through `SessionViewModelFactory` and `AppContainer`**

Replace the full content of
`app/src/main/java/com/petroff/transyncai/session/SessionViewModelFactory.kt`:

```kotlin
package com.petroff.transyncai.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.petroff.transyncai.AppContainer

class SessionViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return SessionViewModel(
            speechRecognizer = container.buildSpeechRecognizer(),
            translator = container.translator,
            configRepository = container.configRepository,
            historyRepository = container.historyRepository,
        ) as T
    }
}
```

In `app/src/main/java/com/petroff/transyncai/AppContainer.kt`, add these imports:

```kotlin
import androidx.room.Room
import com.petroff.transyncai.history.HistoryRepository
import com.petroff.transyncai.history.RoomHistoryRepository
import com.petroff.transyncai.history.TransyncDatabase
```

And add, right after the `private val httpClient = OkHttpClient()` line:

```kotlin
    private val database: TransyncDatabase = Room.databaseBuilder(
        context,
        TransyncDatabase::class.java,
        "transyncai.db",
    ).build()

    val historyRepository: HistoryRepository = RoomHistoryRepository(database.conversationRecordDao())
```

- [ ] **Step 6: Run the tests, verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.petroff.transyncai.session.SessionViewModelTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests (14 existing + 2 new = 16) pass.

- [ ] **Step 7: Run the full unit test suite to check for regressions**

Run: `./gradlew testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/session/SessionViewModel.kt app/src/main/java/com/petroff/transyncai/session/SessionViewModelFactory.kt app/src/main/java/com/petroff/transyncai/AppContainer.kt app/src/test/java/com/petroff/transyncai/session/FakeHistoryRepository.kt app/src/test/java/com/petroff/transyncai/session/SessionViewModelTest.kt
git commit -m "Save a history record when a session stops"
```

---

### Task 4: HistoryScreen (list + search + delete confirm)

**Files:**
- Create: `app/src/main/java/com/petroff/transyncai/ui/HistoryScreen.kt`
- Test: `app/src/androidTest/java/com/petroff/transyncai/ui/HistoryScreenTest.kt`

**Interfaces:**
- Consumes: `ConversationRecord`, `displayTitle()` (Task 2).
- Produces: `@Composable fun HistoryScreen(records: List<ConversationRecord>, searchQuery: String, onSearchQueryChange: (String) -> Unit, onRecordClick: (String) -> Unit, onDeleteRecord: (String) -> Unit, onBack: () -> Unit)`.

This is a purely presentational screen — it does not call `HistoryRepository` itself
(matching how `MainScreen`/`SettingsScreen` never call a repository directly; wiring
happens in `TransyncAiApp`, Task 6).

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/com/petroff/transyncai/ui/HistoryScreenTest.kt`:

```kotlin
package com.petroff.transyncai.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.petroff.transyncai.history.ConversationRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun record(id: String, title: String? = null) = ConversationRecord(
        id = id,
        timestamp = 1_700_000_000_000L,
        title = title,
        sourceLanguageName = "English",
        targetLanguageName = "Russian",
        sourceText = "hello world",
        translatedText = "привет мир",
    )

    @Test
    fun emptyListShowsEmptyState() {
        composeRule.setContent {
            HistoryScreen(
                records = emptyList(),
                searchQuery = "",
                onSearchQueryChange = {},
                onRecordClick = {},
                onDeleteRecord = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("No conversations yet.").assertExists()
    }

    @Test
    fun tappingARowInvokesOnRecordClickWithItsId() {
        var clickedId: String? = null
        composeRule.setContent {
            HistoryScreen(
                records = listOf(record(id = "abc", title = "My conversation")),
                searchQuery = "",
                onSearchQueryChange = {},
                onRecordClick = { clickedId = it },
                onDeleteRecord = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("My conversation").performClick()

        assertEquals("abc", clickedId)
    }

    @Test
    fun deleteRequiresConfirmationBeforeInvokingOnDeleteRecord() {
        var deletedId: String? = null
        composeRule.setContent {
            HistoryScreen(
                records = listOf(record(id = "abc", title = "My conversation")),
                searchQuery = "",
                onSearchQueryChange = {},
                onRecordClick = {},
                onDeleteRecord = { deletedId = it },
                onBack = {},
            )
        }

        composeRule.onNodeWithContentDescription("Delete conversation").performClick()
        assertEquals(null, deletedId)

        composeRule.onNodeWithText("Delete").performClick()

        assertEquals("abc", deletedId)
    }

    @Test
    fun typingInSearchInvokesOnSearchQueryChange() {
        var query = ""
        composeRule.setContent {
            HistoryScreen(
                records = emptyList(),
                searchQuery = query,
                onSearchQueryChange = { query = it },
                onRecordClick = {},
                onDeleteRecord = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Search").performTextInput("invoice")

        assertEquals("invoice", query)
    }
}
```

- [ ] **Step 2: Run the test, verify it fails to compile**

Run: `./gradlew compileDebugAndroidTestKotlin --console=plain`
Expected: FAIL — `Unresolved reference: HistoryScreen`.

- [ ] **Step 3: Write `HistoryScreen.kt`**

Create `app/src/main/java/com/petroff/transyncai/ui/HistoryScreen.kt`:

```kotlin
package com.petroff.transyncai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.history.displayTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    records: List<ConversationRecord>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onRecordClick: (String) -> Unit,
    onDeleteRecord: (String) -> Unit,
    onBack: () -> Unit,
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete this conversation?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteRecord(id)
                    pendingDeleteId = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (records.isEmpty()) {
                Text(
                    "No conversations yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(records, key = { it.id }) { record ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onRecordClick(record.id) },
                            ) {
                                Text(record.displayTitle(), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    record.translatedText.ifBlank { record.sourceText }.take(60),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { pendingDeleteId = record.id }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete conversation")
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.petroff.transyncai.ui.HistoryScreenTest --console=plain`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/ui/HistoryScreen.kt app/src/androidTest/java/com/petroff/transyncai/ui/HistoryScreenTest.kt
git commit -m "Add HistoryScreen (list, search, delete with confirmation)"
```

---

### Task 5: HistoryDetailScreen (view full conversation + editable title)

**Files:**
- Create: `app/src/main/java/com/petroff/transyncai/ui/HistoryDetailScreen.kt`
- Test: `app/src/androidTest/java/com/petroff/transyncai/ui/HistoryDetailScreenTest.kt`

**Interfaces:**
- Consumes: `ConversationRecord`, `displayTitle()` (Task 2).
- Produces: `@Composable fun HistoryDetailScreen(record: ConversationRecord, onSaveTitle: (String) -> Unit, onBack: () -> Unit)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/com/petroff/transyncai/ui/HistoryDetailScreenTest.kt`:

```kotlin
package com.petroff.transyncai.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.petroff.transyncai.history.ConversationRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val record = ConversationRecord(
        id = "abc",
        timestamp = 1_700_000_000_000L,
        title = "My conversation",
        sourceLanguageName = "English",
        targetLanguageName = "Russian",
        sourceText = "the full source transcript",
        translatedText = "полный исходный текст перевода",
    )

    @Test
    fun showsFullSourceAndTranslatedText() {
        composeRule.setContent {
            HistoryDetailScreen(record = record, onSaveTitle = {}, onBack = {})
        }

        composeRule.onNodeWithText("the full source transcript").assertExists()
        composeRule.onNodeWithText("полный исходный текст перевода").assertExists()
    }

    @Test
    fun editingTitleInvokesOnSaveTitleWithNewValue() {
        var saved: String? = null
        composeRule.setContent {
            HistoryDetailScreen(record = record, onSaveTitle = { saved = it }, onBack = {})
        }

        composeRule.onNodeWithContentDescription("Edit title").performClick()
        composeRule.onNodeWithTag("titleEditField").performTextReplacement("Renamed conversation")
        composeRule.onNodeWithText("Save").performClick()

        assertEquals("Renamed conversation", saved)
    }

    @Test
    fun untitledRecordShowsFallbackTitleInTopBar() {
        composeRule.setContent {
            HistoryDetailScreen(record = record.copy(title = null), onSaveTitle = {}, onBack = {})
        }

        composeRule.onNodeWithText("· English → Russian", substring = true).assertExists()
    }
}
```

(The `deleteRequiresConfirmationBeforeInvokingOnDeleteRecord`-style ambiguity risk
applies here too: with a title already set to "My conversation", the top bar shows that
exact text, and the edit dialog's `TextField` is pre-filled with it — querying by that
text after the dialog opens would match two nodes. `onNodeWithTag("titleEditField")`
targets the `TextField` unambiguously; see Step 3 below. Likewise the fallback-title test
asserts on `"· English → Russian"` — the `·` separator — rather than
`"English → Russian"` alone, because the screen's body also shows the language pair as
plain text without that separator.)

- [ ] **Step 2: Run the test, verify it fails to compile**

Run: `./gradlew compileDebugAndroidTestKotlin --console=plain`
Expected: FAIL — `Unresolved reference: HistoryDetailScreen`.

- [ ] **Step 3: Write `HistoryDetailScreen.kt`**

Create `app/src/main/java/com/petroff/transyncai/ui/HistoryDetailScreen.kt`:

```kotlin
package com.petroff.transyncai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.history.displayTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    record: ConversationRecord,
    onSaveTitle: (String) -> Unit,
    onBack: () -> Unit,
) {
    var showEditTitle by remember { mutableStateOf(false) }

    if (showEditTitle) {
        var titleField by remember { mutableStateOf(record.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showEditTitle = false },
            title = { Text("Rename conversation") },
            text = {
                TextField(
                    value = titleField,
                    onValueChange = { titleField = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth().testTag("titleEditField"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSaveTitle(titleField)
                    showEditTitle = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditTitle = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(record.displayTitle()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditTitle = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit title")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "${record.sourceLanguageName} → ${record.targetLanguageName}",
                style = MaterialTheme.typography.labelLarge,
            )

            Text("Source", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
            Text(record.sourceText, style = MaterialTheme.typography.bodyLarge)

            Text("Translation", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
            Text(record.translatedText, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.petroff.transyncai.ui.HistoryDetailScreenTest --console=plain`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/ui/HistoryDetailScreen.kt app/src/androidTest/java/com/petroff/transyncai/ui/HistoryDetailScreenTest.kt
git commit -m "Add HistoryDetailScreen (full conversation view + editable title)"
```

---

### Task 6: Navigation + History icon wiring

**Files:**
- Modify: `app/src/main/java/com/petroff/transyncai/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/petroff/transyncai/ui/TransyncAiApp.kt`
- Modify: `app/src/androidTest/java/com/petroff/transyncai/ui/MainScreenTest.kt`

**Interfaces:**
- Consumes: `HistoryScreen` (Task 4), `HistoryDetailScreen` (Task 5), `HistoryRepository`/`ConversationRecord` (Task 2), `container.historyRepository` (Task 3).
- Produces: `MainScreen(..., onOpenHistory: () -> Unit)` (new required param).

- [ ] **Step 1: Write the failing test**

In `app/src/main/java/com/petroff/transyncai/ui/MainScreenTest.kt`, add
`onOpenHistory = {}` to every existing `MainScreen(...)` call (all 12), and add one new
test. Replace the full file content:

```kotlin
package com.petroff.transyncai.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.session.SessionUiState
import com.petroff.transyncai.session.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startStopButtonTogglesLabel() {
        var listening = false
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = listening),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = { listening = !listening },
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithText("Start").assertExists()
    }

    @Test
    fun transcriptionOnlyModeHidesTranslationPanel() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(
                    viewMode = ViewMode.TRANSCRIPTION_ONLY,
                    sourceText = "hello world",
                    translatedText = "should not show",
                ),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithText("hello world").assertExists()
        composeRule.onNodeWithText("should not show").assertDoesNotExist()
    }

    @Test
    fun viewModeSwitchIsDisabledWhileListening() {
        // viewMode is pinned away from the BOTH default (which the other tests rely on)
        // because BOTH renders a "Translation" panel section header alongside the
        // "Translation" segmented-button label, making onNodeWithText("Translation")
        // ambiguous (2 matching nodes) and the assertion below fail regardless of which
        // node it happened to resolve to. TRANSCRIPTION_ONLY hides that header so the
        // segmented button is the only "Translation" node, without touching what's under
        // test: whether all three segmented buttons are disabled while listening.
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = true, viewMode = ViewMode.TRANSCRIPTION_ONLY),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithText("Both").assertIsNotEnabled()
        composeRule.onNodeWithText("Transcription").assertIsNotEnabled()
        composeRule.onNodeWithText("Translation").assertIsNotEnabled()
    }

    @Test
    fun viewModeSwitchIsEnabledWhenNotListening() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = false),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithText("Both").assertIsEnabled()
    }

    @Test
    fun contextButtonIsDisabledWhileListening() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = true),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithContentDescription("Context").assertIsNotEnabled()
    }

    @Test
    fun contextButtonIsHiddenInTranscriptionOnlyMode() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.TRANSCRIPTION_ONLY),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithContentDescription("Context").assertDoesNotExist()
    }

    @Test
    fun contextButtonOpensDialogPrefilledWithCurrentConversationContextAndSavingInvokesCallback() {
        var saved: String? = null
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = false),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "Existing conversation topic",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = { saved = it },
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithContentDescription("Context").performClick()
        composeRule.onNodeWithText("Existing conversation topic").assertExists()
        composeRule.onNodeWithText("Existing conversation topic").performTextReplacement("New conversation topic")
        composeRule.onNodeWithText("Save").performClick()

        assertEquals("New conversation topic", saved)
    }

    @Test
    fun tappingBothWithoutApiKeyRedirectsInsteadOfSwitchingMode() {
        var viewModeChanged = false
        var redirected = false
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.TRANSCRIPTION_ONLY),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                hasApiKey = false,
                onToggleListening = {},
                onViewModeChange = { viewModeChanged = true },
                onOpenSettings = {},
                onSaveConversationContext = {},
                onApiKeyRequired = { redirected = true },
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithText("Both").performClick()

        assert(redirected)
        assert(!viewModeChanged)
    }

    @Test
    fun tappingTranscriptionWorksNormallyEvenWithoutApiKey() {
        var newMode: ViewMode? = null
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.BOTH),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                hasApiKey = false,
                onToggleListening = {},
                onViewModeChange = { newMode = it },
                onOpenSettings = {},
                onSaveConversationContext = {},
                onApiKeyRequired = {},
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithText("Transcription").performClick()

        assertEquals(ViewMode.TRANSCRIPTION_ONLY, newMode)
    }

    @Test
    fun bothAndTranslationSwitchModeNormallyOnceApiKeyIsSet() {
        var newMode: ViewMode? = null
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.TRANSCRIPTION_ONLY),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                hasApiKey = true,
                onToggleListening = {},
                onViewModeChange = { newMode = it },
                onOpenSettings = {},
                onSaveConversationContext = {},
                onApiKeyRequired = { throw AssertionError("should not redirect when a key is set") },
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithText("Both").performClick()

        assertEquals(ViewMode.BOTH, newMode)
    }

    @Test
    fun dividerIsShownBetweenPanelsOnlyInBothMode() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.BOTH),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithTag("sourceTranslationDivider").assertExists()
    }

    @Test
    fun dividerIsNotShownInTranscriptionOnlyMode() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.TRANSCRIPTION_ONLY),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onOpenHistory = {},
            )
        }

        composeRule.onNodeWithTag("sourceTranslationDivider").assertDoesNotExist()
    }

    @Test
    fun historyIconInvokesOnOpenHistoryWhenTapped() {
        var opened = false
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = false),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onOpenHistory = { opened = true },
            )
        }

        composeRule.onNodeWithContentDescription("History").performClick()

        assert(opened)
    }
}
```

- [ ] **Step 2: Run the test, verify it fails to compile**

Run: `./gradlew compileDebugAndroidTestKotlin --console=plain`
Expected: FAIL — `No parameter with name 'onOpenHistory' found` (repeated across the file).

- [ ] **Step 3: Add the History icon to `MainScreen`**

In `app/src/main/java/com/petroff/transyncai/ui/MainScreen.kt`, add this import next to
the other icon imports:

```kotlin
import androidx.compose.material.icons.filled.History
```

Add `onOpenHistory: () -> Unit` to the function signature, right after `onOpenSettings`:

```kotlin
fun MainScreen(
    uiState: SessionUiState,
    currentLanguagePair: LanguagePair,
    currentConversationContext: String,
    onToggleListening: () -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onSaveConversationContext: (String) -> Unit,
    hasApiKey: Boolean = true,
    onApiKeyRequired: () -> Unit = {},
) {
```

Add the History icon to the `TopAppBar`'s `actions`, between the Context icon and the
Settings icon:

```kotlin
                actions = {
                    // Hidden when there's no translation panel to provide context for,
                    // disabled while listening (same rule as the view-mode switch below:
                    // context only takes effect at the start of a session).
                    if (uiState.viewMode != ViewMode.TRANSCRIPTION_ONLY) {
                        IconButton(onClick = { showContextDialog = true }, enabled = !uiState.isListening) {
                            Icon(Icons.AutoMirrored.Filled.SpeakerNotes, contentDescription = "Context")
                        }
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "History")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
```

- [ ] **Step 4: Run the `MainScreenTest` test, verify it passes**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.petroff.transyncai.ui.MainScreenTest --console=plain`
Expected: `BUILD SUCCESSFUL`, 13 tests passed.

- [ ] **Step 5: Wire navigation and repository calls into `TransyncAiApp`**

Replace the full content of
`app/src/main/java/com/petroff/transyncai/ui/TransyncAiApp.kt`:

```kotlin
package com.petroff.transyncai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.petroff.transyncai.AppContainer
import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.session.SessionViewModel
import com.petroff.transyncai.session.SessionViewModelFactory
import kotlinx.coroutines.launch

@Composable
fun TransyncAiApp(container: AppContainer) {
    MaterialTheme {
        Surface {
            RequiresRecordAudioPermission {
                val navController = rememberNavController()
                val coroutineScope = rememberCoroutineScope()
                val sessionViewModel: SessionViewModel = viewModel(
                    factory = SessionViewModelFactory(container)
                )
                val uiState by sessionViewModel.uiState.collectAsState()

                // Re-read config whenever the visible screen changes, so edits made in
                // Settings (API key, buffer size) are picked up as soon as the user
                // navigates back to Main, without needing ConfigRepository to be reactive.
                // Also nudge the ViewModel's own canStart, which ConfigRepository changes
                // don't otherwise propagate into (canStart only recomputes on init/setViewMode).
                val backStackEntry by navController.currentBackStackEntryAsState()
                var config by remember { mutableStateOf(container.configRepository.getConfig()) }
                var historyRecords by remember { mutableStateOf<List<ConversationRecord>>(emptyList()) }
                var historySearchQuery by remember { mutableStateOf("") }
                LaunchedEffect(backStackEntry) {
                    config = container.configRepository.getConfig()
                    sessionViewModel.refreshCanStart()
                    // Reload fresh (and reset any leftover search) every time History's list
                    // screen is (re-)entered — covers both "just navigated here" and "came
                    // back from a detail screen after a rename."
                    if (backStackEntry?.destination?.route == "history") {
                        historySearchQuery = ""
                        historyRecords = container.historyRepository.getRecords()
                    }
                }

                // Both navigation paths into Settings set this explicitly (true from a blocked
                // view-mode tab, false from the gear icon) so it always reflects how the most
                // recent navigation got here, rather than needing an explicit reset somewhere.
                var focusApiKeyOnSettingsOpen by remember { mutableStateOf(false) }

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            uiState = uiState,
                            currentLanguagePair = config.selectedLanguagePair,
                            currentConversationContext = config.conversationContext,
                            onToggleListening = {
                                if (uiState.isListening) sessionViewModel.stop() else sessionViewModel.start()
                            },
                            onViewModeChange = sessionViewModel::setViewMode,
                            onOpenSettings = {
                                focusApiKeyOnSettingsOpen = false
                                navController.navigate("settings")
                            },
                            onOpenHistory = { navController.navigate("history") },
                            onSaveConversationContext = {
                                container.configRepository.setConversationContext(it)
                                // No navigation happens here (unlike Settings), so refresh the
                                // locally held config directly — otherwise re-opening the
                                // Context dialog in the same session would show stale text.
                                config = container.configRepository.getConfig()
                            },
                            hasApiKey = !config.apiKey.isNullOrBlank(),
                            onApiKeyRequired = {
                                focusApiKeyOnSettingsOpen = true
                                navController.navigate("settings")
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            currentApiKey = config.apiKey,
                            currentBufferSize = config.bufferSize,
                            currentChunkSizeWords = config.chunkSizeWords,
                            currentGeneralContext = config.generalContext,
                            languagePairs = config.languagePairs,
                            selectedLanguagePairIndex = config.selectedLanguagePairIndex,
                            onSelectLanguagePair = { container.configRepository.setSelectedLanguagePairIndex(it) },
                            onSaveApiKey = { container.configRepository.setApiKey(it) },
                            onSaveBufferSize = { container.configRepository.setBufferSize(it) },
                            onSaveChunkSizeWords = { container.configRepository.setChunkSizeWords(it) },
                            onSaveGeneralContext = { container.configRepository.setGeneralContext(it) },
                            onBack = { navController.popBackStack() },
                            autoFocusApiKey = focusApiKeyOnSettingsOpen,
                        )
                    }
                    composable("history") {
                        HistoryScreen(
                            records = historyRecords,
                            searchQuery = historySearchQuery,
                            onSearchQueryChange = { query ->
                                historySearchQuery = query
                                coroutineScope.launch {
                                    historyRecords = if (query.isBlank()) {
                                        container.historyRepository.getRecords()
                                    } else {
                                        container.historyRepository.search(query)
                                    }
                                }
                            },
                            onRecordClick = { id -> navController.navigate("history/$id") },
                            onDeleteRecord = { id ->
                                coroutineScope.launch {
                                    container.historyRepository.deleteRecord(id)
                                    historyRecords = historyRecords.filterNot { it.id == id }
                                }
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = "history/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { detailBackStackEntry ->
                        val id = detailBackStackEntry.arguments?.getString("id")
                        val record = historyRecords.find { it.id == id }
                        if (record != null) {
                            HistoryDetailScreen(
                                record = record,
                                onSaveTitle = { newTitle ->
                                    coroutineScope.launch {
                                        container.historyRepository.setTitle(record.id, newTitle)
                                        historyRecords = historyRecords.map {
                                            if (it.id == record.id) it.copy(title = newTitle) else it
                                        }
                                    }
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Build the app to verify it compiles**

Run: `./gradlew assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the full unit and instrumented test suites**

Run: `./gradlew testDebugUnitTest connectedDebugAndroidTest --console=plain`
Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/petroff/transyncai/ui/MainScreen.kt app/src/main/java/com/petroff/transyncai/ui/TransyncAiApp.kt app/src/androidTest/java/com/petroff/transyncai/ui/MainScreenTest.kt
git commit -m "Wire conversation history navigation and repository into the app"
```
