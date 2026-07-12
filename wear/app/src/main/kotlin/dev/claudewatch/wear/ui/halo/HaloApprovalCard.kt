// Handoff §5 — the approval card, the highest-stakes surface: the agent is
// BLOCKED until this prompt is answered. Renders the [card] it is GIVEN —
// HaloApp resolves nav's targeted prompt (or the queue front) and keeps a
// resolved prompt composed while its result flash plays, so ui.permissionQueue
// is read here ONLY as the resolution signal (the ack drops the prompt from
// the queue), never to choose what to render. onAnswer(permissionId, behavior)
// answers with a canonical machine-readable behavior string — never option
// position or label wording. onDismiss(permissionId) is the local no-decision
// escape hatch, offered only after repeated failed answer attempts. onDone
// tells HaloApp the user is finished here: "decide later" (exits WITHOUT
// answering, queue intact) or the result flash completed (HaloApp then chains
// to the next queued prompt, or returns home on an empty queue).
package dev.claudewatch.wear.ui.halo

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Text
import dev.claudewatch.wear.BridgeViewModel
import dev.claudewatch.wear.ui.LOCAL_DISMISS_AFTER_FAILURES
import kotlinx.coroutines.delay

/**
 * Taps landing right after the card appears are ignored: the card can slide
 * in under a finger that was mid-gesture (queue chaining, feed banner tap)
 * and a swallowed tap here is an unintended permission DECISION.
 */
private const val ARM_DELAY_MS = 400L

/** How long the ✓/✕ result flash stays before chaining/exiting (handoff §5). */
private const val FLASH_MS = 1_400L

/** Deny label color from the handoff (`#B9B7AF`) — not a shared Halo token. */
private val DenyText = Color(0xFFB9B7AF)

@Composable
fun HaloApprovalCard(
    card: BridgeViewModel.PendingPermission,
    model: HaloModel,
    ui: BridgeViewModel.UiState,
    onAnswer: (String, String) -> Unit,
    onDismiss: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done by rememberUpdatedState(onDone)
    // What was tapped, remembered so the flash can announce the right outcome.
    // Keyed by prompt id so a recycled composition never leaks a previous
    // card's decision into this one's flash.
    var sent by remember(card.permissionId) { mutableStateOf<String?>(null) }
    var dismissedLocally by remember(card.permissionId) { mutableStateOf(false) }
    // The error of the last FAILED attempt on this card, scoped locally
    // because ui.decisionError is connection-global and sticky.
    var attemptError by remember(card.permissionId) { mutableStateOf<String?>(null) }
    val armAtMs = remember(card.permissionId) { SystemClock.uptimeMillis() + ARM_DELAY_MS }

    // Ack-gated: the prompt leaves the queue only when the ViewModel got an
    // authoritative outcome (2xx/404/dead token) — see sendDecision. That
    // removal is this card's resolution signal.
    val resolved = ui.permissionQueue.none { it.permissionId == card.permissionId }
    val inFlight = ui.decisionInFlightId == card.permissionId

    // A failed attempt (prompt still queued, POST no longer in flight, error
    // surfaced) releases the latched tap: keeping it would let a later
    // server-side resolution masquerade as this watch's delivered decision.
    // Idempotent snapshot write, same discipline as HaloApp's card hold.
    if (sent != null && !resolved && !inFlight && ui.decisionError != null) {
        attemptError = ui.decisionError
        sent = null
    }

    // Resolution drives the exit — but the ✓/✕ flash plays only for an
    // outcome THIS watch delivered: `sent` latched, no local dismiss, and the
    // resolving ack was a decision 2xx. A prompt resolved any other way
    // (answered from another device → 404, dead token → 401/403, timed out,
    // hook-abort push while our POST was mid-flight) leaves immediately —
    // flashing "Approved" for a decision that never landed would be a lie.
    // Latched at the resolving snapshot: decisionResult/decisionError are
    // global and sticky, so they are only meaningful in that exact frame.
    var resolutionSeen by remember(card.permissionId) { mutableStateOf(false) }
    var showFlash by remember(card.permissionId) { mutableStateOf(false) }
    if (resolved && !resolutionSeen) {
        resolutionSeen = true
        showFlash = sent != null && !dismissedLocally && !inFlight &&
            ui.decisionError == null && ui.decisionResult.isDecisionSuccess()
    }
    LaunchedEffect(resolved) {
        if (!resolved) return@LaunchedEffect
        if (showFlash) delay(FLASH_MS)
        done()
    }

    fun decide(behavior: String) {
        if (inFlight || resolved) return
        if (SystemClock.uptimeMillis() < armAtMs) return
        attemptError = null
        sent = behavior
        onAnswer(card.permissionId, behavior)
    }

    Crossfade(
        targetState = showFlash,
        label = "approvalResolve",
        modifier = modifier.fillMaxSize(),
    ) { flash ->
        if (flash) {
            ResultFlash(approved = sent != "deny")
        } else {
            DecisionLayer(
                card = card,
                model = model,
                ui = ui,
                inFlight = inFlight,
                attemptError = attemptError,
                resolved = resolved,
                onDecide = ::decide,
                onDismissLocally = {
                    dismissedLocally = true
                    onDismiss(card.permissionId)
                },
                onDecideLater = done,
            )
        }
    }
}

/** True only for an acked decision POST with an HTTP 2xx (see decisionResult). */
private fun String?.isDecisionSuccess(): Boolean =
    this?.removePrefix("decision:")?.toIntOrNull()?.let { it in 200..299 } == true

@Composable
private fun DecisionLayer(
    card: BridgeViewModel.PendingPermission,
    model: HaloModel,
    ui: BridgeViewModel.UiState,
    inFlight: Boolean,
    attemptError: String?,
    resolved: Boolean,
    onDecide: (String) -> Unit,
    onDismissLocally: () -> Unit,
    onDecideLater: () -> Unit,
) {
    // Canonical options only, keyed by behavior. A prompt offering only
    // allow-always still gets a filled Approve; the separate "always allow"
    // line exists only when it is genuinely a third choice.
    val denyOption = card.options.firstOrNull { it.behavior == "deny" }
    val approveOption = card.options.firstOrNull { it.behavior == "allow" }
        ?: card.options.firstOrNull { it.behavior == "allow-always" }
    val alwaysOption = card.options.firstOrNull { it.behavior == "allow-always" }
        .takeIf { approveOption?.behavior == "allow" }

    // Identity: which session is asking. Orphan prompts (session pruned or
    // never reported) fall back to the prompt's own resolved label.
    val session = model.sessions.firstOrNull { it.id == card.sessionId }
    val projectName = session?.projectName ?: card.sessionLabel
    val sessionTitle = session?.title ?: card.sessionLabel

    val buttonsEnabled = !inFlight && !resolved
    val statusLine = if (inFlight) "sending…" else attemptError

    // The failure path stacks extra rows (status line, local-dismiss hatch)
    // past what a round display fits, so the column scrolls — touch and
    // rotary — with the full circular safe inset as content padding: the
    // header and the bottom exits must never sit on the curve.
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // verticalScroll consumes every vertical drag (leftover deltas surface
    // through nested scroll, never back to the pointer system), which starves
    // the overlay's swipe-down detector in HaloApp. This restores that exit
    // from the leftovers: dragging down past the threshold with the column
    // already at its top is "decide later" — same ~60px-at-450 threshold.
    val decideLater by rememberUpdatedState(onDecideLater)
    val exitThresholdPx = with(LocalDensity.current) { 30.dp.toPx() }
    val overscrollExit = remember(exitThresholdPx) {
        object : NestedScrollConnection {
            private var overscroll = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) overscroll += available.y
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll > exitThresholdPx) decideLater()
                overscroll = 0f
                return Velocity.Zero
            }
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(overscrollExit)
            .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollState), focusRequester)
            .verticalScroll(scrollState)
            .padding(Halo.Geo.SafeInset),
    ) {
        Text(
            text = "PERMISSION",
            fontSize = Halo.Type.Caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            color = Halo.Palette.WaitingForYou,
        )
        if (model.waitingCount > 1) {
            Text(
                text = "${model.waitingCount} waiting",
                fontSize = Halo.Type.Min,
                color = Halo.Palette.TextFaint,
                modifier = Modifier.testTag("haloWaitingCount"),
            )
        }
        Spacer(Modifier.height(5.dp))

        // Identity pill: dot + project, then the session title (wraps).
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .background(Halo.Palette.Surface, RoundedCornerShape(Halo.Geo.RowRadius))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(Halo.Palette.WaitingForYou, CircleShape),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = projectName,
                    fontSize = Halo.Type.Min,
                    color = Halo.Palette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = sessionTitle,
                fontSize = Halo.Type.Body,
                fontWeight = FontWeight.Medium,
                color = Halo.Palette.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))

        // Command well: WHAT is being asked, centered mono, on the inset well.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .background(Halo.Palette.InsetWell, RoundedCornerShape(Halo.Geo.CardRadius))
                .border(1.dp, Halo.Palette.CommandWellBorder, RoundedCornerShape(Halo.Geo.CardRadius))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = card.requestSummary,
                fontSize = Halo.Type.MonoCommand,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = Halo.Palette.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("haloSummary"),
            )
            Text(
                text = "${card.toolName} · agent is blocked",
                fontSize = Halo.Type.Caption,
                color = Halo.Palette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))

        // Deny / Approve, both single-tap (the arm-delay is the only guard).
        // Approve is wider — it is the common action; Deny stays big enough
        // to never be a precision target.
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (denyOption != null) {
                DecisionPill(
                    label = "Deny",
                    filled = false,
                    weight = 0.42f,
                    enabled = buttonsEnabled,
                    tag = "haloDeny",
                    onClick = { onDecide(denyOption.behavior) },
                )
            }
            if (approveOption != null) {
                DecisionPill(
                    label = "Approve",
                    filled = true,
                    weight = if (denyOption != null) 0.58f else 1f,
                    enabled = buttonsEnabled,
                    tag = "haloApprove",
                    onClick = { onDecide(approveOption.behavior) },
                )
            }
        }

        if (alwaysOption != null) {
            TextAction(
                label = "always allow ›",
                color = Halo.Palette.TextFaint,
                tag = "haloAlwaysAllow",
                enabled = buttonsEnabled,
                onClick = { onDecide(alwaysOption.behavior) },
            )
        }

        if (statusLine != null) {
            Text(
                text = statusLine,
                fontSize = Halo.Type.Min,
                color = if (inFlight) Halo.Palette.UserEntry else Halo.Palette.Error,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("haloDecisionStatus"),
            )
        }
        // The no-decision escape hatch: only after repeated retryable answer
        // failures — a bridge that stopped answering must not wedge the app
        // behind an unanswerable card (see dismissPermissionLocally).
        if (ui.decisionFailureCount >= LOCAL_DISMISS_AFTER_FAILURES) {
            TextAction(
                label = "dismiss without answering",
                color = Halo.Palette.Error,
                tag = "haloDismissLocal",
                onClick = onDismissLocally,
            )
        }
        TextAction(
            label = "decide later ↓",
            color = Halo.Palette.TextFaint,
            tag = "haloDecideLater",
            onClick = onDecideLater,
        )
    }
}

/**
 * A small faint text link per the handoff, but with a TouchMin-tall hit box:
 * these stack directly on each other and one of them ("always allow") is a
 * permanent grant — a fat-finger between them is a decision-grade mis-tap.
 */
@Composable
private fun TextAction(
    label: String,
    color: Color,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = Halo.Geo.TouchMin)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag(tag),
    ) {
        Text(text = label, fontSize = Halo.Type.Min, color = color)
    }
}

/**
 * Decision pill. The handoff's 76px reference works out to 38dp, but its
 * ≥48dp touch-target rule is the binding constraint on this surface —
 * TouchMin tall; the full-width row split keeps both far past 48dp wide.
 */
@Composable
private fun RowScope.DecisionPill(
    label: String,
    filled: Boolean,
    weight: Float,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(weight)
            .height(Halo.Geo.TouchMin)
            .alpha(if (enabled) 1f else 0.55f)
            .clip(shape)
            .then(
                if (filled) {
                    Modifier.background(Halo.Palette.WaitingForYou, shape)
                } else {
                    Modifier.border(2.dp, Halo.Palette.OutlineButton, shape)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
    ) {
        Text(
            text = label,
            fontSize = Halo.Type.Title,
            fontWeight = FontWeight.Medium,
            color = if (filled) Halo.Palette.ApproveText else DenyText,
        )
    }
}

/** The 1.4s outcome flash: green ✓ approved, grey ✕ denied (handoff §5). */
@Composable
private fun ResultFlash(approved: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(Halo.Geo.SafeInset)
            .testTag("haloResultFlash"),
    ) {
        Text(
            text = if (approved) "✓" else "✕",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = if (approved) Halo.Palette.Running else Halo.Palette.TextSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (approved) "Approved" else "Denied",
            fontSize = Halo.Type.Title,
            fontWeight = FontWeight.Medium,
            color = Halo.Palette.TextPrimary,
        )
        Text(
            text = if (approved) "sent to bridge · agent resumed" else "agent notified",
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
