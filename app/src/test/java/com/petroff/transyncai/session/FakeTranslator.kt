package com.petroff.transyncai.session

import com.petroff.transyncai.translate.Translator

class FakeTranslator : Translator {
    val calls = mutableListOf<String>()
    var result: Result<String> = Result.success("translated")

    override suspend fun translate(
        context: String,
        currentPhrase: String,
        sourceLang: String,
        targetLang: String,
    ): Result<String> {
        calls.add(currentPhrase)
        return result
    }
}
