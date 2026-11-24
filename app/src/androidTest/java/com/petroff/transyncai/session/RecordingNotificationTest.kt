package com.petroff.transyncai.session

import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingNotificationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun showsWhatHappensToTheAudioATimerAndAStopButtonButNoConversationText() {
        val state = SessionUiState(
            isListening = true,
            isSending = true,
            startedAt = 1_700_000_000_000L,
            sourceText = "secret meeting",
            translatedText = "секретная встреча",
        )

        val notification = RecordingService.buildNotification(context, state)

        val text = notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
        assertEquals("Sending audio to Soniox", text)
        assertTrue(notification.extras.getBoolean(NotificationCompat.EXTRA_SHOW_CHRONOMETER))
        assertEquals(1_700_000_000_000L, notification.`when`)
        assertEquals("Stop", notification.actions.single().title.toString())
        @Suppress("DEPRECATION")
        val everything = notification.extras.keySet().joinToString { notification.extras.get(it).toString() }
        assertTrue("no conversation text", !everything.contains("secret") && !everything.contains("секрет"))
    }
}
