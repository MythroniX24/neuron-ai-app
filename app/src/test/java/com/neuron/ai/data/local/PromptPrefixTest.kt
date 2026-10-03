package com.neuron.ai.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPrefixTest {

    @Test
    fun `identical prompts reuse everything but the last token worth decoding`() {
        val tokens = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(tokens.size, PromptPrefix.commonPrefixLength(tokens, tokens))
    }

    @Test
    fun `a longer conversation reuses the shared history`() {
        // Turn 2 = turn 1's prompt + the assistant's answer + the new message.
        val cached = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 90, 91, 92)
        val fresh = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 90, 91, 92, 42, 43)
        assertEquals(cached.size, PromptPrefix.commonPrefixLength(cached, fresh))
    }

    @Test
    fun `divergence in the middle reuses only up to the divergence`() {
        val cached = intArrayOf(1, 2, 3, 4, 5)
        val fresh = intArrayOf(1, 2, 3, 99, 5, 6)
        assertEquals(3, PromptPrefix.commonPrefixLength(cached, fresh))
    }

    @Test
    fun `an edited earlier message reuses nothing`() {
        val cached = intArrayOf(1, 2, 3, 4, 5)
        val fresh = intArrayOf(7, 8, 9, 10, 11)
        assertEquals(0, PromptPrefix.commonPrefixLength(cached, fresh))
    }

    @Test
    fun `tiny overlaps are not worth reusing`() {
        assertEquals(0, PromptPrefix.commonPrefixLength(intArrayOf(), intArrayOf(1, 2, 3)))
        assertEquals(0, PromptPrefix.commonPrefixLength(intArrayOf(1, 2, 3), intArrayOf()))
        assertEquals(0, PromptPrefix.commonPrefixLength(intArrayOf(5), intArrayOf(5, 6)))
        assertEquals(PromptPrefix.MIN_REUSABLE_TOKENS,
            PromptPrefix.commonPrefixLength(intArrayOf(5, 6), intArrayOf(5, 6, 7)))
    }

    @Test
    fun `the shorter of the two lists bounds the prefix`() {
        assertEquals(4, PromptPrefix.commonPrefixLength(intArrayOf(1, 2, 3, 4), intArrayOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `context clamping keeps the newest tokens`() {
        val prompt = IntArray(10) { it }
        // Fits: returned untouched (same instance, no copy).
        assertTrue(prompt === PromptPrefix.clampToContext(prompt, 10))
        val clamped = PromptPrefix.clampToContext(prompt, 4)
        assertEquals(4, clamped.size)
        assertEquals(6, clamped.first())
        assertEquals(9, clamped.last())
        // Unknown context window → no clamping rather than a crash.
        assertEquals(10, PromptPrefix.clampToContext(prompt, 0).size)
    }

    @Test
    fun `reuse percentage is reported for the performance card`() {
        assertEquals(0, PromptPrefix.reusePercent(0, 100))
        assertEquals(0, PromptPrefix.reusePercent(50, 0))
        assertEquals(50, PromptPrefix.reusePercent(50, 100))
        assertEquals(100, PromptPrefix.reusePercent(120, 100))
    }
}
