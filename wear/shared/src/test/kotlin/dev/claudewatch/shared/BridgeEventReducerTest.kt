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
    fun gitMetadataIsParsedAndSurvivesMetadatalessResends() {
        // Issue #54: a worktree session's `running` carries branch/worktree/
        // repoRoot; all three land on the session state.
        val state = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/w/alpha-fix","folderName":"alpha-fix","branch":"issue-53-fix","worktree":true,"repoRoot":"/home/dev/alpha","sessionId":"A"}"""),
            ),
        )
        val a = state.sessions.getValue("A")
        assertEquals("issue-53-fix", a.branch)
        assertTrue("worktree:true must parse onto the session", a.worktree)
        assertEquals("/home/dev/alpha", a.repoRoot)

        // A resend WITHOUT the fields (older bridge, partial payload) must
        // not erase any of them — same preserve-on-absence rule as title.
        val bare = SseFrame(null, "session", """{"state":"running","agent":"claude","sessionId":"A"}""")
        val afterSync = (BridgeEventReducer.reduce(state, bare, 1_002_000L) as BridgeEventReducer.Applied).state
        val synced = afterSync.sessions.getValue("A")
        assertEquals("issue-53-fix", synced.branch)
        assertTrue("a worktree-less resend must not clear the worktree flag", synced.worktree)
        assertEquals("/home/dev/alpha", synced.repoRoot)

        // A branch switch is broadcast as an idempotent re-sent `running`
        // with the new value: present replaces.
        val switched = SseFrame("2", "session", """{"state":"running","branch":"main","sessionId":"A"}""")
        val afterSwitch = (BridgeEventReducer.reduce(afterSync, switched, 1_003_000L) as BridgeEventReducer.Applied).state
        assertEquals("main", afterSwitch.sessions.getValue("A").branch)

        // A brand-new session with none of the fields (non-git root) defaults
        // to no metadata.
        val nonGit = fold(
            listOf(
                SseFrame("3", "session", """{"state":"running","agent":"claude","cwd":"/b","folderName":"b","sessionId":"B"}"""),
            ),
        )
        val b = nonGit.sessions.getValue("B")
        assertNull(b.branch)
        assertFalse(b.worktree)
        assertNull(b.repoRoot)
    }

    @Test
    fun branchBearingPayloadWithoutWorktreeDropsTheWorktreeClaim() {
        // Issue #54 follow-up: git metadata is ONE atomic group keyed on
        // branch presence. The bridge's rebind from a linked worktree to the
        // plain main checkout (issue #52 ancestor rebind) re-derives metadata
        // and broadcasts `running` with branch present but worktree/repoRoot
        // OMITTED (the wire carries them only when truthy) — the reducer must
        // read that as the drop, not preserve the stale worktree claim.
        val state = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/home/dev/alpha/wt/fix","folderName":"fix","branch":"issue-53-fix","worktree":true,"repoRoot":"/home/dev/alpha","sessionId":"A"}"""),
            ),
        )
        val rebound = SseFrame("2", "session", """{"state":"running","branch":"main","sessionId":"A"}""")
        val afterRebind = (BridgeEventReducer.reduce(state, rebound, 1_002_000L) as BridgeEventReducer.Applied).state
        val a = afterRebind.sessions.getValue("A")
        assertEquals("main", a.branch)
        assertFalse("a branch-bearing payload without worktree must drop the worktree claim", a.worktree)
        assertNull("a branch-bearing payload without repoRoot must drop the stale repoRoot", a.repoRoot)
    }

    @Test
    fun agentsActivityPreservesOnAbsenceAndClearsOnlyByExplicitZero() {
        // Issue #55: workflow activity lands on the session state.
        val state = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","agents":{"running":3,"done":1},"sessionId":"A"}"""),
            ),
        )
        assertEquals(3, state.sessions.getValue("A").agents?.running)
        assertEquals(1, state.sessions.getValue("A").agents?.done)

        // An agents-less resend (connect-time sync, title refresh) must NOT
        // clear it — absence preserves, so omission cannot end a workflow.
        val agentless = SseFrame(null, "session", """{"state":"running","agent":"claude","sessionId":"A"}""")
        val afterSync = (BridgeEventReducer.reduce(state, agentless, 1_002_000L) as BridgeEventReducer.Applied).state
        assertEquals(3, afterSync.sessions.getValue("A").agents?.running)

        // The bridge's ONLY clear path: an explicit present-but-zero
        // {running:0, done:N} REPLACES the previous {running:3, done:1}.
        val cleared = SseFrame("2", "session", """{"state":"running","agents":{"running":0,"done":4},"sessionId":"A"}""")
        val afterClear = (BridgeEventReducer.reduce(afterSync, cleared, 1_003_000L) as BridgeEventReducer.Applied).state
        assertEquals(0, afterClear.sessions.getValue("A").agents?.running)
        assertEquals(4, afterClear.sessions.getValue("A").agents?.done)

        // No workflow ever observed: stays null.
        val quiet = fold(
            listOf(
                SseFrame("3", "session", """{"state":"running","agent":"claude","cwd":"/b","folderName":"b","sessionId":"B"}"""),
            ),
        )
        assertNull(quiet.sessions.getValue("B").agents)
    }

    // ------------------------------------------------------------------
    // Issue #60: the turn-end `idle` flag, consumed at FIRST SIGHT only
    // ------------------------------------------------------------------

    @Test
    fun aNewlySeenSessionCarryingIdleStartsIdleNotWorking() {
        // The live bug: a session whose last hook was a `Stop` HOURS before
        // this client existed. Its `stop` is long gone from the SSE replay
        // ring, so the connect-time snapshot's flag is the only truth on
        // offer — and before #60 we ignored it and rendered green.
        // A connect-time sync frame carries no id, so it is reduced directly
        // rather than through the id-keyed `fold` clock.
        val snapshot = SseFrame(
            null,
            "session",
            """{"state":"running","agent":"claude","cwd":"/home/dev/claypot","folderName":"claypot","external":true,"idle":true,"sessionId":"A"}""",
        )
        val state = (BridgeEventReducer.reduce(BridgeState(), snapshot, 1_001_000L) as BridgeEventReducer.Applied).state

        val a = state.sessions.getValue("A")
        assertEquals(SessionActivity.IDLE, a.activity)
        // No running span, and nothing frozen either: this client never saw
        // the span that ended, so it has no honest duration to show.
        assertNull(a.activeSinceMs)
        assertNull(a.frozenElapsedMs)
        assertNull("an idle first sight must show no elapsed clock at all", a.elapsedMs(9_999_999L))
        // Metadata still lands as usual.
        assertEquals("claypot", a.folderName)
        assertTrue(a.external)
    }

    @Test
    fun aNewlySeenSessionWithoutTheIdleFlagStaysWorking() {
        // Absence means "working, or an OLDER bridge" — never "idle". Our
        // bridge emits the flag on every session event now, so defaulting
        // absent to IDLE would paint every genuinely-live session on an older
        // bridge grey on every reconnect: the same bug in the other colour.
        val snapshot = SseFrame(null, "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}""")
        val state = (BridgeEventReducer.reduce(BridgeState(), snapshot, 1_001_000L) as BridgeEventReducer.Applied).state
        val a = state.sessions.getValue("A")
        assertEquals(SessionActivity.WORKING, a.activity)
        assertEquals(1_001_000L, a.activeSinceMs)
    }

    @Test
    fun aReconnectSnapshotCarryingIdleIdlesAKnownSessionAndFreezesItsSpan() {
        // The OTHER half of the live bug, and the half the issue title actually
        // names ("on reconnect"). The watch keeps its BridgeState across SSE
        // drops, so a session it already tracks is not covered by first-sight
        // handling at all: if the turn ended during the drop and that `stop`
        // then aged out of the replay ring, nothing else will ever correct the
        // WORKING it is still holding. Green, indefinitely — issue #60 verbatim.
        val state = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}"""),
                SseFrame("2", "pty-output", """{"text":"working...\r\n","sessionId":"A"}"""),
            ),
        )
        val before = state.sessions.getValue("A")
        assertEquals(SessionActivity.WORKING, before.activity)
        // The span opened at first sight (frame 1); output on an already-working
        // session does not restart it.
        assertEquals(1_001_000L, before.activeSinceMs)

        // Reconnect: the connect-time sync re-sends the slot, now flagged idle.
        val resent = SseFrame(
            null,
            "session",
            """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","idle":true,"sessionId":"A"}""",
        )
        val after = (BridgeEventReducer.reduce(state, resent, 1_005_000L) as BridgeEventReducer.Applied).state
        val a = after.sessions.getValue("A")
        assertEquals("a flagged resend must idle a known session too", SessionActivity.IDLE, a.activity)
        // Frozen, NOT restarted: the span that really ran is what we show.
        assertNull(a.activeSinceMs)
        assertEquals(4_000L, a.frozenElapsedMs) // t(resend) - activeSince

        // Idempotent: the NEXT reconnect re-sends the same flag, and must not
        // re-freeze the span from a later `now` (that would inflate a duration
        // by however long the watch has been reconnecting).
        val again = (BridgeEventReducer.reduce(after, resent, 9_000_000L) as BridgeEventReducer.Applied).state
        assertEquals(4_000L, again.sessions.getValue("A").frozenElapsedMs)
    }

    @Test
    fun aFlaglessResendNeverWakesOrRestartsAKnownSession() {
        // The direction that must stay inert, which is what keeps the latch
        // above from becoming its own bug: the bridge re-sends `running` for
        // every live slot on EVERY connect, so if ABSENCE were treated as
        // "working" a routine reconnect would restart the elapsed clock of a
        // session that has been sitting idle for hours — and would do it again
        // on every reconnect after that.
        val idle = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","sessionId":"A"}"""),
                SseFrame("2", "pty-output", """{"text":"working...\r\n","sessionId":"A"}"""),
                SseFrame("3", "stop", """{"sessionId":"A"}"""),
            ),
        )
        val before = idle.sessions.getValue("A")
        assertEquals(SessionActivity.IDLE, before.activity)
        assertEquals(2_000L, before.frozenElapsedMs) // t(3) - the span opened at t(1)

        val flagless = SseFrame(null, "session", """{"state":"running","agent":"claude","folderName":"a","sessionId":"A"}""")
        val after = (BridgeEventReducer.reduce(idle, flagless, 6_000_000L) as BridgeEventReducer.Applied).state
        val a = after.sessions.getValue("A")
        assertEquals("absence must never wake a session", SessionActivity.IDLE, a.activity)
        assertNull(a.activeSinceMs)
        assertEquals("the frozen span must survive a reconnect", 2_000L, a.frozenElapsedMs)

        // A WORKING session is equally untouched by a flagless resend: no
        // fresh span, no restarted clock.
        val working = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/b","folderName":"b","sessionId":"B"}"""),
                SseFrame("2", "pty-output", """{"text":"busy\r\n","sessionId":"B"}"""),
            ),
        )
        val resent = SseFrame(null, "session", """{"state":"running","agent":"claude","folderName":"b","sessionId":"B"}""")
        val stillWorking = (BridgeEventReducer.reduce(working, resent, 7_000_000L) as BridgeEventReducer.Applied).state
        val b = stillWorking.sessions.getValue("B")
        assertEquals(SessionActivity.WORKING, b.activity)
        assertEquals("the elapsed clock must not restart", 1_001_000L, b.activeSinceMs)
    }

    @Test
    fun liveStopAndOutputStillOverrideAFirstSightIdle() {
        // A session that first appears idle is not stuck there: the ordinary
        // markWorking path takes over the moment it produces anything.
        val state = fold(
            listOf(
                SseFrame("1", "session", """{"state":"running","agent":"claude","cwd":"/a","folderName":"a","idle":true,"sessionId":"A"}"""),
                SseFrame("2", "pty-output", """{"text":"new turn\r\n","sessionId":"A"}"""),
            ),
        )
        val working = state.sessions.getValue("A")
        assertEquals(SessionActivity.WORKING, working.activity)
        assertEquals(1_002_000L, working.activeSinceMs) // fresh span from the output

        // ...and back to idle on the next turn end, freezing that real span.
        val after = fold(listOf(SseFrame("3", "stop", """{"sessionId":"A"}""")), state)
        val a = after.sessions.getValue("A")
        assertEquals(SessionActivity.IDLE, a.activity)
        assertEquals(1_000L, a.frozenElapsedMs) // t3 - t2
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
