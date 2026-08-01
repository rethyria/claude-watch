// The live ring engine (Halo v2, epic #94 S4 + the S7 morphs): per-slot
// Animatables driven by the S2 reconciliation plans, plus the layer channels
// the level morphs animate — one merge fraction for the dashed layer, one
// persistent hero arc that is both the list highlight and the feed ring. The
// engine owns MOTION only — HaloRingHost owns the trigger (snapshotFlow over
// the value-comparable RingInputs snapshot) and the draw; HaloRingMath owns
// every target number. Morph phase lives HERE and is never exposed as
// snapshot state for content to key on (the epic's structural no-flash rule).
// Invariants that shape the API:
//
// - Plans are computed from the COMMITTED targets (the last plan applied),
//   never from mid-flight Animatable values: settled poses reproduce
//   bit-exactly, which is what keeps the S2 zero-motion contract exact — a
//   raw mid-flight sweep fed back as a prevPose would defeat the epsilon and
//   restart tweens over nothing (#96 carry-over). The one deliberate
//   exception is the grow/shrink morph target, which the S7 spec derives
//   from the hero's ACTUAL pose so an interrupted morph retargets smoothly.
// - Interruptions retarget via animateTo from the CURRENT value (Animatable's
//   mutex cancels the in-flight tween in place): the ring never snaps
//   mid-morph — except deliberately, in [snapTo] (the ambient/first-render
//   path), on a non-adjacent level jump (Answer-pill page→feed, jump-home —
//   both happen under the opaque card), and in the close-swap, whose snap is
//   pixel-invisible by construction.
//
// Pure Compose animation + Kotlin — no android.* — so the whole engine runs
// under a hand-rolled MonotonicFrameClock in JVM tests (HaloRingStateTest).
package dev.claudewatch.wear.ui.halo

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The animated state behind [HaloRingHost]. Slots are GROW-ONLY: a vanished
 * session's slot stays, collapsed onto its own start (sweep 0, alpha 0), so a
 * later plan for the same index reconciles against a real pose instead of a
 * hole — exactly the shape [HaloRingMath.planRetarget] expects.
 *
 * Three renderings share the one slot geometry: the solid layer (the page
 * ring), the dashed layer (same geometry, dash paint — merged dashes are
 * pixel-identical to the solid layer by construction), and the hero arc,
 * which never fades during morphs: OPEN snaps it onto the selected segment,
 * steps rotate it shortest-path, GROW expands it into the full feed ring and
 * SHRINK is the exact reverse.
 */
class HaloRingState {

    /**
     * One tracked slot's animation channels plus its committed targets. The
     * Animatables are what the draw phase reads; the `target*` values are what
     * the NEXT plan reconciles from (see the file header for why those are
     * never the live values). [targetState] doubles as the draw's key for the
     * dying-arc treatment (butt cap — a round cap's dot would outlive the
     * collapsing arc) and the ambient palette.
     */
    class Slot internal constructor(
        plan: SlotPlan,
        initialColor: Color,
        initialAlpha: Float,
        appearedAt: Long?,
    ) {
        val end = Animatable(plan.end)
        val sweep = Animatable(plan.sweep)
        val color = Animatable(initialColor)
        val alpha = Animatable(initialAlpha)

        var targetEnd: Float = plan.end
            internal set
        var targetSweep: Float = plan.sweep
            internal set
        var targetState: Halo.SessionState? = plan.state
            internal set
        var targetAlpha: Float = plan.alpha
            internal set

        /** Last [SlotPlan.isNew] render, for [drawOrder]; null = never new. */
        var appearedAt: Long? = appearedAt
            internal set

        internal fun commit(plan: SlotPlan) {
            targetEnd = plan.end
            targetSweep = plan.sweep
            targetState = plan.state
            targetAlpha = plan.alpha
        }
    }

    // A snapshot state list so the draw that iterates it is invalidated when
    // a brand-new slot is born mid-frame.
    private val trackedSlots = mutableStateListOf<Slot>()
    val slots: List<Slot> get() = trackedSlots

    /**
     * The faint idle circle's opacity (0-session home, and — per the #96
     * emptyStyle decision — a transiently empty scope at list/feed depth). A
     * channel of its own, not a slot: it is a full circle in the Idle grey,
     * and the empty-scope styles fade between it and "nothing" rather than
     * collapsing it.
     */
    val idleAlpha = Animatable(0f)

    // ── The morph layers (S7) ───────────────────────────────────────────────

    /**
     * The dash split/merge fraction: 1 = the solid look (stroke 9, alpha 1,
     * zero off-interval), 0 = the list's dotted ring (stroke 4, alpha .65,
     * dash 2.5/11). The ONE value [HaloRingMath.dashIntervals]/[HaloRingMath.dashStroke]/
     * [HaloRingMath.dashLayerAlpha] all derive from, so interval, stroke and
     * alpha can never desync — and fraction 1 is pixel-identical to the solid
     * layer by construction, which is what makes the close-swap atomic.
     */
    val merge = Animatable(1f)

    /** Dashed-layer presence: 1 at PLIST (and through the whole CLOSE, until
     *  the swap), 0 at PAGE/FEED. Fades against the grow/shrink morph. */
    val dashAlpha = Animatable(0f)

    // The hero arc: the list highlight AND the feed ring, one set of channels
    // retargeted at handoffs. Geometry is accumulated (winding history), like
    // the slots.
    val heroEnd = Animatable(0f)
    val heroSweep = Animatable(0f)
    val heroStroke = Animatable(Halo.Geo.RingStroke)
    val heroColor = Animatable(Halo.Palette.Idle)
    val heroAlpha = Animatable(0f)

    /** Whether the hero renders at all. Flipped inside the same coroutine
     *  that snaps its pose, so visibility and geometry change together. */
    var heroShown by mutableStateOf(false)
        private set

    /** The hero's colour KEY (committed): diffs colour retargets and picks
     *  the ambient palette, exactly like [Slot.targetState] for slots. */
    var heroState: Halo.SessionState? = null
        private set

    // Committed hero targets (same never-read-live rule as slot targets).
    private var heroTargetEnd = 0f
    private var heroTargetSweep = 0f
    private var heroTargetAlpha = 0f

    /** The committed level — what the last applied inputs said is on screen. */
    var level: RingLevel = RingLevel.PAGE
        private set

    /**
     * The pending close-swap. While active the solid layer stays hidden (the
     * "real solid layer hidden throughout" close rule); the job itself waits
     * out [Halo.Motion.MorphSettleMs] ON THE FRAME CLOCK — not wall-clock
     * delay — so JVM manual-clock and instrumented mainClock tests drive the
     * swap deterministically.
     */
    private var closeSettle: Job? = null

    /** False until the first [snapTo]: process recreation and cold start must
     *  land on the current model with no animation, so the host routes the
     *  first emission here instead of [retarget]. */
    var initialized = false
        private set

    private var idleTarget = 0f

    /** When a plan last had both [RetargetPlan.moved] and
     *  [RetargetPlan.recolored] set — the colour-first delay window's anchor
     *  ([HaloRingMath.geometryDelayMs]). */
    private var bothWindowStart: Long? = null

    /** Every launched animation job, so ambient entry can cancel jobs that
     *  are QUEUED but not yet inside an Animatable mutex (a delayed-geometry
     *  job that has not started cannot be cancelled any other way). */
    private val jobs = mutableListOf<Job>()

    /** Sub-this hero rotation deltas collapse to zero — the hero's own
     *  zero-motion guard, against float dust on grow/shrink round trips. */
    private companion object {
        const val HERO_MOTION_EPS = 0.02f
    }

    /**
     * Applies [inputs] instantly: cancels every animation job and snaps all
     * channels — slots AND the morph layers — to their level targets. The
     * ambient path (never freeze a mid-morph frame; never animate for a
     * wrist that is down) and the first render (process recreation shows the
     * current model, no theatre).
     */
    suspend fun snapTo(inputs: RingInputs) {
        jobs.forEach { it.cancel() }
        jobs.clear()
        applySnap(inputs)
        initialized = true
    }

    /**
     * Reconciles toward [inputs], launching per-channel animations on [scope].
     * Channels whose target did not change are LEFT ALONE — an in-flight tween
     * keeps its schedule rather than being retimed by an unrelated update
     * (recolouring slot 1 must not decelerate slot 0's re-slice). Equal inputs
     * therefore launch nothing at all, the engine-side half of the no-restart
     * contract (the host's snapshotFlow dedup is the other half).
     *
     * A level change plays its [HaloRingMath.morphFor] morph; the non-adjacent
     * jumps (Answer-pill page→feed, jump-home, self-heal) snap outright — they
     * happen under the opaque card and have no geometry story to tell.
     */
    fun retarget(scope: CoroutineScope, inputs: RingInputs, nowMs: Long) {
        jobs.removeAll { it.isCompleted }
        val morph = if (inputs.level == level) null else HaloRingMath.morphFor(level, inputs.level)
        if (inputs.level != level && morph == null) {
            jobs.forEach { it.cancel() }
            jobs.clear()
            jobs += scope.launch { applySnap(inputs) }
            level = inputs.level
            return
        }

        // The solid layer is hidden under the dashed layer at every non-PAGE
        // level AND through the whole close (until the settle swap reveals it
        // atomically) — this is where planRetarget's solidHidden becomes real.
        val solidHidden = inputs.level != RingLevel.PAGE ||
            morph == RingMorph.CLOSE || closeSettle?.isActive == true
        val plan = planFor(inputs.states, solidHidden)
        if (plan.moved && plan.recolored) bothWindowStart = nowMs
        // Colour lands first: geometry launched by this update (and by the
        // rest of the same 850ms burst) waits out the 220ms hold via the
        // tween's own delay, so it lives inside the Animatable mutex and a
        // snap or retarget can cancel it mid-hold.
        val geometry = tween<Float>(
            durationMillis = Halo.Motion.GeometryMs,
            delayMillis = HaloRingMath.geometryDelayMs(bothWindowStart, nowMs),
            easing = Halo.Motion.GeometryEasing,
        )

        when (morph) {
            RingMorph.OPEN -> beginOpen(scope, inputs, geometry)
            RingMorph.CLOSE -> beginClose(scope)
            RingMorph.GROW -> beginGrow(scope, inputs)
            RingMorph.SHRINK -> beginShrink(scope, inputs)
            null -> steadyHero(scope, inputs, geometry)
        }
        level = inputs.level

        plan.slots.forEachIndexed { k, sp ->
            val color = colorOf(sp.state)
            val slot = trackedSlots.getOrNull(k)
            when {
                slot == null -> {
                    // Born at its final geometry, pre-coloured, and only then
                    // faded in — beneath the settled ring (appearedAt feeds
                    // drawOrder) so it can't flash over its neighbours.
                    val born = Slot(sp, color, initialAlpha = 0f, appearedAt = nowMs)
                    trackedSlots += born
                    jobs += scope.launch { born.alpha.animateTo(sp.alpha, newArcFade()) }
                }
                sp.isNew -> {
                    // Reborn over a collapsed corpse: snap geometry and colour
                    // to the final pose (the S2 rule — never grow out of the
                    // death spot), then fade from the CURRENT alpha, which is
                    // mid-fade-out when a kill is undone within 300ms.
                    slot.appearedAt = nowMs
                    slot.commit(sp)
                    jobs += scope.launch {
                        slot.end.snapTo(sp.end)
                        slot.sweep.snapTo(sp.sweep)
                        slot.color.snapTo(color)
                        slot.alpha.animateTo(sp.alpha, newArcFade())
                    }
                }
                else -> {
                    if (sp.end != slot.targetEnd) {
                        jobs += scope.launch { slot.end.animateTo(sp.end, geometry) }
                    }
                    if (sp.sweep != slot.targetSweep) {
                        jobs += scope.launch { slot.sweep.animateTo(sp.sweep, geometry) }
                    }
                    if (color != colorOf(slot.targetState)) {
                        jobs += scope.launch { slot.color.animateTo(color, paint()) }
                    }
                    if (sp.alpha != slot.targetAlpha) {
                        jobs += scope.launch { slot.alpha.animateTo(sp.alpha, paint()) }
                    }
                    slot.commit(sp)
                }
            }
        }
        val idle = idleTargetFor(inputs)
        if (idle != idleTarget) {
            idleTarget = idle
            jobs += scope.launch { idleAlpha.animateTo(idle, paint()) }
        }
        initialized = true
    }

    /** Paint order for the draw (first = beneath): fresh arcs under the
     *  settled ring until their 1300ms expires. */
    fun drawOrder(nowMs: Long): List<Int> =
        HaloRingMath.drawOrder(trackedSlots.map { it.appearedAt }, nowMs)

    // ── Level morphs ────────────────────────────────────────────────────────

    /**
     * OPEN (page→list): the hero SNAPS onto the selected segment (deliberate
     * divergence from the design prototype, whose stale rotation would
     * visibly spin in) at solid weight, then thickens 9→10 while the dashed
     * layer splits 1→0 over it and the real solid layer fades under (the
     * solidHidden plan). Reopening mid-close finds the hero still shown and
     * retargets it smoothly instead — a snap there would teleport a visible
     * arc.
     */
    private fun beginOpen(scope: CoroutineScope, inputs: RingInputs, geometry: TweenSpec<Float>) {
        closeSettle?.cancel()
        closeSettle = null
        val n = inputs.states.size
        val sel = inputs.selectedIndex
        if (!heroShown) {
            if (sel >= 0) {
                val state = inputs.states[sel]
                val end = HaloRingMath.endAngle(sel, n)
                val sweep = HaloRingMath.sweepDegrees(n)
                heroState = state
                heroTargetEnd = end
                heroTargetSweep = sweep
                heroTargetAlpha = 1f
                jobs += scope.launch {
                    heroEnd.snapTo(end)
                    heroSweep.snapTo(sweep)
                    heroColor.snapTo(colorOf(state))
                    heroAlpha.snapTo(1f)
                    heroStroke.snapTo(Halo.Geo.RingStroke)
                    heroShown = true
                    heroStroke.animateTo(Halo.Geo.RingStrokeHero, dashMorph())
                }
            } else {
                // Empty scope / spawn card: the highlight has nothing to sit
                // on — the dashed layer (and idle circle) carry the screen.
                heroState = null
                heroTargetAlpha = 0f
                jobs += scope.launch {
                    heroAlpha.snapTo(0f)
                    heroStroke.snapTo(Halo.Geo.RingStrokeHero)
                    heroShown = true
                }
            }
        } else {
            jobs += scope.launch { heroStroke.animateTo(Halo.Geo.RingStrokeHero, dashMorph()) }
            retargetPlistHero(scope, inputs, geometry)
        }
        jobs += scope.launch {
            dashAlpha.snapTo(1f)
            merge.animateTo(0f, dashMorph())
        }
    }

    /**
     * CLOSE (list→page): the dashes fuse 0→1 while the hero thins back to
     * solid weight — the real solid layer stays hidden throughout — and at
     * the 1s settle the swap lands in ONE frame: solid alphas snap up, the
     * dashed layer and hero vanish. Merged dashes are pixel-identical to the
     * solid layer by construction, so the swap cannot flash.
     */
    private fun beginClose(scope: CoroutineScope) {
        jobs += scope.launch { merge.animateTo(1f, dashMorph()) }
        jobs += scope.launch { heroStroke.animateTo(Halo.Geo.RingStroke, dashMorph()) }
        val settle = scope.launch {
            awaitFrameMs(Halo.Motion.MorphSettleMs)
            // The atomic swap. Every write below lands before the next frame
            // draws. appearedAt clears with it: the swap is a settle, and a
            // stale "beneath" partition would otherwise ride the wall-clock
            // drawOrder read past its 1300ms until some later redraw.
            trackedSlots.forEach { slot ->
                val alpha = if (slot.targetState != null) 1f else 0f
                slot.alpha.snapTo(alpha)
                slot.targetAlpha = alpha
                slot.appearedAt = null
            }
            dashAlpha.snapTo(0f)
            heroShown = false
            heroAlpha.snapTo(0f)
            heroTargetAlpha = 0f
            closeSettle = null
        }
        closeSettle = settle
        jobs += settle
    }

    /**
     * GROW (list→feed): the hero expands BOTH ways into the full circle from
     * its ACTUAL pose — mid-rotation included — while the stroke eases 10→6
     * and the dashed layer fades out. The hero never fades; only its weight
     * and the resting .85 feed alpha ease.
     */
    private fun beginGrow(scope: CoroutineScope, inputs: RingInputs) {
        val target = HaloRingMath.growEndTarget(heroEnd.value, heroSweep.value)
        heroTargetEnd = target
        heroTargetSweep = 360f
        heroTargetAlpha = Halo.Geo.FeedRingAlpha
        val state = inputs.feedState
        if (state != null && state != heroState) {
            heroState = state
            jobs += scope.launch { heroColor.animateTo(colorOf(state), paint()) }
        }
        jobs += scope.launch { heroEnd.animateTo(target, growShrink()) }
        jobs += scope.launch { heroSweep.animateTo(360f, growShrink()) }
        jobs += scope.launch { heroStroke.animateTo(Halo.Geo.RingStrokeFeed, growShrink()) }
        jobs += scope.launch { heroAlpha.animateTo(Halo.Geo.FeedRingAlpha, growShrink()) }
        jobs += scope.launch { dashAlpha.animateTo(0f, paint()) }
    }

    /**
     * SHRINK (feed→list): the exact reverse — back off symmetrically, then
     * land on the selection's real segment (nearest coterminal correction, so
     * a scope that re-sliced under the feed still resolves with minimal
     * rotation). A selection that vanished with its whole scope has no
     * segment to land on: the hero fades while the upstream self-heal backs
     * the nav out.
     */
    private fun beginShrink(scope: CoroutineScope, inputs: RingInputs) {
        val n = inputs.states.size
        val sel = inputs.selectedIndex
        jobs += scope.launch { dashAlpha.animateTo(1f, paint()) }
        if (n == 0 || sel < 0) {
            heroTargetAlpha = 0f
            jobs += scope.launch { heroAlpha.animateTo(0f, paint()) }
            return
        }
        val sweep = HaloRingMath.sweepDegrees(n)
        val end = HaloRingMath.shrinkEndTarget(heroEnd.value, sweep, HaloRingMath.endAngle(sel, n))
        val state = inputs.states[sel]
        heroTargetEnd = end
        heroTargetSweep = sweep
        heroTargetAlpha = 1f
        if (state != heroState) {
            heroState = state
            jobs += scope.launch { heroColor.animateTo(colorOf(state), paint()) }
        }
        jobs += scope.launch { heroEnd.animateTo(end, growShrink()) }
        jobs += scope.launch { heroSweep.animateTo(sweep, growShrink()) }
        jobs += scope.launch { heroStroke.animateTo(Halo.Geo.RingStrokeHero, growShrink()) }
        jobs += scope.launch { heroAlpha.animateTo(1f, growShrink()) }
    }

    /** Same-level updates: the hero tracks selection and state. At PAGE there
     *  is nothing to track — mid-close the settle job owns the layers. */
    private fun steadyHero(scope: CoroutineScope, inputs: RingInputs, geometry: TweenSpec<Float>) {
        when (inputs.level) {
            RingLevel.PAGE -> Unit
            RingLevel.PLIST -> retargetPlistHero(scope, inputs, geometry)
            RingLevel.FEED -> {
                val state = inputs.feedState
                if (state != null && state != heroState) {
                    heroState = state
                    jobs += scope.launch { heroColor.animateTo(colorOf(state), paint()) }
                }
            }
        }
    }

    /**
     * The list highlight's retarget: fade against the spawn card (the one
     * sanctioned hero fade — a selection change, not a morph), colour to the
     * selected state, rotate shortest-path accumulated with the 2-session
     * backstep retrace, and ride the ring's OWN geometry tween (delay
     * included) on a re-slice so the highlight stays glued to its segment —
     * a plain step takes the quicker highlight rotation.
     */
    private fun retargetPlistHero(scope: CoroutineScope, inputs: RingInputs, geometry: TweenSpec<Float>) {
        val n = inputs.states.size
        val sel = inputs.selectedIndex
        val alpha = if (sel >= 0) 1f else 0f
        if (alpha != heroTargetAlpha) {
            heroTargetAlpha = alpha
            jobs += scope.launch { heroAlpha.animateTo(alpha, paint()) }
        }
        if (sel < 0) return
        val state = inputs.states[sel]
        if (state != heroState) {
            heroState = state
            jobs += scope.launch { heroColor.animateTo(colorOf(state), paint()) }
        }
        val sweep = HaloRingMath.sweepDegrees(n)
        val reslice = sweep != heroTargetSweep
        if (reslice) {
            heroTargetSweep = sweep
            jobs += scope.launch { heroSweep.animateTo(sweep, geometry) }
        }
        val delta = HaloRingMath.plistStepDelta(heroTargetEnd, HaloRingMath.endAngle(sel, n), inputs.stepDir)
        if (abs(delta) >= HERO_MOTION_EPS) {
            heroTargetEnd += delta
            val spec = if (reslice) geometry else highlight()
            val target = heroTargetEnd
            jobs += scope.launch { heroEnd.animateTo(target, spec) }
        }
    }

    // ── Snap ────────────────────────────────────────────────────────────────

    /** The full instant landing: layers AND slots at [inputs]' targets. */
    private suspend fun applySnap(inputs: RingInputs) {
        closeSettle = null
        val n = inputs.states.size
        val sel = inputs.selectedIndex
        when (inputs.level) {
            RingLevel.PAGE -> {
                merge.snapTo(1f)
                dashAlpha.snapTo(0f)
                heroShown = false
                heroAlpha.snapTo(0f)
                heroTargetAlpha = 0f
                heroState = null
            }
            RingLevel.PLIST -> {
                merge.snapTo(0f)
                dashAlpha.snapTo(1f)
                heroStroke.snapTo(Halo.Geo.RingStrokeHero)
                if (sel >= 0) {
                    val state = inputs.states[sel]
                    heroState = state
                    heroTargetEnd = HaloRingMath.endAngle(sel, n)
                    heroTargetSweep = HaloRingMath.sweepDegrees(n)
                    heroTargetAlpha = 1f
                    heroEnd.snapTo(heroTargetEnd)
                    heroSweep.snapTo(heroTargetSweep)
                    heroColor.snapTo(colorOf(state))
                    heroAlpha.snapTo(1f)
                } else {
                    heroTargetAlpha = 0f
                    heroAlpha.snapTo(0f)
                }
                heroShown = true
            }
            RingLevel.FEED -> {
                merge.snapTo(0f)
                dashAlpha.snapTo(0f)
                heroStroke.snapTo(Halo.Geo.RingStrokeFeed)
                heroTargetSweep = 360f
                heroSweep.snapTo(360f)
                if (sel >= 0) {
                    heroTargetEnd = HaloRingMath.growEndTarget(
                        HaloRingMath.endAngle(sel, n),
                        HaloRingMath.sweepDegrees(n),
                    )
                    heroEnd.snapTo(heroTargetEnd)
                }
                (inputs.feedState ?: heroState)?.let { state ->
                    heroState = state
                    heroColor.snapTo(colorOf(state))
                }
                heroTargetAlpha = Halo.Geo.FeedRingAlpha
                heroAlpha.snapTo(Halo.Geo.FeedRingAlpha)
                heroShown = true
            }
        }

        val plan = planFor(inputs.states, solidHidden = inputs.level != RingLevel.PAGE)
        plan.slots.forEachIndexed { k, sp ->
            val slot = trackedSlots.getOrNull(k)
            if (slot == null) {
                trackedSlots += Slot(sp, colorOf(sp.state), initialAlpha = sp.alpha, appearedAt = null)
            } else {
                slot.end.snapTo(sp.end)
                slot.sweep.snapTo(sp.sweep)
                slot.color.snapTo(colorOf(sp.state))
                slot.alpha.snapTo(sp.alpha)
                slot.commit(sp)
                // A snapped ring has no "new" arcs: everything is settled, so
                // nothing needs the beneath-the-ring paint order.
                slot.appearedAt = null
            }
        }
        idleTarget = idleTargetFor(inputs)
        idleAlpha.snapTo(idleTarget)
        // The burst the window was tracking is over — everything is settled.
        bothWindowStart = null
        level = inputs.level
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    /** Waits [ms] of FRAME time — cancellable, and driven by whatever clock
     *  the scope carries (the composition's on device, a manual one in JVM
     *  tests), unlike a wall-clock delay no test clock can advance. */
    private suspend fun awaitFrameMs(ms: Long) {
        val start = withFrameNanos { it }
        @Suppress("ControlFlowWithEmptyBody")
        while (withFrameNanos { it } - start < ms * 1_000_000L) {
        }
    }

    private fun planFor(shown: List<Halo.SessionState>, solidHidden: Boolean): RetargetPlan =
        HaloRingMath.planRetarget(
            prevPoses = trackedSlots.map { SlotPose(it.targetEnd, it.targetSweep) },
            prevStates = trackedSlots.map { it.targetState },
            states = shown,
            solidHidden = solidHidden,
        )

    /** The idle circle backs any IDLE_CIRCLE-styled empty scope, at every
     *  level; COLLAPSED (usage/settings) folds the ring away entirely. */
    private fun idleTargetFor(inputs: RingInputs): Float =
        if (inputs.states.isEmpty() && inputs.emptyStyle == EmptyRingStyle.IDLE_CIRCLE) 1f else 0f

    /** Vanishing slots (state null) blend to black — the AMOLED background —
     *  while their alpha fades, so the collapse tail is invisible twice over. */
    private fun colorOf(state: Halo.SessionState?): Color =
        state?.let(Halo::colorFor) ?: Halo.Palette.Background

    private fun <T> paint() = tween<T>(Halo.Motion.PaintMs, easing = Halo.Motion.PaintEasing)

    private fun newArcFade() = tween<Float>(Halo.Motion.NewArcFadeMs, easing = Halo.Motion.PaintEasing)

    private fun dashMorph() = tween<Float>(Halo.Motion.DashMorphMs, easing = Halo.Motion.GeometryEasing)

    private fun growShrink() = tween<Float>(Halo.Motion.GrowShrinkMs, easing = Halo.Motion.GeometryEasing)

    private fun highlight() = tween<Float>(Halo.Motion.HighlightMs, easing = Halo.Motion.HighlightEasing)
}
