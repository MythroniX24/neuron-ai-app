package com.neuron.ai.data.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Facts about the APK that is actually installed. The signing SHA-1 is the
 * single most useful thing when Google Sign-In answers DEVELOPER_ERROR: the
 * Android OAuth client must contain the package name plus *this* fingerprint,
 * and a CI-built debug APK has a different one from a local build.
 */
data class BuildInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signingSha1: String?,
    val signingSha256: String?,
    val debuggable: Boolean,
    val androidRelease: String,
    val sdkInt: Int
) {
    fun report(): String = buildString {
        appendLine("package: $packageName")
        appendLine("version: $versionName ($versionCode)")
        appendLine("debuggable: $debuggable")
        appendLine("android: $androidRelease (API $sdkInt)")
        appendLine("signing SHA-1: ${signingSha1 ?: "unavailable"}")
        appendLine("signing SHA-256: ${signingSha256 ?: "unavailable"}")
    }
}

object BuildDiagnostics {

    fun read(context: Context): BuildInfo {
        val pm = context.packageManager
        val packageName = context.packageName
        var versionName = "unknown"
        var versionCode = 0L
        var signer: ByteArray? = null
        runCatching {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            versionName = info.versionName ?: "unknown"
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
        }
        return BuildInfo(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            signingSha1 = signer?.let { fingerprint(it, "SHA-1") },
            signingSha256 = signer?.let { fingerprint(it, "SHA-256") },
            debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    /** Colon-separated uppercase hex, the format Google Cloud expects. */
    fun fingerprint(certificate: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm)
            .digest(certificate)
            .joinToString(":") { byte -> "%02X".format(byte) }
}
