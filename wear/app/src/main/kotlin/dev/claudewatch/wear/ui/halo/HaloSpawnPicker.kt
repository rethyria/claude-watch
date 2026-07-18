// Issue #56 — the spawn target picker: tapping the list's "+ new claude
// session" row no longer fires blind (the bridge used to spawn in ITS OWN
// cwd, invisible from the wrist); it opens this round-safe ScalingLazyColumn
// over the list, offering one entry per KNOWN project (spawn root derived in
// HaloModel: repoRoot beats cwd, a worktree offers its MAIN checkout) plus an
// explicit "no project" home entry (the "~" sentinel = the bridge user's
// home). Selection spawns and closes; swipe-down cancels without spawning.
// The cancel gesture is rebuilt from nested-scroll leftovers exactly like
// HaloSessionList's swipe-down-back: the picker's scrollable consumes every
// vertical drag, so a plain pointer detector underneath would never fire —
// and the caller must disable the API 31+ stretch-overscroll around this
// composable or the stretch eats every delta after the first overpull frame.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Text

/** Cancel-swipe threshold ≈60px at the 450 reference, matching HaloApp's. */
private val CANCEL_SWIPE_THRESHOLD = 30.dp

@Composable
fun HaloSpawnPicker(
    model: HaloModel,
    onPick: (cwd: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Swipe-down-cancel from the drags the picker's list rejects — the same
    // top-of-list pull-past-threshold contract as HaloSessionList's back.
    val currentOnCancel by rememberUpdatedState(onCancel)
    val cancelThresholdPx = with(LocalDensity.current) { CANCEL_SWIPE_THRESHOLD.toPx() }
    val cancelConnection = remember(listState, cancelThresholdPx) {
        object : NestedScrollConnection {
            // Unconsumed pull-down so far; any real scroll or upward motion
            // resets it, so only a continuous top-of-list pull counts.
            private var pulled = 0f
            private var fired = false

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y > 0f && !listState.canScrollBackward) {
                    pulled += available.y
                    if (!fired && pulled > cancelThresholdPx) {
                        fired = true
                        currentOnCancel()
                    }
                } else if (consumed.y != 0f || available.y < 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                fired = false
                return Velocity.Zero
            }
        }
    }

    ScalingLazyColumn(
        state = listState,
        autoCentering = AutoCenteringParams(itemIndex = 0),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        contentPadding = PaddingValues(horizontal = Halo.Geo.SafeInset),
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(cancelConnection)
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

        model.spawnTargets.forEach { target ->
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

        item {
            Text(
                text = "↓ cancel",
                fontSize = Halo.Type.Min,
                color = Halo.Palette.TextFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

/** One pick target as a quiet pill (same geometry family as the session rows). */
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
            color = Halo.Palette.TextFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
