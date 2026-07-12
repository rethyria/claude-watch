// PLACEHOLDER — handoff §6 (AskUserQuestion card: option pills, per-question
// advance, answers buffered and submitted together, dictate-an-answer) is
// implemented by a follow-up agent. Contract: renders the FRONT of
// ui.permissionQueue (which has questions); onAnswers(permissionId, answers)
// submits one answer per question positionally, onDismiss(permissionId) is
// the local escape hatch, onDone tells HaloApp the user is finished here.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Text
import dev.claudewatch.wear.BridgeViewModel

@Composable
fun HaloQuestionCard(
    model: HaloModel,
    ui: BridgeViewModel.UiState,
    onAnswers: (String, List<String>) -> Unit,
    onDismiss: (String) -> Unit,
    onDictate: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val front = ui.permissionQueue.firstOrNull()
    Box(modifier = modifier.fillMaxSize().padding(Halo.Geo.SafeInset)) {
        Text(
            text = front?.questions?.firstOrNull()?.question ?: "no question",
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.WaitingForYou,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
