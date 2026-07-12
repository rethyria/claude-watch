// The foreground terminal experience (issue #19): a horizontal pager with the
// control/debug page first and one live terminal page per session, all
// rendered from the shared reducer's BridgeState. Stateless over UiState so
// instrumented tests can drive it with fixture-fed states directly.
//
// Pager: androidx.compose.foundation's HorizontalPager (the primitive
// Horologist wraps; Horologist itself is not pulled in to keep the dependency
// set minimal). Terminal: ScalingLazyColumn, 30-line viewport window over the
// session's 200-line ring buffer, 11 sp monospace, design tokens on black.
package dev.claudewatch.wear.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Text
import dev.claudewatch.shared.state.SessionActivity
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.BridgeViewModel

/** Lines of terminal history rendered per page (watchOS parity). */
const val TERMINAL_VIEWPORT_LINES = 30

/** Everything the pager can ask the ViewModel to do. */
data class SessionPagerActions(
    val onPair: (host: String, port: String, code: String) -> Unit = { _, _, _ -> },
    val onSendCommand: (String) -> Unit = {},
    /** Answer the RENDERED card: (its permissionId, the chosen option's behavior). */
    val onAnswerPermission: (permissionId: String, behavior: String) -> Unit = { _, _ -> },
    /** Answer the RENDERED AskUserQuestion card: one answer per question, keyed by question text. */
    val onAnswerQuestions: (permissionId: String, answers: Map<String, String>) -> Unit = { _, _ -> },
    /** Drop the rendered card locally WITHOUT sending a decision (escape hatch; see PermissionSheet). */
    val onDismissPermission: (permissionId: String) -> Unit = {},
    val onSpawn: (agent: String) -> Unit = {},
    val onKill: (sessionId: String) -> Unit = {},
)

/**
 * Page 0 is the control page (pairing, dictated commands, permissions, spawn);
 * every live session gets its own terminal page after it. Session order is the
 * reducer map's insertion order, so pages are stable while sessions live.
 */
@Composable
fun SessionPagerScreen(ui: BridgeViewModel.UiState, actions: SessionPagerActions) {
    val sessions = ui.bridge.sessions.values.toList()
    val pagerState = rememberPagerState(pageCount = { 1 + sessions.size })
    val indicatorState = remember(pagerState) {
        object : PageIndicatorState {
            override val pageCount: Int get() = pagerState.pageCount
            override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
            override val selectedPage: Int get() = pagerState.currentPage
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchTheme.Background),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("sessionPager"),
        ) { page ->
            if (page == 0) {
                ControlPage(ui, actions)
            } else {
                SessionTerminalPage(sessions[page - 1], onKill = actions.onKill)
            }
        }
        HorizontalPageIndicator(pageIndicatorState = indicatorState)
        // The approval sheet: the SINGLE presenter for pending permissions,
        // drawn over everything (pager included) and never dismissable by
        // gesture — it leaves when the queue empties, or via the explicit
        // no-decision escape hatch after repeated failed answers (see
        // PermissionSheet.kt for the full defect inoculation list).
        if (ui.permissionQueue.isNotEmpty()) {
            PermissionSheet(
                queue = ui.permissionQueue,
                inFlightId = ui.decisionInFlightId,
                error = ui.decisionError,
                failureCount = ui.decisionFailureCount,
                onAnswer = actions.onAnswerPermission,
                onAnswerQuestions = actions.onAnswerQuestions,
                onDismiss = actions.onDismissPermission,
            )
        }
    }
}

/**
 * One session's live terminal: header (folder + agent + activity dot + kill),
 * then the last [TERMINAL_VIEWPORT_LINES] of its ring buffer in 11 sp
 * monospace, auto-scrolled to the newest line, with a blinking block cursor
 * while a sent command awaits its first output.
 */
@Composable
fun SessionTerminalPage(session: SessionState, onKill: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchTheme.Background)
            .padding(top = 24.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (session.activity == SessionActivity.WORKING) WatchTheme.Success
                        else WatchTheme.TextSecondary,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = session.folderName ?: session.agent ?: session.sessionId.take(8),
                fontSize = 11.sp,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .testTag("sessionHeader-${session.sessionId}"),
            )
            CompactChip(
                onClick = { onKill(session.sessionId) },
                label = { Text("✕", fontSize = 11.sp, color = WatchTheme.Error) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.testTag("kill-${session.sessionId}"),
            )
        }

        val visible = session.terminal.items.takeLast(TERMINAL_VIEWPORT_LINES)
        val listState = rememberScalingLazyListState()
        // Follow the tail: every new line (or cursor change) re-pins the view
        // to the newest content.
        LaunchedEffect(visible.size, session.terminal.size, session.thinking) {
            val lastIndex = visible.size - (if (session.thinking) 0 else 1)
            if (lastIndex >= 0) listState.scrollToItem(lastIndex)
        }
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .testTag("terminal-${session.sessionId}"),
        ) {
            items(visible.size) { index ->
                val line = visible[index]
                Text(
                    text = line.text,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WatchTheme.colorFor(line),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (session.thinking) {
                item { BlinkingCursor() }
            }
        }
    }
}

/** The blinking block cursor shown while a sent command awaits output. */
@Composable
private fun BlinkingCursor() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 400), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Text(
        text = "█",
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = WatchTheme.ClaudeOrange,
        modifier = Modifier
            .alpha(alpha)
            .testTag("thinkingCursor"),
    )
}

/**
 * The walking skeleton's debug controls, now page 0 of the pager: manual
 * IP:port + pairing-code entry, session-scoped command box, spawn actions,
 * and the reduced (human-readable) event log. Permission prompts live in the
 * PermissionSheet overlay, never here. Deliberately a plain scrollable column
 * so every node exists in the semantics tree for the instrumented e2e test.
 */
@Composable
private fun ControlPage(ui: BridgeViewModel.UiState, actions: SessionPagerActions) {
    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("7860") }
    var code by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchTheme.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
    ) {
        Text("Claude Watch", fontSize = 14.sp, color = Color.White)
        Text(
            ui.status,
            fontSize = 11.sp,
            color = Color(0xFFFFC080),
            modifier = Modifier.testTag("status"),
        )
        Text(
            "session:${ui.sessionId ?: "none"}",
            fontSize = 9.sp,
            color = Color.Gray,
            modifier = Modifier.testTag("sessionId"),
        )

        Spacer(Modifier.height(6.dp))
        LabeledField("host", host, "host") { host = it }
        LabeledField("port", port, "port") { port = it }
        LabeledField("code", code, "code") { code = it }
        DebugChip("Pair", "pairButton") { actions.onPair(host, port, code) }

        Spacer(Modifier.height(6.dp))
        LabeledField("command", command, "commandInput") { command = it }
        DebugChip("Send", "sendButton") { actions.onSendCommand(command) }
        ui.commandResult?.let {
            Text(it, fontSize = 10.sp, color = Color(0xFF80C0FF), modifier = Modifier.testTag("commandResult"))
        }

        Spacer(Modifier.height(6.dp))
        DebugChip("Spawn claude", "spawnClaudeButton") { actions.onSpawn("claude") }
        DebugChip("Spawn codex", "spawnCodexButton") { actions.onSpawn("codex") }
        ui.sessionActionResult?.let {
            Text(it, fontSize = 10.sp, color = Color(0xFF80C0FF), modifier = Modifier.testTag("sessionActionResult"))
        }

        // Permission prompts are NOT rendered here: the PermissionSheet
        // overlay is the single presenter (issue #17). Only the last decision
        // outcome stays visible for debugging.
        ui.decisionResult?.let {
            Text(it, fontSize = 10.sp, color = Color(0xFF80C0FF), modifier = Modifier.testTag("decisionResult"))
        }

        Spacer(Modifier.height(6.dp))
        Text(
            ui.eventLog.joinToString("\n"),
            fontSize = 8.sp,
            color = Color(0xFF80FF80),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("eventLog"),
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun LabeledField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    Text(label, fontSize = 9.sp, color = Color.Gray)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
        cursorBrush = SolidColor(Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF202020))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag(tag),
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun DebugChip(label: String, tag: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
    Spacer(Modifier.height(4.dp))
}
