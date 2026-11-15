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
    fun `unknown legacy value falls back instead of crashing`() {
        assertEquals(
            ResponseSpacing.COMFORTABLE,
            ResponseSpacing.fromNameOrDefault("EXTRA_WIDE")
        )
    }

    @Test
    fun `options are ordered from tightest to airiest`() {
        val scales = ResponseSpacing.entries.map { it.lineHeightScale }
        assertEquals(scales.sorted(), scales)
        assertTrue(scales.first() < 1f)
        assertTrue(scales.last() > 1f)
    }
}
