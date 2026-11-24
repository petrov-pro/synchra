package com.petroff.transyncai

import android.content.Context
import androidx.room.Room
import com.petroff.transyncai.asr.MicAudioSource
import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.config.DefaultConfigRepository
import com.petroff.transyncai.config.EncryptedPrefsSettingsStore
import com.petroff.transyncai.history.HistoryRepository
import com.petroff.transyncai.history.RoomHistoryRepository
import com.petroff.transyncai.history.TransyncDatabase
import com.petroff.transyncai.soniox.SonioxSpeechTranslator
import com.petroff.transyncai.soniox.SpeechTranslator
import java.io.File
import kotlin.concurrent.thread
import okhttp3.OkHttpClient

class AppContainer(private val context: Context) {

    private val httpClient = OkHttpClient()

    private val database: TransyncDatabase = Room.databaseBuilder(
        context,
        TransyncDatabase::class.java,
        "transyncai.db",
    ).build()

    val historyRepository: HistoryRepository = RoomHistoryRepository(database.conversationRecordDao())

    val configRepository: ConfigRepository = DefaultConfigRepository(
        store = EncryptedPrefsSettingsStore(context),
        defaultApiBaseUrl = BuildConfig.DEFAULT_API_BASE_URL,
        defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
    )

    init {
        // The on-device recognition model (~650 MB) from before Soniox; nothing reads it anymore.
        val legacyModelDir = File(context.filesDir, "model")
        if (legacyModelDir.exists()) thread(name = "LegacyModelCleanup") { legacyModelDir.deleteRecursively() }
    }

    fun buildSpeechTranslator(): SpeechTranslator = SonioxSpeechTranslator(
        audioSource = MicAudioSource(),
        httpClient = httpClient,
        apiKeyProvider = { configRepository.getConfig().sonioxApiKey },
    )
}
