package com.neuron.ai.data.local

import java.io.File

/**
 * Milestone 8 — CPU topology, the pure half.
 *
 * A phone SoC is rarely uniform: Snapdragon/Dimensity parts pair 2-4 big
 * cores with a cluster of much slower little ones. Threading inference across
 * little cores is the single most common self-inflicted slowdown — they are
 * typically 2-4x slower per clock, and they also make the big cores stall on
 * shared cache/memory bandwidth. llama.cpp's default of "one thread per core"
 * therefore leaves a lot on the table.
 *
 * Everything here is pure: the caller supplies the per-core numbers, so the
 * classification and the thread math are unit tested without a device. The
 * sysfs reader at the bottom is the only part that touches the filesystem.
 */
object CpuTopology {

    /**
     * A core counts as "big" when its peak frequency is at least
     * [BIG_CORE_RATIO] of the fastest core on the SoC. 0.85 keeps prime cores
     * in the pool without dragging in the efficiency cluster.
     */
    const val BIG_CORE_RATIO = 0.85f

    /** Never go below this — a 1-thread inference pass is unusably slow. */
    const val MIN_THREADS = 2

    /** Never ask for more than this (also keeps room for the system). */
    const val MAX_THREADS = 8

    /**
     * Counts the big cores from per-core peak frequencies (kHz, any unit as
     * long as it is consistent). Cores reporting nothing are ignored.
     */
    fun bigCoreCount(peakFrequencies: List<Int>): Int {
        val known = peakFrequencies.filter { it > 0 }
        if (known.isEmpty()) return 0
        val fastest = known.max()
        return known.count { it >= fastest * BIG_CORE_RATIO }
    }

    /**
     * Threads to actually run inference on.
     *
     * - [requested] > 0 → the user's explicit choice, only clamped.
     * - otherwise → the big-core count, clamped, and never more than the
     *   device's total cores.
     *
     * [thermalCap] (from the milestone-7 throttle policy) wins over both: a
     * hot device must not get more threads than the policy allows.
     */
    fun resolveThreads(
        bigCores: Int,
        totalCores: Int,
        requested: Int = 0,
        thermalCap: Int = MAX_THREADS
    ): Int {
        val total = totalCores.coerceAtLeast(1)
        val base = if (requested > 0) {
            requested
        } else {
            // No topology info (permissions, exotic device): all cores.
            if (bigCores > 0) bigCores else total
        }
        return base.coerceIn(MIN_THREADS, minOf(MAX_THREADS, total, thermalCap.coerceAtLeast(1)))
    }

    /**
     * Reads each core's peak frequency from sysfs, falling back to
     * `cpu_capacity` (Android 12+ kernel) and finally to "all cores look the
     * same" (bigCoreCount then returns every core it could read).
     *
     * @param cpuCount how many cores to probe
     */
    fun readPeakFrequencies(cpuCount: Int): List<Int> {
        val freqs = mutableListOf<Int>()
        for (cpu in 0 until cpuCount) {
            val khz = readInt(File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq"))
                ?: readInt(File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq"))
            if (khz != null && khz > 0) {
                freqs.add(khz)
            } else {
                // No cpufreq node (some kernels restrict it) — treat as same-class.
                freqs.add(readCapacity(cpu) ?: 0)
            }
        }
        return freqs
    }

    private fun readCapacity(cpu: Int): Int? =
        readInt(File("/sys/devices/system/cpu/cpu$cpu/cpu_capacity"))

    private fun readInt(file: File): Int? = try {
        if (file.canRead()) file.readText().trim().toInt() else null
    } catch (_: Throwable) {
        null
    }
}
