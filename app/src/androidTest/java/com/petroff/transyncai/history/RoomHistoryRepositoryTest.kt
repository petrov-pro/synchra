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
        assertEquals(null, loaded.summary)
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
    fun setSummaryIsReturnedByGetRecordAndGetRecords() = runBlocking {
        repository.addRecord(record(id = "1", title = null))

        repository.setSummary("1", "Встреча", "Обсудили сроки.")

        val byId = repository.getRecord("1")!!
        assertEquals("Встреча", byId.title)
        assertEquals("Обсудили сроки.", byId.summary)
        assertEquals(byId, repository.getRecords().single())
    }

    @Test
    fun searchIgnoresCaseInRussianToo() = runBlocking {
        repository.addRecord(record(id = "1").copy(translatedText = "Встреча с Командой"))
        repository.addRecord(record(id = "2").copy(sourceText = "Meeting with the Team", translatedText = "ничего"))

        assertEquals(listOf("1"), repository.search("встреча с команд").map { it.id })
        assertEquals(listOf("1"), repository.search("ВСТРЕЧА").map { it.id })
        assertEquals(listOf("2"), repository.search("meeting WITH").map { it.id })
    }

    @Test
    fun searchTreatsPercentAndUnderscoreAsPlainCharacters() = runBlocking {
        repository.addRecord(record(id = "1").copy(sourceText = "a 50% discount"))
        repository.addRecord(record(id = "2").copy(sourceText = "500 offers"))
        repository.addRecord(record(id = "3").copy(sourceText = "the user_id field"))
        repository.addRecord(record(id = "4").copy(sourceText = "the userXid field"))
        repository.addRecord(record(id = "5").copy(sourceText = "C:\\temp folder"))

        assertEquals(listOf("1"), repository.search("50%").map { it.id })
        assertEquals(listOf("3"), repository.search("user_id").map { it.id })
        assertEquals(listOf("5"), repository.search("C:\\temp").map { it.id })
    }

    @Test
    fun contextIsStoredWithTheConversation() = runBlocking {
        repository.addRecord(record(id = "1").copy(context = "Cardiology lecture"))

        assertEquals("Cardiology lecture", repository.getRecord("1")!!.context)
    }
}

