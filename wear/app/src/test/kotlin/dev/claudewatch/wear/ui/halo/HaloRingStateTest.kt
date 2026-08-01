package dev.claudewatch.wear.ui.halo

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import dev.claudewatch.wear.ui.halo.Halo.SessionState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.resume

/**
 * Pins the S4 ring engine (epic #94) on the JVM: the whole animation timeline
 * runs under a hand-rolled [MonotonicFrameClock], so every assertion is
 * against an exact frame time — no sleeps, no flake. The contracts pinned
 * here are the ones a renderer regression would silently bend: interruptions
 * continue from current values (never snap), equal inputs launch nothing, the
 * 220ms colour-first geometry hold, ambient's cancel-everything snap, and the
 * empty-scope styles (usage collapse vs the 0-session idle circle).
 */
class HaloRingStateTest {

    private val m = HaloRingMath

    /** Frames on demand: [frame] advances the clock and resumes every waiter. */
    private class ManualFrameClock : MonotonicFrameClock {
        var nowMs = 0L
            private set
        private val awaiters = mutableListOf<CancellableContinuation<Long>>()

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            val nanos = suspendCancellableCoroutine<Long> { awaiters += it }
            return onFrame(nanos)
        }

        fun frame(advanceByMs: Long) {
            nowMs += advanceByMs
            val ready = awaiters.toList()
            awaiters.clear()
            // Resuming a cancelled continuation is a no-op, so waiters whose
            // animation was cancelled between frames are simply skipped.
            ready.forEach { it.resume(nowMs * 1_000_000L) }
        }
    }

    private fun engineTest(
        body: suspend TestScope.(clock: ManualFrameClock, scope: CoroutineScope, engine: HaloRingState) -> Unit,
    ) = runTest {
        val clock = ManualFrameClock()
        // The engine's animation scope: the test dispatcher (so runCurrent
        // drives it) plus the manual clock, detached from the test Job so
        // runTest never waits on a deliberately-unfinished tween.
        val scope = CoroutineScope(coroutineContext + Job() + clock)
        try {
            body(clock, scope, HaloRingState())
        } finally {
            scope.cancel()
        }
    }

    /** Advance one frame and run everything it resumed. */
    private fun TestScope.pump(clock: ManualFrameClock, advanceByMs: Long) {
        clock.frame(advanceByMs)
        runCurrent()
    }

    /** Launch the pending animateTo calls and latch their start frame. */
    private fun TestScope.start(clock: ManualFrameClock) {
        runCurrent()
        pump(clock, 0)
    }

    // ── Retarget continues from current values ──────────────────────────────

    @Test
    fun retargetContinuesFromCurrentValuesNeverSnaps() = engineTest { clock, scope, engine ->
        val two = listOf(SessionState.RUNNING, SessionState.IDLE)
        engine.snapTo(two, collapsed = false, nowMs = clock.nowMs)
        engine.retarget(scope, two + SessionState.RUNNING, collapsed = false, nowMs = clock.nowMs)
        start(clock)
        pump(clock, 275)

        // Mid-flight between the n=2 pose (−274) and the n=3 target (−214).
        val slot = engine.slots[1]
        val midEnd = slot.end.value
        assertTrue("expected mid-flight, got $midEnd", midEnd > -274f && midEnd < -214f)

        // Interrupt: back to two sessions. The value must hold through the
        // handover — cancel+animateTo retargets from the CURRENT value.
        engine.retarget(scope, two, collapsed = false, nowMs = clock.nowMs)
        runCurrent()
        assertEquals(midEnd, slot.end.value, 0f)
        pump(clock, 0)
        assertEquals(midEnd, slot.end.value, 0f)

        // The kill is a move+recolour, so geometry waits out the 220ms hold,
        // then walks DOWN from the interrupted value toward −274 — no snap to
        // either endpoint on the way.
        pump(clock, 275)
        val later = slot.end.value
        assertTrue("expected motion back toward −274, got $later", later < midEnd && later > -274f)
        pump(clock, 600)
        assertEquals(m.endAngle(1, 2), slot.end.value, 1e-3f)
    }

    // ── Equal inputs restart nothing ────────────────────────────────────────

    @Test
    fun equalInputsLaunchNothingWhenSettled() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.WAITING_PERM)
        engine.snapTo(states, collapsed = false, nowMs = clock.nowMs)

        // A fresh-but-equal list instance: what a feed line's recomposition
        // rebuilds. Nothing may start, and every target stays bit-exact.
        engine.retarget(scope, ArrayList(states), collapsed = false, nowMs = clock.nowMs)
        runCurrent()
        engine.slots.forEachIndexed { k, slot ->
            assertFalse(slot.end.isRunning)
            assertFalse(slot.sweep.isRunning)
            assertFalse(slot.color.isRunning)
            assertFalse(slot.alpha.isRunning)
            assertEquals(m.endAngle(k, 3), slot.end.value, 0f)
            assertEquals(m.sweepDegrees(3), slot.sweep.value, 0f)
        }
        assertFalse(engine.idleAlpha.isRunning)
    }

    @Test
    fun equalInputsMidFlightLeaveTheTweenOnItsOriginalSchedule() = engineTest { clock, scope, engine ->
        val two = listOf(SessionState.RUNNING, SessionState.IDLE)
        engine.snapTo(two, collapsed = false, nowMs = clock.nowMs)
        val three = two + SessionState.RUNNING
        engine.retarget(scope, three, collapsed = false, nowMs = clock.nowMs)
        start(clock)
        pump(clock, 275)

        // An equal update mid-flight (the clock-tick shape): the in-flight
        // tween must not be restarted OR retimed — it completes exactly 550ms
        // after its original start.
        engine.retarget(scope, ArrayList(three), collapsed = false, nowMs = clock.nowMs)
        runCurrent()
        pump(clock, 275)
        assertEquals(m.endAngle(1, 3), engine.slots[1].end.value, 1e-3f)
        pump(clock, 16)
        assertFalse(engine.slots[1].end.isRunning)
    }

    // ── The colour-first geometry hold ──────────────────────────────────────

    @Test
    fun geometryHoldsTwoHundredTwentyMsWhenOneUpdateMovesAndRecolours() = engineTest { clock, scope, engine ->
        val three = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.RUNNING)
        engine.snapTo(three, collapsed = false, nowMs = clock.nowMs)

        // Kill the last session: the collapse recolours (→black) AND moves
        // (collapse + re-slice) — the delay window opens.
        engine.retarget(scope, three.take(2), collapsed = false, nowMs = clock.nowMs)
        start(clock)
        val dying = engine.slots[2]
        val survivor = engine.slots[1]

        pump(clock, 219)
        // Inside the hold: geometry is EXACTLY at its start...
        assertEquals(m.sweepDegrees(3), dying.sweep.value, 0f)
        assertEquals(m.endAngle(2, 3), dying.end.value, 0f)
        assertEquals(m.endAngle(1, 3), survivor.end.value, 0f)
        // ...while paint is already moving: the recolour reads first.
        assertTrue(dying.color.value != Halo.colorFor(SessionState.RUNNING))
        assertTrue(dying.alpha.value < 1f)

        pump(clock, 200)
        // Past the hold: geometry is under way.
        assertTrue(dying.sweep.value < m.sweepDegrees(3))
        assertTrue(survivor.end.value < m.endAngle(1, 3))

        pump(clock, 600)
        assertEquals(0f, dying.sweep.value, 0f)
        assertEquals(m.endAngle(2, 3) - m.sweepDegrees(3), dying.end.value, 1e-3f)
        assertEquals(Halo.Palette.Background, dying.color.value)
        assertEquals(0f, dying.alpha.value, 0f)
        assertEquals(m.endAngle(1, 2), survivor.end.value, 1e-3f)
    }

    @Test
    fun bareArrivalOpensNoHoldAndSnapsInPreColoured() = engineTest { clock, scope, engine ->
        val two = listOf(SessionState.RUNNING, SessionState.IDLE)
        engine.snapTo(two, collapsed = false, nowMs = clock.nowMs)
        val arrivedAt = clock.nowMs
        engine.retarget(scope, two + SessionState.WAITING_PERM, collapsed = false, nowMs = arrivedAt)
        start(clock)

        // The newcomer is at its final geometry and colour from frame zero —
        // only its alpha animates.
        val fresh = engine.slots[2]
        assertEquals(m.endAngle(2, 3), fresh.end.value, 1e-4f)
        assertEquals(m.sweepDegrees(3), fresh.sweep.value, 0f)
        assertEquals(Halo.colorFor(SessionState.WAITING_PERM), fresh.color.value)

        pump(clock, 100)
        // A pre-coloured arrival must NOT open the colour-first hold: the
        // survivors' re-slice geometry is moving well before 220ms.
        assertTrue(engine.slots[1].end.value > m.endAngle(1, 2) + 0.5f)
        assertTrue(fresh.alpha.value > 0f && fresh.alpha.value < 1f)

        // And it paints BENEATH the settled ring until its 1300ms expires.
        assertEquals(listOf(2, 0, 1), engine.drawOrder(arrivedAt + 100))
        assertEquals(listOf(0, 1, 2), engine.drawOrder(arrivedAt + 1400))

        pump(clock, 300)
        assertEquals(1f, fresh.alpha.value, 0f)
    }

    // ── Ambient snap ────────────────────────────────────────────────────────

    @Test
    fun ambientSnapCancelsEveryJobAndLandsOnTargets() = engineTest { clock, scope, engine ->
        val three = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.RUNNING)
        engine.snapTo(three, collapsed = false, nowMs = clock.nowMs)
        val two = three.take(2)
        engine.retarget(scope, two, collapsed = false, nowMs = clock.nowMs)
        start(clock)
        // 100ms in: paint is mid-blend and geometry still inside its 220ms
        // hold — the held tween is exactly the job only an explicit cancel
        // can reach.
        pump(clock, 100)

        engine.snapTo(two, collapsed = false, nowMs = clock.nowMs)
        val dying = engine.slots[2]
        assertEquals(0f, dying.sweep.value, 0f)
        assertEquals(m.endAngle(2, 3) - m.sweepDegrees(3), dying.end.value, 0f)
        assertEquals(Halo.Palette.Background, dying.color.value)
        assertEquals(0f, dying.alpha.value, 0f)
        assertEquals(m.endAngle(1, 2), engine.slots[1].end.value, 0f)
        engine.slots.forEach { slot ->
            assertFalse(slot.end.isRunning)
            assertFalse(slot.sweep.isRunning)
            assertFalse(slot.color.isRunning)
            assertFalse(slot.alpha.isRunning)
        }

        // The cancelled hold must never wake: frames later, nothing moves.
        pump(clock, 1000)
        assertEquals(0f, dying.sweep.value, 0f)
        assertEquals(m.endAngle(1, 2), engine.slots[1].end.value, 0f)
    }

    // ── Empty-scope styles ──────────────────────────────────────────────────

    @Test
    fun usageSwipeCollapsesTheRingAndReturnFadesItBackPreColoured() = engineTest { clock, scope, engine ->
        val two = listOf(SessionState.RUNNING, SessionState.WAITING_PERM)
        engine.snapTo(two, collapsed = false, nowMs = clock.nowMs)

        // Swipe to usage: every arc collapses onto its own start; the idle
        // circle stays out (COLLAPSED style, not a 0-session home).
        engine.retarget(scope, emptyList(), collapsed = true, nowMs = clock.nowMs)
        start(clock)
        pump(clock, 2000)
        engine.slots.forEachIndexed { k, slot ->
            assertEquals(0f, slot.sweep.value, 0f)
            assertEquals(m.endAngle(k, 2) - m.sweepDegrees(2), slot.end.value, 1e-3f)
            assertEquals(0f, slot.alpha.value, 0f)
        }
        assertEquals(0f, engine.idleAlpha.value, 0f)

        // Return: the arcs are REBORN — snapped to final geometry, final
        // colour, fading in — never growing back out of their death spots.
        engine.retarget(scope, two, collapsed = false, nowMs = clock.nowMs)
        start(clock)
        engine.slots.forEachIndexed { k, slot ->
            assertEquals(m.endAngle(k, 2), slot.end.value, 1e-3f)
            assertEquals(m.sweepDegrees(2), slot.sweep.value, 0f)
            assertEquals(Halo.colorFor(two[k]), slot.color.value)
        }
        pump(clock, 100)
        assertTrue(engine.slots[0].alpha.value > 0f && engine.slots[0].alpha.value < 1f)
        pump(clock, 300)
        engine.slots.forEach { assertEquals(1f, it.alpha.value, 0f) }
    }

    @Test
    fun zeroSessionHomeFadesTheIdleCircleAgainstTheArcs() = engineTest { clock, scope, engine ->
        engine.snapTo(emptyList(), collapsed = false, nowMs = clock.nowMs)
        assertEquals(1f, engine.idleAlpha.value, 0f)

        // First session: the idle circle fades out as the arc fades in.
        engine.retarget(scope, listOf(SessionState.RUNNING), collapsed = false, nowMs = clock.nowMs)
        start(clock)
        pump(clock, 150)
        assertTrue(engine.idleAlpha.value > 0f && engine.idleAlpha.value < 1f)
        assertTrue(engine.slots[0].alpha.value > 0f && engine.slots[0].alpha.value < 1f)
        pump(clock, 300)
        assertEquals(0f, engine.idleAlpha.value, 0f)
        assertEquals(1f, engine.slots[0].alpha.value, 0f)

        // Last session dies at home: the arcs collapse and the circle returns.
        engine.retarget(scope, emptyList(), collapsed = false, nowMs = clock.nowMs)
        start(clock)
        pump(clock, 2000)
        assertEquals(1f, engine.idleAlpha.value, 0f)
        assertEquals(0f, engine.slots[0].sweep.value, 0f)
    }

    // ── The trigger contract ────────────────────────────────────────────────

    @Test
    fun snapshotFlowNeverReEmitsForValueEqualOrUnrelatedWrites() = runTest {
        // The host's trigger in miniature: a snapshotFlow over a
        // value-comparable inputs snapshot. This is the upstream half of the
        // no-restart contract — clock ticks and feed lines cannot even REACH
        // the engine, because their recompositions rebuild an EQUAL snapshot.
        var states by mutableStateOf(listOf(SessionState.RUNNING, SessionState.IDLE))
        var feedLines by mutableStateOf(0)
        val emissions = mutableListOf<List<SessionState>>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            snapshotFlow { states }.collect { emissions += it }
        }
        runCurrent()
        assertEquals(1, emissions.size)

        // A feed line's recomposition rebuilds an equal-but-new list.
        states = ArrayList(states)
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(1, emissions.size)

        // A clock tick writes snapshot state the trigger never read.
        feedLines += 1
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(1, emissions.size)

        // A real change still gets through.
        states = listOf(SessionState.RUNNING, SessionState.WAITING_PERM)
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(2, emissions.size)
        assertEquals(listOf(SessionState.RUNNING, SessionState.WAITING_PERM), emissions.last())

        collector.cancel()
    }
}
