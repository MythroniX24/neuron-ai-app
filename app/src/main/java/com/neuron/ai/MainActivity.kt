package com.neuron.ai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neuron.ai.core.settings.ThemeMode
import com.neuron.ai.data.storage.DeviceStorage
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.NeuronApp
import com.neuron.ai.ui.theme.NeuronTheme

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer
    private var hasPromptedStorageOnLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (application as NeuronApplication).container

        // Startup storage permission flow: prompt user immediately on app launch if missing
        checkStoragePermissionOnLaunch()

        setContent {
            val settings = container.settingsRepository
            val themeMode by settings.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.LIGHT)

            // SYSTEM must follow the OS setting, not force light.
            val darkMode = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            NeuronTheme(darkMode = darkMode) {
                // Sync system-bar icon appearance with the APP theme, not the
                // OS theme — otherwise app-dark + system-light renders
                // invisible white status-bar icons over a light top bar.
                val view = androidx.compose.ui.platform.LocalView.current
                if (!view.isInEditMode) {
                    androidx.compose.runtime.LaunchedEffect(darkMode) {
                        val window = (view.context as? android.app.Activity)?.window
                        if (window != null) {
                            val controller =
                                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                            controller.isAppearanceLightStatusBars = !darkMode
                            controller.isAppearanceLightNavigationBars = !darkMode
                        }
                    }
                }
                // One full-screen image viewer above every screen: a tapped
                // picture (reply image or card thumbnail) opens here with a
                // close cross and a download button.
                // In-app file viewer (code with syntax highlighting, HTML with
                // a Preview tab, download) sits above every screen so chat
                // cards can open a file without leaving the app.
                com.neuron.ai.ui.files.FileViewerHost {
                    com.neuron.ai.ui.components.ImageViewerHost {
                        NeuronApp(container = container)
                        // Full-screen report left by the PREVIOUS run when it crashed.
                        // Rendered as a dialog so it sits above every screen, and it
                        // shows nothing at all after a clean launch.
                        com.neuron.ai.ui.CrashReportGate()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If permission wasn't granted yet and wasn't prompted, check on resume
        if (!hasPromptedStorageOnLaunch && !DeviceStorage.hasAccess(this)) {
            checkStoragePermissionOnLaunch()
        }
    }

    private fun checkStoragePermissionOnLaunch() {
        if (!hasPromptedStorageOnLaunch && !DeviceStorage.hasAccess(this)) {
            hasPromptedStorageOnLaunch = true
            promptStorageAccess()
        }
    }

    private fun promptStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = DeviceStorage.accessIntent(this)
                ?: Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            runCatching { startActivity(intent) }
        } else {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                1001
            )
        }
    }
}
