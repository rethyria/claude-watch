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

import dev.claudewatch.shared.protocol.BridgeEvent
import dev.claudewatch.shared.protocol.BridgeEventParser
import dev.claudewatch.shared.protocol.ErrorEvent
import dev.claudewatch.shared.protocol.NotificationEvent
import dev.claudewatch.shared.protocol.PermissionClearedEvent
import dev.claudewatch.shared.protocol.PermissionRequestEvent
import dev.claudewatch.shared.protocol.PtyOutputEvent
import dev.claudewatch.shared.protocol.SessionEvent
import dev.claudewatch.shared.protocol.SessionRunState
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.protocol.StopEvent
import dev.claudewatch.shared.protocol.TaskCompleteEvent
import dev.claudewatch.shared.protocol.ToolOutputEvent
import dev.claudewatch.shared.protocol.UnknownEvent

/** What a session is doing right now, from this client's point of view. */
enum class SessionActivity { WORKING, IDLE }

/**
 * Per-session state. The elapsed clock is per session: [activeSinceMs] is the
 * wall time the session last became [SessionActivity.WORKING], and
 * [frozenElapsedMs] preserves the final elapsed span once it went idle.
 */
data class SessionState(
    val sessionId: String,
    val agent: String? = null,
    val cwd: String? = null,
    val folderName: String? = null,
    val activity: SessionActivity = SessionActivity.WORKING,
    val activeSinceMs: Long? = null,
    val frozenElapsedMs: Long? = null,
) {
    /** Elapsed working time at [nowMs]: ticking while WORKING, frozen once idle. */
    fun elapsedMs(nowMs: Long): Long? =
        if (activity == SessionActivity.WORKING && activeSinceMs != null) nowMs - activeSinceMs
        else frozenElapsedMs
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
     * Drop a pending permission this client answered itself. The bridge only
     * pushes `permission-cleared` for prompts resolved elsewhere (hook abort,
     * Codex), so a locally answered prompt is removed locally.
     */
    fun resolvePermission(permissionId: String): BridgeState =
        copy(pendingPermissions = pendingPermissions.filterNot { it.permissionId == permissionId })
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
        // Only the addressed session goes idle; an event with no/unknown
        // sessionId changes nothing (never "all sessions").
        is TaskCompleteEvent -> markIdle(state, event.sessionId, nowMs)
        is StopEvent -> markIdle(state, event.sessionId, nowMs)
        // Output is an activity signal: a session that went idle after a turn
        // starts a fresh elapsed span when it produces output again.
        is PtyOutputEvent -> markWorking(state, event.sessionId, nowMs)
        is ToolOutputEvent -> markWorking(state, event.sessionId, nowMs)
        // Keyed replace: connect-time snapshots re-send pending prompts, and
        // that must not stack duplicates.
        is PermissionRequestEvent -> state.copy(
            pendingPermissions = state.pendingPermissions
                .filterNot { it.permissionId == event.permissionId } + event,
        )
        is PermissionClearedEvent -> state.resolvePermission(event.permissionId)
        is NotificationEvent, is ErrorEvent, is UnknownEvent -> state
    }

    private fun applySession(state: BridgeState, event: SessionEvent, nowMs: Long): BridgeState =
        when (event.state) {
            SessionRunState.CONNECTED -> state
            SessionRunState.RUNNING -> {
                val id = requireNotNull(event.sessionId) // guaranteed by SessionEvent's init
                val existing = state.sessions[id]
                val session = SessionState(
                    sessionId = id,
                    agent = event.agent ?: existing?.agent,
                    cwd = event.cwd ?: existing?.cwd,
                    folderName = event.folderName ?: existing?.folderName,
                    activity = SessionActivity.WORKING,
                    // A replayed/connect-time-sync "running" for an already
                    // working session must not reset its elapsed clock.
                    activeSinceMs = if (existing?.activity == SessionActivity.WORKING) {
                        existing.activeSinceMs
                    } else {
                        nowMs
                    },
                    frozenElapsedMs = null,
                )
                state.copy(sessions = state.sessions + (id to session))
            }
            // Prune: ended sessions leave state entirely; every other
            // session's activity/elapsed state is untouched.
            SessionRunState.ENDED ->
                state.copy(sessions = state.sessions - requireNotNull(event.sessionId))
        }

    private fun markIdle(state: BridgeState, sessionId: String?, nowMs: Long): BridgeState {
        val session = sessionId?.let { state.sessions[it] } ?: return state
        if (session.activity == SessionActivity.IDLE) return state
        return state.copy(
            sessions = state.sessions + (session.sessionId to session.copy(
                activity = SessionActivity.IDLE,
                activeSinceMs = null,
                frozenElapsedMs = session.activeSinceMs?.let { nowMs - it },
            )),
        )
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
        is PermissionRequestEvent -> "permission-request ${event.toolName ?: "?"} (${event.permissionId})"
        is PermissionClearedEvent ->
            "permission-cleared ${event.permissionId}${event.reason?.let { " ($it)" } ?: ""}"
        is StopEvent -> "stop${event.sessionId?.let { " $it" } ?: ""}"
        is TaskCompleteEvent -> "task-complete${event.sessionId?.let { " $it" } ?: ""}"
        is NotificationEvent -> "notification ${event.notificationType ?: ""}".trimEnd()
        is ErrorEvent -> "error ${event.error ?: ""}".trimEnd()
        is UnknownEvent -> "$type ${event.data}"
    }

    private const val PTY_LOG_CHARS = 120
}
