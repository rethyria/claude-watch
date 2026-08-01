// The Halo v2 ring's geometry/reconciliation library (epic #94, slice S2):
// every number the persistent morphing ring needs, as pure functions over
// plain values. No Compose, no clocks, no Animatables — colours stay
// Halo.SessionState keys and timestamps are parameters — so the entire v2
// motion spec is pinned by fast JVM tests BEFORE any drawing changes. The
// ring engine (HaloRingState, S4) owns the per-slot Animatables and feeds
// them from these plans.
//
// Angle convention throughout: Canvas degrees (clockwise-positive, 0° at
// 3 o'clock), and every pose angle is ACCUMULATED — full loops keep adding
// rather than normalising into [0, 360) — because Compose animates the raw
// number, and shortest-path rotation only works if the current value carries
// its winding history.
package dev.claudewatch.wear.ui.halo

import kotlin.math.abs
import kotlin.math.max

/**
 * Which of the three ring renderings is on screen. Deliberately NOT
 * [HaloDepth]: the ring cares about geometry regimes, not IA — the depth-less
 * glance pages (usage/settings) are still [PAGE], just with an empty scope.
 */
enum class RingLevel { PAGE, PLIST, FEED }

/** The four animated level transitions; every other pair snaps. */
enum class RingMorph { OPEN, CLOSE, GROW, SHRINK }

/**
 * The last pager step's direction (FORWARD = step +1, BACK = step −1), kept by
 * the caller across renders. Only [BACK] changes any output — it breaks the
 * exact-half-turn tie in [HaloRingMath.plistStepDelta] — but the engine still
 * snapshots it so a step is a VALUE change even when the resolved highlight
 * angle happens to coincide.
 */
enum class StepDir { NONE, FORWARD, BACK }

/** How an EMPTY scope rings the screen. */
enum class EmptyRingStyle {
    /** 0-session home: the faint idle circle — the layout stays readable. */
    IDLE_CIRCLE,

    /** Usage/settings glance pages: the arcs collapse away entirely. */
    COLLAPSED,
}

/** One slot's live geometry, as the engine last animated it (accumulated). */
data class SlotPose(val end: Float, val sweep: Float)

/**
 * One slot's reconciliation target. [state] is the colour KEY — null means
 * "vanishing": the renderer blends toward black, drops the round cap (its dot
 * would outlive the arc), and skips drawing below [Halo.Geo.MinDrawSweepDeg].
 */
data class SlotPlan(
    val end: Float,
    val sweep: Float,
    val state: Halo.SessionState?,
    val alpha: Float,
    /**
     * True when this slot has no visible past to animate from (absent or
     * collapsed last render): the engine must SNAP geometry and colour, then
     * animate only alpha — new arcs arrive pre-coloured at their final place.
     */
    val isNew: Boolean,
)

/** A whole-ring retarget plus the flags that gate the colour-first delay. */
data class RetargetPlan(
    val slots: List<SlotPlan>,
    /** Some already-visible geometry will animate (re-slice or collapse). */
    val moved: Boolean,
    /** Some already-visible colour will animate (state change or fade-to-black). */
    val recolored: Boolean,
)

/** A start+sweep pair, for the grow/shrink morph targets. */
data class ArcSpan(val start: Float, val sweep: Float)

/** Dash pattern lengths in ref-px along the ring channel. */
data class DashIntervals(val onPx: Float, val offPx: Float)

/**
 * The engine's whole world, as one value-comparable snapshot. This is what
 * `snapshotFlow` dedupes on: clock ticks and feed lines produce an EQUAL
 * snapshot, so they provably cannot restart a ring animation (the prototype's
 * clock-tick bug). Selection is an index, not an id, because the ring only
 * knows slots; −1 means no highlighted slot (spawn card selected, empty
 * scope, or a session that vanished under the cursor).
 */
data class RingInputs(
    val level: RingLevel,
    val states: List<Halo.SessionState>,
    /** Meaningful only when [states] is empty. */
    val emptyStyle: EmptyRingStyle,
    val selectedIndex: Int,
    val stepDir: StepDir,
    /** The open session's state at [RingLevel.FEED] (hero colour); else null. */
    val feedState: Halo.SessionState?,
)

object HaloRingMath {

    /**
     * Below this sweep a slot counts as collapsed. From the design prototype:
     * float noise on a fully-collapsed arc must not read as "visible past",
     * or a returning session would GROW out of its own death spot instead of
     * snapping in pre-coloured.
     */
    private const val SWEEP_EPS = 0.02f

    /**
     * Retarget deltas under this are forced to exactly zero. This is what
     * makes the zero-motion contract EXACT rather than approximate: entering
     * the same-scope list re-derives the same layout, float rounding on the
     * unwrap must not nudge a pose and restart a 550ms tween over nothing.
     */
    private const val MOTION_EPS = 0.02f

    /** How close to an exact half turn the backstep tie-break applies. */
    private const val HALF_TURN_TIE_EPS = 0.01f

    // ── Slot layout ─────────────────────────────────────────────────────────

    /** Segment gap: the solo ring tightens to 8° so one session still reads
     *  as "a segment", not a circle with a glitch. */
    fun gapDegrees(n: Int): Float =
        if (n <= 1) Halo.Geo.RingGapSoloDeg else Halo.Geo.RingGapDeg

    fun stepDegrees(n: Int): Float = 360f / max(1, n)

    /**
     * Arc k's END angle: −94 − k·step. The ring is anchored by segment ENDS —
     * the first arc closes just left of midnight and index order winds
     * anticlockwise — while each arc keeps a positive sweep, so round caps
     * render identically to a clockwise ring.
     */
    fun endAngle(k: Int, n: Int): Float = Halo.Geo.RingAnchorDeg - k * stepDegrees(n)

    fun sweepDegrees(n: Int): Float = stepDegrees(n) - gapDegrees(n)

    // ── Reconciliation ──────────────────────────────────────────────────────

    /**
     * Signed shortest rotation from [from] to [to] in [−180, 180), with the
     * exact-opposite tie resolving to −180 (anticlockwise — the ring's native
     * winding). Both inputs may be arbitrarily accumulated; only their
     * difference matters.
     */
    fun shortestDelta(from: Float, to: Float): Float =
        ((to - from) % 360f + 540f) % 360f - 180f

    /**
     * Reconciles the tracked slots against the scope's new [states].
     *
     * Per slot:
     * - surviving arcs retarget by [shortestDelta] on their accumulated end
     *   (sub-[MOTION_EPS] deltas collapse to zero — the zero-motion contract);
     * - vanishing arcs collapse onto their OWN START (end −= sweep, sweep→0,
     *   state null, alpha 0): the arc dies where it began instead of sliding
     *   toward a neighbour;
     * - new arcs (no visible past) snap to final geometry pre-coloured with
     *   [SlotPlan.isNew] set — they animate only alpha, drawn beneath the
     *   existing ring (see [drawOrder]).
     *
     * The [RetargetPlan.moved]/[RetargetPlan.recolored] flags count only
     * ALREADY-VISIBLE slots whose geometry/colour will actually animate. New
     * arcs deliberately trip neither: they snap, so a bare session arrival
     * must not open the colour-first delay window (the design prototype
     * counted its black→colour swap as a recolour and delayed every re-slice
     * on arrival; the epic's "pre-coloured" rule wins).
     *
     * @param solidHidden the solid layer is alpha-0 under the plist's dashed
     *   layer (same geometry, different paint) awaiting the close-swap.
     */
    fun planRetarget(
        prevPoses: List<SlotPose>,
        prevStates: List<Halo.SessionState?>,
        states: List<Halo.SessionState>,
        solidHidden: Boolean,
    ): RetargetPlan {
        val n = states.size
        val slotCount = max(prevPoses.size, n)
        var moved = false
        var recolored = false
        val slots = ArrayList<SlotPlan>(slotCount)
        for (k in 0 until slotCount) {
            val shown = k < n
            val prev = prevPoses.getOrNull(k)
            val prevState = prevStates.getOrNull(k)
            val isNew = shown && (prev == null || prev.sweep <= SWEEP_EPS)
            val targetSweep = if (shown) sweepDegrees(n) else 0f
            val end = when {
                prev == null -> endAngle(k, n)
                // The unwrap keeps the accumulated pose bounded even for a
                // re-appearing (isNew) slot: the value it snaps to is the
                // nearest coterminal representation of the target, identical
                // geometry, no runaway winding.
                shown -> {
                    val delta = shortestDelta(prev.end, endAngle(k, n))
                    prev.end + if (abs(delta) < MOTION_EPS) 0f else delta
                }
                prev.sweep > SWEEP_EPS -> prev.end - prev.sweep
                // Already collapsed: stay put, so a long-dead slot never
                // creeps and never re-flags `moved`.
                else -> prev.end
            }
            if (!isNew && prev != null &&
                (end != prev.end || abs(targetSweep - prev.sweep) > MOTION_EPS)
            ) {
                moved = true
            }
            val state = if (shown) states[k] else null
            if (!isNew && prev != null && prevState != state) recolored = true
            slots.add(
                SlotPlan(
                    end = end,
                    sweep = targetSweep,
                    state = state,
                    alpha = if (shown && !solidHidden) 1f else 0f,
                    isNew = isNew,
                ),
            )
        }
        return RetargetPlan(slots, moved, recolored)
    }

    /**
     * Extra delay before geometry starts, so colour lands first when one
     * update both moves and recolours the ring. [windowStart] is when the
     * caller last saw a plan with both flags set (null = never); the delay
     * holds for the whole [Halo.Motion.GeometryDelayWindowMs] so the
     * follow-up renders of the same burst stay coherent.
     */
    fun geometryDelayMs(windowStart: Long?, now: Long): Int =
        if (windowStart != null && now - windowStart < Halo.Motion.GeometryDelayWindowMs) {
            Halo.Motion.GeometryDelayMs
        } else {
            0
        }

    /**
     * Paint order for the slots (first = beneath): arcs that appeared within
     * the last [Halo.Motion.NewArcBeneathMs] draw UNDER the settled ring —
     * a new arc fades in behind its neighbours instead of flashing over them
     * — then everyone returns to stable index order. Both partitions keep
     * index order, so expiry never reshuffles what it doesn't have to.
     *
     * @param appearedAt per-slot timestamp of the last [SlotPlan.isNew]
     *   render, null for never-new slots.
     */
    fun drawOrder(appearedAt: List<Long?>, now: Long): List<Int> {
        val (fresh, settled) = appearedAt.indices.partition { k ->
            val t = appearedAt[k]
            t != null && now - t < Halo.Motion.NewArcBeneathMs
        }
        return fresh + settled
    }

    // ── Pager highlight ─────────────────────────────────────────────────────

    /** The highlight arc's START angle over slot [index] (end − sweep). */
    fun highlightAngle(index: Int, n: Int): Float = endAngle(index, n) - sweepDegrees(n)

    /**
     * The rotation to ADD to the accumulated highlight angle to reach
     * [targetBase]: shortest path, except the exact-half-turn tie. With 2
     * sessions every step is ±180 and the unwrap's tie rule always answers
     * −180, so stepping back would orbit the SAME way as stepping forward;
     * a [StepDir.BACK] step at the tie forces +180 so the highlight retraces
     * counter to its forward path.
     */
    fun plistStepDelta(current: Float, targetBase: Float, lastStepDir: StepDir): Float {
        val delta = shortestDelta(current, targetBase)
        return if (lastStepDir == StepDir.BACK && abs(delta + 180f) < HALF_TURN_TIE_EPS) {
            180f
        } else {
            delta
        }
    }

    // ── Level morphs ────────────────────────────────────────────────────────

    /**
     * Grow targets for the list→feed morph: the selected arc expands BOTH
     * ways into the full feed circle — the start backs off by half the
     * missing sweep, so the arc's midpoint holds still while its ends race
     * to meet at the far side. [ArcSpan.start]+360 is also what the exact
     * reverse (shrink) animates back from.
     */
    fun growTargets(start: Float, sweep: Float): ArcSpan =
        ArcSpan(start = start - (360f - sweep) / 2f, sweep = 360f)

    /**
     * Dash pattern at [mergeFraction] through the split/merge morph
     * (0 = dashed list layer, 1 = solid). The 13.5 period is CONSTANT — only
     * the on-length grows, so each dash fuses in place with its neighbours
     * instead of the whole pattern crawling along the ring.
     */
    fun dashIntervals(mergeFraction: Float): DashIntervals {
        val f = mergeFraction.coerceIn(0f, 1f)
        val on = Halo.Geo.DashOnPx + (Halo.Geo.DashPeriodPx - Halo.Geo.DashOnPx) * f
        return DashIntervals(onPx = on, offPx = Halo.Geo.DashPeriodPx - on)
    }

    /**
     * Dashed-layer stroke at [mergeFraction]: 4 (list) ↔ 9 (solid). The same
     * ONE fraction that drives [dashIntervals] and [dashLayerAlpha] — the
     * three are functions of it by construction, so they cannot desync, and
     * at fraction 1 the layer is pixel-identical to the solid ring (stroke 9,
     * alpha 1, zero off-interval): what makes the close-swap atomic.
     */
    fun dashStroke(mergeFraction: Float): Float {
        val f = mergeFraction.coerceIn(0f, 1f)
        return Halo.Geo.RingStrokeDashed + (Halo.Geo.RingStroke - Halo.Geo.RingStrokeDashed) * f
    }

    /** Dashed-layer alpha at [mergeFraction]: .65 (list) ↔ 1 (solid). */
    fun dashLayerAlpha(mergeFraction: Float): Float {
        val f = mergeFraction.coerceIn(0f, 1f)
        return Halo.Geo.DashedLayerAlpha + (1f - Halo.Geo.DashedLayerAlpha) * f
    }

    /**
     * The grown hero's END for an arc currently at ([end], [sweep]) — the
     * end-anchored form of [growTargets], because the engine tracks slot
     * geometry by END. Fed the hero's ACTUAL pose (mid-flight included, per
     * the S7 spec — this is a morph target, not a [planRetarget] prevPose),
     * so a grow interrupted mid-rotation still expands symmetrically about
     * wherever the arc really is.
     */
    fun growEndTarget(end: Float, sweep: Float): Float =
        growTargets(end - sweep, sweep).start + 360f

    /**
     * The shrunk hero's END: the exact reverse of [growEndTarget] — back off
     * by half the vanishing sweep — then corrected by [shortestDelta] onto the
     * REAL slot end [targetBaseEnd] (nearest coterminal representation). When
     * nothing changed while the feed was open the correction is zero and the
     * shrink retraces the grow exactly; when the scope re-sliced underneath,
     * the hero lands on the segment's new place with minimal extra rotation.
     */
    fun shrinkEndTarget(currentEnd: Float, targetSweep: Float, targetBaseEnd: Float): Float {
        val natural = currentEnd - (360f - targetSweep) / 2f
        return natural + shortestDelta(natural, targetBaseEnd)
    }

    /**
     * The morph a level transition plays, or null to snap. Only the four
     * adjacent transitions animate; anything else (jumpHome from a feed,
     * self-heal after a scope vanishes) lands instantly — a morph between
     * non-adjacent regimes has no geometry story to tell.
     */
    fun morphFor(from: RingLevel, to: RingLevel): RingMorph? = when {
        from == RingLevel.PAGE && to == RingLevel.PLIST -> RingMorph.OPEN
        from == RingLevel.PLIST && to == RingLevel.PAGE -> RingMorph.CLOSE
        from == RingLevel.PLIST && to == RingLevel.FEED -> RingMorph.GROW
        from == RingLevel.FEED && to == RingLevel.PLIST -> RingMorph.SHRINK
        else -> null
    }

    // ── Input snapshot ──────────────────────────────────────────────────────

    /**
     * Derives the engine's [RingInputs] from nav + model. Scope rules:
     * - PAGE depth reads the pager: home/project pages ring their scope's
     *   sessions; the depth-less glance pages (page < 0, usage/settings)
     *   are an empty scope that [EmptyRingStyle.COLLAPSED] folds away;
     *   a 0-session home keeps [EmptyRingStyle.IDLE_CIRCLE] — the faint
     *   circle the empty ring has always drawn.
     * - LIST/SESSION depth reads [HaloNavState.listScope], the scope nav
     *   resolved on the way down.
     *
     * Selection maps [HaloNavState.sessionId] to its slot index in scope
     * order (ring order == pager order, #95). It is read at LIST depth too:
     * when back-from-feed starts preserving the id (S1), the shrink morph
     * lands on the surviving selection with no change here. −1 = no
     * highlight (spawn card, empty scope, or a vanished session — upstream
     * self-heal owns re-resolving it).
     */
    fun ringInputs(nav: HaloNavState, model: HaloModel, lastStepDir: StepDir): RingInputs {
        val level = when (nav.depth) {
            HaloDepth.PAGE -> RingLevel.PAGE
            HaloDepth.LIST -> RingLevel.PLIST
            HaloDepth.SESSION -> RingLevel.FEED
        }
        val scope: ListScope? = when (nav.depth) {
            HaloDepth.PAGE -> if (nav.page < 0) null else scopeForPage(nav.page, model)
            else -> nav.listScope
        }
        val sessions = when (scope) {
            null -> emptyList()
            ListScope.All -> model.sessions
            is ListScope.Project ->
                model.projects.firstOrNull { it.name == scope.name }?.sessions.orEmpty()
        }
        val selectedIndex =
            if (level == RingLevel.PAGE) {
                -1
            } else {
                nav.sessionId?.let { id -> sessions.indexOfFirst { it.id == id } } ?: -1
            }
        return RingInputs(
            level = level,
            states = sessions.map { it.state },
            emptyStyle = if (scope == null) EmptyRingStyle.COLLAPSED else EmptyRingStyle.IDLE_CIRCLE,
            selectedIndex = selectedIndex,
            stepDir = lastStepDir,
            feedState = if (level == RingLevel.FEED) sessions.getOrNull(selectedIndex)?.state else null,
        )
    }
}
