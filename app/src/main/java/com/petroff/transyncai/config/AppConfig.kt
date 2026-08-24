package com.petroff.transyncai.config

data class AppConfig(
    val bufferSize: Int,
    val apiBaseUrl: String,
    val apiKey: String?,
    val languagePairs: List<LanguagePair>,
    val selectedLanguagePairIndex: Int,
) {
    val selectedLanguagePair: LanguagePair
        get() = languagePairs[selectedLanguagePairIndex.coerceIn(languagePairs.indices)]
}
