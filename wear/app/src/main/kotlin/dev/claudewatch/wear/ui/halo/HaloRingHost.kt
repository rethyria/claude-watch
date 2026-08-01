// The ONE halo ring (Halo v2, epic #94 — S3 seam, S4 engine, S7 morphs): the
// root screen box's PERSISTENT bottom layer, drawing whatever regime the nav
// is in — the page's solid arcs, the list's dotted position ring with its
// solid hero highlight, or the feed's full-circle state ring — with the v2
// geometry (fixed channel radius, −94° anchor, 8.5°/8° gaps from
// HaloRingMath). The inner screens stopped painting opaque backgrounds in S7:
// the ring stays visible beneath them and the engine reconciles only what is
// actually on screen (RingInputs tracks the nav), instead of the S4-era waste
// of animating the page scope under an opaque list. Cards and takeover
// overlays still cover it — those are transient, and a prompt outranks
// choreography.
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag

/**
 * What the trigger flow dedupes on. A data class so equality is by VALUE:
 * a clock tick or a streaming feed line recomposes the app and rebuilds the
 * [RingInputs] snapshot, but the rebuilt snapshot compares equal, so
 * snapshotFlow never re-emits and the engine provably cannot be restarted by
 * either (the design prototype's clock-tick bug — the reason this iteration
 * exists). Since S7 the snapshot is the FULL engine world — level, selection,
 * step direction, feed state — so the same property covers the morphs.
 */
private data class RingHostInputs(
    val inputs: RingInputs,
    val ambient: Boolean,
)

/**
 * Draws the ring for the current nav regime, animating scope changes
 * (sessions appearing, dying, changing state; the glance-page collapse) and
 * the level morphs (dash split/merge, highlight rotation, grow/shrink).
 *
 * @param inputs the engine's whole world as one value-comparable snapshot
 *   ([HaloRingMath.ringInputs] derives it from nav + model).
 * @param engine injectable so instrumented tests can watch the Animatables;
 *   real callers keep the remembered default (a fresh engine per composition
 *   is what makes process recreation snap to the current model).
 */
@Composable
fun HaloRingHost(
    inputs: RingInputs,
    modifier: Modifier = Modifier,
    engine: HaloRingState = remember { HaloRingState() },
) {
    val ambient = LocalHaloAmbient.current
    // The trigger reads through rememberUpdatedState cells so the one
    // snapshotFlow (keyed on the engine alone) observes every recomposition's
    // values without ever restarting — a restart would re-emit an equal
    // snapshot and the collector could not tell it from a real change.
    val currentInputs by rememberUpdatedState(inputs)
    val currentAmbient by rememberUpdatedState(ambient)
    LaunchedEffect(engine) {
        val scope = this
        snapshotFlow { RingHostInputs(currentInputs, currentAmbient) }
            .collect { (inp, amb) ->
                if (amb || !engine.initialized) {
                    // Ambient: cancel everything and land on targets — never
                    // freeze a mid-morph frame, never animate for a wrist
                    // that is down (updates arriving while ambient snap too).
                    // First render: process recreation and cold start show
                    // the current model with no animation.
                    engine.snapTo(inp)
                } else {
                    engine.retarget(scope, inp, SystemClock.uptimeMillis())
                }
            }
    }
    Canvas(modifier = modifier.fillMaxSize().testTag("haloRingHost")) {
        val scale = size.minDimension / HALO_REF_PX
        // The FIXED channel centreline (Geo.RingChannel): every stroke weight
        // the v2 morphs animate through shares it, so the ring fattens and
        // thins in place instead of breathing radially.
        val radius = Halo.Geo.RingChannel * scale
        val solidStrokePx =
            (if (ambient) Halo.Geo.RingStrokeAmbient else Halo.Geo.RingStroke) * scale

        val idleAlpha = engine.idleAlpha.value
        if (idleAlpha > 0f) {
            drawCircle(
                color = (if (ambient) Halo.Palette.AmbientNeutral else Halo.Palette.Idle)
                    .copy(alpha = idleAlpha),
                radius = radius,
                style = Stroke(width = solidStrokePx),
            )
        }

        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)

        // ── Solid layer (the page ring; alpha-0 under the dashed layer) ─────
        for (k in engine.drawOrder(SystemClock.uptimeMillis())) {
            val slot = engine.slots[k]
            val sweep = slot.sweep.value
            val alpha = slot.alpha.value
            // Skip-draw below half a degree: a collapsed arc's round cap
            // would otherwise survive as a lit dot at its death spot.
            if (sweep < Halo.Geo.MinDrawSweepDeg || alpha <= 0f) continue
            val color = slotColor(slot, ambient)
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
                    width = solidStrokePx,
                    // Dying arcs drop the round cap: its dot would outlive
                    // the arc it belongs to.
                    cap = if (slot.targetState == null) StrokeCap.Butt else StrokeCap.Round,
                ),
            )
        }

        // ── Dashed layer (same slot geometry, dash paint) ───────────────────
        // Everything about it is the ONE merge fraction: interval, stroke and
        // alpha move together (HaloRingMath), so at fraction 1 it is
        // pixel-identical to the solid layer — round caps included — and the
        // close-swap cannot flash. Per-arc presence rides the geometry and
        // colour channels (a dying segment collapses blending to black), not
        // slot alpha, which belongs to the hidden solid layer here.
        val dashPresence = engine.dashAlpha.value
        if (dashPresence > 0f) {
            val mergeF = engine.merge.value
            val d = HaloRingMath.dashIntervals(mergeF)
            val dashStyle = Stroke(
                width = HaloRingMath.dashStroke(mergeF) * scale,
                cap = StrokeCap.Round,
                // A zero off-interval is a continuous stroke BY CONSTRUCTION:
                // draw it as one, so the merged endpoint never depends on how
                // the platform renders a degenerate dash pattern.
                pathEffect = if (d.offPx > 0f) {
                    PathEffect.dashPathEffect(floatArrayOf(d.onPx * scale, d.offPx * scale), 0f)
                } else {
                    null
                },
            )
            val layerAlpha = HaloRingMath.dashLayerAlpha(mergeF) * dashPresence
            for (slot in engine.slots) {
                val sweep = slot.sweep.value
                if (sweep < Halo.Geo.MinDrawSweepDeg) continue
                val color = slotColor(slot, ambient)
                drawArc(
                    color = color.copy(alpha = color.alpha * layerAlpha),
                    startAngle = slot.end.value - sweep,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = dashStyle,
                )
            }
        }

        // ── The hero (list highlight ≡ feed ring) ───────────────────────────
        if (engine.heroShown) {
            val alpha = engine.heroAlpha.value
            val sweep = engine.heroSweep.value
            if (alpha > 0f && sweep >= Halo.Geo.MinDrawSweepDeg) {
                val color = if (ambient) {
                    engine.heroState?.let(Halo::ambientColorFor) ?: Halo.Palette.AmbientNeutral
                } else {
                    engine.heroColor.value
                }
                drawArc(
                    color = color.copy(alpha = color.alpha * alpha),
                    startAngle = engine.heroEnd.value - sweep,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = engine.heroStroke.value * scale, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** Interactive draws read the animated colour (blending mid-flight); ambient
 *  is snapped, so the dimmed palette keys off the settled target state. */
private fun slotColor(slot: HaloRingState.Slot, ambient: Boolean) =
    slot.targetState.let { state ->
        if (ambient && state != null) Halo.ambientColorFor(state) else slot.color.value
    }
