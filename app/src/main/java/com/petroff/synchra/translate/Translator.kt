package com.petroff.synchra.translate

/** Translates short pieces of a conversation, one language pair at a time. */
interface Translator {
    /** Gets the pair ready (e.g. downloads its models); call before [translate]. */
    suspend fun prepare(source: String, target: String): Result<Unit>

    suspend fun translate(text: String): Result<String>

    fun close()
}
