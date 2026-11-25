package com.petroff.synchra.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.petroff.synchra.MainActivity
import com.petroff.synchra.R
import com.petroff.synchra.SynchraApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.petroff.synchra.config.Mode
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** Shown in the notification: whether audio is being sent (and billed) right now. */
fun recordingStatusText(isSending: Boolean, mode: Mode): String = when {
    // Free mode never sends anything, so there is nothing to tell apart.
    mode == Mode.FREE -> "Listening"
    isSending -> "Sending audio"
    else -> "Listening · nothing sent"
}

/**
 * Keeps a live session recording while the app is in the background or the screen is locked
 * (Android only allows the microphone there for a foreground service), and shows a
 * notification with what is happening to the audio and a Stop button.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    private val liveSession get() = (application as SynchraApplication).container.liveSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            liveSession.stop()
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(this, liveSession.uiState.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        watcher?.cancel()
        watcher = scope.launch {
            liveSession.uiState
                .distinctUntilChangedBy { it.isListening to it.isSending }
                .collect { state ->
                    if (!state.isListening) {
                        ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(this@RecordingService, state))
                    }
                }
        }
        // If the system kills the process, the session is gone with it; don't restart empty.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Synchra listens to the microphone"
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.petroff.synchra.action.STOP_RECORDING"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingService::class.java))
        }

        fun buildNotification(context: Context, state: SessionUiState): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context, 1,
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Synchra")
                .setContentText(recordingStatusText(state.isSending, state.mode))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                // Only the status, never conversation text — safe to show in full on the lock screen.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .apply { state.startedAt?.let { setWhen(it).setUsesChronometer(true).setShowWhen(true) } }
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .build()
        }
    }
}
