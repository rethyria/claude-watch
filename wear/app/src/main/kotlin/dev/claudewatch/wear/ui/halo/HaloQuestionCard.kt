// PLACEHOLDER — handoff §6 (AskUserQuestion card: option pills, per-question
// advance, answers buffered and submitted together, dictate-an-answer) is
// implemented by a follow-up agent. Contract: renders the [card] it is GIVEN
// (which has questions) — HaloApp resolves nav's targeted prompt (or the
// queue front) and keeps the exiting chaining frame rendering the entry that
// just left the queue, so never re-read ui.permissionQueue here.
// onAnswers(permissionId, answers) submits one answer per question
// positionally, onDismiss(permissionId) is the local escape hatch, onDone
// tells HaloApp the user is finished here.
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
    card: BridgeViewModel.PendingPermission,
    model: HaloModel,
    ui: BridgeViewModel.UiState,
    onAnswers: (String, List<String>) -> Unit,
    onDismiss: (String) -> Unit,
    onDictate: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(Halo.Geo.SafeInset)) {
        Text(
            text = card.questions.firstOrNull()?.question ?: "no question",
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.WaitingForYou,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
