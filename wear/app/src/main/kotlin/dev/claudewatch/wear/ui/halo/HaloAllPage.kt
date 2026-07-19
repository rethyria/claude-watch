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
                text = haloCensusText(model.projectCount, model.sessionCount),
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

/**
 * The session half of the census — "no sessions" / "1 session" / "2
 * sessions". Extracted (issue #28) because the Tile's status headline must be
 * the EXACT wording the home ring's census uses: two near-identical strings
 * ("2 sessions" here, "2 active" there) would read as two different facts on
 * the same wrist. Pure, plain-JVM-tested via GlanceModelTest.
 */
internal fun sessionCensusText(sessionCount: Int): String =
    if (sessionCount == 0) "no sessions" else "$sessionCount ${plural(sessionCount, "session")}"

/**
 * The FULL census line the home ring's centerpiece renders — extracted from
 * the composable above (issue #28) so glanceables reuse it verbatim instead
 * of duplicating the string format. Zero sessions collapses to just
 * "no sessions": "0 projects · no sessions" would be counting nothing twice.
 */
internal fun haloCensusText(projectCount: Int, sessionCount: Int): String =
    if (sessionCount == 0) {
        sessionCensusText(0)
    } else {
        "$projectCount ${plural(projectCount, "project")} · ${sessionCensusText(sessionCount)}"
    }
