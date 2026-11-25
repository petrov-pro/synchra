package com.petroff.synchra.asr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.petroff.synchra.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val percent: Int) : ModelState
    /** Downloaded; the archive is being unpacked (takes a minute or two on a phone). */
    data object Unpacking : ModelState
    data object Ready : ModelState
    data object Failed : ModelState
}

/** [work]: state of the latest download work, null when there was none. */
fun modelStateOf(ready: Boolean, work: WorkInfo.State?, percent: Int, unpacking: Boolean = false): ModelState = when {
    work == WorkInfo.State.RUNNING && unpacking -> ModelState.Unpacking
    work == WorkInfo.State.RUNNING || work == WorkInfo.State.ENQUEUED || work == WorkInfo.State.BLOCKED ->
        ModelState.Downloading(percent)
    ready -> ModelState.Ready
    work == WorkInfo.State.FAILED -> ModelState.Failed
    else -> ModelState.NotDownloaded
}

fun speechModelDir(context: Context): File = File(context.filesDir, "model")

/**
 * Downloads and unpacks the speech model in the background, as a foreground job with a
 * progress notification, so it goes on when the app is left. A partial archive stays on disk
 * and the next attempt continues from it.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val store = ModelStore(speechModelDir(applicationContext))
        if (store.isReady()) return@coroutineScope Result.success()
        val percent = AtomicInteger(0)
        val unpacking = AtomicBoolean(false)
        setForeground(foregroundInfo(0))
        val reporter = launch {
            var shown = -1 to false
            while (true) {
                val now = percent.get() to unpacking.get()
                if (now != shown) {
                    shown = now
                    setProgress(workDataOf(KEY_PROGRESS to now.first, KEY_UNPACKING to now.second))
                    setForeground(foregroundInfo(now.first, now.second))
                }
                delay(500)
            }
        }
        try {
            runInterruptible {
                store.prepareForDownload()
                ResumableDownloader(OkHttpClient()).download(MODEL_ARCHIVE_URL, store.archivePart) { done, total ->
                    // 99 at most until unpacked: Ready means the files are usable.
                    if (total > 0) percent.set((done * 99 / total).toInt())
                }
                unpacking.set(true)
                try {
                    ModelArchiveExtractor.extract(store.archivePart, speechModelDir(applicationContext), MODEL_FILES.toSet())
                } finally {
                    // Unpacked or broken, the archive is not needed again: a broken one would
                    // otherwise be "resumed" forever. The next Download fetches it anew.
                    store.archivePart.delete()
                }
            }
            percent.set(100)
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // The partial archive stays: the next Download continues from it.
            Result.failure()
        } finally {
            reporter.cancel()
        }
    }

    private fun foregroundInfo(percent: Int, unpacking: Boolean = false): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Speech model download", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (unpacking) "Unpacking speech model" else "Downloading speech model")
            .setContentText(if (unpacking) "A minute or two" else "$percent%")
            .setProgress(100, percent, unpacking)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_PROGRESS = "progress"
        const val KEY_UNPACKING = "unpacking"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 2
    }
}

/** Starts, cancels and deletes the speech model download, and reports its state. */
class ModelDownloads(private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)
    val store = ModelStore(speechModelDir(context))

    // Deleting files changes the state without any work changing: this re-reads the disk.
    private val filesChanged = MutableStateFlow(0)

    val state: Flow<ModelState> =
        combine(workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME), filesChanged) { infos, _ ->
            val info = infos.lastOrNull()
            val percent = info?.progress?.getInt(ModelDownloadWorker.KEY_PROGRESS, 0) ?: 0
            val unpacking = info?.progress?.getBoolean(ModelDownloadWorker.KEY_UNPACKING, false) ?: false
            modelStateOf(store.isReady(), info?.state, percent, unpacking)
        }

    fun start() {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>().build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    fun delete() {
        workManager.cancelUniqueWork(WORK_NAME)
        store.delete()
        filesChanged.value++
    }

    private companion object {
        const val WORK_NAME = "speech-model-download"
    }
}
