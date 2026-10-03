package com.neuron.ai.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuTopologyTest {

    // 2.8 GHz prime + prime, 2.4 GHz big, 1.8 GHz little x4 — a typical
    // Snapdragon/Dimensity layout (kHz).
    private val sd845Like = listOf(1785000, 1785000, 2457000, 2457000, 1764000, 1764000, 1764000, 1764000)

    @Test
    fun `only the prime and big cores count as big`() {
        assertEquals(4, CpuTopology.bigCoreCount(sd845Like))
    }

    @Test
    fun `little cores just under the ratio are excluded`() {
        // 1800 vs 2000 = 0.9 → included; 1600 vs 2000 = 0.8 → excluded.
        assertEquals(3, CpuTopology.bigCoreCount(listOf(2000, 2000, 1800, 1600)))
    }

    @Test
    fun `the capacity path classifies the same way`() {
        // cpu_capacity reports a class number instead of kHz; the same ratio
        // rule splits prime/big from little cores.
        assertEquals(2, CpuTopology.bigCoreCount(listOf(1024, 1024, 512, 512)))
        assertEquals(4, CpuTopology.bigCoreCount(listOf(1024, 1024, 1024, 1024)))
        assertEquals(0, CpuTopology.bigCoreCount(emptyList()))
        assertEquals(0, CpuTopology.bigCoreCount(listOf(0, 0)))
    }

    @Test
    fun `auto threads use the big cores and stay inside sane bounds`() {
        // 4 big cores on an 8-core SoC → 4 threads, not 8.
        assertEquals(
            4,
            CpuTopology.resolveThreads(bigCores = 4, totalCores = 8)
        )
        // A 2-core device → the floor.
        assertEquals(2, CpuTopology.resolveThreads(bigCores = 1, totalCores = 2))
        // Homogeneous device (no topology info) → all cores, capped.
        assertEquals(
            CpuTopology.MAX_THREADS,
            CpuTopology.resolveThreads(bigCores = 0, totalCores = 16)
        )
    }

    @Test
    fun `an explicit user choice wins but is still clamped`() {
        assertEquals(6, CpuTopology.resolveThreads(bigCores = 4, totalCores = 8, requested = 6))
        // Never more threads than the device has cores.
        assertEquals(8, CpuTopology.resolveThreads(bigCores = 8, totalCores = 8, requested = 12))
        // Never fewer than the floor.
        assertEquals(2, CpuTopology.resolveThreads(bigCores = 4, totalCores = 8, requested = 1))
    }

    @Test
    fun `the thermal cap overrides both auto and explicit choices`() {
        // Hot device: the milestone-7 policy cap wins over "use all big cores".
        assertEquals(
            2,
            CpuTopology.resolveThreads(bigCores = 4, totalCores = 8, thermalCap = 2)
        )
        assertEquals(
            4,
            CpuTopology.resolveThreads(bigCores = 4, totalCores = 8, thermalCap = 4)
        )
    }
}
