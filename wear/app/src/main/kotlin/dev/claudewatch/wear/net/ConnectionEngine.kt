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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Pairing was rejected or unreachable. Nothing was wiped, nothing
     * retries, and a connection that was live before the attempt is STILL
     * live underneath — the failed attempt never tears the engine down.
     */
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
    private var reconnectEpoch = 0 // last stream epoch a reconnect was scheduled for
    private var stopped = true

    // Bumped by every action that must abort an in-flight pair() before it
    // commits: a user stop()/unpair and a concurrent pair() winning the race.
    // Deliberately NOT bumped by a definitive 401 tearing the previous token
    // down — the whole point of a re-pair in flight is to replace that token,
    // so its death must not cancel the replacement. (The stream `epoch` can't
    // serve here: every reconnect of a still-live engine bumps it, and a live
    // engine now keeps running while pair() does its network I/O.)
    private var pairGeneration = 0

    // Serializes credential persistence (pair's save vs stop/401's clear) so
    // a stop() racing an in-flight pair() can never leave the wiped pairing's
    // token persisted at rest.
    private val persistMutex = Mutex()

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
     *
     * A LIVE engine keeps running until the new pairing has actually
     * succeeded: teardown happens only inside the commit critical section.
     * The bridge locks pairing after its first success, so a Pair tap while
     * connected is always rejected — tearing the stream down up front turned
     * that rejection into a dead engine (stopped, yet not in Stopped state,
     * so start() refused to resume) until process restart, with the valid
     * credentials still sitting in the store.
     */
    suspend fun pair(hostIp: String, port: Int, code: String, deviceName: String): JSONObject? =
        withContext(io) {
            val myPairGeneration = synchronized(lock) {
                _state.value = ConnectionState.Pairing
                pairGeneration
            }
            val candidate = try {
                clientFactory(hostIp, port)
            } catch (e: IllegalArgumentException) {
                failPair(myPairGeneration, ConnectionState.PairFailed(e.message ?: "invalid bridge address"))
                return@withContext null
            }

            // Proto gate: refuse to pair with a bridge older than we support,
            // with an explanation instead of undefined behavior later.
            val ping = try {
                candidate.ping()
            } catch (e: Exception) {
                failPair(myPairGeneration, ConnectionState.PairFailed("bridge unreachable: ${e.message}"))
                return@withContext null
            }
            val proto = ping.body?.optString("proto")?.toIntOrNull()
            if (!ping.ok || proto == null || proto < minProto) {
                failPair(
                    myPairGeneration,
                    ConnectionState.ProtoMismatch(
                        bridgeProto = ping.body?.optString("proto")?.takeUnless { it.isEmpty() },
                        minProto = minProto,
                    ),
                )
                return@withContext null
            }

            val result = try {
                candidate.pair(code, deviceName)
            } catch (e: Exception) {
                failPair(myPairGeneration, ConnectionState.PairFailed("pair error: ${e.message}"))
                return@withContext null
            }
            val body = result.body
            val newToken = body?.optString("token").takeUnless { it.isNullOrEmpty() }
            if (!result.ok || body == null || newToken == null) {
                val error = body?.optString("error") ?: ""
                failPair(myPairGeneration, ConnectionState.PairFailed("${result.status} $error".trim()))
                return@withContext null
            }

            // Commit + persist under the persistence mutex: the generation
            // check decides whether this pairing is still current, and the
            // same critical section writes the store, so a stop()/unpair that
            // raced the /v1/pair roundtrip can never end up with this
            // pairing's token persisted at rest (its clear() is serialized
            // either fully before the check — which then fails — or fully
            // after our save).
            val committed = persistMutex.withLock {
                val current = synchronized(lock) {
                    // A stop() or a second pair() raced this one: don't commit.
                    if (pairGeneration != myPairGeneration) return@synchronized false
                    pairGeneration += 1
                    // Only now — with the new pairing accepted — does the
                    // previous engine die.
                    teardownLocked()
                    client = candidate
                    token = newToken
                    stopped = false
                    attempt = 0
                    // Fresh pairing: request a full ring-buffer replay so events
                    // pushed between pair success and the stream registering are
                    // never lost (see BridgeViewModelTest for the war story).
                    lastEventId = PersistedConnection.FULL_REPLAY_EVENT_ID
                    true
                }
                if (current) {
                    store.saveCredentials(
                        BridgeCredentials(
                            token = newToken,
                            hostIp = hostIp,
                            port = port,
                            bridgeId = body.optString("bridgeId").takeUnless { it.isEmpty() },
                        ),
                    )
                }
                current
            }
            if (!committed) return@withContext null
            connect()
            body
        }

    /**
     * Records a failed pair attempt WITHOUT touching the engine: whatever was
     * running before the attempt (a healthy stream, a retry loop) keeps
     * running, its credentials stay persisted, and a stop() that raced the
     * attempt keeps the Stopped state it set. Only the surfaced state flags
     * the failure; the next stream transition of a live engine overwrites it.
     */
    private fun failPair(myPairGeneration: Int, failure: ConnectionState) {
        synchronized(lock) {
            if (pairGeneration != myPairGeneration) return
            _state.value = failure
        }
    }

    /**
     * User disconnect/unpair: cancel the engine, clear credentials, done.
     * Deliberately not a failure path — nothing is retried afterwards and
     * zero further requests reach the bridge.
     */
    fun stop() {
        synchronized(lock) {
            pairGeneration += 1 // aborts any pair() still in flight
            teardownLocked()
            _state.value = ConnectionState.Stopped
        }
        scope.launch { persistMutex.withLock { store.clear() } }
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
        val (c, t, myEpoch) = synchronized(lock) {
            val currentClient = client
            val currentToken = token
            if (stopped || currentClient == null || currentToken == null) return@withContext null
            Triple(currentClient, currentToken, epoch)
        }
        val result = block(c, t)
        // A definitive 401 on any authed call means the token it was sent
        // with is dead — the epoch guard ensures a delayed 401 earned by an
        // OLD token never wipes a pairing established while it was in flight.
        if (result.status == 401) onDefinitiveAuthFailure(myEpoch)
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
            scheduleReconnect(myEpoch, "stream closed")
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            if (!isCurrent(myEpoch)) return
            if (response?.code == 401) {
                onDefinitiveAuthFailure(myEpoch)
            } else {
                // Timeouts, resets, refusals, airplane mode: all transient.
                scheduleReconnect(myEpoch, "stream failure: ${t?.message ?: response?.code}")
            }
        }
    }

    private fun isCurrent(myEpoch: Int): Boolean = synchronized(lock) {
        !stopped && epoch == myEpoch
    }

    private fun scheduleReconnect(myEpoch: Int, reason: String) {
        synchronized(lock) {
            if (stopped || epoch != myEpoch) return
            // Single flight, keyed on the FAILING STREAM's epoch — never on
            // reconnectJob liveness. connect() runs inside the reconnect job,
            // and a refused connect can fail on an OkHttp thread before the
            // job finishes connect()'s tail; a liveness check would swallow
            // that failure and leave the engine permanently deaf in
            // Connecting. Each stream epoch gets exactly one reconnect.
            if (reconnectEpoch == myEpoch) return
            reconnectEpoch = myEpoch
            attempt += 1
            val nextAttempt = attempt
            val delayMs = backoff.delayMsFor(nextAttempt)
            reconnectJob = scope.launch {
                delay(delayMs)
                connect()
            }
            _state.value = ConnectionState.Reconnecting(nextAttempt, reason)
        }
    }

    /**
     * [myEpoch] pins the 401 to the pairing generation that earned it: a
     * delayed 401 for an OLD token, landing after a successful re-pair, must
     * never tear down the fresh valid pairing (the watchOS app erased a valid
     * pairing exactly this way).
     */
    private fun onDefinitiveAuthFailure(myEpoch: Int) {
        val teardownEpoch: Int
        synchronized(lock) {
            if (stopped || epoch != myEpoch) return
            teardownLocked()
            teardownEpoch = epoch
            _state.value = ConnectionState.AuthExpired(
                "The bridge rejected this watch's token (401) — for example after " +
                    "its stored credentials were reset. Pair again to continue.",
            )
        }
        // The wipe is pinned to OUR teardown's epoch: a re-pair() in flight
        // survives the dying token's 401 (see pairGeneration), and when its
        // commit wins the persistMutex first — bumping the epoch — the fresh
        // credentials it just saved must not be erased by the old token's
        // obituary. (After this teardown only a pair() commit or stop() can
        // change the epoch; stop() runs its own clear.)
        scope.launch {
            persistMutex.withLock {
                if (synchronized(lock) { epoch == teardownEpoch }) store.clear()
            }
        }
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
