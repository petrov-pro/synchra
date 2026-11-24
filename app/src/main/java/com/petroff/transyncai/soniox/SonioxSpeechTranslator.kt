package com.petroff.transyncai.soniox

import android.util.Log
import com.petroff.transyncai.asr.AudioSource
import com.petroff.transyncai.asr.MicAudioSource
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
    )

    fun stop()
}

/**
 * Soniox real-time STT with one-way translation in the same stream
 * (https://docs.soniox.com/stt/api-reference/websocket-api). Microphone audio is streamed as
 * 16 kHz mono PCM16 for as long as the session runs.
 */
class SonioxSpeechTranslator(
    private val audioSource: AudioSource,
    private val httpClient: OkHttpClient,
    private val url: String = DEFAULT_URL,
    private val apiKeyProvider: () -> String?,
) : SpeechTranslator {

    @Volatile private var socket: WebSocket? = null
    @Volatile private var finishOnce: ((LiveTranscript) -> Unit)? = null
    @Volatile private var latest = LiveTranscript("", "")

    override fun start(
        request: LiveRequest,
        onUpdate: (LiveTranscript) -> Unit,
        onError: (String) -> Unit,
        onFinished: (LiveTranscript) -> Unit,
    ) {
        val done = AtomicBoolean(false)
        val finish: (LiveTranscript) -> Unit = { if (done.compareAndSet(false, true)) onFinished(it) }
        finishOnce = finish
        latest = LiveTranscript("", "")

        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            onError("Set your Soniox API key in Settings")
            finish(latest)
            return
        }

        val transcript = SonioxTranscript()
        val httpRequest = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()
        socket = httpClient.newWebSocket(httpRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(configJson(request))
                audioSource.start { samples -> webSocket.send(toPcm16(samples).toByteString()) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val event = transcript.apply(text)) {
                    is SonioxEvent.Update -> {
                        latest = LiveTranscript(event.source, event.translation)
                        onUpdate(latest)
                    }
                    is SonioxEvent.Finished -> {
                        latest = LiveTranscript(event.source, event.translation)
                        onUpdate(latest)
                        finish(latest)
                    }
                    is SonioxEvent.Error -> {
                        Log.e(TAG, event.message)
                        audioSource.stop()
                        onError(event.message)
                        finish(latest)
                        webSocket.close(NORMAL_CLOSURE, null)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "stream failed", t)
                audioSource.stop()
                if (!done.get()) onError("Soniox connection failed: ${t.message}")
                finish(latest)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(latest)
            }
        })
    }

    override fun stop() {
        audioSource.stop()
        val ws = socket ?: return
        ws.send("") // empty text frame = end of audio; the server answers with finished:true
        val finish = finishOnce
        // Don't let a lost "finished" response hold the session (and its history save) forever.
        thread(isDaemon = true, name = "SonioxStopTimeout") {
            Thread.sleep(STOP_TIMEOUT_MS)
            finish?.invoke(latest)
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
        private const val TAG = "TransyncAI/Soniox"
        const val DEFAULT_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        const val MODEL = "stt-rt-v6"
        private const val NORMAL_CLOSURE = 1000
        private const val STOP_TIMEOUT_MS = 3000L
    }
}
