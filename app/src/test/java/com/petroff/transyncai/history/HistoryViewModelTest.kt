package com.petroff.transyncai.history

import com.petroff.transyncai.session.FakeHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var history: FakeHistoryRepository

    private fun record(id: String, text: String) = ConversationRecord(
        id = id, timestamp = id.toLong(), title = null, sourceLanguageName = "English",
        targetLanguageName = "Russian", sourceText = text, translatedText = "",
    )

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        history = FakeHistoryRepository()
        // FakeHistoryRepository keeps the newest added first, like the real one.
        history.addRecord(record("1", "about the invoice"))
        history.addRecord(record("2", "about the weather"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh loads oldest first by default`() = runTest(dispatcher) {
        val vm = HistoryViewModel(history)

        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf("1", "2"), vm.records.value.map { it.id })
        assertEquals(false, vm.newestFirst.value)
    }

    @Test
    fun `toggleOrder switches to newest first and back`() = runTest(dispatcher) {
        val vm = HistoryViewModel(history)
        vm.refresh()
        advanceUntilIdle()

        vm.toggleOrder()
        advanceUntilIdle()
        assertEquals(listOf("2", "1"), vm.records.value.map { it.id })
        assertEquals(true, vm.newestFirst.value)

        vm.toggleOrder()
        advanceUntilIdle()
        assertEquals(listOf("1", "2"), vm.records.value.map { it.id })
    }

    @Test
    fun `search filters and blank search shows all`() = runTest(dispatcher) {
        val vm = HistoryViewModel(history)

        vm.setSearchQuery("invoice")
        advanceUntilIdle()
        assertEquals(listOf("1"), vm.records.value.map { it.id })
        assertEquals("invoice", vm.searchQuery.value)

        vm.setSearchQuery("")
        advanceUntilIdle()
        assertEquals(listOf("1", "2"), vm.records.value.map { it.id })
    }

    @Test
    fun `delete removes the record`() = runTest(dispatcher) {
        val vm = HistoryViewModel(history)

        vm.delete("1")
        advanceUntilIdle()

        assertEquals(listOf("2"), vm.records.value.map { it.id })
    }
}
