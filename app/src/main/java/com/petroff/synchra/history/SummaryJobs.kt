package com.petroff.synchra.history

import com.petroff.synchra.summary.ConversationSummary
import com.petroff.synchra.summary.Summarizer
import com.petroff.synchra.summary.SummaryException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

/**
 * Summary requests that belong to the app, not to a screen: leaving the conversation screen
 * doesn't throw away a summary Claude is already making (and billing), and reopening it joins
 * the request in flight instead of paying for a second one. The result is saved on success.
 */
class SummaryJobs(
    private val scope: CoroutineScope,
    private val historyRepository: HistoryRepository,
    private val summarizer: Summarizer,
) {
    private val inFlight = mutableMapOf<String, Deferred<Result<ConversationSummary>>>()

    @Synchronized
    fun inProgress(recordId: String): Deferred<Result<ConversationSummary>>? = inFlight[recordId]

    @Synchronized
    fun summarize(record: ConversationRecord): Deferred<Result<ConversationSummary>> =
        inFlight.getOrPut(record.id) {
            scope.async {
                val result = runCatching {
                    summarizeWithRetries(record).onSuccess {
                        historyRepository.setSummary(record.id, it.title, it.summary)
                    }.getOrThrow()
                }
                synchronized(this@SummaryJobs) { inFlight.remove(record.id) }
                result
            }
        }

    private suspend fun summarizeWithRetries(record: ConversationRecord): Result<ConversationSummary> {
        var last: Result<ConversationSummary> = Result.failure(IllegalStateException("not attempted"))
        repeat(SUMMARY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS)
            last = summarizer.summarize(
                record.sourceText,
                record.translatedText,
                record.targetLanguageName,
                record.context.orEmpty(),
            )
            val kind = (last.exceptionOrNull() as? SummaryException)?.kind
            // Success, or a failure another attempt can't fix (no money, bad key).
            if (last.isSuccess || kind == SummaryException.Kind.OUT_OF_CREDIT || kind == SummaryException.Kind.INVALID_KEY) {
                return last
            }
        }
        return last
    }

    companion object {
        const val SUMMARY_ATTEMPTS = 3

        // Pause before each retry, so an overloaded or rate-limited Claude gets a moment.
        const val RETRY_DELAY_MS = 1_000L
    }
}
