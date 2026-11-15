package com.neuron.ai.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseSpacingTest {

    @Test
    fun `default is comfortable when nothing is stored`() {
        assertEquals(ResponseSpacing.COMFORTABLE, ResponseSpacing.fromNameOrDefault(null))
        assertEquals(ResponseSpacing.COMFORTABLE, ResponseSpacing.fromNameOrDefault(""))
    }

    @Test
    fun `saved name restores the exact option`() {
        ResponseSpacing.entries.forEach { option ->
            assertEquals(option, ResponseSpacing.fromNameOrDefault(option.name))
        }
    }

    @Test
    fun `fromNameOrNull accepts case and whitespace but rejects unknown values`() {
        assertEquals(ResponseSpacing.RELAXED, ResponseSpacing.fromNameOrNull(" relaxed "))
        assertEquals(ResponseSpacing.AIRY, ResponseSpacing.fromNameOrNull("AIRY"))
        assertEquals(null, ResponseSpacing.fromNameOrNull("wide"))
        assertEquals(null, ResponseSpacing.fromNameOrNull(null))
    }

    @Test
    fun `unknown legacy value falls back instead of crashing`() {
        assertEquals(
            ResponseSpacing.COMFORTABLE,
            ResponseSpacing.fromNameOrDefault("EXTRA_WIDE")
        )
    }

    @Test
    fun `profile keeps paragraph and list choices separate`() {
        val profile = ReplySpacingProfile.fromNames(
            paragraph = "RELAXED",
            list = "COMPACT",
            legacy = "AIRY"
        )
        assertEquals(ResponseSpacing.RELAXED, profile.paragraph)
        assertEquals(ResponseSpacing.COMPACT, profile.list)

        val legacy = ReplySpacingProfile.fromNames(null, null, legacy = "AIRY")
        assertEquals(ResponseSpacing.AIRY, legacy.paragraph)
        assertEquals(ResponseSpacing.AIRY, legacy.list)
    }

    @Test
    fun `options are ordered from tightest to airiest`() {
        val scales = ResponseSpacing.entries.map { it.lineHeightScale }
        assertEquals(scales.sorted(), scales)
        assertTrue(scales.first() < 1f)
        assertTrue(scales.last() > 1f)
    }
}
