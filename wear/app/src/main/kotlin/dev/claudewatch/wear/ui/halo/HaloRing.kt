// The status ring: one arc segment per session, equal slices with small gaps,
// colored by session state. Pure Canvas — the segment count changes with live
// sessions, so nothing here may allocate paths per frame or assume a fixed
// display size (stroke and radius scale off the measured min dimension, per
// the handoff's 450px reference).
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** Reference dimension all handoff px values are quoted against. */
internal const val HALO_REF_PX = 450f

/** Gap between segments; also keeps a single-session ring visibly "a segment". */
private const val RING_GAP_DEGREES = 10f

/**
 * Draws the session ring.
 *
 * @param states one entry per session, ring order = list order.
 * @param ambient always-on variant: thin stroke, dimmed palette, minimal lit
 *   pixels (waiting keeps a dim terracotta so it survives the dim).
 * @param drained offline variant: same geometry, all segments hollow grey —
 *   state colors are withheld because they can't be trusted while disconnected.
 */
@Composable
fun HaloRing(
    states: List<Halo.SessionState>,
    modifier: Modifier = Modifier,
    ambient: Boolean = false,
    drained: Boolean = false,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val minDim = size.minDimension
        val scale = minDim / HALO_REF_PX
        val strokePx = (if (ambient) Halo.Geo.RingStrokeAmbient else Halo.Geo.RingStroke) * scale
        // Derived from the OUTER edge inward (see Geo.RingEdgeGap): the arc is
        // stroked centered on `radius`, so half the stroke has to come back off
        // to leave the gap the token actually names. Doing it this way keeps
        // the rim line fixed across the interactive/ambient stroke change and
        // makes clipping arithmetically impossible at any display size.
        val radius = (minDim / 2f) - (Halo.Geo.RingEdgeGap * scale) - strokePx / 2f

        if (states.isEmpty()) {
            // No sessions: a faint full circle keeps the layout readable
            // instead of an empty black screen.
            drawCircle(
                color = if (ambient) Halo.Palette.AmbientNeutral else Halo.Palette.Idle,
                radius = radius,
                style = Stroke(width = strokePx),
            )
            return@Canvas
        }

        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val slice = 360f / states.size
        val sweep = (slice - RING_GAP_DEGREES).coerceAtLeast(2f)
        states.forEachIndexed { index, state ->
            val color = when {
                drained -> if (ambient) Halo.Palette.AmbientNeutral else Halo.Palette.Idle
                ambient -> Halo.ambientColorFor(state)
                else -> Halo.colorFor(state)
            }
            drawArc(
                color = color,
                // Sessions wind ANTICLOCKWISE from 12 o'clock (user-directed,
                // 2026-07-18): segment i occupies the slice ENDING at
                // −90 − i·slice, so index order runs counter to Canvas's
                // clockwise-positive angles while each arc keeps its positive
                // sweep (the round stroke caps render identically).
                startAngle = -90f - (index + 1) * slice + RING_GAP_DEGREES / 2f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}
