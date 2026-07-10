package dev.claudewatch.wear.net

import dev.claudewatch.wear.data.AesGcmTokenCipher
import dev.claudewatch.wear.data.BridgeCredentials
import dev.claudewatch.wear.data.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    private fun newEngine(heartbeatMs: Long = BridgeClient.DEFAULT_HEARTBEAT_TIMEOUT_MS) =
        ConnectionEngine(
            store = store,
            scope = scope,
            clientFactory = { hostIp, port -> BridgeClient(hostIp, port, heartbeatTimeoutMs = heartbeatMs) },
            backoff = BackoffPolicy(baseMs = 40, maxMs = 200, random = Random(7)),
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
     * socket promptly at shutdown.
     */
    private fun sseHeld(events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events + ":pad\n\n".repeat(50))
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
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        // Bridge back: a healthy stream delivering event 8.
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
        // Every reconnect resumes from the last event actually seen.
        repeat(3) {
            takeRequest().let {
                assertEquals("/v1/events", it.path)
                assertEquals("7", it.getHeader("Last-Event-ID"))
            }
        }
        // Transient failures never wiped the pairing.
        assertNotNull(credentialsInStore())
    }

    // -- Heartbeat watchdog -------------------------------------------------

    @Test
    fun heartbeatSilenceTriggersReconnect() {
        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        // Delivers one event, then dead air with the socket held open — the
        // exact failure the iOS run-loop-less timers never caught.
        server.enqueue(sseHeld("id: 3\nevent: tool-output\ndata: {}\n\n"))
        server.enqueue(sseHeld("id: 4\nevent: tool-output\ndata: {}\n\n"))

        val engine = newEngine(heartbeatMs = 1_500)
        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })

        assertEquals("/v1/ping", takeRequest().path)
        assertEquals("/v1/pair", takeRequest().path)
        assertEquals("0", takeRequest().getHeader("Last-Event-ID"))

        // Silence beyond the heartbeat window must surface as a reconnect,
        // resuming from the event the silent stream had delivered.
        takeRequest().let {
            assertEquals("/v1/events", it.path)
            assertEquals("3", it.getHeader("Last-Event-ID"))
        }
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
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))
        }

        val engine = newEngine()
        engine.start()

        awaitCondition { engine.state.value is ConnectionState.AuthExpired }
        awaitCondition { credentialsInStore() == null }
        Thread.sleep(1_000)
        assertEquals("a definitive 401 is re-onboarding, not a retry loop", 1, server.requestCount)
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
