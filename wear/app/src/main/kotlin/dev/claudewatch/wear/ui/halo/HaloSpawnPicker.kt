// Issue #56 — the spawn target picker: tapping the list's "+ new claude
// session" row no longer fires blind (the bridge used to spawn in ITS OWN
// cwd, invisible from the wrist); it opens this round-safe ScalingLazyColumn
// over the list, offering one entry per KNOWN project (spawn root derived in
// HaloModel: repoRoot beats cwd, a worktree offers its MAIN checkout) plus an
// explicit "no project" home entry (the "~" sentinel = the bridge user's
// home). Selection spawns and closes; cancelling without spawning is the
// trailing cancel row or the system back (the pull-down cancel died in the
// v3 vertical purge, #109 — vertical drags scroll this list and nothing
// else, so the once-passive "↓ cancel" label became the tappable escape).
// A PROJECT pager's spawn card spawns directly and never opens this picker
// (#130, user-directed) — only the All scope and vanished-project fallback
// arrive here, where the project is a genuine open question.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Text

@Composable
fun HaloSpawnPicker(
    model: HaloModel,
    onPick: (cwd: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // No preselect: project scopes spawn DIRECTLY from their card (#130,
    // user-directed) — this picker only ever opens when the project is a
    // genuine open question (the All scope, or a vanished project's card).
    val targets = model.spawnTargets
    // Top-anchor the list so the first AND second rows are both on screen at once
    // — same round-screen fix as DiscoveredBridgeList. ScalingLazyColumn's default
    // autoCentering reserves ~half a screen above item 0 so it can reach center,
    // which pushed the tappable rows into the lower half. Dropping autoCentering
    // (null, below) with an explicit top inset lets the "new session in…" caption
    // sit near the top and the first pick row directly beneath it.
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ScalingLazyColumn(
        state = listState,
        autoCentering = null,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        contentPadding = PaddingValues(
            start = Halo.Geo.SafeInset,
            end = Halo.Geo.SafeInset,
            top = Halo.Geo.ListTopInset,
            bottom = Halo.Geo.ListBottomInset,
        ),
        modifier = modifier
            .fillMaxSize()
            // rotaryScrollable installs the focus target itself; the
            // LaunchedEffect above claims it so the bezel works on entry.
            .rotaryScrollable(
                behavior = RotaryScrollableDefaults.behavior(listState),
                focusRequester = focusRequester,
            ),
    ) {
        item {
            Text(
                text = "new session in…",
                fontSize = Halo.Type.Caption,
                color = Halo.Palette.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
            )
        }

        targets.forEach { target ->
            // Namespaced away from the fixed home entry's key: a project
            // literally named "home" must not collide (duplicate lazy keys
            // crash at composition).
            item(key = "spawn-proj:${target.projectName}") {
                SpawnTargetRow(
                    title = target.projectName,
                    subtitle = target.root,
                    tag = "haloSpawnPick-${target.projectName}",
                    onPick = { onPick(target.root) },
                )
            }
        }

        // The explicit non-project option: "~" is the wire sentinel the
        // bridge resolves to ITS user's home — a neutral scratch session,
        // never "wherever the bridge happened to be started".
        item(key = "spawn:home") {
            SpawnTargetRow(
                title = "no project",
                subtitle = "home directory",
                tag = "haloSpawnPickHome",
                onPick = { onPick("~") },
            )
        }

        // The visible escape (v3): the old "↓ cancel" was a passive label for
        // the purged pull-down — the overlay must keep a non-gesture exit,
        // so the row itself now cancels (the system back is its twin).
        item(key = "spawn:cancel") {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Halo.Geo.TouchMin)
                    .clickable(onClick = onCancel)
                    .testTag("haloSpawnCancel")
                    .padding(top = 8.dp),
            ) {
                Text(
                    text = "cancel",
                    fontSize = Halo.Type.Min,
                    color = Halo.Palette.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * One pick target as a quiet pill (same geometry family as the session rows).
 */
@Composable
private fun SpawnTargetRow(
    title: String,
    subtitle: String,
    tag: String,
    onPick: () -> Unit,
) {
    val shape = RoundedCornerShape(Halo.Geo.RowRadius)
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Halo.Geo.TouchMin)
            .background(Halo.Palette.Surface, shape)
            .clickable(onClick = onPick)
            .testTag(tag)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = title,
            fontSize = Halo.Type.Title,
            fontWeight = FontWeight.Medium,
            color = Halo.Palette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            fontSize = Halo.Type.Min,
            color = Halo.Palette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
