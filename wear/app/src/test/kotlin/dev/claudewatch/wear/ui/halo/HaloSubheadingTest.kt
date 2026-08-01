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
}
