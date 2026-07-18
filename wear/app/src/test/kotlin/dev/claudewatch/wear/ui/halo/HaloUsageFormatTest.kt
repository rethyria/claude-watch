package dev.claudewatch.wear.ui.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The usage page's pure presentation math (issue #57, re-skinned per the Halo
 * usage design), all plain-JVM — no Compose:
 * - reset-time formatting: the 5-hour session window shows a clock time,
 *   weekly (and any UNKNOWN — render-what-you-get) kinds show a weekday, and
 *   a malformed/absent resetsAt degrades to NO reset line — never a crash,
 *   never a dropped bar. Asserted by shape, not exact value: the rendered
 *   time is in the device's zone.
 * - chord-fitted widths: exact px values of the design's formula
 *   min(336, round(2·√(max(R²−dy², 115²))·0.97)), R=169.
 * - semantic tiers from REMAINING (= 100 − wire USED percent).
 * - REMAINING/USED mode inversion of the shown number and bar, including the
 *   out-and-USED full-bar pin.
 * - presentation-only display names (session → Session, weekly_all → Weekly).
 */
class HaloUsageFormatTest {

    @Test
    fun sessionKindFormatsAClockTime() {
        val label = usageResetLabel("session", "2026-07-18T19:10:00Z")
        assertTrue(
            "expected 'resets HH:mm', got: $label",
            label != null && Regex("""resets \d{2}:\d{2}""").matches(label),
        )
    }

    @Test
    fun weeklyAndUnknownKindsFormatAWeekday() {
        for (kind in listOf("weekly_all", "weekly_scoped", "lunar_window")) {
            val label = usageResetLabel(kind, "2026-07-24T00:00:00Z")
            assertTrue(
                "expected 'resets <weekday>' for $kind, got: $label",
                label != null && Regex("""resets \p{L}+""").matches(label),
            )
        }
    }

    @Test
    fun malformedOrAbsentResetsAtDegradesToNoLine() {
        assertNull(usageResetLabel("session", null))
        assertNull(usageResetLabel("session", "not-a-timestamp"))
        // A bare date without an offset is not the wire's ISO8601 either.
        assertNull(usageResetLabel("weekly_all", "2026-07-24"))
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

    // ── Semantic tiers (always from REMAINING; wire percent is USED) ────────

    @Test
    fun tiersDeriveFromRemaining() {
        assertEquals(UsageTier.NORMAL, usageTier(0.0)) // untouched window
        assertEquals(UsageTier.NORMAL, usageTier(80.0)) // exactly 20 left: not low
        assertEquals(UsageTier.LOW, usageTier(81.0)) // 19 left
        assertEquals(UsageTier.LOW, usageTier(99.9)) // sliver left is still low
        assertEquals(UsageTier.OUT, usageTier(100.0)) // nothing left
        assertEquals(UsageTier.OUT, usageTier(104.0)) // wire overshoot is out
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
    fun outAndUsedPinsTheBarFull() {
        // A drained window in USED mode is a FULL bar (and an empty one in
        // REMAINING mode) — "you used it all" must never read as almost-full.
        assertEquals(1f, usageBarFraction(100.0, usedMode = true), 0f)
        assertEquals(1f, usageBarFraction(104.0, usedMode = true), 0f)
        assertEquals(0f, usageBarFraction(104.0, usedMode = false), 0f)
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
