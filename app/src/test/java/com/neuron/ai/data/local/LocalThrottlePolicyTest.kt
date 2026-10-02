package com.neuron.ai.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 7 — the throttle policy is the difference between "the phone got
 * hot and the answer died" and "the app quietly got slower". Every branch is
 * pinned here so a hot device is never silently run at full tilt.
 */
class LocalThrottlePolicyTest {

    private fun state(
        severity: DeviceThermalState.Severity = DeviceThermalState.Severity.NOMINAL,
        headroom: Float? = null,
        batteryPercent: Int = 80,
        charging: Boolean = true,
        powerSaveMode: Boolean = false
    ) = DeviceThermalState(
        severity = severity,
        headroomPercent = headroom,
        batteryPercent = batteryPercent,
        charging = charging,
        powerSaveMode = powerSaveMode
    )

    @Test
    fun `a cool charging device runs unthrottled`() {
        val decision = LocalThrottlePolicy.decide(state(), wantsGpu = true, requestedThreads = 6)
        assertFalse(decision.throttled)
        assertTrue(decision.allowGpu)
        assertEquals(6, decision.threads)
        assertEquals(LocalThrottlePolicy.NORMAL_MAX_OUTPUT_TOKENS, decision.maxOutputTokens)
        assertNull(decision.reason)
    }

    @Test
    fun `a hot device keeps the gpu but trims the cpu`() {
        val decision = LocalThrottlePolicy.decide(
            state(severity = DeviceThermalState.Severity.MODERATE),
            wantsGpu = true,
            requestedThreads = 8
        )
        assertTrue(decision.throttled)
        assertTrue(decision.allowGpu)
        assertEquals(LocalThrottlePolicy.WARM_THREAD_CAP, decision.threads)
        assertEquals(LocalThrottlePolicy.WARM_MAX_OUTPUT_TOKENS, decision.maxOutputTokens)
        assertNotNull(decision.reason)
    }

    @Test
    fun `a very hot device drops the gpu entirely`() {
        val decision = LocalThrottlePolicy.decide(
            state(severity = DeviceThermalState.Severity.SEVERE),
            wantsGpu = true,
            requestedThreads = 8
        )
        assertFalse(decision.allowGpu)
        assertEquals(LocalThrottlePolicy.HOT_THREAD_CAP, decision.threads)
        assertEquals(LocalThrottlePolicy.HOT_MAX_OUTPUT_TOKENS, decision.maxOutputTokens)
    }

    @Test
    fun `low headroom escalates before the thermal status does`() {
        val decision = LocalThrottlePolicy.decide(
            state(severity = DeviceThermalState.Severity.LIGHT, headroom = 0.9f),
            wantsGpu = true,
            requestedThreads = 6
        )
        assertTrue(decision.throttled)
        assertTrue(decision.allowGpu)
        assertEquals(LocalThrottlePolicy.WARM_THREAD_CAP, decision.threads)
        assertTrue(decision.reason!!.contains("headroom"))
    }

    @Test
    fun `an unplugged low battery is protected even when the device is cool`() {
        val decision = LocalThrottlePolicy.decide(
            state(batteryPercent = 10, charging = false),
            wantsGpu = true,
            requestedThreads = 6
        )
        assertFalse(decision.allowGpu)
        assertTrue(decision.reason!!.contains("Battery"))
    }

    @Test
    fun `a low battery that is charging is not throttled`() {
        val decision = LocalThrottlePolicy.decide(
            state(batteryPercent = 10, charging = true),
            wantsGpu = true,
            requestedThreads = 6
        )
        assertFalse(decision.throttled)
    }

    @Test
    fun `battery saver always protects the gpu`() {
        val decision = LocalThrottlePolicy.decide(
            state(powerSaveMode = true),
            wantsGpu = true,
            requestedThreads = 6
        )
        assertFalse(decision.allowGpu)
        assertTrue(decision.reason!!.contains("Battery saver"))
    }

    @Test
    fun `a user who asked for fewer threads never gets more back`() {
        val decision = LocalThrottlePolicy.decide(
            state(severity = DeviceThermalState.Severity.MODERATE),
            wantsGpu = false,
            requestedThreads = 1
        )
        assertEquals(1, decision.threads)
        assertFalse(decision.allowGpu)
    }

    @Test
    fun `unknown thermal status degrades to no throttling`() {
        val decision = LocalThrottlePolicy.decide(
            state(severity = DeviceThermalState.Severity.UNKNOWN),
            wantsGpu = false,
            requestedThreads = 4
        )
        assertFalse(decision.throttled)
        assertEquals(4, decision.threads)
    }

    @Test
    fun `status line reports thermal battery and power saver`() {
        val line = LocalThrottlePolicy.statusLine(
            state(
                severity = DeviceThermalState.Severity.MODERATE,
                batteryPercent = 42,
                charging = false,
                powerSaveMode = true
            )
        )
        assertTrue(line.contains("Hot"))
        assertTrue(line.contains("42%"))
        assertTrue(line.contains("power saver on"))
    }
}