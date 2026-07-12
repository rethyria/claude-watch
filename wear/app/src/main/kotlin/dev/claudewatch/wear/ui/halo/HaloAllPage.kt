// Page 0, the glance home: every session as a ring segment, the time in the
// middle, and a one-line census underneath. Handoff §1.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Text

@Composable
fun HaloAllPage(
    model: HaloModel,
    onTapCenter: () -> Unit,
    modifier: Modifier = Modifier,
    drained: Boolean = false,
) {
    Box(modifier = modifier.fillMaxSize()) {
        HaloRing(states = model.sessions.map { it.state }, drained = drained)
        HaloCenterpiece(onTap = onTapCenter, modifier = Modifier.testTag("haloCenter")) {
            Text(
                text = when {
                    model.sessionCount == 0 -> "no sessions"
                    else ->
                        "${model.projectCount} ${plural(model.projectCount, "project")} · " +
                            "${model.sessionCount} ${plural(model.sessionCount, "session")}"
                },
                fontSize = Halo.Type.Caption,
                color = Halo.Palette.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.testTag("haloCensus"),
            )
        }
    }
}

internal fun plural(n: Int, noun: String): String = if (n == 1) noun else "${noun}s"
