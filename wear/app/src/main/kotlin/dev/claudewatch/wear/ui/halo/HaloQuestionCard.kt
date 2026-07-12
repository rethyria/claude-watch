// Handoff §6 — the AskUserQuestion card: one question at a time, full-width
// option pills, a selection advances to the next question, and the answers
// are BUFFERED and submitted together after the last one (one positional
// answer per question — never keyed by question text, which would collapse
// duplicate questions and deadlock the submit gate). The buffer itself is
// HOISTED to HaloApp (keyed by prompt id) so "answer later ↓" / swipe-down —
// which unmount this overlay-scoped composable — lose nothing (§6).
// "Dictate an answer…" is always the last option, so a question whose
// options failed to parse is still answerable. Renders the [card] it is
// GIVEN — HaloApp resolves nav's
// targeted prompt (or the queue front) and keeps the exiting chaining frame
// rendering the entry that just left the queue, so ui.permissionQueue is read
// here ONLY as the resolution signal, never to choose what to render.
// Resolution/flash/failure semantics mirror HaloApprovalCard exactly.
package dev.claudewatch.wear.ui.halo

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Text
import dev.claudewatch.shared.protocol.AskUserQuestion
import dev.claudewatch.wear.BridgeViewModel
import dev.claudewatch.wear.ui.LOCAL_DISMISS_AFTER_FAILURES
import kotlinx.coroutines.delay

/** Session-title wrap width ≈260px at the 450 reference (≈ px/2 in dp). */
private val TITLE_MAX_WIDTH = 130.dp

@Composable
fun HaloQuestionCard(
    card: BridgeViewModel.PendingPermission,
    model: HaloModel,
    ui: BridgeViewModel.UiState,
    /**
     * The answer buffer: one answer per question POSITION (see the header
     * comment). OWNED BY THE CALLER and keyed to this prompt's id there,
     * because this composable unmounts on every overlay exit — "answer
     * later ↓", swipe-down, even a reconnect blip — and §6 promises that
     * exit "loses nothing": picks made so far must survive a close/reopen
     * round-trip. Kept after submit so a failed POST retries the exact picks.
     */
    answers: SnapshotStateList<String?>,
    onAnswers: (String, List<String>) -> Unit,
    onDismiss: (String) -> Unit,
    /** Launch the recognizer; the transcription lands in the callback. */
    onDictate: (onResult: (String) -> Unit) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done by rememberUpdatedState(onDone)
    val questions = card.questions

    // The current question is DERIVED (first unanswered position), never a
    // separately-stored index that could drift from the hoisted buffer.
    val index = answers.indexOfFirst { it == null }.let { if (it < 0) answers.size else it }
    var submitted by remember(card.permissionId) { mutableStateOf(false) }
    var dismissedLocally by remember(card.permissionId) { mutableStateOf(false) }
    // The error of the last FAILED attempt on this card, scoped locally
    // because ui.decisionError is connection-global and sticky.
    var attemptError by remember(card.permissionId) { mutableStateOf<String?>(null) }
    val armAtMs = remember(card.permissionId) { SystemClock.uptimeMillis() + ARM_DELAY_MS }

    // Same resolution/flash discipline as HaloApprovalCard: the prompt leaves
    // the queue only on an authoritative outcome; the ✓ flash plays only for
    // an answer THIS watch delivered with a decision 2xx.
    val resolved = ui.permissionQueue.none { it.permissionId == card.permissionId }
    val inFlight = ui.decisionInFlightId == card.permissionId
    if (submitted && !resolved && !inFlight && ui.decisionError != null) {
        attemptError = ui.decisionError
        submitted = false
    }
    var resolutionSeen by remember(card.permissionId) { mutableStateOf(false) }
    var showFlash by remember(card.permissionId) { mutableStateOf(false) }
    if (resolved && !resolutionSeen) {
        resolutionSeen = true
        // The ✓ flash needs decisionForId to match: decisionResult is global
        // and sticky, so alone it can be an EARLIER prompt's success — e.g. a
        // submit that raced a hook-abort and never actually POSTed.
        showFlash = submitted && !dismissedLocally && !inFlight &&
            ui.decisionError == null && ui.decisionForId == card.permissionId &&
            ui.decisionResult.isDecisionSuccess()
    }
    LaunchedEffect(resolved) {
        if (!resolved) return@LaunchedEffect
        if (showFlash) delay(FLASH_MS)
        done()
    }

    fun submit() {
        val complete = answers.mapNotNull { it }
        if (complete.size != questions.size) return
        attemptError = null
        submitted = true
        onAnswers(card.permissionId, complete)
    }

    /** Record [answer] for the current question; last answer submits all. */
    fun record(answer: String) {
        if (inFlight || resolved || submitted) return
        if (SystemClock.uptimeMillis() < armAtMs) return
        val trimmed = answer.trim()
        if (trimmed.isEmpty()) return
        val position = answers.indexOfFirst { it == null }
        if (position < 0) return
        answers[position] = trimmed
        if (answers.none { it == null }) submit()
    }
    // The dictation sink outlives the composition that created it (the
    // recognizer round-trip takes seconds): route it through the LATEST
    // record, whose resolved/inFlight guards read the current state — a
    // tap-time closure would happily "answer" a prompt that resolved while
    // the recognizer was up.
    val currentRecord by rememberUpdatedState<(String) -> Unit>(::record)

    Crossfade(
        targetState = showFlash,
        label = "questionResolve",
        modifier = modifier.fillMaxSize(),
    ) { flash ->
        if (flash) {
            QuestionResultFlash()
        } else {
            QuestionLayer(
                card = card,
                model = model,
                ui = ui,
                question = questions.getOrNull(index),
                index = index,
                count = questions.size,
                answeredAll = submitted || (questions.isNotEmpty() && answers.none { it == null }),
                inFlight = inFlight,
                attemptError = attemptError,
                resolved = resolved,
                onPick = ::record,
                onDictate = { onDictate { spoken -> currentRecord(spoken) } },
                onRetry = { if (!inFlight && !resolved) submit() },
                onDismissLocally = {
                    dismissedLocally = true
                    onDismiss(card.permissionId)
                },
                onAnswerLater = done,
            )
        }
    }
}

/** True only for an acked decision POST with an HTTP 2xx (see decisionResult). */
private fun String?.isDecisionSuccess(): Boolean =
    this?.removePrefix("decision:")?.toIntOrNull()?.let { it in 200..299 } == true

@Composable
private fun QuestionLayer(
    card: BridgeViewModel.PendingPermission,
    model: HaloModel,
    ui: BridgeViewModel.UiState,
    question: AskUserQuestion?,
    index: Int,
    count: Int,
    answeredAll: Boolean,
    inFlight: Boolean,
    attemptError: String?,
    resolved: Boolean,
    onPick: (String) -> Unit,
    onDictate: () -> Unit,
    onRetry: () -> Unit,
    onDismissLocally: () -> Unit,
    onAnswerLater: () -> Unit,
) {
    val session = model.sessions.firstOrNull { it.id == card.sessionId }
    val sessionTitle = session?.title ?: card.sessionLabel

    // Same scroll + pull-down-to-exit chrome as the approval card: the
    // options can outgrow a round display, and verticalScroll starves the
    // overlay's swipe-down detector (see HaloApprovalCard for the mechanics).
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val answerLater by rememberUpdatedState(onAnswerLater)
    val exitThresholdPx = with(LocalDensity.current) { 30.dp.toPx() }
    val overscrollExit = remember(exitThresholdPx) {
        object : NestedScrollConnection {
            private var overscroll = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) overscroll += available.y
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll > exitThresholdPx) answerLater()
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
            .padding(Halo.Geo.SafeInset)
            .testTag("haloQuestionCard"),
    ) {
        // Header stack: progress → session → the question itself (handoff §6).
        Text(
            text = "question ${(index + 1).coerceAtMost(count)} of $count",
            fontSize = Halo.Type.Min,
            color = Halo.Palette.WaitingForYou,
            modifier = Modifier.testTag("haloQuestionCount"),
        )
        Text(
            text = sessionTitle,
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = TITLE_MAX_WIDTH),
        )
        Spacer(Modifier.height(4.dp))

        if (answeredAll || question == null) {
            // Every question answered: the buffered submit is in flight (or
            // failed and is retryable). No options left to render.
            SubmitStage(
                ui = ui,
                inFlight = inFlight,
                attemptError = attemptError,
                resolved = resolved,
                onRetry = onRetry,
                onDismissLocally = onDismissLocally,
            )
        } else {
            Text(
                text = question.question,
                fontSize = 14.sp, // 28px medium
                fontWeight = FontWeight.Medium,
                lineHeight = 16.sp,
                color = Halo.Palette.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("haloQuestionText"),
            )
            Spacer(Modifier.height(6.dp))
            if (question.multiSelect) {
                MultiSelectOptions(question = question, index = index, onPick = onPick)
            } else {
                question.options.forEach { option ->
                    OptionPill(
                        label = option.label,
                        tag = "haloQOption-$index-${option.label}",
                        onClick = { onPick(option.label) },
                    )
                }
            }
            // Always the last option: no question is ever unanswerable, even
            // when its options failed to parse (handoff §6).
            OptionPill(
                label = "Dictate an answer…",
                tag = "haloQDictate",
                textColor = Halo.Palette.UserEntry,
                onClick = onDictate,
            )
        }

        TextAction(
            label = "answer later ↓",
            color = Halo.Palette.TextFaint,
            tag = "haloAnswerLater",
            onClick = onAnswerLater,
        )
    }
}

/**
 * multiSelect toggles instead of advancing; "next" confirms the set, joined
 * in the question's OPTION order so the answer is stable regardless of tap
 * order (matches the bridge contract the old sheet established).
 */
@Composable
private fun MultiSelectOptions(
    question: AskUserQuestion,
    index: Int,
    onPick: (String) -> Unit,
) {
    // Keyed by index too: duplicate multiSelect questions are EQUAL data
    // classes, and a value-keyed remember would carry q0's picks into q1.
    var picked by remember(question, index) { mutableStateOf(emptySet<String>()) }
    question.options.forEach { option ->
        val selected = option.label in picked
        OptionPill(
            label = (if (selected) "✓ " else "") + option.label,
            tag = "haloQOption-$index-${option.label}",
            textColor = if (selected) Halo.Palette.WaitingForYou else Halo.Palette.TextPrimary,
            onClick = { picked = if (selected) picked - option.label else picked + option.label },
        )
    }
    val joined = question.options.map { it.label }.filter { it in picked }.joinToString(", ")
    OptionPill(
        label = "next →",
        tag = "haloQNext",
        enabled = picked.isNotEmpty(),
        filled = true,
        onClick = { onPick(joined) },
    )
}

/** Full-width option pill: 62px min height at 450 ref, but ≥48dp binding. */
@Composable
private fun OptionPill(
    label: String,
    tag: String,
    onClick: () -> Unit,
    textColor: Color = Halo.Palette.TextPrimary,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .defaultMinSize(minHeight = Halo.Geo.TouchMin)
            .clip(shape)
            .background(
                when {
                    !enabled -> Halo.Palette.Surface.copy(alpha = 0.5f)
                    filled -> Halo.Palette.WaitingForYou
                    else -> Halo.Palette.Surface
                },
                shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(tag),
    ) {
        Text(
            text = label,
            fontSize = Halo.Type.Body,
            color = if (filled) Halo.Palette.ApproveText else textColor,
            textAlign = TextAlign.Center,
        )
    }
}

/** After the last answer: sending status, failure retry, local-dismiss hatch. */
@Composable
private fun SubmitStage(
    ui: BridgeViewModel.UiState,
    inFlight: Boolean,
    attemptError: String?,
    resolved: Boolean,
    onRetry: () -> Unit,
    onDismissLocally: () -> Unit,
) {
    val statusLine = if (inFlight) "sending answers…" else attemptError
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
    if (attemptError != null && !inFlight && !resolved) {
        OptionPill(
            label = "send again",
            tag = "haloQRetry",
            filled = true,
            onClick = onRetry,
        )
    }
    // Same availability escape hatch as the approval card: a bridge that
    // stopped answering must not wedge the app behind this card.
    if (ui.decisionFailureCount >= LOCAL_DISMISS_AFTER_FAILURES) {
        TextAction(
            label = "dismiss without answering",
            color = Halo.Palette.Error,
            tag = "haloDismissLocal",
            onClick = onDismissLocally,
        )
    }
}

/** Faint text link with a TouchMin-tall hit box (see HaloApprovalCard's). */
@Composable
private fun TextAction(
    label: String,
    color: Color,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = Halo.Geo.TouchMin)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag(tag),
    ) {
        Text(text = label, fontSize = Halo.Type.Min, color = color)
    }
}

/** The 1.4s outcome flash, mirroring the approval card's approved variant. */
@Composable
private fun QuestionResultFlash() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(Halo.Geo.SafeInset)
            .testTag("haloResultFlash"),
    ) {
        Text(
            text = "✓",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Halo.Palette.Running,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Answered",
            fontSize = Halo.Type.Title,
            fontWeight = FontWeight.Medium,
            color = Halo.Palette.TextPrimary,
        )
        Text(
            text = "sent to bridge · agent resumed",
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
