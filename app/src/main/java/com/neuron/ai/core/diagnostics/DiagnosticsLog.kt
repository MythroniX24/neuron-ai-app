package com.neuron.ai.core.diagnostics

/**
 * One line in the in-app diagnostics feed. Deliberately plain data so it can
 * be rendered by the Debug console and copied into a bug report without
 * logcat, and so behaviour stays unit-testable.
 */
data class DiagnosticEntry(
    val atMs: Long,
    val tag: String,
    val message: String,
    val detail: String? = null
) {
    fun asLine(): String = buildString {
        append('[').append(tag).append("] ").append(message)
        detail?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
    }
}

/**
 * Bounded, thread-safe ring buffer for product-level diagnostics (Google
 * connect, image lookup, reply layout). Unlike the logcat logger this is meant
 * to be read by the user in Settings → Debug console, so it keeps the most
 * recent [capacity] entries and never throws.
 */
class DiagnosticsLog(private val capacity: Int = 200) {

    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticEntry>()

    fun log(tag: String, message: String, detail: String? = null) {
        val entry = DiagnosticEntry(
            atMs = System.currentTimeMillis(),
            tag = tag.trim().uppercase(),
            message = message,
            detail = detail
        )
        synchronized(lock) {
            if (capacity <= 0) return
            buffer.addLast(entry)
            while (buffer.size > capacity) buffer.removeFirst()
        }
    }

    /** Newest last — the order the Debug console reads. */
    fun snapshot(): List<DiagnosticEntry> = synchronized(lock) { buffer.toList() }

    fun entriesFor(tag: String): List<DiagnosticEntry> = synchronized(lock) {
        val wanted = tag.trim().uppercase()
        buffer.filter { it.tag == wanted }
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }
}
