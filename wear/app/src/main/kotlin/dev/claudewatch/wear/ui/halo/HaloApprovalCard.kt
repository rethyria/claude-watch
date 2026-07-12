// PLACEHOLDER — handoff §5 (approval card: identity block, command well,
// Deny/Approve pills with the appear-debounce, result flash, queue chaining)
// is implemented by a follow-up agent. Contract: the card renders the [card]
// it is GIVEN — HaloApp resolves nav's targeted prompt (or the queue front)
// and keeps the exiting chaining frame rendering the entry that just left the
// queue, so never re-read ui.permissionQueue here. onAnswer(permissionId,
// behavior) answers it, onDismiss(permissionId) is the local no-decision
// escape hatch, onDone tells HaloApp the user is finished here ("decide
// later" or the queue emptied).
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
fun HaloApprovalCard(
    card: BridgeViewModel.PendingPermission,
    model: HaloModel,
    ui: BridgeViewModel.UiState,
    onAnswer: (String, String) -> Unit,
    onDismiss: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(Halo.Geo.SafeInset)) {
        Text(
            text = card.requestSummary,
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.WaitingForYou,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
