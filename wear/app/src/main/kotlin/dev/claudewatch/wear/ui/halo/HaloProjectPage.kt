// Pages 1..n: one project's sessions on the ring (a single session reads as a
// near-full ring thanks to the segment gap), project name under the time, and
// the "↑ sessions" drill hint above the page dots. Handoff §2.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text

@Composable
fun HaloProjectPage(
    project: HaloProject,
    onTapCenter: () -> Unit,
    modifier: Modifier = Modifier,
    drained: Boolean = false,
) {
    Box(modifier = modifier.fillMaxSize()) {
        HaloRing(states = project.sessions.map { it.state }, drained = drained)
        HaloCenterpiece(onTap = onTapCenter) {
            Text(
                text = project.name,
                fontSize = Halo.Type.Title,
                color = Halo.Palette.TextSecondary,
                textAlign = TextAlign.Center,
                // Folder names are unbounded; the center column is not.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "↑ sessions",
            fontSize = Halo.Type.Min,
            color = Halo.Palette.TextFaint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp), // clears the page dots underneath
        )
    }
}
