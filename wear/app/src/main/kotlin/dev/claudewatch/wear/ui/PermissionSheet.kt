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
//    else in the app. The ONE escape hatch — offered only after repeated
//    failed answer attempts (bridge unreachable, or restarted with our token
//    now dead) — is an explicit "Dismiss" button that sends NO decision: the
//    bridge's own timeout owns the real outcome, so nothing is ever silently
//    approved or denied. Without it, a dead bridge would wedge the whole app
//    (pairing page included) behind an unanswerable sheet forever.
//  - Option buttons come from the bridge's canonical behavior-keyed option
//    list; the decision sent is the option's machine-readable `behavior`,
//    never inferred from label wording or position.
//
// AskUserQuestion prompts (issue #18) render on the SAME sheet with the same
// queue/ack/dismissal semantics, but with a question card body instead of the
// behavior buttons: EVERY question of the payload (the watchOS card silently
// answered only the first), each with its per-question option chips and a
// free-text field, and one Send that goes out only once every question has an
// answer — sent as a POSITIONAL array aligned with the payload's question
// order (the bridge's /v1 array form), because keying by question text would
// collapse duplicate-text questions into one answer and permanently disable
// Send. On the question card, the failed-send error surfaces NEXT TO the Send
// chip the user just tapped, not only at the top of the (scrolled-away)
// sheet.
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import dev.claudewatch.shared.protocol.AskUserQuestion
import dev.claudewatch.wear.BridgeViewModel

/** Failed answer attempts before the sheet offers the no-decision local dismiss. */
const val LOCAL_DISMISS_AFTER_FAILURES = 2

/**
 * Render the front of [queue] (newest-first; see
 * [BridgeViewModel.UiState.permissionQueue]) as a non-dismissable approval
 * card. [inFlightId] disables the buttons while that card's answer POST is in
 * flight; [error] is the surfaced failure of the last answer attempt. Once
 * [failureCount] consecutive answers have failed retryably
 * ([LOCAL_DISMISS_AFTER_FAILURES]), [onDismiss] is offered as an explicit
 * escape hatch that drops the card locally WITHOUT sending a decision — see
 * [BridgeViewModel.dismissPermissionLocally].
 */
@Composable
fun PermissionSheet(
    queue: List<BridgeViewModel.PendingPermission>,
    inFlightId: String?,
    error: String?,
    failureCount: Int,
    onAnswer: (permissionId: String, behavior: String) -> Unit,
    onAnswerQuestions: (permissionId: String, answers: List<String>) -> Unit = { _, _ -> },
    onDismiss: (permissionId: String) -> Unit,
) {
    val prompt = queue.firstOrNull() ?: return
    val inFlight = inFlightId == prompt.permissionId
    val isQuestion = prompt.questions.isNotEmpty()
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
            Text(
                if (isQuestion) "Question" else "Permission",
                fontSize = 13.sp,
                color = WatchTheme.ClaudeOrange,
            )
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
            if (!isQuestion) {
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
            }
            Spacer(Modifier.height(6.dp))

            // On the question card the error/in-flight status renders inside
            // QuestionCardBody, right above the Send chip — the card is tall
            // enough to scroll, and a failed send must be visible where the
            // user is actually looking (the bottom, where they tapped Send),
            // not at the scrolled-away top of the sheet.
            if (!isQuestion) {
                SendStatus(error = error, inFlight = inFlight)
            }

            if (isQuestion) {
                // AskUserQuestion body: every question of the payload with its
                // own options and free-text answer. The rendered card's
                // permissionId is captured at render time, exactly like the
                // behavior buttons below.
                QuestionCardBody(
                    prompt = prompt,
                    inFlight = inFlight,
                    error = error,
                    onSend = { answers -> onAnswerQuestions(prompt.permissionId, answers) },
                )
            } else {
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
            }
            // The escape hatch: only after repeated failed answers, and never
            // while one is in flight. Sends NO decision — it merely drops the
            // card locally so an unreachable/restarted bridge can't wedge the
            // whole app; the bridge's own timeout owns the real outcome.
            if (!inFlight && failureCount >= LOCAL_DISMISS_AFTER_FAILURES) {
                Spacer(Modifier.height(8.dp))
                Chip(
                    onClick = { onDismiss(prompt.permissionId) },
                    label = {
                        Text(
                            "Dismiss (sends no answer)",
                            fontSize = 11.sp,
                            color = WatchTheme.TextSecondary,
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("permissionDismiss"),
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The last answer attempt's failure ([error]) and the in-flight marker,
 * rendered wherever the card's action buttons are so a failed send is visible
 * next to what the user just tapped.
 */
@Composable
private fun SendStatus(error: String?, inFlight: Boolean) {
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
}

/**
 * The AskUserQuestion card body: EVERY question of [prompt]'s payload, each
 * with its per-question option chips (single-select replaces, `multiSelect`
 * toggles) and a free-text field, plus one Send that fires only when every
 * question has an answer. Completeness is gated BY INDEX (each question
 * position must have an answer), never by distinct question text: a payload
 * with duplicate question texts must still be fully answerable, or the
 * swipe-immune sheet would deadlock with Send permanently disabled.
 *
 * Answer state is remembered PER permissionId: a failed POST (the ViewModel
 * keeps the card queued and surfaces the [error] right above Send) restores
 * the card with the user's picks intact for retry, while a different prompt
 * fronting the queue starts clean. Chips and free text are mutually exclusive
 * per question — tapping a chip clears typed text, typing clears the chip
 * selection — so the answer that gets sent is always the one visibly active.
 */
@Composable
private fun QuestionCardBody(
    prompt: BridgeViewModel.PendingPermission,
    inFlight: Boolean,
    error: String?,
    onSend: (List<String>) -> Unit,
) {
    // Selected option labels per question index (label order is restored from
    // the question's option list on send, so multi-select answers are stable
    // regardless of tap order).
    val selections = remember(prompt.permissionId) { mutableStateMapOf<Int, Set<String>>() }
    val freeText = remember(prompt.permissionId) { mutableStateMapOf<Int, String>() }

    fun answerFor(index: Int, question: AskUserQuestion): String? {
        val typed = freeText[index]?.trim().orEmpty()
        if (typed.isNotEmpty()) return typed
        val selected = selections[index].orEmpty()
        if (selected.isEmpty()) return null
        return question.options.map { it.label }.filter { it in selected }.joinToString(", ")
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        prompt.questions.forEachIndexed { index, question ->
            Text(
                question.header ?: "Q${index + 1}",
                fontSize = 10.sp,
                color = WatchTheme.TextSecondary,
                modifier = Modifier.testTag("questionHeader-$index"),
            )
            Text(
                question.question,
                fontSize = 12.sp,
                color = WatchTheme.Command,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("questionText-$index"),
            )
            question.options.forEach { option ->
                val selected = option.label in selections[index].orEmpty()
                Chip(
                    onClick = {
                        if (inFlight) return@Chip
                        val current = selections[index].orEmpty()
                        selections[index] = when {
                            !question.multiSelect -> setOf(option.label)
                            selected -> current - option.label
                            else -> current + option.label
                        }
                        // A picked option is the answer: drop typed text.
                        freeText.remove(index)
                    },
                    enabled = !inFlight,
                    label = {
                        Text(
                            text = (if (selected) "✓ " else "") + option.label,
                            fontSize = 12.sp,
                            color = if (selected) WatchTheme.ClaudeOrange else WatchTheme.Command,
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("questionOption-$index-${option.label}"),
                )
            }
            // Free-text answer: always available, even for a question whose
            // options failed to parse — no question is ever unanswerable.
            BasicTextField(
                value = freeText[index].orEmpty(),
                onValueChange = { typed ->
                    freeText[index] = typed
                    // Typing takes over from any picked option.
                    if (typed.isNotBlank()) selections.remove(index)
                },
                enabled = !inFlight,
                singleLine = true,
                textStyle = TextStyle(color = WatchTheme.Command, fontSize = 12.sp),
                cursorBrush = SolidColor(WatchTheme.Command),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WatchTheme.FieldBackground)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .testTag("questionFreeText-$index"),
            )
            Spacer(Modifier.height(4.dp))
        }

        // One answer per question POSITION, aligned with the payload's
        // question order (the bridge's /v1 array form). Gated by index, not
        // by question text: duplicate question texts are model-generated
        // content the client cannot rule out, and a text-keyed map would
        // collapse them so `complete` could never be reached. Send stays
        // disabled until every position is answered: a partial answer set
        // would silently drop the unanswered questions.
        val answersByIndex = prompt.questions.mapIndexed { index, question -> answerFor(index, question) }
        val answeredCount = answersByIndex.count { it != null }
        val complete = answeredCount == prompt.questions.size
        // Failed-send error and in-flight marker live HERE, next to the Send
        // chip the user just tapped — the top of the sheet may be scrolled
        // far out of the viewport on a tall multi-question card.
        SendStatus(error = error, inFlight = inFlight)
        Chip(
            onClick = { if (!inFlight && complete) onSend(answersByIndex.map { checkNotNull(it) }) },
            enabled = !inFlight && complete,
            label = {
                Text(
                    text = if (complete) "Send answers" else "Answer all questions ($answeredCount/${prompt.questions.size})",
                    fontSize = 12.sp,
                    color = if (complete) WatchTheme.Success else WatchTheme.TextSecondary,
                )
            },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("questionsSend"),
        )
    }
}
