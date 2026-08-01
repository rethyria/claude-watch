// The live ring engine (Halo v2, epic #94 S4): per-slot Animatables driven by
// the S2 reconciliation plans. The engine owns MOTION only — HaloRingHost owns
// the trigger (snapshotFlow over a value-comparable inputs snapshot) and the
// draw; HaloRingMath owns every target number. Two invariants shape the API:
//
// - Plans are computed from the COMMITTED targets (the last plan applied),
//   never from mid-flight Animatable values: settled poses reproduce
//   bit-exactly, which is what keeps the S2 zero-motion contract exact — a
//   raw mid-flight sweep fed back as a prevPose would defeat the epsilon and
//   restart tweens over nothing (#96 carry-over).
// - Interruptions retarget via animateTo from the CURRENT value (Animatable's
//   mutex cancels the in-flight tween in place): the ring never snaps
//   mid-morph — except deliberately, in [snapTo], which is the ambient/first-
//   render path ("entering ambient cancels all jobs and snaps to targets").
//
// Pure Compose animation + Kotlin — no android.* — so the whole engine runs
// under a hand-rolled MonotonicFrameClock in JVM tests (HaloRingStateTest).
package dev.claudewatch.wear.ui.halo

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The animated state behind [HaloRingHost]. Slots are GROW-ONLY: a vanished
 * session's slot stays, collapsed onto its own start (sweep 0, alpha 0), so a
 * later plan for the same index reconciles against a real pose instead of a
 * hole — exactly the shape [HaloRingMath.planRetarget] expects.
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
     * The faint idle circle's opacity (0-session home). A channel of its own,
     * not a slot: it is a full circle in the Idle grey, and the empty-scope
     * styles fade between it and "nothing" rather than collapsing it.
     */
    val idleAlpha = Animatable(0f)

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

    /**
     * Applies the plan for [states] instantly: cancels every animation job and
     * snaps all channels to their targets. The ambient path (never freeze a
     * mid-morph frame; never animate for a wrist that is down) and the first
     * render (process recreation shows the current model, no theatre).
     */
    suspend fun snapTo(states: List<Halo.SessionState>, collapsed: Boolean, nowMs: Long) {
        jobs.forEach { it.cancel() }
        jobs.clear()
        val shown = if (collapsed) emptyList() else states
        val plan = planFor(shown)
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
        idleTarget = if (!collapsed && shown.isEmpty()) 1f else 0f
        idleAlpha.snapTo(idleTarget)
        // The burst the window was tracking is over — everything is settled.
        bothWindowStart = null
        initialized = true
    }

    /**
     * Reconciles toward [states], launching per-channel animations on [scope].
     * Channels whose target did not change are LEFT ALONE — an in-flight tween
     * keeps its schedule rather than being retimed by an unrelated update
     * (recolouring slot 1 must not decelerate slot 0's re-slice). Equal inputs
     * therefore launch nothing at all, the engine-side half of the no-restart
     * contract (the host's snapshotFlow dedup is the other half).
     */
    fun retarget(scope: CoroutineScope, states: List<Halo.SessionState>, collapsed: Boolean, nowMs: Long) {
        jobs.removeAll { it.isCompleted }
        val shown = if (collapsed) emptyList() else states
        val plan = planFor(shown)
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
        val idle = if (!collapsed && shown.isEmpty()) 1f else 0f
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

    private fun planFor(shown: List<Halo.SessionState>): RetargetPlan = HaloRingMath.planRetarget(
        prevPoses = trackedSlots.map { SlotPose(it.targetEnd, it.targetSweep) },
        prevStates = trackedSlots.map { it.targetState },
        states = shown,
        // The dashed-layer handoff that hides the solid ring is S7's morph
        // work; at PAGE level the solid layer is always the one on screen.
        solidHidden = false,
    )

    /** Vanishing slots (state null) blend to black — the AMOLED background —
     *  while their alpha fades, so the collapse tail is invisible twice over. */
    private fun colorOf(state: Halo.SessionState?): Color =
        state?.let(Halo::colorFor) ?: Halo.Palette.Background

    private fun <T> paint() = tween<T>(Halo.Motion.PaintMs, easing = Halo.Motion.PaintEasing)

    private fun newArcFade() = tween<Float>(Halo.Motion.NewArcFadeMs, easing = Halo.Motion.PaintEasing)
}
