package dev.okhsunrog.yamusdownloader

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object DownloadCoordinator {
    private val mutableStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val status = mutableStatus.asStateFlow()

    fun start(context: Context, trackLink: String) {
        if (mutableStatus.value.isBusy) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED && context is Activity
        ) {
            context.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        mutableStatus.value = DownloadStatus.Downloading(NativeDownloadProgress())
        val intent = Intent(context, DownloadService::class.java)
            .putExtra(DownloadService.EXTRA_TRACK_LINK, trackLink)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Throwable) {
            mutableStatus.value = DownloadStatus.Failure(
                error.message ?: "Android не разрешил запустить скачивание",
            )
        }
    }

    fun cancel(context: Context) {
        if (!mutableStatus.value.isBusy) return
        mutableStatus.value = DownloadStatus.Cancelling
        context.startService(
            Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL),
        )
    }

    fun reject(message: String) {
        if (!mutableStatus.value.isBusy) mutableStatus.value = DownloadStatus.Failure(message)
    }

    fun reset() {
        if (!mutableStatus.value.isBusy) mutableStatus.value = DownloadStatus.Idle
    }

    internal fun update(status: DownloadStatus) {
        mutableStatus.value = status
    }
}

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            DownloadCoordinator.update(DownloadStatus.Cancelling)
            NativeBridge.cancelDownload()
            updateNotification(DownloadStatus.Cancelling)
            return START_NOT_STICKY
        }
        if (downloadJob?.isActive == true) return START_NOT_STICKY

        val trackLink = intent?.getStringExtra(EXTRA_TRACK_LINK)
        val token = TokenStore(this).load()
        if (token.isBlank() || trackLink.isNullOrBlank()) {
            DownloadCoordinator.update(DownloadStatus.Failure("Не хватает данных для скачивания"))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startAsForeground()
        downloadJob = scope.launch {
            runDownload(token, trackLink)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (downloadJob?.isActive == true) NativeBridge.cancelDownload()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runDownload(token: String, trackLink: String) {
        var lastNotification = 0L
        try {
            val nativeDownload = scope.async(Dispatchers.IO) {
                NativeBridge.downloadTrack(
                    token,
                    trackLink,
                    cacheDir.resolve("downloads").absolutePath,
                )
            }
            while (nativeDownload.isActive) {
                if (DownloadCoordinator.status.value !is DownloadStatus.Cancelling) {
                    val status = DownloadStatus.Downloading(readNativeProgress())
                    DownloadCoordinator.update(status)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotification >= NOTIFICATION_INTERVAL_MS) {
                        updateNotification(status)
                        lastNotification = now
                    }
                }
                delay(100)
            }
            val path = nativeDownload.await()
            val publishing = DownloadStatus.Downloading(
                NativeDownloadProgress(phase = "publishing", cancellable = false),
            )
            DownloadCoordinator.update(publishing)
            updateNotification(publishing)
            val track = withContext(Dispatchers.IO) {
                TrackPublisher.publish(this@DownloadService, path)
            }
            DownloadCoordinator.update(DownloadStatus.Success(track))
        } catch (error: Throwable) {
            val cancelled = DownloadCoordinator.status.value is DownloadStatus.Cancelling ||
                error.message?.contains("cancelled", ignoreCase = true) == true
            DownloadCoordinator.update(
                if (cancelled) {
                    DownloadStatus.Cancelled
                } else {
                    DownloadStatus.Failure(error.message ?: "Неизвестная ошибка")
                },
            )
        }
    }

    private fun startAsForeground() {
        val notification = notification(DownloadCoordinator.status.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: DownloadStatus) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(status))
    }

    private fun notification(status: DownloadStatus): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Скачиваю трек")
            .setContentText(notificationText(status))
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(status.isBusy)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        val progress = (status as? DownloadStatus.Downloading)?.progress
        val total = progress?.total
        if (total != null && total > 0) {
            builder.setProgress(1_000, ((progress.downloaded * 1_000) / total).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (progress?.cancellable != false) {
            builder.addAction(0, "Отменить", cancelIntent)
        }
        return builder.build()
    }

    private fun notificationText(status: DownloadStatus): String = when (status) {
        is DownloadStatus.Downloading -> progressDescription(status.progress)
        DownloadStatus.Cancelling -> "Останавливаю скачивание…"
        else -> "Обрабатываю трек…"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Скачивание музыки",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        internal const val EXTRA_TRACK_LINK = "track_link"
        internal const val ACTION_CANCEL = "dev.okhsunrog.yamusdownloader.CANCEL_DOWNLOAD"
        private const val CHANNEL_ID = "track-downloads"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_INTERVAL_MS = 500L
    }
}
