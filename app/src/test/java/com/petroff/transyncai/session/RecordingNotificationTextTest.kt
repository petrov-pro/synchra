package com.petroff.transyncai.session

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingNotificationTextTest {

    @Test
    fun `says when audio is going to Soniox`() {
        assertEquals("Sending audio to Soniox", recordingStatusText(isSending = true))
    }

    @Test
    fun `says when nothing is being sent`() {
        assertEquals("Listening · nothing sent", recordingStatusText(isSending = false))
    }
}
