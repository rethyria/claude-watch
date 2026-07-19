package dev.claudewatch.wear.glance

import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionActivity
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.BridgeViewModel.PendingPermission
import dev.claudewatch.wear.BridgeViewModel.UiState
import dev.claudewatch.wear.net.ConnectionState
import dev.claudewatch.wear.ui.halo.HaloModel
import dev.claudewatch.wear.ui.halo.haloCensusText
import dev.claudewatch.wear.ui.halo.sessionCensusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The honesty table (issue #28): EVERY ConnectionState variant → the exact
 * glance rendering, pinned. This is the acceptance criterion "the glanceable
 * never shows stale green" in unit form — the watchOS complication this
 * feature replaces derived green from the optimistic pairing state and
 * glowed through outages, so the table's load-bearing rows are the
 * NOT-healthy ones, and the sabotage test at the bottom makes re-deriving
 * `healthy` from paired/credentials a loud failure.
 */
class GlanceModelTest {

    // ------------------------------------------------------------------
    // Fixtures — the HaloModelTest builders, trimmed.
    // ------------------------------------------------------------------

    private fun session(id: String, folderName: String = "proj", cwd: String = "/home/dev/$folderName") =
        SessionState(
            sessionId = id,
            agent = "claude",
            cwd = cwd,
            folderName = folderName,
            activity = SessionActivity.WORKING,
            activeSinceMs = 1_000L,
        )

    private fun model(vararg sessions: SessionState, queue: List<PendingPermission> = emptyList()) =
        HaloModel.from(
            UiState(
                bridge = BridgeState(sessions = sessions.associateBy { it.sessionId }),
                permissionQueue = queue,
            ),
        )

    private fun prompt(id: String, sessionId: String) = PendingPermission(
        permissionId = id,
        sessionId = sessionId,
        toolName = "Bash",
        requestSummary = "$ make",
        sessionLabel = "proj",
        options = emptyList(),
    )

    private val twoSessionsOneProject =
        model(session("s-1"), session("s-2"))

    // ------------------------------------------------------------------
    // The honesty table.
    // ------------------------------------------------------------------

    @Test
    fun connectedIsTheOnlyHealthyRowAndSpeaksTheCensus() {
        val status = glanceStatus(ConnectionState.Connected, twoSessionsOneProject)
        assertTrue(status.healthy)
        assertEquals("2 sessions", status.statusText)
        assertEquals("1 project", status.detailText)
        assertEquals("2 sess", status.shortText)
    }

    @Test
    fun connectedWithNoSessionsIsStillHealthyJustEmpty() {
        val status = glanceStatus(ConnectionState.Connected, model())
        assertTrue("an empty but live stream is healthy", status.healthy)
        assertEquals("no sessions", status.statusText)
        assertEquals("connected", status.detailText)
        assertEquals("0 sess", status.shortText)
    }

    @Test
    fun connectedWithAWaitingSessionSurfacesTheQueueInTheDetail() {
        val status = glanceStatus(
            ConnectionState.Connected,
            model(session("s-1"), session("s-2"), queue = listOf(prompt("perm-1", "s-1"))),
        )
        assertTrue("waiting is not UNhealthy — the stream is fine", status.healthy)
        assertEquals("2 sessions", status.statusText)
        assertEquals("1 waiting", status.detailText)
        assertEquals("2 sess", status.shortText)
    }

    @Test
    fun connectingIsNotHealthyAndSaysReconnecting() {
        val status = glanceStatus(ConnectionState.Connecting(attempt = 0), twoSessionsOneProject)
        assertFalse("the stream is not open yet — not healthy", status.healthy)
        assertEquals("reconnecting", status.statusText)
        assertEquals("tap to open", status.detailText)
        assertEquals("recon", status.shortText)
    }

    @Test
    fun reconnectingIsNotHealthyAndSaysReconnecting() {
        val status = glanceStatus(
            ConnectionState.Reconnecting(attempt = 3, reason = "stream failure: timeout"),
            twoSessionsOneProject,
        )
        assertFalse(status.healthy)
        assertEquals("reconnecting", status.statusText)
        assertEquals("recon", status.shortText)
    }

    @Test
    fun stoppedSaysDisconnected() {
        val status = glanceStatus(ConnectionState.Stopped, twoSessionsOneProject)
        assertFalse(status.healthy)
        assertEquals("disconnected", status.statusText)
        assertEquals("tap to open", status.detailText)
        assertEquals("off", status.shortText)
    }

    @Test
    fun pairingIsInProgressNotHealthy() {
        val status = glanceStatus(ConnectionState.Pairing, model())
        assertFalse(status.healthy)
        assertEquals("pairing", status.statusText)
        assertEquals("pairing", status.shortText)
    }

    @Test
    fun pairFailedSaysSoAndOffersTheRetry() {
        val status = glanceStatus(ConnectionState.PairFailed("401 bad code"), model())
        assertFalse(status.healthy)
        assertEquals("pair failed", status.statusText)
        assertEquals("tap to retry", status.detailText)
        assertEquals("no pair", status.shortText)
    }

    @Test
    fun authExpiredSaysRepairNeeded() {
        val status = glanceStatus(ConnectionState.AuthExpired("token rejected"), twoSessionsOneProject)
        assertFalse(status.healthy)
        assertEquals("re-pair needed", status.statusText)
        assertEquals("tap to re-pair", status.detailText)
        assertEquals("re-pair", status.shortText)
    }

    @Test
    fun bridgeMismatchSaysWrongBridge() {
        val status = glanceStatus(
            ConnectionState.BridgeMismatch(expectedBridgeId = "b-1", actualBridgeId = "b-2"),
            twoSessionsOneProject,
        )
        assertFalse(status.healthy)
        assertEquals("wrong bridge", status.statusText)
        assertEquals("re-pair", status.shortText)
    }

    @Test
    fun protoMismatchSaysUpdateNeeded() {
        val status = glanceStatus(
            ConnectionState.ProtoMismatch(bridgeProto = "2", minProto = 3),
            twoSessionsOneProject,
        )
        assertFalse(status.healthy)
        assertEquals("update needed", status.statusText)
        assertEquals("update the bridge", status.detailText)
        assertEquals("update", status.shortText)
    }

    /** The null-peek row: no engine in the process (BridgeViewModel.peek()
     *  returned null — the passivity rule forbids constructing one just to
     *  ask). Renders exactly like Stopped, plus the tap affordance. */
    @Test
    fun nullPeekRendersAsDisconnectedWithTheTapAffordance() {
        val status = glanceStatus(null, null)
        assertFalse(status.healthy)
        assertEquals("disconnected", status.statusText)
        assertEquals("tap to open", status.detailText)
        assertEquals("off", status.shortText)
    }

    // ------------------------------------------------------------------
    // The sabotage-proof: the exact watchOS bug, as a failing test.
    // ------------------------------------------------------------------

    /**
     * Reconnecting WITH paired=true and a full census must NOT be healthy.
     * The watchOS complication derived green from "credentials exist /
     * paired" — precisely the state a mid-outage reconnect loop is in
     * (UiState.paired is true for Connecting/Reconnecting, see
     * isPairedState). Anyone re-deriving `healthy` from the paired flag, the
     * census, or credential presence turns this test red before they turn
     * the wrist green.
     */
    @Test
    fun reconnectingWhilePairedWithLiveSessionsIsNeverHealthy() {
        // The most tempting-to-trust snapshot possible: paired, sessions
        // present, a rich census — and a stream that is DOWN.
        val pairedUi = UiState(
            status = "paired, reconnecting (stream failure: timeout)",
            paired = true,
            bridge = BridgeState(
                sessions = mapOf("s-1" to session("s-1"), "s-2" to session("s-2")),
            ),
        )
        assertTrue("fixture sanity: this snapshot claims paired", pairedUi.paired)
        val status = glanceStatus(
            ConnectionState.Reconnecting(attempt = 1, reason = "stream failure: timeout"),
            HaloModel.from(pairedUi),
        )
        assertFalse(
            "STREAM health, never pairing state: a reconnecting stream is DOWN " +
                "no matter how paired the snapshot looks",
            status.healthy,
        )
        // And the census must not leak into the headline as fake liveness.
        assertEquals("reconnecting", status.statusText)
    }

    /** Same trap from the other side: Stopped with a stale session census
     *  (state retained across disconnect by design, see BridgeViewModel
     *  .disconnect) must not render the census as if it were live. */
    @Test
    fun stoppedWithARetainedCensusStillSaysDisconnected() {
        val status = glanceStatus(ConnectionState.Stopped, twoSessionsOneProject)
        assertFalse(status.healthy)
        assertEquals("disconnected", status.statusText)
        assertEquals("off", status.shortText)
    }

    // ------------------------------------------------------------------
    // The short-form mapping table + budget.
    // ------------------------------------------------------------------

    /** SHORT_TEXT budgets ~7 chars on many faces; every short form must fit
     *  (double digits included: "12 sess" is 7). Table + budget in one place
     *  so a new ConnectionState variant that forgets its short form fails
     *  compilation (exhaustive when) and an over-budget one fails here. */
    @Test
    fun everyShortFormFitsTheSevenCharacterBudget() {
        val rows = mapOf(
            glanceStatus(ConnectionState.Connected, twoSessionsOneProject).shortText to "2 sess",
            glanceStatus(ConnectionState.Connecting(0), null).shortText to "recon",
            glanceStatus(ConnectionState.Reconnecting(1, "x"), null).shortText to "recon",
            glanceStatus(ConnectionState.Stopped, null).shortText to "off",
            glanceStatus(ConnectionState.Pairing, null).shortText to "pairing",
            glanceStatus(ConnectionState.PairFailed("x"), null).shortText to "no pair",
            glanceStatus(ConnectionState.AuthExpired("x"), null).shortText to "re-pair",
            glanceStatus(ConnectionState.BridgeMismatch("a", "b"), null).shortText to "re-pair",
            glanceStatus(ConnectionState.ProtoMismatch("2", 3), null).shortText to "update",
            glanceStatus(null, null).shortText to "off",
        )
        for ((actual, expected) in rows) {
            assertEquals(expected, actual)
            assertTrue("short form over the ~7-char budget: \"$actual\"", actual.length <= 7)
        }
        // Double-digit census still fits.
        val many = model(*(1..12).map { session("s-$it") }.toTypedArray())
        val crowded = glanceStatus(ConnectionState.Connected, many).shortText
        assertEquals("12 sess", crowded)
        assertTrue(crowded.length <= 7)
    }

    // ------------------------------------------------------------------
    // Census reuse: same words as the home ring, by construction.
    // ------------------------------------------------------------------

    /** The tile headline is the home census's session wording VERBATIM (the
     *  shared sessionCensusText — issue #28's do-not-duplicate rule), and
     *  the full home line still reads as before the extraction. */
    @Test
    fun glanceHeadlineReusesTheHomeRingsCensusWording() {
        assertEquals("no sessions", sessionCensusText(0))
        assertEquals("1 session", sessionCensusText(1))
        assertEquals("2 sessions", sessionCensusText(2))
        assertEquals("no sessions", haloCensusText(0, 0))
        assertEquals("1 project · 1 session", haloCensusText(1, 1))
        assertEquals("2 projects · 3 sessions", haloCensusText(2, 3))
        assertEquals(
            sessionCensusText(twoSessionsOneProject.sessionCount),
            glanceStatus(ConnectionState.Connected, twoSessionsOneProject).statusText,
        )
    }

    /** Hidden sessions (issue #53) are respected for free: the census comes
     *  from HaloModel.from, the same derivation the home ring renders. */
    @Test
    fun theGlanceCensusHonorsHonestHiddenSessions() {
        val ui = UiState(
            bridge = BridgeState(
                sessions = mapOf(
                    "s-1" to session("s-1"),
                    "s-ext" to session("s-ext").copy(external = true),
                ),
            ),
            hiddenSessions = setOf("s-ext"),
        )
        val status = glanceStatus(ConnectionState.Connected, HaloModel.from(ui))
        assertEquals("a hidden session must not be counted", "1 session", status.statusText)
        assertEquals("1 sess", status.shortText)
    }

    /**
     * The production glue every real tile/complication request flows
     * through — the one piece the instrumented suites CANNOT see, because
     * they override GlanceStateSource.resolver before each request. On the
     * plain JVM the singleton can never exist (construction requires a
     * Context, and constructing a BridgeViewModel directly never sets it),
     * so peek() is deterministically null and the glue must land on the
     * honest disconnected derivation — a cached last-known status or an
     * optimistic default here would ship the watchOS stale-green bug with
     * every other test green.
     */
    @Test
    fun theProductionPeekGlueDerivesDisconnectedFromANullSingleton() {
        assertEquals(glanceStatus(null, null), peekGlanceStatus())
        assertEquals(
            "the untouched resolver default IS the peek glue",
            peekGlanceStatus(),
            GlanceStateSource.resolver(),
        )
    }
}
