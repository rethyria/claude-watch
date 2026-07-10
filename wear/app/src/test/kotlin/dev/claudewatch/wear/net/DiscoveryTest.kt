package dev.claudewatch.wear.net

import dev.claudewatch.wear.data.AesGcmTokenCipher
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlin.random.Random

/**
 * The emulator-verifiable rungs of the discovery ladder (issue #22), against
 * MockWebServer on loopback:
 *
 *  - the unicast /v1/ping port probe finds the bridge at a CHANGED port on a
 *    known host, while [BridgePing] shape validation rejects a decoy HTTP
 *    server (7860 is also Gradio's default port) — the decoy never sees a
 *    token;
 *  - a host whose bridgeId changed since pair time is refused with a clear
 *    re-pair prompt, and the (possibly still valid elsewhere) credentials are
 *    NOT wiped;
 *  - a bridge-down (refused) preflight is classified differently from a
 *    broken PATH (timeout/unreachable): only the latter escalates to a held
 *    Wi-Fi network, released again once the stream recovers;
 *  - manual IP entry pairs successfully on the very first run.
 *
 * Timing assertions stay loose on purpose — this machine runs an emulator
 * under load, so the oracle is request/credential evidence, never wall-clock
 * precision. mDNS/NSD zero-typing discovery is a separate HITL issue (the
 * emulator's NAT drops LAN multicast).
 */
class DiscoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: CredentialStore
    private var engine: ConnectionEngine? = null
    private val extraServers = CopyOnWriteArrayList<MockWebServer>()

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
        (extraServers + server).forEach {
            try {
                it.shutdown()
            } catch (_: Exception) {
                // Some tests shut a server down themselves (bridge killed).
            }
        }
    }

    private fun newServer(): MockWebServer =
        MockWebServer().also {
            it.start()
            extraServers.add(it)
        }

    private fun newEngine(
        probePorts: List<Int> = emptyList(),
        escalator: NetworkEscalator = NetworkEscalator.NOOP,
        clientFactory: (String, Int) -> BridgeClient = { hostIp, port -> BridgeClient(hostIp, port) },
    ) = ConnectionEngine(
        store = store,
        scope = scope,
        clientFactory = clientFactory,
        backoff = BackoffPolicy(baseMs = 40, maxMs = 200, random = Random(11)),
        escalator = escalator,
        probePorts = probePorts,
    ).also { engine = it }

    private fun pingResponse(bridgeId: String = "b-1") =
        MockResponse().setBody("""{"proto":"2","bridgeId":"$bridgeId","machineName":"m"}""")

    private fun pairResponse() =
        MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}""")

    /** What a decoy web app (Gradio et al.) answers on the bridge's port. */
    private fun decoyResponse() = MockResponse()
        .setHeader("Content-Type", "text/html")
        .setBody("<!doctype html><html><body>Some other web app</body></html>")

    /**
     * SSE response that delivers [events] then holds the socket open with 2 s
     * of dead air between padding comments; [pads] bounds its natural
     * lifetime at pads x 2 s.
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

    private fun takeRequest(from: MockWebServer = server): RecordedRequest =
        from.takeRequest(30, TimeUnit.SECONDS) ?: throw AssertionError("expected a request")

    // -- Shape validation (the decoy filter itself) --------------------------

    @Test
    fun pingShapeValidationAcceptsOnlyTheBridgeContract() {
        fun result(status: Int, json: String?) =
            BridgeClient.ApiResult(status, json?.let { JSONObject(it) })

        // The real bridge's reply (skill/bridge: {proto, bridgeId, machineName}).
        val valid = BridgePing.from(
            result(200, """{"proto":"2","bridgeId":"b-1","machineName":"mac"}"""),
        )
        assertNotNull(valid)
        assertEquals(2, valid!!.proto)
        assertEquals("b-1", valid.bridgeId)

        // Decoys: HTML (parses to no JSON body), foreign JSON APIs, partial
        // shapes, junk proto values, non-2xx — all rejected.
        assertNull("HTML body is not a bridge", BridgePing.from(result(200, null)))
        assertNull("foreign JSON is not a bridge", BridgePing.from(result(200, """{"version":"4.44.0"}""")))
        assertNull("missing bridgeId", BridgePing.from(result(200, """{"proto":"2","machineName":"m"}""")))
        assertNull("missing machineName", BridgePing.from(result(200, """{"proto":"2","bridgeId":"b"}""")))
        assertNull("non-numeric proto", BridgePing.from(result(200, """{"proto":"latest","bridgeId":"b","machineName":"m"}""")))
        assertNull("non-positive proto", BridgePing.from(result(200, """{"proto":"0","bridgeId":"b","machineName":"m"}""")))
        assertNull("error status", BridgePing.from(result(500, """{"proto":"2","bridgeId":"b","machineName":"m"}""")))
    }

    // -- Port probe + decoy rejection ----------------------------------------

    @Test
    fun probeFindsBridgeAtChangedPortAndNeverTrustsTheDecoy() {
        // The bridge originally lives on `server`. After a restart a decoy
        // squats near its old port and the bridge itself moved to relocated's
        // port (exactly what happens when Gradio grabs 7860).
        val decoy = newServer()
        val relocated = newServer()
        repeat(8) { decoy.enqueue(decoyResponse()) }
        relocated.enqueue(pingResponse()) // probe hit: same pinned bridgeId
        relocated.enqueue(sseHeld("id: 6\nevent: tool-output\ndata: {\"n\":2}\n\n"))

        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        server.enqueue(sseHeld("id: 5\nevent: tool-output\ndata: {\"n\":1}\n\n"))

        val engine = newEngine(probePorts = listOf(decoy.port, relocated.port))
        val events = CopyOnWriteArrayList<ConnectionEngine.SseEvent>()
        scope.launch { engine.events.collect { events.add(it) } }

        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { events.any { it.id == "5" } }

        // Kill the original bridge: its port now refuses connections.
        server.shutdown()

        // The probe ladder must find the bridge at its new port and resume
        // the stream from the last seen event — no full replay, no re-pair.
        awaitCondition { events.any { it.id == "6" } }
        awaitCondition { engine.state.value == ConnectionState.Connected }

        // The relocated port was probed unauthenticated first, then trusted
        // with the token and the replay cursor.
        takeRequest(from = relocated).let {
            assertEquals("/v1/ping", it.path)
            assertNull("probes are unauthenticated", it.getHeader("Authorization"))
        }
        takeRequest(from = relocated).let {
            assertEquals("/v1/events", it.path)
            assertEquals("Bearer tok-1", it.getHeader("Authorization"))
            assertEquals("5", it.getHeader("Last-Event-ID"))
        }

        // The decoy saw only unauthenticated pings: never a token, never a
        // stream, no matter how convincingly it answered HTTP 200.
        assertTrue("the decoy should have been probed", decoy.requestCount > 0)
        repeat(decoy.requestCount) {
            val request = takeRequest(from = decoy)
            assertEquals("/v1/ping", request.path)
            assertNull("the decoy must never see a token", request.getHeader("Authorization"))
        }

        // The relocation is persisted (same token, same bridge, new port) so
        // the next cold start goes straight to the right place.
        awaitCondition { runBlocking { store.read().credentials }?.port == relocated.port }
        val credentials = runBlocking { store.read().credentials }!!
        assertEquals("tok-1", credentials.token)
        assertEquals("b-1", credentials.bridgeId)
    }

    // -- bridgeId pinning -----------------------------------------------------

    @Test
    fun reconnectToChangedBridgeIdIsRefusedWithRepairPrompt() {
        server.enqueue(pingResponse(bridgeId = "b-1"))
        server.enqueue(pairResponse())
        server.enqueue(sseHeld("id: 2\nevent: tool-output\ndata: {}\n\n", pads = 2))
        // The stream above ends naturally (~4 s); by then the machine behind
        // the paired address is a DIFFERENT bridge (wrong Mac, reinstalled
        // bridge, DHCP reshuffle...). Extra copies in case of a second look.
        repeat(3) { server.enqueue(pingResponse(bridgeId = "b-2")) }

        val engine = newEngine()
        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { engine.state.value == ConnectionState.Connected }

        // The reconnect preflight sees the foreign bridgeId and refuses.
        awaitCondition { engine.state.value is ConnectionState.BridgeMismatch }
        val state = engine.state.value as ConnectionState.BridgeMismatch
        assertEquals("b-1", state.expectedBridgeId)
        assertEquals("b-2", state.actualBridgeId)

        // Refused means REFUSED: the pinned token was never offered to the
        // stranger. Requests so far: ping, pair, events, preflight ping.
        val requestsAtMismatch = server.requestCount
        assertEquals("/v1/ping", takeRequest().path)
        assertEquals("/v1/pair", takeRequest().path)
        assertEquals("/v1/events", takeRequest().path)
        assertEquals("/v1/ping", takeRequest().path)
        assertEquals(4, requestsAtMismatch)

        // Terminal until re-pair: no retry hammering the stranger...
        Thread.sleep(1_000)
        assertEquals(requestsAtMismatch, server.requestCount)
        // ...and the credentials are NOT wiped — the real bridge may still
        // exist elsewhere; only the user's re-pair (or a definitive 401 from
        // the SAME bridge) may erase a pairing.
        val credentials = runBlocking { store.read().credentials }
        assertNotNull("a foreign bridge must not wipe the pairing", credentials)
        assertEquals("tok-1", credentials!!.token)
    }

    // -- Path-broken classification + held-Wi-Fi escalation -------------------

    private class RecordingEscalator : NetworkEscalator {
        val escalations = AtomicInteger(0)
        val releases = AtomicInteger(0)
        override fun escalate() {
            escalations.incrementAndGet()
        }

        override fun release() {
            releases.incrementAndGet()
        }
    }

    @Test
    fun pathBrokenEscalatesToHeldWifiAndReleasesOnRecovery() {
        // Simulated path failure: while the flag is up, /v1/ping fails the
        // way a dead route fails (timeout) — NOT the way a dead bridge fails
        // (refused). On the watch this is the BT-proxy-cannot-see-the-LAN
        // case, which no amount of retrying over the same path fixes.
        val pathBroken = AtomicBoolean(false)
        val escalator = RecordingEscalator()
        val engine = newEngine(
            escalator = escalator,
            clientFactory = { hostIp, port ->
                object : BridgeClient(hostIp, port) {
                    override fun ping(): ApiResult {
                        if (pathBroken.get()) throw SocketTimeoutException("simulated broken path")
                        return super.ping()
                    }
                }
            },
        )

        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        // A short-lived healthy stream (~6 s), so the path can "break" while
        // it is still up and the failure lands on the reconnect preflight.
        server.enqueue(sseHeld(":connected\n\n", pads = 3))
        // Recovery responses, consumed only once the path is healed (a
        // broken-path ping throws client-side and dequeues nothing).
        server.enqueue(pingResponse())
        server.enqueue(sseHeld(":recovered\n\n"))

        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { engine.state.value == ConnectionState.Connected }
        assertEquals("no escalation while the path is healthy", 0, escalator.escalations.get())

        // Break the path; the held stream ends shortly after, and the
        // reconnect preflight must classify + escalate instead of just
        // burning retries down the dead route.
        pathBroken.set(true)
        awaitCondition { escalator.escalations.get() > 0 }
        assertTrue(
            "path-broken must keep retrying (with Wi-Fi held), not give up: ${engine.state.value}",
            engine.state.value is ConnectionState.Reconnecting ||
                engine.state.value is ConnectionState.Connecting,
        )
        val releasesWhileBroken = escalator.releases.get()

        // Path recovers: the engine reconnects and drops the Wi-Fi hold so
        // the platform can return to the battery-friendly BT-proxy path.
        pathBroken.set(false)
        awaitCondition { engine.state.value == ConnectionState.Connected }
        awaitCondition { escalator.releases.get() > releasesWhileBroken }

        // Transient path failures never touch the pairing.
        assertNotNull(runBlocking { store.read().credentials })
    }

    // -- Manual IP entry ------------------------------------------------------

    @Test
    fun manualIpEntryPairsSuccessfullyOnFirstRun() {
        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        server.enqueue(sseHeld(":connected\n\n"))

        // First run: nothing persisted, so start() must stay quietly Stopped
        // (no probing, no phantom reconnects) until the user types an address.
        val engine = newEngine()
        engine.start()
        Thread.sleep(300)
        assertEquals(ConnectionState.Stopped, engine.state.value)
        assertEquals("no requests before the user enters an address", 0, server.requestCount)

        // The user types host + port + code — the first-class path, no prior
        // discovery required.
        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { engine.state.value == ConnectionState.Connected }

        // The pairing persisted everything reconnects and pinning need.
        awaitCondition { runBlocking { store.read().credentials } != null }
        val credentials = runBlocking { store.read().credentials }!!
        assertEquals("127.0.0.1", credentials.hostIp)
        assertEquals(server.port, credentials.port)
        assertEquals("tok-1", credentials.token)
        assertEquals("bridgeId is pinned at pair time", "b-1", credentials.bridgeId)
    }
}
