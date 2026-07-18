// Issue #57 — the usage page LEFT of home: one bar per plan window the bridge
// reports (render-what-you-get: 5-hour session, weekly all-models, weekly
// model-scoped today — but any entry the wire carries gets a bar, so new
// upstream windows appear without an app update). Re-skinned per the Halo
// usage design: a tappable REMAINING/USED eyebrow (screen-local mode, the
// wire percent stays USED), chord-fitted row widths that hug the round
// display, semantic tiers computed from REMAINING (green / terracotta-low /
// red-out), pulsing skeleton rows while loading, and a filled Retry pill on
// error. Glanceable by design: label + bar + reset time, no charts, no
// scrolling for the expected three bars, no centerpiece and no drill-down
// (HaloNav no-ops both). Fetch-on-open drives the states: skeletons while
// loading, bars on data (with an "as of Xm ago" caveat when the bridge served
// its cache fallback), message + retry on error. Round-safe: every row is cut
// to the chord of the circle at its own vertical position.
package dev.claudewatch.wear.ui.halo

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import dev.claudewatch.wear.BridgeViewModel.UsageLimit
import dev.claudewatch.wear.BridgeViewModel.UsageUi
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ─── Pure presentation math (plain functions so JVM unit tests can pin the ───
// ─── design's numbers without composing anything) ────────────────────────────

/**
 * Semantic tier of a window, ALWAYS computed from what is left — the wire
 * percent is USED, so remaining = 100 − wire. The REMAINING/USED display mode
 * never feeds into this: a drained window is red no matter which number is
 * on screen.
 */
internal enum class UsageTier { OUT, LOW, NORMAL }

internal fun usageTier(wirePercent: Double): UsageTier {
    val remaining = 100.0 - wirePercent
    return when {
        remaining <= 0.0 -> UsageTier.OUT
        // Below 20% left = the "waiting for you" terracotta, per the Halo
        // usage design (same threshold the pre-redesign screen used).
        remaining < 20.0 -> UsageTier.LOW
        else -> UsageTier.NORMAL
    }
}

/**
 * Presentation-only display names, per the Halo usage design: the two kinds
 * every plan carries get friendly names; anything else keeps its wire label
 * as-is (render-what-you-get, e.g. the model-scoped "Fable" window).
 */
internal fun usageDisplayName(kind: String, label: String): String = when (kind) {
    "session" -> "Session"
    "weekly_all" -> "Weekly"
    else -> label
}

/**
 * The percent NUMBER shown for a window: remaining (100 − wire) in REMAINING
 * mode, the wire's used percent in USED mode — clamped to 0..100 so a wire
 * overshoot never renders "104%" or "-4%".
 */
internal fun usageShownPercent(wirePercent: Double, usedMode: Boolean): Int {
    val shown = if (usedMode) wirePercent else 100.0 - wirePercent
    return shown.coerceIn(0.0, 100.0).roundToInt()
}

/**
 * The bar's fill fraction (0..1) — the shown percent, except a drained window
 * in USED mode pins to a FULL bar per the Halo usage design: "you used it
 * all" must never read as a nearly-full-but-not-quite bar.
 */
internal fun usageBarFraction(wirePercent: Double, usedMode: Boolean): Float {
    if (usedMode && usageTier(wirePercent) == UsageTier.OUT) return 1f
    val shown = if (usedMode) wirePercent else 100.0 - wirePercent
    return (shown.coerceIn(0.0, 100.0) / 100.0).toFloat()
}

/**
 * Chord-fitted row widths, in px at the 450 reference, per the Halo usage
 * design: row i of n sits dy = (i − (n−1)/2) · pitch from the vertical
 * center, and gets 97% of the circle's chord at that height (R = 169),
 * floored at the chord's 115-half-width so far-out rows stay usable and
 * capped at 336 (the design's max content width). Rows are individually
 * centered, so the stack hugs the round display instead of fighting it.
 * Pitch tightens as rows are added: 63 for the expected ≤3, 54 for 4, 46
 * beyond.
 */
internal fun usageChordWidthsPx(n: Int): List<Int> {
    val pitch = when {
        n <= 3 -> 63.0
        n == 4 -> 54.0
        else -> 46.0
    }
    val r = 169.0
    return List(n) { i ->
        val dy = (i - (n - 1) / 2.0) * pitch
        min(336, (2.0 * sqrt(max(r * r - dy * dy, 115.0 * 115.0)) * 0.97).roundToInt())
    }
}

/**
 * "resets 19:10" for the 5-hour session window (it lands today), "resets Fri"
 * for the weekly kinds — and for any UNKNOWN kind, whose cadence we cannot
 * guess (render-what-you-get includes the reset line). A malformed/absent
 * resetsAt yields null: the bar renders without a reset line, never a crash
 * and never a dropped bar. Pure, so plain-JVM tests can pin the formats.
 */
internal fun usageResetLabel(kind: String, resetsAt: String?): String? {
    if (resetsAt == null) return null
    val parsed = try {
        OffsetDateTime.parse(resetsAt)
    } catch (_: Exception) {
        return null
    }
    val local = parsed.atZoneSameInstant(ZoneId.systemDefault())
    val pattern = if (kind == "session") "HH:mm" else "EEE"
    return "resets " + local.format(DateTimeFormatter.ofPattern(pattern))
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun HaloUsageScreen(
    usage: UsageUi,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    // REMAINING vs USED is screen-local UI state (not wire state — the wire
    // percent is always USED): rememberSaveable so a process-recreation
    // keeps the reader's choice, default REMAINING per the design.
    var usedMode by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = modifier.fillMaxSize().testTag("haloUsage"),
        contentAlignment = Alignment.Center,
    ) {
        when (usage) {
            // Idle renders like Loading: the page's fetch-on-open fires the
            // moment it becomes current, so Idle is at most one frame old —
            // a distinct empty state would only flash.
            UsageUi.Idle, UsageUi.Loading -> UsageSkeleton(
                usedMode = usedMode,
                onToggleMode = { usedMode = !usedMode },
            )
            is UsageUi.Error -> UsageError(message = usage.message, onRetry = onRetry)
            is UsageUi.Data -> UsageData(
                data = usage,
                usedMode = usedMode,
                onToggleMode = { usedMode = !usedMode },
                nowMs = nowMs,
            )
        }
    }
}

/**
 * The REMAINING/USED eyebrow — the mode toggle. Tapping it is the whole
 * feature: no settings, no long-press, just flip the reading. Also shown
 * (dimmed) over the loading skeletons so the affordance never jumps around.
 */
@Composable
private fun UsageEyebrow(usedMode: Boolean, onToggle: () -> Unit, dimmed: Boolean = false) {
    // The clickable wraps a Box, not the Text: the drawn label is ~10dp tall
    // and the toggle is this feature's ONLY entry point — a text-bounds hit
    // area is nearly untappable with a finger (review finding). The Box
    // expands the target toward the 48dp wear minimum while the label's
    // visual position stays put (the same composable renders in every state,
    // so the enlarged footprint never makes the layout jump between states).
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 24.dp)
            .clickable(onClick = onToggle)
            .testTag("haloUsageMode"),
    ) {
        Text(
            text = if (usedMode) "USED" else "REMAINING",
            fontSize = 9.5.sp, // 19px eyebrow at the 450 ref
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.14.em,
            color = Halo.Palette.TextFaint,
            modifier = Modifier.alpha(if (dimmed) 0.7f else 1f),
        )
    }
}

// ─── Data state ──────────────────────────────────────────────────────────────

@Composable
private fun UsageData(
    data: UsageUi.Data,
    usedMode: Boolean,
    onToggleMode: () -> Unit,
    nowMs: () -> Long,
) {
    val widthsPx = usageChordWidthsPx(data.limits.size)
    // 4+ rows tighten the typography so the stack still fits the circle.
    val compact = data.limits.size >= 4
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // 17px stack gap at the 450 ref, vertically centered in the circle.
        verticalArrangement = Arrangement.spacedBy(8.5.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp), // 4px under the eyebrow
        ) {
            UsageEyebrow(usedMode = usedMode, onToggle = onToggleMode)
            // The bridge served its CACHE fallback (its upstream call
            // failed): the bars are real but old — say how old, right under
            // the eyebrow, instead of pretending live.
            if (data.source == "cache" && data.fetchedAtMs != null) {
                val ageMin = ((nowMs() - data.fetchedAtMs) / 60_000L).coerceAtLeast(0L)
                Text(
                    text = "as of ${ageMin}m ago",
                    fontSize = 9.5.sp, // 19px caveat
                    color = Halo.Palette.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("haloUsageStale"),
                )
            }
        }
        if (data.limits.isEmpty()) {
            // A 200 with zero windows (plan without limits?): honest empty
            // state rather than a blank page.
            Text(
                text = "no usage windows reported",
                fontSize = 11.sp, // 22px
                color = Halo.Palette.TextFaint,
                textAlign = TextAlign.Center,
            )
        }
        data.limits.forEachIndexed { i, limit ->
            UsageRow(limit = limit, widthPx = widthsPx[i], compact = compact, usedMode = usedMode)
        }
    }
}

/** Header line (name · reset · percent) + tiered bar — one window. */
@Composable
private fun UsageRow(limit: UsageLimit, widthPx: Int, compact: Boolean, usedMode: Boolean) {
    val tier = usageTier(limit.percent)
    // Tier colors ALWAYS derive from remaining, never from the shown number:
    // flipping to USED must not turn a drained red row green.
    val fillColor = when (tier) {
        UsageTier.OUT -> Halo.Palette.Error
        UsageTier.LOW -> Halo.Palette.WaitingForYou
        UsageTier.NORMAL -> Halo.Palette.Running
    }
    val percentColor = when (tier) {
        UsageTier.OUT -> Halo.Palette.Error
        UsageTier.LOW -> Halo.Palette.WaitingForYou
        UsageTier.NORMAL -> Halo.Palette.TextPrimary
    }
    Column(
        // Header-to-bar gap: 8px, tightened to 5px in compact stacks.
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.5.dp else 4.dp),
        // The chord-fitted width (px/2 in dp at the 450 ref, as everywhere
        // in Halo); the parent centers each row on the circle's axis.
        modifier = Modifier.width((widthPx / 2f).dp),
    ) {
        // SpaceBetween + a single weighted LEFT CLUSTER, not two weighted
        // children: weighting both the name (fill=false) and a pusher spacer
        // made Compose split the free space 50/50, ellipsizing short names
        // ("Sessi…") while stranding the percent shy of the chord's right
        // edge (review finding, traced through RowColumnMeasurePolicy). This
        // shape is margin-left:auto: the cluster takes what it needs, all
        // leftover lands between it and the percent, and only a genuinely
        // colliding name ellipsizes.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(modifier = Modifier.alignByBaseline().weight(1f, fill = false)) {
                Text(
                    text = usageDisplayName(limit.kind, limit.label),
                    fontSize = 11.sp, // 22px window name
                    color = Halo.Palette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                )
                usageResetLabel(limit.kind, limit.resetsAt)?.let { resets ->
                    Spacer(modifier = Modifier.width(5.dp)) // 10px baseline gap
                    Text(
                        text = resets,
                        fontSize = 9.5.sp, // 19px reset time
                        color = Halo.Palette.TextFaint,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            Text(
                text = "${usageShownPercent(limit.percent, usedMode)}%",
                fontSize = if (compact) 12.5.sp else 15.sp, // 25px compact / 30px
                fontWeight = FontWeight.Medium,
                color = percentColor,
                // Floor of 10px between cluster and percent even when a long
                // name has eaten all the SpaceBetween slack.
                modifier = Modifier.alignByBaseline().padding(start = 5.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp) // 8px bar
                .clip(RoundedCornerShape(2.dp)) // 4px radius; clips the fill's square end
                .background(Halo.Palette.Idle),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = usageBarFraction(limit.percent, usedMode))
                    .fillMaxHeight()
                    .background(fillColor, RoundedCornerShape(2.dp)),
            )
        }
    }
}

// ─── Loading state ───────────────────────────────────────────────────────────

/** Skeleton rows shown while loading — mirrors the expected three windows. */
private const val SKELETON_ROWS = 3

@Composable
private fun UsageSkeleton(usedMode: Boolean, onToggleMode: () -> Unit) {
    val widthsPx = usageChordWidthsPx(SKELETON_ROWS)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.5.dp, Alignment.CenterVertically), // 17px
        modifier = Modifier.fillMaxSize(),
    ) {
        UsageEyebrow(usedMode = usedMode, onToggle = onToggleMode, dimmed = true)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.5.dp), // 17px between rows
            modifier = Modifier.testTag("haloUsageLoading"),
        ) {
            repeat(SKELETON_ROWS) { i -> UsageSkeletonRow(widthPx = widthsPx[i], index = i) }
        }
    }
}

/**
 * One pulsing placeholder row: header rects (96×15 name, 58×22 percent at the
 * 450 ref) + a bar-height track, all in the skeleton grey. The pulse is the
 * design's uPulse — alpha 0.5↔1 over 1.2s ease-in-out, staggered 0.18s per
 * row — built on rememberInfiniteTransition like the feed's thinking cursor,
 * so the compose test clock treats it as idle instead of hanging on it.
 */
@Composable
private fun UsageSkeletonRow(widthPx: Int, index: Int) {
    val transition = rememberInfiniteTransition(label = "usageSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Half the 1.2s pulse per direction; CSS ease-in-out curve.
            animation = tween(durationMillis = 600, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
            repeatMode = RepeatMode.Reverse,
            // 0.18s stagger per row, per the Halo usage design.
            initialStartOffset = StartOffset(index * 180),
        ),
        label = "skeletonAlpha",
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp), // 8px header-to-track
        modifier = Modifier.width((widthPx / 2f).dp).alpha(alpha),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp, 7.5.dp) // 96×15 name placeholder
                    .background(Halo.Palette.SkeletonFill, RoundedCornerShape(4.dp)), // 8px radius
            )
            Box(
                modifier = Modifier
                    .size(29.dp, 11.dp) // 58×22 percent placeholder
                    .background(Halo.Palette.SkeletonFill, RoundedCornerShape(4.dp)),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp) // 8px track
                .background(Halo.Palette.SkeletonFill, RoundedCornerShape(2.dp)),
        )
    }
}

// ─── Error state ─────────────────────────────────────────────────────────────

@Composable
private fun UsageError(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), // 12px
        modifier = Modifier.fillMaxSize().padding(horizontal = Halo.Geo.SafeInset),
    ) {
        Text(
            text = "usage unavailable",
            fontSize = 13.5.sp, // 27px headline
            fontWeight = FontWeight.Medium,
            color = Halo.Palette.Error,
            textAlign = TextAlign.Center,
        )
        // The dynamic failure detail (bridge error string / transport
        // message) — one honest line under the fixed headline.
        Text(
            text = message,
            fontSize = 10.5.sp, // 21px detail
            color = Halo.Palette.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The retry re-calls onUsageOpen — the same fetch the page entry
        // fires, so retry and re-entry are indistinguishable to the VM.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 4.dp) // extra 8px above the pill
                .height(32.dp) // 64px pill
                .clip(CircleShape) // fully rounded
                .background(Halo.Palette.WaitingForYou)
                .clickable(onClick = onRetry)
                .testTag("haloUsageRetry")
                .padding(horizontal = 20.dp), // 40px side padding
        ) {
            Text(
                text = "Retry",
                fontSize = 12.sp, // 24px
                fontWeight = FontWeight.Medium,
                color = Halo.Palette.ApproveText,
            )
        }
    }
}
