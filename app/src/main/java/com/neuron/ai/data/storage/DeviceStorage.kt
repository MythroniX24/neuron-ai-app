package com.neuron.ai.data.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Whole-device (shared storage) access for the agent's file tools.
 *
 * The agent is meant to work like a computer assistant — read and edit any
 * file the user owns (Downloads, Documents, DCIM, project folders…). Android
 * only allows that with "All files access" (MANAGE_EXTERNAL_STORAGE) on
 * Android 11+, or the storage runtime permission on older releases.
 *
 * When the permission is missing [root] returns null instead of a directory
 * the process cannot actually read, so the tools answer with an actionable
 * message rather than "file not found".
 */
object DeviceStorage {

    fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Shared storage root, or null when the app may not read it. */
    fun root(context: Context): File? {
        if (!hasAccess(context)) return null
        return runCatching { Environment.getExternalStorageDirectory() }
            .getOrNull()
            ?.takeIf { it.isDirectory }
    }

    /** System screen where the user grants "All files access" (Android 11+). */
    fun accessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val appSpecific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        return if (appSpecific.resolveActivity(context.packageManager) != null) {
            appSpecific
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }
}
