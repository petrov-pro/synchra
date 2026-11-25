package com.petroff.synchra.session

/**
 * Soft-commit chunking: turns a growing ASR transcript into word-count-bounded chunks for
 * translation, without waiting for silence. The last [unstableTailWords] words of a partial
 * update are held back uncommitted, since streaming ASR can still revise its most recent word(s)
 * as more audio arrives; [onFinal] flushes everything remaining, tail included, since an
 * endpoint-finalized result is settled.
 */
class TranslationChunker(
    private val minChunkWords: Int = 8,
    private val unstableTailWords: Int = 2,
) {
    private var committedWordCount = 0

    fun onPartialUpdate(fullText: String): String? {
        val words = wordsOf(fullText)
        val stableWordCount = (words.size - unstableTailWords).coerceAtLeast(0)
        val newStableWordCount = stableWordCount - committedWordCount
        if (newStableWordCount < minChunkWords) return null
        val chunk = words.subList(committedWordCount, stableWordCount).joinToString(" ")
        committedWordCount = stableWordCount
        return chunk
    }

    fun onFinal(fullText: String): String? {
        val words = wordsOf(fullText)
        val chunk = if (words.size > committedWordCount) {
            words.subList(committedWordCount, words.size).joinToString(" ")
        } else {
            null
        }
        reset()
        return chunk
    }

    fun reset() {
        committedWordCount = 0
    }

    private fun wordsOf(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
