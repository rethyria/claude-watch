package dev.claudewatch.shared

import dev.claudewatch.shared.BridgeEventFixtures.PERMISSION_ASK_USER
import dev.claudewatch.shared.BridgeEventFixtures.PERMISSION_BASH
import dev.claudewatch.shared.BridgeEventFixtures.SESSION_ALPHA
import dev.claudewatch.shared.BridgeEventFixtures.SESSION_BETA
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.state.BridgeEventReducer
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reducer half of issue #16: fixtures reduce to expected state snapshots,
 * per-session activity/elapsed state survives another session ending, and
 * lastEventId advances only after a successful parse+apply.
 */
class BridgeEventReducerTest {

    // Deterministic clock: fixture id N is applied at t(N).
    private fun tMs(frame: SseFrame): Long = 1_000_000L + frame.id!!.toLong() * 1_000L

    private fun fold(frames: List<SseFrame>, initial: BridgeState = BridgeState()): BridgeState =
        frames.fold(initial) { state, frame ->
            when (val result = BridgeEventReducer.reduce(state, frame, tMs(frame))) {
                is BridgeEventReducer.Applied -> result.state
                is BridgeEventReducer.Rejected ->
                    throw AssertionError("corpus frame ${frame.id} rejected: ${result.error}")
            }
        }

    private fun corpusPrefix(throughId: Int): List<SseFrame> =
        BridgeEventFixtures.corpus().filter { it.id!!.toInt() <= throughId }

    // ------------------------------------------------------------------
    // Snapshot tests over the fixture corpus
    // ------------------------------------------------------------------

    @Test
    fun twoRunningSessionsSnapshot() {
        val state = fold(corpusPrefix(6))

        assertEquals(listOf(SESSION_ALPHA, SESSION_BETA), state.sessions.keys.toList())
        val alpha = state.sessions.getValue(SESSION_ALPHA)
        val beta = state.sessions.getValue(SESSION_BETA)

        assertEquals("claude", alpha.agent)
        assertEquals("alpha", alpha.folderName)
        assertEquals("/home/dev/projects/alpha", alpha.cwd)
        assertEquals("Fix the flaky auth tests", alpha.title) // additive wire title (frame 2)
        assertEquals(SessionActivity.WORKING, alpha.activity)
        assertEquals(1_002_000L, alpha.activeSinceMs) // frame 2

        assertEquals("codex", beta.agent)
        assertNull(beta.title) // no title reported for beta
        assertEquals(SessionActivity.WORKING, beta.activity)
        assertEquals(1_005_000L, beta.activeSinceMs) // frame 5

        // Elapsed ticks per session while WORKING.
        assertEquals(8_000L, alpha.elapsedMs(1_010_000L))
        assertEquals(5_000L, beta.elapsedMs(1_010_000L))

        // The Bash prompt is pending; lastEventId tracks the frame id.
        assertEquals(listOf(PERMISSION_BASH), state.pendingPermissions.map { it.permissionId })
        assertEquals("6", state.lastEventId)
    }

    @Test
    fun permissionPromptsClearAndReplaceByPermissionId() {
        // After frame 8 the Bash prompt is cleared...
        assertTrue(fold(corpusPrefix(8)).pendingPermissions.isEmpty())

        // ...frame 9 pends the AskUserQuestion prompt...
        val withAskUser = fold(corpusPrefix(9))
        assertEquals(listOf(PERMISSION_ASK_USER), withAskUser.pendingPermissions.map { it.permissionId })

        // ...and a connect-time re-send of the same prompt must not stack a
        // duplicate (the bridge replays pending prompts on every connect).
        val frame9 = BridgeEventFixtures.corpus().first { it.id == "9" }
        val resent = (BridgeEventReducer.reduce(withAskUser, frame9, tMs(frame9)) as BridgeEventReducer.Applied).state
        assertEquals(1, resent.pendingPermissions.size)

        // Frame 10 clears it again.
        assertTrue(fold(corpusPrefix(10)).pendingPermissions.isEmpty())
    }

    @Test
    fun turnEndFreezesOnlyTheAddressedSessionsElapsedClock() {
        // Frame 11 (stop) puts alpha idle at t11; its elapsed span freezes.
        val state = fold(corpusPrefix(12))

        val alpha = state.sessions.getValue(SESSION_ALPHA)
        assertEquals(SessionActivity.IDLE, alpha.activity)
        assertEquals(9_000L, alpha.frozenElapsedMs) // t11 - t2
        assertEquals(9_000L, alpha.elapsedMs(1_099_000L)) // frozen: no longer ticking

        // Beta was NOT addressed: still working on its own clock.
        val beta = state.sessions.getValue(SESSION_BETA)
        assertEquals(SessionActivity.WORKING, beta.activity)
        assertEquals(1_005_000L, beta.activeSinceMs)
    }

    @Test
    fun endedSessionsArePrunedAndTheCorpusDrainsToEmptyState() {
        // After frame 18 alpha is pruned; beta (idle since its task-complete
        // at frame 17) is untouched.
        val afterAlphaEnds = fold(corpusPrefix(18))
        assertFalse(afterAlphaEnds.sessions.containsKey(SESSION_ALPHA))
        val beta = afterAlphaEnds.sessions.getValue(SESSION_BETA)
        assertEquals(SessionActivity.IDLE, beta.activity)
        assertEquals(12_000L, beta.frozenElapsedMs) // t17 - t5

        // The full corpus ends every session and clears every prompt.
        val final = fold(BridgeEventFixtures.corpus())
        assertTrue(final.sessions.isEmpty())
        assertTrue(final.pendingPermissions.isEmpty())
        assertEquals("22", final.lastEventId)
        assertEquals(22, final.eventLog.size)
        assertTrue(final.eventLog.first().startsWith("session connected"))
        assertTrue(final.eventLog.any { it == "tool-output Read file contents here" })
        assertTrue(final.eventLog.last().startsWith("error Failed to spawn claude"))
    }

    // ------------------------------------------------------------------
    // Two concurrent sessions: one ends, the other is untouched
    // ------------------------------------------------------------------

    @Test
    fun oneSessionEndingLeavesTheOthersActivityAndElapsedUntouched() {
        val frames = listOf(
            SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}"""),
            SseFrame("2", "session", """{"state":"running","agent":"codex","cwd":"/b","folderName":"b","sessionId":"B"}"""),
            SseFrame("3", "task-complete", """{"source":"claude","sessionId":"A"}"""),
            SseFrame("4", "session", """{"state":"ended","exitCode":0,"agent":"claude","folderName":"a","sessionId":"A"}"""),
        )

        val beforeEnd = fold(frames.take(3))
        // A completed ONLY its own state; the iOS global-timer bug would have
        // stopped B's clock here too.
        assertEquals(SessionActivity.IDLE, beforeEnd.sessions.getValue("A").activity)
        assertEquals(2_000L, beforeEnd.sessions.getValue("A").frozenElapsedMs) // t3 - t1
        val bBefore = beforeEnd.sessions.getValue("B")
        assertEquals(SessionActivity.WORKING, bBefore.activity)

        val afterEnd = fold(frames)
        assertNull(afterEnd.sessions["A"])
        // B's state is byte-for-byte what it was before A ended, and its
        // elapsed clock keeps ticking.
        assertEquals(bBefore, afterEnd.sessions.getValue("B"))
        assertEquals(8_000L, afterEnd.sessions.getValue("B").elapsedMs(1_010_000L))
        assertEquals("B", afterEnd.currentSessionId)
    }

    @Test
    fun taskCompleteWithoutOrWithUnknownSessionIdChangesNoSession() {
        val running = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}"""),
            ),
        )

        val unaddressed = BridgeEventReducer.reduce(
            running,
            SseFrame("2", "task-complete", """{"source":"claude"}"""),
            2_000_000L,
        ) as BridgeEventReducer.Applied
        assertEquals(running.sessions, unaddressed.state.sessions)

        val unknown = BridgeEventReducer.reduce(
            running,
            SseFrame("3", "task-complete", """{"sessionId":"nope","source":"claude"}"""),
            2_000_000L,
        ) as BridgeEventReducer.Applied
        assertEquals(running.sessions, unknown.state.sessions)
    }

    @Test
    fun replayedRunningEventDoesNotResetTheElapsedClock() {
        val first = SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}""")
        val state = fold(listOf(first))

        // Reconnect: the bridge's connect-time sync re-sends the running
        // session. The clock must keep its original base.
        val resent = SseFrame("2", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}""")
        val after = (BridgeEventReducer.reduce(state, resent, 5_000_000L) as BridgeEventReducer.Applied).state
        assertEquals(1_001_000L, after.sessions.getValue("A").activeSinceMs)
    }

    @Test
    fun syncResentRunningDoesNotWakeAnIdleSessionOrDropItsFrozenSpan() {
        // running@t1 -> task-complete@t6 freezes a 5s span. The bridge's
        // connect-time sync then re-sends `running` for the still-live slot
        // on EVERY /v1/events connect (the ViewModel reconnects every few
        // seconds after a drop), long after the turn ended.
        val state = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}"""),
                SseFrame("6", "task-complete", """{"sessionId":"A","source":"claude"}"""),
            ),
        )
        assertEquals(5_000L, state.sessions.getValue("A").frozenElapsedMs)

        // Sync frames carry no id; reconnect happens much later (t100).
        val syncResent = SseFrame(null, "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}""")
        val after = (BridgeEventReducer.reduce(state, syncResent, 1_100_000L) as BridgeEventReducer.Applied).state

        // Metadata refresh only: still idle, frozen span intact, no new clock.
        val a = after.sessions.getValue("A")
        assertEquals(SessionActivity.IDLE, a.activity)
        assertEquals(5_000L, a.frozenElapsedMs)
        assertNull(a.activeSinceMs)
        assertEquals(5_000L, a.elapsedMs(2_000_000L))
    }

    @Test
    fun titleUpdatesFromResentRunningAndSurvivesTitlelessResends() {
        val state = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","title":"First title","sessionId":"A"}"""),
            ),
        )
        assertEquals("First title", state.sessions.getValue("A").title)

        // The bridge broadcasts a title change as an idempotent re-sent
        // `running` event: the new title replaces the old one.
        val retitled = SseFrame("2", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","title":"Better title","sessionId":"A"}""")
        val afterRetitle = (BridgeEventReducer.reduce(state, retitled, 1_002_000L) as BridgeEventReducer.Applied).state
        assertEquals("Better title", afterRetitle.sessions.getValue("A").title)

        // A connect-time sync resend without a title (or from an older
        // bridge) must not erase the known title.
        val titleless = SseFrame(null, "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}""")
        val afterSync = (BridgeEventReducer.reduce(afterRetitle, titleless, 1_003_000L) as BridgeEventReducer.Applied).state
        assertEquals("Better title", afterSync.sessions.getValue("A").title)
    }

    @Test
    fun externalFlagIsParsedAndSurvivesExternallessResends() {
        // A hook-created (external) slot carries external:true on its `running`.
        val state = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","external":true,"sessionId":"A"}"""),
            ),
        )
        assertTrue("external:true must parse onto the session", state.sessions.getValue("A").external)

        // A connect-time sync resend WITHOUT the flag (PTY slot shape / older
        // bridge) must not erase a known external flag — same preserve-on-
        // resend rule as folderName/title.
        val externalless = SseFrame(null, "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}""")
        val afterSync = (BridgeEventReducer.reduce(state, externalless, 1_002_000L) as BridgeEventReducer.Applied).state
        assertTrue("an externalless resend must not clear the external flag", afterSync.sessions.getValue("A").external)

        // A brand-new PTY session with no external field defaults to false.
        val pty = fold(
            listOf(
                SseFrame("3", "session", """{"state":"running","agent":"claude","cwd":"/b","folderName":"b","sessionId":"B"}"""),
            ),
        )
        assertFalse("a PTY slot defaults to external=false (killable)", pty.sessions.getValue("B").external)
    }

    @Test
    fun resentRunningStillRefreshesSessionMetadata() {
        val state = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","sessionId":"A"}"""),
                SseFrame("2", "task-complete", """{"sessionId":"A"}"""),
            ),
        )
        // A later `running` for the known session fills in metadata the first
        // frame lacked, without touching activity/elapsed state.
        val resent = SseFrame(null, "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}""")
        val after = (BridgeEventReducer.reduce(state, resent, 1_100_000L) as BridgeEventReducer.Applied).state
        val a = after.sessions.getValue("A")
        assertEquals("claude", a.agent)
        assertEquals("/a", a.cwd)
        assertEquals("a", a.folderName)
        assertEquals(SessionActivity.IDLE, a.activity)
    }

    @Test
    fun outputAfterIdleStartsAFreshElapsedSpan() {
        val frames = listOf(
            SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}"""),
            SseFrame("2", "task-complete", """{"sessionId":"A"}"""),
            SseFrame("3", "pty-output", """{"text":"$ next turn\r\n","sessionId":"A"}"""),
        )
        val state = fold(frames)
        val a = state.sessions.getValue("A")
        assertEquals(SessionActivity.WORKING, a.activity)
        assertEquals(1_003_000L, a.activeSinceMs) // fresh span from frame 3
    }

    // ------------------------------------------------------------------
    // lastEventId advances only after successful parse+apply
    // ------------------------------------------------------------------

    @Test
    fun lastEventIdAdvancesOnlyOnParseAndApply() {
        val valid = SseFrame("7", "pty-output", """{"text":"hello","sessionId":"s-1"}""")
        val applied = BridgeEventReducer.reduce(BridgeState(), valid, 1_000L)
        assertTrue(applied is BridgeEventReducer.Applied)
        assertEquals("7", applied.state.lastEventId)

        // Contract violation: the whole frame is rejected, state (including
        // lastEventId) is untouched, so a reconnect replays it.
        val malformed = SseFrame("8", "permission-request", """{"tool_name":"Bash"}""")
        val rejected = BridgeEventReducer.reduce(applied.state, malformed, 2_000L)
        assertTrue(rejected is BridgeEventReducer.Rejected)
        assertEquals(applied.state, rejected.state)
        assertEquals("7", rejected.state.lastEventId)

        val notJson = SseFrame("9", "session", "garbage{{{")
        val alsoRejected = BridgeEventReducer.reduce(rejected.state, notJson, 3_000L)
        assertTrue(alsoRejected is BridgeEventReducer.Rejected)
        assertEquals("7", alsoRejected.state.lastEventId)

        // A frame without an id applies but keeps the previous committed id
        // (connect-time sync frames must not regress the replay cursor).
        val noId = SseFrame(null, "pty-output", """{"text":"more","sessionId":"s-1"}""")
        val appliedNoId = BridgeEventReducer.reduce(alsoRejected.state, noId, 4_000L) as BridgeEventReducer.Applied
        assertEquals("7", appliedNoId.state.lastEventId)

        // Unknown event types are tolerated and DO advance the cursor: an
        // older client must not replay-loop on a newer bridge's new events.
        val unknown = SseFrame("10", "shiny-new-event", """{"anything":true}""")
        val appliedUnknown = BridgeEventReducer.reduce(appliedNoId.state, unknown, 5_000L) as BridgeEventReducer.Applied
        assertEquals("10", appliedUnknown.state.lastEventId)
    }
}
