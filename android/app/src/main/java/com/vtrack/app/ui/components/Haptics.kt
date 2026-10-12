package com.vtrack.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Small wrappers so haptics stay consistent and are easy to disable in one place. */
class VTrackHaptics(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** Light tick for taps and tab changes. */
    fun tick() = vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK), 8L)

    /** Confirmation for saved/rewarded actions. */
    fun confirm() = vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK), 14L)

    /** Start / stop of a workout. */
    fun impact() = vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK), 24L)

    fun success() {
        if (supportsAmplitude()) {
            vibrate(VibrationEffect.createWaveform(longArrayOf(0, 18, 40, 26), intArrayOf(0, 160, 0, 255), -1), 60L)
        } else {
            vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20, 40, 30), -1), 90L)
        }
    }

    private fun supportsAmplitude(): Boolean = vibrator?.hasAmplitudeControl() == true

    private fun vibrate(effect: VibrationEffect, fallbackMs: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (supportsAmplitude()) {
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(fallbackMs)
            }
        }
    }
}

@Composable
fun rememberHaptics(): VTrackHaptics {
    val context = LocalContext.current
    return remember(context) { VTrackHaptics(context) }
}
