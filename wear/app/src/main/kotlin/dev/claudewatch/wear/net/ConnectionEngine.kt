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

    /**
     * A bridge at the paired address answered /v1/ping with a DIFFERENT
     * bridgeId than the one pinned at pair time, and the port probe found no
     * relocated bridge on that host either. Reconnecting is refused so the
     * pinned token is never offered to a stranger's bridge (both existing
     * clients pair with the first mDNS hit and can nondeterministically talk
     * to the wrong Mac — this state is that bug's tombstone). Terminal until
     * the user re-pairs; credentials are deliberately NOT wiped, because the
     * real bridge may still exist elsewhere (e.g. DHCP handed its IP to
     * another machine running its own bridge).
     */
    data class BridgeMismatch(
        val expectedBridgeId: String?,
        val actualBridgeId: String?,
    ) : ConnectionState
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
 *
 * Discovery (issue #22): every reconnect and cold start begins with an
 * unauthenticated /v1/ping preflight whose reply must pass [BridgePing] shape
 * validation (a decoy HTTP server on the bridge's port — Gradio also defaults
 * to 7860 — is never mistaken for the bridge) and whose bridgeId must match
 * the one pinned at pair time. A refused/decoy'd known port triggers the port
 * probe ladder over [probePorts] on the same host, relocating to a changed
 * port only when the pinned bridgeId answers there. A preflight failure that
 * is NOT a refusal (timeout, unreachable) is a broken PATH, not a stopped
 * bridge: the engine escalates via [NetworkEscalator.escalate] (held Wi-Fi)
 * and releases the hold once the stream is healthy again.
 */
class ConnectionEngine(
    private val store: CredentialStore,
    private val scope: CoroutineScope,
    private val clientFactory: (hostIp: String, port: Int) -> BridgeClient =
        { hostIp, port -> BridgeClient(hostIp, port) },
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val minProto: Int = MIN_PROTO_VERSION,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val escalator: NetworkEscalator = NetworkEscalator.NOOP,
    // Issue #23: mDNS/NSD re-discovery for the DHCP self-heal. NOOP on the
    // emulator/JVM (multicast can't be exercised there); the production
    // NsdBridgeDiscovery is wired in the ViewModel singleton.
    private val discovery: BridgeDiscovery = BridgeDiscovery.NOOP,
    // Consecutive failed reconnects before a self-heal scan fires — below this a
    // DHCP move is indistinguishable from a transient blip, and NSD (multicast +
    // a process-global Wi-Fi bind) is far too heavy to run on every blip.
    private val rediscoverAfterAttempts: Int = DEFAULT_REDISCOVER_AFTER_ATTEMPTS,
    private val discoveryTimeoutMs: Long = BridgeDiscovery.DEFAULT_TIMEOUT_MS,
    private val probePorts: List<Int> = DEFAULT_PROBE_PORTS,
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

    // Discovery state: where the pairing lives and which bridge identity was
    // pinned when it was established (verified by every reconnect preflight).
    private var pairedHost: String? = null
    private var pairedPort: Int = 0
    private var pinnedBridgeId: String? = null

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
                pairedHost = credentials.hostIp
                pairedPort = credentials.port
                pinnedBridgeId = credentials.bridgeId
                stopped = false
                attempt = 0
            }
            // verifyIdentity default: a cold start must notice a replaced
            // bridge (or one that moved ports) before offering the token.
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

            // Discovery gates, in order: reachability, then response SHAPE (a
            // decoy HTTP server on the bridge's port — Gradio also defaults
            // to 7860 — must never be pair-able), then the proto min-version
            // gate with an explanation instead of undefined behavior later.
            val pingResult = try {
                candidate.ping()
            } catch (e: Exception) {
                failPair(myPairGeneration, ConnectionState.PairFailed("bridge unreachable: ${e.message}"))
                return@withContext null
            }
            val ping = BridgePing.from(pingResult)
            if (ping == null) {
                failPair(
                    myPairGeneration,
                    ConnectionState.PairFailed(
                        "no bridge at $hostIp:$port — the service there does not " +
                            "answer /v1/ping like a bridge (HTTP ${pingResult.status})",
                    ),
                )
                return@withContext null
            }
            if (ping.proto < minProto) {
                failPair(
                    myPairGeneration,
                    ConnectionState.ProtoMismatch(
                        bridgeProto = ping.proto.toString(),
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
            // Pin the bridge's identity at pair time: every future reconnect
            // verifies it, so a different bridge later squatting this address
            // is refused instead of silently trusted (the wrong-Mac bug).
            val bridgeId = body.optString("bridgeId").takeUnless { it.isEmpty() } ?: ping.bridgeId
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
                    pairedHost = hostIp
                    pairedPort = port
                    pinnedBridgeId = bridgeId
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
                            bridgeId = bridgeId,
                        ),
                    )
                }
                current
            }
            if (!committed) return@withContext null
            // The identity preflight is skipped for the connect right after a
            // successful pair: the very bridge that just answered /v1/ping
            // and issued this token IS the pinned identity.
            connect(verifyIdentity = false)
            body
        }

    /**
     * Issue #23 zero-typing: discover ANY bridge on the LAN (null bridgeId
     * filter — there is no pinned identity yet at pairing time) so the pairing
     * form can pre-fill host+port and the user enters only the code. Returns
     * null on the emulator/JVM ([BridgeDiscovery.NOOP]) or when no bridge
     * answers, in which case the form keeps its manual defaults. Best-effort:
     * any discovery failure degrades to manual entry, never an error.
     */
    suspend fun discoverForPairing(): BridgeDiscovery.Discovered? =
        withContext(io) { runCatching { discovery.discover(null, discoveryTimeoutMs) }.getOrNull() }

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
     * User-initiated stop WITHOUT the credential wipe (issue #24: the
     * foreground-service notification's Disconnect action). The three
     * teardowns, side by side:
     *
     *  - [stop] — unpair. Cancels everything AND clears the store; only a
     *    fresh pairing code brings the engine back.
     *  - [shutdown] — process teardown. Cancels everything, wipes nothing,
     *    and deliberately does NOT land in [ConnectionState.Stopped]: the
     *    process is dying, there is no resume to enable.
     *  - disconnect — THIS. Cancels everything, wipes nothing, and lands in
     *    [ConnectionState.Stopped] so a later [start] (the app reopening, a
     *    sticky service restart) resumes from the PERSISTED credentials.
     *
     * Note that [teardownLocked] resets only the IN-MEMORY replay cursor; the
     * persisted one survives untouched — which is exactly what catch-up
     * needs: the next [start] reads it back and the reconnect's
     * Last-Event-ID replays everything after the last APPLIED frame, never a
     * full replay and never a skip.
     */
    fun disconnect() {
        synchronized(lock) {
            pairGeneration += 1 // aborts any pair() still in flight
            teardownLocked()
            _state.value = ConnectionState.Stopped
        }
    }

    /**
     * Cancels all network activity WITHOUT clearing credentials — for process
     * teardown, where the pairing must survive. For a user-visible stop that
     * a later [start] can resume from, use [disconnect] instead.
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

    /**
     * Answer a pending AskUserQuestion prompt with one positional answer per
     * question. Null when not paired. 404 = prompt expired.
     */
    suspend fun answerQuestions(permissionId: String, answers: List<String>): BridgeClient.ApiResult? =
        authedCall { c, t -> c.answerQuestions(t, permissionId, answers) }

    /**
     * Spawn a fresh agent session ("claude" or "codex"). Null when not
     * paired. [cwd] is the spawn target directory (issue #56: a project root,
     * `"~"` for the bridge user's home, null = the bridge's default chain).
     */
    suspend fun spawnSession(agent: String, cwd: String? = null): BridgeClient.ApiResult? =
        authedCall { c, t -> c.spawnSession(t, agent, cwd) }

    /**
     * GET /v1/usage — the bridge-normalized plan-usage windows (issue #57).
     * Null when not paired. Authed like every /v1 call, so a definitive 401
     * here tears the dead token down the same way.
     */
    suspend fun fetchUsage(): BridgeClient.ApiResult? =
        authedCall { c, t -> c.getUsage(t) }

    /** Kill [sessionId]. Null when not paired. */
    suspend fun killSession(sessionId: String): BridgeClient.ApiResult? =
        authedCall { c, t -> c.killSession(t, sessionId) }

    /**
     * ACK an APPLIED frame back from the event collector (issue #48). This
     * engine no longer advances its replay cursor on mere receipt; only a
     * frame the reducer actually APPLIED moves the persisted + reconnect
     * cursor forward, so a reducer-REJECTED frame is replayed on the next
     * reconnect (a Rejected frame never acks — see BridgeViewModel.handleEvent).
     * Empty/keepalive ids carry nothing to resume from and are ignored.
     * Ordering is preserved by the single [persistCursor] collector.
     */
    fun ackApplied(id: String) {
        if (id.isEmpty()) return
        lastEventId = id
        persistCursor.tryEmit(id)
    }

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

    /**
     * Opens the SSE stream, preceded (unless [verifyIdentity] is false, i.e.
     * right after a successful pair) by the discovery preflight: an
     * unauthenticated /v1/ping that must pass [BridgePing] shape validation
     * and match the pinned bridgeId BEFORE the token is offered. The
     * preflight is also the bridge-down vs path-broken classifier — see
     * [preflight]. Always called from a coroutine (pair/start/reconnect
     * jobs), so the blocking probe I/O never lands on an OkHttp callback
     * thread.
     */
    private fun connect(verifyIdentity: Boolean = true) {
        val myEpoch: Int
        var currentClient: BridgeClient
        val currentToken: String
        val currentAttempt: Int
        val currentHost: String?
        val currentPort: Int
        val pinned: String?
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
            currentHost = pairedHost
            currentPort = pairedPort
            pinned = pinnedBridgeId
            _state.value = ConnectionState.Connecting(currentAttempt)
        }
        if (verifyIdentity) {
            when (val outcome = preflight(currentClient, currentHost, currentPort, pinned)) {
                is Preflight.Proceed -> Unit
                is Preflight.Relocated -> {
                    // Same bridge, new port (its old one was taken after a
                    // restart): follow it and persist the move — WITHOUT
                    // resetting the replay cursor, it is the same bridge.
                    synchronized(lock) {
                        if (stopped || epoch != myEpoch) return
                        client = outcome.client
                        pairedPort = outcome.port
                    }
                    currentClient = outcome.client
                    persistRelocatedPort(outcome.port)
                }
                is Preflight.Mismatch -> {
                    synchronized(lock) {
                        if (stopped || epoch != myEpoch) return
                        teardownLocked()
                        _state.value = ConnectionState.BridgeMismatch(
                            expectedBridgeId = pinned,
                            actualBridgeId = outcome.actualBridgeId,
                        )
                    }
                    return
                }
                is Preflight.Retry -> {
                    // Path-broken (unreachable/timeout, NOT refused): the
                    // host itself is unreachable — on Wear that usually means
                    // the BT phone proxy cannot see the LAN. Escalate to a
                    // held Wi-Fi network; released again on stream recovery.
                    // The escalation happens inside scheduleReconnect's
                    // stopped/epoch-guarded critical section: a path-broken
                    // preflight blocks in ping() for the full socket timeout,
                    // which is exactly the window a user stop()/unpair lands
                    // in, and an unguarded escalate() here would re-acquire
                    // the Wi-Fi hold AFTER teardown released it — pinning the
                    // radio up until process death.
                    scheduleReconnect(myEpoch, outcome.reason, escalatePath = outcome.pathBroken)
                    return
                }
            }
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

    private sealed interface Preflight {
        /** The pinned bridge answered at the known address: open the stream. */
        data object Proceed : Preflight

        /** The pinned bridge answered on a DIFFERENT port of the same host. */
        data class Relocated(val client: BridgeClient, val port: Int) : Preflight

        /** A different bridge answered and the probe found ours nowhere. */
        data class Mismatch(val actualBridgeId: String) : Preflight

        /** Transient: back off and try again. */
        data class Retry(val reason: String, val pathBroken: Boolean) : Preflight
    }

    /**
     * The discovery preflight, classifying what is actually at the paired
     * address before any token is sent:
     *
     *  - valid [BridgePing] + pinned bridgeId → [Preflight.Proceed]
     *  - connection refused (host up, nothing listening — bridge-down) or a
     *    decoy answering (wrong service on the bridge's port) → probe
     *    [probePorts] on the same host for the pinned bridgeId; relocate on a
     *    match, otherwise retry with backoff
     *  - valid ping but a FOREIGN bridgeId → probe for ours; when it is
     *    nowhere on that host, [Preflight.Mismatch] (refused, re-pair prompt)
     *  - any other I/O failure (timeout, unreachable) → the PATH is broken,
     *    not the bridge: retry with [Preflight.Retry.pathBroken] set so the
     *    engine holds a Wi-Fi network
     */
    private fun preflight(
        client: BridgeClient,
        host: String?,
        currentPort: Int,
        pinned: String?,
    ): Preflight {
        val result = try {
            client.ping()
        } catch (e: Exception) {
            return if (isConnectionRefused(e)) {
                probeForRelocatedBridge(host, currentPort, pinned)
                    ?: Preflight.Retry("bridge down: ${e.message}", pathBroken = false)
            } else {
                Preflight.Retry("path broken: ${e.message}", pathBroken = true)
            }
        }
        val ping = BridgePing.from(result)
            ?: return probeForRelocatedBridge(host, currentPort, pinned)
                ?: Preflight.Retry(
                    "not a bridge at the paired address (HTTP ${result.status})",
                    pathBroken = false,
                )
        if (pinned != null && ping.bridgeId != pinned) {
            return probeForRelocatedBridge(host, currentPort, pinned)
                ?: Preflight.Mismatch(ping.bridgeId)
        }
        return Preflight.Proceed
    }

    /**
     * The unicast port-probe rung of the discovery ladder: ping every
     * candidate port on the known [host] (cheap — a bridge-down
     * classification means the host answers, so dead ports refuse instantly)
     * and relocate ONLY to a responder that both passes [BridgePing] shape
     * validation and carries the pinned bridgeId. A decoy or a foreign bridge
     * on a probed port is skipped, never trusted.
     */
    private fun probeForRelocatedBridge(
        host: String?,
        currentPort: Int,
        pinned: String?,
    ): Preflight.Relocated? {
        if (host == null || pinned == null) return null
        for (candidate in probePorts) {
            if (candidate == currentPort) continue
            if (synchronized(lock) { stopped }) return null
            val probe = try {
                clientFactory(host, candidate)
            } catch (_: IllegalArgumentException) {
                continue
            }
            val ping = try {
                BridgePing.from(probe.ping())
            } catch (_: Exception) {
                null
            } ?: continue
            if (ping.bridgeId == pinned) return Preflight.Relocated(probe, candidate)
        }
        return null
    }

    /**
     * Issue #23 self-heal. A DHCP lease change hands the bridge a NEW IP; the
     * stored ip then times out (unassigned) or is squatted by a machine that
     * refuses/decoys — every reconnect fails and [attempt] climbs. Once it has
     * climbed to [rediscoverAfterAttempts] (PERSISTENT failure, not a blip),
     * re-run mDNS discovery FILTERED BY THE PINNED bridgeId and transparently
     * follow the bridge to its new address.
     *
     * Complementary to the escalator, never a fight with it: the escalator
     * fixes the TRANSPORT (holds Wi-Fi up so the LAN is reachable at all — it
     * ran synchronously at schedule time, before this call); discovery fixes
     * the ADDRESS. This runs INSIDE the single-flight reconnect job, just before
     * connect(), so a scan never overlaps itself or a live stream.
     *
     * Never relocates without a pinned identity to match ([pinnedBridgeId] null
     * for a legacy pairing bails — same invariant probeForRelocatedBridge holds
     * for port moves), and the discovered bridgeId is re-checked against the pin
     * before anything is applied. connect() below is still the authoritative
     * gate: its preflight /v1/pings the new address and verifies the bridgeId
     * BEFORE the token is ever offered, so a fooled discovery cannot leak a
     * token to a stranger.
     */
    private suspend fun maybeSelfHeal(myEpoch: Int) {
        val pinned = synchronized(lock) {
            // Still our reconnect, still paired, and the failure is persistent.
            if (stopped || epoch != myEpoch) return
            if (attempt < rediscoverAfterAttempts) return
            pinnedBridgeId ?: return // legacy pairing: never relocate blind
        }
        // Heavy, cancellable, self-releasing (see BridgeDiscovery). Runs OUTSIDE
        // the lock — it blocks on multicast for up to discoveryTimeoutMs.
        val found = try {
            discovery.discover(pinned, discoveryTimeoutMs)
        } catch (_: Exception) {
            null // discovery is best-effort; fall through to the normal backoff
        } ?: return
        // Defense in depth: the seam already filters by bridgeId, but re-verify
        // before mutating engine state — never follow a stranger.
        if (found.bridgeId != pinned) return
        val newClient = try {
            clientFactory(found.hostIp, found.port)
        } catch (_: IllegalArgumentException) {
            return // resolved a non-RFC1918 address: ignore it
        }
        val applied = synchronized(lock) {
            // A stop()/pair()/401 that raced the scan wins: discard the result.
            if (stopped || epoch != myEpoch) return
            if (pairedHost == found.hostIp && pairedPort == found.port) {
                false // discovery pointed us back at the SAME address: nothing to move
            } else {
                client = newClient
                pairedHost = found.hostIp
                pairedPort = found.port
                true
            }
        }
        if (applied) persistSelfHealedEndpoint(found.hostIp, found.port)
        // connect() runs next (same reconnect job) and its preflight verifies
        // the pinned bridgeId at the NEW address before offering the token.
    }

    /**
     * Persist a DHCP self-heal relocation. Mirrors [persistRelocatedPort]'s
     * launch/mutex/liveness discipline, but writes host AND port via the new
     * [CredentialStore.saveEndpoint] — NOT saveCredentials, which would reset
     * the replay cursor. It is the SAME bridge at a new address, so the cursor
     * MUST survive: a full replay here would drop every event pushed while the
     * watch was stranded.
     */
    private fun persistSelfHealedEndpoint(hostIp: String, port: Int) {
        scope.launch {
            persistMutex.withLock {
                // Only persist while the relocation is still the engine's live view.
                val current = synchronized(lock) {
                    !stopped && pairedHost == hostIp && pairedPort == port
                }
                if (current) store.saveEndpoint(hostIp, port)
            }
        }
    }

    /** Refused = the host answered with a reset: something is THERE, the bridge is not. */
    private fun isConnectionRefused(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is java.net.ConnectException &&
                cause.message?.contains("refused", ignoreCase = true) == true
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun persistRelocatedPort(newPort: Int) {
        scope.launch {
            persistMutex.withLock {
                // Only persist while the relocation is still the engine's
                // live view — a stop() or re-pair that raced us wins.
                val current = synchronized(lock) { !stopped && pairedPort == newPort }
                if (current) store.savePort(newPort)
            }
        }
    }

    private fun listenerFor(myEpoch: Int) = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            if (!isCurrent(myEpoch)) return
            synchronized(lock) { attempt = 0 }
            _state.value = ConnectionState.Connected
            // The stream is healthy again: drop any held Wi-Fi escalation so
            // the platform can return to the battery-friendly BT-proxy path.
            escalator.release()
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (!isCurrent(myEpoch)) return
            // Issue #48: do NOT advance/persist the replay cursor on mere
            // receipt. The collector ACKs a frame back via [ackApplied] only
            // after the reducer has APPLIED it, so a reducer-REJECTED frame is
            // replayed after a reconnect instead of being silently skipped —
            // restoring #16's replay-on-rejection guarantee across reconnects
            // (it had degraded to in-process only, because this engine cursor
            // won on reconnect regardless of what the reducer accepted).
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

    private fun scheduleReconnect(myEpoch: Int, reason: String, escalatePath: Boolean = false) {
        synchronized(lock) {
            if (stopped || epoch != myEpoch) return
            // Escalate only while the engine is still live at OUR epoch, and
            // under the same lock teardownLocked() runs its release() under —
            // so an escalation can never land after the teardown that was
            // supposed to end it (see the Preflight.Retry caller).
            if (escalatePath) escalator.escalate()
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
                // Issue #23: after a PERSISTENT stored-ip failure, re-discover
                // the pinned bridge by mDNS and follow a DHCP move BEFORE the
                // next connect(). escalate() already ran synchronously above
                // (before this delay), so the Wi-Fi radio the multicast scan
                // needs is already up — the required escalate-precedes-discovery
                // ordering. Gated + guarded inside maybeSelfHeal.
                maybeSelfHeal(myEpoch)
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
        pairedHost = null
        pairedPort = 0
        pinnedBridgeId = null
        attempt = 0
        lastEventId = PersistedConnection.FULL_REPLAY_EVENT_ID
        // A torn-down engine must not keep the Wi-Fi radio pinned up.
        escalator.release()
    }

    companion object {
        /**
         * Oldest bridge protocol this client can talk to. The bridge is
         * PROTOCOL_VERSION=3 with MIN_SUPPORTED_CLIENT_PROTO=3, so a proto-2
         * bridge can no longer serve this client — reject it at pair time with
         * an explanation instead of tolerating undefined behavior later.
         */
        const val MIN_PROTO_VERSION = 3

        /**
         * Consecutive failed reconnects before an NSD self-heal scan fires
         * (issue #23). Below this floor a DHCP move is indistinguishable from a
         * transient blip, and a full multicast scan + process Wi-Fi bind is far
         * too heavy to run on every blip. On a hit, onOpen resets attempt to 0,
         * ending the scan loop.
         */
        const val DEFAULT_REDISCOVER_AFTER_ATTEMPTS = 3

        /**
         * The bridge walks 7860..7869 at startup (7860 is Gradio's default,
         * so it is often taken) — the probe ladder walks the same range.
         * Single source of truth on the bridge side: PORT_RANGE_START/END in
         * skill/bridge/config.js.
         */
        val DEFAULT_PROBE_PORTS: List<Int> = (7860..7869).toList()
    }
}
