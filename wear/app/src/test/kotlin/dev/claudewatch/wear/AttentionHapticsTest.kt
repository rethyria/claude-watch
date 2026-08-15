package dev.claudewatch.wear

import dev.claudewatch.shared.protocol.PermissionRequestEvent
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionActivity
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.net.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attention-verb discipline (issue #129), tabled rule by rule over an
 * injected clock and a recording grammar — one test per dedupe rule, each
 * written so that removing exactly its guard fails exactly it:
 *
 *  - per-prompt-id memory        → [replayedPromptNeverRebuzzes]
 *  - departures silent+remembered→ [clearedPromptNeitherBuzzesNorForgets]
 *  - burst window (needsYou)     → [burstOfPromptsCollapsesToOneBuzz]
 *  - burst window (workFinished) → [burstOfFinishesCollapsesToOneBuzz]
 *  - cold first-sync window      → [firstSyncIsQuietExceptOneNeedsYouForTheOldCard]
 *  - edge-only activity          → [onlyAnObservedWorkingToIdleEdgeBuzzes]
 *  - prune-forgets-activity      → [prunedSessionRevivedIdleIsFirstSightAgain]
 *  - error verb + its burst      → [errorFramesBuzzWentWrongOncePerBurst]
 *  - per-verb anchors + routing  → [eachTriggerSpeaksItsOwnVerbWithItsOwnBurstAnchor]
 *  - sustained-loss offline      → [offlineBuzzesOnlyFromConnectedAndOnlySustained]
 *  - terminal states             → [authExpiredBuzzesImmediatelyAndStoppedStaysSilent]
 *  - unpair reset                → [resetReturnsToTheFreshProcessPosture]
 */
class AttentionHapticsTest {

    private class Recorder : Haptics {
        val events = mutableListOf<String>()
        override fun commandAcked() { events += "acked" }
        override fun commandFailed() { events += "failed" }
        override fun needsYou() { events += "needsYou" }
        override fun workFinished() { events += "workFinished" }
        override fun wentWrong() { events += "wentWrong" }
    }

    private val recorder = Recorder()
    private var now = 0L
    private val attention = AttentionHaptics({ recorder }, { now })

    private fun state(
        prompts: List<String> = emptyList(),
        sessions: Map<String, SessionActivity> = emptyMap(),
    ) = BridgeState(
        sessions = sessions.mapValues { (id, activity) ->
            SessionState(sessionId = id, activity = activity)
        },
        pendingPermissions = prompts.map {
            PermissionRequestEvent(permissionId = it, sessionId = "s-1", toolName = "Bash")
        },
    )

    /** Anchor the cold window with an empty first emission, then step past it. */
    private fun warmUp() {
        attention.onApplied(state(), errorEvent = false)
        now = AttentionHaptics.COLD_REPLAY_WINDOW_MS + 7_000L
    }

    /** A step comfortably past the burst window. */
    private fun stepPastBurst() {
        now += AttentionHaptics.BURST_WINDOW_MS + 7_000L
    }

    @Test
    fun replayedPromptNeverRebuzzes() {
        warmUp()
        attention.onApplied(state(prompts = listOf("p-1")), errorEvent = false)
        assertEquals(listOf("needsYou"), recorder.events)
        // Far past the burst window: only the id memory can keep this silent.
        stepPastBurst()
        attention.onApplied(state(prompts = listOf("p-1")), errorEvent = false)
        assertEquals("a reconnect replay of the same card must not re-buzz", listOf("needsYou"), recorder.events)
    }

    @Test
    fun clearedPromptNeitherBuzzesNorForgets() {
        warmUp()
        attention.onApplied(state(prompts = listOf("p-1")), errorEvent = false)
        stepPastBurst()
        // permission-cleared / answered: the prompt departs.
        attention.onApplied(state(), errorEvent = false)
        assertEquals("a clear is silent", listOf("needsYou"), recorder.events)
        stepPastBurst()
        // The stale entry resurfaces (the ack-to-advance queue keeps them):
        // the departure must not have FORGOTTEN the id.
        attention.onApplied(state(prompts = listOf("p-1")), errorEvent = false)
        assertEquals(listOf("needsYou"), recorder.events)
    }

    @Test
    fun burstOfPromptsCollapsesToOneBuzz() {
        warmUp()
        attention.onApplied(state(prompts = listOf("p-1")), errorEvent = false)
        now += 1_000 // inside the burst window
        attention.onApplied(state(prompts = listOf("p-1", "p-2")), errorEvent = false)
        assertEquals("a sync's second prompt folds into the first buzz", listOf("needsYou"), recorder.events)
        // Collapsed, NOT deferred: the suppressed id was still recorded.
        stepPastBurst()
        attention.onApplied(state(prompts = listOf("p-1", "p-2")), errorEvent = false)
        assertEquals(listOf("needsYou"), recorder.events)
    }

    @Test
    fun burstOfFinishesCollapsesToOneBuzz() {
        attention.onApplied(
            state(sessions = mapOf("s-1" to SessionActivity.WORKING, "s-2" to SessionActivity.WORKING)),
            errorEvent = false,
        )
        now = AttentionHaptics.COLD_REPLAY_WINDOW_MS + 7_000L
        attention.onApplied(
            state(sessions = mapOf("s-1" to SessionActivity.IDLE, "s-2" to SessionActivity.WORKING)),
            errorEvent = false,
        )
        now += 1_000 // the second finish lands inside the first's window
        attention.onApplied(
            state(sessions = mapOf("s-1" to SessionActivity.IDLE, "s-2" to SessionActivity.IDLE)),
            errorEvent = false,
        )
        assertEquals(listOf("workFinished"), recorder.events)
    }

    @Test
    fun firstSyncIsQuietExceptOneNeedsYouForTheOldCard() {
        // A fresh process connects onto three idle sessions, one working
        // session and one old unanswered card: the recorded product call is
        // ONE needsYou (the card still blocks the agent and this process has
        // no evidence the user ever felt the original buzz) — and nothing
        // else, however historical the replay is.
        attention.onApplied(
            state(
                prompts = listOf("p-old"),
                sessions = mapOf(
                    "s-1" to SessionActivity.IDLE,
                    "s-2" to SessionActivity.IDLE,
                    "s-3" to SessionActivity.IDLE,
                    "s-4" to SessionActivity.WORKING,
                ),
            ),
            errorEvent = false,
        )
        // The replay continues inside the cold window: a historical stop
        // (s-4's working→idle) and a historical error frame are pre-existing
        // state, not news.
        now += 500
        attention.onApplied(
            state(
                prompts = listOf("p-old"),
                sessions = mapOf(
                    "s-1" to SessionActivity.IDLE,
                    "s-2" to SessionActivity.IDLE,
                    "s-3" to SessionActivity.IDLE,
                    "s-4" to SessionActivity.IDLE,
                ),
            ),
            errorEvent = true,
        )
        assertEquals(listOf("needsYou"), recorder.events)
    }

    @Test
    fun onlyAnObservedWorkingToIdleEdgeBuzzes() {
        warmUp()
        // First sight of an idle session RECORDS, silently (warm, so the
        // silence is the edge rule's, not the cold window's).
        attention.onApplied(state(sessions = mapOf("s-1" to SessionActivity.IDLE)), errorEvent = false)
        stepPastBurst()
        // Idle re-send: no edge. Idle→working: silent by design.
        attention.onApplied(state(sessions = mapOf("s-1" to SessionActivity.IDLE)), errorEvent = false)
        attention.onApplied(state(sessions = mapOf("s-1" to SessionActivity.WORKING)), errorEvent = false)
        assertTrue("nothing above is a working→idle edge: ${recorder.events}", recorder.events.isEmpty())
        stepPastBurst()
        attention.onApplied(state(sessions = mapOf("s-1" to SessionActivity.IDLE)), errorEvent = false)
        assertEquals(listOf("workFinished"), recorder.events)
    }

    @Test
    fun prunedSessionRevivedIdleIsFirstSightAgain() {
        warmUp()
        attention.onApplied(state(sessions = mapOf("s-1" to SessionActivity.WORKING)), errorEvent = false)
        stepPastBurst()
        // The session ends (pruned from state), then the same stable id
        // re-registers idle-flagged: first sight again, not a phantom edge
        // against the remembered WORKING.
        attention.onApplied(state(), errorEvent = false)
        stepPastBurst()
        attention.onApplied(state(sessions = mapOf("s-1" to SessionActivity.IDLE)), errorEvent = false)
        assertTrue("a revived id must not inherit a stale edge: ${recorder.events}", recorder.events.isEmpty())
    }

    @Test
    fun errorFramesBuzzWentWrongOncePerBurst() {
        warmUp()
        attention.onApplied(state(), errorEvent = true)
        now += 1_000 // an error storm inside the window
        attention.onApplied(state(), errorEvent = true)
        assertEquals(listOf("wentWrong"), recorder.events)
        stepPastBurst()
        attention.onApplied(state(), errorEvent = true)
        assertEquals("a genuinely later error is fresh news", listOf("wentWrong", "wentWrong"), recorder.events)
    }

    @Test
    fun eachTriggerSpeaksItsOwnVerbWithItsOwnBurstAnchor() {
        attention.onApplied(state(sessions = mapOf("s-1" to SessionActivity.WORKING)), errorEvent = false)
        now = AttentionHaptics.COLD_REPLAY_WINDOW_MS + 7_000L
        attention.onApplied(
            state(prompts = listOf("p-1"), sessions = mapOf("s-1" to SessionActivity.WORKING)),
            errorEvent = false,
        )
        // 1s later — inside needsYou's window — a turn finishes AND an error
        // frame lands: the burst anchors are PER VERB, so both still speak.
        now += 1_000
        attention.onApplied(
            state(prompts = listOf("p-1"), sessions = mapOf("s-1" to SessionActivity.IDLE)),
            errorEvent = true,
        )
        assertEquals(listOf("needsYou", "workFinished", "wentWrong"), recorder.events)
    }

    @Test
    fun offlineBuzzesOnlyFromConnectedAndOnlySustained() {
        // A fresh launch that cannot reach its bridge is not a LOSS: no
        // Connected was ever observed, so even a sustained retry is silent.
        attention.onConnection(ConnectionState.Reconnecting(AttentionHaptics.OFFLINE_AFTER_ATTEMPT, "x"))
        assertTrue(recorder.events.isEmpty())

        attention.onConnection(ConnectionState.Connected)
        // Routine stream recycles reconnect at attempt 1–2 and stay silent.
        attention.onConnection(ConnectionState.Reconnecting(1, "x"))
        attention.onConnection(ConnectionState.Reconnecting(2, "x"))
        assertTrue("a routine recycle must not alarm: ${recorder.events}", recorder.events.isEmpty())
        attention.onConnection(ConnectionState.Reconnecting(3, "x"))
        assertEquals(listOf("wentWrong"), recorder.events)
        // One buzz per outage: later attempts stay latched, however far apart.
        stepPastBurst()
        attention.onConnection(ConnectionState.Reconnecting(4, "x"))
        assertEquals(listOf("wentWrong"), recorder.events)
        // The next Connected re-arms; the next sustained loss is fresh news.
        attention.onConnection(ConnectionState.Connected)
        stepPastBurst()
        attention.onConnection(ConnectionState.Reconnecting(3, "x"))
        assertEquals(listOf("wentWrong", "wentWrong"), recorder.events)
    }

    @Test
    fun authExpiredBuzzesImmediatelyAndStoppedStaysSilent() {
        attention.onConnection(ConnectionState.Connected)
        // The user's own disconnect/unpair needs no alarm.
        attention.onConnection(ConnectionState.Stopped)
        assertTrue(recorder.events.isEmpty())
        attention.onConnection(ConnectionState.Connected)
        // A dead token is terminal — no retry threshold to wait out.
        attention.onConnection(ConnectionState.AuthExpired("401"))
        assertEquals(listOf("wentWrong"), recorder.events)
    }

    @Test
    fun resetReturnsToTheFreshProcessPosture() {
        warmUp()
        attention.onApplied(state(prompts = listOf("p-1")), errorEvent = false)
        stepPastBurst()
        attention.reset() // unpair
        // The id memory is gone (a re-pair's old card is news to the new
        // pairing, same call as process death)…
        attention.onApplied(
            state(prompts = listOf("p-1"), sessions = mapOf("s-1" to SessionActivity.WORKING)),
            errorEvent = false,
        )
        // …and the cold window re-armed: the new bridge's replayed history
        // stays quiet.
        now += 500
        attention.onApplied(
            state(prompts = listOf("p-1"), sessions = mapOf("s-1" to SessionActivity.IDLE)),
            errorEvent = true,
        )
        assertEquals(listOf("needsYou", "needsYou"), recorder.events)
    }
}
