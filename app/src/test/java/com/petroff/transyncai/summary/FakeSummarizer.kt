package com.petroff.transyncai.summary

import kotlinx.coroutines.CompletableDeferred

/** Answers from [results] in order; [gate], when set, holds each call open until completed. */
class FakeSummarizer(private val results: MutableList<Result<ConversationSummary>>) : Summarizer {
    var calls = 0
    var lastContext: String? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun summarize(
        sourceText: String,
        translatedText: String,
        targetLanguageName: String,
        context: String,
    ): Result<ConversationSummary> {
        calls++
        lastContext = context
        gate?.await()
        return results.removeAt(0)
    }

    companion object {
        fun ok(title: String = "Встреча", summary: String = "Обсудили сроки.") =
            Result.success(ConversationSummary(title, summary))

        fun fail(kind: SummaryException.Kind = SummaryException.Kind.TEMPORARY) =
            Result.failure<ConversationSummary>(SummaryException(kind, "boom"))
    }
}
