package com.neuron.ai.data.local

/**
 * Milestone 7 — THERMAL / BATTERY THROTTLE POLICY (pure).
 *
 * On-device generation is a heavy, sustained load. A phone that hard-throttles
 * mid-answer feels broken; one that quietly dropped to fewer threads up front
 * feels fast. So the policy is explicit, deterministic and unit-testable:
 * from a device-health snapshot it produces the EXACT load we will run, plus
 * the one-line reason the user sees.
 *
 * No Android types here on purpose — the watcher feeds it, the repository
 * obeys it, and tests drive it directly.
 */

/** Snapshot of device health. Every field degrades gracefully when unknown. */
data class DeviceThermalState(
    /** Coarse thermal severity from the platform (null when unsupported). */
    val severity: Severity = Severity.UNKNOWN,
    /** Forward-looking thermal headroom 0..1 (API 30+); null when unsupported. */
    val headroomPercent: Float? = null,
    /** Battery level 0..100. */
    val batteryPercent: Int = 100,
    /** True while the device is plugged in / charging. */
    val charging: Boolean = true,
    /** Power-save (battery saver) mode engaged. */
    val powerSaveMode: Boolean = false
) {
    enum class Severity { UNKNOWN, NOMINAL, LIGHT, MODERATE, SEVERE, CRITICAL }

    /** Short user-facing label for the Settings row. */
    fun severityLabel(): String = when (severity) {
        Severity.UNKNOWN -> "Unknown"
        Severity.NOMINAL -> "Cool"
        Severity.LIGHT -> "Warm"
        Severity.MODERATE -> "Hot"
        Severity.SEVERE -> "Very hot"
        Severity.CRITICAL -> "Critical"
    }
}

/** The load we will actually run, plus why. */
data class ThrottleDecision(
    /** GPU offload may be requested for the next load. */
    val allowGpu: Boolean,
    /** Thread count for the next load (>= 1). */
    val threads: Int,
    /** Upper bound on generated tokens for the next turn. */
    val maxOutputTokens: Int,
    /** True when this decision is a DEGRADATION of what the user asked for. */
    val throttled: Boolean,
    /** One-line reason shown in the UI; null when not throttled. */
    val reason: String?
)

object LocalThrottlePolicy {

    /** Thread cap when merely WARM (keep the GPU, trim the CPU). */
    const val WARM_THREAD_CAP = 4

    /** Thread cap when HOT or on a low battery (GPU off, CPU trimmed hard). */
    const val HOT_THREAD_CAP = 2

    /** Battery percentage below which an unplugged device is treated as hot. */
    const val LOW_BATTERY_PERCENT = 15

    /** Headroom above this means "throttling imminent" (API 30+). */
    const val HEADROOM_ESCALATE = 0.85f

    const val WARM_MAX_OUTPUT_TOKENS = 1024
    const val HOT_MAX_OUTPUT_TOKENS = 384
    const val NORMAL_MAX_OUTPUT_TOKENS = 2048

    /**
     * Decides the load for the next generation.
     *
     * @param state live device-health snapshot
     * @param wantsGpu the user's GPU preference (the policy may veto it)
     * @param requestedThreads the user's thread choice (0 = auto)
     */
    fun decide(
        state: DeviceThermalState,
        wantsGpu: Boolean,
        requestedThreads: Int
    ): ThrottleDecision {
        val baseThreads = requestedThreads.coerceIn(1, 16)
        val severity = state.severity
        val unplugged = !state.charging
        val lowBattery = unplugged && state.batteryPercent <= LOW_BATTERY_PERCENT
        val soonHot = (state.headroomPercent ?: 0f) >= HEADROOM_ESCALATE

        return when {
            // ---- Hard protect: GPU is the first thing to go, CPU gets trimmed.
            severity >= DeviceThermalState.Severity.CRITICAL -> hot(
                baseThreads,
                "Device is critically hot — GPU off, generation trimmed"
            )
            severity >= DeviceThermalState.Severity.SEVERE -> hot(
                baseThreads,
                "Device is very hot — GPU off, generation trimmed"
            )
            state.powerSaveMode -> hot(
                baseThreads,
                "Battery saver is on — GPU off, generation trimmed"
            )
            lowBattery -> hot(
                baseThreads,
                "Battery at ${state.batteryPercent}% and not charging — GPU off"
            )

            // ---- Soft throttle: keep the GPU, trim the CPU fan-out.
            severity >= DeviceThermalState.Severity.MODERATE -> warm(
                baseThreads,
                "Device is hot — CPU threads capped at $WARM_THREAD_CAP"
            )
            soonHot -> warm(
                baseThreads,
                "Thermal headroom is low — CPU threads capped at $WARM_THREAD_CAP"
            )
            else -> ThrottleDecision(
                allowGpu = wantsGpu,
                threads = baseThreads,
                maxOutputTokens = NORMAL_MAX_OUTPUT_TOKENS,
                throttled = false,
                reason = null
            )
        }
    }

    private fun warm(baseThreads: Int, reason: String) = ThrottleDecision(
        allowGpu = true,
        threads = baseThreads.coerceAtMost(WARM_THREAD_CAP),
        maxOutputTokens = WARM_MAX_OUTPUT_TOKENS,
        throttled = true,
        reason = reason
    )

    private fun hot(baseThreads: Int, reason: String) = ThrottleDecision(
        allowGpu = false,
        threads = baseThreads.coerceAtMost(HOT_THREAD_CAP),
        maxOutputTokens = HOT_MAX_OUTPUT_TOKENS,
        throttled = true,
        reason = reason
    )

    /** One-line summary for the Settings screen (never empty). */
    fun statusLine(state: DeviceThermalState): String {
        val battery = "${state.batteryPercent}%" +
            (if (state.charging) " (charging)" else "")
        return "${state.severityLabel()} · battery $battery" +
            (if (state.powerSaveMode) " · power saver on" else "")
    }
}