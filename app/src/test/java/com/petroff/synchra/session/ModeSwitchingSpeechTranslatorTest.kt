package com.petroff.synchra.session

import com.petroff.synchra.config.Mode
import com.petroff.synchra.soniox.LiveRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeSwitchingSpeechTranslatorTest {

    private val paid = FakeSpeechTranslator()
    private val free = FakeSpeechTranslator()
    private var mode = Mode.FREE
    private val switching = ModeSwitchingSpeechTranslator({ mode }, paid = paid, free = free)
    private val request = LiveRequest("en", "ru", "")

    private fun start() = switching.start(request, onUpdate = {}, onError = {}, onFinished = {})

    @Test
    fun `starts the engine of the current mode`() {
        start()
        assertEquals(1, free.startCount)
        assertEquals(0, paid.startCount)

        mode = Mode.PAID
        start()
        assertEquals(1, paid.startCount)
    }

    @Test
    fun `stop goes to the engine that started`() {
        start()
        mode = Mode.PAID

        switching.stop()

        assertTrue(free.stopped)
        assertFalse(paid.stopped)
    }
}
