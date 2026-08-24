package com.petroff.transyncai.translate

interface Translator {
    suspend fun translate(
        context: String,
        currentPhrase: String,
        sourceLang: String,
        targetLang: String,
    ): Result<String>
}
