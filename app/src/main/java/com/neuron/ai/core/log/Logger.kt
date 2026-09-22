package com.neuron.ai.core.log

import android.util.Log

/**
 * Logging facade. All logging in the app must go through this interface so we can
 * swap implementations (file logging, crash reporting) and keep one redaction policy.
 */
interface Logger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)
}

private val SENSITIVE_KEY_NAMES = listOf(
    "key", "token", "secret", "password", "credential", "authorization"
)

private val REDACTION_PATTERNS = SENSITIVE_KEY_NAMES.map { name ->
    Regex("(?i)(\\b\\w*$name\\w*\\b\\s*[:=]\\s*)(\\S+)")
}

/**
 * Replaces values that look like credentials ("api_key=abc123") with "***"
 * before anything reaches the log buffer.
 */
fun redactSecrets(message: String): String {
    var result = message
    REDACTION_PATTERNS.forEach { pattern ->
        result = pattern.replace(result) { match -> "${match.groupValues[1]}***" }
    }
    return result
}

class AndroidLogger(private val baseTag: String = "NeuronAI") : Logger {

    override fun d(tag: String, message: String) {
        Log.d(baseTag, redactSecrets(message))
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        Log.w(baseTag, redactSecrets(message), error)
    }

    override fun e(tag: String, message: String, error: Throwable?) {
        Log.e(baseTag, redactSecrets(message), error)
    }
}
