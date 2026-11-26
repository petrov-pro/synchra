package com.petroff.synchra.local

import org.junit.Assert.assertEquals
import org.junit.Test

class JoinPiecesTest {

    @Test
    fun `pieces are joined with spaces and a pause makes exactly one empty line`() {
        assertEquals("Hello there\n\nBye", joinPieces(listOf("Hello", " there ", "\n\n", "Bye")))
    }

    @Test
    fun `a pause at the start, at the end or twice in a row adds no extra lines`() {
        assertEquals("Hello\n\nBye", joinPieces(listOf("\n\n", "Hello", "\n\n", "\n\n", "", "Bye", "\n\n")))
    }
}
