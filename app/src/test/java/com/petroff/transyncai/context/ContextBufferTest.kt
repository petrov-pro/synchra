package com.petroff.transyncai.context

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextBufferTest {

    @Test
    fun `current phrase reflects the latest partial segment`() {
        val buffer = ContextBuffer(maxFinalPhrases = 3)
        buffer.addSegment("hello wor", isFinal = false)
        assertEquals("hello wor", buffer.currentPhrase())
    }

    @Test
    fun `final segment moves into context and clears current phrase`() {
        val buffer = ContextBuffer(maxFinalPhrases = 3)
        buffer.addSegment("hello world", isFinal = true)
        assertEquals("hello world", buffer.context())
        assertEquals("", buffer.currentPhrase())
    }

    @Test
    fun `context keeps only the most recent maxFinalPhrases`() {
        val buffer = ContextBuffer(maxFinalPhrases = 2)
        buffer.addSegment("one", isFinal = true)
        buffer.addSegment("two", isFinal = true)
        buffer.addSegment("three", isFinal = true)
        assertEquals("two three", buffer.context())
    }

    @Test
    fun `blank final segments are ignored`() {
        val buffer = ContextBuffer(maxFinalPhrases = 3)
        buffer.addSegment("real phrase", isFinal = true)
        buffer.addSegment("   ", isFinal = true)
        assertEquals("real phrase", buffer.context())
    }

    @Test
    fun `clear resets both context and current phrase`() {
        val buffer = ContextBuffer(maxFinalPhrases = 3)
        buffer.addSegment("one", isFinal = true)
        buffer.addSegment("partial", isFinal = false)
        buffer.clear()
        assertEquals("", buffer.context())
        assertEquals("", buffer.currentPhrase())
    }
}
