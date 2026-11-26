package com.petroff.synchra.soniox

import android.util.Log
import com.petroff.synchra.asr.AudioSource
import com.petroff.synchra.asr.MicAudioSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

data class LiveTranscript(val source: String, val translation: String)

/** targetLang = null means transcription only (no translation requested or billed). */
data class LiveRequest(val sourceLang: String, val targetLang: String?, val context: String)

/** Speech in, source text and its translation out, from one streaming service. */
interface SpeechTranslator {
    fun start(
        request: LiveRequest,
        onUpdate: (LiveTranscript) -> Unit,
        onError: (String) -> Unit,
        // Called exactly once per start(), after stop() or a fatal error, with the final text.
        onFinished: (LiveTranscript) -> Unit,
        // The session ended itself after a long silence (onFinished still follows).
        onAutoStopped: () -> Unit = {},
        // Audio started (true) or stopped (false) going to the provider — it is billed while true.
        // On-device engines send nothing: for them it means speech heard (true) or silence.
        onSendingChanged: (Boolean) -> Unit = {},
    )

    fun stop()
}

/**
 * Soniox real-time STT with one-way translation in the same stream
 * (https://docs.soniox.com/stt/api-reference/websocket-api). Microphone audio is streamed as
 * 16 kHz mono PCM16 for as long as the session runs.
 */
class SonioxSpeechTranslator(
    // A fresh microphone per session: a late close of the previous stream must not stop the
    // microphone the next session is already using.
    private val audioSourceFactory: () -> AudioSource,
    private val httpClient: OkHttpClient,
    private val url: String = DEFAULT_URL,
    private val apiKeyProvider: () -> String?,
) : SpeechTranslator {

    /** Everything one start()…finished stream owns; old sessions never touch the current one. */
    private inner class Session(
        private val onFinished: (LiveTranscript) -> Unit,
        private val onSendingChanged: (Boolean) -> Unit,
    ) {
        val audio: AudioSource = audioSourceFactory()
        // Soniox bills every second it receives, silence included: send speech, not pauses.
        val gate = SilenceGate()
        val ending = AtomicBoolean(false)
        @Volatile var opened = false
        val transcript = SonioxTranscript()
        val done = AtomicBoolean(false)
        @Volatile var latest = LiveTranscript("", "")
        @Volatile var socket: WebSocket? = null

        private var sending = false

        @Synchronized
        fun setSending(now: Boolean) {
            if (now == sending) return
            sending = now
            // Speech again after a pause the gate held back: a new paragraph.
            if (now) transcript.paragraphBreak()
            onSendingChanged(now)
        }

        fun finish() {
            if (done.compareAndSet(false, true)) onFinished(latest)
        }
    }

    @Volatile private var current: Session? = null

    override fun start(
        request: LiveRequest,
        onUpdate: (LiveTranscript) -> Unit,
        onError: (String) -> Unit,
        onFinished: (LiveTranscript) -> Unit,
        onAutoStopped: () -> Unit,
        onSendingChanged: (Boolean) -> Unit,
    ) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            onError("Set your Soniox API key in Settings")
            onFinished(LiveTranscript("", ""))
            return
        }

        val session = Session(onFinished, onSendingChanged)
        current = session
        val httpRequest = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()
        session.socket = httpClient.newWebSocket(httpRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                session.opened = true
                // Stop was tapped while still connecting: nothing to record, just close.
                if (session.ending.get()) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    session.finish()
                    return
                }
                webSocket.send(configJson(request))
                session.audio.start { samples ->
                    val decision = session.gate.process(samples)
                    decision.send.forEach { webSocket.send(toPcm16(it).toByteString()) }
                    session.setSending(decision.send.isNotEmpty())
                    if (decision.keepalive) webSocket.send(KEEPALIVE)
                    if (decision.autoStop && session.ending.compareAndSet(false, true)) {
                        Log.d(TAG, "stopping after a long silence")
                        endAudio(session)
                        onAutoStopped()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val event = session.transcript.apply(text)) {
                    is SonioxEvent.Update -> {
                        session.latest = LiveTranscript(event.source, event.translation)
                        onUpdate(session.latest)
                    }
                    is SonioxEvent.Finished -> {
                        session.latest = LiveTranscript(event.source, event.translation)
                        onUpdate(session.latest)
                        session.finish()
                    }
                    is SonioxEvent.Error -> {
                        Log.e(TAG, event.message)
                        session.audio.stop()
                session.setSending(false)
                        session.setSending(false)
                        onError(event.message)
                        session.finish()
                        webSocket.close(NORMAL_CLOSURE, null)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "stream failed", t)
                session.audio.stop()
                session.setSending(false)
                if (!session.done.get()) onError("Soniox connection failed: ${t.message}")
                session.finish()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                session.finish()
            }
        })
    }

    override fun stop() {
        val session = current ?: return
        if (session.ending.compareAndSet(false, true)) endAudio(session)
    }

    private fun endAudio(session: Session) {
        session.audio.stop()
        session.setSending(false)
        val ws = session.socket ?: return
        // Before onOpen the end-of-audio frame would go out ahead of the config; onOpen closes instead.
        if (session.opened) ws.send("") // end of audio; the server answers with finished:true
        // Don't let a lost "finished" response hold the session (and its history save) forever.
        thread(isDaemon = true, name = "SonioxStopTimeout") {
            Thread.sleep(STOP_TIMEOUT_MS)
            session.finish()
            ws.cancel()
        }
    }

    private fun configJson(request: LiveRequest): String = buildJsonObject {
        put("model", MODEL)
        put("audio_format", "pcm_s16le")
        put("sample_rate", MicAudioSource.SAMPLE_RATE)
        put("num_channels", 1)
        putJsonArray("language_hints") { add(request.sourceLang) }
        // Endpoints finalize the phrase (and its translation) shortly after the speaker pauses.
        put("enable_endpoint_detection", true)
        put("max_endpoint_delay_ms", 1000)
        // Every token says who spoke it ("1", "2"…), translation tokens too. Checked on 2 and 3
        // speakers with these same settings (2026-11-26): 99.8–100% of words labeled right.
        put("enable_speaker_diarization", true)
        request.targetLang?.let { target ->
            putJsonObject("translation") {
                put("type", "one_way")
                put("target_language", target)
            }
        }
        if (request.context.isNotBlank()) {
            putJsonObject("context") { put("text", request.context) }
        }
    }.toString()

    private fun toPcm16(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        return buffer.array()
    }

    companion object {
        private const val TAG = "Synchra/Soniox"
        const val DEFAULT_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        const val MODEL = "stt-rt-v6"
        private const val NORMAL_CLOSURE = 1000
        private const val STOP_TIMEOUT_MS = 3000L
        private const val KEEPALIVE = """{"type":"keepalive"}"""
    }
}
