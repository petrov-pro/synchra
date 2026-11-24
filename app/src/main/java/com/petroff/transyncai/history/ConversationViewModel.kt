package com.petroff.transyncai.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.session.ViewMode
import com.petroff.transyncai.summary.ConversationSummary
import com.petroff.transyncai.summary.SummaryException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConversationView { SUMMARY, ORIGINAL }

data class ConversationUiState(
    val record: ConversationRecord? = null,
    val view: ConversationView = ConversationView.ORIGINAL,
    val viewMode: ViewMode = ViewMode.BOTH,
    val isSummarizing: Boolean = false,
    val errorMessage: String? = null,
    // Summary was requested from the main screen: after its error pop-up, go back there.
    val closeOnErrorDismiss: Boolean = false,
)

/** One saved conversation: its original text, and its Claude summary (made at most once). */
class ConversationViewModel(
    private val recordId: String,
    private val historyRepository: HistoryRepository,
    private val summaryJobs: SummaryJobs,
    private val configRepository: ConfigRepository,
    private val summarizeOnOpen: Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val record = historyRepository.getRecord(recordId)
            _uiState.update {
                it.copy(
                    record = record,
                    view = if (record?.summary != null) ConversationView.SUMMARY else ConversationView.ORIGINAL,
                )
            }
            // A summary started on an earlier visit of this screen may still be on its way.
            val running = summaryJobs.inProgress(recordId)
            when {
                running != null && record != null -> awaitSummary(record, running)
                summarizeOnOpen -> onSummaryClick()
            }
        }
    }

    fun onSummaryClick() {
        val state = _uiState.value
        val record = state.record ?: return
        if (record.summary != null) {
            _uiState.update { it.copy(view = ConversationView.SUMMARY) }
            return
        }
        if (state.isSummarizing) return
        if (configRepository.getConfig().apiKey.isNullOrBlank()) {
            showError(MISSING_KEY_MESSAGE)
            return
        }
        awaitSummary(record, summaryJobs.summarize(record))
    }

    private fun awaitSummary(record: ConversationRecord, job: Deferred<Result<ConversationSummary>>) {
        _uiState.update { it.copy(isSummarizing = true) }
        viewModelScope.launch {
            job.await().onSuccess { summary ->
                _uiState.update {
                    it.copy(
                        record = record.copy(title = summary.title, summary = summary.summary),
                        view = ConversationView.SUMMARY,
                        isSummarizing = false,
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isSummarizing = false) }
                showError(messageFor(error))
            }
        }
    }

    fun onOriginalClick() {
        _uiState.update { it.copy(view = ConversationView.ORIGINAL) }
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message, closeOnErrorDismiss = summarizeOnOpen) }
    }

    private fun messageFor(error: Throwable): String = when ((error as? SummaryException)?.kind) {
        SummaryException.Kind.OUT_OF_CREDIT -> "Your Anthropic balance is empty. Top it up at console.anthropic.com."
        SummaryException.Kind.INVALID_KEY -> "Your Anthropic API key is invalid. Check it in Settings."
        else -> "Couldn't make the summary. Please try again later."
    }

    companion object {
        private const val MISSING_KEY_MESSAGE = "Set your Anthropic API key in Settings to make summaries."
    }
}
