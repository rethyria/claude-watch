// The v2 session feed (Halo v2 S6, epic #94): chrome-free — no header, no
// arrows, no waiting banner. The terminal tail fills the screen inside a
// soft-edged legibility band (offscreen compositing + vertical DstIn fade,
// #116 — sized so every line in the band reads edge to edge on the round
// face, while a scrolling line still dissolves before it can reach the ring
// channel at any scroll position) and scrolls by TOUCH and rotary on a
// reversed list. Navigation is gestural:
// swipe right = back to the session list (sibling cycling died with the
// header — position lives in the list pager now; the at-top pull-down back
// died in the v3 vertical purge, #109 — vertical drags are scroll and ONLY
// scroll), and while the session waits the whole feed surface is the prompt's
// tap target. The dictate pill (and its honest "unavailable" variant, issue #78)
// keeps the bottom slot when nothing is waiting. The full-circle feed ring
// (S7) shows through from the root host — the mask keeps text off it.
// px values are at the 450 reference (≈ px/2 in dp, matching HaloTheme).
package dev.claudewatch.wear.ui.halo

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import dev.claudewatch.shared.terminal.TerminalLine
import dev.claudewatch.shared.terminal.TerminalLineType
import dev.claudewatch.wear.BridgeViewModel
import dev.claudewatch.wear.R

/** Back-swipe threshold ≈60px at the 450 reference (the app-wide gesture unit). */
private const val BACK_SWIPE_FRACTION = 60f / HALO_REF_PX

/** A swipe suppresses the synthetic tap that can follow it on-device. */
private const val TAP_GUARD_MS = 300L

/**
 * The feed's legibility band (#116, superseding the epic's radial mask): on
 * the round face the radial fade kept ink off the ring but not on the glass —
 * a full-width line's ENDS left the readable circle as the line left the
 * vertical centre, and at the resting extremes the corners sat beyond even
 * the fade's end (the user's "parts of the top and bottom are not visible").
 * What a circle actually grants full-width text is a horizontal band: with
 * lines spanning ±163 ref-px of centre (the side insets), ink may reach at
 * most √(211² − 163²) ≈ 134 of the vertical centre before its corners cross
 * the ring channel's inner stroke edge (214 − 6/2 = 211). So the mask is a
 * VERTICAL fade — fully opaque within ±114 of centre, gone by ±133 — making
 * every line inside the band legible edge to edge, while a scrolling line
 * still dissolves before it can clip the ring, whatever the scroll position.
 * Still drawn as a DstIn over the offscreen-composited list layer: DstIn
 * against the layer's own alpha, not the black beneath.
 */
private const val MASK_OPAQUE_PX = 114f
private const val MASK_CLEAR_PX = 133f

/**
 * v2 feed insets, vertical pair re-derived for #116: where content RESTS.
 * contentPadding, not outer padding, on purpose — an outer pad would
 * hard-clip lines at the inset edge, while resting insets let a mid-scroll
 * line ride through them into the mask's fade band and dissolve instead of
 * shearing. Top/bottom sit the resting extreme lines 112 ref-px from centre,
 * just inside the mask's ±114 opaque band, so scrolled-to-top and
 * scrolled-to-bottom both show a COMPLETE first/last line — the epic's
 * 30/48dp rested them in (top: beyond) the old fade. Sides stay at the
 * epic's 31dp: the user's clipping is vertical, and narrowing the lines
 * would shrink the feed beyond what the fix needs.
 */
private val FEED_TOP_INSET = 56.dp
private val FEED_BOTTOM_INSET = 56.dp
private val FEED_SIDE_INSET = 31.dp

@Composable
fun HaloSessionFeed(
    model: HaloModel,
    sessionId: String,
    ui: BridgeViewModel.UiState,
    onOpenCard: () -> Unit,
    onDictate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * False while an overlay (card, voice, offline takeover) owns rotary
     * focus above this feed (#121). Keyed into the tail's focus claim so the
     * bezel is re-requested when the overlay closes — the pager's reclaim
     * pattern; a one-shot claim left the crown dead after any card or voice
     * round trip while touch kept working.
     */
    rotaryActive: Boolean = true,
) {
    val session = model.sessions.firstOrNull { it.id == sessionId }
    if (session == null) {
        // Killed/pruned under us; HaloApp's model-shrink effect backs out a
        // frame later — render a placeholder rather than crash or ghost.
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "session ended",
                fontSize = Halo.Type.Body,
                color = Halo.Palette.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    // The swipe detector lives in a pointerInput(Unit) that never restarts;
    // this keeps it calling the current back, not a stale capture.
    val back by rememberUpdatedState(onBack)
    var lastSwipeAtMs by remember { mutableLongStateOf(0L) }
    val systemBackInFlight = LocalHaloSystemBackInFlight.current

    Box(
        modifier = modifier
            .fillMaxSize()
            // Swipe right = back to the list (nav's back() preserves this
            // session as the pager selection, #95). Horizontal only:
            // vertical drags belong to the tail's touch scroll — content
            // scrolling, never navigation (v3). Deltas are CONSUMED so the
            // tap-to-prompt clickable below sees the gesture as claimed and
            // cancels its press — the uptime tap guard stays as the second
            // line of defence.
            .pointerInput(Unit) {
                val threshold = size.width * BACK_SWIPE_FRACTION
                // #109: a system back gesture's edge swipe reads here as a
                // rightward drag — stand down; the root handler's completion
                // is the one back (SystemBackDragClaim).
                val claim = SystemBackDragClaim(systemBackInFlight)
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        claim.start()
                        total = 0f
                    },
                    onDragEnd = {
                        if (!claim.owns && total > threshold) {
                            lastSwipeAtMs = SystemClock.uptimeMillis()
                            back()
                        }
                    },
                ) { change, dragAmount ->
                    claim.update()
                    total += dragAmount
                    change.consume()
                }
            }
            .testTag("haloFeed-${session.id}"),
    ) {
        // Tap-to-prompt: while THIS session waits, the whole feed surface is
        // the prompt's tap target (the waiting banner's successor), routed
        // through the same prompt-pinning onOpenCard. No pending → no click
        // handler AT ALL, not a disabled one — an inert target would still
        // announce a click action the session cannot honour.
        val feedTap = if (session.pending != null) {
            Modifier
                .clickable {
                    if (SystemClock.uptimeMillis() - lastSwipeAtMs > TAP_GUARD_MS) onOpenCard()
                }
                .testTag("haloFeedTap")
        } else {
            Modifier
        }
        val bridgeSession = ui.bridge.sessions[sessionId]
        Box(modifier = Modifier.fillMaxSize().then(feedTap)) {
            FeedTail(
                lines = bridgeSession?.terminal?.items ?: emptyList(),
                thinking = bridgeSession?.thinking == true,
                rotaryActive = rotaryActive,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (session.pending == null) {
            if (session.dictatable) {
                DictatePill(
                    onDictate = onDictate,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                // Honest affordance (issue #78): a session the bridge can't
                // reach live (a PTY-less hook session) shows WHY there's no
                // Dictate, instead of a pill that would silently do nothing.
                DictateUnavailablePill(modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

// ── The terminal tail ───────────────────────────────────────────────────────

@Composable
private fun FeedTail(
    lines: List<TerminalLine>,
    thinking: Boolean,
    rotaryActive: Boolean,
    modifier: Modifier = Modifier,
) {
    // The empty state composes INSTEAD of the LazyColumn below, so it must be
    // decided before the FocusRequester's LaunchedEffect: requesting focus
    // while the rotaryScrollable node (the only thing the requester ever
    // attaches to) is not composed throws IllegalStateException — and every
    // fresh session and orphan prompt starts with an empty terminal.
    if (lines.isEmpty() && !thinking) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "no output yet",
                fontSize = Halo.Type.Min,
                color = Halo.Palette.TextSecondary,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    // Keyed on the reclaim flag, NOT one-shot (#121): the overlays above this
    // feed take rotary focus while up, and their unmount clears focus without
    // restoring it — only the flag flipping true re-arms the crown.
    LaunchedEffect(rotaryActive) { if (rotaryActive) focusRequester.requestFocus() }

    // Stable per-line keys, so a reading position held in history survives the
    // stream appending (and the 200-line ring dropping) lines: without keys
    // the viewport is anchored by INDEX and drifts one row per event. The
    // RingBuffer keeps no monotonic counter, so the absolute index of the
    // oldest retained line is reconstructed here by diffing successive lists.
    val keyState = remember { FeedKeyState() }
    if (keyState.lines !== lines || keyState.thinking != thinking) {
        keyState.base += droppedCount(keyState.lines, lines)
        keyState.lines = lines
        keyState.thinking = thinking
        // Key-based anchoring holds the viewport on the line it shows — which
        // at the tail means NOT following new output. Reading the position
        // here (pre-measure, so still the pre-append position; unobserved so
        // scrolling doesn't recompose us) and re-requesting index 0 keeps the
        // tail pinned; requestScrollToItem overrides key anchoring for
        // exactly the next remeasure.
        val atTail = Snapshot.withoutReadObservation {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
        if (atTail) listState.requestScrollToItem(0)
    }

    // No stretch-overscroll (API 31+): a bound-hit stretch would smear the
    // masked tail through the fade band — the feed rests still at its ends.
    // (Originally load-bearing for the retired at-top pull-down back's
    // nested-scroll leftovers, gone in the v3 purge.)
    @OptIn(ExperimentalFoundationApi::class)
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        // Bottom-anchored via reverseLayout: index 0 is the NEWEST line pinned
        // to the bottom edge, and touch scrolling (v2) runs the reversed axis —
        // dragging down walks into history.
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(
                start = FEED_SIDE_INSET,
                end = FEED_SIDE_INSET,
                top = FEED_TOP_INSET,
                bottom = FEED_BOTTOM_INSET,
            ),
            modifier = modifier
                // The mask: composite the whole list offscreen, then keep only
                // what the vertical gradient covers (DstIn) — the legibility
                // band, gone before the ring stroke. Layer alpha, not the
                // black background, is what the blend erases into. Clamped
                // tiling keeps everything above/below the band erased too.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val scale = size.minDimension / HALO_REF_PX
                    val half = MASK_CLEAR_PX * scale
                    val fade = (MASK_CLEAR_PX - MASK_OPAQUE_PX) / (2f * MASK_CLEAR_PX)
                    val mask = Brush.verticalGradient(
                        0f to Color.Transparent,
                        fade to Color.White,
                        1f - fade to Color.White,
                        1f to Color.Transparent,
                        startY = size.height / 2f - half,
                        endY = size.height / 2f + half,
                    )
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush = mask, blendMode = BlendMode.DstIn)
                    }
                }
                .rotaryScrollable(
                    behavior = RotaryScrollableDefaults.behavior(listState),
                    focusRequester = focusRequester,
                    // The rotary behavior drives scrollBy toward higher indices,
                    // which reverseLayout renders UPWARD — reversed here so the
                    // crown direction matches the session list's.
                    reverseDirection = true,
                ),
        ) {
            if (thinking) {
                item(key = "thinking") {
                    Box(modifier = Modifier.testTag("haloThinking")) {
                        FeedLine(TerminalLine("…", TerminalLineType.SYSTEM))
                    }
                }
            }
            // Newest-first to match reverseLayout's index order; keys count from
            // the base so a line keeps its key as older lines fall off the ring.
            items(
                count = lines.size,
                key = { i -> keyState.base + (lines.size - 1 - i) },
            ) { i -> FeedLine(lines[lines.size - 1 - i]) }
        }
    }
}

/**
 * Composition-local bookkeeping for [FeedTail]'s stable keys: the last list
 * rendered and the absolute stream index of its first element. Deliberately
 * not snapshot state — it is read and written only inside composition, in the
 * same pass that rebuilds the item keys.
 */
private class FeedKeyState {
    var lines: List<TerminalLine> = emptyList()
    var thinking = false
    var base = 0L
}

/**
 * How many lines the ring dropped between [old] and [new], recovered from the
 * append-only contract: `new` is `old` minus some head plus some tail. The
 * largest suffix/prefix overlap decides; repeated identical lines can make it
 * overestimate the overlap, which only shifts every key by the same amount —
 * anchoring then lands on an identical-looking line, which is acceptable.
 */
private fun droppedCount(old: List<TerminalLine>, new: List<TerminalLine>): Int {
    for (overlap in minOf(old.size, new.size) downTo 1) {
        var match = true
        for (j in 0 until overlap) {
            if (old[old.size - overlap + j] != new[j]) {
                match = false
                break
            }
        }
        if (match) return old.size - overlap
    }
    return old.size
}

/**
 * Type → style per handoff §4. The pipeline has no dedicated "agent prose"
 * line type: assistant text arrives as OUTPUT with the formatter's "[codex] "
 * source prefix (ToolOutputFormatter's CodexMessage branch), so that prefix
 * is the discriminator; all other OUTPUT is tool results. "> " marks the
 * user's own dictated/echoed commands (BridgeState.echoCommand).
 */
@Composable
private fun FeedLine(line: TerminalLine) {
    val text: AnnotatedString
    val color: Color
    var family: FontFamily? = FontFamily.Monospace
    var size = 11.5.sp // tool calls & results: 23px mono
    var weight: FontWeight? = null

    when (line.type) {
        TerminalLineType.COMMAND, TerminalLineType.SYSTEM ->
            if (line.text.startsWith("> ")) { // user entry: 24px medium
                text = AnnotatedString(line.text)
                color = Halo.Palette.UserEntry
                family = null
                size = 12.sp
                weight = FontWeight.Medium
            } else { // tool call / meta
                text = AnnotatedString(line.text)
                color = Halo.Palette.TextSecondary
            }
        TerminalLineType.OUTPUT ->
            if (line.text.startsWith("[codex] ")) { // agent prose: 25px Roboto
                text = AnnotatedString(line.text.removePrefix("[codex] "))
                color = Halo.Palette.TextPrimary
                family = null
                size = Halo.Type.Body
            } else { // result
                text = highlightPassCounts(line.text)
                color = Halo.Palette.TextSecondary
            }
        // Assistant prose (#79): the same speech treatment the `[codex] `
        // branch above applies, but chosen from the line's TYPE instead of
        // sniffing its text — proportional, brightest role, body size.
        TerminalLineType.PROSE -> {
            text = AnnotatedString(line.text)
            color = Halo.Palette.TextPrimary
            family = null
            size = Halo.Type.Body
        }
        TerminalLineType.ERROR -> {
            text = AnnotatedString(line.text)
            color = Halo.Palette.Error
        }
    }

    Text(
        text = text,
        fontSize = size,
        lineHeight = size * 1.3f,
        fontFamily = family,
        fontWeight = weight,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    )
}

/** "12 passed", "3 passing", "✓" light up green inside result lines. */
private val PASS_COUNT = Regex("""\b\d+\s+pass(?:ed|ing)?\b|✓""", RegexOption.IGNORE_CASE)

private fun highlightPassCounts(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    for (match in PASS_COUNT.findAll(text)) {
        addStyle(SpanStyle(color = Halo.Palette.Running), match.range.first, match.range.last + 1)
    }
}

// ── Bottom slot: dictate pill ───────────────────────────────────────────────

@Composable
private fun DictateUnavailablePill(modifier: Modifier = Modifier) {
    // Same footprint and the SAME microphone icon as DictatePill, struck with
    // a ⊘ and muted (#104 user feedback, superseding #78's text pill — the
    // crossed mic says "no dictation here" without words). The honest-
    // unavailability semantics survive intact: the bridge cannot deliver
    // dictation into this session live (issue #78), so the pill carries NO
    // click handler at all — a disabled-looking target that still announced
    // a click action would lie to a screen reader.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(Halo.Geo.TouchMin)
            .testTag("haloDictateUnavailable"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .background(Halo.Palette.InsetWell, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 5.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.halo_mic),
                contentDescription = "Dictation unavailable",
                tint = Halo.Palette.TextFaint,
                modifier = Modifier.size(Halo.Geo.MicGlyph),
            )
            MicOffOverlay()
        }
    }
}

/** The crossed circle over the muted mic: ⊘ in the app's own idiom (the
 *  external-session hide glyph), drawn to enclose the icon's ink. Solidus
 *  orientation matches the ⊘ character the menu's hide row renders. */
@Composable
private fun MicOffOverlay() {
    Canvas(modifier = Modifier.size(Halo.Geo.MicOffOverlay).testTag("haloDictateMicOff")) {
        val stroke = 1.5.dp.toPx()
        val r = size.minDimension / 2f - stroke / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = Halo.Palette.TextFaint,
            radius = r,
            center = c,
            style = Stroke(width = stroke),
        )
        // The slash's endpoints sit ON the circle (r/√2 out from centre), so
        // circle and solidus read as one struck-through glyph, not a line
        // taped over a ring.
        val d = r * 0.7071f
        drawLine(
            color = Halo.Palette.TextFaint,
            start = Offset(c.x - d, c.y + d),
            end = Offset(c.x + d, c.y - d),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun DictatePill(onDictate: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(Halo.Geo.TouchMin)
            .clickable(onClick = onDictate)
            .testTag("haloDictate"),
    ) {
        // The visual pill is smaller than the 48dp-tall full-width tap area,
        // and hugs its icon (#116 user feedback: "much wider than it needs to
        // be" — the 88dp floor was the retired TEXT pill's footprint,
        // outliving its label when #104 swapped in the icon). The icon sits
        // in a cell the ⊘ overlay's size, so this pill and the unavailable
        // variant keep one footprint.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .background(Halo.Palette.Surface2, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 5.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(Halo.Geo.MicOffOverlay),
            ) {
                // A real microphone icon (#104 user feedback, superseding the
                // hand-drawn Canvas glyph that held this slot pre-v2).
                Icon(
                    painter = painterResource(R.drawable.halo_mic),
                    contentDescription = "Dictate",
                    tint = Halo.Palette.TextPrimary,
                    modifier = Modifier.size(Halo.Geo.MicGlyph).testTag("haloDictateMic"),
                )
            }
        }
    }
}
