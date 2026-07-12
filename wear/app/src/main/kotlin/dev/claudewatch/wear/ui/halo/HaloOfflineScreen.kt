// PLACEHOLDER — handoff §8 (offline/re-pair: drained ring, reconnect
// countdown, re-pair chip, and the pairing spinner flow with host/port/code
// entry) is implemented by a follow-up agent. Until then this screen is the
// whole unpaired/offline experience, so it keeps the drained ring and status
// line so the state is at least legible. Contract: onRepair starts re-pairing
// (today: unpair, which wipes credentials and lands on the pairing state).
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Text
import dev.claudewatch.wear.BridgeViewModel

@Composable
fun HaloOfflineScreen(
    ui: BridgeViewModel.UiState,
    onRepair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        HaloRing(states = emptyList(), drained = true)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(Halo.Geo.SafeInset),
        ) {
            Text(
                text = if (ui.paired) "bridge offline" else "not paired",
                fontSize = Halo.Type.Title,
                color = Halo.Palette.Error,
                textAlign = TextAlign.Center,
            )
            Text(
                text = ui.repairExplanation ?: ui.status,
                fontSize = Halo.Type.Min,
                color = Halo.Palette.TextFaint,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
        }
    }
}
