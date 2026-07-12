// Handoff §4 — the live session feed: ‹ › header cycling through the
// project's sessions (also horizontal swipe), a bottom-anchored tail of the
// session's terminal ring, and a bottom slot that is either the terracotta
// "waiting" banner (tap = open the card) or the Dictate pill. The feed
// scrolls via ROTARY ONLY, per the handoff's interaction table ("rotary
// scrolls lists/feeds") — leaving vertical touch drags unconsumed is also
// what keeps InnerScreen's swipe-down-back reachable under a scrollable.
// px values are at the 450 reference (≈ px/2 in dp, matching HaloTheme).
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Text
import dev.claudewatch.shared.terminal.TerminalLine
import dev.claudewatch.shared.terminal.TerminalLineType
import dev.claudewatch.wear.BridgeViewModel
import dev.claudewatch.wear.ui.halo.Halo.SessionState

/** Session-cycle swipe threshold ≈60px at the 450 reference (≈ px/2 in dp). */
private val CYCLE_SWIPE_THRESHOLD = 30.dp

/** Title wrap width ≈230px at the 450 reference. */
private val TITLE_MAX_WIDTH = 115.dp

@Composable
fun HaloSessionFeed(
    model: HaloModel,
    sessionId: String,
    ui: BridgeViewModel.UiState,
    onOpenCard: () -> Unit,
    onDictate: () -> Unit,
    onCycle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = model.sessions.firstOrNull { it.id == sessionId }
    if (session == null) {
        // Killed/pruned under us; HaloApp's model-shrink effect backs out a
        // frame later — render a placeholder rather than crash or ghost.
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "session ended",
                fontSize = Halo.Type.Body,
                color = Halo.Palette.TextFaint,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    // ‹ › cycle within the project. An orphan prompt's project can be missing
    // from the model's project list by the time we render; degrade to a
    // single-session "project" instead of indexing into nothing.
    val siblings = model.projects.firstOrNull { it.name == session.projectName }
        ?.sessions ?: listOf(session)
    val index = siblings.indexOfFirst { it.id == session.id }.coerceAtLeast(0)
    // The swipe detector lives in a pointerInput(Unit) that never restarts;
    // this keeps it cycling through the CURRENT sibling list, not a stale one.
    val cycleBy by rememberUpdatedState<(Int) -> Unit> { delta ->
        if (siblings.size > 1) onCycle(siblings[(index + delta).mod(siblings.size)].id)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Horizontal swipe cycles sessions (handoff §4). Horizontal only:
            // vertical drags stay unconsumed for InnerScreen's back detector.
            .pointerInput(Unit) {
                val threshold = CYCLE_SWIPE_THRESHOLD.toPx()
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        // Swipe left = next session (content follows finger).
                        if (total < -threshold) cycleBy(1)
                        else if (total > threshold) cycleBy(-1)
                    },
                ) { change, dragAmount ->
                    total += dragAmount
                    change.consume()
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FeedHeader(
                session = session,
                position = index + 1,
                count = siblings.size,
                onPrev = { cycleBy(-1) },
                onNext = { cycleBy(1) },
            )
            val bridgeSession = ui.bridge.sessions[sessionId]
            FeedTail(
                lines = bridgeSession?.terminal?.items ?: emptyList(),
                thinking = bridgeSession?.thinking == true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            if (session.pending != null) {
                WaitingBanner(state = session.state, onOpenCard = onOpenCard)
            } else {
                DictatePill(onDictate = onDictate)
            }
        }
    }
}

// ── Header: ‹ dot + wrapping title / "n of m · project" › ───────────────────

@Composable
private fun FeedHeader(
    session: HaloSession,
    position: Int,
    count: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Clears the TimeText strip; the row's sides stay inside the curve
            // because the arrows are narrow and the title is width-capped.
            .padding(top = 26.dp),
    ) {
        CycleArrow(glyph = "‹", visible = count > 1, onClick = onPrev)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp) // 12px state dot, as on list rows
                        .background(Halo.colorFor(session.state), CircleShape),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = session.title,
                    fontSize = 12.sp, // 24px / 1.15
                    fontWeight = FontWeight.Medium,
                    lineHeight = 14.sp,
                    color = Halo.Palette.TextPrimary,
                    textAlign = TextAlign.Center,
                    // Wraps by design; the cap stops a pathological label.
                    maxLines = 3,
                    modifier = Modifier.widthIn(max = TITLE_MAX_WIDTH),
                )
            }
            Text(
                text = "$position of $count · ${session.projectName}",
                fontSize = Halo.Type.Min,
                color = Halo.Palette.TextFaint,
                maxLines = 1,
            )
        }
        CycleArrow(glyph = "›", visible = count > 1, onClick = onNext)
    }
}

/**
 * The ‹/› glyphs are small by design, so the touch target is the full 48dp
 * cell. A single-session project hides them but keeps the cells, so the title
 * stays centered and doesn't jump when a sibling appears.
 */
@Composable
private fun CycleArrow(glyph: String, visible: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Halo.Geo.TouchMin)
            .clickable(enabled = visible, onClick = onClick),
    ) {
        if (visible) {
            Text(text = glyph, fontSize = 16.sp, color = Halo.Palette.TextSecondary)
        }
    }
}

// ── The terminal tail ───────────────────────────────────────────────────────

@Composable
private fun FeedTail(
    lines: List<TerminalLine>,
    thinking: Boolean,
    modifier: Modifier = Modifier,
) {
    // The empty state composes INSTEAD of the LazyColumn below, so it must be
    // decided before the FocusRequester's LaunchedEffect: requesting focus
    // while the rotaryScrollable node (the only thing the requester ever
    // attaches to) is not composed throws IllegalStateException — and every
    // fresh session and orphan prompt starts with an empty terminal.
    if (lines.isEmpty() && !thinking) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "no output yet",
                fontSize = Halo.Type.Min,
                color = Halo.Palette.TextFaint,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Stable per-line keys, so a reading position held in history survives the
    // stream appending (and the 200-line ring dropping) lines: without keys
    // the viewport is anchored by INDEX and drifts one row per event. The
    // RingBuffer keeps no monotonic counter, so the absolute index of the
    // oldest retained line is reconstructed here by diffing successive lists.
    val keyState = remember { FeedKeyState() }
    if (keyState.lines !== lines || keyState.thinking != thinking) {
        keyState.base += droppedCount(keyState.lines, lines)
        keyState.lines = lines
        keyState.thinking = thinking
        // Key-based anchoring holds the viewport on the line it shows — which
        // at the tail means NOT following new output. Reading the position
        // here (pre-measure, so still the pre-append position; unobserved so
        // scrolling doesn't recompose us) and re-requesting index 0 keeps the
        // tail pinned; requestScrollToItem overrides key anchoring for
        // exactly the next remeasure.
        val atTail = Snapshot.withoutReadObservation {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
        if (atTail) listState.requestScrollToItem(0)
    }

    // Bottom-anchored via reverseLayout: index 0 is the NEWEST line pinned to
    // the bottom edge. Touch scrolling is off (rotary-only per the handoff),
    // which leaves vertical drags to the screen's swipe-down-back.
    LazyColumn(
        state = listState,
        reverseLayout = true,
        userScrollEnabled = false,
        modifier = modifier
            .rotaryScrollable(
                behavior = RotaryScrollableDefaults.behavior(listState),
                focusRequester = focusRequester,
                // The rotary behavior drives scrollBy toward higher indices,
                // which reverseLayout renders UPWARD — reversed here so the
                // crown direction matches the session list's.
                reverseDirection = true,
            )
            .padding(horizontal = Halo.Geo.SafeInset)
            .padding(vertical = 4.dp),
    ) {
        if (thinking) {
            item(key = "thinking") { FeedLine(TerminalLine("…", TerminalLineType.SYSTEM)) }
        }
        // Newest-first to match reverseLayout's index order; keys count from
        // the base so a line keeps its key as older lines fall off the ring.
        items(
            count = lines.size,
            key = { i -> keyState.base + (lines.size - 1 - i) },
        ) { i -> FeedLine(lines[lines.size - 1 - i]) }
    }
}

/**
 * Composition-local bookkeeping for [FeedTail]'s stable keys: the last list
 * rendered and the absolute stream index of its first element. Deliberately
 * not snapshot state — it is read and written only inside composition, in the
 * same pass that rebuilds the item keys.
 */
private class FeedKeyState {
    var lines: List<TerminalLine> = emptyList()
    var thinking = false
    var base = 0L
}

/**
 * How many lines the ring dropped between [old] and [new], recovered from the
 * append-only contract: `new` is `old` minus some head plus some tail. The
 * largest suffix/prefix overlap decides; repeated identical lines can make it
 * overestimate the overlap, which only shifts every key by the same amount —
 * anchoring then lands on an identical-looking line, which is acceptable.
 */
private fun droppedCount(old: List<TerminalLine>, new: List<TerminalLine>): Int {
    for (overlap in minOf(old.size, new.size) downTo 1) {
        var match = true
        for (j in 0 until overlap) {
            if (old[old.size - overlap + j] != new[j]) {
                match = false
                break
            }
        }
        if (match) return old.size - overlap
    }
    return old.size
}

/**
 * Type → style per handoff §4. The pipeline has no dedicated "agent prose"
 * line type: assistant text arrives as OUTPUT with the formatter's "[codex] "
 * source prefix (ToolOutputFormatter's CodexMessage branch), so that prefix
 * is the discriminator; all other OUTPUT is tool results. "> " marks the
 * user's own dictated/echoed commands (BridgeState.echoCommand).
 */
@Composable
private fun FeedLine(line: TerminalLine) {
    val text: AnnotatedString
    val color: Color
    var family: FontFamily? = FontFamily.Monospace
    var size = 11.5.sp // tool calls & results: 23px mono
    var weight: FontWeight? = null

    when (line.type) {
        TerminalLineType.COMMAND, TerminalLineType.SYSTEM ->
            if (line.text.startsWith("> ")) { // user entry: 24px medium
                text = AnnotatedString(line.text)
                color = Halo.Palette.UserEntry
                family = null
                size = 12.sp
                weight = FontWeight.Medium
            } else { // tool call / meta
                text = AnnotatedString(line.text)
                color = Halo.Palette.TextFaint
            }
        TerminalLineType.OUTPUT ->
            if (line.text.startsWith("[codex] ")) { // agent prose: 25px Roboto
                text = AnnotatedString(line.text.removePrefix("[codex] "))
                color = Halo.Palette.TextPrimary
                family = null
                size = Halo.Type.Body
            } else { // result
                text = highlightPassCounts(line.text)
                color = Halo.Palette.TextSecondary
            }
        TerminalLineType.ERROR -> {
            text = AnnotatedString(line.text)
            color = Halo.Palette.Error
        }
    }

    Text(
        text = text,
        fontSize = size,
        lineHeight = size * 1.3f,
        fontFamily = family,
        fontWeight = weight,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    )
}

/** "12 passed", "3 passing", "✓" light up green inside result lines. */
private val PASS_COUNT = Regex("""\b\d+\s+pass(?:ed|ing)?\b|✓""", RegexOption.IGNORE_CASE)

private fun highlightPassCounts(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    for (match in PASS_COUNT.findAll(text)) {
        addStyle(SpanStyle(color = Halo.Palette.Running), match.range.first, match.range.last + 1)
    }
}

// ── Bottom slot: waiting banner / dictate pill ──────────────────────────────

/**
 * Persistent while the session is waiting: a terracotta gradient rising from
 * the bottom edge with the call to action. Tap opens this session's card.
 */
@Composable
private fun WaitingBanner(state: SessionState, onOpenCard: () -> Unit) {
    val label =
        if (state == SessionState.WAITING_Q) "has a question →" else "waiting for permission →"
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(Halo.Geo.TouchMin)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Halo.Palette.WaitingForYou.copy(alpha = 0.30f),
                ),
            )
            .clickable(onClick = onOpenCard),
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp, // 23px medium
            fontWeight = FontWeight.Medium,
            color = Halo.Palette.WaitingForYou,
            maxLines = 1,
            // Lifts the text off the screen curve; the tap target stays 48dp.
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun DictatePill(onDictate: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(Halo.Geo.TouchMin)
            .clickable(onClick = onDictate),
    ) {
        // The visual pill is smaller than the 48dp-tall full-width tap area.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .background(Halo.Palette.Surface2, RoundedCornerShape(50))
                .defaultMinSize(minWidth = 88.dp)
                .padding(horizontal = 14.dp, vertical = 5.dp),
        ) {
            Text(
                text = "Dictate",
                fontSize = Halo.Type.Caption,
                fontWeight = FontWeight.Medium,
                color = Halo.Palette.TextPrimary,
                maxLines = 1,
            )
        }
    }
}
