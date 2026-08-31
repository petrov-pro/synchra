package com.petroff.transyncai.session

import com.petroff.transyncai.translate.Translator

class FakeTranslator : Translator {
    val calls = mutableListOf<String>()
    val contexts = mutableListOf<String>()
    var result: Result<String> = Result.success("translated")

    /** Overrides [result] when set — lets a test return a distinct result per input phrase. */
    var resultFor: ((String) -> Result<String>)? = null

    /**
     * Test-only suspension hook invoked just before [translate] returns — lets a test
     * hold a translation "in flight" (e.g. suspend on a CompletableDeferred) to control
     * exactly when the fake network call resolves, for testing races against it.
     */
    var beforeReturn: (suspend () -> Unit)? = null

    override suspend fun translate(
        context: String,
        currentPhrase: String,
        sourceLang: String,
        targetLang: String,
    ): Result<String> {
        calls.add(currentPhrase)
        contexts.add(context)
        beforeReturn?.invoke()
        return resultFor?.invoke(currentPhrase) ?: result
    }
}
