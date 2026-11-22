package com.neuron.ai.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Tactile, subtle haptic feedback helper for premium touch response.
 */
class HapticFeedbackHelper(private val context: Context) {
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** Quick, subtle tactile click (~15ms) for button presses. */
    fun click() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(15, 60))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(15)
            }
        }
    }

    /** Crisp double-tap / tick for positive confirmations (e.g. copy to clipboard). */
    fun confirmation() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(22, 110))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(22)
            }
        }
    }
}

@Composable
fun rememberHapticFeedback(): HapticFeedbackHelper {
    val context = LocalContext.current
    return remember(context) { HapticFeedbackHelper(context) }
}
