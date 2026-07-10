package dev.claudewatch.wear.net

import dev.claudewatch.wear.data.BridgeCredentials
import dev.claudewatch.wear.data.CredentialStore
import dev.claudewatch.wear.data.PersistedConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.json.JSONObject
import kotlin.math.min
import kotlin.random.Random

/**
 * Connection health as this layer derives it — the UI only renders it.
 * [Connected] means an SSE response has actually been received AND the wire
 * has been non-silent within the heartbeat window (silence beyond it fails
 * the stream via the socket read timeout, flipping to [Reconnecting]).
 */
sealed interface ConnectionState {
    /**
     * Unpaired or explicitly stopped by the user. Terminal until the next
     * pair(): never retried, never counted as a failure.
     */
    data object Stopped : ConnectionState

    data object Pairing : ConnectionState

    /** Pairing was rejected or unreachable. Nothing was wiped, nothing retries. */
    data class PairFailed(val message: String) : ConnectionState

    /** The bridge speaks an older protocol than this client supports. */
    data class ProtoMismatch(val bridgeProto: String?, val minProto: Int) : ConnectionState

    /** Actively opening the SSE stream (attempt 0 = first connect). */
    data class Connecting(val attempt: Int) : ConnectionState

    data object Connected : ConnectionState

    /** Waiting out an exponential-backoff delay before the next connect. */
    data class Reconnecting(val attempt: Int, val reason: String) : ConnectionState

    /**
     * The bridge definitively rejected our token (HTTP 401). Credentials are
     * wiped and re-onboarding is required. This is the ONLY path that erases
     * a pairing — transient network errors never do.
     */
    data class AuthExpired(val reason: String) : ConnectionState
}

/**
 * Exponential backoff 1 s → 30 s with jitter (uniform over the upper half of
 * the exponential step, so retries neither hammer at a fixed cadence nor
 * synchronize across devices).
 */
class BackoffPolicy(
    private val baseMs: Long = 1_000L,
    private val maxMs: Long = 30_000L,
    private val random: Random = Random.Default,
) {
    /** Delay before reconnect [attempt] (1-based). */
    fun delayMsFor(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)
        val capped = min(baseMs shl shift, maxMs)
        val floor = capped / 2
        return floor + random.nextLong(capped - floor + 1)
    }

    private companion object {
        const val MAX_SHIFT = 20 // 1 s << 20 already far beyond any sane cap
    }
}

/**
 * Owns the whole connection lifecycle: pairing (with the proto min-version
 * gate), the SSE stream, reconnect scheduling, credential persistence and the
 * derived [ConnectionState]. Design points, each pinned to a confirmed client
 * bug in the iOS/watchOS apps this port replaces:
 *
 *  - Exponential backoff 1→30 s with jitter — never a fixed 1 s hammer.
 *  - The heartbeat watchdog is OkHttp's socket read timeout (see
 *    [BridgeClient]) — never an app timer that can be scheduled on a thread
 *    without a live dispatcher.
 *  - Single-flight reconnect: an epoch counter plus explicit teardown means
 *    at most one EventSource exists; stale callbacks are dropped, and the old
 *    engine is cancelled before a new one starts (no leaked connections).
 *  - [stop] (user disconnect/unpair) cancels everything, clears credentials,
 *    and is never treated as a failure — no zombie reconnects after unpair.
 *  - Transient errors NEVER wipe credentials; only a definitive HTTP 401
 *    does, landing in [ConnectionState.AuthExpired] with an explanation.
 *  - There is deliberately NO polling fallback: a broken stream is shown as
 *    broken and retried, never silently degraded to a one-way channel.
 */
class ConnectionEngine(
    private val store: CredentialStore,
    private val scope: CoroutineScope,
    private val clientFactory: (hostIp: String, port: Int) -> BridgeClient =
        { hostIp, port -> BridgeClient(hostIp, port) },
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val minProto: Int = MIN_PROTO_VERSION,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    data class SseEvent(val id: String?, val type: String, val data: String)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<SseEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SseEvent> = _events

    private val lock = Any()
    private var epoch = 0
    private var attempt = 0
    private var client: BridgeClient? = null
    private var token: String? = null
    private var eventSource: EventSource? = null
    private var reconnectJob: Job? = null
    private var stopped = true

    @Volatile
    private var lastEventId: String = PersistedConnection.FULL_REPLAY_EVENT_ID

    // Replay-cursor persistence: a conflated single-collector queue keeps the
    // writes ordered (a launch-per-event would race ids out of order) and
    // coalesces bursts into fewer disk writes.
    private val persistCursor = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch { persistCursor.collect { store.saveLastEventId(it) } }
    }

    /**
     * Cold start: resume from persisted credentials when present. A transient
     * connect failure here lands in the ordinary retry loop — it must never
     * erase the pairing (the watchOS app wiped a valid pairing on a single
     * failed 3-second probe at launch).
     */
    fun start() {
        scope.launch {
            val persisted = store.read()
            val credentials = persisted.credentials ?: return@launch // stays Stopped
            synchronized(lock) {
                // Don't stomp a pair() already in flight or a running engine.
                if (!stopped || _state.value != ConnectionState.Stopped) return@launch
                lastEventId = persisted.lastEventId
                client = clientFactory(credentials.hostIp, credentials.port)
                token = credentials.token
                stopped = false
                attempt = 0
            }
            connect()
        }
    }

    /**
     * Pair with a bridge: proto min-version gate first, then the code
     * exchange, then persist + connect. Returns the pair response body on
     * success (the caller seeds its session snapshot from it), null on any
     * failure — in which case state explains why and NOTHING is retried or
     * wiped (a rejected pairing code is not a reason to erase a previous
     * pairing's credentials, which stay in the store untouched).
     */
    suspend fun pair(hostIp: String, port: Int, code: String, deviceName: String): JSONObject? =
        withContext(io) {
            val pairEpoch = synchronized(lock) {
                teardownLocked()
                _state.value = ConnectionState.Pairing
                epoch
            }
            val candidate = try {
                clientFactory(hostIp, port)
            } catch (e: IllegalArgumentException) {
                _state.value = ConnectionState.PairFailed(e.message ?: "invalid bridge address")
                return@withContext null
            }

            // Proto gate: refuse to pair with a bridge older than we support,
            // with an explanation instead of undefined behavior later.
            val ping = try {
                candidate.ping()
            } catch (e: Exception) {
                _state.value = ConnectionState.PairFailed("bridge unreachable: ${e.message}")
                return@withContext null
            }
            val proto = ping.body?.optString("proto")?.toIntOrNull()
            if (!ping.ok || proto == null || proto < minProto) {
                _state.value = ConnectionState.ProtoMismatch(
                    bridgeProto = ping.body?.optString("proto")?.takeUnless { it.isEmpty() },
                    minProto = minProto,
                )
                return@withContext null
            }

            val result = try {
                candidate.pair(code, deviceName)
            } catch (e: Exception) {
                _state.value = ConnectionState.PairFailed("pair error: ${e.message}")
                return@withContext null
            }
            val body = result.body
            val newToken = body?.optString("token").takeUnless { it.isNullOrEmpty() }
            if (!result.ok || body == null || newToken == null) {
                val error = body?.optString("error") ?: ""
                _state.value = ConnectionState.PairFailed("${result.status} $error".trim())
                return@withContext null
            }

            store.saveCredentials(
                BridgeCredentials(
                    token = newToken,
                    hostIp = hostIp,
                    port = port,
                    bridgeId = body.optString("bridgeId").takeUnless { it.isEmpty() },
                ),
            )
            synchronized(lock) {
                // A stop() or a second pair() raced this one: don't commit.
                if (epoch != pairEpoch) return@withContext null
                client = candidate
                token = newToken
                stopped = false
                attempt = 0
                // Fresh pairing: request a full ring-buffer replay so events
                // pushed between pair success and the stream registering are
                // never lost (see BridgeViewModelTest for the war story).
                lastEventId = PersistedConnection.FULL_REPLAY_EVENT_ID
            }
            connect()
            body
        }

    /**
     * User disconnect/unpair: cancel the engine, clear credentials, done.
     * Deliberately not a failure path — nothing is retried afterwards and
     * zero further requests reach the bridge.
     */
    fun stop() {
        synchronized(lock) {
            teardownLocked()
            _state.value = ConnectionState.Stopped
        }
        scope.launch { store.clear() }
    }

    /**
     * Cancels all network activity WITHOUT clearing credentials — for process
     * teardown (ViewModel.onCleared), where the pairing must survive.
     */
    fun shutdown() {
        synchronized(lock) { teardownLocked() }
    }

    /** POST a session-scoped command. Null when not paired. */
    suspend fun sendCommand(sessionId: String, command: String): BridgeClient.ApiResult? =
        authedCall { c, t -> c.sendCommand(t, sessionId, command) }

    /** Answer a pending permission. Null when not paired. 404 = prompt expired. */
    suspend fun answerPermission(
        permissionId: String,
        behavior: String,
        message: String? = null,
    ): BridgeClient.ApiResult? =
        authedCall { c, t -> c.answerPermission(t, permissionId, behavior, message) }

    private suspend fun authedCall(
        block: (BridgeClient, String) -> BridgeClient.ApiResult,
    ): BridgeClient.ApiResult? = withContext(io) {
        val (c, t) = synchronized(lock) {
            val currentClient = client
            val currentToken = token
            if (stopped || currentClient == null || currentToken == null) return@withContext null
            currentClient to currentToken
        }
        val result = block(c, t)
        // A definitive 401 on any authed call means the token is dead.
        if (result.status == 401) onDefinitiveAuthFailure()
        result
    }

    private fun connect() {
        val myEpoch: Int
        val currentClient: BridgeClient
        val currentToken: String
        val currentAttempt: Int
        synchronized(lock) {
            if (stopped) return
            // Single flight: tear the previous stream down before opening a
            // new one, and bump the epoch so its late callbacks are ignored.
            eventSource?.cancel()
            eventSource = null
            epoch += 1
            myEpoch = epoch
            currentClient = client ?: return
            currentToken = token ?: return
            currentAttempt = attempt
            _state.value = ConnectionState.Connecting(currentAttempt)
        }
        val source = currentClient.openEvents(currentToken, lastEventId, listenerFor(myEpoch))
        synchronized(lock) {
            if (stopped || epoch != myEpoch) {
                source.cancel()
                return
            }
            eventSource = source
        }
    }

    private fun listenerFor(myEpoch: Int) = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            if (!isCurrent(myEpoch)) return
            synchronized(lock) { attempt = 0 }
            _state.value = ConnectionState.Connected
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (!isCurrent(myEpoch)) return
            if (!id.isNullOrEmpty()) {
                lastEventId = id
                persistCursor.tryEmit(id)
            }
            _events.tryEmit(SseEvent(id, type ?: "message", data))
        }

        override fun onClosed(eventSource: EventSource) {
            if (!isCurrent(myEpoch)) return
            scheduleReconnect("stream closed")
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            if (!isCurrent(myEpoch)) return
            if (response?.code == 401) {
                onDefinitiveAuthFailure()
            } else {
                // Timeouts, resets, refusals, airplane mode: all transient.
                scheduleReconnect("stream failure: ${t?.message ?: response?.code}")
            }
        }
    }

    private fun isCurrent(myEpoch: Int): Boolean = synchronized(lock) {
        !stopped && epoch == myEpoch
    }

    private fun scheduleReconnect(reason: String) {
        val nextAttempt: Int
        synchronized(lock) {
            if (stopped) return
            if (reconnectJob?.isActive == true) return // single flight
            attempt += 1
            nextAttempt = attempt
            val delayMs = backoff.delayMsFor(nextAttempt)
            reconnectJob = scope.launch {
                delay(delayMs)
                connect()
            }
            _state.value = ConnectionState.Reconnecting(nextAttempt, reason)
        }
    }

    private fun onDefinitiveAuthFailure() {
        synchronized(lock) {
            if (stopped) return
            teardownLocked()
            _state.value = ConnectionState.AuthExpired(
                "The bridge rejected this watch's token (401) — for example after " +
                    "its stored credentials were reset. Pair again to continue.",
            )
        }
        scope.launch { store.clear() }
    }

    private fun teardownLocked() {
        stopped = true
        epoch += 1
        reconnectJob?.cancel()
        reconnectJob = null
        eventSource?.cancel()
        eventSource = null
        client = null
        token = null
        attempt = 0
        lastEventId = PersistedConnection.FULL_REPLAY_EVENT_ID
    }

    companion object {
        /** Oldest bridge protocol this client can talk to. */
        const val MIN_PROTO_VERSION = 2
    }
}
