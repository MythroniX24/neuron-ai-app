package com.neuron.ai.data.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.Logger
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Download manager for local models from the HF Hub: background streaming
 * download with progress, PAUSE/RESUME/CANCEL (HTTP Range — resumes where it
 * left off, never from zero), a Wi-Fi-only gate (model files are GB-sized),
 * sha256 integrity verification against the Hub's LFS hash, and survival of
 * app restarts (partial file + metadata file are kept; [restore] re-arms
 * them). Completed downloads register in [LocalModelRepository].
 */
class ModelDownloadManager(
    private val context: Context,
    private val repository: LocalModelRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger? = null,
    /** Used to build resolve URLs (path-safe encoding of repo id/file name). */
    private val hub: HfHubClient = HfHubClient()
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val http = OkHttpClient.Builder().build()

    /** One tracked download. */
    data class Download(
        val downloadId: String,
        val repoId: String,
        val fileName: String,
        val displayName: String,
        val totalBytes: Long,
        val downloadedBytes: Long,
        val sha256: String?,
        val state: State,
        val error: String? = null
    ) {
        enum class State { DOWNLOADING, PAUSED, WAITING_FOR_WIFI, COMPLETED, FAILED }
    }

    private val _downloads = MutableStateFlow<List<Download>>(emptyList())
    val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()

    /** Wi-Fi-only preference (persisted; defaults ON — files are GB-sized). */
    private val wifiOnlyFile = File(context.filesDir, "local-models-wifi-only.txt")

    var wifiOnly: Boolean
        get() = runCatching {
            wifiOnlyFile.exists() && wifiOnlyFile.readText().trim() == "true"
        }.getOrDefault(true)
        set(value) {
            runCatching { wifiOnlyFile.writeText(value.toString()) }
        }

    /** Active coroutine jobs, keyed by downloadId (pause/cancel handles). */
    private val jobs = linkedMapOf<String, Job>()
    /** Set when the user PAUSED — distinguishes pause from process death. */
    private val pausedIds = mutableSetOf<String>()
    /** Set while a download is BLOCKED on Wi-Fi-only (auto-resumes later). */
    private val wifiBlockedIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun stateFile(id: String) = File(repository.modelsDir, "$id.download.json")
    private fun partialFile(id: String) = File(repository.modelsDir, "$id.download.part")

    // ---- Public actions ---------------------------------------------------

    /** Starts (or resumes) a download for one GGUF variant. */
    fun start(repoId: String, variant: HfHubClient.SearchResult.Variant, displayName: String) {
        val id = stableId(repoId, variant.fileName)
        val existing = _downloads.value.firstOrNull { it.downloadId == id }
        if (existing != null && existing.state == Download.State.DOWNLOADING) return

        val entry = Download(
            downloadId = id,
            repoId = repoId,
            fileName = variant.fileName,
            displayName = displayName,
            totalBytes = variant.sizeBytes,
            downloadedBytes = partialFile(id).length().coerceAtMost(variant.sizeBytes),
            sha256 = variant.sha256,
            state = Download.State.DOWNLOADING
        )
        _downloads.value = _downloads.value.filterNot { it.downloadId == id } + entry
        pausedIds.remove(id)
        wifiBlockedIds.remove(id)
        persistState(entry)
        jobs[id] = scope.launch { runDownload(entry) }
    }

    /** Pauses: stops the transfer but keeps the partial bytes for resume. */
    fun pause(downloadId: String) {
        pausedIds.add(downloadId)
        wifiBlockedIds.remove(downloadId)
        jobs.remove(downloadId)?.cancel()
        update(downloadId) { it.copy(state = Download.State.PAUSED) }
    }

    /** Resumes a paused download from where it stopped. */
    fun resume(downloadId: String) {
        val entry = _downloads.value.firstOrNull { it.downloadId == downloadId } ?: return
        if (entry.state != Download.State.PAUSED &&
            entry.state != Download.State.WAITING_FOR_WIFI
        ) return
        pausedIds.remove(downloadId)
        wifiBlockedIds.remove(downloadId)
        val revived = entry.copy(state = Download.State.DOWNLOADING, error = null)
        update(downloadId) { revived }
        jobs[downloadId] = scope.launch { runDownload(revived) }
    }

    /** Cancels and DELETES partial bytes. */
    fun cancel(downloadId: String) {
        pausedIds.remove(downloadId)
        wifiBlockedIds.remove(downloadId)
        jobs.remove(downloadId)?.cancel()
        partialFile(downloadId).delete()
        stateFile(downloadId).delete()
        _downloads.value = _downloads.value.filterNot { it.downloadId == downloadId }
    }

    /**
     * Restart survival: re-arms persisted downloads. Ones that were actively
     * transferring resume AUTOMATICALLY (the doc always claimed this — the
     * code used to leave everything PAUSED, so a download interrupted by a
     * process death never picked up on its own); explicitly paused ones stay
     * paused. The .part file length is the authoritative byte count.
     * Called once at app start.
     */
    fun restore() {
        val files = repository.modelsDir
            .listFiles { f -> f.name.endsWith(".download.json") } ?: return
        files.forEach { stateJson ->
            val id = stateJson.name.removeSuffix(".download.json")
            runCatching {
                // repoId|fileName|displayName|totalBytes|sha256-or-dash
                val parts = stateJson.readText().trim().split("|")
                val entry = Download(
                    downloadId = id,
                    repoId = parts[0],
                    fileName = parts[1],
                    displayName = parts[2],
                    totalBytes = parts[3].toLong(),
                    downloadedBytes = partialFile(id).length(),
                    sha256 = parts.getOrNull(4)?.takeIf { it != "-" },
                    state = Download.State.PAUSED
                )
                _downloads.value = _downloads.value.filterNot {
                    it.downloadId == entry.downloadId
                } + entry
            }.onFailure { t ->
                logger?.w("Download", "State restore failed for $id", t)
                stateJson.delete()
            }
        }
        watchNetwork()
    }

    // ---- Core transfer ------------------------------------------------------

    private suspend fun runDownload(entry: Download) = withContext(dispatchers.io) {
        val partFile = partialFile(entry.downloadId)
        try {
            // Split GGUF files (-00001-of-00003) cannot run: every shard would
            // have to land first. Reject up front instead of downloading a
            // model that can never load.
            if (HfHubClient.isShardFile(entry.fileName)) {
                throw IOException("Split (multi-shard) GGUF files are not supported yet — pick a single-file quantization")
            }
            if (wifiOnly && !isUnmetered()) {
                // Not an error: the download WAITS and resumes by itself the
                // moment an unmetered network appears. Treating it as FAILED
                // (the old behaviour) is why downloads "never worked" on
                // mobile data with Wi-Fi-only on.
                waitForWifi(entry)
                return@withContext
            }
            watchNetwork()

            val url = hub.downloadUrl(entry.repoId, entry.fileName)
            val already = partFile.length()
            val builder = Request.Builder().url(url)
            if (already > 0) builder.header("Range", "bytes=$already-")
            http.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException(describeHttpFailure(resp.code, entry))
                val resumed = already > 0 && resp.code == 206
                if (already > 0 && !resumed) {
                    if (resp.code != 200) throw IOException(describeHttpFailure(resp.code, entry))
                    // Server ignored Range — restart the file cleanly.
                    partFile.delete()
                }

                val input = resp.body?.byteStream() ?: throw IOException("Empty response body")
                input.use { stream ->
                    java.io.FileOutputStream(partFile, !resumed).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var total = if (resumed) already else 0L
                        while (true) {
                            // Pause = cooperative stop; partial bytes stay on disk.
                            if (pausedIds.contains(entry.downloadId)) {
                                update(entry.downloadId) {
                                    it.copy(downloadedBytes = total, state = Download.State.PAUSED)
                                }
                                return@withContext
                            }
                            val n = stream.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            total += n
                            _downloads.value = _downloads.value.map {
                                if (it.downloadId == entry.downloadId) {
                                    it.copy(downloadedBytes = total)
                                } else it
                            }
                        }
                    }
                }
            }
            // Integrity verification reads the WHOLE file — correct for fresh
            // AND resumed downloads (no incremental hash across sessions).
            verifyAndRegister(entry, partFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // Network dropped mid-transfer (e.g. Wi-Fi → mobile data): wait for
            // an unmetered network instead of surfacing a hard failure, the
            // partial bytes stay on disk and the transfer continues later.
            if (t is IOException && wifiOnly && !isUnmetered()) {
                waitForWifi(entry)
                return@withContext
            }
            logger?.w("Download", "Download failed: ${t.message}")
            update(entry.downloadId) {
                it.copy(
                    state = when {
                        pausedIds.contains(it.downloadId) -> Download.State.PAUSED
                        wifiBlockedIds.contains(it.downloadId) -> Download.State.WAITING_FOR_WIFI
                        else -> Download.State.FAILED
                    },
                    error = t.message ?: "Download failed"
                )
            }
        }
    }

    /**
     * Puts [entry] into WAITING_FOR_WIFI and arranges for it to resume itself
     * on the next unmetered network (see [watchNetwork]).
     */
    private fun waitForWifi(entry: Download) {
        wifiBlockedIds.add(entry.downloadId)
        jobs.remove(entry.downloadId)
        update(entry.downloadId) {
            it.copy(
                state = Download.State.WAITING_FOR_WIFI,
                error = "Waiting for Wi-Fi (Wi-Fi-only is ON) — will resume automatically"
            )
        }
        watchNetwork()
    }

    /**
     * One-shot-registered network watcher: as soon as the device is on an
     * unmetered network again, every Wi-Fi-blocked download restarts from its
     * partial bytes. Registered lazily (first download / restore), never
     * twice for the same manager instance.
     */
    private fun watchNetwork() {
        if (networkCallbackRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        networkCallbackRegistered = true
        runCatching {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        if (!isUnmetered()) return
                        val blocked = wifiBlockedIds.toList()
                        blocked.forEach { id ->
                            wifiBlockedIds.remove(id)
                            resume(id)
                        }
                    }
                }
            )
        }
    }

    private var networkCallbackRegistered = false

    /**
     * Maps a Hub HTTP status onto the ACTION the user can take. Pure and
     * static so it is unit-tested: 401/403 is a gated repo (needs a HF login
     * + accepted licence), 404 a renamed file, 429 a rate limit.
     */
    fun describeHttpFailure(code: Int, entry: Download? = null): String = when (code) {
        401, 403 -> if (entry != null && entry.repoId.startsWith("google/")) {
            "Google's repos require a Hugging Face account: open ${entry.repoId} in a browser, " +
                "accept the Gemma licence, then download again."
        } else {
            "This model is gated on Hugging Face — sign in on the web, accept its licence, then retry."
        }
        404 -> "That file no longer exists in the repo (renamed upstream). Pick another quantization."
        429 -> "Hugging Face is rate-limiting this app. Wait a minute and retry."
        in 500..599 -> "Hugging Face server error (HTTP $code). Try again shortly."
        else -> "Download failed (HTTP $code from the model hub)."
    }

    /** sha256 verification against the Hub LFS hash, then registration. */
    private suspend fun verifyAndRegister(entry: Download, partFile: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        partFile.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (entry.sha256 != null && !actual.equals(entry.sha256, ignoreCase = true)) {
            partFile.delete()
            stateFile(entry.downloadId).delete()
            update(entry.downloadId) {
                it.copy(
                    state = Download.State.FAILED,
                    error = "Integrity check failed (checksum mismatch) — download deleted"
                )
            }
            return
        }

        val finalFile = File(repository.modelsDir, entry.fileName)
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            update(entry.downloadId) {
                it.copy(state = Download.State.FAILED, error = "Could not move the finished file")
            }
            return
        }
        stateFile(entry.downloadId).delete()

        val importResult = repository.registerExistingFile(
            finalFile,
            displayName = entry.displayName,
            source = LocalModelRecord.SOURCE_DOWNLOAD
        )
        importResult.fold(
            onSuccess = { record ->
                update(entry.downloadId) {
                    it.copy(state = Download.State.COMPLETED, downloadedBytes = it.totalBytes)
                }
                logger?.d("Download", "Registered ${record.displayName}")
            },
            onFailure = { t ->
                update(entry.downloadId) {
                    it.copy(state = Download.State.FAILED, error = t.message ?: "Import failed")
                }
            }
        )
    }

    // ---- Helpers -----------------------------------------------------------

    private fun update(id: String, transform: (Download) -> Download) {
        _downloads.value = _downloads.value.map {
            if (it.downloadId == id) transform(it) else it
        }
    }

    /** Metadata only — byte counts live in the .part file itself. */
    private fun persistState(entry: Download) {
        runCatching {
            stateFile(entry.downloadId).writeText(
                listOf(
                    entry.repoId, entry.fileName, entry.displayName,
                    entry.totalBytes.toString(), entry.sha256 ?: "-"
                ).joinToString("|")
            )
        }
    }

    private fun stableId(repoId: String, fileName: String): String =
        stableIdOf(repoId, fileName)

    /** true when the current network is usable AND unmetered (Wi-Fi). */
    fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        /** Deterministic per-(repo,file) id — resume works across restarts. */
        fun stableIdOf(repoId: String, fileName: String): String =
            "dl-" + (repoId + "/" + fileName).fold(0) { acc, c ->
                (acc * 31 + c.code) and 0x7FFFFFFF
            }.toString(16)

        /** Hex sha256 of [bytes] — matches the Hub's LFS oid format. */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
