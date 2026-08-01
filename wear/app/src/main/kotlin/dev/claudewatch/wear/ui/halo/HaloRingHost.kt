// The ONE halo ring (Halo v2, epic #94 — S3 seam, S4 engine): the root screen
// box's bottom layer, drawing the current page scope with the v2 geometry
// (fixed channel radius, −94° anchor, 8.5°/8° gaps from HaloRingMath) — LIVE
// since S4: a HaloRingState of per-slot Animatables reconciles between
// targets, triggered by a snapshotFlow over a value-comparable inputs
// snapshot. Screens that must hide the ring (inner depths, cards, voice,
// spawn picker, offline) already paint opaque backgrounds, so ring visibility
// needs no per-screen wiring; the level morphs that rewire that are S7.
package dev.claudewatch.wear.ui.halo

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag

/**
 * What the trigger flow dedupes on. A data class so equality is by VALUE:
 * a clock tick or a streaming feed line recomposes the app and rebuilds the
 * states list, but the rebuilt snapshot compares equal, so snapshotFlow never
 * re-emits and the engine provably cannot be restarted by either (the design
 * prototype's clock-tick bug — the reason this iteration exists).
 */
private data class RingHostInputs(
    val states: List<Halo.SessionState>,
    val collapsed: Boolean,
    val ambient: Boolean,
)

/**
 * Draws the ring for the current page scope, animating between scope changes
 * (sessions appearing, dying, changing state; the glance-page collapse).
 *
 * @param states one entry per session, ring order = pager order (#95).
 * @param collapsed the depth-less glance pages (usage/settings) have no scope:
 *   the ring collapses away entirely. Distinct from an EMPTY scope (0-session
 *   home), whose faint idle circle keeps the layout readable instead of an
 *   empty black screen — the engine fades between the two styles.
 * @param engine injectable so instrumented tests can watch the Animatables;
 *   real callers keep the remembered default (a fresh engine per composition
 *   is what makes process recreation snap to the current model).
 */
@Composable
fun HaloRingHost(
    states: List<Halo.SessionState>,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    engine: HaloRingState = remember { HaloRingState() },
) {
    val ambient = LocalHaloAmbient.current
    // The trigger reads through rememberUpdatedState cells so the one
    // snapshotFlow (keyed on the engine alone) observes every recomposition's
    // values without ever restarting — a restart would re-emit an equal
    // snapshot and the collector could not tell it from a real change.
    val currentStates by rememberUpdatedState(states)
    val currentCollapsed by rememberUpdatedState(collapsed)
    val currentAmbient by rememberUpdatedState(ambient)
    LaunchedEffect(engine) {
        val scope = this
        snapshotFlow { RingHostInputs(currentStates, currentCollapsed, currentAmbient) }
            .collect { inputs ->
                val now = SystemClock.uptimeMillis()
                if (inputs.ambient || !engine.initialized) {
                    // Ambient: cancel everything and land on targets — never
                    // freeze a mid-morph frame, never animate for a wrist
                    // that is down (updates arriving while ambient snap too).
                    // First render: process recreation and cold start show
                    // the current model with no animation.
                    engine.snapTo(inputs.states, inputs.collapsed, now)
                } else {
                    engine.retarget(scope, inputs.states, inputs.collapsed, now)
                }
            }
    }
    Canvas(modifier = modifier.fillMaxSize().testTag("haloRingHost")) {
        val scale = size.minDimension / HALO_REF_PX
        // The FIXED channel centreline (Geo.RingChannel): every stroke weight
        // the v2 morphs animate through shares it, so the ring fattens and
        // thins in place instead of breathing radially.
        val radius = Halo.Geo.RingChannel * scale
        val strokePx =
            (if (ambient) Halo.Geo.RingStrokeAmbient else Halo.Geo.RingStroke) * scale

        val idleAlpha = engine.idleAlpha.value
        if (idleAlpha > 0f) {
            drawCircle(
                color = (if (ambient) Halo.Palette.AmbientNeutral else Halo.Palette.Idle)
                    .copy(alpha = idleAlpha),
                radius = radius,
                style = Stroke(width = strokePx),
            )
        }

        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        for (k in engine.drawOrder(SystemClock.uptimeMillis())) {
            val slot = engine.slots[k]
            val sweep = slot.sweep.value
            val alpha = slot.alpha.value
            // Skip-draw below half a degree: a collapsed arc's round cap
            // would otherwise survive as a lit dot at its death spot.
            if (sweep < Halo.Geo.MinDrawSweepDeg || alpha <= 0f) continue
            val state = slot.targetState
            // While ambient every channel is snapped, so the dimmed palette
            // keys off the (settled) target state; interactive draws read
            // the animated colour, blending mid-flight.
            val color = if (ambient && state != null) Halo.ambientColorFor(state) else slot.color.value
            drawArc(
                color = color.copy(alpha = color.alpha * alpha),
                // Arc k ENDS at the anchor minus k·step (HaloRingMath): the
                // first segment closes just left of midnight and index order
                // winds anticlockwise, while each arc keeps a positive sweep
                // so the round caps render identically to a clockwise ring.
                startAngle = slot.end.value - sweep,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(
                    width = strokePx,
                    // Dying arcs drop the round cap: its dot would outlive
                    // the arc it belongs to.
                    cap = if (state == null) StrokeCap.Butt else StrokeCap.Round,
                ),
            )
        }
    }
}
