package dev.claudewatch.wear.speech

import dev.claudewatch.wear.speech.DictationSession.Effect
import dev.claudewatch.wear.speech.DictationSession.Event
import dev.claudewatch.wear.speech.DictationSession.Failure
import dev.claudewatch.wear.speech.DictationSession.Phase
import dev.claudewatch.wear.speech.DictationSession.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #134: the stitching rules that make "record until I physically stop"
 * out of a recogniser that ends every turn on silence. Pure reducer — the
 * emulator has no recogniser, so this is the only place they can be pinned.
 */
class DictationSessionTest {

    /** Run [events] from a fresh session; returns the final state and every effect in order. */
    private fun run(vararg events: Event, from: State = State()): Pair<State, List<Effect>> {
        var state = from
        val effects = mutableListOf<Effect>()
        for (e in events) {
            val step = DictationSession.reduce(state, e)
            state = step.state
            effects += step.effects
        }
        return state to effects
    }

    @Test
    fun `a turn ending on its own restarts listening instead of finishing`() {
        val (state, effects) = run(Event.Ready, Event.Partial("run the"), Event.Segment("run the tests"), Event.SessionEnded)
        assertEquals(Phase.Listening, state.phase)
        assertEquals(listOf(Effect.Restart), effects)
        assertEquals("run the tests", state.transcript)
        assertEquals("", state.partial)
    }

    @Test
    fun `silence errors keep going and keep the partial the user watched appear`() {
        val (state, effects) = run(Event.Ready, Event.Partial("and then"), Event.Error(Failure.NO_SPEECH))
        assertEquals(Phase.Listening, state.phase)
        assertEquals(listOf(Effect.Restart), effects)
        assertEquals("and then", state.transcript)
    }

    @Test
    fun `segments stitch in order with the live partial at the tail`() {
        val (state, _) = run(
            Event.Ready,
            Event.Segment("open the file"),
            Event.SessionEnded,
            Event.Ready,
            Event.Segment("then run the tests"),
            Event.SessionEnded,
            Event.Ready,
            Event.Partial("and commit"),
        )
        assertEquals("open the file then run the tests and commit", state.transcript)
    }

    @Test
    fun `tap to send stops listening and delivers once the final segment lands`() {
        val (state, effects) = run(
            Event.Ready, Event.Segment("first part"), Event.SessionEnded, Event.Ready,
            Event.Partial("second"), Event.Stop, Event.Segment("second part"),
        )
        assertEquals(Phase.Done, state.phase)
        assertTrue(state.terminal)
        assertEquals(
            listOf(Effect.Restart, Effect.StopListening, Effect.Release, Effect.Deliver("first part second part")),
            effects,
        )
    }

    @Test
    fun `stop with no final segment delivers the last partial`() {
        val (state, effects) = run(Event.Ready, Event.Partial("half a thought"), Event.Stop, Event.StopTimeout)
        assertEquals(Phase.Done, state.phase)
        assertTrue(effects.contains(Effect.Deliver("half a thought")))
    }

    @Test
    fun `stop with nothing heard cancels quietly`() {
        val (state, effects) = run(Event.Ready, Event.Stop, Event.Error(Failure.NO_SPEECH))
        assertEquals(Phase.Cancelled, state.phase)
        assertEquals(listOf(Effect.StopListening, Effect.Release), effects)
        assertFalse(effects.any { it is Effect.Deliver })
    }

    @Test
    fun `back reviews instead of sending, and review then sends the frozen text`() {
        val (reviewing, effects) = run(Event.Ready, Event.Partial("draft text"), Event.Review, Event.SessionEnded)
        assertEquals(Phase.Review(null), reviewing.phase)
        assertEquals(listOf(Effect.StopListening, Effect.Release), effects)
        // Late recogniser noise cannot alter the frozen text.
        val (still, _) = run(Event.Partial("garbage"), Event.Segment("more garbage"), from = reviewing)
        assertEquals("draft text", still.transcript)
        val (sent, sendEffects) = run(Event.Send, from = still)
        assertEquals(Phase.Done, sent.phase)
        assertEquals(listOf(Effect.Deliver("draft text")), sendEffects)
    }

    @Test
    fun `review discard throws the text away without delivering`() {
        val (reviewing, _) = run(Event.Ready, Event.Segment("text"), Event.Review, Event.SessionEnded)
        val (state, effects) = run(Event.Discard, from = reviewing)
        assertEquals(Phase.Cancelled, state.phase)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `a hard error mid-speech keeps the text for review with a note`() {
        val (state, effects) = run(Event.Ready, Event.Segment("keep this"), Event.SessionEnded, Event.Ready, Event.Partial("and this"), Event.Error(Failure.OTHER))
        assertEquals(Phase.Review("recognition stopped early"), state.phase)
        assertEquals("keep this and this", state.transcript)
        assertEquals(listOf(Effect.Restart, Effect.Release), effects)
    }

    @Test
    fun `a hard error before any speech fails, flagged as before-speech when the mic never opened`() {
        val (dead, deadEffects) = run(Event.Error(Failure.OTHER))
        assertEquals(Phase.Failed("Speech recognition failed"), dead.phase)
        assertEquals(listOf(Effect.Release, Effect.Fail("Speech recognition failed", beforeSpeech = true)), deadEffects)

        val (afterReady, effects) = run(Event.Ready, Event.Error(Failure.OTHER))
        assertTrue(afterReady.phase is Phase.Failed)
        assertEquals(Effect.Fail("Speech recognition failed", beforeSpeech = false), effects.last())
    }

    @Test
    fun `permission denial fails as before-speech so the caller can fall back`() {
        val (_, effects) = run(Event.Error(Failure.PERMISSION))
        assertEquals(Effect.Fail("Microphone permission denied", beforeSpeech = true), effects.last())
    }

    @Test
    fun `turns that never open the mic trip the runaway guard, silent turns do not`() {
        // Runaway: the service ends every turn without Ready.
        var state = State()
        val effects = mutableListOf<Effect>()
        repeat(DictationSession.MAX_IDLE_RESTARTS) {
            val step = DictationSession.reduce(state, Event.Error(Failure.OTHER).let { Event.SessionEnded })
            state = step.state; effects += step.effects
        }
        assertTrue(state.phase is Phase.Failed)
        assertEquals(DictationSession.MAX_IDLE_RESTARTS - 1, effects.count { it == Effect.Restart })

        // Thinking in silence: the mic opens each turn, NO_SPEECH ends it — fine up to the silence ceiling.
        var quiet = State()
        repeat(DictationSession.MAX_SILENT_TURNS - 1) {
            quiet = DictationSession.reduce(quiet, Event.Ready).state
            quiet = DictationSession.reduce(quiet, Event.Error(Failure.NO_SPEECH)).state
        }
        assertEquals(Phase.Listening, quiet.phase)
    }

    @Test
    fun `the runaway guard parks heard text in review instead of throwing it away`() {
        var state = run(Event.Ready, Event.Segment("keep this")).first
        repeat(DictationSession.MAX_IDLE_RESTARTS + 1) {
            state = DictationSession.reduce(state, Event.SessionEnded).state
        }
        assertEquals(Phase.Review(DictationSession.NOTE_STOPPED_EARLY), state.phase)
        assertEquals("keep this", state.transcript)
    }

    @Test
    fun `a long silence parks the text, and cancels when nothing was ever heard`() {
        var withText = run(Event.Ready, Event.Segment("said this"), Event.SessionEnded).first
        repeat(DictationSession.MAX_SILENT_TURNS) {
            withText = DictationSession.reduce(withText, Event.Ready).state
            withText = DictationSession.reduce(withText, Event.Error(Failure.NO_SPEECH)).state
        }
        assertEquals(Phase.Review(DictationSession.NOTE_SILENCE), withText.phase)
        assertEquals("said this", withText.transcript)

        var empty = State()
        repeat(DictationSession.MAX_SILENT_TURNS) {
            empty = DictationSession.reduce(empty, Event.Ready).state
            empty = DictationSession.reduce(empty, Event.Error(Failure.NO_SPEECH)).state
        }
        assertEquals(Phase.Cancelled, empty.phase)
    }

    @Test
    fun `the binding's wall-clock silence parks text or cancels an empty session`() {
        val (parked, effects) = run(Event.Ready, Event.Segment("kept"), Event.SessionEnded, Event.Ready, Event.LongSilence)
        assertEquals(Phase.Review(DictationSession.NOTE_SILENCE), parked.phase)
        assertEquals("kept", parked.transcript)
        assertEquals(Effect.Release, effects.last())
        val (empty, _) = run(Event.Ready, Event.LongSilence)
        assertEquals(Phase.Cancelled, empty.phase)
    }

    @Test
    fun `speech resets the silence count`() {
        var state = State()
        repeat(DictationSession.MAX_SILENT_TURNS - 1) {
            state = DictationSession.reduce(state, Event.Ready).state
            state = DictationSession.reduce(state, Event.Error(Failure.NO_SPEECH)).state
        }
        state = run(Event.Ready, Event.Partial("hm"), Event.Error(Failure.NO_SPEECH), from = state).first
        assertEquals(0, state.silentTurns)
        assertEquals(Phase.Listening, state.phase)
    }

    @Test
    fun `busy is a retry, not a failure`() {
        val (state, effects) = run(Event.Error(Failure.BUSY))
        assertEquals(Phase.Listening, state.phase)
        assertEquals(listOf(Effect.Restart), effects)
    }

    @Test
    fun `discard while listening cancels and releases`() {
        val (state, effects) = run(Event.Ready, Event.Partial("x"), Event.Discard)
        assertEquals(Phase.Cancelled, state.phase)
        assertEquals(listOf(Effect.Release), effects)
    }

    @Test
    fun `terminal states ignore everything`() {
        val (done, _) = run(Event.Ready, Event.Segment("t"), Event.Stop, Event.SessionEnded)
        assertEquals(Phase.Done, done.phase)
        val (same, effects) = run(Event.Segment("more"), Event.Stop, Event.Send, Event.Discard, from = done)
        assertEquals(done, same)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `blank segments are dropped, not stitched as empty gaps`() {
        val (state, _) = run(Event.Ready, Event.Segment("  "), Event.Segment("real"), Event.Segment(""))
        assertEquals(listOf("real"), state.segments)
        assertEquals("real", state.transcript)
    }
}
