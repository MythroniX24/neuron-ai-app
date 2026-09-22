package com.neuron.ai.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.neuron.ai.core.log.Logger

/**
 * Storage for secrets (API keys, tokens). Implementations must never write
 * credentials to normal preferences, databases, or logs.
 */
interface SecureCredentialStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun clear()
}

/** Non-persisting fallback used only when hardware-backed encryption is unavailable. */
class InMemorySecureCredentialStore : SecureCredentialStore {

    private val values = mutableMapOf<String, String>()

    @Synchronized
    override fun put(key: String, value: String) {
        values[key] = value
    }

    @Synchronized
    override fun get(key: String): String? = values[key]

    @Synchronized
    override fun remove(key: String) {
        values.remove(key)
    }

    @Synchronized
    override fun clear() {
        values.clear()
    }
}

/**
 * Chooses the best available secure store. If the Android Keystore-backed
 * implementation cannot be created (rare device issues), it degrades to an
 * in-memory store and logs a warning — never crashes, never downgrades to
 * plain-text persistence.
 */
object SecureCredentialStoreFactory {

    fun create(context: Context, logger: Logger): SecureCredentialStore = try {
        AndroidEncryptedCredentialStore(context)
    } catch (t: Throwable) {
        logger.w("SecureStore", "Encrypted store unavailable, using in-memory fallback", t)
        InMemorySecureCredentialStore()
    }
}

private class AndroidEncryptedCredentialStore(context: Context) : SecureCredentialStore {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "neuron_secure_prefs"
    }
}
