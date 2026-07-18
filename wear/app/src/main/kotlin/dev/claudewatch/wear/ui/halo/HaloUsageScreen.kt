// Issue #57 — the usage page LEFT of home: one bar per plan window the bridge
// reports (render-what-you-get: 5-hour session, weekly all-models, weekly
// model-scoped today — but any entry the wire carries gets a bar, so new
// upstream windows appear without an app update). Re-skinned per the Halo
// usage design: a decorative top clock (the InnerScreen TimeText idiom), a
// tappable REMAINING/USED eyebrow (screen-local mode, the wire percent stays
// USED), chord-fitted row widths that hug the round display, semantic tiers
// that are SEVERITY-FIRST (the server's own `severity` coding wins when
// present and non-"normal"; local REMAINING thresholds — orange at 75% used,
// red at 95% — are only the fallback and can only be escalated, never
// downgraded), pulsing skeleton rows while loading, and a filled Retry pill
// on error. Glanceable by design: label + bar + reset time, no charts, no
// scrolling for the expected three bars, no centerpiece and no drill-down
// (HaloNav no-ops both). Fetch-on-open drives the states: skeletons while
// loading, bars on data (with an "as of 5 minutes ago" freshness label under
// the last bar once the data is MORE THAN A MINUTE old — under that the label
// stays silent; tapping it is a manual force-refresh), message + retry on
// error. A ~30s ticker keeps the now-derived labels honest while the page
// sits open.
// Round-safe: every row is cut
// to the chord of the circle at its own vertical position — which is why the
// expected ≤3-row stack is centered by ITSELF (the header pins to the top):
// usageChordWidthsPx assumes the rows straddle dead center, so the chords are
// honest only when they actually do.
package dev.claudewatch.wear.ui.halo

import android.content.Context
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.LocalTextStyle
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TimeTextDefaults
import dev.claudewatch.wear.BridgeViewModel.UsageLimit
import dev.claudewatch.wear.BridgeViewModel.UsageUi
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay

// ─── Pure presentation math (plain functions so JVM unit tests can pin the ───
// ─── design's numbers without composing anything) ────────────────────────────

/**
 * Semantic tier of a window — SEVERITY-FIRST. The upstream carries its own
 * `severity` color coding per window (observed value: "normal"), whose exact
 * thresholds are not documented anywhere — so the SERVER's word wins when it
 * says anything non-"normal", and the LOCAL thresholds are only a fallback,
 * set to the user's recollection of the official usage screen: orange at 75%
 * used, red at 95% used. Computed from what is left — the wire percent is
 * USED, so remaining = 100 − wire. The server can only ESCALATE (final tier
 * = the more severe of server and local): a "normal" severity never turns a
 * 96%-used window green. The REMAINING/USED display mode never feeds into
 * this: a drained window is red no matter which number is on screen.
 */
internal enum class UsageTier { OUT, LOW, NORMAL }

internal fun usageTier(wirePercent: Double, severity: String? = null): UsageTier {
    val remaining = 100.0 - wirePercent
    // Local fallback: ≤5 left (≥95% used) is OUT, ≤25 left (≥75% used) is
    // the "waiting for you" terracotta LOW.
    val local = when {
        remaining <= 5.0 -> UsageTier.OUT
        remaining <= 25.0 -> UsageTier.LOW
        else -> UsageTier.NORMAL
    }
    // Server escalation, mapping the undocumented vocabulary conservatively:
    // anything terminal-sounding ("critical", "exceeded", "error",
    // "blocked", …) is OUT; any OTHER non-"normal" value at least earns the
    // terracotta — an unknown severity is the server flagging SOMETHING.
    val server = severity?.lowercase()?.takeUnless { it == "normal" }?.let { s ->
        val terminal = listOf("crit", "exceed", "error", "block").any { it in s }
        if (terminal) UsageTier.OUT else UsageTier.LOW
    } ?: UsageTier.NORMAL
    // Enum order IS severity order (OUT < LOW < NORMAL), so "the more severe
    // of the two" is minOf — the server escalates, never downgrades below
    // the local floor.
    return minOf(local, server)
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
 * all" must never read as a nearly-full-but-not-quite bar. The pin keys on
 * TRULY drained (remaining ≤ 0), deliberately DECOUPLED from tier == OUT:
 * OUT now begins at 5% remaining, and a 95%-used bar must still read as 95%,
 * not 100% — only "you used it all" earns the full bar.
 */
internal fun usageBarFraction(wirePercent: Double, usedMode: Boolean): Float {
    if (usedMode && wirePercent >= 100.0) return 1f
    val shown = if (usedMode) wirePercent else 100.0 - wirePercent
    return (shown.coerceIn(0.0, 100.0) / 100.0).toFloat()
}

/**
 * Chord-fitted row widths, in px at the 450 reference, per the Halo usage
 * design: row i of n sits dy = (i − (n−1)/2) · pitch from the vertical
 * center, and gets 106% of the circle's chord at that height (R = 169),
 * floored at the chord's 115-half-width so far-out rows stay usable and
 * capped at 360 (the widened max content width). The mock's 0.97 chord
 * factor was conservative (user-directed widening, 2026-07-18): 1.06 with
 * the 360 cap widens every row ~6–9% while staying comfortably inside the
 * PHYSICAL circle — even the top n=3 row's 332px sits well under the actual
 * screen chord ≈432px at that height (screen R = 225, dy = 63). Rows are
 * individually centered, so the stack hugs the round display instead of
 * fighting it. Pitch tightens as rows are added: 63 for the expected ≤3, 54
 * for 4, 46 beyond.
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
        min(360, (2.0 * sqrt(max(r * r - dy * dy, 115.0 * 115.0)) * 1.06).roundToInt())
    }
}

/**
 * Time-to-reset aware reset line, as a DEGRADATION LADDER of variants,
 * longest first (2026-07-18 refinements) — one UNIFORM rule keyed on how far
 * away the reset is, not on the window's kind (a 5-hour session window is
 * always < 24h out, so it naturally lands in the relative form;
 * render-what-you-get includes the reset line for unknown kinds too):
 *  - delta ≤ 0   → ["resets soon", "soon"] — clock skew or a just-elapsed
 *    window; the next fetch replaces the window anyway, so no countdown
 *    theater;
 *  - delta < 24h → ["resets in 3h 40m", "reset 3h 40m", "3h 40m"] (the
 *    middle form is literally the user-specified "reset 3h 40m") — hours
 *    OMITTED when zero ("resets in 42m"), minutes always shown, and the
 *    minutes are FLOORED (a 4h 13m 59s delta reads 4h 13m — never
 *    optimistic rounding up);
 *  - delta ≥ 24h → ["resets Sat 10am", "Sat 10am"] — weekday + LOCAL
 *    12-hour clock with lowercase am/pm (12am/12pm correct, never 0am),
 *    minutes only when non-zero ("Sat 10:30am").
 * A malformed/absent resetsAt yields the EMPTY list: the bar renders without
 * a reset line, never a crash and never a dropped bar. The list exists so
 * the row can render the LONGEST variant that actually FITS the width left
 * over by the window's name (see UsageRow): the full label must always be
 * readable, never ellipsized mid-word — only when even the shortest variant
 * overflows does ellipsis apply. Pure (nowMs injected), so plain-JVM tests
 * can pin every rung.
 */
internal fun usageResetLabelVariants(resetsAt: String?, nowMs: Long): List<String> {
    if (resetsAt == null) return emptyList()
    val parsed = try {
        OffsetDateTime.parse(resetsAt)
    } catch (_: Exception) {
        return emptyList()
    }
    val deltaMs = parsed.toInstant().toEpochMilli() - nowMs
    return when {
        deltaMs <= 0 -> listOf("resets soon", "soon")
        deltaMs < 24 * 3_600_000L -> {
            // Integer division IS the floor (delta > 0 here, no sign trap).
            val totalMin = deltaMs / 60_000L
            val h = totalMin / 60
            val m = totalMin % 60
            val core = if (h > 0) "${h}h ${m}m" else "${m}m"
            listOf("resets in $core", "reset $core", core)
        }
        else -> {
            val local = parsed.atZoneSameInstant(ZoneId.systemDefault())
            // 0→12am, 12→12pm, 13→1pm: the +11 %12 +1 dance is the standard
            // 24h→12h mapping that never yields 0.
            val hour12 = (local.hour + 11) % 12 + 1
            val ampm = if (local.hour < 12) "am" else "pm"
            // Zero-padded minutes only when non-zero ("10:05am", plain
            // "10am"); padStart, not String.format, so no locale surprises.
            val minutes = if (local.minute == 0) "" else ":" + local.minute.toString().padStart(2, '0')
            val core = local.format(DateTimeFormatter.ofPattern("EEE")) + " $hour12$minutes$ampm"
            listOf("resets $core", core)
        }
    }
}

/**
 * The FULL (longest) reset label — the ladder's head, or null for a
 * malformed/absent resetsAt. Kept as the single-string seam the format tests
 * pin: the ladder degrades from exactly this string.
 */
internal fun usageResetLabel(resetsAt: String?, nowMs: Long): String? =
    usageResetLabelVariants(resetsAt, nowMs).firstOrNull()

/**
 * The freshness line under the last bar (2026-07-18 refinements): NULL —
 * meaning no label at all — while the data is under a minute old (a "just
 * now" line is noise: fresh data needs no caveat, and the ~30s ticker pops
 * the label in right on time once the minute passes), then full words with
 * honest singular/plural in the same buckets as before: "as of 1 minute
 * ago" / "as of 5 minutes ago" under an hour, "as of 1 hour ago" / "as of
 * 3 hours ago" beyond. Computed from [UsageUi.Data.fetchedAtMs], which the
 * client model guarantees non-null (live parses stamp it, cache keeps the
 * bridge's value). Age clamps at 0 so a skewed clock never renders a
 * negative age — a future stamp is simply "fresh", i.e. null. Pure, so
 * plain-JVM tests can pin the buckets.
 */
internal fun usageUpdatedLabel(fetchedAtMs: Long, nowMs: Long): String? {
    val ageMs = (nowMs - fetchedAtMs).coerceAtLeast(0L)
    return when {
        ageMs < 60_000L -> null
        ageMs < 3_600_000L -> {
            val m = ageMs / 60_000L
            "as of $m minute${if (m == 1L) "" else "s"} ago"
        }
        else -> {
            val h = ageMs / 3_600_000L
            "as of $h hour${if (h == 1L) "" else "s"} ago"
        }
    }
}

// ─── Screen ──────────────────────────────────────────────────────────────────

/**
 * The header block's fixed anchor: 118px at the 450 ref — the design mock's
 * eyebrow height, clear of the decorative TimeText clock above it. The SAME
 * anchor in Loading (skeleton) and ≤3-row Data, so the eyebrow never jumps
 * when the fetch lands.
 */
// Header anchor for the pinned (n ≤ 3) layout. The collision math is exact
// (review finding): Weekly's center sits at 112.5dp, so Session's row top
// lands at ~62dp — the eyebrow's 24dp tap box must END by ~58dp for real
// clearance. Anchoring the box at 34dp with BOTTOM-aligned glyphs puts the
// text at ~46–58dp (near the mock's 118px eyebrow) while the enlarged hit
// area grows UPWARD into the empty band under the TimeText, never over the
// first row.
private val UsageHeaderTop = 34.dp

@Composable
fun HaloUsageScreen(
    usage: UsageUi,
    onRetry: () -> Unit,
    /**
     * Manual FORCE refresh — the freshness label's tap (2026-07-18): unlike
     * [onRetry]/page entry (the non-forced fetch, which the VM's rate limit
     * may skip), this bypasses the limiter. The silent-refresh rule keeps
     * the bars on screen while it lands.
     */
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    // REMAINING vs USED is a PERSISTED reader preference (user-directed,
    // 2026-07-18): rememberSaveable only survived process recreation, so
    // every fresh app launch reset the reader's choice back to REMAINING.
    // Plain SharedPreferences — a single boolean UI pref has no business in
    // the Keystore-encrypted connection store. Write-through on toggle; the
    // composable state stays the single source of truth for the frame.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("halo_ui", Context.MODE_PRIVATE) }
    var usedMode by remember { mutableStateOf(prefs.getBoolean(USAGE_USED_MODE_PREF, false)) }
    val toggleMode = {
        usedMode = !usedMode
        prefs.edit().putBoolean(USAGE_USED_MODE_PREF, usedMode).apply()
    }
    // Minute ticker: BOTH label families ("resets in 4h 13m", "as of Xm
    // ago") are computed from NOW, and nothing else recomposes while the
    // page just sits open — without a tick they would silently go stale.
    // ~30s keeps the minute displays honest at half their resolution.
    // LaunchedEffect(Unit) scopes the loop to THIS screen: it cancels on
    // dispose, so no ticker outlives the page (and the instrumented tests,
    // which never advance 30s, never observe a tick).
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
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
                onToggleMode = toggleMode,
            )
            is UsageUi.Error -> UsageError(message = usage.message, onRetry = onRetry)
            is UsageUi.Data -> UsageData(
                data = usage,
                usedMode = usedMode,
                onToggleMode = toggleMode,
                onRefresh = onRefresh,
                // Sampled ON the tick (and on fresh data): reading `tick` in
                // the remember key is the subscription that re-evaluates
                // nowMs() every ~30s, flowing a new Long down so every
                // now-derived label recomputes. Between ticks the value is
                // stable — no per-frame clock reads.
                nowMs = remember(tick, usage) { nowMs() },
            )
        }
        // The decorative top clock from the design mock — EXACTLY the
        // InnerScreen idiom (HaloApp.kt): same style, and deliberately NOT a
        // tap target (the clock is just a clock — an invisible hotspot over
        // the time read as an accidental-jump trap in live testing).
        TimeText(
            timeTextStyle = TimeTextDefaults.timeTextStyle(
                color = Color(0xFF7E7C76),
                fontSize = Halo.Type.Min,
            ),
        )
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
        // BottomCenter, not Center: in the pinned layout the box's bottom
        // edge is what must clear the first row (see UsageHeaderTop), so the
        // glyphs hug it and the tap slack all extends upward.
        contentAlignment = Alignment.BottomCenter,
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
    onRefresh: () -> Unit,
    nowMs: Long,
) {
    val widthsPx = usageChordWidthsPx(data.limits.size)
    // 4+ rows tighten the typography so the stack still fits the circle.
    val compact = data.limits.size >= 4
    if (compact) {
        // ≥4 rows: the single centered stack (eyebrow + rows centered as
        // one). A fixed-top header would collide with the taller row pile, so
        // everything centers together — accepting that the chord widths
        // (computed around dead center) are approximate for these rare tall
        // stacks.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // 17px stack gap at the 450 ref, vertically centered in the circle.
            verticalArrangement = Arrangement.spacedBy(8.5.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize(),
        ) {
            UsageEyebrow(usedMode = usedMode, onToggle = onToggleMode)
            data.limits.forEachIndexed { i, limit ->
                UsageRow(limit = limit, widthPx = widthsPx[i], compact = true, usedMode = usedMode, nowMs = nowMs)
            }
            // The freshness label as the column's LAST child — "under the
            // last bar", the same reading order as the pinned layout's
            // bottom band (absent while the data is under a minute old).
            UsageUpdatedLabel(data = data, nowMs = nowMs, onRefresh = onRefresh)
        }
    } else {
        // ≤3 rows (the real-world case): the ROW STACK is centered by ITSELF
        // and the eyebrow pins to the top. Centering the whole column as one
        // stack pushed the rows BELOW center — but usageChordWidthsPx assumes
        // row i sits dy = (i−(n−1)/2)·pitch around DEAD center, so the chords
        // are honest only when the rows actually straddle it.
        Box(modifier = Modifier.fillMaxSize()) {
            // Eyebrow ALONE at the top anchor — the freshness label lives
            // below the stack (see UsageUpdatedLabel); in the header it
            // would draw straight across the first row.
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = UsageHeaderTop)) {
                UsageEyebrow(usedMode = usedMode, onToggle = onToggleMode)
            }
            UsageUpdatedLabel(
                data = data,
                nowMs = nowMs,
                onRefresh = onRefresh,
                // Under the LAST bar: between the stack's bottom (~162dp) and
                // the page dots — the one honest empty band in the pinned
                // layout, visually "under the Fable bar" and clear of the
                // dots at 32dp bottom padding.
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.5.dp), // 17px between rows
                modifier = Modifier.align(Alignment.Center),
            ) {
                if (data.limits.isEmpty()) {
                    // A 200 with zero windows (plan without limits?): honest
                    // empty state rather than a blank page.
                    Text(
                        text = "no usage windows reported",
                        fontSize = 11.sp, // 22px
                        color = Halo.Palette.TextFaint,
                        textAlign = TextAlign.Center,
                    )
                }
                data.limits.forEachIndexed { i, limit ->
                    UsageRow(limit = limit, widthPx = widthsPx[i], compact = false, usedMode = usedMode, nowMs = nowMs)
                }
            }
        }
    }
}

/**
 * The "as of ..." freshness label (2026-07-18 refinements; was the
 * cache-only "as of" caveat) — rendered under the last bar in BOTH layouts:
 * the pinned (n ≤ 3) layout pins it to the BottomCenter band below the row
 * stack, the compact (n ≥ 4) stack appends it as the column's last child.
 * Live and cache alike: fetchedAtMs is when these numbers were current, and
 * saying so beats pretending "live" means "this second" — but only once the
 * data is MORE THAN A MINUTE old (usageUpdatedLabel returns null under
 * that, and this composable renders NOTHING on null; the screen's ~30s
 * ticker pops the label in on time). TAPPING it is a manual force-refresh
 * ([onRefresh] → fetchUsage(force = true)): the label is the page's honest
 * age readout, so the age readout is also the "get me fresh numbers" seam.
 * Same enlarged-target idiom as the eyebrow: the clickable wraps a Box
 * grown toward the 48×24 minimum with BOTTOM-aligned glyphs, so the text
 * keeps its exact visual position and the tap slack extends upward into the
 * empty band above (never down over the page dots). The testTag keeps the
 * historical "haloUsageStale" name — instrumented tests key on it — and
 * sits on the TAPPABLE node.
 */
@Composable
private fun UsageUpdatedLabel(
    data: UsageUi.Data,
    nowMs: Long,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Fresh data (< 1 minute old): no caveat, no tap target, nothing at all.
    val label = usageUpdatedLabel(data.fetchedAtMs, nowMs) ?: return
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 24.dp)
            .clickable(onClick = onRefresh)
            .testTag("haloUsageStale"),
    ) {
        Text(
            text = label,
            fontSize = 9.5.sp, // 19px caveat
            color = Halo.Palette.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Header line (name · reset · percent) + tiered bar — one window. */
@Composable
private fun UsageRow(limit: UsageLimit, widthPx: Int, compact: Boolean, usedMode: Boolean, nowMs: Long) {
    // Severity-first (see usageTier): the wire's own coding escalates the
    // local remaining-based fallback, never downgrades it.
    val tier = usageTier(limit.percent, limit.severity)
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
                // Truncation priority: the window's NAME wins, the reset
                // detail compresses. The name is primary data, the reset is
                // secondary, so the RESET is the weighted (flexible) child
                // and adapts first. The unweighted name is still bounded by
                // the row, so a pathological wire label ellipsizes rather
                // than clipping.
                Text(
                    text = usageDisplayName(limit.kind, limit.label),
                    fontSize = 11.sp, // 22px window name
                    color = Halo.Palette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline(),
                )
                val resetVariants = usageResetLabelVariants(limit.resetsAt, nowMs)
                if (resetVariants.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(5.dp)) // 10px baseline gap
                    // Width-aware DEGRADATION instead of ellipsis (2026-07-18
                    // refinement): a mid-word "resets in 3h…" hid the one
                    // number the line exists to show. The BoxWithConstraints
                    // sits exactly where the old reset Text did (weighted,
                    // fill = false), so its maxWidth IS the space the row
                    // actually left for the reset after the name took its
                    // natural width and the percent claimed the right edge.
                    // Each ladder rung is measured with the SAME style the
                    // Text below renders in; the first (longest) variant
                    // that fits wins. Only when even the shortest rung
                    // overflows does the maxLines=1 ellipsis apply — the
                    // last-resort a degradation ladder cannot dodge.
                    BoxWithConstraints(
                        modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                    ) {
                        val measurer = rememberTextMeasurer()
                        val style = LocalTextStyle.current.copy(fontSize = 9.5.sp)
                        val available = constraints.maxWidth
                        val resets = resetVariants.firstOrNull { candidate ->
                            measurer.measure(candidate, style, maxLines = 1).size.width <= available
                        } ?: resetVariants.last()
                        Text(
                            text = resets,
                            fontSize = 9.5.sp, // 19px reset time
                            color = Halo.Palette.TextFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

/** SharedPreferences key for the persisted REMAINING/USED reader choice. */
private const val USAGE_USED_MODE_PREF = "usage_used_mode"

@Composable
private fun UsageSkeleton(usedMode: Boolean, onToggleMode: () -> Unit) {
    val widthsPx = usageChordWidthsPx(SKELETON_ROWS)
    // The SAME two anchors as the ≤3-row Data layout — header pinned to
    // UsageHeaderTop, rows centered by themselves — so the eyebrow does not
    // jump when the fetch lands and the skeleton rows sit exactly where the
    // real bars will.
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = UsageHeaderTop)) {
            UsageEyebrow(usedMode = usedMode, onToggle = onToggleMode, dimmed = true)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.5.dp), // 17px between rows
            modifier = Modifier.align(Alignment.Center).testTag("haloUsageLoading"),
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
    // Ambient (issue #24): wrist-down, the infinite pulse would keep the
    // composition animating (and the display redrawing) forever with nobody
    // looking — freeze at the pulse's dim floor instead. Keyed off the
    // CompositionLocal so exiting ambient restarts the transition in place.
    val alpha = if (LocalHaloAmbient.current) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "usageSkeleton")
        transition.animateFloat(
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
        ).value
    }
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
