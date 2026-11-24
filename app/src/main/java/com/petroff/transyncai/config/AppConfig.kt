package com.petroff.transyncai.config

// Both contexts go to Soniox together, and Soniox rejects a context over ~8,000 tokens
// (~10,000 characters): these caps keep the pair under that even for dense text.
const val MAX_GENERAL_CONTEXT_CHARS = 6_000
const val MAX_CONVERSATION_CONTEXT_CHARS = 2_000

data class AppConfig(
    // Anthropic API (Claude), kept for conversation summaries.
    val apiBaseUrl: String,
    val apiKey: String?,
    val languagePairs: List<LanguagePair>,
    val selectedLanguagePairIndex: Int,
    // Standing background the customer sets once in Settings (e.g. domain/terminology hints)
    // and general-purpose enough to apply to any conversation.
    val generalContext: String = "",
    // Per-conversation context, set via the Main screen's Context popup. Sent after
    // generalContext (see LiveSession.start); persists across app restarts like everything
    // else here, but is meant to be replaced each new conversation.
    val conversationContext: String = "",
    val sonioxApiKey: String? = null,
) {
    val selectedLanguagePair: LanguagePair
        get() = languagePairs[selectedLanguagePairIndex.coerceIn(languagePairs.indices)]
}
