// Pure event reducer: folds SSE frames into typed bridge state.
//
// Design constraints (issue #16):
//  - Activity/elapsed state is PER SESSION. An `ended`/`task-complete` event
//    updates only the session matching its sessionId — one session finishing
//    must never corrupt another's status (the iOS app's single global timer
//    did exactly that).
//  - Sessions are pruned from state on session-ended.
//  - lastEventId is committed only after an event fully parses AND applies;
//    a rejected frame leaves state (including lastEventId) untouched, so a
//    reconnect replays it instead of silently skipping past it. Persistence
//    of lastEventId is deliberately NOT wired here — that arrives with the
//    connection-lifecycle issue.
package dev.claudewatch.shared.state

import dev.claudewatch.shared.protocol.AgentsActivity
import dev.claudewatch.shared.protocol.BridgeEvent
import dev.claudewatch.shared.protocol.BridgeEventParser
import dev.claudewatch.shared.protocol.ErrorEvent
import dev.claudewatch.shared.protocol.NotificationEvent
import dev.claudewatch.shared.protocol.PermissionClearedEvent
import dev.claudewatch.shared.protocol.PermissionRequestEvent
import dev.claudewatch.shared.protocol.PermissionSyncEvent
import dev.claudewatch.shared.protocol.MessageEvent
import dev.claudewatch.shared.protocol.PtyOutputEvent
import dev.claudewatch.shared.protocol.SessionEvent
import dev.claudewatch.shared.protocol.SessionRunState
import dev.claudewatch.shared.protocol.SessionSyncEvent
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.protocol.StopEvent
import dev.claudewatch.shared.protocol.TaskCompleteEvent
import dev.claudewatch.shared.protocol.ToolOutputEvent
import dev.claudewatch.shared.protocol.UnknownEvent
import dev.claudewatch.shared.terminal.RingBuffer
import dev.claudewatch.shared.terminal.TerminalLine
import dev.claudewatch.shared.terminal.TerminalLineType
import dev.claudewatch.shared.terminal.ToolOutputFormatter

/** What a session is doing right now, from this client's point of view. */
enum class SessionActivity { WORKING, IDLE }

/**
 * Per-session state. The elapsed clock is per session: [activeSinceMs] is the
 * wall time the session last became [SessionActivity.WORKING], and
 * [frozenElapsedMs] preserves the final elapsed span once it went idle.
 *
 * [terminal] is the session's human-readable terminal history (bounded ring;
 * see [TERMINAL_BUFFER_LINES]) and [thinking] the blinking-cursor flag: set by
 * a locally sent command ([BridgeState.echoCommand]) and cleared by the next
 * output/turn-end event addressed to this session.
 */
data class SessionState(
    val sessionId: String,
    val agent: String? = null,
    val cwd: String? = null,
    val folderName: String? = null,
    /** Bridge-derived session title (additive wire field); null until the bridge reports one. */
    val title: String? = null,
    /**
     * True for a HOOK-CREATED (external, PTY-less) session the bridge does not
     * own — the row's close action HIDES it honestly instead of pretending to
     * kill a process the bridge cannot. Bridge-owned PTY slots omit the wire
     * flag and default to false (killable). Additive wire field (issue #53).
     */
    val external: Boolean = false,
    /**
     * Session-type discriminator (additive wire field, issue #78): "acp" for a
     * session hosted by the Zed ACP adapter; null for bridge-owned PTY and
     * hook-created slots. Preserve-on-absence, exactly like [external].
     */
    val kind: String? = null,
    /**
     * True when the bridge can deliver a dictated prompt into this session LIVE
     * — a bridge-owned PTY (stdin) or an ACP session (inject). The Dictate
     * affordance gates on THIS, NOT on !external (an ACP session is both
     * external and dictatable). Additive wire field (issue #78);
     * preserve-on-absence, exactly like [external].
     */
    val dictatable: Boolean = false,
    /**
     * Git branch of the session's project root (additive wire field, issue
     * #54); null until the bridge reports one (non-git root, older bridge).
     */
    val branch: String? = null,
    /**
     * True when the session's root is a LINKED git worktree. The wire carries
     * the flag ONLY when true; absence preserves UNLESS the payload carries a
     * branch — a branch-bearing payload without the flag is the bridge's
     * worktree-to-plain-checkout drop and clears it (issue #54).
     */
    val worktree: Boolean = false,
    /**
     * The MAIN repo root path — present ONLY for worktree sessions, where it
     * differs from the session's own root; the UI groups the session under
     * basename(repoRoot) when set (issue #54).
     */
    val repoRoot: String? = null,
    /**
     * Observed workflow activity (issue #55); null until the bridge has seen
     * any. The bridge CLEARS by re-announcing an explicit `{running: 0,
     * done: N}` — a present value always replaces, absence always preserves.
     */
    val agents: AgentsActivity? = null,
    /**
     * The session-meta trio behind the pager's `model · mode · use%`
     * subheading (issue #97, Halo v2): model display name, ACP permission-
     * mode id verbatim, integer percent of the context window used. Only ACP
     * sessions ever report them, so all three stay null for PTY/hook slots
     * and the subheading simply omits the missing parts. [contextPct] keys
     * on PRESENCE — 0 is a real value (a fresh session) — and absence never
     * becomes a guess. Preserve-on-absence, exactly like [title].
     */
    val model: String? = null,
    val mode: String? = null,
    val contextPct: Int? = null,
    val activity: SessionActivity = SessionActivity.WORKING,
    val activeSinceMs: Long? = null,
    val frozenElapsedMs: Long? = null,
    val terminal: RingBuffer<TerminalLine> = RingBuffer(TERMINAL_BUFFER_LINES),
    val thinking: Boolean = false,
) {
    /** Elapsed working time at [nowMs]: ticking while WORKING, frozen once idle. */
    fun elapsedMs(nowMs: Long): Long? =
        if (activity == SessionActivity.WORKING && activeSinceMs != null) nowMs - activeSinceMs
        else frozenElapsedMs

    companion object {
        /** Per-session history cap (matches the watchOS app's 200-line buffer). */
        const val TERMINAL_BUFFER_LINES = 200
    }
}

/**
 * Everything the reducer folds out of the event stream. Immutable; every
 * applied event produces a new value. [sessions] preserves insertion order.
 */
data class BridgeState(
    val sessions: Map<String, SessionState> = emptyMap(),
    val pendingPermissions: List<PermissionRequestEvent> = emptyList(),
    val eventLog: List<String> = emptyList(),
    val lastEventId: String? = null,
) {
    /** The session a bare command should target: the most recently active working session. */
    val currentSessionId: String?
        get() = sessions.values.lastOrNull { it.activity == SessionActivity.WORKING }?.sessionId
            ?: sessions.keys.lastOrNull()

    /**
     * Drop a pending permission the client has learned is gone. The bridge
     * pushes `permission-cleared` for every non-answer exit — hook aborts,
     * Codex clears, expiry, and prompts proven answered on the computer
     * (issue #63) — but NOT for prompts resolved from another paired device
     * via /v1/command. So the client must still call this both for a prompt it
     * answered itself (2xx) and for one the bridge reports as no longer
     * existing (404), or the entry lives in state forever.
     */
    fun resolvePermission(permissionId: String): BridgeState =
        copy(pendingPermissions = pendingPermissions.filterNot { it.permissionId == permissionId })

    /**
     * Echo a locally sent command into [sessionId]'s terminal (`> text`) and
     * raise its thinking cursor. Commands are a client action, not an SSE
     * event, so this is the one state transition that does not come through
     * the reducer; the cursor clears when the next output event for the
     * session reduces in. Unknown/absent session: no-op.
     */
    fun echoCommand(sessionId: String?, text: String): BridgeState {
        val session = sessionId?.let { sessions[it] } ?: return this
        return copy(
            sessions = sessions + (session.sessionId to session.copy(
                terminal = session.terminal.append(TerminalLine("> $text", TerminalLineType.COMMAND)),
                thinking = true,
            )),
        )
    }
}

object BridgeEventReducer {

    /** Matches the walking-skeleton debug screen's previous raw-log cap. */
    const val DEFAULT_EVENT_LOG_LIMIT = 30

    sealed interface Result {
        val state: BridgeState
    }

    /** The frame parsed and applied; [state] has lastEventId advanced to the frame's id. */
    data class Applied(override val state: BridgeState, val event: BridgeEvent) : Result

    /**
     * Contract violation: [state] is the UNCHANGED input state — in
     * particular lastEventId did NOT advance past the bad frame.
     */
    data class Rejected(override val state: BridgeState, val error: IllegalArgumentException) : Result

    /**
     * Fold one SSE frame into [state]. Pure: the clock comes in as [nowMs].
     */
    fun reduce(
        state: BridgeState,
        frame: SseFrame,
        nowMs: Long,
        eventLogLimit: Int = DEFAULT_EVENT_LOG_LIMIT,
    ): Result {
        val event = try {
            BridgeEventParser.parse(frame)
        } catch (e: IllegalArgumentException) {
            return Rejected(state, e)
        }
        val applied = apply(state, event, nowMs)
        return Applied(
            applied.copy(
                eventLog = (applied.eventLog + describe(frame.type, event)).takeLast(eventLogLimit),
                // Commit the id only now that the event both parsed and applied.
                lastEventId = frame.id?.takeUnless { it.isEmpty() } ?: state.lastEventId,
            ),
            event,
        )
    }

    private fun apply(state: BridgeState, event: BridgeEvent, nowMs: Long): BridgeState = when (event) {
        is SessionEvent -> applySession(state, event, nowMs)
        is SessionSyncEvent -> applySessionSync(state, event, nowMs)
        // Only the addressed session goes idle; an event with no/unknown
        // sessionId changes nothing (never "all sessions"). A finished turn
        // also lowers the thinking cursor — nothing more is coming.
        is TaskCompleteEvent -> appendTerminal(
            markIdle(state, event.sessionId, nowMs),
            event.sessionId,
            emptyList(),
            clearThinking = true,
        )
        is StopEvent -> appendTerminal(
            markIdle(state, event.sessionId, nowMs),
            event.sessionId,
            listOf(TerminalLine("— stopped —", TerminalLineType.SYSTEM)),
            clearThinking = true,
        )
        // Output is an activity signal: a session that went idle after a turn
        // starts a fresh elapsed span when it produces output again. Output is
        // also what feeds the terminal — and what clears a raised thinking
        // cursor (a blank PTY keepalive frame does neither).
        is PtyOutputEvent -> {
            val lines = ToolOutputFormatter.formatPtyOutput(event.text)
            appendTerminal(
                markWorking(state, event.sessionId, nowMs),
                event.sessionId,
                lines,
                clearThinking = lines.isNotEmpty(),
            )
        }
        is ToolOutputEvent -> appendTerminal(
            markWorking(state, event.sessionId, nowMs),
            event.sessionId,
            ToolOutputFormatter.format(event),
            clearThinking = true,
        )
        // Assistant prose from an ACP session (#79) — the agent talking, not
        // tool noise. Same activity semantics as any other output: it restarts
        // the elapsed span and lowers the thinking cursor. Rendered as OUTPUT
        // for now, so prose and tool text share a colour role; giving prose its
        // own role is a UI change, not a reducer one.
        is MessageEvent -> appendTerminal(
            markWorking(state, event.sessionId, nowMs),
            event.sessionId,
            listOf(TerminalLine(event.text, TerminalLineType.PROSE)),
            clearThinking = true,
        )
        // Keyed replace: connect-time snapshots re-send pending prompts, and
        // that must not stack duplicates.
        is PermissionRequestEvent -> state.copy(
            pendingPermissions = state.pendingPermissions
                .filterNot { it.permissionId == event.permissionId } + event,
        )
        // A card that just vanishes reads as a glitch — or worse, as an
        // approval the user believes they gave. When the bridge tells us WHY,
        // and the reason is one the user can act on, leave a terminal line so
        // the disappearance is explained (issue #63).
        is PermissionClearedEvent -> appendTerminal(
            state.resolvePermission(event.permissionId),
            event.sessionId,
            listOfNotNull(clearedNoticeLine(event.reason)),
            clearThinking = false, // a cleared prompt says nothing about output
        )
        // Authoritative retraction (issue #63): keep only the prompts the
        // bridge still lists. Never additive — payloads arrive as
        // permission-request — so this can only ever DROP, which is exactly
        // what the additive per-prompt re-send could not do for a client that
        // was offline when a prompt died.
        is PermissionSyncEvent -> {
            val live = event.permissionIds.toSet()
            state.copy(pendingPermissions = state.pendingPermissions.filter { it.permissionId in live })
        }
        // A session-addressed bridge error surfaces in that terminal; global
        // errors stay in the event log only.
        is ErrorEvent -> appendTerminal(
            state,
            event.sessionId,
            listOfNotNull(event.error?.let { TerminalLine(it.take(ToolOutputFormatter.MAX_LINE_CHARS), TerminalLineType.ERROR) }),
            clearThinking = true,
        )
        is NotificationEvent, is UnknownEvent -> state
    }

    /**
     * What a cleared prompt is allowed to SAY. Only reasons the user can act
     * on get a line; `hook-aborted` (the agent withdrew its own request) and
     * any future reason we do not understand stay SILENT rather than narrate a
     * guess (issue #63). A null sessionId means the notice has nowhere to land
     * — appendTerminal drops it — but the prompt is still removed regardless.
     *
     * `expired` is DELIBERATELY non-committal. The bridge emits it for three
     * real outcomes it cannot tell apart: a genuine no-answer, a DENY made in
     * the IDE (Claude Code fires no PostToolUse for a tool it never ran, so the
     * deny reaches the bridge as nothing and falls to the expiry timer), and an
     * approve-and-long-run whose PostToolUse lands after the window closed. The
     * old copy — "expired unanswered: answer it on the computer" — was a false
     * instruction in two of those three: the user who already denied it did
     * answer, and the approved tool is running, not waiting. "Check the
     * computer" is true in all three (the still-open dialog, the resolved deny,
     * or the running tool all live there) and never claims the user failed to
     * act (#63 review).
     */
    private fun clearedNoticeLine(reason: String?): TerminalLine? = when (reason) {
        "expired" -> TerminalLine(
            "— approval expired — check the computer —",
            TerminalLineType.SYSTEM,
        )
        "answered-elsewhere" -> TerminalLine(
            "— approval answered on the computer —",
            TerminalLineType.SYSTEM,
        )
        else -> null
    }

    /**
     * Append [lines] to the addressed session's terminal ring, optionally
     * lowering its thinking cursor. Events without a known sessionId change
     * nothing (there is no flat "all sessions" terminal to pollute).
     */
    private fun appendTerminal(
        state: BridgeState,
        sessionId: String?,
        lines: List<TerminalLine>,
        clearThinking: Boolean,
    ): BridgeState {
        val session = sessionId?.let { state.sessions[it] } ?: return state
        val thinking = if (clearThinking) false else session.thinking
        if (lines.isEmpty() && thinking == session.thinking) return state
        return state.copy(
            sessions = state.sessions + (session.sessionId to session.copy(
                terminal = session.terminal.appendAll(lines),
                thinking = thinking,
            )),
        )
    }

    private fun applySession(state: BridgeState, event: SessionEvent, nowMs: Long): BridgeState =
        when (event.state) {
            SessionRunState.CONNECTED -> state
            SessionRunState.RUNNING -> {
                val id = requireNotNull(event.sessionId) // guaranteed by SessionEvent's init
                val existing = state.sessions[id]
                // For an already-known session `running` is a metadata refresh
                // ONLY: the bridge's connect-time sync re-sends `running` for
                // every live slot on EVERY /v1/events connect, so flipping an
                // IDLE session back to WORKING here would restart its elapsed
                // clock (and drop its frozen span) on each routine reconnect.
                // The one live re-emission of `running` for an existing slot
                // (headless prompt run) is immediately followed by pty-output,
                // which markWorking turns into a fresh WORKING span anyway.
                // The `idle` flag (issue #60) is the ONE exception, and only in
                // the IDLE direction — see the latch below.
                val session = existing?.copy(
                    agent = event.agent ?: existing.agent,
                    cwd = event.cwd ?: existing.cwd,
                    folderName = event.folderName ?: existing.folderName,
                    // The bridge re-sends `running` with a fresh title when it
                    // changes; a payload without one must not erase the known title.
                    title = event.title ?: existing.title,
                    // A hook-created slot carries external:true on EVERY session
                    // event (uniform emission); a payload without it (PTY slot,
                    // older bridge) must not erase a known external flag — same
                    // preserve-on-resend rule as folderName/title.
                    external = event.external ?: existing.external,
                    // Session kind + dictatable (issue #78) follow the same
                    // preserve-on-absence rule as external: the bridge carries
                    // them on every event of a slot that has them, and a
                    // payload without either (older bridge, or a slot that
                    // never had them) must not erase what we knew.
                    kind = event.kind ?: existing.kind,
                    dictatable = event.dictatable ?: existing.dictatable,
                    // Git metadata (issue #54) is ONE atomic group keyed on
                    // branch presence: whenever the bridge derives any git
                    // metadata it always sends branch, and worktree/repoRoot
                    // only when truthy — so a branch-bearing payload with
                    // worktree/repoRoot ABSENT is the bridge's worktree-to-
                    // plain-checkout drop, not a partial resend, and must
                    // clear both. Only a branch-less payload (older bridge,
                    // no derivation) preserves the trio. Workflow activity
                    // (issue #55) keeps plain preserve-on-absence. Note
                    // agents: an explicit {running:0, done:N} IS a value and
                    // replaces — that present-but-zero re-announce is the
                    // bridge's ONLY clear path (absence cannot clear).
                    branch = event.branch ?: existing.branch,
                    worktree = if (event.branch != null) event.worktree ?: false else existing.worktree,
                    repoRoot = if (event.branch != null) event.repoRoot else existing.repoRoot,
                    agents = event.agents ?: existing.agents,
                    // Session meta (issue #97): a slot that has the trio
                    // carries it on every event and re-announces on change, so
                    // present replaces and absence preserves — title's exact
                    // doctrine. The Elvis is presence-based, not truthy-based,
                    // which is what lets an explicit contextPct 0 (a fresh
                    // session) land instead of vanishing as falsy.
                    model = event.model ?: existing.model,
                    mode = event.mode ?: existing.mode,
                    contextPct = event.contextPct ?: existing.contextPct,
                )?.let { known ->
                    // ONE-WAY IDLE LATCH (issue #60). `idle: true` is honoured
                    // for a session we already track; its ABSENCE is not —
                    // absence never wakes anything up.
                    //
                    // The asymmetry is the whole point. The reported bug is a
                    // session idled while we were not listening, and that does
                    // NOT only happen before first sight: the watch drops SSE
                    // constantly and keeps its BridgeState across reconnects,
                    // so a `stop` that fired during the gap and then aged out
                    // of the replay ring is lost just as completely for a
                    // session we already know. First-sight-only scoping would
                    // leave that session green forever — issue #60's exact
                    // symptom, on the path its title actually names.
                    //
                    // Honouring it in only this direction is what keeps the
                    // cure from becoming the disease: applying `true` can only
                    // FREEZE a span (idempotently — an already-IDLE session is
                    // untouched, so routine reconnects never re-freeze), which
                    // is precisely the transition the missed `stop` would have
                    // made. It is a WAKE that would be dangerous, because it
                    // restarts the elapsed clock on every reconnect — so
                    // absence stays preserve-on-absence, exactly as it was, and
                    // live `stop`/output events remain the authority for
                    // everything else.
                    // An EXPLICIT `false` is a different statement from absence
                    // and does wake the session (#79). Absence means "I am not
                    // telling you" — every routine reconnect snapshot says that,
                    // which is why waking on it would restart the elapsed clock
                    // constantly. A present `false` means "I know a turn just
                    // started", which the bridge sends only on a turn-start
                    // boundary. ACP sessions need it: their prose is coalesced
                    // to turn end, so no mid-turn event exists to wake the
                    // session, and without this the wrist showed idle for the
                    // whole of every turn.
                    when (event.idle) {
                        true -> idled(known, nowMs)
                        false -> working(known, nowMs)
                        null -> known
                    }
                } ?: SessionState(
                    sessionId = id,
                    agent = event.agent,
                    cwd = event.cwd,
                    folderName = event.folderName,
                    title = event.title,
                    external = event.external ?: false,
                    kind = event.kind,
                    // Backward-compat default (issue #78). Absent means "the
                    // bridge did not say", which we read as dictatable UNLESS we
                    // positively know this is an unreachable external session. A
                    // NEW bridge sends dictatable:true EXPLICITLY for the reachable
                    // kinds (its own PTY, ACP inject) — so an ACP external session
                    // resolves true here and the pill, which gates on THIS resolved
                    // value, still shows for it — and omits it for hook sessions
                    // (external → false). An OLD bridge that never sends the flag
                    // keeps PTY dictation working (non-external → true) instead of
                    // losing the Dictate affordance on every session. NOTE: this is
                    // a default for ABSENCE only; the gate itself reads `dictatable`,
                    // never `external`.
                    dictatable = event.dictatable ?: (event.external != true),
                    branch = event.branch,
                    worktree = event.worktree ?: false,
                    repoRoot = event.repoRoot,
                    agents = event.agents,
                    model = event.model,
                    mode = event.mode,
                    contextPct = event.contextPct,
                    // FIRST SIGHT: the case `idle` was added for (issue #60). A
                    // session whose turn ended before this client connected has
                    // no `stop` left in the replay ring to correct a guess, so
                    // the bridge's flag is the only truth on offer — without it,
                    // a session idle for three hours rendered green on a
                    // freshly-paired watch. (The latch above covers the same
                    // loss for a session we already know.)
                    //
                    // ABSENCE keeps WORKING deliberately. Our bridge now emits
                    // the flag on every session event, so an absent flag means
                    // an OLDER bridge — and defaulting those to IDLE would
                    // paint every genuinely-live session grey on every
                    // reconnect: the same bug wearing the opposite colour.
                    // Guessing WORKING is also the self-correcting guess when
                    // it is wrong (a live session's next output or stop event
                    // is already on its way); guessing IDLE is not.
                    activity = if (event.idle == true) SessionActivity.IDLE else SessionActivity.WORKING,
                    // Elapsed fields stay coherent with that activity: an idle
                    // session has no running span (and nothing frozen either —
                    // this client never observed the span that ended, so it
                    // has no honest duration to show), a working one starts
                    // its span now, exactly as before.
                    activeSinceMs = if (event.idle == true) null else nowMs,
                    frozenElapsedMs = null,
                )
                state.copy(sessions = state.sessions + (id to session))
            }
            // Prune: ended sessions leave state entirely; every other
            // session's activity/elapsed state is untouched.
            SessionRunState.ENDED ->
                state.copy(sessions = state.sessions - requireNotNull(event.sessionId))
        }

    /**
     * The authoritative connect-time set (issue #66): drop every session the
     * bridge did not list, and take its word for what the ones it kept are
     * doing (issue #60).
     *
     * The session set used to only ever GROW — the sole removal path was a
     * `session` event with `state: "ended"`, so anything the bridge forgot
     * WITHOUT emitting one (a restart wiping its map, a crash, a cap eviction
     * that landed while this client was offline) stayed on the wrist forever:
     * green, labelled running, for a process that no longer existed. And a
     * bridge that has forgotten a session can never emit its `ended`, so the
     * client had to be told by absence instead. This frame is that telling.
     *
     * Two guards, both from the issue:
     *  - PRUNE ONLY ON A COMPLETE SYNC. A frame that cannot claim to describe
     *    the whole set has no authority over absence, and a partial sync is
     *    exactly the state in which dropping is most wrong. (An INTERRUPTED
     *    sync needs no guard here: the bridge emits this frame last, so a
     *    connection that died mid-snapshot never delivers it at all — and a
     *    truncated frame fails to parse, which the reducer already rejects
     *    without advancing lastEventId.)
     *  - NEVER CREATE. Sessions arrive as `session` events, which precede this
     *    frame in the same snapshot; an id listed here that we do not know is
     *    one whose re-send we missed, not an invitation to invent a row.
     *
     * Honest-hidden sessions (#53) are untouched by construction: hiding lives
     * in the client's own UI state keyed by id, and this frame carries no
     * sessionId, so it is not "the session speaking" and cannot un-hide one.
     *
     * ACTIVITY (issue #60) rides the same authority, and is the one place the
     * one-way idle latch does NOT apply. On a `session` event absence means
     * "working, or an older bridge" and must never wake anything, because every
     * routine reconnect resend arrives that way. A sync entry is a DESCRIPTION
     * of current state, so it can afford all three answers — and the third is
     * the one the issue asks for: an entry with NO verdict is the bridge saying
     * it has observed no turn signal at all, which renders IDLE, not green.
     * Guessing WORKING there is what put a three-hours-idle session on the
     * wrist in green; guessing IDLE is self-correcting, because a session that
     * really is working re-marks itself on its very next event.
     */
    private fun applySessionSync(state: BridgeState, event: SessionSyncEvent, nowMs: Long): BridgeState {
        var sessions = state.sessions
        for (entry in event.sessions) {
            val known = sessions[entry.id] ?: continue
            // Both transitions are idempotent, so a reconnect that tells us
            // nothing new cannot restart or re-freeze an elapsed span.
            val next = if (entry.idle == false) working(known, nowMs) else idled(known, nowMs)
            if (next !== known) sessions = sessions + (entry.id to next)
        }
        if (!event.complete) return state.copy(sessions = sessions)
        val listed = event.sessions.mapTo(mutableSetOf()) { it.id }
        if (sessions.keys.all { it in listed }) return state.copy(sessions = sessions)
        return state.copy(sessions = sessions.filterKeys { it in listed })
    }

    /**
     * The IDLE transition itself: stop the running span and freeze whatever it
     * had accumulated. Idempotent — an already-idle session is returned
     * unchanged (identity), so a repeated signal can never re-freeze a span
     * that already stopped.
     *
     * Extracted so the two ways a session can go idle — a live `stop`/
     * `task-complete` event via [markIdle], and the bridge's `idle: true` flag
     * on a `session` resend (issue #60) — are the SAME transition by
     * construction. Two hand-written copies would drift, and the drift would
     * show up as a wrong duration on the one screen that exists to be glanced
     * at.
     */
    private fun idled(session: SessionState, nowMs: Long): SessionState =
        if (session.activity == SessionActivity.IDLE) {
            session
        } else {
            session.copy(
                activity = SessionActivity.IDLE,
                activeSinceMs = null,
                frozenElapsedMs = session.activeSinceMs?.let { nowMs - it },
                // #64: the ring renders green on `thinking || activity ==
                // WORKING`, so leaving the cursor raised MASKS this transition
                // entirely — the session stays green however idle it is. The
                // live stop/task-complete path cleared it separately, outside
                // this function, which is exactly the drift this function's own
                // doc comment claimed it prevented. Clearing it here makes the
                // two ways of going idle one transition in fact, not just in
                // the comment.
                thinking = false,
            )
        }

    /** The mirror of [idled]: a turn started, so the elapsed clock runs again.
     *  Idempotent — an already-WORKING session keeps its existing span rather
     *  than restarting it, so a repeated `idle: false` cannot inflate the
     *  clock. */
    private fun working(session: SessionState, nowMs: Long): SessionState =
        if (session.activity == SessionActivity.WORKING) {
            session
        } else {
            session.copy(
                activity = SessionActivity.WORKING,
                activeSinceMs = nowMs,
                frozenElapsedMs = null,
            )
        }

    private fun markIdle(state: BridgeState, sessionId: String?, nowMs: Long): BridgeState {
        val session = sessionId?.let { state.sessions[it] } ?: return state
        val next = idled(session, nowMs)
        if (next === session) return state
        return state.copy(sessions = state.sessions + (session.sessionId to next))
    }

    private fun markWorking(state: BridgeState, sessionId: String?, nowMs: Long): BridgeState {
        val session = sessionId?.let { state.sessions[it] } ?: return state
        if (session.activity == SessionActivity.WORKING) return state
        return state.copy(
            sessions = state.sessions + (session.sessionId to session.copy(
                activity = SessionActivity.WORKING,
                activeSinceMs = nowMs,
                frozenElapsedMs = null,
            )),
        )
    }

    // One human-readable log line per applied event — the typed replacement
    // for the walking skeleton's raw "$type $data" string appending.
    private fun describe(type: String, event: BridgeEvent): String = when (event) {
        is SessionEvent ->
            "session ${event.state.wire} ${event.folderName ?: event.sessionId ?: ""}".trimEnd()
        is PtyOutputEvent -> "pty-output ${event.text.take(PTY_LOG_CHARS)}"
        is ToolOutputEvent ->
            listOfNotNull("tool-output", event.toolName, event.toolOutputText).joinToString(" ")
        is MessageEvent -> "message ${event.role} ${event.text.take(PTY_LOG_CHARS)}"
        is PermissionRequestEvent -> "permission-request ${event.toolName ?: "?"} (${event.permissionId})"
        is PermissionClearedEvent ->
            "permission-cleared ${event.permissionId}${event.reason?.let { " ($it)" } ?: ""}"
        is PermissionSyncEvent ->
            "permission-sync ${event.permissionIds.size} live"
        is SessionSyncEvent ->
            "session-sync ${event.sessions.size} live${if (event.complete) "" else " (partial)"}"
        is StopEvent -> "stop${event.sessionId?.let { " $it" } ?: ""}"
        is TaskCompleteEvent -> "task-complete${event.sessionId?.let { " $it" } ?: ""}"
        is NotificationEvent -> "notification ${event.notificationType ?: ""}".trimEnd()
        is ErrorEvent -> "error ${event.error ?: ""}".trimEnd()
        is UnknownEvent -> "$type ${event.data}"
    }

    private const val PTY_LOG_CHARS = 120
}
