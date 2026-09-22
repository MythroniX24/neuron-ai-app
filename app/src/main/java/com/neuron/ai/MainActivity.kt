package com.neuron.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neuron.ai.core.settings.ThemeMode
import com.neuron.ai.di.AppContainer
import com.neuron.ai.ui.NeuronApp
import com.neuron.ai.ui.theme.NeuronTheme

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (application as NeuronApplication).container

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
                NeuronApp(container = container)
            }
        }
    }
}
