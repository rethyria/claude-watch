package dev.claudewatch.wear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.claudewatch.shared.protocol.AskUserQuestion
import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.shared.protocol.PermissionRequestEvent
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.state.BridgeEventReducer
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.terminal.ToolOutputFormatter
import dev.claudewatch.wear.net.BridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/**
 * State for the single debug screen: pair with the bridge, stream SSE events,
 * send a session-scoped command, answer permission prompts.
 *
 * All event handling goes through :shared's pure [BridgeEventReducer]: this
 * class only does I/O and mirrors the reduced [BridgeState] into [UiState].
 */
class BridgeViewModel : ViewModel() {

    /**
     * One queued approval card, fully resolved for rendering: WHAT is being
     * asked ([toolName] + [requestSummary]) and WHICH session is asking
     * ([sessionLabel]) — the live-testing feedback on the single-slot card
     * that only said "Bash". [options] is the bridge's canonical
     * behavior-keyed list (never inferred from labels or position).
     * [questions] is non-empty exactly for AskUserQuestion prompts (which
     * carry no canonical options): EVERY question of the payload, each with
     * its own option list, rendered by the question card and answered
     * per-question via [answerQuestions].
     */
    data class PendingPermission(
        val permissionId: String,
        val sessionId: String?,
        val toolName: String,
        val requestSummary: String,
        val sessionLabel: String,
        val options: List<PermissionOption>,
        val questions: List<AskUserQuestion> = emptyList(),
    )

    data class UiState(
        val status: String = "unpaired",
        val paired: Boolean = false,
        val sessionId: String? = null,
        /**
         * Approval queue, newest-first. The FRONT is the rendered card. Newest
         * first because the bridge pushes no permission-cleared for prompts
         * resolved from another device or timed out server-side: a stale entry
         * must never shadow a live prompt that arrived after it (answering the
         * stale one 404s, which drops it and reveals the next).
         */
        val permissionQueue: List<PendingPermission> = emptyList(),
        /** permissionId of the answer POST currently in flight, if any. */
        val decisionInFlightId: String? = null,
        /** Why the last answer failed; the prompt it belongs to is still queued. */
        val decisionError: String? = null,
        /**
         * Consecutive answer attempts that failed retryably (transport error
         * or a 5xx) since the last ack. Resets on any authoritative outcome.
         * The sheet uses it to unlock the local-dismiss escape hatch: without
         * one, a bridge that stopped answering (host gone, network changed)
         * would wedge the whole app behind an unanswerable sheet forever.
         */
        val decisionFailureCount: Int = 0,
        val commandResult: String? = null,
        val decisionResult: String? = null,
        /** Last spawn/kill outcome, e.g. "spawn:200" / "kill:200". */
        val sessionActionResult: String? = null,
        val eventLog: List<String> = emptyList(),
        val bridge: BridgeState = BridgeState(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var client: BridgeClient? = null
    private var token: String? = null
    private var eventSource: EventSource? = null

    @Volatile
    private var stopped = false

    fun pair(host: String, portText: String, code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update { it.copy(status = "pairing") }
                val port = portText.trim().toIntOrNull()
                    ?: throw IllegalArgumentException("invalid port: $portText")
                val candidate = BridgeClient(host, port)
                val result = candidate.pair(code.trim(), deviceName = "wear-skeleton")
                val body = result.body
                val newToken = body?.optString("token").takeUnless { it.isNullOrEmpty() }
                if (!result.ok || body == null || newToken == null) {
                    val error = body?.optString("error") ?: ""
                    _state.update { it.copy(status = "pair failed: ${result.status} $error") }
                    return@launch
                }
                client = candidate
                token = newToken
                // Seed the session id from the pair snapshot when one is running.
                var sessionId: String? = null
                val sessions = body.optJSONArray("sessions")
                if (sessions != null) {
                    for (i in 0 until sessions.length()) {
                        val session = sessions.optJSONObject(i) ?: continue
                        if (session.optString("state") == "running") {
                            sessionId = session.optString("id").takeUnless { it.isEmpty() }
                        }
                    }
                }
                // Don't report "paired" yet: the stream isn't open, so events
                // can still be missed. onOpen below is the ready signal (and
                // what the e2e test gates on before firing hooks).
                _state.update { it.copy(status = "pair ok, opening stream", paired = true, sessionId = sessionId) }
                connectEvents()
            } catch (e: Exception) {
                _state.update { it.copy(status = "pair error: ${e.message}") }
            }
        }
    }

    fun sendCommand(text: String) {
        val currentClient = client
        val currentToken = token
        val sessionId = _state.value.sessionId
        if (currentClient == null || currentToken == null) {
            _state.update { it.copy(commandResult = "command:not-paired") }
            return
        }
        if (sessionId == null) {
            _state.update { it.copy(commandResult = "command:no-session") }
            return
        }
        // Echo the command into the session's terminal and raise its thinking
        // cursor BEFORE the network round-trip (synchronously, so the send is
        // visible immediately); the cursor clears when the session's next
        // output event reduces in.
        _state.update { it.withBridge(it.bridge.echoCommand(sessionId, text)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = currentClient.sendCommand(currentToken, sessionId, text)
                _state.update { it.copy(commandResult = "command:${result.status}") }
            } catch (e: Exception) {
                _state.update { it.copy(commandResult = "command:error ${e.message}") }
            }
        }
    }

    /** Spawn a fresh agent session ("claude" or "codex") in a bridge-owned PTY. */
    fun spawnSession(agent: String) {
        val currentClient = client
        val currentToken = token
        if (currentClient == null || currentToken == null) {
            _state.update { it.copy(sessionActionResult = "spawn:not-paired") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = currentClient.spawnSession(currentToken, agent)
                val spawnedId = result.body?.optString("sessionId").takeUnless { it.isNullOrEmpty() }
                _state.update {
                    it.copy(
                        sessionActionResult = "spawn:${result.status}",
                        // Target the new session right away; the SSE `running`
                        // event makes it the current session moments later.
                        sessionId = spawnedId ?: it.sessionId,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(sessionActionResult = "spawn:error ${e.message}") }
            }
        }
    }

    /** Kill [sessionId]; the page disappears when the bridge's `ended` event prunes it. */
    fun killSession(sessionId: String) {
        val currentClient = client
        val currentToken = token
        if (currentClient == null || currentToken == null) {
            _state.update { it.copy(sessionActionResult = "kill:not-paired") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = currentClient.killSession(currentToken, sessionId)
                _state.update { it.copy(sessionActionResult = "kill:${result.status}") }
            } catch (e: Exception) {
                _state.update { it.copy(sessionActionResult = "kill:error ${e.message}") }
            }
        }
    }

    /**
     * Answer the queued prompt [permissionId] — always the id of the RENDERED
     * card, passed down from the UI, never "whatever is globally current"
     * (the watchOS race that approved the wrong permission). Dismissal is
     * ack-gated: the prompt leaves the queue only on an authoritative outcome
     *  - 2xx: we resolved it ourselves;
     *  - 404: gone (resolved elsewhere or timed out server-side — keeping it
     *    would wedge a zombie prompt forever, since the bridge pushes no
     *    permission-cleared for either);
     *  - 401/403: this device's token is dead (bridge restarted, device
     *    revoked). No retry with this token can ever succeed and the new
     *    bridge pushes no permission-cleared, so keeping the card would wedge
     *    the whole app — pairing page included — behind an unanswerable
     *    sheet. The card is dropped WITHOUT deciding anything (the bridge's
     *    own timeout owns the real outcome) and a re-pair error is surfaced.
     * Any retryable failure (transport, 5xx) keeps the prompt queued and
     * surfaces the error — never a silent inversion of an approval into a
     * 10-minute auto-deny — and bumps [UiState.decisionFailureCount], which
     * unlocks the sheet's [dismissPermissionLocally] escape hatch.
     */
    fun answerPermission(permissionId: String, behavior: String) {
        sendDecision(permissionId) { currentClient, currentToken ->
            val message = if (behavior == "deny") "Denied from the watch" else null
            currentClient.answerPermission(currentToken, permissionId, behavior, message)
        }
    }

    /**
     * Answer the queued AskUserQuestion prompt [permissionId] with an answer
     * for EVERY question — [answers] is positional, one entry per question in
     * the prompt's question order (a selected option's label or free typed
     * text); the bridge zips the array with the questions into
     * `updatedInput.answers` for the blocked hook. Positional, not text-keyed:
     * questions with duplicate text must still each count as answered (a
     * text-keyed map collapses them and would deadlock the send gate). Same
     * ack-gated dismissal and failure semantics as [answerPermission]: the
     * card leaves the queue only on 2xx/404 (or dead-token 401/403), a
     * retryable failure keeps it rendered with the error surfaced and counts
     * toward the local-dismiss escape hatch.
     */
    fun answerQuestions(permissionId: String, answers: List<String>) {
        if (answers.isEmpty()) return
        sendDecision(permissionId) { currentClient, currentToken ->
            currentClient.answerQuestions(currentToken, permissionId, answers)
        }
    }

    private fun sendDecision(
        permissionId: String,
        post: (BridgeClient, String) -> BridgeClient.ApiResult,
    ) {
        val currentClient = client
        val currentToken = token
        // Answer only a prompt that is actually still queued.
        if (_state.value.bridge.pendingPermissions.none { it.permissionId == permissionId }) return
        if (currentClient == null || currentToken == null) return
        _state.update { it.copy(decisionInFlightId = permissionId, decisionError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = post(currentClient, currentToken)
                _state.update { ui ->
                    // The bridge pushes no permission-cleared when a prompt is
                    // resolved via /v1/command from another paired device, or
                    // when it times out server-side — only hook-abort and
                    // Codex clears broadcast. So both outcomes here mean the
                    // prompt is gone and must be dropped locally:
                    //  - ok: we resolved it ourselves.
                    //  - 404: the bridge says it no longer exists (answered
                    //    elsewhere or timed out). Keeping it would wedge a
                    //    zombie prompt in state forever.
                    val gone = result.ok || result.status == 404
                    // Dead token (bridge restarted / device revoked): also
                    // dropped — see the function doc. Nothing is decided on
                    // the user's behalf; only the local card goes away.
                    val authDead = result.status == 401 || result.status == 403
                    val next = ui.copy(
                        decisionResult = "decision:${result.status}",
                        decisionInFlightId = null,
                        decisionError = when {
                            result.ok -> null
                            result.status == 404 -> "Already resolved elsewhere"
                            authDead -> "Not authorized — re-pair with the bridge"
                            else -> "Decision failed: HTTP ${result.status}"
                        },
                        decisionFailureCount =
                            if (gone || authDead) 0 else ui.decisionFailureCount + 1,
                    )
                    if (gone || authDead) next.withBridge(next.bridge.resolvePermission(permissionId)) else next
                }
            } catch (e: Exception) {
                // Transport failure: the prompt stays queued (it may well still
                // be pending server-side) and the error is surfaced on the card.
                _state.update {
                    it.copy(
                        decisionResult = "decision:error ${e.message}",
                        decisionInFlightId = null,
                        decisionError = "Decision failed: ${e.message}",
                        decisionFailureCount = it.decisionFailureCount + 1,
                    )
                }
            }
        }
    }

    /**
     * Drop [permissionId] from the LOCAL queue without sending any decision —
     * the escape hatch for a bridge that stopped answering (host gone,
     * network changed, laptop closed). Never a false resolve: nothing is
     * decided on the user's behalf, the bridge's own pending-permission
     * timeout owns the real outcome, and if the prompt is in fact still live
     * the user simply stops seeing it here. The sheet offers this only after
     * repeated failed answer attempts ([UiState.decisionFailureCount]);
     * without it an unreachable bridge would wedge the whole app — pairing
     * page included — behind an unanswerable full-screen sheet.
     */
    fun dismissPermissionLocally(permissionId: String) {
        _state.update { ui ->
            val next = ui.copy(
                decisionError = null,
                decisionFailureCount = 0,
                decisionInFlightId = ui.decisionInFlightId.takeUnless { it == permissionId },
            )
            next.withBridge(next.bridge.resolvePermission(permissionId))
        }
    }

    private val sseListener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            _state.update { it.copy(status = "paired, stream open") }
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            handleEvent(id, type ?: "message", data)
        }

        override fun onClosed(eventSource: EventSource) {
            scheduleReconnect("stream closed")
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            scheduleReconnect("stream failure: ${t?.message ?: response?.code}")
        }
    }

    private fun connectEvents() {
        val currentClient = client ?: return
        val currentToken = token ?: return
        eventSource?.cancel()
        // Before any event has parsed AND applied, connect with "0" so the
        // bridge replays its whole ring buffer. Without it, an event pushed
        // between pair success and the stream actually opening is lost
        // forever: a connect with no Last-Event-ID gets no replay, and the
        // connect-time sync event reuses the newest buffered id, so a later
        // reconnect's "id > lastId" replay can never recover the miss. The
        // reducer commits lastEventId only after a successful parse+apply, so
        // a rejected frame is also replayed on reconnect instead of skipped.
        val lastEventId = _state.value.bridge.lastEventId ?: "0"
        eventSource = currentClient.openEvents(currentToken, lastEventId, sseListener)
    }

    private fun scheduleReconnect(reason: String) {
        if (stopped) return
        _state.update { it.copy(status = "paired, reconnecting ($reason)") }
        viewModelScope.launch(Dispatchers.IO) {
            delay(RECONNECT_DELAY_MS)
            if (!stopped && _state.value.paired) connectEvents()
        }
    }

    private fun handleEvent(id: String?, type: String, data: String) {
        val frame = SseFrame(id, type, data)
        _state.update { ui ->
            when (val result = BridgeEventReducer.reduce(ui.bridge, frame, System.currentTimeMillis())) {
                is BridgeEventReducer.Applied -> ui.withBridge(result.state)
                // Contract violation: drop the frame, leave state (incl.
                // lastEventId) untouched so a reconnect replays it.
                is BridgeEventReducer.Rejected -> ui
            }
        }
    }

    /** Mirror the reduced bridge state into the flat fields the screen renders. */
    private fun UiState.withBridge(bridge: BridgeState): UiState = copy(
        bridge = bridge,
        eventLog = bridge.eventLog,
        // Sticky fallback: keep targeting the last known session when none is
        // currently running (matches the skeleton's previous behavior).
        sessionId = bridge.currentSessionId ?: sessionId,
        // The full pending queue, newest-first (see UiState.permissionQueue
        // for why), each entry resolved against the CURRENT session table so
        // late-arriving session metadata still labels an earlier prompt.
        permissionQueue = bridge.pendingPermissions.asReversed().map { it.toPending(bridge) },
    )

    private fun PermissionRequestEvent.toPending(bridge: BridgeState): PendingPermission {
        val session = sessionId?.let { bridge.sessions[it] }
        // AskUserQuestion prompts are content, not permission decisions: they
        // carry per-question option lists instead of canonical options, and
        // the question card answers them via answerQuestions. A question
        // payload that parses to nothing degrades to the plain allow/deny
        // card below rather than an unanswerable sheet.
        val askQuestions = questions
        return PendingPermission(
            permissionId = permissionId,
            sessionId = sessionId,
            toolName = toolName ?: "?",
            requestSummary = ToolOutputFormatter.describeToolRequest(toolName, toolInput),
            sessionLabel = session?.folderName
                ?: session?.agent
                ?: sessionId?.take(8)
                ?: "unknown session",
            // Behavior-keyed options straight from the bridge; a legacy event
            // without options still gets behavior-based allow/deny (never
            // label matching). Question prompts render questions, not options.
            options = if (askQuestions.isNotEmpty()) emptyList() else options.ifEmpty {
                listOf(
                    PermissionOption("allow", "Yes"),
                    PermissionOption("deny", "No"),
                )
            },
            questions = askQuestions,
        )
    }

    override fun onCleared() {
        stopped = true
        eventSource?.cancel()
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 2_000L
    }
}
