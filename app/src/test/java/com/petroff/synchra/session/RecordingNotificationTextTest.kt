package com.petroff.synchra.session

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingNotificationTextTest {

    @Test
    fun `says when audio is being sent, without naming the provider`() {
        assertEquals("Sending audio", recordingStatusText(isSending = true))
    }

    @Test
    fun `says when nothing is being sent`() {
        assertEquals("Listening · nothing sent", recordingStatusText(isSending = false))
    }
}
