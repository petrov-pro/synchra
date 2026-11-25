package com.petroff.synchra

import android.content.Context
import androidx.room.Room
import com.petroff.synchra.asr.MicAudioSource
import com.petroff.synchra.asr.ModelDownloads
import com.petroff.synchra.config.ConfigRepository
import com.petroff.synchra.config.DefaultConfigRepository
import com.petroff.synchra.config.EncryptedPrefsSettingsStore
import com.petroff.synchra.diagnostics.CrashReporter
import com.petroff.synchra.diagnostics.FirebaseCrashReporter
import com.petroff.synchra.history.HistoryRepository
import com.petroff.synchra.history.MIGRATION_1_2
import com.petroff.synchra.history.MIGRATION_2_3
import com.petroff.synchra.history.MIGRATION_3_4
import com.petroff.synchra.history.SummaryJobs
import com.petroff.synchra.session.LiveSession
import com.petroff.synchra.session.RecordingService
import com.petroff.synchra.history.RoomHistoryRepository
import com.petroff.synchra.history.SynchraDatabase
import com.petroff.synchra.soniox.SonioxSpeechTranslator
import com.petroff.synchra.soniox.SpeechTranslator
import com.petroff.synchra.summary.ClaudeSummarizer
import com.petroff.synchra.summary.Summarizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class AppContainer(private val context: Context) {

    private val httpClient = OkHttpClient()

    val crashReporter: CrashReporter = FirebaseCrashReporter.createOrNoOp(context)

    private val database: SynchraDatabase = Room.databaseBuilder(
        context,
        SynchraDatabase::class.java,
        "synchra.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

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

    // Free mode's speech model: an older model left in filesDir/model is replaced when this
    // one is downloaded (ModelStore checks which model the files belong to).
    val modelDownloads = ModelDownloads(context)

    val summaryJobs = SummaryJobs(appScope, historyRepository, summarizer, crashReporter)

    val liveSession = LiveSession(buildSpeechTranslator(), configRepository, historyRepository, appScope, crashReporter)

    init {
        crashReporter.setEnabled(configRepository.getConfig().crashReportsConsent == true)
        // A session that is recording needs the foreground service, or Android cuts the
        // microphone as soon as the app leaves the screen.
        appScope.launch {
            liveSession.uiState.map { it.isListening }.distinctUntilChanged().collect { listening ->
                if (listening) RecordingService.start(context)
            }
        }
    }

    fun buildSpeechTranslator(): SpeechTranslator = SonioxSpeechTranslator(
        audioSourceFactory = { MicAudioSource() },
        httpClient = httpClient,
        apiKeyProvider = { configRepository.getConfig().sonioxApiKey },
    )
}
