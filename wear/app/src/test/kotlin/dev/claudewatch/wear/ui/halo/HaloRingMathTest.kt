package dev.claudewatch.wear.ui.halo

import dev.claudewatch.wear.ui.halo.Halo.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Halo v2 ring math (epic #94, S2) before any drawing changes. The
 * epic's constants table is the spec; the assertions here are the numbers a
 * later renderer regression would silently bend: the −94° anchor and gap
 * family, the exact-zero zero-motion contract (same states → nothing may
 * animate, ever — the prototype's clock-tick bug made this a contract, not a
 * nicety), the collapse-onto-own-start rule, the colour-first delay window
 * edges, and the 2-session backstep retrace.
 */
class HaloRingMathTest {

    private val m = HaloRingMath

    // ── Slot layout ─────────────────────────────────────────────────────────

    @Test
    fun soloLayoutTightensTheGapAndEndsLeftOfMidnight() {
        assertEquals(8f, m.gapDegrees(1), 0f)
        assertEquals(360f, m.stepDegrees(1), 0f)
        assertEquals(-94f, m.endAngle(0, 1), 0f)
        assertEquals(352f, m.sweepDegrees(1), 0f)
    }

    @Test
    fun fiveSlotLayoutWindsAnticlockwiseFromTheAnchor() {
        assertEquals(8.5f, m.gapDegrees(5), 0f)
        assertEquals(72f, m.stepDegrees(5), 0f)
        assertEquals(63.5f, m.sweepDegrees(5), 0f)
        // END angles march anticlockwise (more negative) with the index.
        listOf(-94f, -166f, -238f, -310f, -382f).forEachIndexed { k, end ->
            assertEquals(end, m.endAngle(k, 5), 0f)
        }
    }

    @Test
    fun sevenSlotLayoutSurvivesANonExactDivision() {
        val step = 360f / 7f
        assertEquals(step, m.stepDegrees(7), 1e-4f)
        assertEquals(step - 8.5f, m.sweepDegrees(7), 1e-4f)
        assertEquals(-94f - 3 * step, m.endAngle(3, 7), 1e-4f)
    }

    // ── shortestDelta ───────────────────────────────────────────────────────

    @Test
    fun shortestDeltaTakesTheNearWayAroundEitherDirection() {
        assertEquals(10f, m.shortestDelta(0f, 10f), 0f)
        assertEquals(-10f, m.shortestDelta(10f, 0f), 0f)
        // Across the wrap point, both ways.
        assertEquals(20f, m.shortestDelta(350f, 10f), 0f)
        assertEquals(-20f, m.shortestDelta(10f, 350f), 0f)
        // Accumulated (multi-turn) inputs: only the difference matters.
        assertEquals(0f, m.shortestDelta(-94f, -94f - 720f), 0f)
        assertEquals(-120f, m.shortestDelta(-94f - 720f, -214f), 0f)
    }

    @Test
    fun shortestDeltaResolvesTheExactHalfTurnTieAnticlockwise() {
        assertEquals(-180f, m.shortestDelta(0f, 180f), 0f)
        assertEquals(-180f, m.shortestDelta(0f, -180f), 0f)
    }

    // ── planRetarget ────────────────────────────────────────────────────────

    /** The settled poses/states a ring showing [states] would hold. */
    private fun settled(states: List<SessionState>): Pair<List<SlotPose>, List<SessionState?>> {
        val n = states.size
        return List(n) { k -> SlotPose(m.endAngle(k, n), m.sweepDegrees(n)) } to
            states.map { it as SessionState? }
    }

    @Test
    fun sameStatesMoveNothingExactly() {
        // The zero-motion contract: entering the same-scope list (or any
        // re-render with equal states) may not nudge a single target — the
        // asserts are EXACT, not epsilon, because any drift restarts a tween.
        val states = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.WAITING_PERM)
        val (poses, prevStates) = settled(states)
        val plan = m.planRetarget(poses, prevStates, states, solidHidden = false)
        assertFalse(plan.moved)
        assertFalse(plan.recolored)
        plan.slots.forEachIndexed { k, slot ->
            assertEquals(poses[k].end, slot.end, 0f)
            assertEquals(poses[k].sweep, slot.sweep, 0f)
            assertEquals(states[k], slot.state)
            assertEquals(1f, slot.alpha, 0f)
            assertFalse(slot.isNew)
        }
    }

    @Test
    fun sameStatesMoveNothingEvenAfterFullLoopsOfAccumulation() {
        // Poses carry winding history; a whole-turn offset is the same place.
        val states = listOf(SessionState.RUNNING, SessionState.RUNNING)
        val (base, prevStates) = settled(states)
        val wound = base.map { SlotPose(it.end - 720f, it.sweep) }
        val plan = m.planRetarget(wound, prevStates, states, solidHidden = false)
        assertFalse(plan.moved)
        plan.slots.forEachIndexed { k, slot -> assertEquals(wound[k].end, slot.end, 0f) }
    }

    @Test
    fun solidHiddenZeroesAlphaWithoutInventingMotion() {
        // Entering the same-scope list: dashed layer takes over, the solid
        // layer hides — but geometrically NOTHING may animate.
        val states = listOf(SessionState.RUNNING, SessionState.IDLE)
        val (poses, prevStates) = settled(states)
        val plan = m.planRetarget(poses, prevStates, states, solidHidden = true)
        assertFalse(plan.moved)
        assertFalse(plan.recolored)
        plan.slots.forEach { assertEquals(0f, it.alpha, 0f) }
    }

    @Test
    fun growPlanMovesSurvivorsAndSnapsTheNewArcPreColoured() {
        val before = listOf(SessionState.RUNNING, SessionState.IDLE)
        val after = before + SessionState.WAITING_PERM
        val (poses, prevStates) = settled(before)
        val plan = m.planRetarget(poses, prevStates, after, solidHidden = false)

        // The re-slice moves existing geometry, but nothing recolours: the
        // new arc arrives pre-coloured and must NOT open the delay window.
        assertTrue(plan.moved)
        assertFalse(plan.recolored)

        // Slot 0's end (−94) is n-invariant; slot 1 retargets by +60.
        assertEquals(-94f, plan.slots[0].end, 0f)
        assertEquals(m.sweepDegrees(3), plan.slots[0].sweep, 0f)
        assertEquals(-214f, plan.slots[1].end, 1e-4f)
        assertFalse(plan.slots[1].isNew)

        // The newcomer: snapped to final geometry, final colour, fade-only.
        val fresh = plan.slots[2]
        assertTrue(fresh.isNew)
        assertEquals(m.endAngle(2, 3), fresh.end, 1e-4f)
        assertEquals(m.sweepDegrees(3), fresh.sweep, 0f)
        assertEquals(SessionState.WAITING_PERM, fresh.state)
        assertEquals(1f, fresh.alpha, 0f)
    }

    @Test
    fun shrinkPlanCollapsesTheVanishingArcOntoItsOwnStart() {
        val before = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.RUNNING)
        val after = before.take(2)
        val (poses, prevStates) = settled(before)
        val plan = m.planRetarget(poses, prevStates, after, solidHidden = false)

        // A vanish is BOTH a move (the collapse + re-slice) and a recolour
        // (the dying arc blends to black) — the delay window applies.
        assertTrue(plan.moved)
        assertTrue(plan.recolored)

        val dying = plan.slots[2]
        assertEquals(poses[2].end - poses[2].sweep, dying.end, 0f)
        assertEquals(0f, dying.sweep, 0f)
        assertNull(dying.state)
        assertEquals(0f, dying.alpha, 0f)
        assertFalse(dying.isNew)

        // Survivors re-slice for n=2.
        assertEquals(-274f, plan.slots[1].end, 1e-4f)
        assertEquals(m.sweepDegrees(2), plan.slots[1].sweep, 0f)
    }

    @Test
    fun alreadyCollapsedSlotsStayPutAndFlagNothing() {
        // Feed the collapse plan's own output back in: the hidden slot is at
        // rest — no motion, no recolour, forever.
        val before = listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.RUNNING)
        val after = before.take(2)
        val (poses, prevStates) = settled(before)
        val collapsed = m.planRetarget(poses, prevStates, after, solidHidden = false)
        val plan = m.planRetarget(
            collapsed.slots.map { SlotPose(it.end, it.sweep) },
            collapsed.slots.map { it.state },
            after,
            solidHidden = false,
        )
        assertFalse(plan.moved)
        assertFalse(plan.recolored)
        assertEquals(collapsed.slots[2].end, plan.slots[2].end, 0f)
    }

    @Test
    fun recolourOnlyPlanKeepsEveryTargetStill() {
        val before = listOf(SessionState.RUNNING, SessionState.RUNNING, SessionState.IDLE)
        val after = listOf(SessionState.RUNNING, SessionState.WAITING_PERM, SessionState.IDLE)
        val (poses, prevStates) = settled(before)
        val plan = m.planRetarget(poses, prevStates, after, solidHidden = false)
        assertFalse(plan.moved)
        assertTrue(plan.recolored)
        plan.slots.forEachIndexed { k, slot ->
            assertEquals(poses[k].end, slot.end, 0f)
            assertEquals(poses[k].sweep, slot.sweep, 0f)
        }
        assertEquals(SessionState.WAITING_PERM, plan.slots[1].state)
    }

    @Test
    fun collapseToEmptyFoldsEverySlotAway() {
        // The usage/settings swipe: the whole scope empties and every arc
        // dies where it began.
        val before = listOf(SessionState.RUNNING, SessionState.IDLE)
        val (poses, prevStates) = settled(before)
        val plan = m.planRetarget(poses, prevStates, emptyList(), solidHidden = false)
        assertTrue(plan.moved)
        assertTrue(plan.recolored)
        plan.slots.forEachIndexed { k, slot ->
            assertEquals(poses[k].end - poses[k].sweep, slot.end, 0f)
            assertEquals(0f, slot.sweep, 0f)
            assertNull(slot.state)
        }
    }

    @Test
    fun firstRenderSnapsEverythingWithNoFlags() {
        val states = listOf(SessionState.RUNNING, SessionState.WAITING_Q)
        val plan = m.planRetarget(emptyList(), emptyList(), states, solidHidden = false)
        assertFalse(plan.moved)
        assertFalse(plan.recolored)
        plan.slots.forEachIndexed { k, slot ->
            assertTrue(slot.isNew)
            assertEquals(m.endAngle(k, 2), slot.end, 0f)
            assertEquals(m.sweepDegrees(2), slot.sweep, 0f)
            assertEquals(states[k], slot.state)
        }
    }

    // ── geometryDelayMs ─────────────────────────────────────────────────────

    @Test
    fun geometryDelayHoldsInsideTheWindowAndDropsAtItsEdge() {
        assertEquals(0, m.geometryDelayMs(null, 5_000L))
        assertEquals(220, m.geometryDelayMs(5_000L, 5_000L))
        assertEquals(220, m.geometryDelayMs(5_000L, 5_849L))
        // The window is half-open: 850ms after the flags fired, geometry is
        // back to immediate.
        assertEquals(0, m.geometryDelayMs(5_000L, 5_850L))
    }

    // ── drawOrder ───────────────────────────────────────────────────────────

    @Test
    fun drawOrderPaintsFreshArcsFirstThenExpiresThemStably() {
        val appeared = listOf<Long?>(null, 1_000L, null, 1_500L)
        // Both new: they paint first (beneath), each partition in index order.
        assertEquals(listOf(1, 3, 0, 2), m.drawOrder(appeared, now = 2_000L))
        // Slot 1 expires at exactly +1300ms; slot 3 is still fresh.
        assertEquals(listOf(3, 0, 1, 2), m.drawOrder(appeared, now = 2_300L))
        // Everyone settled: plain index order — expiry never reshuffles the
        // settled ring.
        assertEquals(listOf(0, 1, 2, 3), m.drawOrder(appeared, now = 2_800L))
    }

    // ── Pager highlight ─────────────────────────────────────────────────────

    @Test
    fun highlightAngleIsTheSlotStart() {
        assertEquals(m.endAngle(2, 4) - m.sweepDegrees(4), m.highlightAngle(2, 4), 0f)
    }

    @Test
    fun plistStepDeltaRotatesShortestPathBothDirections() {
        val h0 = m.highlightAngle(0, 3)
        val h1 = m.highlightAngle(1, 3)
        assertEquals(-120f, m.plistStepDelta(h0, h1, StepDir.FORWARD), 1e-4f)
        assertEquals(120f, m.plistStepDelta(h1, h0, StepDir.BACK), 1e-4f)
    }

    @Test
    fun twoSessionBackstepRetracesInsteadOfOrbiting() {
        val h0 = m.highlightAngle(0, 2)
        val h1 = m.highlightAngle(1, 2)
        // Forward is an exact half turn; the tie resolves anticlockwise.
        assertEquals(-180f, m.plistStepDelta(h0, h1, StepDir.FORWARD), 0f)
        // Stepping BACK hits the same tie — without the override the
        // highlight would orbit the same way it came. It must retrace.
        assertEquals(180f, m.plistStepDelta(h1, h0, StepDir.BACK), 0f)
        // The override is ONLY for the tie: a non-tie back step is untouched.
        assertEquals(
            120f,
            m.plistStepDelta(m.highlightAngle(1, 3), m.highlightAngle(0, 3), StepDir.BACK),
            1e-4f,
        )
    }

    @Test
    fun fullLoopAccumulationReturnsExactlyHome() {
        // Forward through a 3-slot list and back again: the accumulated angle
        // must land exactly on its origin, not a float-drifted neighbour.
        var angle = m.highlightAngle(0, 3)
        val home = angle
        for (i in listOf(1, 2)) angle += m.plistStepDelta(angle, m.highlightAngle(i, 3), StepDir.FORWARD)
        for (i in listOf(1, 0)) angle += m.plistStepDelta(angle, m.highlightAngle(i, 3), StepDir.BACK)
        assertEquals(home, angle, 0f)
        // And winding history never breaks retargeting: two full loops out,
        // the next step is still the plain −120.
        assertEquals(
            -120f,
            m.plistStepDelta(home - 720f, m.highlightAngle(1, 3), StepDir.FORWARD),
            1e-4f,
        )
    }

    // ── Level morphs ────────────────────────────────────────────────────────

    @Test
    fun growTargetsExpandSymmetricallyAboutTheArcCentre() {
        val n = 4
        val start = m.highlightAngle(1, n)
        val sweep = m.sweepDegrees(n)
        val grown = m.growTargets(start, sweep)
        assertEquals(360f, grown.sweep, 0f)
        assertEquals(start - (360f - sweep) / 2f, grown.start, 0f)
        // Symmetric = the arc's midpoint holds still while both ends move.
        assertEquals(start + sweep / 2f, grown.start + 180f, 1e-4f)
    }

    @Test
    fun dashIntervalsKeepAConstantPeriod() {
        for (f in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val d = m.dashIntervals(f)
            assertEquals(13.5f, d.onPx + d.offPx, 1e-4f)
        }
        assertEquals(2.5f, m.dashIntervals(0f).onPx, 0f)
        assertEquals(11f, m.dashIntervals(0f).offPx, 0f)
        assertEquals(13.5f, m.dashIntervals(1f).onPx, 0f)
        assertEquals(0f, m.dashIntervals(1f).offPx, 0f)
        // Out-of-range fractions clamp to the endpoints instead of inventing
        // negative dashes.
        assertEquals(2.5f, m.dashIntervals(-1f).onPx, 0f)
        assertEquals(13.5f, m.dashIntervals(2f).onPx, 0f)
    }

    @Test
    fun dashStrokeAndAlphaRideTheOneMergeFraction() {
        // Interval, stroke and alpha are all functions of the ONE fraction —
        // the structural can't-desync guarantee — and fraction 1 is the solid
        // layer's exact paint (stroke 9, alpha 1), what makes the close-swap
        // pixel-identical by construction.
        assertEquals(Halo.Geo.RingStrokeDashed, m.dashStroke(0f), 0f)
        assertEquals(Halo.Geo.RingStroke, m.dashStroke(1f), 0f)
        assertEquals(Halo.Geo.DashedLayerAlpha, m.dashLayerAlpha(0f), 0f)
        assertEquals(1f, m.dashLayerAlpha(1f), 0f)
        // Clamped like dashIntervals — no negative strokes off the ends.
        assertEquals(Halo.Geo.RingStrokeDashed, m.dashStroke(-1f), 0f)
        assertEquals(1f, m.dashLayerAlpha(2f), 0f)
    }

    @Test
    fun growEndTargetIsTheEndAnchoredFormOfGrowTargets() {
        val n = 3
        val end = m.endAngle(1, n)
        val sweep = m.sweepDegrees(n)
        val grown = m.growTargets(end - sweep, sweep)
        assertEquals(grown.start + 360f, m.growEndTarget(end, sweep), 1e-4f)
        assertEquals(end + (360f - sweep) / 2f, m.growEndTarget(end, sweep), 1e-4f)
    }

    @Test
    fun shrinkEndTargetInvertsGrowExactlyAndCorrectsOntoARealSlot() {
        val n = 4
        val end = m.endAngle(2, n)
        val sweep = m.sweepDegrees(n)
        val grown = m.growEndTarget(end, sweep)
        // Nothing changed while the feed was open: the exact reverse.
        assertEquals(end, m.shrinkEndTarget(grown, sweep, m.endAngle(2, n)), 1e-3f)
        // The scope re-sliced underneath (4 → 3): land coterminal with the
        // REAL slot end — minimal extra rotation, correct segment.
        val resliced = m.shrinkEndTarget(grown, m.sweepDegrees(3), m.endAngle(1, 3))
        assertEquals(0f, m.shortestDelta(resliced, m.endAngle(1, 3)), 1e-3f)
    }

    @Test
    fun morphForAnimatesTheFourAdjacentTransitionsOnly() {
        assertEquals(RingMorph.OPEN, m.morphFor(RingLevel.PAGE, RingLevel.PLIST))
        assertEquals(RingMorph.CLOSE, m.morphFor(RingLevel.PLIST, RingLevel.PAGE))
        assertEquals(RingMorph.GROW, m.morphFor(RingLevel.PLIST, RingLevel.FEED))
        assertEquals(RingMorph.SHRINK, m.morphFor(RingLevel.FEED, RingLevel.PLIST))
        // Everything else snaps: jumpHome from a feed, self-heal jumps, and
        // staying put.
        assertNull(m.morphFor(RingLevel.PAGE, RingLevel.FEED))
        assertNull(m.morphFor(RingLevel.FEED, RingLevel.PAGE))
        for (level in RingLevel.entries) assertNull(m.morphFor(level, level))
    }

    // ── ringInputs ──────────────────────────────────────────────────────────

    private fun session(id: String, project: String, state: SessionState = SessionState.RUNNING) =
        HaloSession(id = id, title = id, projectName = project, state = state)

    private fun model(): HaloModel {
        val alpha = listOf(session("s-a1", "alpha"), session("s-a2", "alpha", SessionState.IDLE))
        val beta = listOf(session("s-b1", "beta", SessionState.WAITING_PERM))
        return HaloModel(
            projects = listOf(HaloProject("alpha", alpha), HaloProject("beta", beta)),
            sessions = alpha + beta,
            queue = beta,
        )
    }

    @Test
    fun glancePagesCollapseTheRingAway() {
        for (page in listOf(USAGE_PAGE, SETTINGS_PAGE)) {
            val inputs = m.ringInputs(HaloNavState(page = page), model(), StepDir.NONE)
            assertEquals(RingLevel.PAGE, inputs.level)
            assertTrue(inputs.states.isEmpty())
            assertEquals(EmptyRingStyle.COLLAPSED, inputs.emptyStyle)
        }
    }

    @Test
    fun zeroSessionHomeKeepsTheFaintIdleCircle() {
        val empty = HaloModel(projects = emptyList(), sessions = emptyList(), queue = emptyList())
        val inputs = m.ringInputs(HaloNavState(page = 0), empty, StepDir.NONE)
        assertTrue(inputs.states.isEmpty())
        assertEquals(EmptyRingStyle.IDLE_CIRCLE, inputs.emptyStyle)
    }

    @Test
    fun pageScopesRingTheirOwnSessions() {
        val home = m.ringInputs(HaloNavState(page = 0), model(), StepDir.NONE)
        assertEquals(RingLevel.PAGE, home.level)
        assertEquals(
            listOf(SessionState.RUNNING, SessionState.IDLE, SessionState.WAITING_PERM),
            home.states,
        )
        assertEquals(-1, home.selectedIndex)
        assertNull(home.feedState)

        val project = m.ringInputs(HaloNavState(page = 2), model(), StepDir.NONE)
        assertEquals(listOf(SessionState.WAITING_PERM), project.states)
    }

    @Test
    fun listLevelReadsTheResolvedScopeAndSelection() {
        val nav = HaloNavState(
            page = 1,
            depth = HaloDepth.LIST,
            listScope = ListScope.Project("alpha"),
            sessionId = "s-a2",
        )
        val inputs = m.ringInputs(nav, model(), StepDir.FORWARD)
        assertEquals(RingLevel.PLIST, inputs.level)
        assertEquals(listOf(SessionState.RUNNING, SessionState.IDLE), inputs.states)
        assertEquals(1, inputs.selectedIndex)
        assertEquals(StepDir.FORWARD, inputs.stepDir)
        assertNull(inputs.feedState)
    }

    @Test
    fun selectionSurvivesBackFromFeed() {
        // The nav state S1's back() will produce: LIST depth with the feed's
        // session id preserved — the shrink morph must land on that slot.
        val nav = HaloNavState(depth = HaloDepth.LIST, listScope = ListScope.All, sessionId = "s-b1")
        val inputs = m.ringInputs(nav, model(), StepDir.NONE)
        assertEquals(RingLevel.PLIST, inputs.level)
        assertEquals(2, inputs.selectedIndex)
    }

    @Test
    fun nullOrVanishedSelectionFadesTheHighlight() {
        // Null id (the trailing spawn card, or an empty scope) and a session
        // that vanished under the cursor both mean: no highlighted slot.
        val spawn = HaloNavState(depth = HaloDepth.LIST, listScope = ListScope.All)
        assertEquals(-1, m.ringInputs(spawn, model(), StepDir.NONE).selectedIndex)

        val stale = spawn.copy(sessionId = "s-gone")
        assertEquals(-1, m.ringInputs(stale, model(), StepDir.NONE).selectedIndex)
    }

    @Test
    fun feedLevelCarriesTheOpenSessionsState() {
        val nav = HaloNavState(
            depth = HaloDepth.SESSION,
            listScope = ListScope.Project("beta"),
            sessionId = "s-b1",
        )
        val inputs = m.ringInputs(nav, model(), StepDir.NONE)
        assertEquals(RingLevel.FEED, inputs.level)
        assertEquals(listOf(SessionState.WAITING_PERM), inputs.states)
        assertEquals(0, inputs.selectedIndex)
        assertEquals(SessionState.WAITING_PERM, inputs.feedState)
    }

    // ── Theme token cross-check ─────────────────────────────────────────────

    @Test
    fun channelDerivedDotClearanceKeepsTheDotsExactlyWhereV1PutThem() {
        // PageDots will re-derive from the channel (a later slice); this pins
        // that the channel-based arithmetic reproduces the v1 dot-edge radius
        // bit-for-bit, so the swap cannot move a pixel.
        val v1DotEdge = HALO_REF_PX / 2f - Halo.Geo.RingEdgeGap - Halo.Geo.RingStroke - Halo.Geo.DotArcGap
        val channelDotEdge = Halo.Geo.RingChannel - Halo.Geo.RingStroke / 2f - Halo.Geo.DotChannelClearance
        assertEquals(v1DotEdge, channelDotEdge, 0f)
    }
}
