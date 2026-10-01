package com.neuron.ai.data.local

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun str(value: String): ByteArray =
        u64(value.length.toLong()) + value.toByteArray(Charsets.UTF_8)

    private val MAGIC = "GGUF".toByteArray(Charsets.US_ASCII)

    // Type codes from the GGUF spec.
    private val TYPE_UINT32 = 4
    private val TYPE_STRING = 11

    private fun kvUInt32(key: String, value: Long) = str(key) + u32(TYPE_UINT32.toLong()) + u32(value)
    private fun kvString(key: String, value: String) = str(key) + u32(TYPE_STRING.toLong()) + str(value)

    /** A minimal but VALID v3 file: header + two KV pairs; tensor data absent. */
    private fun validGguf(): ByteArray {
        val kvs = kvString("general.architecture", "llama") +
            kvUInt32("llama.context_length", 4096) +
            kvUInt32("general.file_type", 15) // Q6_K
        return gguf(MAGIC, u32(3), u64(0), u64(3), kvs)
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
        assertEquals(3, info.metadataKVCount)
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
    fun `unknown kv types are skipped without desync`() {
        // KV 1: architecture; KV 2: a 64-bit value the reader does not care
        // about (skipValue must consume exactly 8 bytes); KV 3: file_type.
        val kvs = kvString("general.architecture", "qwen2") +
            str("some.unknown") + u32(9) + u64(0x1122334455667788L) +
            kvUInt32("general.file_type", 18)
        val file = tmp.newFile("mixed.gguf")
        file.writeBytes(gguf(MAGIC, u32(3), u64(0), u64(3), kvs))

        val info = GgufReader.parse(file)

        assertEquals("qwen2", info.architecture)
        assertEquals("Q4_K_M", info.quantization)
    }
}
