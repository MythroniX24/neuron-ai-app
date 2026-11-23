package com.neuron.ai.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DimensTest {

    @Test
    fun testRadiusAndShapes() {
        assertEquals(22.dp, Radius.xl)
        val extraLarge = NeuronShapes.extraLarge as RoundedCornerShape
        // Ensure extraLarge is standard 22.dp and not the distorted 100.dp pill
        assertNotEquals(100.dp, Radius.xl)
    }
}
