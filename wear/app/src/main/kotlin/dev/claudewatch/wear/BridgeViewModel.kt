package dev.claudewatch.wear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
     */
    data class PendingPermission(
        val permissionId: String,
        val sessionId: String?,
        val toolName: String,
        val requestSummary: String,
        val sessionLabel: String,
        val options: List<PermissionOption>,
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
        /**
         * The command box's text, owned HERE (not by the composable) because
         * the ack-gated send owns its lifecycle: cleared when a send goes in
         * flight, RESTORED when the send fails so the exact text can be
         * retried — never silently lost (the watchOS trap: dictated text
         * swallowed by any transport error/401/404/500 while the UI claimed
         * it was sent).
         */
        val commandDraft: String = "",
        /**
         * Text of the send currently awaiting the bridge's ack. Rendered as a
         * pending indicator; the terminal echo happens only on the 2xx ack.
         */
        val commandInFlightText: String? = null,
        /** Why the last send failed/was refused; the text is back in [commandDraft]. */
        val commandError: String? = null,
        val commandResult: String? = null,
        val decisionResult: String? = null,
        /** Last spawn/kill outcome, e.g. "spawn:200" / "kill:200". */
        val sessionActionResult: String? = null,
        val eventLog: List<String> = emptyList(),
        val bridge: BridgeState = BridgeState(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /**
     * The haptic grammar spoken on command outcomes. Defaults to the no-op so
     * plain-JVM unit tests can construct the ViewModel; [MainActivity] swaps
     * in [VibratorHaptics], tests may swap in a recorder. Volatile: outcomes
     * fire from the IO dispatcher.
     */
    @Volatile
    var haptics: Haptics = Haptics.None

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

    /** Mirror the command box into [UiState.commandDraft] (see its doc for why the VM owns it). */
    fun updateCommandDraft(text: String) {
        _state.update { it.copy(commandDraft = text) }
    }

    /**
     * A recognizer result (RecognizerIntent.ACTION_RECOGNIZE_SPEECH) landed:
     * put the transcription in the draft — so a refused/failed send leaves it
     * visible and retryable instead of vanishing — and send it through the
     * exact same ack-gated path as typed text.
     */
    fun dictationResult(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _state.update { it.copy(commandDraft = trimmed) }
        sendCommand(trimmed)
    }

    /** The watch has no speech recognizer activity: surface it, send nothing. */
    fun dictationUnavailable() {
        haptics.commandFailed()
        _state.update { it.copy(commandError = "No speech recognizer on this watch — type the command") }
    }

    /**
     * Ack-gated command send (issue #20). The confirmed watchOS trap was the
     * inverse: echo "> command" + thinking cursor BEFORE the network call and
     * swallow every failure — the transcription silently lost while the UI
     * claimed it was sent. Here:
     *  - the text leaves the draft and shows as PENDING
     *    ([UiState.commandInFlightText]) until the bridge answers;
     *  - the terminal echo + thinking cursor happen only on a 2xx ack (in the
     *    same atomic state update that clears the pending marker), with the
     *    ack tick of the haptic grammar;
     *  - any failure (transport, timeout, non-2xx) echoes NOTHING, surfaces
     *    the error, buzzes, and RESTORES the text into the draft so retry
     *    re-sends the same text — unless newer text claimed the draft while
     *    the send was pending, in which case the newer text wins and the
     *    failed text rides the error message (see [sendFailed]);
     *  - unpaired / no session / send-already-in-flight refuse cleanly up
     *    front: error surfaced, text kept in the draft, no POST, no echo —
     *    never pretending to send.
     */
    fun sendCommand(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val currentClient = client
        val currentToken = token
        val sessionId = _state.value.sessionId
        fun refuse(result: String, error: String) {
            haptics.commandFailed()
            _state.update { it.copy(commandResult = result, commandError = error, commandDraft = trimmed) }
        }
        if (currentClient == null || currentToken == null) {
            refuse("command:not-paired", "Not paired — command not sent")
            return
        }
        if (sessionId == null) {
            refuse("command:no-session", "No session — command not sent")
            return
        }
        if (_state.value.commandInFlightText != null) {
            // One send at a time: a second command while the first awaits its
            // ack is refused into the draft (kept, never silently dropped).
            refuse("command:busy", "Still sending the previous command")
            return
        }
        // Pending, NOT echoed: the terminal shows nothing until the bridge
        // acks. The draft empties so the pending indicator owns the text.
        _state.update { it.copy(commandInFlightText = trimmed, commandError = null, commandDraft = "") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = currentClient.sendCommand(currentToken, sessionId, trimmed)
                if (result.ok) {
                    haptics.commandAcked()
                    _state.update { ui ->
                        // Echo and pending-clear are one atomic update: there
                        // is no observable state where the command is echoed
                        // but still pending, or acked but not echoed.
                        val echoed = ui.withBridge(ui.bridge.echoCommand(sessionId, trimmed))
                        echoed.copy(
                            commandResult = "command:${result.status}",
                            commandInFlightText = null,
                        )
                    }
                } else {
                    sendFailed(trimmed, "command:${result.status}", "HTTP ${result.status}")
                }
            } catch (e: Exception) {
                // Transport error or timeout: same contract as a non-2xx.
                sendFailed(trimmed, "command:error ${e.message}", "${e.message}")
            }
        }
    }

    /**
     * A send that went in flight failed (non-2xx, transport error, timeout):
     * echo nothing, surface the error, buzz, and put the text back where a
     * retry finds it. The restore is CONDITIONAL: it only refills the draft
     * this send emptied. Text typed or dictated during the pending window
     * owns the draft — overwriting it with the old in-flight text would
     * silently destroy the newer text, the exact loss class issue #20 exists
     * to prevent. When the draft is occupied, the failed text stays visible
     * inside the surfaced error instead, so it is still never silently lost.
     */
    private fun sendFailed(trimmed: String, result: String, reason: String) {
        haptics.commandFailed()
        _state.update {
            val draftFree = it.commandDraft.isEmpty()
            it.copy(
                commandResult = result,
                commandInFlightText = null,
                commandError = if (draftFree) {
                    "Send failed: $reason"
                } else {
                    "Send failed: $reason — not sent: “$trimmed”"
                },
                commandDraft = if (draftFree) trimmed else it.commandDraft,
            )
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
        val currentClient = client
        val currentToken = token
        // Answer only a prompt that is actually still queued.
        if (_state.value.bridge.pendingPermissions.none { it.permissionId == permissionId }) return
        if (currentClient == null || currentToken == null) return
        _state.update { it.copy(decisionInFlightId = permissionId, decisionError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val message = if (behavior == "deny") "Denied from the watch" else null
                val result = currentClient.answerPermission(currentToken, permissionId, behavior, message)
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
            // label matching).
            options = options.ifEmpty {
                listOf(
                    PermissionOption("allow", "Yes"),
                    PermissionOption("deny", "No"),
                )
            },
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
