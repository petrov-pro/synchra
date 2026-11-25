package com.petroff.synchra.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationChunkerTest {

    @Test
    fun `no chunk emitted until enough new stable words accumulate`() {
        val chunker = TranslationChunker(minChunkWords = 5, unstableTailWords = 2)

        assertNull(chunker.onPartialUpdate("we need"))
        assertNull(chunker.onPartialUpdate("we need to move this"))
    }

    @Test
    fun `chunk emitted once threshold reached, excluding the unstable tail`() {
        val chunker = TranslationChunker(minChunkWords = 5, unstableTailWords = 2)

        // 7 words total; last 2 ("to", "worker") held back as unstable tail -> 5 stable words
        val chunk = chunker.onPartialUpdate("we need to move this to worker")

        assertEquals("we need to move this", chunk)
    }

    @Test
    fun `subsequent chunks only contain new words since the last commit`() {
        val chunker = TranslationChunker(minChunkWords = 5, unstableTailWords = 2)
        chunker.onPartialUpdate("we need to move this to worker")

        val chunk = chunker.onPartialUpdate("we need to move this to worker because it is slow now")

        assertEquals("to worker because it is", chunk)
    }

    @Test
    fun `no premature commit of the unstable tail across updates`() {
        val chunker = TranslationChunker(minChunkWords = 5, unstableTailWords = 2)
        chunker.onPartialUpdate("we need to move this to worker")

        // Only 3 new words beyond the already-committed 5 -> below threshold, no chunk yet
        val chunk = chunker.onPartialUpdate("we need to move this to worker because")

        assertNull(chunk)
    }

    @Test
    fun `onFinal flushes all remaining words including the unstable tail`() {
        val chunker = TranslationChunker(minChunkWords = 5, unstableTailWords = 2)
        chunker.onPartialUpdate("we need to move this to worker")

        val chunk = chunker.onFinal("we need to move this to worker now")

        assertEquals("to worker now", chunk)
    }

    @Test
    fun `onFinal flushes a short tail even below minChunkWords`() {
        val chunker = TranslationChunker(minChunkWords = 8, unstableTailWords = 2)

        val chunk = chunker.onFinal("hello world")

        assertEquals("hello world", chunk)
    }

    @Test
    fun `onFinal resets committed state for the next utterance`() {
        val chunker = TranslationChunker(minChunkWords = 5, unstableTailWords = 2)
        chunker.onFinal("we need to move this")

        // A fresh utterance's text starts over from empty (matches SherpaOnnxAsrEngine's
        // reset behavior after isEndpoint), so word counting must restart from zero too.
        val chunk = chunker.onPartialUpdate("later we")

        assertNull(chunk)
    }

    @Test
    fun `blank text never produces a chunk`() {
        val chunker = TranslationChunker(minChunkWords = 5, unstableTailWords = 2)

        assertNull(chunker.onPartialUpdate(""))
        assertNull(chunker.onFinal(""))
    }
}
