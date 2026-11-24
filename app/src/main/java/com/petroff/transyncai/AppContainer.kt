package com.petroff.transyncai

import android.content.Context
import androidx.room.Room
import com.petroff.transyncai.asr.MicAudioSource
import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.config.DefaultConfigRepository
import com.petroff.transyncai.config.EncryptedPrefsSettingsStore
import com.petroff.transyncai.history.HistoryRepository
import com.petroff.transyncai.history.MIGRATION_1_2
import com.petroff.transyncai.history.MIGRATION_2_3
import com.petroff.transyncai.history.SummaryJobs
import com.petroff.transyncai.session.LiveSession
import com.petroff.transyncai.session.RecordingService
import com.petroff.transyncai.history.RoomHistoryRepository
import com.petroff.transyncai.history.TransyncDatabase
import com.petroff.transyncai.soniox.SonioxSpeechTranslator
import com.petroff.transyncai.soniox.SpeechTranslator
import com.petroff.transyncai.summary.ClaudeSummarizer
import com.petroff.transyncai.summary.Summarizer
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class AppContainer(private val context: Context) {

    private val httpClient = OkHttpClient()

    private val database: TransyncDatabase = Room.databaseBuilder(
        context,
        TransyncDatabase::class.java,
        "transyncai.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

    val historyRepository: HistoryRepository = RoomHistoryRepository(database.conversationRecordDao())

    val configRepository: ConfigRepository = DefaultConfigRepository(
        store = EncryptedPrefsSettingsStore(context),
        defaultApiBaseUrl = BuildConfig.DEFAULT_API_BASE_URL,
    )

    val summarizer: Summarizer = ClaudeSummarizer(
        baseUrl = configRepository.getConfig().apiBaseUrl,
        apiKeyProvider = { configRepository.getConfig().apiKey },
    )

    // Lives as long as the app process: summary requests outlive the screen that started them.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val summaryJobs = SummaryJobs(appScope, historyRepository, summarizer)

    val liveSession = LiveSession(buildSpeechTranslator(), configRepository, historyRepository, appScope)

    init {
        // A session that is recording needs the foreground service, or Android cuts the
        // microphone as soon as the app leaves the screen.
        appScope.launch {
            liveSession.uiState.map { it.isListening }.distinctUntilChanged().collect { listening ->
                if (listening) RecordingService.start(context)
            }
        }
        // The on-device recognition model (~650 MB) from before Soniox; nothing reads it anymore.
        val legacyModelDir = File(context.filesDir, "model")
        if (legacyModelDir.exists()) thread(name = "LegacyModelCleanup") { legacyModelDir.deleteRecursively() }
    }

    fun buildSpeechTranslator(): SpeechTranslator = SonioxSpeechTranslator(
        audioSourceFactory = { MicAudioSource() },
        httpClient = httpClient,
        apiKeyProvider = { configRepository.getConfig().sonioxApiKey },
    )
}
