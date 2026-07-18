package dev.claudewatch.wear.ui.halo

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The usage page's pure reset-time formatting (issue #57): the 5-hour session
 * window shows a clock time, weekly (and any UNKNOWN — render-what-you-get)
 * kinds show a weekday, and a malformed/absent resetsAt degrades to NO reset
 * line — never a crash, never a dropped bar. Asserted by shape, not exact
 * value: the rendered time is in the device's zone.
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
}
