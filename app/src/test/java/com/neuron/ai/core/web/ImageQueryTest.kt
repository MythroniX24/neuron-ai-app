package com.neuron.ai.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageQueryTest {

    @Test
    fun `blank phrases produce no variants`() {
        assertTrue(ImageQuery.variants("").isEmpty())
        assertTrue(ImageQuery.variants("   ").isEmpty())
        assertTrue(ImageQuery.variants("a").isEmpty())
    }

    @Test
    fun `full phrase stays first so the most specific lookup wins`() {
        val variants = ImageQuery.variants("Spirited Away")
        assertEquals("Spirited Away", variants.first())
    }

    @Test
    fun `parenthetical years and years are dropped from fallbacks`() {
        val variants = ImageQuery.variants("Spirited Away (2001)")
        assertEquals("Spirited Away (2001)", variants.first())
        assertTrue(variants.contains("Spirited Away"))
        assertFalse(variants.drop(1).any { it.contains("2001") })
    }

    @Test
    fun `descriptive tails after a dash or colon are trimmed`() {
        val variants = ImageQuery.variants("Motorola Moto G 2024 — budget pick")
        assertTrue(variants.any { it.equals("Motorola Moto G 2024", ignoreCase = true) })
    }

    @Test
    fun `generic category words are removed to reach the entity`() {
        val variants = ImageQuery.variants("Nothing Phone minimalist smartphone")
        assertTrue(variants.any { it.equals("Nothing Phone", ignoreCase = true) } ||
            variants.any { it.contains("Nothing") && !it.contains("smartphone") })
    }

    @Test
    fun `variants are unique case-insensitively and bounded`() {
        val variants = ImageQuery.variants("Pixel 9", limit = 3)
        assertTrue(variants.size <= 3)
        assertEquals(variants.size, variants.map { it.lowercase() }.distinct().size)
    }

    @Test
    fun `cache key ignores case and collapsing whitespace`() {
        assertEquals(ImageQuery.key("Spirited   Away"), ImageQuery.key(" spirited away "))
    }
}
