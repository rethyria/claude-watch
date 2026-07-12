// PLACEHOLDER — handoff §4 (live session feed: ‹ › session cycling, bottom-
// anchored terminal tail, waiting banner, dictate pill) is implemented by a
// follow-up agent. The signature below is the contract HaloApp already calls:
// onOpenCard raises the approval/question overlay, onCycle asks HaloApp to
// switch this feed to a sibling session's id.
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
    Box(modifier = modifier.fillMaxSize().padding(Halo.Geo.SafeInset)) {
        Text(
            text = session?.title ?: "session gone",
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
