package dev.claudewatch.wear.ui.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pager card's `model · mode · use%` subheading derivation (Halo v2 S5).
 * The wire fields land with S9 (#102), so the row must already be correct at
 * every stage of partial arrival: each part rendered only when present,
 * separators only between present parts (the composable interleaves a dot
 * before every part but the first, so "no gaps in the list" IS that rule),
 * the whole row omitted when all are absent, and use% flips terracotta at 80.
 */
class HaloSubheadingTest {

    @Test
    fun allPartsPresentInModelModeUseOrder() {
        assertEquals(
            listOf(SubheadingPart("opus"), SubheadingPart("plan"), SubheadingPart("42%")),
            sessionSubheading("opus", "plan", 42),
        )
    }

    @Test
    fun absentPartsDropOutWithoutLeavingGaps() {
        // Any subset keeps relative order and packs tight — the renderer's
        // between-parts separators then land only between present parts.
        assertEquals(
            listOf(SubheadingPart("opus"), SubheadingPart("42%")),
            sessionSubheading("opus", null, 42),
        )
        assertEquals(listOf(SubheadingPart("plan")), sessionSubheading(null, "plan", null))
        assertEquals(listOf(SubheadingPart("7%")), sessionSubheading(null, null, 7))
    }

    @Test
    fun allAbsentMeansNoRowAtAll() {
        // Today's bridge sends none of the fields: the card renders no
        // subheading row, not an empty one holding blank space.
        assertEquals(emptyList<SubheadingPart>(), sessionSubheading(null, null, null))
    }

    @Test
    fun usePercentRunsHotAtEightyNotBelow() {
        assertFalse(sessionSubheading(null, null, 79).single().hot)
        assertTrue(sessionSubheading(null, null, 80).single().hot)
        assertTrue(sessionSubheading(null, null, 97).single().hot)
    }

    // ── The #54/#55 detail line, rehomed on the card (#104) ─────────────────
    // Same derivation the retired FeedHeader ran: branch badge and agents
    // indicator share one line, each part only when present, joined " · ".

    @Test
    fun detailLineJoinsBranchAndAgentsWithHonestSingular() {
        assertEquals("⎇ main · ⚙ 1 agent", sessionDetailLine("⎇ main", 1))
        assertEquals("⎇ fix-53 · wt · ⚙ 3 agents", sessionDetailLine("⎇ fix-53 · wt", 3))
    }

    @Test
    fun detailLinePartsDropOutIndependently() {
        assertEquals("⎇ main", sessionDetailLine("⎇ main", 0))
        assertEquals("⚙ 2 agents", sessionDetailLine(null, 2))
    }

    @Test
    fun noBranchAndNothingRunningMeansNoLineAtAll() {
        // The back-compat rule: a PTY/hook session with neither signal keeps
        // today's clean card — null, not an empty row holding blank space.
        assertEquals(null, sessionDetailLine(null, 0))
    }
}
