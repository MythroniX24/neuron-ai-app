package com.neuron.ai.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedModelsTest {

    private val gbLong = 1024L * 1024 * 1024

    @Test
    fun `catalog is ordered by speed tier and covers the required starting set`() {
        val ids = RecommendedModels.all.map { it.id }
        // Required starting set from the Local AI spec.
        assertTrue("gemma-2-2b" in ids)
        assertTrue("gemma-2-9b" in ids)
        assertTrue("qwen25-1_5b" in ids)
        assertTrue("qwen25-3b" in ids)
        assertTrue("phi-3-mini" in ids)
        assertTrue("llama-3.2-1b" in ids)
        assertTrue("llama-3.2-3b" in ids)
        assertTrue("tinyllama" in ids)
        // Ordered roughly by speed-vs-quality for phone hardware.
        assertEquals(
            RecommendedModels.all.map { it.speedTier },
            RecommendedModels.all.map { it.speedTier }.sorted()
        )
        // The heavy pick is explicitly flagged.
        assertTrue(RecommendedModels.all.first { it.id == "gemma-2-9b" }.needsMoreRam)
        // Every entry carries explicit minimum-RAM guidance.
        assertTrue(RecommendedModels.all.all { it.minRamGb > 0 && it.minFreeRamGb > 0 })
    }

    @Test
    fun `fit classification warns and greys out by available ram`() {
        val tiny = RecommendedModels.all.first { it.id == "tinyllama" }
        val heavy = RecommendedModels.all.first { it.id == "gemma-2-9b" }

        // Plenty of RAM: fine.
        assertEquals(
            RecommendedModels.Fit.FINE,
            RecommendedModels.fitForDevice(tiny, totalRamBytes = 8 * gbLong, freeRamBytes = 4 * gbLong)
        )
        // Low free memory but enough total: tight (warn, don't grey out).
        assertEquals(
            RecommendedModels.Fit.TIGHT,
            RecommendedModels.fitForDevice(tiny, totalRamBytes = 8 * gbLong, freeRamBytes = (1.4 * gbLong).toLong())
        )
        // Below the minimum RAM: unlikely (grey out).
        assertEquals(
            RecommendedModels.Fit.UNLIKELY,
            RecommendedModels.fitForDevice(heavy, totalRamBytes = 4 * gbLong, freeRamBytes = 2 * gbLong)
        )
        // Free memory far below the required headroom: unlikely even if total fits.
        assertEquals(
            RecommendedModels.Fit.UNLIKELY,
            RecommendedModels.fitForDevice(heavy, totalRamBytes = 8 * gbLong, freeRamBytes = gbLong)
        )
    }
}
