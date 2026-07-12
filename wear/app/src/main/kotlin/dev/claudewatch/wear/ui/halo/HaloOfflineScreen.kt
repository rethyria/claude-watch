// Handoff §8 (offline / re-pair). The spec's per-second "retry in Ns"
// countdown renders as the engine's own status line instead ("paired,
// reconnecting (reason)") — UiState does not expose the backoff deadline,
// and the UI layer does not reach into the engine to get one. The pairing
// PATH itself lives here: this screen is the whole unpaired/offline
// experience and MainActivity mounts HaloApp directly, so without the form a
// fresh install would dead-end on "not paired" with nothing tappable.
// Pairing goes straight through onPair — the engine re-pairs in place, so
// re-pairing never requires unpair (which wipes credentials) first.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import dev.claudewatch.wear.BridgeViewModel

@Composable
fun HaloOfflineScreen(
    ui: BridgeViewModel.UiState,
    onPair: (host: String, port: String, code: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("7860") }
    var code by remember { mutableStateOf("") }
    // Paired-but-offline usually means the engine is retrying on its own;
    // the form stays behind a chip so it doesn't shout over "reconnecting".
    var formOpen by remember { mutableStateOf(false) }
    val showForm = !ui.paired || formOpen

    Box(modifier = modifier.fillMaxSize()) {
        HaloRing(states = emptyList(), drained = true)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Halo.Geo.SafeInset),
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
                modifier = Modifier.testTag("status"),
            )
            Spacer(Modifier.height(8.dp))
            if (showForm) {
                PairField("host", host, "host") { host = it }
                PairField("port", port, "port") { port = it }
                PairField("code", code, "code") { code = it }
                Chip(
                    onClick = { onPair(host, port, code) },
                    label = {
                        Text("pair", fontSize = Halo.Type.Caption, color = Halo.Palette.ApproveText)
                    },
                    colors = ChipDefaults.primaryChipColors(
                        backgroundColor = Halo.Palette.WaitingForYou,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("pairButton"),
                )
            } else {
                Chip(
                    onClick = { formOpen = true },
                    label = {
                        Text(
                            "re-pair watch",
                            fontSize = Halo.Type.Caption,
                            color = Halo.Palette.WaitingForYou,
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = Halo.Palette.Surface,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("repairButton"),
                )
            }
        }
    }
}

@Composable
private fun PairField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    Text(label, fontSize = Halo.Type.Min, color = Halo.Palette.TextFaint)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Halo.Palette.TextPrimary, fontSize = Halo.Type.Body),
        cursorBrush = SolidColor(Halo.Palette.TextPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .background(Halo.Palette.Surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(tag),
    )
    Spacer(Modifier.height(4.dp))
}
