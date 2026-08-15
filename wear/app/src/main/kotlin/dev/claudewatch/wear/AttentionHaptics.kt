// Attention haptics (issue #129): the state-diffing observer that decides
// WHEN the attention verbs fire. The verbs live in Haptics; everything here
// is the discipline that makes them liveable — rising-edge detection, per-
// prompt buzz memory, burst collapse, and the quiet first sync.
//
// WHERE IT LIVES AND DIES: this object is owned by BridgeViewModel and fed by
// its engineScope collectors — the EVENT path, never composition. The
// ViewModel is the process singleton (issue #24) and BridgeSessionService's
// FGS is what keeps that process (and the SSE socket) alive with the screen
// off and every activity destroyed — so a buzz here fires exactly when the
// wrist needs it most: while nobody is looking. It dies only with the
// process; process death resets every memory below, which is a recorded
// product call (see [onApplied]).
package dev.claudewatch.wear

import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionActivity
import dev.claudewatch.wear.net.ConnectionState

/**
 * Diffs consecutive reducer outputs (and connection transitions) into the
 * attention verbs. Pure Kotlin over an injected clock so the JVM tests table
 * every dedupe rule; [haptics] is a provider because the ViewModel's grammar
 * is a swap-able seam ([BridgeViewModel.haptics]) and the CURRENT one must
 * speak. Entry points are synchronized: events and connection states arrive
 * on different engineScope coroutines.
 *
 * The rules, each pinned by its own JVM test (AttentionHapticsTest):
 *
 *  - PER-PROMPT-ID MEMORY. needsYou fires once per permissionId, ever (this
 *    process). The connect-time replay re-sends every still-pending prompt,
 *    and the reducer's keyed replace makes that a queue "addition" only to a
 *    fresh process — the id memory is what makes it silent to a warm one. A
 *    departure (answered, cleared, expired) never buzzes and never FORGETS:
 *    a stale entry re-surfacing (the ack-to-advance queue keeps them) must
 *    not re-buzz as new. Bounded ([PROMPT_MEMORY_CAP], oldest evicted) —
 *    prompts are rare and transient, the cap only guards unbounded growth.
 *  - PROCESS DEATH FORGETS, DELIBERATELY. A fresh launch onto an old
 *    unanswered card buzzes needsYou ONCE (inside the cold window below):
 *    the new process has no evidence the user ever felt the original buzz —
 *    it may have fired into a dying process, or hours ago before a reboot —
 *    and the card is still blocking the agent. One buzz for a still-
 *    actionable card is honest; the volley is what the cold window prevents.
 *  - EDGE-ONLY ACTIVITY. workFinished fires on an observed WORKING→IDLE
 *    transition per session. First sight only RECORDS (a freshly-connected
 *    watch meeting three idle sessions learns, silently); idle re-sends and
 *    IDLE→WORKING are silent; a pruned session's id is forgotten with it, so
 *    a revived id is first sight again.
 *  - BURST COLLAPSE. At most one buzz per verb per [BURST_WINDOW_MS]: a
 *    reconnect sync's N updates (each its own reducer emission) collapse to
 *    one buzz per verb, and N edges inside ONE emission were always one call
 *    by construction. Suppressed prompts are still recorded — collapsed,
 *    not deferred.
 *  - COLD WINDOW. The first applied event after construction opens a
 *    [COLD_REPLAY_WINDOW_MS] window covering the initial backlog replay
 *    (same sizing rationale as ApprovalNotificationCollector.REPLAY_SETTLE_MS):
 *    inside it workFinished/wentWrong are SILENT — a replayed historical
 *    stop or error frame is pre-existing state, not news — and needsYou is
 *    allowed (the burst window keeps it to one), per the process-death call
 *    above. Reconnects with retained state never re-enter cold: their
 *    replayed frames are only the un-acked gap, which IS news.
 *  - OFFLINE IS SUSTAINED LOSS, NEVER A BLIP. wentWrong fires for a lost
 *    stream only from an observed Connected and only once Reconnecting
 *    reaches attempt [OFFLINE_AFTER_ATTEMPT] — routine stream recycles
 *    reconnect at attempt 1–2 and stay silent (buzzing every SSE drop would
 *    be the churn storm this class exists to prevent). One buzz per outage:
 *    re-armed only by the next Connected. AuthExpired/BridgeMismatch from a
 *    live stream buzz immediately (the channel is dead until the user
 *    re-pairs); Stopped is the user's own disconnect/unpair and stays
 *    silent — their action needs no alarm.
 *
 * TOGGLES (recorded product call): all three verbs ship ALWAYS-ON, no
 * settings toggles this slice. needsYou is non-negotiable per the issue;
 * workFinished is deliberately the faintest effect in the grammar; wentWrong
 * is rare by the sustained-loss rule and is exactly the "your AFK channel
 * died" signal an AFK-awareness product should not bury behind a setting.
 * The dedupe/burst/DND discipline above is the annoyance control; a
 * persisted toggle stays cheap to add if lived experience demands one.
 */
class AttentionHaptics(
    private val haptics: () -> Haptics,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var coldUntilMs = UNANCHORED

    /** permissionIds already buzzed (or observed inside a burst) — see the memory rule. */
    private val buzzedPromptIds = LinkedHashSet<String>()

    /** Last observed activity per LIVE session — the previous state's projection. */
    private var knownActivity: Map<String, SessionActivity> = emptyMap()

    // Per-verb burst anchors; null = never fired (avoids Long-underflow games
    // with a test clock that starts at 0).
    private var lastNeedsYouMs: Long? = null
    private var lastWorkFinishedMs: Long? = null
    private var lastWentWrongMs: Long? = null

    /** True once THIS instance saw Connected — offline edges exist only from live. */
    private var wasConnected = false

    /** One wentWrong per outage: latched at the buzz, re-armed by Connected. */
    private var offlineBuzzed = false

    /**
     * One applied reducer emission: [bridge] is the committed state,
     * [errorEvent] whether the applied frame was the bridge's `error` (the
     * one trigger a state diff cannot see — an appended terminal line is not
     * a diffable edge). Called by BridgeViewModel.handleEvent AFTER the
     * update commits, never from inside it (update() may retry its lambda;
     * a buzz is not retryable).
     */
    @Synchronized
    fun onApplied(bridge: BridgeState, errorEvent: Boolean) {
        val now = clock()
        if (coldUntilMs == UNANCHORED) coldUntilMs = now + COLD_REPLAY_WINDOW_MS
        val cold = now < coldUntilMs

        // needsYou: additions this process has never buzzed. add() doubles as
        // the membership test; a burst-suppressed id is still added (collapsed,
        // not deferred), and departures simply stop appearing — never a buzz,
        // never a forget.
        var newPrompt = false
        for (prompt in bridge.pendingPermissions) {
            if (buzzedPromptIds.add(prompt.permissionId)) newPrompt = true
        }
        while (buzzedPromptIds.size > PROMPT_MEMORY_CAP) {
            buzzedPromptIds.remove(buzzedPromptIds.first())
        }
        if (newPrompt && allows(lastNeedsYouMs, now)) {
            lastNeedsYouMs = now
            haptics().needsYou()
        }

        // workFinished: per-session WORKING→IDLE edges against the previous
        // observation. Unknown ids record only (first sight); the rebuilt map
        // drops pruned sessions so a revived stable id (ACP, #89) is first
        // sight again instead of inheriting a stale edge.
        var finished = false
        val next = HashMap<String, SessionActivity>(bridge.sessions.size)
        for (session in bridge.sessions.values) {
            next[session.sessionId] = session.activity
            val previous = knownActivity[session.sessionId] ?: continue
            if (previous == SessionActivity.WORKING && session.activity == SessionActivity.IDLE) {
                finished = true
            }
        }
        knownActivity = next
        if (finished && !cold && allows(lastWorkFinishedMs, now)) {
            lastWorkFinishedMs = now
            haptics().workFinished()
        }

        // wentWrong, error half. Cold-gated like workFinished: a fresh pair's
        // replay-from-0 can carry historical error frames, and "quiet on
        // first sync" outranks re-announcing an old failure.
        if (errorEvent && !cold && allows(lastWentWrongMs, now)) {
            lastWentWrongMs = now
            haptics().wentWrong()
        }
    }

    /** Connection transitions, from the ViewModel's engine.state collector. */
    @Synchronized
    fun onConnection(state: ConnectionState) {
        when (state) {
            ConnectionState.Connected -> {
                wasConnected = true
                offlineBuzzed = false
            }
            is ConnectionState.Reconnecting ->
                if (state.attempt >= OFFLINE_AFTER_ATTEMPT) lostStream()
            // Terminal-and-needs-the-user: no retry can revive these, so the
            // sustained-loss threshold would only delay honest news.
            is ConnectionState.AuthExpired, is ConnectionState.BridgeMismatch -> lostStream()
            // Stopped = the user's own disconnect/unpair; Pairing/Connecting/
            // PairFailed/ProtoMismatch precede any live stream. All silent.
            else -> {}
        }
    }

    /**
     * Return to the process-birth posture (memory cleared, cold re-armed).
     * Called on unpair: the UiState resets there too, and stale activity
     * memory surviving into a re-pair would turn the new bridge's first
     * idle-flagged re-announce of a remembered id into a phantom
     * WORKING→IDLE edge.
     */
    @Synchronized
    fun reset() {
        coldUntilMs = UNANCHORED
        buzzedPromptIds.clear()
        knownActivity = emptyMap()
        lastNeedsYouMs = null
        lastWorkFinishedMs = null
        lastWentWrongMs = null
        wasConnected = false
        offlineBuzzed = false
    }

    /** No cold gate: an offline edge requires a Connected THIS instance saw, so it is never a replay. */
    private fun lostStream() {
        if (!wasConnected || offlineBuzzed) return
        val now = clock()
        if (!allows(lastWentWrongMs, now)) return
        offlineBuzzed = true
        lastWentWrongMs = now
        haptics().wentWrong()
    }

    private fun allows(last: Long?, now: Long): Boolean =
        last == null || now - last >= BURST_WINDOW_MS

    companion object {
        /**
         * Per-verb refractory. Sized like the collector's REPLAY_SETTLE_MS:
         * a reconnect replay's frames land within ~1 s on hardware, so 3 s
         * collapses a whole sync to one buzz per verb; two genuinely
         * separate prompts inside 3 s still both render — only the second
         * BUZZ is folded into the first.
         */
        internal const val BURST_WINDOW_MS = 3_000L

        /**
         * The quiet-first-sync window, anchored at the first applied event
         * (the backlog replay starts at stream open and this observer only
         * runs on applied frames, so the first frame IS the replay's start).
         */
        internal const val COLD_REPLAY_WINDOW_MS = 3_000L

        /**
         * Reconnecting attempt at which a lost stream counts as OFFLINE.
         * Attempt resets to 0 on every stream open, so reaching 3 means two
         * scheduled reconnects already failed — several seconds of genuine
         * unreachability under the 1→30 s backoff — while a routine stream
         * recycle reconnects at attempt 1 and never gets here.
         */
        internal const val OFFLINE_AFTER_ATTEMPT = 3

        /** Guards unbounded growth only; see the memory rule for why eviction is safe. */
        internal const val PROMPT_MEMORY_CAP = 128

        /** [coldUntilMs] sentinel: no applied event has anchored the window yet. */
        private const val UNANCHORED = Long.MIN_VALUE
    }
}
