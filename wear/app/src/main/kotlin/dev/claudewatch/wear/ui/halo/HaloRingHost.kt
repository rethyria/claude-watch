// The ONE halo ring (Halo v2, epic #94 S3): the root screen box's bottom
// layer, drawing the current page scope with the v2 geometry (fixed channel
// radius, −94° anchor, 8.5°/8° gaps from HaloRingMath). Static targets for
// now — the live engine that animates between them is S4, which replaces this
// file's internals while keeping the seam: one host, fed states, at the
// bottom of the z-order. Screens that must hide it (inner depths, cards,
// voice, spawn picker, offline) already paint opaque backgrounds, so ring
// visibility needs no per-screen wiring.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag

/**
 * Draws the ring for the current page scope.
 *
 * @param states one entry per session, ring order = pager order (#95).
 * @param collapsed the depth-less glance pages (usage/settings) have no scope:
 *   the ring collapses away entirely — nothing is drawn. Distinct from an
 *   EMPTY scope (0-session home), whose faint idle circle keeps the layout
 *   readable instead of an empty black screen.
 */
@Composable
fun HaloRingHost(
    states: List<Halo.SessionState>,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize().testTag("haloRingHost")) {
        if (collapsed) return@Canvas
        val scale = size.minDimension / HALO_REF_PX
        // The FIXED channel centreline (Geo.RingChannel): every stroke weight
        // the v2 morphs animate through shares it, so the ring fattens and
        // thins in place instead of breathing radially.
        val radius = Halo.Geo.RingChannel * scale
        val strokePx = Halo.Geo.RingStroke * scale

        if (states.isEmpty()) {
            drawCircle(
                color = Halo.Palette.Idle,
                radius = radius,
                style = Stroke(width = strokePx),
            )
            return@Canvas
        }

        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val n = states.size
        val sweep = HaloRingMath.sweepDegrees(n)
        states.forEachIndexed { k, state ->
            drawArc(
                color = Halo.colorFor(state),
                // Arc k ENDS at the anchor minus k·step (HaloRingMath): the
                // first segment closes just left of midnight and index order
                // winds anticlockwise, while each arc keeps a positive sweep
                // so the round caps render identically to a clockwise ring.
                startAngle = HaloRingMath.endAngle(k, n) - sweep,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}
