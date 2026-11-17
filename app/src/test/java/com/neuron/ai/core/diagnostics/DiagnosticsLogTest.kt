package com.neuron.ai.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {

    @Test
    fun `entries keep insertion order with newest last`() {
        val log = DiagnosticsLog()
        log.log("google", "first")
        log.log("image", "second")

        val entries = log.snapshot()
        assertEquals(2, entries.size)
        assertEquals("FIRST", entries[0].message.uppercase())
        assertEquals("SECOND", entries[1].message.uppercase())
        assertEquals("GOOGLE", entries[0].tag)
    }

    @Test
    fun `capacity drops the oldest entries`() {
        val log = DiagnosticsLog(capacity = 3)
        repeat(6) { index -> log.log("TEST", "entry $index") }

        val entries = log.snapshot()
        assertEquals(3, entries.size)
        assertEquals("entry 3", entries.first().message)
        assertEquals("entry 5", entries.last().message)
    }

    @Test
    fun `entries can be filtered by tag`() {
        val log = DiagnosticsLog()
        log.log("GOOGLE", "consent failed")
        log.log("IMAGE", "no image")
        log.log("google", "health probe")

        assertEquals(2, log.entriesFor("google").size)
        assertEquals(1, log.entriesFor("image").size)
    }

    @Test
    fun `clear empties the feed`() {
        val log = DiagnosticsLog()
        log.log("GOOGLE", "x")
        log.clear()
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun `line includes the tag and an optional detail`() {
        val entry = DiagnosticEntry(atMs = 0L, tag = "GOOGLE", message = "failed", detail = "code 10")
        assertTrue(entry.asLine().contains("[GOOGLE]"))
        assertTrue(entry.asLine().contains("code 10"))
    }
}
