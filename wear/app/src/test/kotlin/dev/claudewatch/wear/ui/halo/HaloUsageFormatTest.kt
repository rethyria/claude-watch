package dev.claudewatch.wear.ui.halo

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The usage page's pure presentation math (issue #57, re-skinned per the Halo
 * usage design), all plain-JVM — no Compose:
 * - reset-time formatting, TIME-TO-RESET aware (2026-07-18 refinement; one
 *   uniform rule, no kind parameter): delta ≤ 0 → "resets soon", < 24h →
 *   relative "resets in 4h 13m" (hours omitted at zero, minutes floored),
 *   ≥ 24h → absolute "resets Sat 10am" (weekday + local 12-hour clock,
 *   minutes only when non-zero, 12am/12pm never 0am). A malformed/absent
 *   resetsAt degrades to NO reset line — never a crash, never a dropped bar.
 *   Expected values are built from instants constructed against
 *   ZoneId.systemDefault(), so the suite passes in ANY zone.
 * - the always-on freshness label: "as of just now" / "Xm ago" / "Xh ago"
 *   buckets, clamped at zero age.
 * - chord-fitted widths: exact px values of the design's formula
 *   min(336, round(2·√(max(R²−dy², 115²))·0.97)), R=169.
 * - semantic tiers, SEVERITY-FIRST: the server's own `severity` coding wins
 *   when present and non-"normal" (escalate-only), the local REMAINING
 *   thresholds (= 100 − wire USED percent; orange ≥75% used, red ≥95%) are
 *   the fallback floor.
 * - REMAINING/USED mode inversion of the shown number and bar, including the
 *   drained-and-USED full-bar pin (remaining ≤ 0, NOT tier == OUT).
 * - presentation-only display names (session → Session, weekly_all → Weekly).
 */
class HaloUsageFormatTest {

    /** Any fixed "now" — the reset rule keys on the DELTA, not the date. */
    private val nowMs = 1_752_850_000_000L

    /** resetsAt wire string for now + [deltaMs], via the system zone. */
    private fun resetsAtIn(deltaMs: Long): String =
        Instant.ofEpochMilli(nowMs + deltaMs)
            .atZone(ZoneId.systemDefault())
            .toOffsetDateTime()
            .toString()

    private val minuteMs = 60_000L
    private val hourMs = 3_600_000L

    // ── Reset labels: relative form (delta < 24h) ───────────────────────────

    @Test
    fun under24hFormatsARelativeCountdown() {
        assertEquals("resets in 4h 13m", usageResetLabel(resetsAtIn(4 * hourMs + 13 * minuteMs), nowMs))
        // Just shy of the 24h boundary stays relative.
        assertEquals("resets in 23h 59m", usageResetLabel(resetsAtIn(24 * hourMs - minuteMs), nowMs))
    }

    @Test
    fun zeroHoursOmitsTheHoursPart() {
        assertEquals("resets in 42m", usageResetLabel(resetsAtIn(42 * minuteMs), nowMs))
        assertEquals("resets in 59m", usageResetLabel(resetsAtIn(59 * minuteMs), nowMs))
    }

    @Test
    fun minutesAreAlwaysShownEvenAtZero() {
        // "resets in 2h 0m", never a bare "resets in 2h" — the minute slot is
        // the label's resolution and it never silently disappears.
        assertEquals("resets in 2h 0m", usageResetLabel(resetsAtIn(2 * hourMs), nowMs))
    }

    @Test
    fun minutesAreFlooredNeverRoundedUp() {
        // 4h 13m 59s reads 4h 13m — never the optimistic 4h 14m.
        assertEquals("resets in 4h 13m", usageResetLabel(resetsAtIn(4 * hourMs + 13 * minuteMs + 59_000L), nowMs))
        assertEquals("resets in 42m", usageResetLabel(resetsAtIn(42 * minuteMs + 59_000L), nowMs))
    }

    @Test
    fun elapsedOrExactlyNowSaysResetsSoon() {
        // Clock skew / just-elapsed windows: the next fetch replaces the
        // window anyway, so no negative countdowns and no "resets 5m ago".
        assertEquals("resets soon", usageResetLabel(resetsAtIn(0L), nowMs))
        assertEquals("resets soon", usageResetLabel(resetsAtIn(-5 * minuteMs), nowMs))
    }

    // ── Reset labels: absolute form (delta ≥ 24h) ───────────────────────────

    /**
     * A ZonedDateTime in the system zone [daysAhead] of today at the given
     * WALL time — skipping (rare) DST-gap days where that wall time does not
     * exist, so the suite passes in any zone, midnight-shifting ones included.
     */
    private fun zonedAt(daysAhead: Long, hour: Int, minute: Int): ZonedDateTime {
        val zone = ZoneId.systemDefault()
        var date = LocalDate.now(zone).plusDays(daysAhead)
        repeat(7) {
            val zdt = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone)
            if (zdt.hour == hour && zdt.minute == minute) return zdt
            date = date.plusDays(1)
        }
        throw AssertionError("no non-DST-gap day found for $hour:$minute")
    }

    /** Assert the absolute form for a target wall time, from 48h before it. */
    private fun assertAbsolute(expectedTime: String, target: ZonedDateTime) {
        val now = target.toInstant().toEpochMilli() - 48 * hourMs
        val weekday = target.format(DateTimeFormatter.ofPattern("EEE"))
        assertEquals(
            "resets $weekday $expectedTime",
            usageResetLabel(target.toOffsetDateTime().toString(), now),
        )
    }

    @Test
    fun over24hFormatsWeekdayPlusLocalHour() {
        assertAbsolute("10am", zonedAt(3, 10, 0))
        assertAbsolute("7pm", zonedAt(3, 19, 0))
    }

    @Test
    fun absoluteMinutesShowOnlyWhenNonZero() {
        assertAbsolute("10:30am", zonedAt(3, 10, 30))
        // Zero-padded when present: "10:05am", never "10:5am".
        assertAbsolute("10:05am", zonedAt(3, 10, 5))
    }

    @Test
    fun twelveHourClockHandlesMidnightAndNoon() {
        // 12-hour clock edges: 0h is 12am (never 0am), 12h is 12pm.
        assertAbsolute("12am", zonedAt(3, 0, 0))
        assertAbsolute("12pm", zonedAt(3, 12, 0))
    }

    @Test
    fun exactly24hOutTakesTheAbsoluteForm() {
        // The boundary itself belongs to the absolute form: delta ≥ 24h.
        val target = Instant.ofEpochMilli(nowMs + 24 * hourMs).atZone(ZoneId.systemDefault())
        val label = usageResetLabel(target.toOffsetDateTime().toString(), nowMs)
        assertEquals(
            "resets " + target.format(DateTimeFormatter.ofPattern("EEE")),
            label?.substringBeforeLast(' '),
        )
    }

    @Test
    fun malformedOrAbsentResetsAtDegradesToNoLine() {
        assertNull(usageResetLabel(null, nowMs))
        assertNull(usageResetLabel("not-a-timestamp", nowMs))
        // A bare date without an offset is not the wire's ISO8601 either.
        assertNull(usageResetLabel("2026-07-24", nowMs))
    }

    // ── The always-on freshness label ───────────────────────────────────────

    @Test
    fun updatedLabelBucketsJustNowMinutesHours() {
        assertEquals("as of just now", usageUpdatedLabel(nowMs, nowMs))
        assertEquals("as of just now", usageUpdatedLabel(nowMs - 59_000L, nowMs))
        assertEquals("as of 1m ago", usageUpdatedLabel(nowMs - minuteMs, nowMs))
        assertEquals("as of 5m ago", usageUpdatedLabel(nowMs - 5 * minuteMs, nowMs))
        assertEquals("as of 59m ago", usageUpdatedLabel(nowMs - 60 * minuteMs + 1, nowMs))
        assertEquals("as of 1h ago", usageUpdatedLabel(nowMs - hourMs, nowMs))
        assertEquals("as of 26h ago", usageUpdatedLabel(nowMs - 26 * hourMs, nowMs))
    }

    @Test
    fun updatedLabelClampsASkewedFutureStampToJustNow() {
        // A fetchedAtMs ahead of the local clock (skew) never renders a
        // negative age — it clamps to the freshest bucket.
        assertEquals("as of just now", usageUpdatedLabel(nowMs + 5 * minuteMs, nowMs))
    }

    // ── Chord-fitted widths ─────────────────────────────────────────────────

    @Test
    fun chordWidthsForThreeRowsMatchTheDesignExactly() {
        // pitch 63, dy = −63/0/63: the CENTER row is 2·169·0.97 = 327.86 →
        // 328 — under the 336 cap, so the cap never bites at n=3; the outer
        // rows shorten to the circle's chord at ±63px.
        assertEquals(listOf(304, 328, 304), usageChordWidthsPx(3))
    }

    @Test
    fun chordWidthsTightenPitchAsRowsAreAdded() {
        assertEquals(listOf(328), usageChordWidthsPx(1))
        assertEquals(listOf(322, 322), usageChordWidthsPx(2))
        assertEquals(listOf(288, 324, 324, 288), usageChordWidthsPx(4)) // pitch 54
        assertEquals(listOf(275, 315, 328, 315, 275), usageChordWidthsPx(5)) // pitch 46
    }

    @Test
    fun chordWidthsFloorAtThe115HalfWidthForFarOutRows() {
        // n=7 puts the outermost rows at dy = ±138 — past the circle — where
        // the 115 floor yields 2·115·0.97 = 223.1 → 223, still usable.
        assertEquals(listOf(223, 275, 315, 328, 315, 275, 223), usageChordWidthsPx(7))
    }

    // ── Semantic tiers (severity-first; local fallback from REMAINING) ──────

    @Test
    fun tiersDeriveFromRemainingWhenTheServerSaysNothing() {
        // The local fallback thresholds match the user's recollection of the
        // official usage screen: orange at 75% used, red at 95% used.
        assertEquals(UsageTier.NORMAL, usageTier(0.0)) // untouched window
        assertEquals(UsageTier.NORMAL, usageTier(74.0)) // 26 left: not low
        assertEquals(UsageTier.LOW, usageTier(75.0)) // exactly 75% used: low
        assertEquals(UsageTier.LOW, usageTier(76.0)) // 24 left
        assertEquals(UsageTier.LOW, usageTier(94.0)) // 6 left: still low
        assertEquals(UsageTier.OUT, usageTier(95.0)) // exactly 95% used: out
        assertEquals(UsageTier.OUT, usageTier(96.0)) // 4 left
        assertEquals(UsageTier.OUT, usageTier(100.0)) // nothing left
        assertEquals(UsageTier.OUT, usageTier(104.0)) // wire overshoot is out
    }

    @Test
    fun serverSeverityEscalatesTheTierButNeverDowngradesIt() {
        // The SERVER's word wins upward: its thresholds are undocumented, so
        // any non-"normal" severity outranks a locally-green window —
        // terminal-sounding values ("crit"/"exceed"/"error"/"block"
        // substrings, case-insensitive) mean OUT, anything else means LOW.
        assertEquals(UsageTier.LOW, usageTier(10.0, "warning"))
        assertEquals(UsageTier.LOW, usageTier(10.0, "elevated"))
        assertEquals(UsageTier.OUT, usageTier(10.0, "critical"))
        assertEquals(UsageTier.OUT, usageTier(10.0, "CRITICAL")) // wire casing is not ours to trust
        assertEquals(UsageTier.OUT, usageTier(10.0, "limit_exceeded"))
        assertEquals(UsageTier.OUT, usageTier(10.0, "error"))
        assertEquals(UsageTier.OUT, usageTier(10.0, "blocked"))
        // …but never downward: "normal" at 96% used is still OUT via the
        // LOCAL floor, and a merely-"warning" severity cannot un-drain it.
        assertEquals(UsageTier.OUT, usageTier(96.0, "normal"))
        assertEquals(UsageTier.OUT, usageTier(96.0, "warning"))
        assertEquals(UsageTier.LOW, usageTier(80.0, "normal")) // local LOW floor holds too
        // Absent severity → purely local (the default and the explicit null).
        assertEquals(UsageTier.NORMAL, usageTier(10.0, null))
        assertEquals(UsageTier.NORMAL, usageTier(10.0))
    }

    // ── REMAINING/USED mode inversion ───────────────────────────────────────

    @Test
    fun shownPercentInvertsWithTheMode() {
        assertEquals(72, usageShownPercent(28.0, usedMode = false)) // remaining
        assertEquals(28, usageShownPercent(28.0, usedMode = true)) // used
        // Clamped: an overshooting wire never renders 104% or −4%.
        assertEquals(0, usageShownPercent(104.0, usedMode = false))
        assertEquals(100, usageShownPercent(104.0, usedMode = true))
    }

    @Test
    fun barFractionFollowsTheShownPercent() {
        assertEquals(0.72f, usageBarFraction(28.0, usedMode = false), 1e-6f)
        assertEquals(0.28f, usageBarFraction(28.0, usedMode = true), 1e-6f)
    }

    @Test
    fun drainedAndUsedPinsTheBarFull() {
        // A drained window in USED mode is a FULL bar (and an empty one in
        // REMAINING mode) — "you used it all" must never read as almost-full.
        assertEquals(1f, usageBarFraction(100.0, usedMode = true), 0f)
        assertEquals(1f, usageBarFraction(104.0, usedMode = true), 0f)
        assertEquals(0f, usageBarFraction(104.0, usedMode = false), 0f)
    }

    @Test
    fun thePinKeysOnTrulyDrainedNotOnTheOutTier() {
        // DECOUPLED from the tier: OUT now begins at 5% remaining, and a
        // 95%-used window IS OUT — but its bar must still read as 95%, not
        // 100%. Only remaining ≤ 0 earns the full-bar pin.
        assertEquals(UsageTier.OUT, usageTier(95.0))
        assertEquals(0.95f, usageBarFraction(95.0, usedMode = true), 1e-6f)
        assertEquals(0.05f, usageBarFraction(95.0, usedMode = false), 1e-6f)
        assertEquals(0.99f, usageBarFraction(99.0, usedMode = true), 1e-6f)
    }

    // ── Display names ───────────────────────────────────────────────────────

    @Test
    fun displayNamesArePresentationOnlyMappings() {
        assertEquals("Session", usageDisplayName("session", "5-hour"))
        assertEquals("Weekly", usageDisplayName("weekly_all", "weekly"))
        // Anything else keeps the wire label as-is — render-what-you-get.
        assertEquals("Fable", usageDisplayName("weekly_scoped", "Fable"))
        assertEquals("lunar", usageDisplayName("lunar_window", "lunar"))
    }
}
