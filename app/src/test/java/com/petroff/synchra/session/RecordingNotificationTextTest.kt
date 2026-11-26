package com.petroff.synchra.session

import com.petroff.synchra.config.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingNotificationTextTest {

    @Test
    fun `says when audio is being sent, without naming the provider`() {
        assertEquals("Sending audio", recordingStatusText(isSending = true, mode = Mode.PAID))
    }

    @Test
    fun `says when nothing is being sent`() {
        assertEquals("Listening · nothing sent", recordingStatusText(isSending = false, mode = Mode.PAID))
    }

    @Test
    fun `free mode only listens`() {
        assertEquals("Listening", recordingStatusText(isSending = false, mode = Mode.FREE))
    }

    @Test
    fun `paused notification text`() {
        assertEquals("Paused after 1 h — tap to continue", pausedNotificationText(60))
        assertEquals("Paused after 30 min — tap to continue", pausedNotificationText(30))
    }
}
