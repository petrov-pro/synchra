package com.petroff.transyncai.summary

data class ConversationSummary(val title: String, val summary: String)

/** Why a summary attempt failed — decides whether trying again can help. */
class SummaryException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** The account has no money left; retrying can't help. */
        OUT_OF_CREDIT,

        /** The key is missing or rejected; retrying can't help. */
        INVALID_KEY,

        /** Network, overload, server error or an unusable answer; worth another attempt. */
        TEMPORARY,
    }
}

interface Summarizer {
    /** One attempt; never throws — failures are a [SummaryException] inside the Result. */
    suspend fun summarize(
        sourceText: String,
        translatedText: String,
        targetLanguageName: String,
        // Background the user gave for this conversation (names, topic, terms); may be blank.
        context: String = "",
    ): Result<ConversationSummary>
}
