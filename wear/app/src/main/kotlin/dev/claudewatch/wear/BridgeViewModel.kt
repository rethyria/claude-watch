package dev.claudewatch.wear

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.claudewatch.wear.data.CredentialStore
import dev.claudewatch.wear.net.BackoffPolicy
import dev.claudewatch.wear.net.BridgeClient
import dev.claudewatch.wear.net.ConnectionEngine
import dev.claudewatch.wear.net.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * State for the single debug screen. All connection lifecycle behavior lives
 * in [ConnectionEngine] — this class only renders its state, feeds it user
 * intents, and folds SSE events into the screen model.
 */
class BridgeViewModel(
    store: CredentialStore,
    clientFactory: (String, Int) -> BridgeClient = { hostIp, port -> BridgeClient(hostIp, port) },
    backoff: BackoffPolicy = BackoffPolicy(),
) : ViewModel() {

    data class PendingPermission(val permissionId: String, val toolName: String, val raw: String)

    data class UiState(
        val status: String = "unpaired",
        val paired: Boolean = false,
        /** Non-null when the user must pair again; rendered as an explanation. */
        val repairExplanation: String? = null,
        val sessionId: String? = null,
        val pendingPermission: PendingPermission? = null,
        val commandResult: String? = null,
        val decisionResult: String? = null,
        val eventLog: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    // Owns the engine's lifetime; viewModelScope is avoided on purpose (it
    // requires Dispatchers.Main, which plain JVM unit tests don't have).
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = ConnectionEngine(store, engineScope, clientFactory, backoff)

    init {
        engineScope.launch {
            engine.state.collect { connection ->
                _state.update {
                    it.copy(
                        status = statusText(connection),
                        paired = connection.isPairedState(),
                        repairExplanation = repairExplanation(connection),
                    )
                }
            }
        }
        engineScope.launch {
            engine.events.collect { handleEvent(it.type, it.data) }
        }
        engine.start()
    }

    fun pair(host: String, portText: String, code: String) {
        engineScope.launch {
            val port = portText.trim().toIntOrNull()
            if (port == null) {
                _state.update { it.copy(status = "pair failed: invalid port: $portText") }
                return@launch
            }
            val body = engine.pair(host, port, code.trim(), deviceName = "wear-client") ?: return@launch
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
            if (sessionId != null) {
                _state.update { it.copy(sessionId = sessionId) }
            }
        }
    }

    /** User unpair: engine stops, credentials are wiped, nothing retries. */
    fun unpair() {
        engine.stop()
        _state.update {
            it.copy(
                sessionId = null,
                pendingPermission = null,
                commandResult = null,
                decisionResult = null,
                eventLog = emptyList(),
            )
        }
    }

    fun sendCommand(text: String) {
        val sessionId = _state.value.sessionId
        engineScope.launch {
            if (sessionId == null) {
                _state.update { it.copy(commandResult = "command:no-session") }
                return@launch
            }
            val result = try {
                engine.sendCommand(sessionId, text)
            } catch (e: Exception) {
                _state.update { it.copy(commandResult = "command:error ${e.message}") }
                return@launch
            }
            _state.update {
                it.copy(commandResult = if (result == null) "command:not-paired" else "command:${result.status}")
            }
        }
    }

    fun answerPermission(behavior: String) {
        val pending = _state.value.pendingPermission ?: return
        engineScope.launch {
            val message = if (behavior == "deny") "Denied from the watch" else null
            val result = try {
                engine.answerPermission(pending.permissionId, behavior, message)
            } catch (e: Exception) {
                _state.update { it.copy(decisionResult = "decision:error ${e.message}") }
                return@launch
            }
            _state.update {
                when {
                    result == null -> it.copy(decisionResult = "decision:not-paired")
                    // The prompt is gone on the bridge (answered elsewhere or
                    // timed out): tell the user it expired and clear the card
                    // instead of leaving a dead Allow/Deny pair on screen.
                    result.status == 404 -> it.copy(
                        decisionResult = "decision:expired — that permission was already resolved",
                        pendingPermission = null,
                    )
                    result.ok -> it.copy(decisionResult = "decision:${result.status}", pendingPermission = null)
                    else -> it.copy(decisionResult = "decision:${result.status}")
                }
            }
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

    private fun statusText(connection: ConnectionState): String = when (connection) {
        ConnectionState.Stopped -> "unpaired"
        ConnectionState.Pairing -> "pairing"
        is ConnectionState.PairFailed -> "pair failed: ${connection.message}"
        is ConnectionState.ProtoMismatch ->
            "bridge proto ${connection.bridgeProto ?: "unknown"} unsupported (need >= ${connection.minProto})"
        // Deliberately does not contain "paired": events could still be
        // missed until the stream is actually open (see BridgeViewModelTest).
        is ConnectionState.Connecting -> "connecting stream"
        ConnectionState.Connected -> "paired, stream open"
        is ConnectionState.Reconnecting -> "paired, reconnecting (${connection.reason})"
        is ConnectionState.AuthExpired -> "re-pair required"
    }

    private fun repairExplanation(connection: ConnectionState): String? = when (connection) {
        is ConnectionState.AuthExpired -> connection.reason
        is ConnectionState.ProtoMismatch ->
            "This bridge speaks protocol ${connection.bridgeProto ?: "unknown"} but the app needs " +
                "${connection.minProto} or newer. Update the bridge skill on your computer, then pair again."
        else -> null
    }

    private fun ConnectionState.isPairedState(): Boolean = when (this) {
        is ConnectionState.Connecting, ConnectionState.Connected, is ConnectionState.Reconnecting -> true
        else -> false
    }

    override fun onCleared() {
        // Process/UI teardown is NOT unpair: cancel all network activity but
        // keep credentials so the next launch resumes.
        engine.shutdown()
        engineScope.cancel()
    }

    companion object {
        private const val EVENT_LOG_LIMIT = 30

        /** Production wiring: Keystore-encrypted store in app-private files. */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { BridgeViewModel(CredentialStore.singleton(context)) }
        }
    }
}
