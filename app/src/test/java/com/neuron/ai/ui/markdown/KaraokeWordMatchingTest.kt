package com.neuron.ai.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeWordMatchingTest {

    @Test
    fun testWordBoundaryMatchingDoesNotMatchSubstrings() {
        val text = "What whatever what"
        val word = "what"
        val pattern = Regex("""(?i)\b${Regex.escape(word)}\b""")
        val matches = pattern.findAll(text).toList()

        // Should match "What" and "what", but NOT "whatever"
        assertEquals(2, matches.size)
        assertEquals(0..3, matches[0].range)
        assertEquals(14..17, matches[1].range)
    }

    @Test
    fun testPunctuationSurroundingWord() {
        val text = "\"What?\" he asked, 'what!' (What)."
        val word = "what"
        val pattern = Regex("""(?i)\b${Regex.escape(word)}\b""")
        val matches = pattern.findAll(text).toList()

        assertEquals(3, matches.size)
        assertEquals("What", matches[0].value)
        assertEquals("what", matches[1].value)
        assertEquals("What", matches[2].value)
    }

    @Test
    fun testExactOccurrenceTargeting() {
        val text = "The quick brown fox jumps over the lazy dog and the sleeping cat."
        val word = "the"
        val pattern = Regex("""(?i)\b${Regex.escape(word)}\b""")
        val matches = pattern.findAll(text).toList()
        assertEquals(3, matches.size)

        // Target occurrence 0 -> matches[0]
        assertEquals(0, matches.getOrNull(0)?.range?.first)

        // Target occurrence 1 -> matches[1]
        val second = matches.getOrNull(1)
        assertNotNull(second)
        assertEquals("the", second?.value)
        assertEquals(31, second?.range?.first)

        // Target occurrence 2 -> matches[2]
        val third = matches.getOrNull(2)
        assertNotNull(third)
        assertEquals("the", third?.value)
        assertEquals(48, third?.range?.first)
    }

    @Test
    fun testMultiBlockTargetOccurrenceCalculation() {
        val block1 = "Hello world! This is Neuron."
        val block2 = "What is what?"
        val block3 = "And what else is new?"

        val word = "what"
        val pattern = Regex("""(?i)\b${Regex.escape(word)}\b""")

        val blocks = listOf(block1, block2, block3)

        fun findTargetBlockAndOccurrence(targetGlobalOccurrence: Int): Pair<Int, Int> {
            var globalMatchesSeen = 0
            for (i in blocks.indices) {
                val countInBlock = pattern.findAll(blocks[i]).count()
                if (countInBlock > 0 && targetGlobalOccurrence < globalMatchesSeen + countInBlock) {
                    return Pair(i, targetGlobalOccurrence - globalMatchesSeen)
                }
                globalMatchesSeen += countInBlock
            }
            return Pair(-1, -1)
        }

        // Global occurrence 0 -> 1st "What" in block 1 (index 1)
        val target0 = findTargetBlockAndOccurrence(0)
        assertEquals(1, target0.first)
        assertEquals(0, target0.second)

        // Global occurrence 1 -> 2nd "what" in block 1 (index 1)
        val target1 = findTargetBlockAndOccurrence(1)
        assertEquals(1, target1.first)
        assertEquals(1, target1.second)

        // Global occurrence 2 -> 3rd "what" in block 2 (index 2)
        val target2 = findTargetBlockAndOccurrence(2)
        assertEquals(2, target2.first)
        assertEquals(0, target2.second)
    }

    @Test
    fun testHindiAndUnicodeWordMatching() {
        val text = "नमस्ते भारत। नमस्ते दुनिया।"
        val word = "नमस्ते"
        val pattern = Regex("""(?i)\b${Regex.escape(word)}\b""")
        val matches = pattern.findAll(text).toList()

        assertEquals(2, matches.size)
        assertEquals(0, matches[0].range.first)
        assertTrue(matches[1].range.first > 0)
    }
}
