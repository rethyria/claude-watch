package dev.claudewatch.wear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import org.json.JSONObject

/**
 * State for the single debug screen: pair with the bridge, stream SSE events,
 * send a session-scoped command, answer permission prompts.
 */
class BridgeViewModel : ViewModel() {

    data class PendingPermission(val permissionId: String, val toolName: String, val raw: String)

    data class UiState(
        val status: String = "unpaired",
        val paired: Boolean = false,
        val sessionId: String? = null,
        val pendingPermission: PendingPermission? = null,
        val commandResult: String? = null,
        val decisionResult: String? = null,
        val eventLog: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var client: BridgeClient? = null
    private var token: String? = null
    private var eventSource: EventSource? = null

    @Volatile
    private var lastEventId: String? = null

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
                _state.update { it.copy(status = "paired", paired = true, sessionId = sessionId) }
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
                _state.update {
                    it.copy(
                        decisionResult = "decision:${result.status}",
                        pendingPermission = if (result.ok) null else it.pendingPermission,
                    )
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
            if (!id.isNullOrEmpty()) lastEventId = id
            handleEvent(type ?: "message", data)
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

    private fun handleEvent(type: String, data: String) {
        var sessionId: String? = null
        var pending: PendingPermission? = null
        try {
            val parsed = JSONObject(data)
            when (type) {
                "session" ->
                    if (parsed.optString("state") == "running") {
                        sessionId = parsed.optString("sessionId").takeUnless { it.isEmpty() }
                    }
                "permission-request" -> {
                    val permissionId = parsed.optString("permissionId")
                    if (permissionId.isNotEmpty()) {
                        pending = PendingPermission(permissionId, parsed.optString("tool_name"), data)
                    }
                }
            }
        } catch (_: Exception) {
            // Non-JSON payload: still rendered raw below.
        }
        _state.update {
            it.copy(
                eventLog = (it.eventLog + "$type $data").takeLast(EVENT_LOG_LIMIT),
                sessionId = sessionId ?: it.sessionId,
                pendingPermission = pending ?: it.pendingPermission,
            )
        }
    }

    override fun onCleared() {
        stopped = true
        eventSource?.cancel()
    }

    private companion object {
        const val EVENT_LOG_LIMIT = 30
        const val RECONNECT_DELAY_MS = 2_000L
    }
}
