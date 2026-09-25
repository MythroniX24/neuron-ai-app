package com.neuron.ai.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User-facing appearance preference. LIGHT is the product default. */
enum class ThemeMode {
    LIGHT, DARK, SYSTEM;

    val label: String
        get() = when (this) {
            LIGHT -> "Light"
            DARK -> "Dark"
            SYSTEM -> "System"
        }

    companion object {
        fun fromNameOrDefault(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: LIGHT
    }
}

interface SettingsRepository {
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    /** Last model the user picked — survives restarts and pre-fills new chats. */
    val lastModelSelection: Flow<Pair<String, String>?>
    suspend fun setLastModelSelection(providerId: String, modelId: String)
}

private val Context.neuronDataStore by preferencesDataStore(name = "neuron_settings")

/** DataStore-backed settings. Add new preferences here as the app grows. */
class SettingsRepositoryImpl(
    context: Context,
    @Suppress("UNUSED_PARAMETER") dispatchers: DispatcherProvider
) : SettingsRepository {

    private val dataStore = context.applicationContext.neuronDataStore

    override val themeMode: Flow<ThemeMode> = dataStore.data
        .map { prefs -> ThemeMode.fromNameOrDefault(prefs[KEY_THEME_MODE]) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[KEY_THEME_MODE] = mode.name }
    }

    override val lastModelSelection: Flow<Pair<String, String>?> = dataStore.data
        .map { prefs ->
            val provider = prefs[KEY_LAST_PROVIDER]
            val model = prefs[KEY_LAST_MODEL]
            if (provider != null && model != null) provider to model else null
        }

    override suspend fun setLastModelSelection(providerId: String, modelId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_PROVIDER] = providerId
            prefs[KEY_LAST_MODEL] = modelId
        }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_LAST_PROVIDER = stringPreferencesKey("last_provider_id")
        val KEY_LAST_MODEL = stringPreferencesKey("last_model_id")
    }
}
