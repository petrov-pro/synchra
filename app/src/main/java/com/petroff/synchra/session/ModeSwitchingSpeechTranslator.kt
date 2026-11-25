package com.petroff.synchra.session

import com.petroff.synchra.config.Mode
import com.petroff.synchra.soniox.LiveRequest
import com.petroff.synchra.soniox.LiveTranscript
import com.petroff.synchra.soniox.SpeechTranslator

/** Soniox in Paid mode, the on-device engine in Free mode, chosen at each start. */
class ModeSwitchingSpeechTranslator(
    private val modeProvider: () -> Mode,
    private val paid: SpeechTranslator,
    private val free: SpeechTranslator,
) : SpeechTranslator {

    // Stop must reach the engine that is running, even if the mode changed since.
    @Volatile private var active: SpeechTranslator? = null

    override fun start(
        request: LiveRequest,
        onUpdate: (LiveTranscript) -> Unit,
        onError: (String) -> Unit,
        onFinished: (LiveTranscript) -> Unit,
        onAutoStopped: () -> Unit,
        onSendingChanged: (Boolean) -> Unit,
    ) {
        val engine = if (modeProvider() == Mode.FREE) free else paid
        active = engine
        engine.start(request, onUpdate, onError, onFinished, onAutoStopped, onSendingChanged)
    }

    override fun stop() {
        active?.stop()
    }
}
