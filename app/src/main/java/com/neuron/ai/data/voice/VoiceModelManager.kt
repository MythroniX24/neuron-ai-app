package com.neuron.ai.data.voice

import android.content.Context
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.log.Logger
import com.neuron.ai.core.voice.VoiceModelDefinition
import com.neuron.ai.core.voice.VoiceModelStatus
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class VoiceModelManager(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger? = null,
    httpClient: OkHttpClient? = null
) {
    private val client: OkHttpClient = (httpClient ?: OkHttpClient()).newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    val modelsDir = File(context.filesDir, "voice_models").also { it.mkdirs() }

    private val _statusMap = MutableStateFlow<Map<String, VoiceModelStatus>>(emptyMap())
    val statusMap: StateFlow<Map<String, VoiceModelStatus>> = _statusMap.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        refreshStatuses()
    }

    fun getModelFile(definition: VoiceModelDefinition): File {
        return File(modelsDir, definition.fileName)
    }

    fun isModelDownloaded(definition: VoiceModelDefinition): Boolean {
        val file = getModelFile(definition)
        return file.exists() && file.length() > 0
    }

    fun refreshStatuses() {
        val current = mutableMapOf<String, VoiceModelStatus>()
        VoiceModelDefinition.PRESET_MODELS.forEach { def ->
            val file = getModelFile(def)
            if (file.exists() && file.length() >= (def.sizeBytes * 0.90)) {
                current[def.id] = VoiceModelStatus.Downloaded
            } else if (downloadJobs.containsKey(def.id)) {
                // Keep active downloading state
                current[def.id] = _statusMap.value[def.id] ?: VoiceModelStatus.NotDownloaded
            } else {
                current[def.id] = VoiceModelStatus.NotDownloaded
            }
        }
        _statusMap.value = current
    }

    fun downloadModel(definition: VoiceModelDefinition) {
        if (downloadJobs.containsKey(definition.id)) return

        val targetFile = getModelFile(definition)
        val tempFile = File(modelsDir, "${definition.fileName}.tmp")

        val job = scope.launch {
            _statusMap.update { it + (definition.id to VoiceModelStatus.Downloading(0, 0, definition.sizeBytes)) }
            try {
                val urlsToTry = listOfNotNull(definition.downloadUrl, definition.fallbackUrl).distinct()
                var downloadedSuccessfully = false
                var lastException: Exception? = null

                for (url in urlsToTry) {
                    try {
                        val request = Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                            .header("Accept", "*/*")
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException("HTTP ${response.code} downloading ${definition.name} from $url")
                            }
                            val body = response.body ?: throw IOException("Empty response body from $url")
                            val totalBytes = if (body.contentLength() > 0) body.contentLength() else definition.sizeBytes

                            tempFile.parentFile?.mkdirs()
                            body.byteStream().use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var bytesRead: Int
                                    var downloaded = 0L
                                    var lastPercent = 0

                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                        downloaded += bytesRead
                                        val percent = ((downloaded.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                                        if (percent != lastPercent) {
                                            lastPercent = percent
                                            _statusMap.update {
                                                it + (definition.id to VoiceModelStatus.Downloading(percent, downloaded, totalBytes))
                                            }
                                        }
                                    }
                                    output.flush()
                                }
                            }

                            if (tempFile.exists() && tempFile.length() > 0) {
                                if (targetFile.exists()) targetFile.delete()
                                if (tempFile.renameTo(targetFile)) {
                                    downloadedSuccessfully = true
                                    _statusMap.update { it + (definition.id to VoiceModelStatus.Downloaded) }
                                    logger?.d("VoiceModelManager", "Downloaded voice model: ${definition.name}")
                                    return@launch
                                } else {
                                    throw IOException("Failed to rename temporary download file to target")
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastException = e
                        logger?.w("VoiceModelManager", "Failed mirror $url for ${definition.name}: ${e.message}")
                    }
                }

                if (!downloadedSuccessfully) {
                    throw lastException ?: IOException("Failed to download ${definition.name} from any available source")
                }
            } catch (e: CancellationException) {
                tempFile.delete()
                _statusMap.update { it + (definition.id to VoiceModelStatus.NotDownloaded) }
            } catch (e: Exception) {
                logger?.w("VoiceModelManager", "Download failed for ${definition.name}: ${e.message}", e)
                tempFile.delete()
                _statusMap.update { it + (definition.id to VoiceModelStatus.Error(e.message ?: "Download failed")) }
            } finally {
                downloadJobs.remove(definition.id)
            }
        }
        downloadJobs[definition.id] = job
    }

    fun cancelDownload(definition: VoiceModelDefinition) {
        downloadJobs[definition.id]?.cancel()
        downloadJobs.remove(definition.id)
        File(modelsDir, "${definition.fileName}.tmp").delete()
        _statusMap.update { it + (definition.id to VoiceModelStatus.NotDownloaded) }
    }

    fun deleteModel(definition: VoiceModelDefinition) {
        cancelDownload(definition)
        val file = getModelFile(definition)
        if (file.exists()) {
            file.delete()
        }
        _statusMap.update { it + (definition.id to VoiceModelStatus.NotDownloaded) }
    }
}
