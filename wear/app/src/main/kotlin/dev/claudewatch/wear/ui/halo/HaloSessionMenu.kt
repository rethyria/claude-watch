// The session-actions menu (issue #114, from the user's on-wrist verdict on
// the v2 action arc: "too small but even so take up too much space"): tapping
// a pager card opens THIS instead of the feed. Every action the arc carried
// becomes a full-width finger-sized row — the four stubs, kept visible but
// dead at the arc's 0.35 alpha, then the live close with its exact semantics
// (✕ kill wherever the bridge can really end the session, its own PTY or an
// ACP session via the adapter's close frame #88; ⊘ honest hide for a
// hook-observed one it cannot stop, #53) LAST (#116 user feedback:
// destructive at the bottom of the list) — and the feed moved behind the
// menu's own "open feed" row, first because it is the old tap's destination
// and the most-travelled path. The menu is a pass-through launcher, not a resting
// place: swipe-right / system back returns to the pager card that summoned
// it, an action tap closes it (the caller's job), and the feed's back skips
// it entirely. Round-safe ScalingLazyColumn over the spawn picker's idiom;
// no vertical navigation, no new exit paths (gesture model v3, #109).
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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

/** Back-swipe threshold ≈60px at the 450 reference (the app-wide gesture unit). */
private const val BACK_SWIPE_FRACTION = 60f / HALO_REF_PX

/** The stubs' reduced alpha — the v1 strip's and the arc's treatment, kept. */
private const val STUB_ALPHA = 0.35f

@Composable
fun HaloSessionMenu(
    session: HaloSession,
    onOpenFeed: () -> Unit,
    onKill: () -> Unit,
    onHide: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Top-anchored like the spawn picker: autoCentering would bury the first
    // rows in the lower half of the round face (the standing round-screen fix).
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // The swipe detector lives in a pointerInput(Unit) that never restarts;
    // this keeps it calling the current back, not a stale capture.
    val back by rememberUpdatedState(onBack)
    val systemBackInFlight = LocalHaloSystemBackInFlight.current

    Box(
        modifier = modifier
            .fillMaxSize()
            // Swipe right = back to the pager card that summoned the menu
            // (the same one-step the system back routes). Horizontal only —
            // vertical drags belong to the list's scroll (v3: vertical is
            // never navigation) — and CONSUMED, so nothing spills through to
            // the pager beneath; a leftward swipe is deliberately a no-op
            // (the menu sits off the horizontal axis: only backward leaves it).
            .pointerInput(Unit) {
                val threshold = size.width * BACK_SWIPE_FRACTION
                // #109: a system back gesture's edge swipe reads here as a
                // rightward drag — stand down; the root handler's completion
                // is the one back (SystemBackDragClaim).
                val claim = SystemBackDragClaim(systemBackInFlight)
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        claim.start()
                        total = 0f
                    },
                    onDragEnd = {
                        if (!claim.owns && total > threshold) back()
                    },
                ) { change, dragAmount ->
                    claim.update()
                    total += dragAmount
                    change.consume()
                }
            }
            .testTag("haloSessionMenu"),
    ) {
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
            modifier = Modifier
                .fillMaxSize()
                // rotaryScrollable installs the focus target itself; the
                // LaunchedEffect above claims it so the bezel works on entry.
                .rotaryScrollable(
                    behavior = RotaryScrollableDefaults.behavior(listState),
                    focusRequester = focusRequester,
                ),
        ) {
            // The header names WHOSE actions these are: a menu covers the
            // card that carried the title. Subheading role → TextSecondary.
            item {
                Text(
                    text = session.title,
                    fontSize = Halo.Type.Caption,
                    color = Halo.Palette.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                )
            }

            // "Open feed" leads: it is where the card's tap used to go — the
            // most-travelled action earns the natural first-thumb position.
            item(key = "menu:feed") {
                MenuRow(glyph = "›", label = "open feed", tag = "haloMenuFeed", onClick = onOpenFeed)
            }

            // The stubs, kept as disabled rows rather than dropped: the arc
            // showed this roadmap and the menu has the room the arc lacked —
            // dropping them would silently shrink the visible surface beyond
            // #114's ask.
            item(key = "menu:model") {
                MenuRow(glyph = "◇", label = "model", tag = "haloMenu-model", onClick = null)
            }
            item(key = "menu:mode") {
                MenuRow(glyph = "◐", label = "mode", tag = "haloMenu-mode", onClick = null)
            }
            item(key = "menu:compact") {
                MenuRow(glyph = "▤", label = "compact", tag = "haloMenu-compact", onClick = null)
            }
            item(key = "menu:handover") {
                MenuRow(glyph = "⇄", label = "handover", tag = "haloMenu-handover", onClick = null)
            }

            // The live close, honest either way (issue #53): the red ✕ only
            // where the bridge can REALLY end the session — its own PTY, or
            // an ACP session through the adapter's close frame (#88) — and ⊘,
            // the local hide that never claims to have stopped anything, for
            // a hook-observed one. LAST, below even the stubs (#116 user
            // feedback): the one row that ends something never sits where a
            // thumb reaches first. The stable "haloRowClose" testTag rides
            // along from the arc (and the row strip before it) on purpose:
            // every close-semantics test finds it unmoved.
            item(key = "menu:close") {
                if (session.kind == "acp" || !session.external) {
                    MenuRow(
                        glyph = "✕",
                        label = "end session",
                        tag = "haloRowClose",
                        onClick = onKill,
                        glyphTint = Halo.Palette.Error,
                    )
                } else {
                    MenuRow(glyph = "⊘", label = "hide session", tag = "haloRowClose", onClick = onHide)
                }
            }
        }
    }
}

/**
 * One action as a full-width pill (the session rows' geometry family):
 * ≥48dp tall — the whole point of the menu is actions sized for a fingertip,
 * where the arc's 33dp cells could not reach the minimum without overlapping.
 * The glyph is its own Text node so a tint (the ✕'s red) stays the glyph's,
 * and so glyph-matching tests keep their exact-text matchers. Disabled rows
 * keep a (dead) click action for the semantics tree but dim whole — pill,
 * glyph and label together — at the arc's stub alpha.
 */
@Composable
private fun MenuRow(
    glyph: String,
    label: String,
    tag: String,
    onClick: (() -> Unit)?,
    glyphTint: Color = Halo.Palette.TextPrimary,
) {
    val enabled = onClick != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Halo.Geo.TouchMin)
            // alpha BEFORE background: the layer dims everything drawn after
            // it in the chain, so a stub's pill fades with its text.
            .alpha(if (enabled) 1f else STUB_ALPHA)
            .background(Halo.Palette.Surface, RoundedCornerShape(Halo.Geo.RowRadius))
            .clickable(enabled = enabled, onClick = onClick ?: {})
            .testTag(tag)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = glyph,
            fontSize = Halo.Type.Body,
            color = glyphTint,
            // A fixed glyph cell keeps the labels left-aligned as a column.
            modifier = Modifier.width(18.dp),
        )
        Text(
            text = label,
            fontSize = Halo.Type.Body,
            fontWeight = FontWeight.Medium,
            color = Halo.Palette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
