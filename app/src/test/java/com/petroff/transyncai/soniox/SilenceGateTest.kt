package com.petroff.transyncai.soniox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceGateTest {

    // 100 ms chunks at 16 kHz, like the microphone delivers.
    private fun chunk(level: Float) = FloatArray(1600) { i -> if (i % 2 == 0) level else -level }
    private val speech get() = chunk(0.2f)
    private val silence get() = chunk(0.001f)

    private val gate = SilenceGate()

    private fun feed(seconds: Double, chunk: () -> FloatArray): List<SilenceGate.Decision> =
        List((seconds * 10).toInt()) { gate.process(chunk()) }

    @Test
    fun `speech is sent`() {
        val decisions = feed(1.0) { speech }

        assertTrue(decisions.all { it.send.size == 1 })
    }

    @Test
    fun `silence before any speech is not sent`() {
        val decisions = feed(5.0) { silence }

        assertTrue(decisions.all { it.send.isEmpty() })
    }

    @Test
    fun `after speech 1_5 s of silence is still sent so Soniox can finish the phrase, then nothing`() {
        feed(1.0) { speech }

        val after = feed(3.0) { silence }

        assertEquals(15, after.count { it.send.isNotEmpty() })
        assertTrue(after.drop(15).all { it.send.isEmpty() })
    }

    @Test
    fun `when speech resumes the last 0_5 s before it is sent first so no syllable is cut`() {
        feed(1.0) { speech }
        feed(5.0) { silence }

        val resumed = gate.process(speech)

        assertEquals(6, resumed.send.size) // 5 buffered silent chunks + this speech chunk
    }

    @Test
    fun `a keepalive goes out every 10 s while nothing is sent`() {
        val decisions = feed(35.0) { silence }

        assertEquals(3, decisions.count { it.keepalive })
    }

    @Test
    fun `one minute of silence asks to stop`() {
        feed(2.0) { speech }

        val silent = feed(60.0) { silence }

        assertFalse(silent.dropLast(1).any { it.autoStop })
        assertTrue(silent.last().autoStop)
    }

    @Test
    fun `steady background noise is not mistaken for speech`() {
        val noise = { chunk(0.02f) }
        feed(6.0, noise) // the gate learns the room

        val later = feed(5.0, noise)

        assertTrue(later.all { it.send.isEmpty() })
        assertEquals(1, gate.process(speech).send.size.coerceAtMost(1))
    }
}
