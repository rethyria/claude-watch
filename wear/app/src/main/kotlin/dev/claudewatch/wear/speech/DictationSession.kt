// Issue #134 — the in-app LISTENING phase's state machine, pure Kotlin so the
// stitching rules are JVM-testable (the emulator has no recognizer, so no
// instrumented test can ever exercise them). The platform binding
// (SpeechListener) turns SpeechRecognizer callbacks into [Event]s and carries
// out the [Effect]s this reducer emits; the screen renders [State].
//
// Why a session, not one recognizer turn: SpeechRecognizer ends its turn on
// its own silence detection, so "record until I physically stop" is many
// turns stitched together — every final segment appends to the buffer and
// the recogniser is started again, until the user's explicit Stop. The
// recogniser's own end-of-speech is therefore NEVER the end of the dictation;
// only the user's tap (send) or back (review) is. Segmented-session mode
// (API 33) delivers several segments inside one turn; plain mode delivers one
// per turn — the reducer treats both identically (a Segment commits, a
// SessionEnded restarts), so which one the service supports is invisible here.
package dev.claudewatch.wear.speech

object DictationSession {

    /** Recognizer error classes the reducer distinguishes (mapped by the binding). */
    enum class Failure {
        /** Silence / nothing recognised — "keep going", never "done". */
        NO_SPEECH,
        /** The service was still busy with the previous turn: retry after a beat. */
        BUSY,
        /** RECORD_AUDIO not granted — the binding should fall back to the intent path. */
        PERMISSION,
        /** Anything else (audio, client, network, server, language…). */
        OTHER,
    }

    sealed interface Event {
        /** The recogniser opened the microphone for this turn. */
        data object Ready : Event
        /** Live (non-final) hypothesis for the CURRENT segment. */
        data class Partial(val text: String) : Event
        /** A final segment: onResults (plain) or onSegmentResults (segmented). */
        data class Segment(val text: String) : Event
        /** The recogniser's turn is over (onResults fired / onEndOfSegmentedSession). */
        data object SessionEnded : Event
        data class Error(val failure: Failure) : Event
        /** The binding's bounded wait after StopListening expired with no final result. */
        data object StopTimeout : Event
        /** User: tap — finish and SEND what was heard. */
        data object Stop : Event
        /** User: back — finish and REVIEW before sending. */
        data object Review : Event
        /** User: send from the review state. */
        data object Send : Event
        /** User: throw the text away (review Discard, or a cancel while listening). */
        data object Discard : Event
    }

    sealed interface Effect {
        /** Call startListening again (a turn ended but the user hasn't stopped). */
        data object Restart : Effect
        /** Call stopListening: the user asked to finish, wait for the final segment. */
        data object StopListening : Effect
        /** Tear the recogniser down: the session is over. */
        data object Release : Effect
        /** Hand the stitched text to the send path. */
        data class Deliver(val text: String) : Effect
        /** No text was ever heard and the session broke: surface [reason]. */
        data class Fail(val reason: String, val beforeSpeech: Boolean) : Effect
    }

    sealed interface Phase {
        data object Listening : Phase
        /** stopListening issued; [review] says where the final text goes. */
        data class Stopping(val review: Boolean) : Phase
        /** Text held for the user's Send / Discard; [note] explains an early stop. */
        data class Review(val note: String?) : Phase
        data object Done : Phase
        data object Cancelled : Phase
        data class Failed(val reason: String) : Phase
    }

    data class State(
        val phase: Phase = Phase.Listening,
        /** Committed segments, in order. */
        val segments: List<String> = emptyList(),
        /** The live hypothesis of the segment in progress. */
        val partial: String = "",
        /** The microphone opened at least once — a later failure is mid-speech, not a dead start. */
        val everReady: Boolean = false,
        /** The microphone opened in the CURRENT turn (reset by every restart). */
        val turnReady: Boolean = false,
        /**
         * Consecutive turns that ended without the mic ever opening; a
         * runaway error loop trips at [MAX_IDLE_RESTARTS]. A user who sits in
         * silence is NOT this: their turns open the mic and end on NO_SPEECH.
         */
        val idleRestarts: Int = 0,
        /** Speech (partial or segment) arrived in the CURRENT turn. */
        val turnHeard: Boolean = false,
        /**
         * Consecutive turns that opened the mic and heard nothing. A user
         * thinking is a few of these; a watch left running on a wrist-down
         * is many — [MAX_SILENT_TURNS] parks the text in review rather than
         * holding the mic and the screen until the battery dies.
         */
        val silentTurns: Int = 0,
    ) {
        /** What the user sees / what gets sent: segments then the live partial. */
        val transcript: String
            get() = (segments + partial).filter { it.isNotBlank() }.joinToString(" ")

        val terminal: Boolean
            get() = phase is Phase.Done || phase is Phase.Cancelled || phase is Phase.Failed
    }

    data class Step(val state: State, val effects: List<Effect> = emptyList())

    /** Consecutive turns that ended without the mic ever opening before we give up. */
    const val MAX_IDLE_RESTARTS = 5

    /** Consecutive mic-open-but-silent turns before the session parks itself (~1–2 min). */
    const val MAX_SILENT_TURNS = 8

    const val NOTE_STOPPED_EARLY = "recognition stopped early"
    const val NOTE_SILENCE = "stopped after a long silence"

    fun reduce(state: State, event: Event): Step {
        if (state.terminal) return Step(state)
        return when (val phase = state.phase) {
            Phase.Listening -> listening(state, event)
            is Phase.Stopping -> stopping(state, phase.review, event)
            is Phase.Review -> review(state, event)
            else -> Step(state)
        }
    }

    private fun listening(state: State, event: Event): Step = when (event) {
        Event.Ready -> Step(state.copy(everReady = true, turnReady = true, idleRestarts = 0))
        is Event.Partial -> Step(state.copy(partial = event.text, turnReady = true, turnHeard = true, idleRestarts = 0, silentTurns = 0))
        is Event.Segment -> Step(state.commit(event.text).copy(turnReady = true, turnHeard = true, idleRestarts = 0, silentTurns = 0))
        Event.SessionEnded -> restart(state)
        is Event.Error -> when (event.failure) {
            // Silence is not the end: the partial (if any) is what the user
            // watched appear, so keep it rather than let it vanish.
            Failure.NO_SPEECH, Failure.BUSY -> restart(state.commit(state.partial))
            Failure.PERMISSION -> fail(state, "Microphone permission denied")
            Failure.OTHER -> if (state.transcript.isBlank()) {
                fail(state, "Speech recognition failed")
            } else {
                // Keep what was heard; the user decides whether it's worth sending.
                park(state, NOTE_STOPPED_EARLY)
            }
        }
        Event.Stop -> Step(state.copy(phase = Phase.Stopping(review = false)), listOf(Effect.StopListening))
        Event.Review -> Step(state.copy(phase = Phase.Stopping(review = true)), listOf(Effect.StopListening))
        Event.Discard -> Step(state.copy(phase = Phase.Cancelled), listOf(Effect.Release))
        Event.Send, Event.StopTimeout -> Step(state)
    }

    private fun stopping(state: State, review: Boolean, event: Event): Step = when (event) {
        Event.Ready -> Step(state.copy(everReady = true, turnReady = true))
        is Event.Partial -> Step(state.copy(partial = event.text))
        // The final segment lands: the turn is finished, no need to wait for
        // SessionEnded (plain mode fires onResults then nothing else anyway).
        is Event.Segment -> finish(state.commit(event.text), review)
        Event.SessionEnded, is Event.Error, Event.StopTimeout -> finish(state.commit(state.partial), review)
        Event.Discard -> Step(state.copy(phase = Phase.Cancelled), listOf(Effect.Release))
        // Already stopping; a repeat tap changes nothing, a back now just
        // downgrades send to review (the safer of the two).
        Event.Stop, Event.Send -> Step(state)
        Event.Review -> Step(state.copy(phase = Phase.Stopping(review = true)))
    }

    private fun review(state: State, event: Event): Step = when (event) {
        Event.Send, Event.Stop -> deliver(state)
        Event.Discard -> Step(state.copy(phase = Phase.Cancelled))
        // Late recogniser noise after Release: ignored, the text is frozen.
        else -> Step(state)
    }

    private fun finish(state: State, review: Boolean): Step = when {
        review -> Step(state.copy(phase = Phase.Review(null)), listOf(Effect.Release))
        // Tap-to-send with nothing heard: nothing to send, nothing to show.
        state.transcript.isBlank() -> Step(state.copy(phase = Phase.Cancelled), listOf(Effect.Release))
        else -> Step(state.copy(phase = Phase.Done), listOf(Effect.Release, Effect.Deliver(state.transcript)))
    }

    private fun deliver(state: State): Step =
        if (state.transcript.isBlank()) {
            Step(state.copy(phase = Phase.Cancelled))
        } else {
            Step(state.copy(phase = Phase.Done), listOf(Effect.Deliver(state.transcript)))
        }

    private fun restart(state: State): Step {
        val idle = if (state.turnReady) 0 else state.idleRestarts + 1
        val silent = if (state.turnReady && !state.turnHeard) state.silentTurns + 1 else 0
        return when {
            // The service can't hold a turn any more. Text already heard is
            // never thrown away for that: park it for the user's decision.
            idle >= MAX_IDLE_RESTARTS ->
                if (state.transcript.isBlank()) fail(state, "Speech recognizer keeps stopping")
                else park(state, NOTE_STOPPED_EARLY)
            silent >= MAX_SILENT_TURNS ->
                if (state.transcript.isBlank()) Step(state.copy(phase = Phase.Cancelled), listOf(Effect.Release))
                else park(state, NOTE_SILENCE)
            else -> Step(
                state.copy(partial = "", turnReady = false, turnHeard = false, idleRestarts = idle, silentTurns = silent),
                listOf(Effect.Restart),
            )
        }
    }

    /** Stop early but keep the text: the review hold with [note] explaining why. */
    private fun park(state: State, note: String): Step =
        Step(state.commit(state.partial).copy(phase = Phase.Review(note)), listOf(Effect.Release))

    private fun fail(state: State, reason: String): Step = Step(
        state.copy(phase = Phase.Failed(reason)),
        listOf(Effect.Release, Effect.Fail(reason, beforeSpeech = !state.everReady)),
    )

    private fun State.commit(text: String): State {
        val t = text.trim()
        return if (t.isEmpty()) copy(partial = "") else copy(segments = segments + t, partial = "")
    }
}
