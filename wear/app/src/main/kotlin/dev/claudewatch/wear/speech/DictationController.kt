// Issue #134 — the in-app listening phase's platform half. Binds Android's
// SpeechRecognizer to the pure [DictationSession] reducer: recogniser
// callbacks become Events, the reducer's Effects become recogniser calls, and
// the resulting State is what the listening screen renders.
//
// Why in-app at all: RecognizerIntent.ACTION_RECOGNIZE_SPEECH resolves to
// ANOTHER app's activity, which covers the display for the whole dictation —
// so FLAG_KEEP_SCREEN_ON is not ours to set (the watch sleeps at 15s and the
// recording dies with it) and end-of-speech is not ours to decide. Owning the
// window fixes both: the screen holds keepScreenOn, and the session below
// stitches recogniser turns until the user's own stop.
package dev.claudewatch.wear.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One live dictation at a time; [state] is null while none is running.
 * Main-thread only (SpeechRecognizer's own contract).
 */
class DictationController(private val context: Context) {

    /** Where the stitched text goes, and how a dead-start failure is handled. */
    interface Outcome {
        fun onText(text: String)
        /**
         * The session broke. [beforeSpeech] = the microphone never opened, so
         * nothing was lost yet and the caller may fall back to the intent
         * path; otherwise surface [reason].
         */
        fun onFailed(reason: String, beforeSpeech: Boolean)
    }

    private val _state = MutableStateFlow<DictationSession.State?>(null)
    val state: StateFlow<DictationSession.State?> = _state

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var outcome: Outcome? = null
    private var stopTimeout: Runnable? = null
    /** Which recogniser path this device offers (probed once). */
    private var segmentedAttempted = false
    /** Diagnostics for the on-device experiment: which callbacks the service actually fires. */
    var lastPathSeen: String = "none"
        private set

    val running: Boolean get() = _state.value != null

    /** Wall clock of the last speech heard (or the session start). */
    private var lastSpeechAt = 0L

    companion object {
        private const val TAG = "Dictation"
        /** How long to wait for the final segment after stopListening before finishing with the partial. */
        private const val STOP_GRACE_MS = 1500L
        private const val BUSY_RETRY_MS = 300L
        /** Nothing heard for this long → the session parks itself (Event.LongSilence). */
        const val SILENCE_LIMIT_MS = 120_000L

        /**
         * Can this device run the in-app path at all? False on the emulator
         * (no recognition service) — the intent path stays the fallback.
         */
        fun recognitionAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context) ||
                (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

        fun hasMicPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** Begin a session; a session already running is left alone (one at a time). */
    fun start(outcome: Outcome) {
        if (running) {
            Log.w(TAG, "start() while a session is running — ignored (phase=${_state.value?.phase})")
            return
        }
        this.outcome = outcome
        lastSpeechAt = android.os.SystemClock.elapsedRealtime()
        _state.value = DictationSession.State()
        val r = create()
        if (r == null) {
            dispatch(DictationSession.Event.Error(DictationSession.Failure.OTHER))
            return
        }
        recognizer = r
        r.setRecognitionListener(listener)
        startTurn()
    }

    /** User tapped: finish and send. */
    fun stop() = dispatch(DictationSession.Event.Stop)

    /** User went back: finish and review. */
    fun review() = dispatch(DictationSession.Event.Review)

    /** Send from the review state. */
    fun send() = dispatch(DictationSession.Event.Send)

    /** Throw the text away (review Discard, or a cancel while listening). */
    fun discard() = dispatch(DictationSession.Event.Discard)

    // The DEFAULT service first: on the watch it IS the on-device Google
    // service, and the dedicated on-device factory reports "component
    // present" even when no language model is downloaded — every start would
    // then error out before speech and bounce to the intent fallback, i.e.
    // the very truncation #134 exists to fix, with nothing on screen saying so.
    private fun create(): SpeechRecognizer? = try {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            null
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "createSpeechRecognizer failed", e)
        null
    }

    private fun startTurn() {
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT >= 33) {
            // Segmented-session mode: the service keeps the turn open and
            // hands over one segment per pause instead of ending on the first
            // silence. Advisory — a service that doesn't support it behaves as
            // plain mode (onResults), which the reducer stitches identically.
            // The silence extra doubles as the mode's required companion.
            intent.putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            )
            // An Int, not a Long: Google's service reads it with getInt and
            // logs "not set with positive value; ignoring EXTRA_SEGMENTED_SESSION"
            // for a Long (seen live on the SM-L330, 2026-09-05).
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            segmentedAttempted = true
        }
        try {
            r.startListening(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "startListening failed", e)
            dispatch(DictationSession.Event.Error(DictationSession.Failure.OTHER))
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = dispatch(DictationSession.Event.Ready)
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstResult() ?: return
            lastSpeechAt = android.os.SystemClock.elapsedRealtime()
            dispatch(DictationSession.Event.Partial(text))
        }

        override fun onResults(results: Bundle?) {
            lastPathSeen = "onResults"
            results.firstResult()?.let { heard(it) }
            // Plain mode: the turn is over with this callback.
            dispatch(DictationSession.Event.SessionEnded)
        }

        @RequiresApi(33)
        override fun onSegmentResults(segmentResults: Bundle) {
            lastPathSeen = "onSegmentResults"
            segmentResults.firstResult()?.let { heard(it) }
        }

        @RequiresApi(33)
        override fun onEndOfSegmentedSession() {
            lastPathSeen = "onEndOfSegmentedSession"
            dispatch(DictationSession.Event.SessionEnded)
        }

        override fun onError(error: Int) {
            Log.d(TAG, "onError $error (segmented=$segmentedAttempted, path=$lastPathSeen)")
            val failure = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> DictationSession.Failure.NO_SPEECH
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> DictationSession.Failure.BUSY
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> DictationSession.Failure.PERMISSION
                else -> DictationSession.Failure.OTHER
            }
            lastErrorWasBusy = failure == DictationSession.Failure.BUSY
            dispatch(DictationSession.Event.Error(failure))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun heard(segment: String) {
        lastSpeechAt = android.os.SystemClock.elapsedRealtime()
        dispatch(DictationSession.Event.Segment(segment))
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun dispatch(event: DictationSession.Event) {
        val current = _state.value ?: return
        val step = DictationSession.reduce(current, event)
        // Terminal: nothing left to render. Clear BEFORE performing the
        // effects so an outcome callback that starts a new session is not
        // refused by the running guard and then clobbered.
        val finalOutcome = outcome
        if (step.state.terminal) {
            _state.value = null
            outcome = null
        } else {
            _state.value = step.state
        }
        step.effects.forEach { perform(it, finalOutcome) }
    }

    private fun perform(effect: DictationSession.Effect, outcome: Outcome?) {
        when (effect) {
            DictationSession.Effect.Restart -> {
                if (android.os.SystemClock.elapsedRealtime() - lastSpeechAt > SILENCE_LIMIT_MS) {
                    dispatch(DictationSession.Event.LongSilence)
                    return
                }
                val delay = if (lastErrorWasBusy) BUSY_RETRY_MS else 0L
                handler.postDelayed({ if (_state.value?.phase == DictationSession.Phase.Listening) startTurn() }, delay)
            }
            DictationSession.Effect.StopListening -> {
                try { recognizer?.stopListening() } catch (e: RuntimeException) { Log.w(TAG, "stopListening", e) }
                val timeout = Runnable { dispatch(DictationSession.Event.StopTimeout) }
                stopTimeout = timeout
                handler.postDelayed(timeout, STOP_GRACE_MS)
            }
            DictationSession.Effect.Release -> release()
            is DictationSession.Effect.Deliver -> outcome?.onText(effect.text)
            is DictationSession.Effect.Fail -> outcome?.onFailed(effect.reason, effect.beforeSpeech)
        }
    }

    // A BUSY error's Restart wants a beat; the reducer folds BUSY into the
    // same Restart effect as silence, so the binding remembers the cause.
    private var lastErrorWasBusy = false
        get() { val v = field; field = false; return v }

    private fun release() {
        stopTimeout?.let(handler::removeCallbacks)
        stopTimeout = null
        lastErrorWasBusy = false
        recognizer?.let {
            try { it.cancel() } catch (_: RuntimeException) {}
            try { it.destroy() } catch (_: RuntimeException) {}
        }
        recognizer = null
    }
}
