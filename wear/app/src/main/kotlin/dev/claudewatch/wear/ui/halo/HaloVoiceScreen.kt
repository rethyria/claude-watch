// Handoff §7 — the ack-gated voice send. The LISTENING phase (concentric
// rings, live transcript) lives in the system recognizer activity
// (RecognizerIntent.ACTION_RECOGNIZE_SPEECH covers the screen and offers no
// partial-result stream), so this screen owns everything AFTER transcription:
// the "sending… waiting for ack" hold — the text is NEVER shown as sent until
// the bridge ACKs (the ViewModel enforces this; here it is rendered honestly)
// — and the not-delivered failure with Retry/Discard. Success needs no state
// of its own: the ack echoes "> text" into the session feed and HaloApp
// closes this overlay.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import dev.claudewatch.wear.BridgeViewModel

@Composable
fun HaloVoiceScreen(
    ui: BridgeViewModel.UiState,
    /** Where the text goes, for "to {session}"; null = the VM's default. */
    targetSessionTitle: String?,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sending = ui.commandInFlightText != null
    // The failed send's text is restored into the draft by the ViewModel's
    // contract ("never silently lost"); render it from there so what the user
    // sees IS what Retry will send.
    val transcript = ui.commandInFlightText ?: ui.commandDraft

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Halo.Geo.SafeInset)
            .testTag("haloVoice"),
    ) {
        TranscriptWell(text = transcript, failed = !sending)
        Spacer(Modifier.height(4.dp))
        if (sending) {
            Text(
                text = "sending… waiting for ack",
                fontSize = Halo.Type.Caption,
                color = Halo.Palette.UserEntry,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("haloVoiceStatus"),
            )
            if (targetSessionTitle != null) {
                Text(
                    text = "to $targetSessionTitle",
                    fontSize = Halo.Type.Min,
                    color = Halo.Palette.TextFaint,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(4.dp))
            VoicePill(
                label = "Cancel",
                tag = "haloVoiceCancel",
                filled = false,
                onClick = onCancel,
            )
        } else {
            Text(
                text = "not delivered — bridge didn't ack",
                fontSize = Halo.Type.Caption,
                color = Halo.Palette.Error,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("haloVoiceError"),
            )
            if (ui.commandError != null) {
                Text(
                    text = ui.commandError,
                    fontSize = Halo.Type.Min,
                    color = Halo.Palette.TextFaint,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                VoicePill(
                    label = "Discard",
                    tag = "haloVoiceDiscard",
                    filled = false,
                    onClick = onDiscard,
                    modifier = Modifier.weight(0.45f),
                )
                VoicePill(
                    label = "Retry",
                    tag = "haloVoiceRetry",
                    filled = true,
                    onClick = onRetry,
                    modifier = Modifier.weight(0.55f),
                )
            }
        }
    }
}

/** The transcript: an inset well; red-dashed outline once delivery failed. */
@Composable
private fun TranscriptWell(text: String, failed: Boolean) {
    val radius = Halo.Geo.CardRadius
    var well = Modifier
        .fillMaxWidth()
        .background(Halo.Palette.InsetWell, RoundedCornerShape(radius))
    if (failed) {
        well = well.drawBehind {
            drawRoundRect(
                color = Halo.Palette.Error,
                cornerRadius = CornerRadius(radius.toPx()),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                ),
            )
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = well.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text.ifEmpty { "…" },
            fontSize = Halo.Type.Body,
            color = Halo.Palette.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 4,
            modifier = Modifier.testTag("haloVoiceTranscript"),
        )
    }
}

@Composable
private fun VoicePill(
    label: String,
    tag: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 76.dp, minHeight = Halo.Geo.TouchMin)
            .clip(shape)
            .background(if (filled) Halo.Palette.WaitingForYou else Halo.Palette.Surface2, shape)
            .clickable(onClick = onClick)
            .testTag(tag),
    ) {
        Text(
            text = label,
            fontSize = Halo.Type.Title,
            fontWeight = FontWeight.Medium,
            color = if (filled) Halo.Palette.ApproveText else Halo.Palette.TextPrimary,
        )
    }
}
