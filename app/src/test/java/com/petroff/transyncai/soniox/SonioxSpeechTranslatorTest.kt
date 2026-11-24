package com.petroff.transyncai.soniox

import com.petroff.transyncai.asr.AudioSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeAudioSource : AudioSource {
    var stopped = false
    override fun start(onSamples: (FloatArray) -> Unit) {
        onSamples(floatArrayOf(0f, 0.5f, -0.5f))
    }
    override fun stop() { stopped = true }
}

/** Server side of the mock WebSocket: records frames, lets the test push responses. */
private class FakeSonioxServer : WebSocketListener() {
    val texts = LinkedBlockingQueue<String>()
    val binaries = LinkedBlockingQueue<ByteString>()
    @Volatile var socket: WebSocket? = null
    val opened = CountDownLatch(1)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
        opened.countDown()
    }
    override fun onMessage(webSocket: WebSocket, text: String) { texts.add(text) }
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { binaries.add(bytes) }
}

class SonioxSpeechTranslatorTest {

    private lateinit var server: MockWebServer
    private lateinit var fakeServer: FakeSonioxServer
    private val audio = FakeAudioSource()
    private val updates = LinkedBlockingQueue<LiveTranscript>()
    private val errors = LinkedBlockingQueue<String>()
    private val finished = LinkedBlockingQueue<LiveTranscript>()

    @Before
    fun setUp() {
        server = MockWebServer()
        fakeServer = FakeSonioxServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(fakeServer))
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun translator(key: String? = "snx-test") = SonioxSpeechTranslator(
        audioSourceFactory = { audio },
        httpClient = OkHttpClient(),
        url = server.url("/transcribe-websocket").toString(),
        apiKeyProvider = { key },
    )

    private fun SonioxSpeechTranslator.startWith(request: LiveRequest) = start(
        request,
        onUpdate = { updates.add(it) },
        onError = { errors.add(it) },
        onFinished = { finished.add(it) },
    )

    @Test
    fun `opens an authorized stream, sends the config, then the microphone audio`() {
        translator().startWith(LiveRequest(sourceLang = "en", targetLang = "ru", context = "Cardiology lecture"))

        assertTrue(fakeServer.opened.await(5, TimeUnit.SECONDS))
        assertEquals("Bearer snx-test", server.takeRequest().getHeader("Authorization"))
        val config = fakeServer.texts.poll(5, TimeUnit.SECONDS)!!
        assertTrue(config.contains("\"model\":\"stt-rt-v6\""))
        assertTrue(config.contains("\"audio_format\":\"pcm_s16le\""))
        assertTrue(config.contains("\"sample_rate\":16000"))
        assertTrue(config.contains("\"language_hints\":[\"en\"]"))
        assertTrue(config.contains("\"translation\":{\"type\":\"one_way\",\"target_language\":\"ru\"}"))
        assertTrue(config.contains("\"enable_endpoint_detection\":true"))
        assertTrue(config.contains("\"context\":{\"text\":\"Cardiology lecture\"}"))
        // 3 float samples -> 3 little-endian 16-bit PCM samples
        assertEquals(6, fakeServer.binaries.poll(5, TimeUnit.SECONDS)!!.size)
    }

    @Test
    fun `transcription-only requests no translation`() {
        translator().startWith(LiveRequest(sourceLang = "en", targetLang = null, context = ""))

        val config = fakeServer.texts.poll(5, TimeUnit.SECONDS)!!
        assertFalse(config.contains("translation"))
        assertFalse(config.contains("context"))
    }

    @Test
    fun `server responses are delivered as source and translation updates`() {
        translator().startWith(LiveRequest("en", "ru", ""))
        assertTrue(fakeServer.opened.await(5, TimeUnit.SECONDS))

        fakeServer.socket!!.send(
            """{"tokens":[{"text":"Hello","is_final":true,"translation_status":"original"},
                {"text":"Привет","is_final":true,"translation_status":"translation"}]}"""
        )

        assertEquals(LiveTranscript("Hello", "Привет"), updates.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `stop ends the audio and finishes with the final transcript`() {
        val t = translator()
        t.startWith(LiveRequest("en", "ru", ""))
        assertNotNull(fakeServer.texts.poll(5, TimeUnit.SECONDS)) // config

        t.stop()

        assertTrue(audio.stopped)
        assertEquals("", fakeServer.texts.poll(5, TimeUnit.SECONDS)) // end-of-audio frame
        fakeServer.socket!!.send(
            """{"tokens":[{"text":"Bye","is_final":true,"translation_status":"original"}],"finished":true}"""
        )
        assertEquals(LiveTranscript("Bye", ""), finished.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a server error is reported and the session still finishes`() {
        translator().startWith(LiveRequest("en", "ru", ""))
        assertTrue(fakeServer.opened.await(5, TimeUnit.SECONDS))

        fakeServer.socket!!.send("""{"tokens":[],"error_code":402,"error_message":"Organization balance exhausted."}""")

        assertEquals("Your Soniox balance is empty. Top it up at console.soniox.com.", errors.poll(5, TimeUnit.SECONDS))
        assertNotNull(finished.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `fails fast without a connection when the api key is missing`() {
        translator(key = null).startWith(LiveRequest("en", "ru", ""))

        assertNotNull(errors.poll(5, TimeUnit.SECONDS))
        assertNotNull(finished.poll(5, TimeUnit.SECONDS))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `closing the previous session's stream does not stop the next session's microphone`() {
        val secondServer = FakeSonioxServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(secondServer))
        val microphones = mutableListOf<FakeAudioSource>()
        val t = SonioxSpeechTranslator(
            audioSourceFactory = { FakeAudioSource().also { microphones += it } },
            httpClient = OkHttpClient(),
            url = server.url("/transcribe-websocket").toString(),
            apiKeyProvider = { "snx-test" },
        )
        t.startWith(LiveRequest("en", "ru", ""))
        assertTrue(fakeServer.opened.await(5, TimeUnit.SECONDS))
        t.stop()
        t.startWith(LiveRequest("en", "ru", ""))
        assertTrue(secondServer.opened.await(5, TimeUnit.SECONDS))
        assertNotNull(secondServer.texts.poll(5, TimeUnit.SECONDS)) // second config sent

        // The first stream never answers "finished": 3 s after stop() the client cancels it,
        // which fails that stream while the second session is already recording.
        Thread.sleep(3_500)

        assertEquals(2, microphones.size)
        assertFalse(microphones[1].stopped)
    }
}

