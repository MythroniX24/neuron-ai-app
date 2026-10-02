package com.neuron.ai.data.local

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Minimal, dependency-free GGUF header/metadata reader used by the Local AI
 * import flow. Reads ONLY the header and metadata section — tensor data is
 * never touched. Bounds are enforced on every count/length so a corrupt or
 * hostile file can never drive unbounded reads.
 *
 * Spec: https://github.com/ggml-org/ggml/blob/master/docs/gguf.md
 * (little-endian; v1 is legacy and rejected — everything shipped for years
 * is v2/v3).
 */
object GgufReader {

    /** Everything the Settings UI needs about an imported model file. */
    data class GgufInfo(
        val version: Int,
        val tensorCount: Long,
        val architecture: String?,
        val quantization: String?,
        val contextLength: Long?,
        val blockCount: Long?,
        val metadataKVCount: Int,
        /**
         * Milestone 6: the GGUF ships a chat template that DECLARES tool
         * calling (a Jinja template with a tools / tool_calls block). This is
         * the only honest signal a local file gives about tool support, so
         * capability-aware routing keys off it instead of guessing.
         */
        val declaresToolCalling: Boolean = false
    )

    /** Hard ceiling on one metadata VALUE (strings/arrays) — malformed files fail fast. */
    private const val MAX_VALUE_BYTES = 8L * 1024 * 1024
    /** Total metadata budget — a hostile file cannot make us read forever. */
    private const val MAX_TOTAL_METADATA_BYTES = 32L * 1024 * 1024
    /** Metadata KV pair count ceiling (real models have < 100). */
    private const val MAX_KV_COUNT = 20_000

    class InvalidGgufException(message: String) : Exception(message)

    /** True when the file's first bytes are the GGUF magic — format, not extension, check. */
    fun looksLikeGguf(file: File): Boolean =
        try {
            FileInputStream(file).use { stream ->
                val magic = ByteArray(4)
                if (readFully(stream, magic)) {
                    magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                        magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
                } else {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }

    /**
     * Parses the header + metadata. Throws [InvalidGgufException] with the
     * ACTUAL reason on any malformed input — failed imports are reported,
     * never silently dropped.
     */
    fun parse(file: File): GgufInfo {
        if (!file.exists() || file.length() < 24) {
            throw InvalidGgufException("File is too small to be a GGUF model")
        }
        FileInputStream(file).use { stream ->
            val magic = ByteArray(4)
            if (!readFully(stream, magic) ||
                magic[0] != 'G'.code.toByte() || magic[1] != 'G'.code.toByte() ||
                magic[2] != 'U'.code.toByte() || magic[3] != 'F'.code.toByte()
            ) {
                throw InvalidGgufException("Not a GGUF file (missing GGUF magic bytes)")
            }
            val version = readU32Le(stream).toInt()
            if (version !in 2..3) {
                throw InvalidGgufException("Unsupported GGUF version: $version (need v2/v3)")
            }
            val tensorCount = readU64Le(stream)
            val kvCount = readU64Le(stream)
            if (kvCount > MAX_KV_COUNT) {
                throw InvalidGgufException("Metadata count $kvCount exceeds sanity limit")
            }

            var architecture: String? = null
            var quantization: String? = null
            var contextLength: Long? = null
            var blockCount: Long? = null
            var declaresToolCalling = false

            // Each KV pair: key(string) + type(uint32) + value. The TYPE is
            // ALWAYS consumed first — dispatching on it keeps the stream in
            // sync even for keys/values we don't care about.
            repeat(kvCount.toInt()) {
                val key = readString(stream)
                val type = readU32Le(stream).toInt()
                when {
                    key == "general.architecture" && type == TYPE_STRING ->
                        architecture = readString(stream)
                    key == "general.quantization" && type == TYPE_STRING ->
                        quantization = readString(stream)
                    key == "general.file_type" && type == TYPE_UINT32 ->
                        quantization = fileTypeLabel(readU32Le(stream))
                    // Architecture-scoped keys: match by SUFFIX so ordering in
                    // the file doesn't matter (architecture may come later).
                    key.endsWith(".context_length") && type == TYPE_UINT32 ->
                        contextLength = readU32Le(stream)
                    key.endsWith(".block_count") && type == TYPE_UINT32 ->
                        blockCount = readU32Le(stream)
                    // The chat template is metadata, not tensors: reading it is
                    // cheap and tells us whether the model was TRAINED for
                    // tool calling (its template declares the block).
                    key == "tokenizer.chat_template" && type == TYPE_STRING ->
                        declaresToolCalling = declaresTools(readString(stream))
                    else -> skipTypedValue(stream, type)
                }
            }

            return GgufInfo(
                version = version,
                tensorCount = tensorCount,
                architecture = architecture,
                quantization = quantization,
                contextLength = contextLength,
                blockCount = blockCount,
                metadataKVCount = kvCount.toInt(),
                declaresToolCalling = declaresToolCalling
            )
        }
    }

    /**
     * True when the model's own chat template declares tool calling. Matching
     * is deliberately conservative: a template that never mentions tools is
     * treated as "no tool support" (under-promising, never over-promising).
     */
    private fun declaresTools(chatTemplate: String): Boolean {
        val lower = chatTemplate.lowercase()
        return lower.contains("tool_calls") ||
            lower.contains("tools") ||
            lower.contains("function_call")
    }

    // ---- Value readers (all little-endian) ---------------------------------

    private const val TYPE_UINT32 = 4
    private const val TYPE_STRING = 11
    private const val TYPE_ARRAY = 12

    private fun readString(stream: InputStream): String {
        val len = readU64Le(stream)
        if (len > MAX_VALUE_BYTES) throw InvalidGgufException("Metadata string too long ($len bytes)")
        val bytes = ByteArray(len.toInt())
        if (!readFully(stream, bytes)) throw EOFException("Truncated GGUF metadata")
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Skips the VALUE of one metadata entry whose uint32 type has ALREADY
     * been consumed by the caller.
     */
    private fun skipTypedValue(stream: InputStream, type: Int) {
        when (type) {
            0, 1 -> skip(stream, 1)           // UINT8 / INT8
            2, 3 -> skip(stream, 2)           // UINT16 / INT16
            4, 5, 6, 7 -> skip(stream, 4)     // UINT32 / INT32 / FLOAT32 / BOOL
            8, 9, 10 -> skip(stream, 8)       // UINT64 / INT64 / FLOAT64
            TYPE_STRING -> {                   // STRING
                val len = readU64Le(stream)
                if (len > MAX_VALUE_BYTES) throw InvalidGgufException("String value too long")
                skip(stream, len)
            }
            TYPE_ARRAY -> {                    // ARRAY
                val elemType = readU32Le(stream).toInt()
                val count = readU64Le(stream)
                skipArray(stream, elemType, count)
            }
            else -> throw InvalidGgufException("Unknown metadata value type: $type")
        }
    }

    /** Array skipping: element sizes per GGUF type; nested arrays recurse. */
    private fun skipArray(stream: InputStream, elemType: Int, count: Long) {
        if (count < 0 || count > MAX_VALUE_BYTES) {
            throw InvalidGgufException("Array value too large ($count elements)")
        }
        when (elemType) {
            0, 1 -> skip(stream, count)
            2, 3 -> skip(stream, count * 2)
            4, 5, 6, 7 -> skip(stream, count * 4)
            8, 9, 10 -> skip(stream, count * 8)
            TYPE_STRING -> repeat(count.toInt()) {
                val len = readU64Le(stream)
                if (len > MAX_VALUE_BYTES) throw InvalidGgufException("String in array too long")
                skip(stream, len)
            }
            TYPE_ARRAY -> repeat(count.toInt()) {
                val inner = readU32Le(stream).toInt()
                val innerCount = readU64Le(stream)
                skipArray(stream, inner, innerCount)
            }
            else -> throw InvalidGgufException("Unknown array element type: $elemType")
        }
    }

    private fun readU32Le(stream: InputStream): Long {
        val b = ByteArray(4)
        if (!readFully(stream, b)) throw EOFException("Truncated GGUF header")
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }

    private fun readU64Le(stream: InputStream): Long {
        val b = ByteArray(8)
        if (!readFully(stream, b)) throw EOFException("Truncated GGUF metadata")
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (b[i].toLong() and 0xFF)
        }
        return result
    }

    private fun skip(stream: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val chunk = minOf(remaining, buffer.size.toLong()).toInt()
            val n = stream.read(buffer, 0, chunk)
            if (n < 0) throw EOFException("Truncated GGUF metadata")
            remaining -= n
        }
    }

    private fun readFully(stream: InputStream, buffer: ByteArray): Boolean {
        var off = 0
        while (off < buffer.size) {
            val n = stream.read(buffer, off, buffer.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    /** Human label for GGUF general.file_type codes (subset that matters). */
    private fun fileTypeLabel(code: Long): String? = when (code) {
        0L -> "F32"
        1L -> "F16"
        7L -> "Q8_0"
        8L -> "Q5_1"
        9L -> "Q5_0"
        10L -> "Q4_1"
        12L -> "Q4_0"
        15L -> "Q6_K"
        16L -> "Q5_K_M"
        17L -> "Q5_K_S"
        18L -> "Q4_K_M"
        19L -> "Q4_K_S"
        20L -> "Q3_K_L"
        21L -> "Q3_K_M"
        22L -> "Q3_K_S"
        24L -> "Q2_K"
        else -> null
    }
}
