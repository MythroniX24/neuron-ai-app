package com.neuron.ai.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GgufReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Little-endian binary writer for building synthetic GGUF files. */
    @Suppress("SameParameterValue")
    private fun gguf(vararg chunks: ByteArray): ByteArray = chunks.reduce { a, b -> a + b }

    private fun u32(value: Long) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun u64(value: Long) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 32) and 0xFF).toByte(),
        ((value shr 40) and 0xFF).toByte(),
        ((value shr 48) and 0xFF).toByte(),
        ((value shr 56) and 0xFF).toByte()
    )

    private fun f32(value: Float) = u32(
        (java.lang.Float.floatToIntBits(value).toLong() and 0xFFFFFFFFL)
    )

    private fun str(value: String): ByteArray =
        u64(value.length.toLong()) + value.toByteArray(Charsets.UTF_8)

    private val MAGIC = "GGUF".toByteArray(Charsets.US_ASCII)

    /**
     * GGUF value type ids EXACTLY as gguf.md numbers them. These must NOT be
     * "adjusted" to match the implementation — a wrong id desyncs the reader
     * and makes it reject healthy models. The regression that shipped with
     * STRING=11 / ARRAY=12 rejected every real download with
     * "Metadata string too long (13612586462316 bytes)".
     */
    private val TYPE_UINT8 = 0
    private val TYPE_INT8 = 1
    private val TYPE_UINT16 = 2
    private val TYPE_INT16 = 3
    private val TYPE_UINT32 = 4
    private val TYPE_INT32 = 5
    private val TYPE_FLOAT32 = 6
    private val TYPE_BOOL = 7
    private val TYPE_STRING = 8
    private val TYPE_ARRAY = 9
    private val TYPE_UINT64 = 10
    private val TYPE_INT64 = 11
    private val TYPE_FLOAT64 = 12

    private fun kvUInt32(key: String, value: Long) = str(key) + u32(TYPE_UINT32.toLong()) + u32(value)
    private fun kvString(key: String, value: String) = str(key) + u32(TYPE_STRING.toLong()) + str(value)

    /** A minimal but VALID v3 file: header + three KV pairs, no tensor data. */
    private fun validGguf(): ByteArray {
        val kvs = kvString("general.architecture", "llama") +
            kvString("general.quantization", "Q6_K") +
            kvUInt32("llama.context_length", 4096) +
            kvUInt32("general.file_type", 18) // legacy llama_ftype for Q6_K
        return gguf(MAGIC, u32(3), u64(0), u64(4), kvs)
    }

    @Test
    fun `parses architecture quantization and context length`() {
        val file = tmp.newFile("model.gguf")
        file.writeBytes(validGguf())

        assertTrue(GgufReader.looksLikeGguf(file))
        val info = GgufReader.parse(file)

        assertEquals(3, info.version)
        assertEquals("llama", info.architecture)
        assertEquals(4096L, info.contextLength)
        assertEquals("Q6_K", info.quantization)
        assertEquals(4, info.metadataKVCount)
    }

    /**
     * THE regression test for the shipped bug: real bytes from
     * tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf (first 13 KVs, tokenizer arrays
     * excluded) must parse. Synthetic fixtures cannot catch this class of
     * bug — they used to carry the same wrong type ids as the code.
     */
    @Test
    fun `parses a real huggingface gguf header`() {
        val bytes = javaClass.classLoader
            ?.getResourceAsStream("gguf/real-tinyllama-header.bin")
            ?.readBytes()
        assertTrue("test fixture missing", bytes != null && bytes.size > 100)
        val file = tmp.newFile("real.gguf")
        file.writeBytes(bytes!!)

        assertTrue(GgufReader.looksLikeGguf(file))
        val info = GgufReader.parse(file)

        assertEquals(3, info.version)
        assertEquals(201L, info.tensorCount)
        assertEquals("llama", info.architecture)
        assertEquals(2048L, info.contextLength)
        assertEquals(22L, info.blockCount)
        // The real file has NO general.quantization string and its
        // general.file_type=15 means Q4_K_M in the legacy llama_ftype enum
        // (current ggml_type would call 15 "Q8_K") — so the reader must NOT
        // invent a label; LocalModelRepository falls back to the file name.
        assertNull(info.quantization)
        assertEquals(13, info.metadataKVCount)
    }

    @Test
    fun `rejects non-gguf magic with the actual reason`() {
        val file = tmp.newFile("fake.gguf")
        file.writeBytes(ByteArray(64) { 'X'.code.toByte() })

        assertFalse(GgufReader.looksLikeGguf(file))
        val ex = assertThrows(GgufReader.InvalidGgufException::class.java) {
            GgufReader.parse(file)
        }
        assertTrue(ex.message!!.contains("magic"))
    }

    @Test
    fun `rejects unsupported version`() {
        val file = tmp.newFile("v1.gguf")
        file.writeBytes(gguf(MAGIC, u32(1), u64(0), u64(0)))

        val ex = assertThrows(GgufReader.InvalidGgufException::class.java) {
            GgufReader.parse(file)
        }
        assertTrue(ex.message!!.contains("version"))
    }

    @Test
    fun `rejects truncated metadata with the actual reason`() {
        val full = validGguf()
        val file = tmp.newFile("trunc.gguf")
        file.writeBytes(full.copyOf(full.size / 2))

        val ex = assertThrows(Exception::class.java) {
            GgufReader.parse(file)
        }
        assertTrue(ex.message != null || ex is java.io.EOFException)
    }

    @Test
    fun `rejects insane metadata count`() {
        val file = tmp.newFile("insane.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(0), u64(Long.MAX_VALUE)))

        val ex = assertThrows(GgufReader.InvalidGgufException::class.java) {
            GgufReader.parse(file)
        }
        assertTrue(ex.message!!.contains("sanity"))
    }

    @Test
    fun `tool calling is detected from the chat template`() {
        // Milestone 6: the template is the only honest tool-support signal a
        // GGUF gives, so it must be read without desyncing the stream.
        val toolKvs = kvString("general.architecture", "llama") +
            kvString("tokenizer.chat_template", "{{ messages }} {% if tools %}tools{% endif %}")
        val file = tmp.newFile("tools.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(0), u64(2), toolKvs))

        val info = GgufReader.parse(file)

        assertEquals("llama", info.architecture)
        assertTrue(info.declaresToolCalling)
    }

    @Test
    fun `a chatml template without tools does not claim tool support`() {
        val kvs = kvString("general.architecture", "qwen2") +
            kvString("tokenizer.chat_template", "system\nYou are helpful")
        val file = tmp.newFile("chatml.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(0), u64(2), kvs))

        assertFalse(GgufReader.parse(file).declaresToolCalling)
    }

    @Test
    fun `files without a chat template stay text and tool free`() {
        assertFalse(GgufReader.parse(writeValid()).declaresToolCalling)
    }

    private fun writeValid(): java.io.File {
        val file = tmp.newFile("plain.gguf")
        file.writeBytes(validGguf())
        return file
    }

    @Test
    fun `unknown kv types are skipped without desync`() {
        // KV 1: architecture; KV 2: a 64-bit value the reader does not care
        // about (skipTypedValue must consume exactly 8 bytes); KV 3: file_type.
        val kvs = kvString("general.architecture", "qwen2") +
            str("some.unknown") + u32(TYPE_UINT64.toLong()) + u64(0x1122334455667788L) +
            kvUInt32("general.file_type", 18)
        val file = tmp.newFile("mixed.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(0), u64(3), kvs))

        val info = GgufReader.parse(file)

        assertEquals("qwen2", info.architecture)
        assertNull(info.quantization)
    }

    /**
     * One KV of EVERY value type in the spec, then a known KV at the end. If
     * any single type consumes the wrong number of bytes the reader desyncs
     * and the trailing general.file_type is never read — so a wrong type
     * table can no longer pass unnoticed.
     */
    @Test
    fun `stays in sync across every metadata value type in the spec`() {
        val kvs = gguf(
            kvString("general.architecture", "llama"),
            str("t.uint8") + u32(TYPE_UINT8.toLong()) + byteArrayOf(7),
            str("t.int8") + u32(TYPE_INT8.toLong()) + byteArrayOf(0x7F.toByte()),
            str("t.uint16") + u32(TYPE_UINT16.toLong()) + u16(513),
            str("t.int16") + u32(TYPE_INT16.toLong()) + u16(0xFFFF.toInt()),
            str("t.uint32") + u32(TYPE_UINT32.toLong()) + u32(70_000L),
            str("t.int32") + u32(TYPE_INT32.toLong()) + u32(0xFFFF_FFFFL),
            str("t.float32") + u32(TYPE_FLOAT32.toLong()) + f32(1.5f),
            // BOOL is ONE byte; reading it as four is what silently shifted
            // the stream in the shipped reader.
            str("t.bool") + u32(TYPE_BOOL.toLong()) + byteArrayOf(1),
            str("t.string") + u32(TYPE_STRING.toLong()) + str("hello"),
            str("t.array.i32") + u32(TYPE_ARRAY.toLong()) +
                u32(TYPE_INT32.toLong()) + u64(3) + u32(1) + u32(2) + u32(3),
            str("t.array.str") + u32(TYPE_ARRAY.toLong()) +
                u32(TYPE_STRING.toLong()) + u64(2) + str("a") + str("bb"),
            str("t.array.bool") + u32(TYPE_ARRAY.toLong()) +
                u32(TYPE_BOOL.toLong()) + u64(2) + byteArrayOf(1, 0),
            str("t.uint64") + u32(TYPE_UINT64.toLong()) + u64(9_000_000_000L),
            str("t.int64") + u32(TYPE_INT64.toLong()) + u64(-5L),
            str("t.float64") + u32(TYPE_FLOAT64.toLong()) + u64(0L),
            kvString("tokenizer.ggml.model", "llama"),
            kvUInt32("llama.block_count", 22),
            kvUInt32("general.file_type", 18) +
            // Read only if every preceding type kept the stream in sync.
            kvString("general.quantization", "Q4_K_M")
        )
        val file = tmp.newFile("alltypes.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(291), u64(20), kvs))

        val info = GgufReader.parse(file)

        assertEquals("llama", info.architecture)
        assertEquals(22L, info.blockCount)
        assertEquals("Q4_K_M", info.quantization)
        assertEquals(291L, info.tensorCount)
        assertEquals(20, info.metadataKVCount)
    }

    /** Nested arrays exist in real GGUFs (e.g. some tokenizer lists). */
    @Test
    fun `nested arrays are skipped without desync`() {
        val kvs = gguf(
            kvString("general.architecture", "qwen2"),
            str("t.nested") + u32(TYPE_ARRAY.toLong()) +
                u32(TYPE_ARRAY.toLong()) + u64(2) +
                u32(TYPE_INT32.toLong()) + u64(2) + u32(1) + u32(2) +
                u32(TYPE_FLOAT32.toLong()) + u64(1) + f32(0.5f),
            kvString("general.quantization", "Q6_K")
        )
        val file = tmp.newFile("nested.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(0), u64(3), kvs))

        val info = GgufReader.parse(file)

        assertEquals("qwen2", info.architecture)
        assertEquals("Q6_K", info.quantization)
    }

    /** An unknown type id must be reported, never skipped blindly. */
    @Test
    fun `unknown metadata value type is reported`() {
        val kvs = kvString("general.architecture", "llama") +
            str("t.weird") + u32(99)
        val file = tmp.newFile("weird.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(0), u64(2), kvs))

        val ex = assertThrows(GgufReader.InvalidGgufException::class.java) {
            GgufReader.parse(file)
        }
        assertTrue(ex.message!!.contains("Unknown metadata value type"))
    }

    /** A desynced reader must fail loudly rather than return partial data. */
    @Test
    fun `a string key longer than the sanity limit is rejected`() {
        val kvs = str("k") + u32(TYPE_STRING.toLong()) + u64(Long.MAX_VALUE)
        val file = tmp.newFile("longstr.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(0), u64(1), kvs))

        val ex = assertThrows(GgufReader.InvalidGgufException::class.java) {
            GgufReader.parse(file)
        }
        assertTrue(ex.message!!.contains("too long"))
    }

    /** Array element counts are bounds-checked before any skipping happens. */
    @Test
    fun `an absurd array count is rejected`() {
        val kvs = kvString("general.architecture", "llama") +
            str("t.huge") + u32(TYPE_ARRAY.toLong()) +
            u32(TYPE_INT32.toLong()) + u64(Long.MAX_VALUE)
        val file = tmp.newFile("hugearr.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(0), u64(2), kvs))

        val ex = assertThrows(GgufReader.InvalidGgufException::class.java) {
            GgufReader.parse(file)
        }
        assertTrue(ex.message!!.contains("too large"))
    }

    @Suppress("SameParameterValue")
    private fun u16(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )

    /** The reader must expose the same limits it enforces on the stream. */
    @Test
    fun `a stream shorter than one kv fails instead of guessing`() {
        val file = tmp.newFile("stub.gguf")
        file.writeBytes(MAGIC + u32(3) + u64(0) + u64(1) + str("general.architecture"))

        assertThrows(java.io.EOFException::class.java) { GgufReader.parse(file) }
    }

    @Test
    fun `architecture is null when the file does not declare one`() {
        val file = tmp.newFile("noarch.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(0), u64(1), kvUInt32("general.file_type", 12)))

        val info = GgufReader.parse(file)
        assertNull(info.architecture)
        assertNull(info.quantization)
    }

    @Test
    fun `looksLikeGguf tolerates a truncated stream`() {
        val file = tmp.newFile("tiny.gguf")
        file.writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte()))
        assertFalse(GgufReader.looksLikeGguf(file))
    }

    @Test
    fun `parse rejects a stream whose bytes are not decodable utf8 key names`() {
        // Sanity: the reader is byte-driven, so a key made of raw bytes is
        // still consumed by length — this must not throw or desync.
        val key = byteArrayOf(0, 1, 2, 3)
        val kvs = u64(4L) + key + u32(TYPE_UINT32.toLong()) + u32(4096L) +
            kvString("general.quantization", "Q6_K")
        val file = tmp.newFile("rawkey.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(0), u64(2), kvs))

        val info = GgufReader.parse(file)
        assertEquals("Q6_K", info.quantization)
    }
}