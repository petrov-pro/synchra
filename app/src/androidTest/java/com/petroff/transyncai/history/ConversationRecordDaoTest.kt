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
