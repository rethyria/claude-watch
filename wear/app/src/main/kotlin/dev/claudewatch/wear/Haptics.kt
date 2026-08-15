// Haptic feedback grammar. Two COMMAND verbs (issue #20) — so the wrist can
// tell "the bridge really has it" from "it failed, look at the watch" without
// lighting the screen — plus three ATTENTION verbs (issue #129) for incoming
// news: a prompt blocking on the user, a turn completing, something breaking.
// Every verb is a DISTINCT waveform (see the factory table below), because a
// buzz the user cannot classify without looking defeats the whole at-a-glance
// point. Wired through an interface so the JVM unit tests (mockable
// android.jar — no real Vibrator) can record the grammar instead of vibrating.
package dev.claudewatch.wear

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

interface Haptics {
    /** The bridge acked the command (2xx): one crisp confirmation tick. */
    fun commandAcked()

    /** The send failed or was refused (no echo happened): a double buzz. */
    fun commandFailed()

    /**
     * A prompt is blocking on the user (permission or question card arrived).
     * The flagship attention verb (issue #129): strong and unmistakable, and
     * ALWAYS ON — the agent is stopped until the wrist answers.
     */
    fun needsYou()

    /** A session's turn completed (working → idle): one subtle tick — glanceable progress, not an alarm. */
    fun workFinished()

    /** A session errored, or the live stream is genuinely gone: distinct from every other verb. */
    fun wentWrong()

    /** No-op default: JVM unit tests and Compose previews. */
    object None : Haptics {
        override fun commandAcked() {}
        override fun commandFailed() {}
        override fun needsYou() {}
        override fun workFinished() {}
        override fun wentWrong() {}
    }
}

// ─── The waveform table, one factory per verb ────────────────────────────────
// Pure builders (no vibrator), exposed internal so the instrumented smoke can
// assert the five effects are pairwise DISTINCT — the "can I classify this
// buzz blind?" contract — and that the #20 command pair is byte-identical to
// what shipped. Each verb differs from every other in pulse COUNT or duration
// CLASS, never just amplitude: predefined click (ack) vs predefined tick
// (finished) vs two shorts (failed) vs three longs (needs you) vs one lone
// drone (went wrong).

/** #20, unchanged: one crisp predefined click. */
internal fun commandAckedEffect(): VibrationEffect =
    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)

/** #20, unchanged: two 90 ms pulses with a 90 ms gap — unmistakably not the ack tick. */
internal fun commandFailedEffect(): VibrationEffect =
    VibrationEffect.createWaveform(longArrayOf(0, 90, 90, 90), -1)

/**
 * needsYou: THREE 250 ms pulses — the longest, heaviest pattern in the
 * grammar. Three long beats can't be confused with commandFailed's two short
 * ones even mid-motion; nothing else in the grammar has three pulses.
 */
internal fun needsYouEffect(): VibrationEffect =
    VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250, 120, 250), -1)

/**
 * workFinished: the predefined TICK — the lightest touch the platform offers,
 * deliberately fainter than the ack CLICK so passive progress news never
 * outranks the user's own command feedback.
 */
internal fun workFinishedEffect(): VibrationEffect =
    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

/** wentWrong: one lone 450 ms drone — the only single LONG pulse in the grammar. */
internal fun wentWrongEffect(): VibrationEffect =
    VibrationEffect.createWaveform(longArrayOf(0, 450), -1)

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
        vibrator.vibrate(commandAckedEffect())
    }

    override fun commandFailed() {
        vibrator.vibrate(commandFailedEffect())
    }

    override fun needsYou() = attention(needsYouEffect())

    override fun workFinished() = attention(workFinishedEffect())

    override fun wentWrong() = attention(wentWrongEffect())

    /**
     * Attention verbs vibrate with NOTIFICATION-usage attribution (issue
     * #129) so DND / bedtime / theater gate them exactly like notification
     * alerts — an unsolicited buzz must obey the user's quiet hours. The
     * typed VibrationAttributes overload exists from T; on 30–32 the same
     * usage rides the AudioAttributes carrier (deprecated in T, which is
     * precisely why the gate reads TIRAMISU). The COMMAND verbs stay
     * unattributed on purpose: they are touch feedback for an action the
     * user is performing right now, not an interruption to police.
     */
    private fun attention(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
            )
        }
    }
}
