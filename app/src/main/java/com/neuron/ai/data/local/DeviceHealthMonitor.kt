package com.neuron.ai.data.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Milestone 7 — DEVICE HEALTH WATCHER.
 *
 * Watches thermal status (API 29+), thermal headroom (API 30+), battery level
 * and power-save mode, and publishes one [DeviceThermalState] that both the
 * throttle policy and the UI read. It decides NOTHING itself: all policy
 * lives in the pure [LocalThrottlePolicy], which is what keeps the behaviour
 * testable.
 *
 * Degradation is total. On old devices or missing services every field falls
 * back to "unknown / nominal", so on-device inference keeps working exactly as
 * before — just without thermal protection.
 */
class DeviceHealthMonitor(
    context: Context,
    private val logger: com.neuron.ai.core.log.Logger? = null
) {

    private val appContext: Context = context.applicationContext
    private val powerManager: PowerManager? =
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _state = MutableStateFlow(read())
    val state: StateFlow<DeviceThermalState> = _state.asStateFlow()

    @Volatile
    private var started = false

    /**
     * Held as [Any] on purpose: the listener class only exists from API 29, so
     * the type must never be touched on an older device (NoClassDefFoundError).
     */
    @Volatile
    private var thermalListener: Any? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Sticky ACTION_BATTERY_CHANGED arrives here too; ignore the
            // payload and re-read the truth (cached percentages go stale).
            refresh()
        }
    }

    /** Registers listeners; safe to call repeatedly. */
    fun start() {
        if (started) return
        started = true
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                batteryReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_POWER_SAVE_MODE_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure { logger?.w("Thermal", "Battery receiver registration failed", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // addThermalStatusListener wants a Looper thread.
            Handler(Looper.getMainLooper()).post { registerThermalListener() }
        }
        refresh()
    }

    /** Unregisters everything (process teardown / container disposal). */
    fun stop() {
        if (!started) return
        started = false
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = thermalListener
            thermalListener = null
            if (listener != null) {
                runCatching {
                    powerManager?.removeThermalStatusListener(listener as PowerManager.OnThermalStatusChangedListener)
                }
            }
        }
    }

    /** API 29+ only — never referenced from a code path that runs earlier. */
    private fun registerThermalListener() {
        val pm = powerManager ?: return
        runCatching {
            val listener = object : PowerManager.OnThermalStatusChangedListener {
                override fun onThermalStatusChanged(status: Int) {
                    refresh()
                }
            }
            thermalListener = listener
            pm.addThermalStatusListener(directExecutor(), listener)
        }.onFailure { logger?.w("Thermal", "Thermal listener unavailable", it) }
    }

    private fun refresh() {
        _state.value = read()
    }

    /** Current truth from the platform; every probe is individually guarded. */
    private fun read(): DeviceThermalState {
        val pm = powerManager
        return DeviceThermalState(
            severity = severityOf(pm),
            headroomPercent = headroomOf(pm),
            batteryPercent = batteryPercent(),
            charging = isCharging(),
            powerSaveMode = runCatching { pm?.isPowerSaveMode == true }.getOrDefault(false)
        )
    }

    private fun severityOf(pm: PowerManager?): DeviceThermalState.Severity {
        if (pm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return DeviceThermalState.Severity.UNKNOWN
        }
        return runCatching {
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> DeviceThermalState.Severity.NOMINAL
                PowerManager.THERMAL_STATUS_LIGHT -> DeviceThermalState.Severity.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> DeviceThermalState.Severity.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> DeviceThermalState.Severity.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN ->
                    DeviceThermalState.Severity.CRITICAL
                else -> DeviceThermalState.Severity.UNKNOWN
            }
        }.getOrDefault(DeviceThermalState.Severity.UNKNOWN)
    }

    /** Forecast: 0 = cool now, 1 = throttling now. Null when unsupported. */
    private fun headroomOf(pm: PowerManager?): Float? {
        if (pm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { pm.getThermalHeadroom(0) }
            .getOrNull()
            ?.takeIf { !it.isNaN() && it.isFinite() }
    }

    private fun batteryIntent(): Intent? = runCatching {
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    private fun batteryPercent(): Int {
        val intent = batteryIntent() ?: return 100
        val level = intent.getIntExtra(EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return 100
        return ((level * 100f) / scale).toInt().coerceIn(0, 100)
    }

    private fun isCharging(): Boolean {
        val intent = batteryIntent() ?: return true
        return intent.getIntExtra(EXTRA_PLUGGED, 0) != 0
    }

    private fun directExecutor(): Executor = Executor { it.run() }

    private companion object {
        /** Battery intent extras. */
        const val EXTRA_LEVEL = "level"
        const val EXTRA_SCALE = "scale"
        const val EXTRA_PLUGGED = "plugged"
    }
}