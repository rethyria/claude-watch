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

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import dev.claudewatch.wear.BridgeViewModel

@Composable
fun HaloOfflineScreen(
    ui: BridgeViewModel.UiState,
    onPair: (host: String, port: String, code: String) -> Unit,
    modifier: Modifier = Modifier,
    onDiscoverForPairing: () -> Unit = {},
) {
    var host by remember { mutableStateOf("10.0.2.2") }
    var port by remember { mutableStateOf("7860") }
    var code by remember { mutableStateOf("") }
    // Paired-but-offline usually means the engine is retrying on its own;
    // the form stays behind a chip so it doesn't shout over "reconnecting".
    var formOpen by remember { mutableStateOf(false) }
    val showForm = !ui.paired || formOpen

    // Issue #23 zero-typing: kick an NSD scan on entry, then seed the host/port
    // fields the moment a bridge is found. The fields keep their manual
    // defaults as a fallback (emulator/no-bridge never lands a discovery), and
    // seeding is one-shot per discovered value — a later manual edit is never
    // clobbered because the LaunchedEffect only re-fires when the discovered
    // value itself changes, not on every recomposition.
    LaunchedEffect(Unit) { onDiscoverForPairing() }
    LaunchedEffect(ui.discoveredHost) { ui.discoveredHost?.let { host = it } }
    LaunchedEffect(ui.discoveredPort) { ui.discoveredPort?.let { port = it.toString() } }

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
                    onClick = { onPair(host.trim(), port.trim(), code.trim()) },
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

/**
 * One pairing field. NOT an inline Compose text field: on the watch those
 * cannot reliably drive the system IME — the Samsung keyboard streams input
 * through the composing region, which the Compose BasicTextField interop
 * drops on this device (the caret moves but the glyphs never stick), so a
 * real finger could tap and type and end up with an empty field. Instead the
 * field is a tappable row that launches the Wear RemoteInput activity — the
 * platform's own full-screen input surface (keyboard + voice) — which hands
 * the finished string back as an activity result. No inline editing, no
 * composing-region interop, so no keyboard can corrupt it mid-type; this is
 * the input path Wear itself recommends over embedded text fields. The tag
 * stays on the tappable row so the instrumented pairing leg can still find
 * it (it drives the value through a test seam, not the real IME — the IME
 * cannot run headless anyway).
 */
@Composable
private fun PairField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            RemoteInput.getResultsFromIntent(result.data)
                ?.getCharSequence(REMOTE_INPUT_KEY)
                ?.toString()
                ?.let { onChange(it.trim()) }
        }
    }
    Text(label, fontSize = Halo.Type.Min, color = Halo.Palette.TextFaint)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Halo.Palette.Surface, RoundedCornerShape(8.dp))
            .clickable {
                // The instrumented pairing leg cannot drive the Wear
                // RemoteInput activity (no headless IME) and the field is no
                // longer an inline node performTextInput can fill, so a test
                // seam short-circuits the tap to a canned value (see
                // PairFieldInput). Production leaves the seam null and takes
                // the real RemoteInput path — the only path a finger takes.
                val seam = PairFieldInput.override
                if (seam != null) {
                    seam(label)?.let { onChange(it.trim()) }
                    return@clickable
                }
                val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
                    .setLabel(label)
                    .build()
                val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
                launcher.launch(intent)
            }
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag(tag),
    ) {
        Text(
            text = value.ifEmpty { "tap to enter" },
            fontSize = Halo.Type.Body,
            color = if (value.isEmpty()) Halo.Palette.TextFaint else Halo.Palette.TextPrimary,
        )
    }
    Spacer(Modifier.height(4.dp))
}

private const val REMOTE_INPUT_KEY = "pair_field_value"

/**
 * Test seam for [PairField] (the resolver-seam idiom used by
 * BridgeSessionService/GlanceStateSource). When [override] is non-null,
 * tapping a pairing field routes to it — returning the string to fill, or
 * null to leave the field unchanged (a cancelled input) — instead of
 * launching the Wear RemoteInput activity, which needs a real IME and does
 * not exist in the instrumented harness. Production never sets it, so a real
 * finger always gets the real system input surface.
 */
internal object PairFieldInput {
    @Volatile
    internal var override: ((label: String) -> String?)? = null
}
