// Handoff §7 — the ack-gated voice send. The LISTENING phase (concentric
// rings, live transcript) lives in the system recognizer activity
// (RecognizerIntent.ACTION_RECOGNIZE_SPEECH covers the screen and offers no
// partial-result stream; the deviation is recorded in HALO_HANDOFF.md §7),
// so this screen owns everything AFTER transcription: the "sending… waiting
// for ack" hold — the text is NEVER shown as sent until the bridge ACKs (the
// ViewModel enforces this; here it is rendered honestly) — and the
// not-delivered failure with Retry/Discard. A failure with no transcript at
// all (no speech recognizer on the watch) renders the error alone with a
// single OK exit. Success needs no state of its own: the ack echoes "> text"
// into the session feed and HaloApp closes this overlay.
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
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
    // An error outranks a (foreign) in-flight send: when this overlay opened
    // for a busy refusal, another send's text is still in flight — rendering
    // THAT as "sending…" would pair the wrong text with this target.
    val sending = ui.commandInFlightText != null && ui.commandError == null
    // The failed send's text is restored into the draft by the ViewModel's
    // contract ("never silently lost"); render it from there so what the user
    // sees IS what Retry will send.
    val transcript = if (ui.commandError != null) ui.commandDraft else ui.commandInFlightText ?: ui.commandDraft

    // Same scroll + exit chrome as the cards: verticalScroll consumes every
    // vertical drag (starving HaloApp's swipe-down detector — see
    // HaloApprovalCard's DecisionLayer for the mechanics), so the Cancel exit
    // is re-provided from the nested-scroll leftovers, plus rotary support.
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val cancel by rememberUpdatedState(onCancel)
    val exitThresholdPx = with(LocalDensity.current) { 30.dp.toPx() }
    val overscrollExit = remember(exitThresholdPx) {
        object : NestedScrollConnection {
            private var overscroll = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) overscroll += available.y
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll > exitThresholdPx) cancel()
                overscroll = 0f
                return Velocity.Zero
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(overscrollExit)
            .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollState), focusRequester)
            .verticalScroll(scrollState)
            .padding(Halo.Geo.SafeInset)
            .testTag("haloVoice"),
    ) {
        if (transcript.isNotEmpty() || sending) {
            TranscriptWell(text = transcript, failed = !sending)
            Spacer(Modifier.height(4.dp))
        }
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
        } else if (transcript.isEmpty()) {
            // Nothing was transcribed (no speech recognizer, or the refusal
            // beat the transcription): there is nothing to retry — the error
            // is the whole story, and OK is the only exit.
            Text(
                text = ui.commandError ?: "dictation failed",
                fontSize = Halo.Type.Caption,
                color = Halo.Palette.Error,
                textAlign = TextAlign.Center,
                maxLines = 3,
                modifier = Modifier.testTag("haloVoiceError"),
            )
            Spacer(Modifier.height(4.dp))
            VoicePill(
                label = "OK",
                tag = "haloVoiceDismiss",
                filled = true,
                onClick = onDiscard,
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
