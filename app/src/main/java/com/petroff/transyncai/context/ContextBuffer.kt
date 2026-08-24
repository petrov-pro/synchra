package com.petroff.transyncai.context

class ContextBuffer(private val maxFinalPhrases: Int) {
    private val finalPhrases = ArrayDeque<String>()
    private var current = ""

    fun addSegment(text: String, isFinal: Boolean) {
        if (isFinal) {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                finalPhrases.addLast(trimmed)
                while (finalPhrases.size > maxFinalPhrases) {
                    finalPhrases.removeFirst()
                }
            }
            current = ""
        } else {
            current = text
        }
    }

    fun context(): String = finalPhrases.joinToString(" ")

    fun currentPhrase(): String = current

    fun clear() {
        finalPhrases.clear()
        current = ""
    }
}
