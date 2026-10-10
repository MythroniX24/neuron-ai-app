package com.neuron.ai.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load refund check has to be predictable: too strict and working models
 * are refused, too loose and the process is still killed mid-load. These
 * pin the arithmetic and the alignment with the curated catalog's own RAM
 * guidance ([RecommendedModels.minFreeRamGb]).
 */
class LoadMemoryBudgetTest {

    private val mb = 1024L * 1024L
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `tiny models are floored by the scratch allowance`() {
        // 4k context on a 32-layer model is 256 MB of KV, i.e. below the
        // scratch floor — the floor is what governs the check.
        assertEquals(
            LoadMemoryBudget.SCRATCH_FLOOR_BYTES,
            LoadMemoryBudget.kvAndScratchBytes(contextTokens = 4096, layerCount = 32L)
        )
    }

    @Test
    fun `a large context on a deep model needs more than the floor`() {
        // 8192 tokens x 64 layers x 2 KB = 1 GB.
        assertEquals(1L * gb, LoadMemoryBudget.kvAndScratchBytes(8192, 64L))
    }

    @Test
    fun `missing or absurd metadata never produces a negative budget`() {
        assertEquals(
            LoadMemoryBudget.SCRATCH_FLOOR_BYTES,
            LoadMemoryBudget.kvAndScratchBytes(contextTokens = 0, layerCount = null)
        )
        assertEquals(
            LoadMemoryBudget.SCRATCH_FLOOR_BYTES,
            LoadMemoryBudget.kvAndScratchBytes(contextTokens = -1, layerCount = -5L)
        )
    }

    @Test
    fun `the file size is part of the requirement`() {
        val required = LoadMemoryBudget.requiredFreeBytes(
            modelFileBytes = 2L * gb,
            contextTokens = 4096,
            layerCount = 32L
        )
        assertEquals(2L * gb + LoadMemoryBudget.SCRATCH_FLOOR_BYTES, required)
    }

    @Test
    fun `fits is inclusive at the boundary`() {
        assertTrue(LoadMemoryBudget.fits(requiredBytes = 1L * gb, availBytes = 1L * gb))
        assertFalse(LoadMemoryBudget.fits(requiredBytes = 1L * gb, availBytes = 1L * gb - 1))
    }

    /**
     * The curated catalog calls a model a fit when the device has
     * `minFreeRamGb` free. The budget must agree: it may not turn that
     * model into a refusal, or the UI would contradict itself.
     */
    @Test
    fun `the budget agrees with the catalog's own ram guidance`() {
        RecommendedModels.all.forEach { model ->
            val fileBytes = (model.approxSizeGb * gb).toLong()
            val required = LoadMemoryBudget.requiredFreeBytes(
                modelFileBytes = fileBytes,
                contextTokens = LocalModelRouter.DEFAULT_CONTEXT_TOKENS,
                layerCount = 32L
            )
            val catalogGuidance = (model.minFreeRamGb * gb).toLong()
            assertTrue(
                "${model.displayName}: budget ${required / mb} MB must fit inside the " +
                    "catalog's ${catalogGuidance / mb} MB of recommended free RAM",
                LoadMemoryBudget.fits(required, catalogGuidance)
            )
        }
    }

    @Test
    fun `the shortfall message names both numbers in GB`() {
        val message = LoadMemoryBudget.describeShortfall(
            requiredBytes = 3L * gb,
            availBytes = 1L * gb
        )
        assertTrue(message, message.contains("3.00 GB"))
        assertTrue(message, message.contains("1.00 GB"))
    }
}
