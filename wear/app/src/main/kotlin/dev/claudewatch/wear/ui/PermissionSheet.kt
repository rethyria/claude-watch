// The approval sheet (issue #17): the single presenter for the per-session
// permission queue, rendered ABOVE the pager whenever prompts are pending.
//
// Inoculations against the confirmed watchOS/iOS defects:
//  - QUEUE, not a single slot: every pending prompt is retained; the front
//    (newest) is rendered with a "N waiting" indicator, and answering one
//    reveals the next — a second request never orphans the first.
//  - Answers are keyed to the RENDERED card's permissionId, captured at
//    render time — never "whatever is globally current".
//  - Ack-gated dismissal: the card stays until the ViewModel gets a 2xx ack
//    (or an authoritative 404); a failed POST keeps the card on screen with
//    the error surfaced instead of silently inverting an approval into a
//    10-minute auto-deny.
//  - Non-dismissable, single presenter: a full-screen opaque overlay that
//    consumes all touch input. Swiping does nothing — the sheet only leaves
//    when the queue empties. There is no second permission surface anywhere
//    else in the app.
//  - Option buttons come from the bridge's canonical behavior-keyed option
//    list; the decision sent is the option's machine-readable `behavior`,
//    never inferred from label wording or position.
package dev.claudewatch.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import dev.claudewatch.wear.BridgeViewModel

/**
 * Render the front of [queue] (newest-first; see
 * [BridgeViewModel.UiState.permissionQueue]) as a non-dismissable approval
 * card. [inFlightId] disables the buttons while that card's answer POST is in
 * flight; [error] is the surfaced failure of the last answer attempt.
 */
@Composable
fun PermissionSheet(
    queue: List<BridgeViewModel.PendingPermission>,
    inFlightId: String?,
    error: String?,
    onAnswer: (permissionId: String, behavior: String) -> Unit,
) {
    val prompt = queue.firstOrNull() ?: return
    val inFlight = inFlightId == prompt.permissionId
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchTheme.Background)
            // Consume every touch: nothing reaches the pager beneath, and no
            // gesture handler exists that could dismiss the sheet. It leaves
            // the screen only when the queue empties (2xx/404 ack or a
            // permission-cleared event) — swipe-dismissing an approval into
            // a 10-minute auto-deny is structurally impossible here.
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("permissionSheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Permission", fontSize = 13.sp, color = WatchTheme.ClaudeOrange)
            if (queue.size > 1) {
                Text(
                    "${queue.size - 1} more waiting",
                    fontSize = 10.sp,
                    color = WatchTheme.TextSecondary,
                    modifier = Modifier.testTag("permissionCount"),
                )
            }
            Spacer(Modifier.height(4.dp))

            // WHICH session is asking (live-testing feedback: the old card
            // never said).
            Text(
                prompt.sessionLabel,
                fontSize = 10.sp,
                color = WatchTheme.TextSecondary,
                maxLines = 1,
                modifier = Modifier.testTag("permissionSession"),
            )
            Text(
                prompt.toolName,
                fontSize = 13.sp,
                color = WatchTheme.Command,
                modifier = Modifier.testTag("permissionTool"),
            )
            // WHAT is being asked: the actual command/file/pattern.
            Text(
                prompt.requestSummary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = WatchTheme.ClaudeOrange,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("permissionSummary"),
            )
            Spacer(Modifier.height(6.dp))

            if (error != null && !inFlight) {
                Text(
                    error,
                    fontSize = 10.sp,
                    color = WatchTheme.Error,
                    modifier = Modifier.testTag("permissionError"),
                )
                Spacer(Modifier.height(4.dp))
            }
            if (inFlight) {
                Text(
                    "sending…",
                    fontSize = 10.sp,
                    color = WatchTheme.TextSecondary,
                    modifier = Modifier.testTag("permissionSending"),
                )
                Spacer(Modifier.height(4.dp))
            }

            // One button per canonical option, in bridge order, keyed and
            // answered by machine-readable behavior. The rendered card's
            // permissionId is captured HERE, so an answer can only ever land
            // on the request the user was looking at.
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                prompt.options.forEach { option ->
                    Chip(
                        onClick = { if (!inFlight) onAnswer(prompt.permissionId, option.behavior) },
                        enabled = !inFlight,
                        label = {
                            Text(
                                text = option.label.ifEmpty { option.behavior },
                                fontSize = 12.sp,
                                color = if (option.behavior == "deny") WatchTheme.Error else WatchTheme.Success,
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("permissionOption-${option.behavior}"),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
