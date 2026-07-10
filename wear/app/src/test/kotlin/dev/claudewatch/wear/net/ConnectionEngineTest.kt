package dev.claudewatch.wear.net

import dev.claudewatch.wear.data.AesGcmTokenCipher
import dev.claudewatch.wear.data.BridgeCredentials
import dev.claudewatch.wear.data.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlin.random.Random

/**
 * The scripted connection matrix from issue #21, against MockWebServer on
 * loopback: bridge kill, outage (airplane-mode-shaped failures), bridge
 * restart, heartbeat silence, unpair, cold-start transient failure, and the
 * definitive-401 re-onboarding path. Backoff is injected small so the matrix
 * runs fast, but timing assertions stay loose — this machine runs an emulator
 * under load, so the oracle is always the request evidence on the server,
 * never wall-clock precision.
 */
class ConnectionEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: CredentialStore
    private var engine: ConnectionEngine? = null

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = CredentialStore({ File(tmp.root, "conn.bin") }, AesGcmTokenCipher { key }, scope)
    }

    @After
    fun tearDown() {
        engine?.shutdown()
        runBlocking {
            val job = scope.coroutineContext[Job]!!
            job.cancel()
            job.join()
        }
        try {
            server.shutdown()
        } catch (_: Exception) {
            // Some tests shut the server down themselves (cold-start outage).
        }
    }

    // probePorts is empty in the lifecycle tests: the default 7860..7869
    // ladder would ping real bridges running on this machine's loopback.
    // DiscoveryTest covers the probe ladder with injected candidate ports.
    private fun newEngine(heartbeatMs: Long = BridgeClient.DEFAULT_HEARTBEAT_TIMEOUT_MS) =
        ConnectionEngine(
            store = store,
            scope = scope,
            clientFactory = { hostIp, port -> BridgeClient(hostIp, port, heartbeatTimeoutMs = heartbeatMs) },
            backoff = BackoffPolicy(baseMs = 40, maxMs = 200, random = Random(7)),
            probePorts = emptyList(),
        ).also { engine = it }

    /**
     * Engine whose client blocks inside openEvents for [openDelayMs] AFTER
     * the stream has started connecting — deterministically widening the
     * window in which a refused connect's onFailure races connect()'s tail
     * while the reconnect job that issued it is still active.
     */
    private fun newEngineWithSlowOpen(openDelayMs: Long) =
        ConnectionEngine(
            store = store,
            scope = scope,
            clientFactory = { hostIp, port ->
                object : BridgeClient(hostIp, port) {
                    override fun openEvents(
                        token: String,
                        lastEventId: String?,
                        listener: EventSourceListener,
                    ): EventSource {
                        val source = super.openEvents(token, lastEventId, listener)
                        Thread.sleep(openDelayMs)
                        return source
                    }
                }
            },
            backoff = BackoffPolicy(baseMs = 40, maxMs = 200, random = Random(7)),
            probePorts = emptyList(),
        ).also { engine = it }

    private fun pingResponse(proto: String = "2") =
        MockResponse().setBody("""{"proto":"$proto","bridgeId":"b-1","machineName":"m"}""")

    private fun pairResponse() =
        MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}""")

    /** SSE response that delivers [events] immediately then ends (bridge kill). */
    private fun sseEnding(events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events)

    /**
     * SSE response that delivers [events] immediately, then holds the socket
     * open with 2 s of dead air between padding comments — comfortably past
     * the injected sub-2 s heartbeat window when a test wants the watchdog to
     * fire, and frequent enough that the server-side writer notices a closed
     * socket promptly at shutdown. [pads] bounds the stream's natural
     * lifetime (pads × 2 s); raise it when a test must prove the CLIENT ended
     * the stream rather than the body merely running out.
     */
    private fun sseHeld(events: String, pads: Int = 50) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events + ":pad\n\n".repeat(pads))
        .throttleBody(events.toByteArray().size.toLong(), 2, TimeUnit.SECONDS)

    private fun awaitCondition(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms; state=${engine?.state?.value}")
    }

    private fun takeRequest(): RecordedRequest =
        server.takeRequest(30, TimeUnit.SECONDS) ?: throw AssertionError("expected a request")

    private fun credentialsInStore(): BridgeCredentials? = runBlocking { store.read().credentials }

    // -- The matrix: kill → outage → restart -------------------------------

    @Test
    fun matrixKillOutageRestartReconnectsWithBackoffAndReplay() {
        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        // Bridge killed: the stream delivers event 7 and then dies.
        server.enqueue(sseEnding("id: 7\nevent: tool-output\ndata: {\"n\":1}\n\n"))
        // Bridge still down / airplane mode: connects are torn down at accept.
        // These are consumed by the reconnects' discovery preflight pings.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        // Bridge back: the preflight ping succeeds (same bridgeId), then a
        // healthy stream delivering event 8. The real bridge's ids are
        // wall-clock-seeded so they stay monotonic ACROSS restarts — a
        // restarted bridge never re-issues ids below the client's resumed
        // cursor (skill/bridge/test/sse-restart-replay.test.js pins that
        // contract against the real process; a same-process mock whose ids
        // only grow cannot).
        server.enqueue(pingResponse())
        server.enqueue(sseHeld("id: 8\nevent: tool-output\ndata: {\"n\":2}\n\n"))

        val engine = newEngine()
        val events = CopyOnWriteArrayList<ConnectionEngine.SseEvent>()
        scope.launch { engine.events.collect { events.add(it) } }

        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })

        // Never silently deaf: both events arrive despite kill + outage, and
        // the engine derives Connected again once the stream is healthy.
        awaitCondition { events.any { it.id == "7" } }
        awaitCondition { events.any { it.id == "8" } }
        awaitCondition { engine.state.value == ConnectionState.Connected }

        assertEquals("/v1/ping", takeRequest().path)
        assertEquals("/v1/pair", takeRequest().path)
        // First connect after pairing: full ring-buffer replay.
        takeRequest().let {
            assertEquals("/v1/events", it.path)
            assertEquals("0", it.getHeader("Last-Event-ID"))
        }
        // Every reconnect attempt starts with a discovery preflight ping and,
        // once the bridge answers again, the stream resumes from the last
        // event actually seen (never a full replay, never skipping ahead).
        // The exact ping count is left loose: OkHttp may fold a torn-down
        // connect into its own transparent retry on a loaded machine.
        var resumed = false
        while (!resumed) {
            val request = takeRequest()
            when (request.path) {
                "/v1/ping" -> Unit // preflight against the down bridge
                "/v1/events" -> {
                    assertEquals("7", request.getHeader("Last-Event-ID"))
                    resumed = true
                }
                else -> throw AssertionError("unexpected request ${request.path}")
            }
        }
        // Transient failures never wiped the pairing.
        assertNotNull(credentialsInStore())
    }

    // -- Connect-tail race --------------------------------------------------

    @Test
    fun failureWhileReconnectJobStillInConnectTailIsNotSwallowed() {
        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        // Three consecutive refused connects, each failing while connect()
        // is still blocked inside openEvents — i.e. while the reconnect job
        // that issued it is still active. A single-flight guard keyed on job
        // liveness (instead of stream epoch) swallows these callbacks and
        // leaves the engine permanently deaf in Connecting. Each reconnect's
        // discovery preflight ping gets a healthy answer (interleaved below)
        // so every torn-down response is consumed by openEvents itself.
        repeat(3) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(pingResponse())
        }
        server.enqueue(sseHeld(":connected\n\n"))

        val engine = newEngineWithSlowOpen(openDelayMs = 500)
        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })

        // Every failure must schedule the next attempt: the engine reaches
        // the healthy stream instead of stalling with nothing left to retry.
        awaitCondition { engine.state.value == ConnectionState.Connected }
        assertNotNull(credentialsInStore())
    }

    // -- Heartbeat watchdog -------------------------------------------------

    @Test
    fun heartbeatSilenceTriggersReconnect() {
        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        // Delivers one event, then dead air with the socket held open — the
        // exact failure the iOS run-loop-less timers never caught. 600 pads
        // × 2 s ≈ 20 min of natural stream lifetime: only the watchdog can
        // end this stream within the bounded wait below, so the test fails
        // if the watchdog is removed instead of passing on the body's end.
        server.enqueue(sseHeld("id: 3\nevent: tool-output\ndata: {}\n\n", pads = 600))
        server.enqueue(pingResponse()) // the reconnect's discovery preflight
        server.enqueue(sseHeld("id: 4\nevent: tool-output\ndata: {}\n\n", pads = 600))

        val engine = newEngine(heartbeatMs = 1_500)
        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })

        assertEquals("/v1/ping", takeRequest().path)
        assertEquals("/v1/pair", takeRequest().path)
        assertEquals("0", takeRequest().getHeader("Last-Event-ID"))

        // Silence beyond the heartbeat window must surface as a reconnect —
        // first the discovery preflight ping, then the stream reopening from
        // the event the silent stream had delivered. The waits are bounded
        // WELL under the stream's ~20 min natural end but stay generous
        // against the 1.5 s injected window (loaded machine).
        val preflight = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("heartbeat watchdog did not trigger a reconnect within 10 s")
        assertEquals("/v1/ping", preflight.path)
        val reconnect = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no stream reopen after the preflight ping")
        assertEquals("/v1/events", reconnect.path)
        assertEquals("3", reconnect.getHeader("Last-Event-ID"))
        awaitCondition { engine.state.value == ConnectionState.Connected }

        // Production watchdog window: two missed 10 s bridge heartbeats.
        assertEquals(25_000L, BridgeClient.DEFAULT_HEARTBEAT_TIMEOUT_MS)
    }

    // -- Unpair / STOPPED ---------------------------------------------------

    @Test
    fun unpairStopsEngineClearsCredentialsAndSendsNothing() {
        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        server.enqueue(sseHeld(":connected\n\n"))

        val engine = newEngine()
        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { engine.state.value == ConnectionState.Connected }
        awaitCondition { credentialsInStore() != null }

        val requestsBeforeStop = server.requestCount
        // If anything did leak a request after stop, give it a response to
        // land on so it would be counted rather than hang.
        repeat(4) { server.enqueue(MockResponse().setBody("{}")) }

        engine.stop()
        awaitCondition { engine.state.value == ConnectionState.Stopped }
        awaitCondition { credentialsInStore() == null }

        // Generous window on a loaded machine: many injected-backoff periods.
        Thread.sleep(1_500)
        assertEquals(
            "zero further requests may reach the bridge after unpair",
            requestsBeforeStop,
            server.requestCount,
        )
        // User cancellation is STOPPED, never a failure to retry.
        assertEquals(ConnectionState.Stopped, engine.state.value)
        // Authed calls refuse locally instead of hitting the network.
        assertNull(runBlocking { engine.sendCommand("s-1", "hello") })
        assertEquals(requestsBeforeStop, server.requestCount)
    }

    @Test
    fun unpairDuringInFlightPairNeverPersistsItsCredentials() {
        server.enqueue(pingResponse())
        // The pair response is held for 5 s so stop() deterministically lands
        // while the /v1/pair roundtrip is still in flight.
        server.enqueue(pairResponse().setBodyDelay(5, TimeUnit.SECONDS))

        val engine = newEngine()
        val pending = scope.async { engine.pair("127.0.0.1", server.port, "123456", "wear-test") }
        assertEquals("/v1/ping", takeRequest().path)
        assertEquals("/v1/pair", takeRequest().path)

        // The user unpairs while the pair roundtrip is in flight.
        engine.stop()
        awaitCondition { engine.state.value == ConnectionState.Stopped }

        // The racing pair() must observe it lost: no commit, no persistence —
        // otherwise the next cold start silently resumes the wiped pairing.
        assertNull(runBlocking { pending.await() })
        Thread.sleep(500) // let any stray persistence land before asserting
        assertNull(
            "unpair must not leave the racing pair()'s credentials persisted",
            credentialsInStore(),
        )
        assertEquals(ConnectionState.Stopped, engine.state.value)
    }

    // -- Cold start ---------------------------------------------------------

    @Test
    fun transientColdStartFailureKeepsCredentialsAndRetries() {
        val deadPort = server.port
        server.shutdown() // bridge is down when the app wakes up
        runBlocking {
            store.saveCredentials(BridgeCredentials("tok-1", "127.0.0.1", deadPort, "b-1"))
        }

        val engine = newEngine()
        engine.start()

        // The engine lands in the ordinary retry loop...
        awaitCondition { engine.state.value is ConnectionState.Reconnecting }
        // ...and after several more failed attempts the pairing still exists —
        // the watchOS bug (wipe on one failed launch probe) must not recur.
        Thread.sleep(800)
        assertNotNull("transient failure must never erase the pairing", credentialsInStore())
        assertFalse(engine.state.value is ConnectionState.AuthExpired)
        assertFalse(engine.state.value is ConnectionState.Stopped)
    }

    // -- Definitive 401 -----------------------------------------------------

    @Test
    fun definitive401WipesCredentialsAndStopsRetrying() {
        runBlocking {
            store.saveCredentials(BridgeCredentials("tok-stale", "127.0.0.1", server.port, "b-1"))
        }
        // Cold start: the discovery preflight ping confirms the SAME bridge
        // (pinned bridgeId) is answering — so the 401 that follows is a
        // definitive verdict on the token, not a stranger rejecting it.
        server.enqueue(pingResponse())
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))
        }

        val engine = newEngine()
        engine.start()

        awaitCondition { engine.state.value is ConnectionState.AuthExpired }
        awaitCondition { credentialsInStore() == null }
        Thread.sleep(1_000)
        assertEquals("a definitive 401 is re-onboarding, not a retry loop", 2, server.requestCount)
    }

    @Test
    fun stale401ForOldTokenAfterRepairDoesNotWipeFreshPairing() {
        server.enqueue(pingResponse())
        server.enqueue(pairResponse()) // tok-1
        server.enqueue(sseHeld(":connected\n\n"))
        // A command sent with the OLD token earns a definitive 401 — but the
        // response is delayed so it lands only after the re-pair below.
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"Unauthorized"}""")
                .setHeadersDelay(5, TimeUnit.SECONDS),
        )
        server.enqueue(pingResponse())
        server.enqueue(MockResponse().setBody("""{"token":"tok-new","bridgeId":"b-1","sessions":[]}"""))
        server.enqueue(sseHeld(":connected\n\n"))

        val engine = newEngine()
        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { engine.state.value == ConnectionState.Connected }
        assertEquals("/v1/ping", takeRequest().path)
        assertEquals("/v1/pair", takeRequest().path)
        assertEquals("/v1/events", takeRequest().path)

        val staleCall = scope.async { engine.sendCommand("s-1", "hello") }
        // Ensure the command reached the server (now waiting out the delayed
        // 401) before re-pairing.
        assertEquals("/v1/command", takeRequest().path)

        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "654321", "wear-test") })
        awaitCondition { engine.state.value == ConnectionState.Connected }
        awaitCondition { credentialsInStore()?.token == "tok-new" }

        // The stale 401 lands: its caller still sees the status, but it must
        // never tear down the pairing established while it was in flight.
        assertEquals(401, runBlocking { staleCall.await() }?.status)
        Thread.sleep(500) // window for any wrongful teardown to surface
        assertEquals(
            "a stale 401 for the old token must not wipe the fresh pairing",
            ConnectionState.Connected,
            engine.state.value,
        )
        assertEquals("tok-new", credentialsInStore()?.token)
    }

    // -- Re-pair attempt against a healthy connection -----------------------

    @Test
    fun failedRepairAttemptDoesNotKillHealthyConnection() {
        server.enqueue(pingResponse())
        server.enqueue(pairResponse()) // tok-1
        server.enqueue(sseHeld("id: 7\nevent: tool-output\ndata: {\"n\":1}\n\n"))
        // The accidental re-pair attempt: the bridge locks pairing after the
        // first success, so a Pair tap while connected is ping-OK, then 403.
        server.enqueue(pingResponse())
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Already paired"}"""))
        // A command sent after the failed attempt must still reach the bridge.
        server.enqueue(MockResponse().setBody("{}"))

        val engine = newEngine()
        val events = CopyOnWriteArrayList<ConnectionEngine.SseEvent>()
        scope.launch { engine.events.collect { events.add(it) } }

        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { engine.state.value == ConnectionState.Connected }
        awaitCondition { events.any { it.id == "7" } }

        // One accidental Pair tap while connected. It fails (pairing locked)…
        assertNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        assertTrue(engine.state.value is ConnectionState.PairFailed)

        // …and a re-pair attempt against an unreachable bridge fails too.
        val dead = MockWebServer().also { it.start() }
        val deadPort = dead.port
        dead.shutdown()
        assertNull(runBlocking { engine.pair("127.0.0.1", deadPort, "123456", "wear-test") })
        assertTrue(engine.state.value is ConnectionState.PairFailed)

        // Neither failed attempt may have torn the live engine down: the
        // previous pairing's credentials are still persisted, and authed
        // calls still reach the bridge with the surviving token — the engine
        // used to be dead here (stopped, yet refusing start()) until process
        // restart.
        assertEquals("tok-1", credentialsInStore()?.token)
        val result = runBlocking { engine.sendCommand("s-1", "hello") }
        assertEquals(200, result?.status)

        assertEquals("/v1/ping", takeRequest().path)
        assertEquals("/v1/pair", takeRequest().path)
        assertEquals("/v1/events", takeRequest().path)
        assertEquals("/v1/ping", takeRequest().path)
        assertEquals("/v1/pair", takeRequest().path)
        takeRequest().let {
            assertEquals("/v1/command", it.path)
            assertEquals("Bearer tok-1", it.getHeader("Authorization"))
        }
        // No further /v1/events connect: the ORIGINAL stream was never torn
        // down, so the failed attempts scheduled no reconnect.
        assertEquals(6, server.requestCount)
    }

    // -- Pairing gates ------------------------------------------------------

    @Test
    fun protoMismatchBlocksPairingWithExplanation() {
        server.enqueue(pingResponse(proto = "1"))

        val engine = newEngine()
        assertNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })

        val state = engine.state.value
        assertTrue("expected ProtoMismatch, got $state", state is ConnectionState.ProtoMismatch)
        state as ConnectionState.ProtoMismatch
        assertEquals("1", state.bridgeProto)
        assertEquals(ConnectionEngine.MIN_PROTO_VERSION, state.minProto)
        assertEquals("nothing past the ping probe may be sent", 1, server.requestCount)
    }

    @Test
    fun pairRejectionNeitherWipesExistingCredentialsNorRetries() {
        runBlocking {
            store.saveCredentials(BridgeCredentials("tok-old", "127.0.0.1", server.port, "b-1"))
        }
        server.enqueue(pingResponse())
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid pairing code"}"""))

        val engine = newEngine()
        assertNull(runBlocking { engine.pair("127.0.0.1", server.port, "000000", "wear-test") })

        assertTrue(engine.state.value is ConnectionState.PairFailed)
        Thread.sleep(500)
        assertEquals("a rejected code is not retried", 2, server.requestCount)
        assertEquals("tok-old", credentialsInStore()!!.token)
    }
}
