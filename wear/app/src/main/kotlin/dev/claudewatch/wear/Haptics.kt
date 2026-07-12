// Haptic feedback grammar for command input (issue #20): two DISTINCT verbs,
// so the wrist can tell "the bridge really has it" from "it failed, look at
// the watch" without lighting the screen. Wired through an interface so the
// JVM unit tests (mockable android.jar — no real Vibrator) can record the
// grammar instead of vibrating.
package dev.claudewatch.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

interface Haptics {
    /** The bridge acked the command (2xx): one crisp confirmation tick. */
    fun commandAcked()

    /** The send failed or was refused (no echo happened): a double buzz. */
    fun commandFailed()

    /** No-op default: JVM unit tests and Compose previews. */
    object None : Haptics {
        override fun commandAcked() {}
        override fun commandFailed() {}
    }
}

/** The real grammar, spoken through the watch's vibrator via [VibrationEffect]. */
class VibratorHaptics(context: Context) : Haptics {

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    override fun commandAcked() {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    override fun commandFailed() {
        // Two 90 ms pulses with a 90 ms gap — unmistakably not the ack tick.
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 90, 90), -1))
    }
}
