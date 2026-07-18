// Handoff §3 — the session list under a page: pill rows with state dot,
// wrapping title and status subtitle; the all-sessions variant groups rows
// under project dividers. A horizontal swipe on a row swaps it for a quick-
// action strip (mode/compact/handover are design stubs; close kills the
// session). Scrolls via rotary. The list's scrollable consumes every vertical
// drag — InnerScreen's underlying back detector never fires here — so
// swipe-down-back is reimplemented via nested scroll: a pull past the
// threshold while the list is already at the top steps back. px values are at
// the 450 reference (≈ px/2 in dp, matching HaloTheme).
package dev.claudewatch.wear.ui.halo

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Text
import dev.claudewatch.wear.ui.halo.Halo.SessionState

/** Row-action swipe threshold ≈40px at the 450 reference (≈ px/2 in dp). */
private val ROW_SWIPE_THRESHOLD = 20.dp

/** A row swipe suppresses the synthetic tap that can follow it. */
private const val TAP_GUARD_MS = 300L

/** Back-swipe threshold ≈60px at the 450 reference, matching HaloApp's. */
private val BACK_SWIPE_THRESHOLD = 30.dp

/** Action-strip reveal: 250ms / 46px slide (handoff "Interactions & Motion"). */
private const val REVEAL_MS = 250
private const val REVEAL_SLIDE_FRACTION = 46f / HALO_REF_PX
private val RevealEasing = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)

@Composable
fun HaloSessionList(
    model: HaloModel,
    scope: ListScope,
    onOpenSession: (String) -> Unit,
    onKill: (String) -> Unit,
    onHide: (String) -> Unit,
    onSpawn: (agent: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (scope) {
        ListScope.All -> "all sessions"
        is ListScope.Project -> "${scope.name} · sessions"
    }
    // The all-sessions variant keeps project grouping (divider per project);
    // a project scope is a single unlabeled group. A project can vanish from
    // the model while this screen is up (HaloApp backs out a frame later) —
    // render the empty state rather than crash on a missing name.
    val groups: List<Pair<String?, List<HaloSession>>> = when (scope) {
        ListScope.All -> model.projects.map { it.name to it.sessions }
        is ListScope.Project ->
            model.projects.firstOrNull { it.name == scope.name }
                ?.let { listOf(null to it.sessions) } ?: emptyList()
    }

    // One strip open at a time; a session leaving the list (killed, resolved
    // into a different grouping) must not leave a ghost reveal on a reused
    // id, so the stored id only counts while it is still listed.
    var revealedId by remember { mutableStateOf<String?>(null) }
    val listedIds = groups.flatMap { it.second }.map { it.id }
    val effectiveRevealedId = revealedId?.takeIf { it in listedIds }
    var lastSwipeAtMs by remember { mutableLongStateOf(0L) }

    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Swipe-down-back, rebuilt from the drags the list rejects: the list's
    // scrollable consumes every vertical drag (even at its edge the leftover
    // goes to nested-scroll parents, never back to pointer input), so the
    // screen-level detector under this list is unreachable. Rotary bypasses
    // nested scroll, so bezel scrolling can't trigger this.
    val currentOnBack by rememberUpdatedState(onBack)
    val backThresholdPx = with(LocalDensity.current) { BACK_SWIPE_THRESHOLD.toPx() }
    val backConnection = remember(listState, backThresholdPx) {
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
                    if (!fired && pulled > backThresholdPx) {
                        fired = true
                        currentOnBack()
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
            .nestedScroll(backConnection)
            // rotaryScrollable installs the focus target itself; the
            // LaunchedEffect above claims it so the bezel works on entry.
            .rotaryScrollable(
                behavior = RotaryScrollableDefaults.behavior(listState),
                focusRequester = focusRequester,
            ),
    ) {
        item {
            Text(
                text = title,
                fontSize = Halo.Type.Caption,
                color = Halo.Palette.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
            )
        }

        if (groups.all { it.second.isEmpty() }) {
            item {
                Text(
                    text = "no sessions",
                    fontSize = Halo.Type.Body,
                    color = Halo.Palette.TextFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                )
            }
        }

        groups.forEach { (projectName, sessions) ->
            if (projectName != null) {
                item(key = "divider:$projectName") { ProjectDivider(projectName) }
            }
            sessions.forEach { session ->
                item(key = session.id) {
                    SessionRow(
                        session = session,
                        revealed = effectiveRevealedId == session.id,
                        onSwipe = {
                            lastSwipeAtMs = SystemClock.uptimeMillis()
                            revealedId = if (revealedId == session.id) null else session.id
                        },
                        onTap = {
                            if (SystemClock.uptimeMillis() - lastSwipeAtMs > TAP_GUARD_MS) {
                                if (revealedId == session.id) revealedId = null
                                else {
                                    revealedId = null
                                    onOpenSession(session.id)
                                }
                            }
                        },
                        onKill = {
                            revealedId = null
                            onKill(session.id)
                        },
                        onHide = {
                            revealedId = null
                            onHide(session.id)
                        },
                    )
                }
            }
        }

        // The one session-creating surface (spawnSession has no home in the
        // handoff's screens): scoped to the All list so project pages stay
        // pure mirrors of what the bridge reports.
        if (scope == ListScope.All) {
            item(key = "spawn") { SpawnRow(onSpawn = { onSpawn("claude") }) }
        }
    }
}

/** Trailing "new session" row; deliberately quieter than the session pills. */
@Composable
private fun SpawnRow(onSpawn: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Halo.Geo.TouchMin)
            .clickable(onClick = onSpawn)
            .testTag("haloSpawn"),
    ) {
        Text(
            text = "+ new claude session",
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.TextFaint,
            textAlign = TextAlign.Center,
        )
    }
}

/** All-sessions grouping header: 19px medium label over a 1px rule. */
@Composable
private fun ProjectDivider(name: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 1.dp)) {
        Text(
            text = name,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            color = Halo.Palette.TextFaint,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Halo.Palette.Divider),
        )
    }
}

private fun SessionState.isWaiting(): Boolean =
    this == SessionState.WAITING_PERM || this == SessionState.WAITING_Q

private fun statusLabel(state: SessionState): String = when (state) {
    SessionState.WAITING_PERM -> "waiting for permission"
    SessionState.WAITING_Q -> "has a question"
    SessionState.RUNNING -> "running"
    SessionState.IDLE -> "idle"
    SessionState.ERROR -> "error"
}

/**
 * One session as a 26px-radius pill. A horizontal swipe past ~40px flips the
 * pill's content to the quick-action strip in place (250ms slide+fade from
 * the swipe direction); a second swipe or a tap flips it back. The drag
 * detector only claims horizontal motion, so list scrolling and the screen's
 * swipe-down-back are unaffected.
 */
@Composable
private fun SessionRow(
    session: HaloSession,
    revealed: Boolean,
    onSwipe: () -> Unit,
    onTap: () -> Unit,
    onKill: () -> Unit,
    onHide: () -> Unit,
) {
    val waiting = session.state.isWaiting()
    val shape = RoundedCornerShape(Halo.Geo.RowRadius)
    var background = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = Halo.Geo.TouchMin)
        .background(
            if (waiting) Halo.Palette.WaitingForYou.copy(alpha = 0.12f) else Halo.Palette.Surface,
            shape,
        )
    if (waiting) background = background.border(1.dp, Halo.Palette.WaitingForYou, shape)

    Box(
        modifier = background
            .testTag("haloRow-${session.id}")
            .clickable(onClick = onTap)
            // keyed on the id: a recycled slot must not keep a stale detector
            // accumulating a previous row's drag total.
            .pointerInput(session.id) {
                val threshold = ROW_SWIPE_THRESHOLD.toPx()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (kotlin.math.abs(total) > threshold) onSwipe() },
                ) { change, dragAmount ->
                    total += dragAmount
                    change.consume()
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedContent(
            targetState = revealed,
            transitionSpec = {
                (slideInHorizontally(tween(REVEAL_MS, easing = RevealEasing)) { width ->
                    val slide = (width * REVEAL_SLIDE_FRACTION).toInt()
                    if (targetState) slide else -slide
                } + fadeIn(tween(REVEAL_MS, easing = RevealEasing)))
                    .togetherWith(fadeOut(tween(REVEAL_MS / 2)))
            },
            label = "rowReveal",
        ) { showActions ->
            if (showActions) {
                ActionStrip(external = session.external, onKill = onKill, onHide = onHide)
            } else {
                RowContent(session = session, waiting = waiting)
            }
        }
    }
}

@Composable
private fun RowContent(session: HaloSession, waiting: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(start = 2.dp)
                .size(6.dp) // 12px state dot
                .background(Halo.colorFor(session.state), CircleShape),
        )
        Spacer(modifier = Modifier.width(7.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title,
                fontSize = Halo.Type.Title,
                fontWeight = FontWeight.Medium,
                color = Halo.Palette.TextPrimary,
                // wraps by design; the cap only stops a pathological label
                // from swallowing the screen.
                maxLines = 3,
                lineHeight = 15.sp,
            )
            Text(
                text = statusLabel(session.state),
                fontSize = Halo.Type.Min,
                color = if (waiting) Halo.Palette.WaitingForYou else Halo.Palette.TextFaint,
                maxLines = 1,
            )
            // ⎇ branch (· wt for a worktree) — issue #54. Absent branch =
            // absent line (back-compat with a bridge that doesn't send it);
            // single faint ellipsized line, glanceability first.
            session.branchLabel?.let { label ->
                Text(
                    text = label,
                    fontSize = Halo.Type.Min,
                    color = Halo.Palette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Swipe-revealed quick actions. The trailing cell is wired; mode/compact/
 * handover ship as visible, disabled stubs per the handoff. The 50px circles
 * are the design's own size — smaller than the 48dp touch minimum, so each
 * button's hit target is its full quarter-width cell (weighted, so the row's
 * whole width is clickable): 48dp tall and as wide as four-across allows.
 *
 * The trailing cell is HONEST about what it can do (issue #53): a bridge-owned
 * PTY session is really killed (red ✕ → onKill → /v1/command kill), but an
 * EXTERNAL (hook-created, PTY-less) session the bridge does not own is only
 * HIDDEN from view until it speaks again — never a fake kill that pretends to
 * stop a process the bridge cannot — so it is labelled "hide" and routed to
 * onHide (local, no network).
 */
@Composable
private fun ActionStrip(external: Boolean, onKill: () -> Unit, onHide: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ActionButton(glyph = "◐", label = "mode", onClick = null, modifier = Modifier.weight(1f))
        ActionButton(glyph = "▤", label = "compact", onClick = null, modifier = Modifier.weight(1f))
        ActionButton(glyph = "⇄", label = "handover", onClick = null, modifier = Modifier.weight(1f))
        if (external) {
            ActionButton(
                glyph = "⊘",
                label = "hide",
                onClick = onHide,
                modifier = Modifier.weight(1f).testTag("haloRowClose"),
            )
        } else {
            ActionButton(
                glyph = "✕",
                label = "close",
                tint = Halo.Palette.Error,
                onClick = onKill,
                modifier = Modifier.weight(1f).testTag("haloRowClose"),
            )
        }
    }
}

@Composable
private fun ActionButton(
    glyph: String,
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tint: Color = Halo.Palette.TextPrimary,
) {
    val enabled = onClick != null
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .defaultMinSize(minHeight = Halo.Geo.TouchMin)
            .clickable(enabled = enabled, onClick = onClick ?: {})
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(25.dp) // 50px circle
                .background(Halo.Palette.Surface2, CircleShape),
        ) {
            Text(
                text = glyph,
                fontSize = 11.sp,
                color = if (enabled) tint else tint.copy(alpha = 0.35f),
            )
        }
        Text(
            text = label,
            fontSize = 8.5.sp, // 17px strip label per the handoff
            color = if (enabled) Halo.Palette.TextSecondary else Halo.Palette.TextFaint,
            maxLines = 1,
        )
    }
}
