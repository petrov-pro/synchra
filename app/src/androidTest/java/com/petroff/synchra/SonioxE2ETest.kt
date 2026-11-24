package com.petroff.synchra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.petroff.synchra.asr.WavFileAudioSource
import com.petroff.synchra.config.DefaultConfigRepository
import com.petroff.synchra.config.FakeSettingsStore
import com.petroff.synchra.history.RoomHistoryRepository
import com.petroff.synchra.history.SynchraDatabase
import com.petroff.synchra.session.LiveSession
import com.petroff.synchra.session.SessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.petroff.synchra.soniox.SonioxSpeechTranslator
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Soniox engine end to end on a device: real WAV audio streamed as PCM16 frames through
 * SonioxSpeechTranslator into a mock Soniox WebSocket, responses rendered into SessionViewModel
 * state, and the finished transcript saved to (in-memory) history on stop.
 */
@RunWith(AndroidJUnit4::class)
class SonioxE2ETest {

    private lateinit var server: MockWebServer
    private lateinit var database: SynchraDatabase
    private val audioBytes = AtomicInteger()
    @Volatile private var config: String? = null

    private val sonioxMock = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {}

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (config == null) {
                config = text
            } else if (text.isEmpty()) {
                webSocket.send(
                    """{"tokens":[{"text":" lamps.","is_final":true,"translation_status":"original"},
                       {"text":" фонари.","is_final":true,"translation_status":"translation"}],"finished":true}"""
                )
                webSocket.close(1000, null)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Answer once, after real audio has arrived.
            if (audioBytes.getAndAdd(bytes.size) == 0) {
                webSocket.send(
                    """{"tokens":[{"text":"After early nightfall the yellow","is_final":true,"translation_status":"original"},
                       {"text":"После раннего наступления темноты жёлтые","is_final":true,"translation_status":"translation"}]}"""
                )
            }
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(sonioxMock))
        server.start()
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SynchraDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    @Test
    fun sonioxSessionStreamsAudioAndShowsTranscriptAndTranslation() {
        val configRepository = DefaultConfigRepository(
            store = FakeSettingsStore(),
            defaultApiBaseUrl = "https://api.anthropic.com",
        )
        configRepository.setSonioxApiKey("snx-e2e")
        val history = RoomHistoryRepository(database.conversationRecordDao())
        val audio = WavFileAudioSource(InstrumentationRegistry.getInstrumentation().context.assets, "test_wavs/0.wav")
        val translator = SonioxSpeechTranslator(
            audioSourceFactory = { audio },
            httpClient = OkHttpClient(),
            url = server.url("/transcribe-websocket").toString(),
            apiKeyProvider = { configRepository.getConfig().sonioxApiKey },
        )
        val viewModel = SessionViewModel(
            LiveSession(translator, configRepository, history, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.start() }
        waitUntil { viewModel.uiState.value.translatedText.isNotBlank() }

        assertEquals("After early nightfall the yellow", viewModel.uiState.value.sourceText)
        assertEquals("После раннего наступления темноты жёлтые", viewModel.uiState.value.translatedText)
        assertTrue("config sent first", config!!.contains("\"model\":\"stt-rt-v6\""))
        assertTrue("real audio streamed", audioBytes.get() > 0)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.stop() }
        waitUntil { runBlocking { history.getRecords() }.isNotEmpty() }

        val record = runBlocking { history.getRecords() }.single()
        assertEquals("After early nightfall the yellow lamps.", record.sourceText)
        assertEquals("После раннего наступления темноты жёлтые фонари.", record.translatedText)
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in $timeoutMs ms" }
            Thread.sleep(50)
        }
    }
}
