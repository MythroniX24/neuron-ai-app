package com.neuron.ai.data.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.neuron.ai.MainActivity
import com.neuron.ai.NeuronApplication
import com.neuron.ai.data.local.ModelDownloadManager.Download
import com.neuron.ai.data.local.ModelDownloadManager.Download.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps model downloads and model loads alive while the app is backgrounded.
 *
 * Why this exists: a plain coroutine in [ModelDownloadManager] survives the app
 * being *minimised* - that scope is app-lifetime, not ViewModel-lifetime - but
 * it does not survive Android deciding the process no longer matters. Once the
 * process dies mid-transfer the bytes are still on disk, yet nothing restarts
 * the transfer, so the user returns to a stopped download and has to press
 * resume by hand. That is the "it quit as soon as I switched apps" bug.
 *
 * A foreground service is the supported way to say "this work matters, do not
 * kill me". It also gives a visible progress notification and the
 * exempt-from-Doze status that stops the network being throttled once the
 * device goes idle.
 *
 * The service deliberately does NOT own the transfer logic. [ModelDownloadManager]
 * already runs it on an app-level scope; this class only advertises the work to
 * the system and renders it. That keeps resume-from-disk in one place instead
 * of splitting ownership across two classes.
 */
class ModelTransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // A foreground start can be REFUSED — Android 12+ blocks it when the
        // app is not in the foreground, and some OEM policies do too. An
        // uncaught throw from a service lifecycle callback kills the process,
        // and this runs on the model-load path, so the user would see "the app
        // crashed while loading" for a notification problem. The work itself
        // lives on the app scope, so losing the foreground claim is survivable.
        runCatching { startForegroundCompat(buildNotification(null, null)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-attach on every start: START_STICKY can deliver a null intent
        // after the system rebuilt the process, and we still need to observe.
        if (observer?.isCompleted != true) {
            val container = (applicationContext as? NeuronApplication)?.container
            if (container != null) {
                observer = scope.launch { observe(container.downloadManager) }
            }
        }
        // If we get killed mid-download, come back. The manager restores byte
        // counts from the .part file, so resuming is safe.
        return START_STICKY
    }

    private suspend fun observe(manager: ModelDownloadManager) {
        combine(manager.downloads, loadingState) { downloads, loadingIds ->
            val active = downloads.firstOrNull {
                it.state == State.DOWNLOADING || it.state == State.WAITING_FOR_WIFI
            }
            active to loadingIds.firstOrNull()
        }.collect { (downloading, loadingId) ->
            if (downloading == null && loadingId == null) {
                // Nothing left worth protecting - release the foreground claim
                // so the notification disappears and the OS can reclaim us.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@collect
            }
            // Progress notifications are cosmetic: a refused/broken notify must
            // never escalate into a process crash on a model load.
            runCatching {
                notificationManager().notify(
                    NOTIFICATION_ID,
                    buildNotification(loadingId, downloading)
                )
            }
        }
    }

    private fun buildNotification(loadingId: String?, downloading: Download?): Notification {
        val title: String
        val body: String
        var progress = -1

        when {
            downloading != null -> {
                val pct = if (downloading.totalBytes > 0) {
                    (downloading.downloadedBytes * 100 / downloading.totalBytes).toInt()
                } else {
                    0
                }
                title = "Downloading ${downloading.displayName}"
                body = if (downloading.state == State.WAITING_FOR_WIFI) {
                    "$pct% - waiting for Wi-Fi"
                } else {
                    "$pct% of ${formatBytes(downloading.totalBytes)}"
                }
                progress = pct
            }

            loadingId != null -> {
                title = "Loading $loadingId"
                // Indeterminate: a GGUF mmap has no meaningful progress to show.
                body = "Preparing the model, this can take a moment"
            }

            else -> {
                title = "Preparing"
                body = "Starting"
            }
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (progress >= 0) setProgress(100, progress, false) }
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model transfers",
            // LOW: a finished download is not worth a sound, but the ongoing
            // entry must stay visible while the app is closed.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress for model downloads and model loading"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "model_transfers"
        private const val NOTIFICATION_ID = 4711

        /**
         * Ids of models currently being loaded. The service reads this so a
         * multi-GB GGUF mmap keeps the process alive even when no download is
         * in flight - loading is the other half of the same "the user is
         * waiting on something expensive" story.
         */
        private val loadingState = MutableStateFlow<Set<String>>(emptySet())

        /** Marks [modelId] as loading, or clears it when [active] is false. */
        fun setLoading(modelId: String, active: Boolean) {
            loadingState.value = loadingState.value.toMutableSet().apply {
                if (active) add(modelId) else remove(modelId)
            }
        }

        /** true when at least one model load is in flight. */
        fun isLoading(): Boolean = loadingState.value.isNotEmpty()

        /**
         * Brings the service up. Safe to call repeatedly - starting an already
         * running service just re-delivers the start intent.
         *
         * Call from a user-visible moment (the download tap, or the load
         * action): Android 12+ blocks foreground starts from the background,
         * and both of those originate from the UI.
         */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, ModelTransferService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
                // Background-start restrictions, or notifications are blocked.
                // The transfer still runs on the app scope; worst case the user
                // loses the notification and the resume-on-reopen behaviour.
            }
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.0f MB", bytes / 1_000_000.0)
            else -> "$bytes B"
        }
    }
}