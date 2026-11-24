package com.petroff.synchra.history

import androidx.lifecycle.viewModelScope
import com.petroff.synchra.config.ConfigRepository
import com.petroff.synchra.config.DefaultConfigRepository
import com.petroff.synchra.config.FakeSettingsStore
import com.petroff.synchra.session.FakeHistoryRepository
import com.petroff.synchra.summary.FakeSummarizer
import com.petroff.synchra.summary.SummaryException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var history: FakeHistoryRepository
    private lateinit var config: ConfigRepository

    private fun record(summary: String? = null, title: String? = null) = ConversationRecord(
        id = "r1",
        timestamp = 1_000L,
        title = title,
        sourceLanguageName = "English",
        targetLanguageName = "Russian",
        sourceText = "We agreed on the deadline.",
        translatedText = "Мы согласовали срок.",
        summary = summary,
    )

    // App-wide summary work outlives any one conversation screen.
    private val appScope = CoroutineScope(SupervisorJob() + dispatcher)

    private fun jobs(summarizer: FakeSummarizer) = SummaryJobs(appScope, history, summarizer)

    private fun viewModel(summarizer: FakeSummarizer, summarizeOnOpen: Boolean = false) =
        viewModel(jobs(summarizer), summarizeOnOpen)

    private fun viewModel(jobs: SummaryJobs, summarizeOnOpen: Boolean = false) =
        ConversationViewModel("r1", history, jobs, config, summarizeOnOpen).also {
            dispatcher.scheduler.advanceUntilIdle()
        }

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        history = FakeHistoryRepository()
        config = DefaultConfigRepository(FakeSettingsStore(), "https://api.anthropic.com")
        config.setApiKey("sk-ant")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `record without summary opens on the original`() = runTest(dispatcher) {
        history.addRecord(record())

        val vm = viewModel(FakeSummarizer(mutableListOf()))

        assertEquals("r1", vm.uiState.value.record!!.id)
        assertEquals(ConversationView.ORIGINAL, vm.uiState.value.view)
    }

    @Test
    fun `record with summary opens on the summary and Summary never calls Claude`() = runTest(dispatcher) {
        history.addRecord(record(summary = "S", title = "T"))
        val summarizer = FakeSummarizer(mutableListOf())
        val vm = viewModel(summarizer)
        assertEquals(ConversationView.SUMMARY, vm.uiState.value.view)

        vm.onOriginalClick()
        assertEquals(ConversationView.ORIGINAL, vm.uiState.value.view)
        vm.onSummaryClick()
        advanceUntilIdle()

        assertEquals(ConversationView.SUMMARY, vm.uiState.value.view)
        assertEquals(0, summarizer.calls)
    }

    @Test
    fun `summary success is saved and shown`() = runTest(dispatcher) {
        history.addRecord(record())
        val vm = viewModel(FakeSummarizer(mutableListOf(FakeSummarizer.ok())))

        vm.onSummaryClick()
        advanceUntilIdle()

        val saved = history.getRecord("r1")!!
        assertEquals("Встреча", saved.title)
        assertEquals("Обсудили сроки.", saved.summary)
        assertEquals(ConversationView.SUMMARY, vm.uiState.value.view)
        assertEquals("Встреча", vm.uiState.value.record!!.displayTitle())
        assertFalse(vm.uiState.value.isSummarizing)
    }

    @Test
    fun `two failures then success is a success`() = runTest(dispatcher) {
        history.addRecord(record())
        val summarizer = FakeSummarizer(mutableListOf(FakeSummarizer.fail(), FakeSummarizer.fail(), FakeSummarizer.ok()))
        val vm = viewModel(summarizer)

        vm.onSummaryClick()
        advanceUntilIdle()

        assertEquals(3, summarizer.calls)
        assertNull(vm.uiState.value.errorMessage)
        assertEquals("Обсудили сроки.", history.getRecord("r1")!!.summary)
    }

    @Test
    fun `three failures show the error and save nothing`() = runTest(dispatcher) {
        history.addRecord(record())
        val summarizer = FakeSummarizer(MutableList(3) { FakeSummarizer.fail() })
        val vm = viewModel(summarizer)

        vm.onSummaryClick()
        advanceUntilIdle()

        assertEquals(3, summarizer.calls)
        assertEquals("Couldn't make the summary. Please try again later.", vm.uiState.value.errorMessage)
        assertEquals(ConversationView.ORIGINAL, vm.uiState.value.view)
        assertNull(history.getRecord("r1")!!.summary)
        assertFalse(vm.uiState.value.closeOnErrorDismiss)
        vm.dismissError()
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `summarizeOnOpen summarizes right away and a failure asks to close on dismiss`() = runTest(dispatcher) {
        history.addRecord(record())
        val summarizer = FakeSummarizer(MutableList(3) { FakeSummarizer.fail() })

        val vm = viewModel(summarizer, summarizeOnOpen = true)

        assertEquals(3, summarizer.calls)
        assertTrue(vm.uiState.value.closeOnErrorDismiss)
    }

    @Test
    fun `missing Anthropic key shows the Settings message without calling Claude`() = runTest(dispatcher) {
        config.setApiKey("")
        history.addRecord(record())
        val summarizer = FakeSummarizer(mutableListOf())
        val vm = viewModel(summarizer)

        vm.onSummaryClick()
        advanceUntilIdle()

        assertEquals(0, summarizer.calls)
        assertEquals("Set your Anthropic API key in Settings to make summaries.", vm.uiState.value.errorMessage)
    }

    @Test
    fun `empty balance is not retried and shows the top-up message`() = runTest(dispatcher) {
        history.addRecord(record())
        val summarizer = FakeSummarizer(mutableListOf(FakeSummarizer.fail(SummaryException.Kind.OUT_OF_CREDIT)))
        val vm = viewModel(summarizer)

        vm.onSummaryClick()
        advanceUntilIdle()

        assertEquals(1, summarizer.calls)
        assertEquals("Your Anthropic balance is empty. Top it up at console.anthropic.com.", vm.uiState.value.errorMessage)
    }

    @Test
    fun `invalid key is not retried`() = runTest(dispatcher) {
        history.addRecord(record())
        val summarizer = FakeSummarizer(mutableListOf(FakeSummarizer.fail(SummaryException.Kind.INVALID_KEY)))
        val vm = viewModel(summarizer)

        vm.onSummaryClick()
        advanceUntilIdle()

        assertEquals(1, summarizer.calls)
        assertEquals("Your Anthropic API key is invalid. Check it in Settings.", vm.uiState.value.errorMessage)
    }

    @Test
    fun `second tap while summarizing is ignored`() = runTest(dispatcher) {
        history.addRecord(record())
        val summarizer = FakeSummarizer(mutableListOf(FakeSummarizer.ok())).apply { gate = CompletableDeferred() }
        val vm = viewModel(summarizer)

        vm.onSummaryClick()
        advanceUntilIdle()
        vm.onSummaryClick()
        advanceUntilIdle()

        assertEquals(1, summarizer.calls)
        assertTrue(vm.uiState.value.isSummarizing)
        summarizer.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSummarizing)
    }

    @Test
    fun `leaving the screen mid-summary still saves the paid-for summary`() = runTest(dispatcher) {
        history.addRecord(record())
        val summarizer = FakeSummarizer(mutableListOf(FakeSummarizer.ok())).apply { gate = CompletableDeferred() }
        val vm = viewModel(summarizer)

        vm.onSummaryClick()
        advanceUntilIdle()
        vm.viewModelScope.cancel() // the screen is gone
        summarizer.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals("Обсудили сроки.", history.getRecord("r1")!!.summary)
    }

    @Test
    fun `reopening during a summary waits for it instead of calling Claude again`() = runTest(dispatcher) {
        history.addRecord(record())
        val summarizer = FakeSummarizer(mutableListOf(FakeSummarizer.ok())).apply { gate = CompletableDeferred() }
        val jobs = jobs(summarizer)
        val first = viewModel(jobs)
        first.onSummaryClick()
        advanceUntilIdle()
        first.viewModelScope.cancel()

        val second = viewModel(jobs)
        assertTrue(second.uiState.value.isSummarizing)
        second.onSummaryClick()
        summarizer.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, summarizer.calls)
        assertEquals(ConversationView.SUMMARY, second.uiState.value.view)
        assertFalse(second.uiState.value.isSummarizing)
    }

    @Test
    fun `retries wait a moment so a briefly overloaded Claude can recover`() = runTest(dispatcher) {
        history.addRecord(record())
        val summarizer = FakeSummarizer(mutableListOf(FakeSummarizer.fail(), FakeSummarizer.ok()))
        val vm = viewModel(summarizer)

        vm.onSummaryClick()
        runCurrent()
        assertEquals(1, summarizer.calls)
        advanceTimeBy(SummaryJobs.RETRY_DELAY_MS - 1)
        runCurrent()
        assertEquals(1, summarizer.calls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, summarizer.calls)
    }

    @Test
    fun `the summary uses the context recorded with the conversation`() = runTest(dispatcher) {
        history.addRecord(record().copy(userContext = "I'm a cardiologist", context = "Cardiology lecture"))
        val summarizer = FakeSummarizer(mutableListOf(FakeSummarizer.ok()))
        val vm = viewModel(summarizer)

        vm.onSummaryClick()
        advanceUntilIdle()

        assertEquals("I'm a cardiologist", summarizer.lastUserContext)
        assertEquals("Cardiology lecture", summarizer.lastContext)
    }
}
