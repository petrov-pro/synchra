package com.petroff.transyncai.config

data class AppConfig(
    val bufferSize: Int,
    val apiBaseUrl: String,
    val apiKey: String?,
    val languagePairs: List<LanguagePair>,
    val selectedLanguagePairIndex: Int,
    val chunkSizeWords: Int,
    // Standing background the customer sets once in Settings (e.g. domain/terminology hints)
    // and general-purpose enough to apply to any conversation.
    val generalContext: String = "",
    // Per-conversation context, set via the Main screen's Context popup. Takes priority over
    // generalContext when non-blank (see AppConfig.effectiveContext); persists across app
    // restarts like everything else here, but is meant to be replaced each new conversation.
    val conversationContext: String = "",
) {
    val selectedLanguagePair: LanguagePair
        get() = languagePairs[selectedLanguagePairIndex.coerceIn(languagePairs.indices)]
}
