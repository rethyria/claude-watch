// The pure glance layer (issue #28): what the Tile and the SHORT_TEXT
// complication SAY, derived from connection state + the Halo census. No
// Android imports in the derivation — GlanceModelTest tables it on plain JVM.
//
// The central rule this file enforces — the reason issue #28 exists at all:
// STATUS MUST REFLECT ACTUAL STREAM HEALTH. The watchOS complication this
// replaces derived its color from the optimistic pairing state ("credentials
// exist" ≈ green) and cheerfully glowed green through entire bridge outages.
// Here, `healthy` is true for EXACTLY ONE ConnectionState — Connected, the
// engine's "an SSE response has been received and the wire is non-silent" —
// and for nothing else. Not paired. Not credentials-on-disk. Not
// Reconnecting-with-paired=true. GlanceModelTest's sabotage test exists to
// make re-deriving it from pairing state a loud failure, not a quiet
// regression.
package dev.claudewatch.wear.glance

import dev.claudewatch.wear.BridgeViewModel
import dev.claudewatch.wear.net.ConnectionState
import dev.claudewatch.wear.ui.halo.HaloModel
import dev.claudewatch.wear.ui.halo.plural
import dev.claudewatch.wear.ui.halo.sessionCensusText

/**
 * Everything a glanceable renders. One derivation feeds BOTH surfaces so they
 * can never disagree:
 *
 *  - [healthy] picks the accent color (Halo Running green vs Error red) —
 *    true ONLY for [ConnectionState.Connected], see the file header.
 *  - [statusText] is the Tile headline — the census session wording verbatim
 *    when connected ("2 sessions", via the shared [sessionCensusText]), the
 *    state's honest name otherwise ("reconnecting", "disconnected", …).
 *  - [detailText] is the Tile's second line: projects/waiting while
 *    connected, the tap affordance while not ("tap to open" — the tile IS
 *    tappable into MainActivity in every state, this line just says so when
 *    opening the app is the fix).
 *  - [shortText] is the complication form. SHORT_TEXT budgets ~7 characters
 *    on many faces before ellipsizing, so this is a separate vocabulary
 *    ("2 sess" / "recon" / "off" / "re-pair"), mapped HERE in the pure layer
 *    — as a field of the same derivation rather than a second function keyed
 *    off the same inputs, so the tile and the complication cannot drift into
 *    describing different worlds. Every value is table-tested to fit the
 *    budget.
 */
data class GlanceStatus(
    val healthy: Boolean,
    val statusText: String,
    val detailText: String,
    val shortText: String,
)

/**
 * Derive the glance status.
 *
 * @param connection the engine's connection state, or NULL when there is no
 *   engine to ask ([BridgeViewModel.peek] returned null — the app process is
 *   not running an engine). Null is NOT an error path: it is the tile
 *   carousel visiting us after a reboot or a swipe-away, and it renders as
 *   honest "disconnected / tap to open" — never as a reason to construct the
 *   singleton (see peek()'s kdoc for why constructing is starting).
 * @param model the derived Halo census for the connected wording. Derived
 *   via [HaloModel.from], so hidden sessions (issue #53's honest hide) and
 *   queue-orphan synthetics are already folded in — the glance census can
 *   never disagree with the home ring's. Ignored unless [connection] is
 *   Connected: a census snapshot from a dead stream is exactly the stale
 *   data this issue forbids rendering as truth.
 */
fun glanceStatus(connection: ConnectionState?, model: HaloModel?): GlanceStatus = when (connection) {
    // No engine in this process: nothing is streaming, full stop. "app not
    // running" would be developer vocabulary — from the wrist the honest
    // word is the same as Stopped's, with the tap affordance spelled out.
    null -> GlanceStatus(
        healthy = false,
        statusText = "disconnected",
        detailText = "tap to open",
        shortText = "off",
    )

    // The ONLY healthy row. The census wording is shared with the home
    // ring's centerpiece (sessionCensusText/plural) — same fact, same words.
    ConnectionState.Connected -> {
        val sessions = model?.sessionCount ?: 0
        val projects = model?.projectCount ?: 0
        val waiting = model?.waitingCount ?: 0
        GlanceStatus(
            healthy = true,
            statusText = sessionCensusText(sessions),
            detailText = when {
                // A blocked agent outranks the project count: "waiting" is
                // the one word that should pull the wrist up. Same wording
                // as the approval card's queue badge ("N waiting").
                waiting > 0 -> "$waiting waiting"
                sessions == 0 -> "connected"
                else -> "$projects ${plural(projects, "project")}"
            },
            // "$n sess" for every n, zero included — "0 sess" is uglier than
            // "idle" but it is the same NUMBER the tile headline shows, and
            // a glance vocabulary where zero gets a special word is how the
            // complication and tile start telling different stories.
            shortText = "$sessions sess",
        )
    }

    // Connecting and Reconnecting collapse — the serviceStatusText precedent
    // (#24): from the wrist they are the same promise, "working on it". Both
    // are NOT healthy: the stream is down RIGHT NOW, however good the
    // prognosis. This row is the watchOS bug's tombstone — it was precisely
    // "reconnecting but paired" that the old complication painted green.
    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> GlanceStatus(
        healthy = false,
        statusText = "reconnecting",
        detailText = "tap to open",
        shortText = "recon",
    )

    ConnectionState.Stopped -> GlanceStatus(
        healthy = false,
        statusText = "disconnected",
        detailText = "tap to open",
        shortText = "off",
    )

    ConnectionState.Pairing -> GlanceStatus(
        healthy = false,
        statusText = "pairing",
        detailText = "tap to open",
        shortText = "pairing",
    )

    is ConnectionState.PairFailed -> GlanceStatus(
        healthy = false,
        statusText = "pair failed",
        detailText = "tap to retry",
        shortText = "no pair",
    )

    is ConnectionState.AuthExpired -> GlanceStatus(
        healthy = false,
        statusText = "re-pair needed",
        detailText = "tap to re-pair",
        shortText = "re-pair",
    )

    // A stranger's bridge answered at the paired address. The long form
    // names the problem; the short form names the FIX (re-pair) because
    // "wrong b" in seven characters explains nothing.
    is ConnectionState.BridgeMismatch -> GlanceStatus(
        healthy = false,
        statusText = "wrong bridge",
        detailText = "tap to re-pair",
        shortText = "re-pair",
    )

    is ConnectionState.ProtoMismatch -> GlanceStatus(
        healthy = false,
        statusText = "update needed",
        detailText = "update the bridge",
        shortText = "update",
    )
}

/**
 * The production read path both glanceable services call: PEEK at the
 * process singleton — never construct (see [BridgeViewModel.peek]) — and
 * derive. A null peek IS an answer: no engine, no stream, "disconnected".
 */
fun peekGlanceStatus(): GlanceStatus {
    val vm = BridgeViewModel.peek() ?: return glanceStatus(null, null)
    return glanceStatus(vm.connection.value, HaloModel.from(vm.state.value))
}

/**
 * The seam the instrumented tests override — the #25 viewModelResolver
 * pattern (see BridgeSessionService.viewModelResolver's kdoc for the whole
 * argument): the REAL HaloTileService.onTileRequest must be exercisable
 * against a fixed [GlanceStatus] without pairing the production singleton to
 * a throwaway fixture. Production never touches this; tests that swap it
 * restore [peekGlanceStatus] in @After.
 */
internal object GlanceStateSource {
    internal var resolver: () -> GlanceStatus = { peekGlanceStatus() }
}
