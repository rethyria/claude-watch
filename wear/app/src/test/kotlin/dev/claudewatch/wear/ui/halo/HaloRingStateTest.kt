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
import kotlin.math.abs

/**
 * Pins the S4 ring engine and its S7 morphs (epic #94) on the JVM: the whole
 * animation timeline runs under a hand-rolled [MonotonicFrameClock], so every
 * assertion is against an exact frame time — no sleeps, no flake. The
 * contracts pinned here are the ones a renderer regression would silently
 * bend: interruptions continue from current values (never snap), equal inputs
 * launch nothing, the 220ms colour-first geometry hold, ambient's
 * cancel-everything snap, the empty-scope styles, the dash split/merge with
 * its atomic close-swap, the hero's rotation/retrace and grow/shrink, and
 * the non-adjacent level snap.
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

    // ── Inputs builders (the RingInputs shapes ringInputs() derives) ────────

    private fun page(states: List<SessionState>, collapsed: Boolean = false) = RingInputs(
        level = RingLevel.PAGE,
        states = states,
        emptyStyle = if (collapsed) EmptyRingStyle.COLLAPSED else EmptyRingStyle.IDLE_CIRCLE,
        selectedIndex = -1,
        stepDir = StepDir.NONE,
        feedState = null,
    )

    private fun plist(states: List<SessionState>, selected: Int, stepDir: StepDir = StepDir.NONE) =
        RingInputs(
            level = RingLevel.PLIST,
            states = states,
            emptyStyle = EmptyRingStyle.IDLE_CIRCLE,
            selectedIndex = selected,
            stepDir = stepDir,
            feedState = null,
        )

    private fun feed(states: List<SessionState>, selected: Int) = RingInputs(
        level = RingLevel.FEED,
        states = states,
        emptyStyle = EmptyRingStyle.IDLE_CIRCLE,
        selectedIndex = selected,
        stepDir = StepDir.NONE,
        feedState = states.getOrNull(selected),
    )

    // ── Retarget continues from current values ──────────────────────────────

    @Test
    fun retargetContinuesFromCurrentValuesNeverSnaps() = engineTest { clock, scope, engine ->
        val two = listOf(SessionState.RUNNING, SessionState.IDLE)
        engine.snapTo(page(two))
        engine.retarget(scope, page(two + SessionState.RUNNING), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 275)

        // Mid-flight between the n=2 pose (−274) and the n=3 target (−214).
        val slot = engine.slots[1]
        val midEnd = slot.end.value
        assertTrue("expected mid-flight, got $midEnd", midEnd > -274f && midEnd < -214f)

        // Interrupt: back to two sessions. The value must hold through the
        // handover — cancel+animateTo retargets from the CURRENT value.
        engine.retarget(scope, page(two), nowMs = clock.nowMs)
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
        engine.snapTo(page(states))

        // A fresh-but-equal list instance: what a feed line's recomposition
        // rebuilds. Nothing may start, and every target stays bit-exact.
        engine.retarget(scope, page(ArrayList(states)), nowMs = clock.nowMs)
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
    fun equalListInputsLaunchNothingIncludingTheHeroAndLayers() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.WAITING_PERM)
        engine.snapTo(plist(states, selected = 1))

        // The S7 half of the contract: at LIST level a rebuilt-but-equal
        // snapshot may not touch the hero, the merge fraction or the dashed
        // layer either — a restart would visibly twitch the highlight.
        engine.retarget(scope, plist(ArrayList(states), selected = 1), nowMs = clock.nowMs)
        runCurrent()
        assertFalse(engine.heroEnd.isRunning)
        assertFalse(engine.heroSweep.isRunning)
        assertFalse(engine.heroStroke.isRunning)
        assertFalse(engine.heroColor.isRunning)
        assertFalse(engine.heroAlpha.isRunning)
        assertFalse(engine.merge.isRunning)
        assertFalse(engine.dashAlpha.isRunning)
        engine.slots.forEach { slot ->
            assertFalse(slot.end.isRunning)
            assertFalse(slot.alpha.isRunning)
        }
        assertEquals(m.endAngle(1, 3), engine.heroEnd.value, 0f)
    }

    @Test
    fun equalInputsMidFlightLeaveTheTweenOnItsOriginalSchedule() = engineTest { clock, scope, engine ->
        val two = listOf(SessionState.RUNNING, SessionState.IDLE)
        engine.snapTo(page(two))
        val three = two + SessionState.RUNNING
        engine.retarget(scope, page(three), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 275)

        // An equal update mid-flight (the clock-tick shape): the in-flight
        // tween must not be restarted OR retimed — it completes exactly 550ms
        // after its original start.
        engine.retarget(scope, page(ArrayList(three)), nowMs = clock.nowMs)
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
        engine.snapTo(page(three))

        // Kill the last session: the collapse recolours (→black) AND moves
        // (collapse + re-slice) — the delay window opens.
        engine.retarget(scope, page(three.take(2)), nowMs = clock.nowMs)
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
        engine.snapTo(page(two))
        val arrivedAt = clock.nowMs
        engine.retarget(scope, page(two + SessionState.WAITING_PERM), nowMs = arrivedAt)
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
        engine.snapTo(page(three))
        val two = three.take(2)
        engine.retarget(scope, page(two), nowMs = clock.nowMs)
        start(clock)
        // 100ms in: paint is mid-blend and geometry still inside its 220ms
        // hold — the held tween is exactly the job only an explicit cancel
        // can reach.
        pump(clock, 100)

        engine.snapTo(page(two))
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

    @Test
    fun ambientSnapMidDashMorphLandsOnListTargets() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.WAITING_PERM)
        engine.snapTo(page(states))
        engine.retarget(scope, plist(states, selected = 1), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 200)
        // Genuinely mid-split: merge between the endpoints, solid mid-fade.
        assertTrue(engine.merge.value > 0f && engine.merge.value < 1f)

        // Wrist down mid-morph: land settled on the LIST regime's targets —
        // never a frozen half-split frame.
        engine.snapTo(plist(states, selected = 1))
        assertEquals(0f, engine.merge.value, 0f)
        assertEquals(1f, engine.dashAlpha.value, 0f)
        assertTrue(engine.heroShown)
        assertEquals(m.endAngle(1, 3), engine.heroEnd.value, 0f)
        assertEquals(m.sweepDegrees(3), engine.heroSweep.value, 0f)
        assertEquals(Halo.Geo.RingStrokeHero, engine.heroStroke.value, 0f)
        assertEquals(1f, engine.heroAlpha.value, 0f)
        engine.slots.forEach { slot ->
            assertEquals(0f, slot.alpha.value, 0f)
            assertFalse(slot.alpha.isRunning)
        }
        assertFalse(engine.merge.isRunning)
        assertFalse(engine.heroStroke.isRunning)

        // And STAY settled: the cancelled morph never wakes.
        pump(clock, 2000)
        assertEquals(0f, engine.merge.value, 0f)
        assertEquals(m.endAngle(1, 3), engine.heroEnd.value, 0f)
    }

    // ── Empty-scope styles ──────────────────────────────────────────────────

    @Test
    fun usageSwipeCollapsesTheRingAndReturnFadesItBackPreColoured() = engineTest { clock, scope, engine ->
        val two = listOf(SessionState.RUNNING, SessionState.WAITING_PERM)
        engine.snapTo(page(two))

        // Swipe to usage: every arc collapses onto its own start; the idle
        // circle stays out (COLLAPSED style, not a 0-session home).
        engine.retarget(scope, page(emptyList(), collapsed = true), nowMs = clock.nowMs)
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
        engine.retarget(scope, page(two), nowMs = clock.nowMs)
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
        engine.snapTo(page(emptyList()))
        assertEquals(1f, engine.idleAlpha.value, 0f)

        // First session: the idle circle fades out as the arc fades in.
        engine.retarget(scope, page(listOf(SessionState.RUNNING)), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 150)
        assertTrue(engine.idleAlpha.value > 0f && engine.idleAlpha.value < 1f)
        assertTrue(engine.slots[0].alpha.value > 0f && engine.slots[0].alpha.value < 1f)
        pump(clock, 300)
        assertEquals(0f, engine.idleAlpha.value, 0f)
        assertEquals(1f, engine.slots[0].alpha.value, 0f)

        // Last session dies at home: the arcs collapse and the circle returns.
        engine.retarget(scope, page(emptyList()), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 2000)
        assertEquals(1f, engine.idleAlpha.value, 0f)
        assertEquals(0f, engine.slots[0].sweep.value, 0f)
    }

    // ── OPEN / CLOSE (dash split/merge) ─────────────────────────────────────

    @Test
    fun openSnapsTheHeroSplitsTheDashesAndFadesTheSolidUnder() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.WAITING_PERM)
        engine.snapTo(page(states))
        assertEquals(1f, engine.merge.value, 0f)
        assertEquals(0f, engine.dashAlpha.value, 0f)
        assertFalse(engine.heroShown)

        engine.retarget(scope, plist(states, selected = 1), nowMs = clock.nowMs)
        start(clock)
        // The hero SNAPS onto the selected segment (no stale rotation) at the
        // solid weight, with the dashed layer up at full merge — the frame-0
        // picture is pixel-identical to the page ring it replaces.
        assertTrue(engine.heroShown)
        assertEquals(m.endAngle(1, 3), engine.heroEnd.value, 0f)
        assertEquals(m.sweepDegrees(3), engine.heroSweep.value, 0f)
        assertEquals(Halo.colorFor(SessionState.IDLE), engine.heroColor.value)
        assertEquals(1f, engine.heroAlpha.value, 0f)
        assertEquals(Halo.Geo.RingStroke, engine.heroStroke.value, 0f)
        assertEquals(1f, engine.dashAlpha.value, 0f)

        pump(clock, 250)
        // Mid-split: the ONE merge fraction is mid-flight, the real solid
        // layer is fading under it, the hero is thickening 9→10.
        assertTrue(engine.merge.value > 0f && engine.merge.value < 1f)
        assertTrue(engine.slots[0].alpha.value > 0f && engine.slots[0].alpha.value < 1f)
        assertTrue(engine.heroStroke.value > Halo.Geo.RingStroke)

        pump(clock, 300)
        assertEquals(0f, engine.merge.value, 0f)
        assertEquals(Halo.Geo.RingStrokeHero, engine.heroStroke.value, 0f)
        engine.slots.forEach { assertEquals(0f, it.alpha.value, 0f) }
    }

    @Test
    fun closeMergesWithTheSolidHiddenAndSwapsAtomicallyAtTheSettle() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE)
        engine.snapTo(plist(states, selected = 0))
        engine.slots.forEach { assertEquals(0f, it.alpha.value, 0f) }

        engine.retarget(scope, page(states), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 500)
        // Fully merged: the dashed layer is pixel-identical to the solid ring
        // (stroke 9, alpha 1, no off-interval) — but the REAL solid layer is
        // still hidden, and stays hidden right up to the settle.
        assertEquals(1f, engine.merge.value, 0f)
        assertEquals(Halo.Geo.RingStroke, engine.heroStroke.value, 0f)
        assertEquals(1f, engine.dashAlpha.value, 0f)
        assertTrue(engine.heroShown)
        engine.slots.forEach { assertEquals(0f, it.alpha.value, 0f) }

        pump(clock, 480)
        engine.slots.forEach { assertEquals(0f, it.alpha.value, 0f) }
        assertEquals(1f, engine.dashAlpha.value, 0f)

        // One frame past the 1s settle: the swap lands in ONE frame — solid
        // up, dashes and hero gone, nothing left animating.
        pump(clock, 30)
        engine.slots.forEach { assertEquals(1f, it.alpha.value, 0f) }
        assertEquals(0f, engine.dashAlpha.value, 0f)
        assertFalse(engine.heroShown)
        engine.slots.forEach { assertFalse(it.alpha.isRunning) }
    }

    @Test
    fun rapidOpenCloseStormNeverSnapsAndTheCancelledSwapNeverFires() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE)
        engine.snapTo(page(states))
        engine.retarget(scope, plist(states, selected = 0), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 200)
        val midMerge = engine.merge.value
        assertTrue(midMerge > 0f && midMerge < 1f)

        // Close mid-open: the merge continues from its current value.
        engine.retarget(scope, page(states), nowMs = clock.nowMs)
        runCurrent()
        assertEquals(midMerge, engine.merge.value, 0f)
        pump(clock, 150)

        // Reopen mid-close: the hero is still shown — it retargets smoothly,
        // never re-snapped over a visible arc.
        val heldEnd = engine.heroEnd.value
        engine.retarget(scope, plist(states, selected = 0), nowMs = clock.nowMs)
        runCurrent()
        assertTrue(engine.heroShown)
        assertEquals(heldEnd, engine.heroEnd.value, 0f)

        pump(clock, 800)
        // Settled at the LIST regime...
        assertEquals(0f, engine.merge.value, 0f)
        assertEquals(Halo.Geo.RingStrokeHero, engine.heroStroke.value, 0f)
        assertEquals(1f, engine.dashAlpha.value, 0f)
        engine.slots.forEach { assertEquals(0f, it.alpha.value, 0f) }

        // ...and the CANCELLED close-swap must never fire late: however long
        // we wait, the solid layer stays hidden and the hero stays up.
        pump(clock, 1500)
        engine.slots.forEach { assertEquals(0f, it.alpha.value, 0f) }
        assertTrue(engine.heroShown)
        assertEquals(1f, engine.dashAlpha.value, 0f)
    }

    // ── The hero: step rotation and retrace ─────────────────────────────────

    @Test
    fun stepRotatesTheHighlightAndTwoSessionBackstepRetraces() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE)
        engine.snapTo(plist(states, selected = 0))
        val home = engine.heroEnd.value

        engine.retarget(scope, plist(states, selected = 1, stepDir = StepDir.FORWARD), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 450)
        assertEquals(home - 180f, engine.heroEnd.value, 1e-3f)

        // Stepping back hits the exact-half-turn tie: BACK forces the +180
        // retrace, landing on the accumulated ORIGIN — not a full orbit on.
        engine.retarget(scope, plist(states, selected = 0, stepDir = StepDir.BACK), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 450)
        assertEquals(home, engine.heroEnd.value, 1e-3f)
    }

    @Test
    fun stepMidRotationRetargetsFromTheAccumulatedTarget() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.WAITING_PERM)
        engine.snapTo(plist(states, selected = 0))
        val home = engine.heroEnd.value

        engine.retarget(scope, plist(states, selected = 1, stepDir = StepDir.FORWARD), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 200)
        val mid = engine.heroEnd.value
        assertTrue(mid < home && mid > home - 120f)

        // A second step mid-rotation: continues from the current VALUE toward
        // the accumulated target of BOTH steps (−240 total), no snap-back.
        engine.retarget(scope, plist(states, selected = 2, stepDir = StepDir.FORWARD), nowMs = clock.nowMs)
        runCurrent()
        assertEquals(mid, engine.heroEnd.value, 0f)
        pump(clock, 450)
        assertEquals(home - 240f, engine.heroEnd.value, 1e-3f)
    }

    @Test
    fun spawnCardSelectionFadesTheHighlightAndReturnRestoresIt() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE)
        engine.snapTo(plist(states, selected = 1))

        // Stepping onto the trailing spawn card: no slot to highlight — the
        // hero FADES (the one sanctioned hero fade: a selection change, not
        // a morph) while its geometry holds.
        engine.retarget(scope, plist(states, selected = -1, stepDir = StepDir.FORWARD), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 350)
        assertEquals(0f, engine.heroAlpha.value, 0f)
        assertTrue(engine.heroShown)
        assertEquals(m.endAngle(1, 2), engine.heroEnd.value, 0f)

        engine.retarget(scope, plist(states, selected = 1, stepDir = StepDir.BACK), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 350)
        assertEquals(1f, engine.heroAlpha.value, 0f)
    }

    // ── GROW / SHRINK ───────────────────────────────────────────────────────

    @Test
    fun growExpandsSymmetricallyAndShrinkRetracesExactly() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.RUNNING)
        engine.snapTo(plist(states, selected = 1))
        val end0 = engine.heroEnd.value
        val sweep0 = engine.heroSweep.value

        engine.retarget(scope, feed(states, selected = 1), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 700)
        // Both ends raced to the far side: end advanced by half the missing
        // sweep, the arc is the full feed circle at feed weight and alpha.
        assertEquals(end0 + (360f - sweep0) / 2f, engine.heroEnd.value, 1e-3f)
        assertEquals(360f, engine.heroSweep.value, 0f)
        assertEquals(Halo.Geo.RingStrokeFeed, engine.heroStroke.value, 0f)
        assertEquals(Halo.Geo.FeedRingAlpha, engine.heroAlpha.value, 0f)
        assertEquals(0f, engine.dashAlpha.value, 0f)

        // Shrink: the exact reverse — back onto the very same segment pose.
        engine.retarget(scope, plist(states, selected = 1), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 700)
        assertEquals(end0, engine.heroEnd.value, 1e-3f)
        assertEquals(sweep0, engine.heroSweep.value, 1e-3f)
        assertEquals(Halo.Geo.RingStrokeHero, engine.heroStroke.value, 0f)
        assertEquals(1f, engine.heroAlpha.value, 0f)
        assertEquals(1f, engine.dashAlpha.value, 0f)
    }

    @Test
    fun backMidGrowRetargetsSmoothlyFromCurrentValues() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.RUNNING)
        engine.snapTo(plist(states, selected = 1))
        engine.retarget(scope, feed(states, selected = 1), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 300)
        val midSweep = engine.heroSweep.value
        assertTrue(midSweep > m.sweepDegrees(3) && midSweep < 360f)

        // Back mid-grow: the shrink takes over from the CURRENT pose — the
        // sweep holds through the handover, then walks back down.
        engine.retarget(scope, plist(states, selected = 1), nowMs = clock.nowMs)
        runCurrent()
        assertEquals(midSweep, engine.heroSweep.value, 0f)
        pump(clock, 0)
        assertEquals(midSweep, engine.heroSweep.value, 0f)
        pump(clock, 700)
        assertEquals(m.sweepDegrees(3), engine.heroSweep.value, 1e-3f)
        // And the end resolved onto the real segment (coterminal).
        assertEquals(0f, m.shortestDelta(engine.heroEnd.value, m.endAngle(1, 3)), 0.05f)
    }

    @Test
    fun sessionKilledMidFeedFadesTheHeroForTheSelfHeal() = engineTest { clock, scope, engine ->
        engine.snapTo(feed(listOf(SessionState.RUNNING), selected = 0))
        // The open session dies and its scope empties: the shrink has no
        // segment to land on — the hero fades while the upstream self-heal
        // backs the nav out, and the idle circle keeps the face readable.
        engine.retarget(
            scope,
            RingInputs(RingLevel.PLIST, emptyList(), EmptyRingStyle.IDLE_CIRCLE, -1, StepDir.NONE, null),
            nowMs = clock.nowMs,
        )
        start(clock)
        pump(clock, 400)
        assertEquals(0f, engine.heroAlpha.value, 0f)
        assertEquals(1f, engine.dashAlpha.value, 0f)
        assertEquals(1f, engine.idleAlpha.value, 0f)
    }

    // ── Non-adjacent level jumps snap ───────────────────────────────────────

    @Test
    fun answerPillJumpAndJumpHomeSnapOutright() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.WAITING_PERM)
        engine.snapTo(page(states))

        // Answer pill: page → feed, under the opaque card — instant.
        engine.retarget(scope, feed(states, selected = 1), nowMs = clock.nowMs)
        runCurrent()
        assertTrue(engine.heroShown)
        assertEquals(360f, engine.heroSweep.value, 0f)
        assertEquals(Halo.Geo.RingStrokeFeed, engine.heroStroke.value, 0f)
        assertEquals(Halo.Geo.FeedRingAlpha, engine.heroAlpha.value, 0f)
        assertEquals(Halo.colorFor(SessionState.WAITING_PERM), engine.heroColor.value)
        engine.slots.forEach { assertEquals(0f, it.alpha.value, 0f) }
        assertFalse(engine.heroSweep.isRunning)

        // Jump-home (the resolved card): feed → page, instant again.
        engine.retarget(scope, page(states), nowMs = clock.nowMs)
        runCurrent()
        assertFalse(engine.heroShown)
        assertEquals(1f, engine.merge.value, 0f)
        assertEquals(0f, engine.dashAlpha.value, 0f)
        engine.slots.forEach { assertEquals(1f, it.alpha.value, 0f) }
        engine.slots.forEach { assertFalse(it.alpha.isRunning) }
    }

    // ── The trigger contract ────────────────────────────────────────────────

    @Test
    fun snapshotFlowNeverReEmitsForValueEqualOrUnrelatedWrites() = runTest {
        // The host's trigger in miniature: a snapshotFlow over the
        // value-comparable RingInputs snapshot. This is the upstream half of
        // the no-restart contract — clock ticks and feed lines cannot even
        // REACH the engine, because their recompositions rebuild an EQUAL
        // snapshot; since S7 the same property covers level, selection and
        // step direction.
        var inputs by mutableStateOf(
            RingInputs(
                level = RingLevel.PLIST,
                states = listOf(SessionState.RUNNING, SessionState.IDLE),
                emptyStyle = EmptyRingStyle.IDLE_CIRCLE,
                selectedIndex = 0,
                stepDir = StepDir.NONE,
                feedState = null,
            ),
        )
        var feedLines by mutableStateOf(0)
        val emissions = mutableListOf<RingInputs>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            snapshotFlow { inputs }.collect { emissions += it }
        }
        runCurrent()
        assertEquals(1, emissions.size)

        // A feed line's recomposition rebuilds an equal-but-new snapshot.
        inputs = inputs.copy(states = ArrayList(inputs.states))
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(1, emissions.size)

        // A clock tick writes snapshot state the trigger never read.
        feedLines += 1
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(1, emissions.size)

        // A real change — a pager step — still gets through.
        inputs = inputs.copy(selectedIndex = 1, stepDir = StepDir.FORWARD)
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(2, emissions.size)
        assertEquals(1, emissions.last().selectedIndex)

        collector.cancel()
    }

    // ── Sanity: the hero's zero-motion guard ────────────────────────────────

    @Test
    fun growShrinkRoundTripLeavesNoFloatDustForTheNextStep() = engineTest { clock, scope, engine ->
        val states = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.RUNNING)
        engine.snapTo(plist(states, selected = 1))
        engine.retarget(scope, feed(states, selected = 1), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 700)
        engine.retarget(scope, plist(states, selected = 1), nowMs = clock.nowMs)
        start(clock)
        pump(clock, 700)

        // An equal re-render after the round trip: any float dust left on the
        // committed hero end would restart a rotation over nothing.
        engine.retarget(scope, plist(ArrayList(states), selected = 1), nowMs = clock.nowMs)
        runCurrent()
        assertFalse(engine.heroEnd.isRunning)
        assertTrue(abs(m.shortestDelta(engine.heroEnd.value, m.endAngle(1, 3))) < 0.05f)
    }
}
