// PLACEHOLDER — handoff §7 (voice command: listening rings, live transcript,
// ack-gated "sending…" and the not-delivered retry state) is implemented by a
// follow-up agent. Contract: ui.commandInFlightText/commandError/commandDraft
// carry the ack-gated lifecycle; targetSessionTitle names where the text goes.
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
fun HaloVoiceScreen(
    ui: BridgeViewModel.UiState,
    targetSessionTitle: String?,
    onDictate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(Halo.Geo.SafeInset)) {
        Text(
            text = ui.commandInFlightText?.let { "sending…" }
                ?: targetSessionTitle?.let { "to $it" }
                ?: "voice",
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.UserEntry,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
