# TransyncAI — Conversation History — Design

Date: 2026-08-31

## Goal

Persist every finished conversation (a Start→Stop session) so the customer can browse,
search, review in full, delete, and title past conversations — surfaced from a new
History icon on the Main screen, next to Settings.

## Non-goals

- No retention cap: every finished conversation is kept forever (an explicit choice —
  see "Storage" below for the tradeoff this implies).
- No editing of the transcript/translation text itself — only the title is editable.
- No export/share of history records.
- No syncing history across devices.

## Architecture

```text
SessionViewModel.stop()
    ↓ (if sourceText or translatedText is non-blank)
ConversationRecord (domain model)
    ↓
HistoryRepository.addRecord()
    ↓
RoomHistoryRepository
    ↓
ConversationRecordDao  ──►  Room  ──►  SQLite (transyncai.db, on-device, unencrypted)

MainScreen (History icon)
    ↓ navigate("history")
HistoryScreen (search box + list, newest-first)
    ↓ navigate("history/{id}")
HistoryDetailScreen (editable title, full source + translated text)
```

## Storage: Room

**Chosen over the two alternatives considered:**

1. **Extend the existing encrypted `SettingsStore`** (JSON blob under one key) — simplest,
   zero new dependencies, stays encrypted like the API key — but a poor fit once search
   and unbounded growth are real requirements: every search or write means
   deserializing/reserializing the entire history into memory, and that cost grows
   without bound as history accumulates forever.
2. **Plain JSON file on disk** — same growth problem as #1, and worse: no encryption at
   all.
3. **Room/SQLite (chosen)** — indexed, bounded-cost search via `LIKE`, no full-blob
   rewrite per save, standard idiomatic approach once the data is unbounded and
   queryable rather than a small fixed-size settings blob.

**Explicit tradeoff accepted by this choice:** unlike the API key
(`EncryptedSharedPreferences`), Room/SQLite here stores conversation text **unencrypted
on disk**. Real encryption would require SQLCipher, a further dependency not included in
this design. If the phone is lost/compromised, past conversation content is readable.
This was raised and knowingly accepted in favor of Room's search/scale properties —
flagged here for the record, not silently dropped. Adding SQLCipher later is a
follow-up, not a blocker for this design.

**New build dependency:** this project has no annotation-processor plugin configured yet.
Room's compiler requires one — the KSP (Kotlin Symbol Processing) plugin, version-paired
to the project's Kotlin version (2.3.20). This is a real, visible build-config change
(`build.gradle.kts` at both root and `app/` level), not just a new library import.

## Data model

Two layers, kept separate — matching the existing pattern of `SettingsStore` (raw
storage) vs. `ConfigRepository`/`AppConfig` (domain), so Room specifics don't leak into
the ViewModel/UI layer or the fast JVM unit-test suite:

```kotlin
// Persistence layer (Room)
@Entity(tableName = "conversation_records")
data class ConversationRecordEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val title: String?,              // null until the customer sets one
    val sourceLanguageName: String,
    val targetLanguageName: String,
    val sourceText: String,
    val translatedText: String,
)

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

@Database(entities = [ConversationRecordEntity::class], version = 1)
abstract class TransyncDatabase : RoomDatabase() {
    abstract fun conversationRecordDao(): ConversationRecordDao
}
```

```kotlin
// Domain layer (what SessionViewModel and the UI actually see)
data class ConversationRecord(
    val id: String,
    val timestamp: Long,
    val title: String?,
    val sourceLanguageName: String,
    val targetLanguageName: String,
    val sourceText: String,
    val translatedText: String,
)

interface HistoryRepository {
    suspend fun getRecords(): List<ConversationRecord>
    suspend fun search(query: String): List<ConversationRecord>
    suspend fun addRecord(record: ConversationRecord)
    suspend fun deleteRecord(id: String)
    suspend fun setTitle(id: String, title: String)
}
```

`RoomHistoryRepository(dao: ConversationRecordDao)` implements `HistoryRepository`,
mapping `ConversationRecordEntity` ⇄ `ConversationRecord`. Built in `AppContainer` via
`Room.databaseBuilder(context, TransyncDatabase::class.java, "transyncai.db").build()`.

**Display title fallback:** when `title` is null/blank, both the list and detail screens
compute `"<formatted timestamp> · <sourceLanguageName> → <targetLanguageName>"` (e.g.
"Aug 31, 23:20 · English → Russian") rather than storing a generated title — keeps the
stored value an honest signal of "customer set this" vs. not. Uses `java.time`
(`Instant`/`ZonedDateTime`/`DateTimeFormatter`), available natively at minSdk 26 with no
desugaring needed.

## Session wiring

`SessionViewModel.stop()` (after stopping the recognizer and closing the translation
queue) builds a `ConversationRecord` from the final `sourceText`/`translatedText` and the
session's language pair, and saves it via
`viewModelScope.launch { historyRepository.addRecord(record) }`:

- `id`: `UUID.randomUUID().toString()`
- `timestamp`: `System.currentTimeMillis()`
- `title`: `null`
- Skipped entirely if both `sourceText` and `translatedText` are blank (an immediate
  Start→Stop with no speech shouldn't create a junk entry).

`SessionViewModel` gains a `historyRepository: HistoryRepository` constructor parameter,
wired through `SessionViewModelFactory`/`AppContainer` alongside the existing
`translator`/`configRepository`.

## UI

**MainScreen top bar** — a History icon (`Icons.Filled.History` or the AutoMirrored
variant if one exists) added to the `TopAppBar` actions, ordered Context → History →
Settings.

**HistoryScreen** (route `"history"`):
- Search `TextField` at the top; re-queries `historyRepository.search(query)` on each
  change (empty query → `getRecords()`).
- `LazyColumn` below, newest-first. Each row: title (or the computed fallback) + a
  one-line preview (e.g. first ~60 chars of `translatedText`, falling back to
  `sourceText` if blank) + a delete `IconButton`.
- Tapping a row (outside the delete icon) navigates to `"history/{id}"`.
- Tapping delete opens a confirm `AlertDialog` ("Delete this conversation?" /
  Cancel/Delete) before calling `historyRepository.deleteRecord(id)` and refreshing the
  list.
- Empty state: centered "No conversations yet." text when the (unfiltered) list is
  empty.

**HistoryDetailScreen** (route `"history/{id}"`):
- Top bar: back arrow, and the title — tapping it turns it into an editable `TextField`
  (or opens a small rename dialog; implementer's call, consistent with the app's
  existing dialog patterns e.g. the Context popup) that saves via
  `historyRepository.setTitle(id, newTitle)` on confirm.
- Below: timestamp + language pair, then a "Source" section and a "Translation" section,
  each showing the full text in a scrollable area (static text — no auto-scroll-to-latest
  behavior needed here, unlike `MainScreen`'s live panels, since the content is finished
  and fixed).
- `TransyncAiApp` looks up the record by the `id` nav argument from a locally held,
  refreshed-on-navigation list (same pattern already used for `config` in
  `TransyncAiApp`), rather than each screen holding its own Room dependency.

## Testing

Room requires a real SQLite engine — unavailable in this project's plain JVM unit tests
(no Robolectric configured, and none is being added for this). Split accordingly:

- **`RoomHistoryRepository`/`ConversationRecordDao`**: instrumented tests (`androidTest`),
  using `Room.inMemoryDatabaseBuilder(...)` — same rationale as
  `SherpaOnnxAsrEngineInstrumentedTest` already testing a real Android-dependent
  component instrumented rather than as a JVM test.
- **`SessionViewModel`**: stays a fast JVM unit test, against a new
  `FakeHistoryRepository` (in-memory `MutableList`), matching the existing
  `FakeTranslator`/`FakeSpeechRecognizer`/`FakeSettingsStore` pattern — Room never enters
  this test's dependency graph.
- **`HistoryScreen`/`HistoryDetailScreen`**: instrumented Compose UI tests, same pattern
  as `MainScreenTest`/`SettingsScreenTest` (`createComposeRule()`, fakes/lambdas for all
  callbacks — no real Room dependency needed at this layer either, since the screens
  only see the domain `ConversationRecord` list and callbacks).

## Open items for the implementation plan

- Exact KSP version pairing for Kotlin 2.3.20, and the Room version to pin.
- Whether title-editing is an inline `TextField` swap or a small `AlertDialog` (either is
  consistent with the app's existing patterns; implementer's call, not a product
  decision).
- Icon choice for History (verify availability in the already-included
  `material-icons-extended` artifact, same verification step used for the Context and
  Settings-help icons).
