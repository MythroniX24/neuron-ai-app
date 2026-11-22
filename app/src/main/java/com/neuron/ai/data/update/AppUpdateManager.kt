package com.neuron.ai.data.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.neuron.ai.BuildConfig
import com.neuron.ai.MainActivity
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.update.AppUpdateCheckState
import com.neuron.ai.core.update.AppUpdateInfo
import com.neuron.ai.core.update.DownloadProgress
import com.neuron.ai.core.update.DownloadState
import com.neuron.ai.core.update.InstallResult
import com.neuron.ai.core.update.ReleaseAsset
import com.neuron.ai.core.update.VersionComparator
import com.neuron.ai.ui.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manages GitHub releases checks, APK downloads with progress reporting,
 * system notifications, and package installer interactions.
 */
class AppUpdateManager(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val gitHubTokenProvider: (() -> String?)? = null,
    client: OkHttpClient? = null
) {
    companion object {
        const val REPO_OWNER = "MythroniX24"
        const val REPO_NAME = "neuron-ai-app"
        const val GITHUB_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

        const val CHANNEL_UPDATES_ID = "neuron_app_updates"
        const val CHANNEL_PROGRESS_ID = "neuron_update_progress"
        const val NOTIFICATION_UPDATE_ID = 9001
        const val NOTIFICATION_PROGRESS_ID = 9002

        private const val PREFS_NAME = "neuron_app_update_prefs"
        private const val KEY_LAST_PROMPTED_VERSION = "last_prompted_version"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val httpClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val _checkState = MutableStateFlow<AppUpdateCheckState>(AppUpdateCheckState.Idle)
    val checkState: StateFlow<AppUpdateCheckState> = _checkState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var activeDownloadJob: Job? = null
    private var activeDownloadCall: okhttp3.Call? = null

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            // Channel for update alerts
            val updateChannel = NotificationChannel(
                CHANNEL_UPDATES_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when a new version of Neuron-AI is available"
            }
            manager.createNotificationChannel(updateChannel)

            // Channel for download progress
            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS_ID,
                "Update Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live download progress for app updates"
                setShowBadge(false)
            }
            manager.createNotificationChannel(progressChannel)
        }
    }

    /**
     * Queries GitHub for the latest release, comparing it to the currently installed version.
     */
    suspend fun checkForUpdate(notifyIfAvailable: Boolean = true): Result<AppUpdateInfo> =
        withContext(dispatchers.io) {
            _checkState.value = AppUpdateCheckState.Checking

            try {
                val requestBuilder = Request.Builder()
                    .url(GITHUB_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "Neuron-AI-App/${BuildConfig.VERSION_NAME}")

                gitHubTokenProvider?.invoke()?.let { token ->
                    if (token.isNotBlank()) {
                        requestBuilder.header("Authorization", "Bearer $token")
                    }
                }

                val response = httpClient.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    val errMsg = when (response.code) {
                        404 -> "No release published yet for $REPO_NAME"
                        403 -> "GitHub API rate limit exceeded. Please try again later."
                        else -> "Failed to check update (HTTP ${response.code})"
                    }
                    _checkState.value = AppUpdateCheckState.Error(errMsg)
                    return@withContext Result.failure(IOException(errMsg))
                }

                val responseBody = response.body?.string() ?: ""
                val rootJson = json.parseToJsonElement(responseBody).jsonObject

                val tagName = rootJson["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val releaseName = rootJson["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: tagName
                val bodyMarkdown = rootJson["body"]?.jsonPrimitive?.content.orEmpty()
                val publishedAt = rootJson["published_at"]?.jsonPrimitive?.content.orEmpty()
                val htmlUrl = rootJson["html_url"]?.jsonPrimitive?.content.orEmpty()

                val rawAssets = rootJson["assets"]?.jsonArray.orEmpty()
                val parsedAssets = rawAssets.mapNotNull { element ->
                    val obj = element.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                    val downloadUrl = obj["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val contentType = obj["content_type"]?.jsonPrimitive?.content
                    ReleaseAsset(
                        name = name,
                        sizeBytes = size,
                        downloadUrl = downloadUrl,
                        contentType = contentType
                    )
                }

                // Prefer .apk files, favoring "app-release.apk" or any file ending with .apk
                val apkAsset = parsedAssets.firstOrNull { it.name.equals("app-release.apk", ignoreCase = true) }
                    ?: parsedAssets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

                val currentVersion = BuildConfig.VERSION_NAME
                val latestCleanVersion = VersionComparator.cleanVersion(tagName)
                val isUpdateAvailable = VersionComparator.isUpdateAvailable(currentVersion, tagName) && apkAsset != null

                val updateInfo = AppUpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = latestCleanVersion,
                    releaseTag = tagName,
                    releaseTitle = releaseName,
                    releaseNotes = bodyMarkdown,
                    publishedAt = publishedAt,
                    downloadUrl = apkAsset?.downloadUrl.orEmpty(),
                    apkFileName = apkAsset?.name ?: "neuron-ai-$tagName.apk",
                    apkSizeBytes = apkAsset?.sizeBytes ?: 0L,
                    htmlUrl = htmlUrl,
                    isUpdateAvailable = isUpdateAvailable
                )

                prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()

                if (isUpdateAvailable) {
                    _checkState.value = AppUpdateCheckState.Available(updateInfo)
                    if (notifyIfAvailable && shouldNotify(tagName)) {
                        postUpdateNotification(updateInfo)
                        markNotified(tagName)
                    }
                } else {
                    _checkState.value = AppUpdateCheckState.UpToDate(currentVersion, System.currentTimeMillis())
                }

                Result.success(updateInfo)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errorMsg = e.localizedMessage ?: "Failed to reach GitHub"
                _checkState.value = AppUpdateCheckState.Error(errorMsg)
                Result.failure(e)
            }
        }

    /**
     * Checks if the app update was already downloaded and is ready in cache.
     */
    fun getDownloadedApk(releaseTag: String, expectedSizeBytes: Long): File? {
        val updatesDir = File(context.cacheDir, "updates")
        val apkFile = File(updatesDir, "neuron-ai-$releaseTag.apk")
        return if (apkFile.exists() && (expectedSizeBytes <= 0L || apkFile.length() == expectedSizeBytes)) {
            apkFile
        } else {
            null
        }
    }

    /**
     * Downloads the APK file to the app's cache directory with streaming progress.
     */
    suspend fun downloadApk(
        updateInfo: AppUpdateInfo,
        scope: CoroutineScope
    ): Result<File> = withContext(dispatchers.io) {
        val existing = getDownloadedApk(updateInfo.releaseTag, updateInfo.apkSizeBytes)
        if (existing != null) {
            _downloadState.value = DownloadState.Downloaded(existing)
            return@withContext Result.success(existing)
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(updatesDir, "neuron-ai-${updateInfo.releaseTag}.apk")
        val tempFile = File(updatesDir, "neuron-ai-${updateInfo.releaseTag}.apk.tmp")

        try {
            if (tempFile.exists()) tempFile.delete()

            val request = Request.Builder()
                .url(updateInfo.downloadUrl)
                .header("User-Agent", "Neuron-AI-App/${BuildConfig.VERSION_NAME}")
                .build()

            val call = httpClient.newCall(request)
            activeDownloadCall = call

            _downloadState.value = DownloadState.Downloading(
                DownloadProgress(0L, updateInfo.apkSizeBytes, 0)
            )
            showDownloadProgressNotification(0, updateInfo.releaseTag)

            val response = call.execute()
            if (!response.isSuccessful) {
                val err = "Download failed: HTTP ${response.code}"
                _downloadState.value = DownloadState.Error(err)
                dismissProgressNotification()
                return@withContext Result.failure(IOException(err))
            }

            val body = response.body ?: throw IOException("Empty response body")
            val totalBytes = if (body.contentLength() > 0) body.contentLength() else updateInfo.apkSizeBytes

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalDownloaded = 0L
                    var lastReportPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead

                        val percent = if (totalBytes > 0) {
                            ((totalDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else 0

                        if (percent != lastReportPercent) {
                            lastReportPercent = percent
                            _downloadState.value = DownloadState.Downloading(
                                DownloadProgress(totalDownloaded, totalBytes, percent)
                            )
                            if (percent % 5 == 0 || percent == 100) {
                                showDownloadProgressNotification(percent, updateInfo.releaseTag)
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }

            _downloadState.value = DownloadState.Downloaded(targetFile)
            showDownloadCompleteNotification(targetFile, updateInfo.releaseTag)
            Result.success(targetFile)
        } catch (e: Exception) {
            if (tempFile.exists()) runCatching { tempFile.delete() }
            dismissProgressNotification()
            if (e is CancellationException) {
                _downloadState.value = DownloadState.Idle
                throw e
            }
            val msg = e.localizedMessage ?: "Download failed"
            _downloadState.value = DownloadState.Error(msg)
            Result.failure(e)
        } finally {
            activeDownloadCall = null
        }
    }

    /**
     * Cancels an ongoing APK download.
     */
    fun cancelDownload() {
        activeDownloadCall?.cancel()
        activeDownloadJob?.cancel()
        dismissProgressNotification()
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Triggers the Android package installer for the downloaded APK file.
     */
    fun installApk(apkFile: File): InstallResult {
        if (!apkFile.exists()) {
            return InstallResult.Error("APK file does not exist")
        }

        // On Android 8.0+ (Oreo), verify unknown sources permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                return InstallResult.NeedsUnknownSourcesPermission
            }
        }

        return try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            InstallResult.Success
        } catch (e: Exception) {
            InstallResult.Error("Could not launch package installer: ${e.localizedMessage}")
        }
    }

    /**
     * Opens the Android Settings screen for enabling install permissions for this app.
     */
    fun requestUnknownSourcesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    // =========================================================================
    // Launch Popup & Notification Tracking
    // =========================================================================

    /**
     * Checks if the launch popup should be displayed for this version tag.
     * Guaranteed to return true at most once per release tag.
     */
    fun shouldPromptOnLaunch(releaseTag: String): Boolean {
        val lastPrompted = prefs.getString(KEY_LAST_PROMPTED_VERSION, null)
        return lastPrompted != releaseTag
    }

    /**
     * Records that the launch popup has been shown/dismissed for this release tag.
     */
    fun dismissPrompt(releaseTag: String) {
        prefs.edit().putString(KEY_LAST_PROMPTED_VERSION, releaseTag).apply()
    }

    /**
     * Checks if a system notification has already been posted for this release tag.
     */
    private fun shouldNotify(releaseTag: String): Boolean {
        val lastNotified = prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)
        return lastNotified != releaseTag
    }

    private fun markNotified(releaseTag: String) {
        prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, releaseTag).apply()
    }

    /**
     * Posts a system notification alerting the user that a new update is ready.
     */
    fun postUpdateNotification(updateInfo: AppUpdateInfo) {
        if (!hasNotificationPermission()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("navigate_to", Routes.APP_UPDATE)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_UPDATE_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("New Update Available: v${updateInfo.latestVersion}")
            .setContentText("Neuron-AI ${updateInfo.releaseTag} is available. Tap to view and install.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "A new version (${updateInfo.releaseTag}) is available with improvements and fixes. Tap to open the update screen."
            ))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATE_ID, notification)
        }
    }

    private fun showDownloadProgressNotification(percent: Int, tag: String) {
        if (!hasNotificationPermission()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("navigate_to", Routes.APP_UPDATE)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_PROGRESS_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading Neuron-AI $tag")
            .setContentText("$percent% downloaded")
            .setProgress(100, percent, false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_PROGRESS_ID, notification)
        }
    }

    private fun showDownloadCompleteNotification(apkFile: File, tag: String) {
        if (!hasNotificationPermission()) return

        dismissProgressNotification()

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("navigate_to", Routes.APP_UPDATE)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_UPDATE_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete: $tag")
            .setContentText("Neuron-AI is ready to install. Tap to proceed.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATE_ID, notification)
        }
    }

    private fun dismissProgressNotification() {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_PROGRESS_ID)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}
