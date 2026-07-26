// What survives of the v1 status ring: the OFFLINE screen's drained circle.
// The live per-page ring died with the v2 shell (HaloRingHost draws the halo
// from the root now, on the fixed channel); offline keeps a hollow grey
// circle because state colors can't be trusted while disconnected — and it
// stays a separate static composable, not a HaloRingHost mode, so the
// engine slice never has to model "the whole app is offline".
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke

/** Reference dimension all handoff px values are quoted against. */
internal const val HALO_REF_PX = 450f

/** The offline takeover's ring: same channel geometry, no state colors. */
@Composable
fun HaloDrainedRing(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val scale = size.minDimension / HALO_REF_PX
        drawCircle(
            color = Halo.Palette.Idle,
            radius = Halo.Geo.RingChannel * scale,
            style = Stroke(width = Halo.Geo.RingStroke * scale),
        )
    }
}
