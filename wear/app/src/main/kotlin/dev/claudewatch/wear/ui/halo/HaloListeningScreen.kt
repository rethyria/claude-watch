// Handoff §7 — the LISTENING phase, in-app at last (issue #134). Concentric
// terracotta circles, the live transcript, "to {session} · tap to send". The
// window is OURS, so it holds the screen on for the whole dictation — the
// system recogniser activity this replaces covered the display and let the
// watch sleep at 15s, taking the recording with it.
//
// Exits: TAP anywhere = stop and send (the §7 affordance). BACK = stop and
// REVIEW — a long dictation must never die to an edge swipe, so back lands on
// a Send / Discard hold instead of discarding. The review state is modal
// like the voice overlay's failed state: Send and Discard are the only ways
// out, because nothing else in Halo renders the held text.
package dev.claudewatch.wear.ui.halo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import dev.claudewatch.wear.speech.DictationSession

@Composable
fun HaloListeningScreen(
    state: DictationSession.State,
    /** "to {session}"; null = the VM's default target. */
    targetSessionTitle: String?,
    /** Tap while listening: stop and send. */
    onStop: () -> Unit,
    /** Review Send. */
    onSend: () -> Unit,
    /** Review Discard. */
    onDiscard: () -> Unit,
    ambient: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The whole point of owning this phase: the display stays awake for as
    // long as the user is dictating. Cleared when the overlay leaves.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val phase = state.phase
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("haloListening"),
    ) {
        when (phase) {
            is DictationSession.Phase.Review -> ReviewHold(
                transcript = state.transcript,
                note = phase.note,
                onSend = onSend,
                onDiscard = onDiscard,
            )
            else -> Listening(
                transcript = state.transcript,
                stopping = phase is DictationSession.Phase.Stopping,
                targetSessionTitle = targetSessionTitle,
                ambient = ambient,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun Listening(
    transcript: String,
    stopping: Boolean,
    targetSessionTitle: String?,
    ambient: Boolean,
    onStop: () -> Unit,
) {
    // §7: concentric circles 150/104/64 px at the 450 ref → 75/52/32 dp.
    // A slow breathing pulse on the outer two says "live"; frozen in ambient
    // (Halo's ambient rendering is static) and while stopping.
    val pulse = if (ambient || stopping) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "listeningPulse")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulse",
        )
        p
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(transcript) { scrollState.scrollTo(scrollState.maxValue) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onStop)
            .padding(horizontal = Halo.Geo.SafeInset, vertical = 14.dp)
            .testTag("haloListeningTap"),
    ) {
        Canvas(modifier = Modifier.size(75.dp)) {
            val c = Halo.Palette.WaitingForYou
            val stroke = Stroke(width = 1.5.dp.toPx())
            drawCircle(c.copy(alpha = 0.35f + 0.25f * pulse), radius = (75.dp.toPx() / 2f) * (0.92f + 0.08f * pulse), style = stroke)
            drawCircle(c.copy(alpha = 0.6f + 0.3f * pulse), radius = (52.dp.toPx() / 2f) * (0.95f + 0.05f * pulse), style = stroke)
            drawCircle(c, radius = 32.dp.toPx() / 2f)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 54.dp)
                .verticalScroll(scrollState),
        ) {
            Text(
                text = transcript.ifEmpty { if (stopping) "…" else "listening…" },
                fontSize = Halo.Type.Title,
                color = if (transcript.isEmpty()) Halo.Palette.TextSecondary else Halo.Palette.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("haloListeningTranscript"),
            )
        }
        Spacer(Modifier.height(6.dp))
        // Two lines, not one: session titles run long on the watch and a
        // single "to {title} · tap to send" line lost the affordance first.
        Text(
            text = if (stopping) "finishing…" else "tap to send",
            fontSize = Halo.Type.Min,
            color = Halo.Palette.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.testTag("haloListeningHint"),
        )
        if (targetSessionTitle != null) {
            // Two lines: session titles on the watch run long, and a single
            // line ran off the round edge (user report, 2026-09-05).
            Text(
                text = "to $targetSessionTitle",
                fontSize = Halo.Type.Min,
                color = Halo.Palette.TextFaint,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Back (or a recogniser that quit early) parks the text here: Send or Discard. */
@Composable
private fun ReviewHold(
    transcript: String,
    note: String?,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(Halo.Geo.SafeInset)
            .testTag("haloListeningReview"),
    ) {
        Text(
            text = transcript.ifEmpty { "nothing heard" },
            fontSize = Halo.Type.Body,
            color = Halo.Palette.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 4,
            modifier = Modifier.testTag("haloListeningReviewText"),
        )
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                fontSize = Halo.Type.Min,
                color = Halo.Palette.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            VoicePill(
                label = "Discard",
                tag = "haloListeningDiscard",
                filled = false,
                onClick = onDiscard,
                modifier = Modifier.weight(0.45f),
            )
            VoicePill(
                label = "Send",
                tag = "haloListeningSend",
                filled = true,
                onClick = onSend,
                modifier = Modifier.weight(0.55f),
            )
        }
    }
}
