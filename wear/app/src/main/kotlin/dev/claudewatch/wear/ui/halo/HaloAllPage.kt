// The home page's census wording. The v2 shell (epic #94 S3) folded the page
// composable itself into HaloApp — the ring is the root HaloRingHost, the
// clock is fixed chrome, and the census rides the centerpiece's sliding
// subtitle slot — but the STRINGS stay here: the glanceables (tile,
// complication) must speak the exact wording the home census uses, and their
// tests import these helpers by name.
package dev.claudewatch.wear.ui.halo

internal fun plural(n: Int, noun: String): String = if (n == 1) noun else "${noun}s"

/**
 * The session half of the census — "no sessions" / "1 session" / "2
 * sessions". Extracted (issue #28) because the Tile's status headline must be
 * the EXACT wording the home ring's census uses: two near-identical strings
 * ("2 sessions" here, "2 active" there) would read as two different facts on
 * the same wrist. Pure, plain-JVM-tested via GlanceModelTest.
 */
internal fun sessionCensusText(sessionCount: Int): String =
    if (sessionCount == 0) "no sessions" else "$sessionCount ${plural(sessionCount, "session")}"

/**
 * The FULL census line the home page's centerpiece renders — extracted from
 * the old page composable (issue #28) so glanceables reuse it verbatim
 * instead of duplicating the string format. Zero sessions collapses to just
 * "no sessions": "0 projects · no sessions" would be counting nothing twice.
 */
internal fun haloCensusText(projectCount: Int, sessionCount: Int): String =
    if (sessionCount == 0) {
        sessionCensusText(0)
    } else {
        "$projectCount ${plural(projectCount, "project")} · ${sessionCensusText(sessionCount)}"
    }
