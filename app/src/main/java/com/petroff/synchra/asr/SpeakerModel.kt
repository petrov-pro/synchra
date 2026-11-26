package com.petroff.synchra.asr

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.petroff.synchra.local.SpeakerEmbedder
import java.io.File
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient

/**
 * Free mode's speaker model (3D-Speaker CAM++, VoxCeleb, 28 MB): voice prints for the
 * "Speaker N:" labels. Picked in tools/asr-bench/diarization_local.py (~94% of speech labeled
 * right in 2-4 speaker conversations). Optional: without it the text is just unlabeled.
 */
const val SPEAKER_MODEL_URL =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/" +
        "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"

/** Kept apart from the speech model's folder, which is wiped when that model changes. */
class SpeakerModelStore(private val dir: File) {
    // Named by the URL's file: a different model later gets a different file.
    val file: File get() = File(dir, SPEAKER_MODEL_URL.substringAfterLast('/'))
    val part: File get() = File(dir, file.name + ".part")

    fun isReady(): Boolean = file.length() > 0

    fun delete() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        fun of(context: Context) = SpeakerModelStore(File(context.filesDir, "speaker-model"))
    }
}

/** Downloads the speaker model quietly in the background (it is small); resumes a partial file. */
class SpeakerModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = SpeakerModelStore.of(applicationContext)
        if (store.isReady()) return Result.success()
        return try {
            runInterruptible {
                store.part.parentFile?.mkdirs()
                // Other files there are older models: not needed any more.
                store.part.parentFile?.listFiles()?.filter { it != store.part }?.forEach { it.delete() }
                ResumableDownloader(OkHttpClient()).download(SPEAKER_MODEL_URL, store.part) { _, _ -> }
                if (!store.part.renameTo(store.file)) throw java.io.IOException("Couldn't save the speaker model")
            }
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.retry()
        }
    }
}

class SpeakerModelDownloads(private val context: Context) {
    val store = SpeakerModelStore.of(context)

    /** Fetches the model unless it is there or already coming. */
    fun ensure() {
        if (store.isReady()) return
        val request = OneTimeWorkRequestBuilder<SpeakerModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun delete() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        store.delete()
    }

    private companion object {
        const val WORK_NAME = "speaker-model-download"
    }
}

/** sherpa-onnx voice prints over the speaker model in [modelPath]. */
class SherpaSpeakerEmbedder(modelPath: String) : SpeakerEmbedder {
    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(model = modelPath, numThreads = 2),
    )

    override fun embed(samples: FloatArray): FloatArray {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, MicAudioSource.SAMPLE_RATE)
            stream.inputFinished()
            check(extractor.isReady(stream)) { "Too little audio for a voice print" }
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun release() = extractor.release()
}
