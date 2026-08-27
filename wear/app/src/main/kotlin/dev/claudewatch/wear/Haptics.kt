// Haptic feedback grammar. Two COMMAND verbs (issue #20) — so the wrist can
// tell "the bridge really has it" from "it failed, look at the watch" without
// lighting the screen — plus three ATTENTION verbs (issue #129) for incoming
// news: a prompt blocking on the user, a turn completing, something breaking.
// Every verb is a DISTINCT waveform (see the factory table below), because a
// buzz the user cannot classify without looking defeats the whole at-a-glance
// point. Wired through an interface so the JVM unit tests (mockable
// android.jar — no real Vibrator) can record the grammar instead of vibrating.
package dev.claudewatch.wear

import android.app.NotificationManager
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
// CLASS, never just amplitude: predefined click (ack) vs one short pulse
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
 * workFinished: one 160 ms pulse. It was the predefined TICK — "the lightest
 * touch the platform offers" — which is the right instinct for passive
 * progress news and the wrong one for a wrist: on hardware it is not reliably
 * felt at all unless you are still and paying attention, which is the opposite
 * of what a glanceable progress signal is for (user-directed, 2026-08-27).
 * A single pulse keeps it the quietest verb with a pulse COUNT of one, and its
 * SHORT duration class is what separates it from wentWrong's lone 450 ms drone.
 */
internal fun workFinishedEffect(): VibrationEffect =
    VibrationEffect.createWaveform(longArrayOf(0, 160), -1)

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

    /** Quiet-hours oracle for the attention verbs — see [attention]. */
    private val notifications: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

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
     * Attention verbs vibrate UNATTRIBUTED, and this method enforces quiet
     * hours itself.
     *
     * They used to carry `VibrationAttributes.USAGE_NOTIFICATION`, so that DND
     * / bedtime / theater would gate them exactly like notification alerts. The
     * intent was right and the mechanism was fatal: an ordinary app on Wear is
     * not permitted to vibrate as a notification, so the platform dropped every
     * single one. Measured on the user's SM-L330 (2026-08-27,
     * `dumpsys vibrator_manager`): 68 consecutive attention verbs, every one
     * `ignored_app_ops`, DND on AND off — while the same app's unattributed
     * COMMAND verbs played from the same process, and the system vibrated
     * happily on the app's behalf for a posted notification 217 ms after one of
     * the drops. The verbs had never once fired since they shipped.
     *
     * So the buzz goes out unattributed — the only usage class Wear actually
     * plays for an app — and the DND policy the attribution was buying us is
     * read explicitly instead. COMMAND verbs stay outside this gate on purpose:
     * they are touch feedback for an action the user is performing right now,
     * not an interruption to police.
     */
    private fun attention(effect: VibrationEffect) {
        if (isQuietHours()) return
        vibrator.vibrate(effect)
    }

    /**
     * Whether an unsolicited buzz would interrupt the user's quiet hours —
     * DND, bedtime and theater mode all surface here as a non-ALL interruption
     * filter. Reading the filter needs no permission (only CHANGING it does).
     *
     * FAILS OPEN on [NotificationManager.INTERRUPTION_FILTER_UNKNOWN]: an
     * unreadable filter means "the platform did not tell us", and a stray buzz
     * during quiet hours is a smaller failure than the one this whole method
     * exists to undo — an attention channel that silently disappears and takes
     * months to notice.
     */
    private fun isQuietHours(): Boolean = when (notifications.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL,
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> false
        else -> true
    }
}
