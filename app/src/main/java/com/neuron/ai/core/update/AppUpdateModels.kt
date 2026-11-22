package com.neuron.ai.core.update

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Pure models for in-app updates: release metadata, assets, download progress,
 * and semantic version comparison. No Android UI dependencies.
 */

@Serializable
data class ReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val contentType: String? = null
)

@Serializable
data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseTag: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val publishedAt: String,
    val downloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val htmlUrl: String,
    val isUpdateAvailable: Boolean
)

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percent: Int
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: DownloadProgress) : DownloadState
    data class Downloaded(val apkFile: File) : DownloadState
    data class Error(val message: String) : DownloadState
}

sealed interface InstallResult {
    data object Success : InstallResult
    data object NeedsUnknownSourcesPermission : InstallResult
    data class Error(val message: String) : InstallResult
}

sealed interface AppUpdateCheckState {
    data object Idle : AppUpdateCheckState
    data object Checking : AppUpdateCheckState
    data class Available(val updateInfo: AppUpdateInfo) : AppUpdateCheckState
    data class UpToDate(val currentVersion: String, val checkedAt: Long) : AppUpdateCheckState
    data class Error(val message: String) : AppUpdateCheckState
}

/**
 * Semantic version comparator supporting formats such as "1.2.7", "v1.2.8",
 * "V2.0.0", "1.3.0-rc1", etc.
 */
object VersionComparator {

    /**
     * Returns true if remoteVersion is strictly newer than installedVersion.
     */
    fun isUpdateAvailable(installedVersion: String, remoteVersion: String): Boolean {
        return compare(installedVersion, remoteVersion) < 0
    }

    /**
     * Compares two version strings:
     *   < 0 if v1 < v2
     *   0   if v1 == v2
     *   > 0 if v1 > v2
     */
    fun compare(v1: String, v2: String): Int {
        val parsed1 = parse(v1)
        val parsed2 = parse(v2)

        val maxSegments = maxOf(parsed1.segments.size, parsed2.segments.size)
        for (i in 0 until maxSegments) {
            val seg1 = parsed1.segments.getOrElse(i) { 0 }
            val seg2 = parsed2.segments.getOrElse(i) { 0 }
            if (seg1 != seg2) {
                return seg1.compareTo(seg2)
            }
        }

        return when {
            parsed1.suffix == null && parsed2.suffix == null -> 0
            parsed1.suffix == null && parsed2.suffix != null -> 1 // v1 full release > v2 pre-release
            parsed1.suffix != null && parsed2.suffix == null -> -1 // v1 pre-release < v2 full release
            else -> (parsed1.suffix ?: "").compareTo(parsed2.suffix ?: "")
        }
    }

    /** Strips leading 'v' or 'V' and trims. */
    fun cleanVersion(version: String): String {
        return version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .trim()
    }

    private data class ParsedVersion(val segments: List<Int>, val suffix: String?)

    private fun parse(version: String): ParsedVersion {
        val cleaned = cleanVersion(version)
        val parts = cleaned.split('-', limit = 2)
        val numPart = parts.firstOrNull().orEmpty()
        val suffix = if (parts.size > 1) parts[1].trim() else null

        val segments = numPart.split('.')
            .mapNotNull { it.trim().toIntOrNull() }

        return ParsedVersion(
            segments = if (segments.isEmpty()) listOf(0) else segments,
            suffix = suffix
        )
    }
}
