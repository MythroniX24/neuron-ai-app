package com.neuron.ai.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadManagerTest {

    @Test
    fun `stable id is deterministic for the same repo and file`() {
        val a = ModelDownloadManager.stableIdOf("some/repo", "model.Q4_K_M.gguf")
        val b = ModelDownloadManager.stableIdOf("some/repo", "model.Q4_K_M.gguf")
        val c = ModelDownloadManager.stableIdOf("some/repo", "model.Q8_0.gguf")

        // Same input → same id: the .part file can be resumed after a restart.
        assertEquals(a, b)
        // Different file → different id (no cross-contamination).
        assertTrue(a != c)
        assertTrue(a.startsWith("dl-"))
    }

    @Test
    fun `sha256 hex matches the known test vector`() {
        // sha256 of the empty string, per NIST test vectors.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ModelDownloadManager.sha256Hex(ByteArray(0))
        )
        // sha256 of "abc".
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ModelDownloadManager.sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun `download states cover pause resume fail complete`() {
        val states = ModelDownloadManager.Download.State.entries.map { it.name }.toSet()
        assertEquals(setOf("DOWNLOADING", "PAUSED", "COMPLETED", "FAILED"), states)
    }
}
