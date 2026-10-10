package com.neuron.ai.core.error

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import com.neuron.ai.data.local.LocalEngineLoader
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash capture for the WHOLE process.
 *
 * Why this exists: the failures that matter most here are the ones NOTHING in
 * Kotlin can catch. A native crash inside llama.cpp / ggml / a Vulkan driver
 * kills the process instantly (SIGSEGV, or SIGABRT from a GGML_ASSERT or an
 * uncaught C++ exception), and the only symptom the user ever saw was "the app
 * simply closes when I load a model" — no message, nothing to report, no way
 * to fix it from the outside.
 *
 * Two writers feed the SAME file, so the next launch can show ONE report
 * whatever killed the process:
 *  - this class, via [install] (Java/Kotlin uncaught exceptions), and
 *  - the native bridge's signal handler (see
 *    [LocalEngineLoader.installCrashHandler]), which writes its own report at
 *    the moment the signal arrives — including the llama.cpp log tail that
 *    explains the crash.
 *
 * The file is the interface: presence of [REPORT_FILE_NAME] with a non-empty
 * body means "the previous run died". [pendingReport] reads it, [clear]
 * dismisses it, and the UI (see CrashReportScreen) shows it full-screen before
 * the user reaches anything else.
 */
object CrashRecorder {

    /** Shared with the native writer and the report screen. */
    const val REPORT_FILE_NAME = "last-crash.txt"

    /** Header line both writers emit — the UI keys off it. */
    const val HEADER = "=== NEURON CRASH REPORT ==="

    /** A report is a bug report, not a log file: keep it pasteable. */
    private const val MAX_REPORT_CHARS = 32_000

    @Volatile
    private var installed = false

    fun reportFile(context: Context): File = File(context.filesDir, REPORT_FILE_NAME)

    /**
     * Installs the process-wide uncaught-exception handler. Must be called from
     * [android.app.Application.onCreate], before anything else can fail.
     *
     * The handler RECORDS and then delegates to the previous handler: the app
     * still dies exactly as it would have (no swallowed crashes, no zombie
     * process), it just leaves an explanation behind.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Never make things worse: a failure while recording must not
            // replace the real crash with a recorder crash.
            runCatching { writeReport(appContext, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /**
     * Arms the native signal handler, which loads libneuron_llama on first
     * use. Safe to call from a background thread — it touches no UI and is a
     * no-op when the native library is absent (stub builds, JVM tests).
     */
    fun armNativeHandler(context: Context) {
        runCatching {
            LocalEngineLoader.installCrashHandler(
                reportFile(context.applicationContext).absolutePath
            )
        }
    }

    /** The report left by the PREVIOUS run, or null when that run was clean. */
    fun pendingReport(context: Context): String? = runCatching {
        val file = reportFile(context)
        if (!file.exists() || file.length() == 0L) return@runCatching null
        file.readText().take(MAX_REPORT_CHARS)
    }.getOrNull()

    /** Drops the stored report (the report screen's Dismiss / Continue). */
    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    private fun writeReport(context: Context, thread: Thread, error: Throwable) {
        val body = buildString {
            appendLine(HEADER)
            appendLine("kind: java")
            appendLine("when: ${stamp()}")
            appendLine("app: ${appVersion(context)}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("thread: ${thread.name}")
            appendLine("memory: ${memoryLine(context)}")
            appendLine()
            appendLine("--- exception chain ---")
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < 6) {
                if (depth > 0) appendLine("  caused by: ${cause.javaClass.name}: ${cause.message}")
                else appendLine("${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            appendLine()
            appendLine("--- stack trace ---")
            append(StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString())
            appendLine()
            appendLine("--- last llama.cpp log lines ---")
            appendLine(nativeLogTail())
        }
        // Written through a temp file + rename so a report is never read
        // half-written (the process is dying, after all).
        val file = reportFile(context)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body.take(MAX_REPORT_CHARS))
        if (!tmp.renameTo(file)) {
            file.writeText(body.take(MAX_REPORT_CHARS))
            tmp.delete()
        }
    }

    /**
     * llama.cpp's own last words. Guarded on every level: this runs on the
     * crashing thread, so it must never throw and never load the library
     * itself (the JVM unit-test path has no .so at all).
     */
    private fun nativeLogTail(): String = runCatching {
        if (!LocalEngineLoader.nativeLibraryAvailable) {
            return@runCatching "(native inference library not loaded in this run)"
        }
        LocalEngineLoader.nativeLogTail(200).ifBlank { "(the engine log was empty)" }
    }.getOrElse { "(could not read the engine log: ${it.message})" }

    private fun memoryLine(context: Context): String = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        "total=${gb(info.totalMem)}, avail=${gb(info.availMem)}, lowMemory=${info.lowMemory}, " +
            "javaHeapUsed=${gb(runtime.totalMemory() - runtime.freeMemory())}, " +
            "javaHeapMax=${gb(runtime.maxMemory())}, " +
            "nativeHeapAllocated=${gb(Debug.getNativeHeapAllocatedSize())}"
    }.getOrElse { "unavailable (${it.message})" }

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        // versionCode (not longVersionCode): minSdk is 26 and the long form
        // only exists from API 28.
        "${info.versionName} (${info.versionCode})"
    }.getOrElse { "unknown" }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun gb(bytes: Long): String =
        String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
