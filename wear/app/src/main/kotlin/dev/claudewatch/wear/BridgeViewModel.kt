package dev.claudewatch.wear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.state.BridgeEventReducer
import dev.claudewatch.shared.state.BridgeState
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

    data class PendingPermission(val permissionId: String, val toolName: String)

    data class UiState(
        val status: String = "unpaired",
        val paired: Boolean = false,
        val sessionId: String? = null,
        val pendingPermission: PendingPermission? = null,
        val commandResult: String? = null,
        val decisionResult: String? = null,
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = currentClient.sendCommand(currentToken, sessionId, text)
                _state.update { it.copy(commandResult = "command:${result.status}") }
            } catch (e: Exception) {
                _state.update { it.copy(commandResult = "command:error ${e.message}") }
            }
        }
    }

    fun answerPermission(behavior: String) {
        val currentClient = client
        val currentToken = token
        val pending = _state.value.pendingPermission ?: return
        if (currentClient == null || currentToken == null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val message = if (behavior == "deny") "Denied from the watch" else null
                val result = currentClient.answerPermission(currentToken, pending.permissionId, behavior, message)
                _state.update { ui ->
                    val next = ui.copy(decisionResult = "decision:${result.status}")
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
                    if (gone) next.withBridge(next.bridge.resolvePermission(pending.permissionId)) else next
                }
            } catch (e: Exception) {
                _state.update { it.copy(decisionResult = "decision:error ${e.message}") }
            }
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
        // Show the NEWEST pending prompt. The bridge pushes no
        // permission-cleared for prompts resolved from another paired device
        // or timed out server-side, so an older entry can go stale in state;
        // it must never shadow a live prompt that arrived after it.
        pendingPermission = bridge.pendingPermissions.lastOrNull()?.let {
            PendingPermission(it.permissionId, it.toolName ?: "?")
        },
    )

    override fun onCleared() {
        stopped = true
        eventSource?.cancel()
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 2_000L
    }
}
