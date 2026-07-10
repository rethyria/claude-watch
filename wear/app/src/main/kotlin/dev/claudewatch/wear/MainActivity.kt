package dev.claudewatch.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * Walking-skeleton debug screen: manual IP:port + pairing-code entry, raw SSE
 * event log, a session-scoped command box, and allow/deny for permission
 * prompts. Deliberately a single scrollable column (not a lazy list) so every
 * node exists in the semantics tree for the instrumented e2e test.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DebugScreen()
            }
        }
    }
}

@Composable
fun DebugScreen(
    viewModel: BridgeViewModel = viewModel(
        factory = BridgeViewModel.factory(LocalContext.current.applicationContext),
    ),
) {
    val state by viewModel.state.collectAsState()

    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("7860") }
    var code by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
    ) {
        Text("Claude Watch", fontSize = 14.sp, color = Color.White)
        Text(
            state.status,
            fontSize = 11.sp,
            color = Color(0xFFFFC080),
            modifier = Modifier.testTag("status"),
        )
        Text(
            "session:${state.sessionId ?: "none"}",
            fontSize = 9.sp,
            color = Color.Gray,
            modifier = Modifier.testTag("sessionId"),
        )
        // Re-onboarding explanation: shown only when the token was
        // definitively rejected (401) or the bridge's protocol is too old.
        state.repairExplanation?.let {
            Text(
                it,
                fontSize = 10.sp,
                color = Color(0xFFFF8080),
                modifier = Modifier.testTag("repairExplanation"),
            )
        }

        Spacer(Modifier.height(6.dp))
        LabeledField("host", host, "host") { host = it }
        LabeledField("port", port, "port") { port = it }
        LabeledField("code", code, "code") { code = it }
        DebugChip("Pair", "pairButton") { viewModel.pair(host, port, code) }
        if (state.paired) {
            DebugChip("Unpair", "unpairButton") { viewModel.unpair() }
        }

        Spacer(Modifier.height(6.dp))
        LabeledField("command", command, "commandInput") { command = it }
        DebugChip("Send", "sendButton") { viewModel.sendCommand(command) }
        state.commandResult?.let {
            Text(it, fontSize = 10.sp, color = Color(0xFF80C0FF), modifier = Modifier.testTag("commandResult"))
        }

        state.pendingPermission?.let { pending ->
            Spacer(Modifier.height(6.dp))
            Text(
                "Permission: ${pending.toolName}",
                fontSize = 11.sp,
                color = Color(0xFFFF8080),
                modifier = Modifier.testTag("permissionTool"),
            )
            DebugChip("Allow", "allowButton") { viewModel.answerPermission("allow") }
            DebugChip("Deny", "denyButton") { viewModel.answerPermission("deny") }
        }
        state.decisionResult?.let {
            Text(it, fontSize = 10.sp, color = Color(0xFF80C0FF), modifier = Modifier.testTag("decisionResult"))
        }

        Spacer(Modifier.height(6.dp))
        Text(
            state.eventLog.joinToString("\n"),
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
