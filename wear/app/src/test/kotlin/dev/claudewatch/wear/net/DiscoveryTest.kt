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
import org.json.JSONObject
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
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
        discovery: BridgeDiscovery = BridgeDiscovery.NOOP,
        rediscoverAfterAttempts: Int = ConnectionEngine.DEFAULT_REDISCOVER_AFTER_ATTEMPTS,
        clientFactory: (String, Int) -> BridgeClient = { hostIp, port -> BridgeClient(hostIp, port) },
    ) = ConnectionEngine(
        store = store,
        scope = scope,
        clientFactory = clientFactory,
        backoff = BackoffPolicy(baseMs = 40, maxMs = 200, random = Random(11)),
        escalator = escalator,
        discovery = discovery,
        rediscoverAfterAttempts = rediscoverAfterAttempts,
        probePorts = probePorts,
    ).also { engine = it }

    private fun pingResponse(bridgeId: String = "b-1") =
        MockResponse().setBody("""{"proto":"3","bridgeId":"$bridgeId","machineName":"m"}""")

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
        // ACK every applied frame like the ViewModel does (issue #48): the
        // engine advances its replay cursor only on an ack, never on receipt,
        // so the relocated stream below resumes from the last SEEN + acked id.
        scope.launch {
            engine.events.collect {
                it.id?.let { id -> engine.ackApplied(id) }
                events.add(it)
            }
        }

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

    /**
     * Regression: a stop()/unpair racing a preflight that is BLOCKED inside
     * ping() — the normal path-broken case, where the ping only fails after
     * the full socket timeout — must not let the late Retry outcome re-acquire
     * the Wi-Fi hold that teardown just released. The unguarded escalate()
     * pinned the radio up until process death ("give up and unpair while
     * offline" is exactly when users tap stop).
     */
    @Test
    fun stopDuringBlockedPreflightDoesNotLeaveWifiHeld() {
        val escalator = RecordingEscalator()
        val blockPing = AtomicBoolean(false)
        val pingBlocked = CountDownLatch(1)
        val pingGate = CountDownLatch(1)
        val engine = newEngine(
            escalator = escalator,
            clientFactory = { hostIp, port ->
                object : BridgeClient(hostIp, port) {
                    override fun ping(): ApiResult {
                        if (blockPing.get()) {
                            // Model a dead route: the ping hangs (held here by
                            // the gate instead of a wall-clock socket timeout)
                            // and eventually fails as path-broken.
                            pingBlocked.countDown()
                            pingGate.await(30, TimeUnit.SECONDS)
                            throw SocketTimeoutException("simulated broken path")
                        }
                        return super.ping()
                    }
                }
            },
        )

        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        // Short-lived healthy stream (~4 s): its natural end sends the engine
        // into the reconnect preflight, which then blocks in ping() above.
        server.enqueue(sseHeld(":connected\n\n", pads = 2))

        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { engine.state.value == ConnectionState.Connected }

        // Break the path, wait for the reconnect preflight to block in ping(),
        // then stop the engine while it is blocked — teardown releases any
        // Wi-Fi hold as part of its "torn-down engines never pin the radio"
        // invariant.
        blockPing.set(true)
        assertTrue(
            "reconnect preflight should have blocked inside ping()",
            pingBlocked.await(30, TimeUnit.SECONDS),
        )
        engine.stop()
        awaitCondition { engine.state.value == ConnectionState.Stopped }

        // Now the blocked ping fails: the stale preflight's path-broken Retry
        // outcome lands on a stopped engine and must NOT escalate. (Escalation
        // fires only on the Retry outcome, so in this interleaving a correct
        // engine never escalates at all.)
        pingGate.countDown()
        Thread.sleep(1_000) // generous: let the stale connect() tail run out
        assertEquals(
            "a stopped engine must never (re-)acquire the Wi-Fi hold",
            0,
            escalator.escalations.get(),
        )
        assertEquals(ConnectionState.Stopped, engine.state.value)
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

    // -- DHCP self-heal via NSD re-discovery (issue #23) ----------------------
    //
    // These exercise the ENGINE side of the self-heal against a recording
    // BridgeDiscovery fake: the threshold gate, the pinned-bridgeId filter, the
    // foreign-bridge refusal, the legacy-null bail, and the transparent
    // host+port persistence that preserves the replay cursor. The framework
    // glue behind NsdBridgeDiscovery (real multicast/bind) is a separate HITL
    // issue and is unit-tested for its resource discipline in
    // NsdBridgeDiscoveryTest; it CANNOT be exercised here (emulator NAT drops
    // LAN multicast).

    /** Records every scan (its bridgeId filter) and returns a fixed [result].
     *  [all] is the canned discoverAll list for the Discover-pairing tests. */
    private class RecordingDiscovery(
        private val result: BridgeDiscovery.Discovered?,
        private val all: List<BridgeDiscovery.DiscoveredBridge> = emptyList(),
    ) : BridgeDiscovery {
        val filters = CopyOnWriteArrayList<String?>()
        val calls = AtomicInteger(0)
        val allCalls = AtomicInteger(0)
        override suspend fun discover(bridgeId: String?, timeoutMs: Long): BridgeDiscovery.Discovered? {
            calls.incrementAndGet()
            filters.add(bridgeId)
            return result
        }

        override suspend fun discoverAll(timeoutMs: Long): List<BridgeDiscovery.DiscoveredBridge> {
            allCalls.incrementAndGet()
            return all
        }
    }

    @Test
    fun selfHealFollowsDhcpMoveResumesAndPersists() {
        // The bridge originally lives on `server`. A DHCP lease change hands it
        // a new IP; it is now reachable only at `relocated`'s address, which the
        // scan returns for the pinned bridgeId.
        val relocated = newServer()
        relocated.enqueue(pingResponse()) // reconnect preflight after relocation
        relocated.enqueue(sseHeld("id: 6\nevent: tool-output\ndata: {\"n\":2}\n\n"))

        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        server.enqueue(sseHeld("id: 5\nevent: tool-output\ndata: {\"n\":1}\n\n", pads = 2))

        val discovery = RecordingDiscovery(
            BridgeDiscovery.Discovered("127.0.0.1", relocated.port, "b-1"),
        )
        val engine = newEngine(discovery = discovery, rediscoverAfterAttempts = 2)
        val events = CopyOnWriteArrayList<ConnectionEngine.SseEvent>()
        scope.launch {
            engine.events.collect {
                it.id?.let { id -> engine.ackApplied(id) }
                events.add(it)
            }
        }

        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { events.any { it.id == "5" } }

        // DHCP move: the stored ip goes dark (host up, bridge gone → refused).
        // After `rediscoverAfterAttempts` failed reconnects the scan fires and
        // the engine follows the bridge to its new port.
        server.shutdown()

        awaitCondition { events.any { it.id == "6" } }
        awaitCondition { engine.state.value == ConnectionState.Connected }

        // The scan was filtered by the pinned bridgeId — never a blind relocation.
        assertTrue("a scan must have run", discovery.filters.isNotEmpty())
        assertTrue("scan filtered by pinned bridgeId", discovery.filters.all { it == "b-1" })

        // The relocated bridge was pinged (preflight) then trusted with the
        // token AND the replay cursor: resume from the last acked id, no full
        // replay.
        takeRequest(from = relocated).let { assertEquals("/v1/ping", it.path) }
        takeRequest(from = relocated).let {
            assertEquals("/v1/events", it.path)
            assertEquals("Bearer tok-1", it.getHeader("Authorization"))
            // Resume from the last acked id, NOT a full replay ("0"). NOTE:
            // this header is sourced from the engine's IN-MEMORY cursor, which
            // self-heal never touches — so it proves the resume happens, but it
            // is INVARIANT to saveEndpoint vs saveCredentials and thus cannot
            // prove the PERSISTED cursor survived (a full-replay regression
            // observable only on a cold restart). That proof lives in
            // selfHealPersistsEndpointWithoutResettingTheReplayCursor below,
            // which reads store.read().lastEventId directly.
            assertEquals("5", it.getHeader("Last-Event-ID"))
        }

        // The new HOST+port is persisted via saveEndpoint — token, bridgeId and
        // the replay cursor all survive (same bridge at a new address).
        awaitCondition { runBlocking { store.read().credentials }?.port == relocated.port }
        val creds = runBlocking { store.read().credentials }!!
        assertEquals("127.0.0.1", creds.hostIp)
        assertEquals(relocated.port, creds.port)
        assertEquals("tok-1", creds.token)
        assertEquals("b-1", creds.bridgeId)
    }

    @Test
    fun selfHealPersistsEndpointWithoutResettingTheReplayCursor() {
        // The A1 gap: selfHealFollowsDhcpMoveResumesAndPersists proves the
        // resume cursor only via the reconnect's Last-Event-ID header, which is
        // sourced from the engine's IN-MEMORY lastEventId — invariant to whether
        // the self-heal persists via saveEndpoint (cursor preserved) or
        // saveCredentials (cursor reset to full-replay "0"). It also cannot read
        // the PERSISTED cursor at the end, because the relocated stream's event
        // "6" re-acks and re-advances it, masking any transient reset.
        //
        // This test closes that gap: the relocated stream delivers NO id-bearing
        // event, so nothing re-acks after the move. The ONLY thing that could
        // have touched store.lastEventId is the self-heal persist itself, and we
        // read it directly — the assertion the header-only proof cannot make.
        val relocated = newServer()
        relocated.enqueue(pingResponse())            // reconnect preflight after relocation
        relocated.enqueue(sseHeld(":connected\n\n")) // held open, NO id-bearing event → no re-ack

        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        server.enqueue(sseHeld("id: 5\nevent: tool-output\ndata: {\"n\":1}\n\n", pads = 2))

        val discovery = RecordingDiscovery(
            BridgeDiscovery.Discovered("127.0.0.1", relocated.port, "b-1"),
        )
        val engine = newEngine(discovery = discovery, rediscoverAfterAttempts = 2)
        val events = CopyOnWriteArrayList<ConnectionEngine.SseEvent>()
        scope.launch {
            engine.events.collect {
                it.id?.let { id -> engine.ackApplied(id) }
                events.add(it)
            }
        }

        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { events.any { it.id == "5" } }
        // Precondition: event 5 was applied+acked, so the PERSISTED cursor is "5".
        awaitCondition { runBlocking { store.read().lastEventId } == "5" }

        // DHCP move: stored ip goes dark, self-heal scans, follows to relocated,
        // reconnects. saveEndpoint here preserves the persisted cursor;
        // saveCredentials would reset it to "0".
        server.shutdown()
        awaitCondition { runBlocking { store.read().credentials }?.port == relocated.port }
        awaitCondition { engine.state.value == ConnectionState.Connected }

        // Nothing re-acked after the move (the relocated stream carried no id),
        // so this is the value the self-heal persist left behind. It MUST still
        // be "5": a full-replay reset ("0") would drop every event pushed while
        // the watch was stranded on the next cold start.
        assertEquals(
            "self-heal must persist host+port WITHOUT resetting the persisted replay cursor",
            "5",
            runBlocking { store.read().lastEventId },
        )
    }

    @Test
    fun selfHealDoesNotFireBeforeThreshold() {
        // Count preflight pings and record the ping count at the FIRST scan: a
        // transient blip (fewer than `rediscoverAfterAttempts` failures) must
        // never trigger the heavyweight multicast scan.
        val pathBroken = AtomicBoolean(false)
        val pingCount = AtomicInteger(0)
        val firstScanAtPingCount = AtomicInteger(-1)
        val threshold = 3
        val discovery = object : BridgeDiscovery {
            val calls = AtomicInteger(0)
            override suspend fun discover(bridgeId: String?, timeoutMs: Long): BridgeDiscovery.Discovered? {
                if (calls.getAndIncrement() == 0) firstScanAtPingCount.set(pingCount.get())
                return null
            }

            override suspend fun discoverAll(timeoutMs: Long): List<BridgeDiscovery.DiscoveredBridge> =
                emptyList()
        }
        val engine = newEngine(
            discovery = discovery,
            rediscoverAfterAttempts = threshold,
            clientFactory = { hostIp, port ->
                object : BridgeClient(hostIp, port) {
                    override fun ping(): ApiResult {
                        if (pathBroken.get()) {
                            pingCount.incrementAndGet()
                            throw SocketTimeoutException("simulated broken path")
                        }
                        return super.ping()
                    }
                }
            },
        )

        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        server.enqueue(sseHeld(":connected\n\n", pads = 2))

        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { engine.state.value == ConnectionState.Connected }

        // Break the path; the held stream ends shortly and every reconnect
        // preflight now fails. Wait for the first scan, then check how many
        // failures preceded it.
        pathBroken.set(true)
        awaitCondition { discovery.calls.get() > 0 }

        assertTrue(
            "self-heal fired too early: only ${firstScanAtPingCount.get()} failed pings before the first scan",
            firstScanAtPingCount.get() >= threshold - 1,
        )
    }

    @Test
    fun selfHealScanIsFilteredByPinnedBridgeId() {
        val discovery = RecordingDiscovery(result = null) // finds nothing
        val engine = newEngine(discovery = discovery, rediscoverAfterAttempts = 1)

        server.enqueue(pingResponse(bridgeId = "b-1"))
        server.enqueue(pairResponse())
        server.enqueue(sseHeld(":connected\n\n", pads = 2))

        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { engine.state.value == ConnectionState.Connected }

        server.shutdown() // DHCP move: stored ip dark, self-heal starts scanning
        awaitCondition { discovery.calls.get() > 0 }

        // Every scan carries the pinned bridgeId as its filter — never a blind
        // "any bridge" scan that could latch onto a stranger.
        assertTrue("a scan must have run", discovery.filters.isNotEmpty())
        assertTrue("scan must filter by pinned bridgeId", discovery.filters.all { it == "b-1" })
    }

    @Test
    fun selfHealRejectsForeignBridgeIdAndStaysPut() {
        // The scan returns an endpoint whose bridgeId does NOT match the pin (a
        // stranger squatting mDNS, or a stale advertisement of another bridge).
        val stranger = newServer() // must never be contacted by the engine
        val discovery = RecordingDiscovery(
            BridgeDiscovery.Discovered("127.0.0.1", stranger.port, "b-EVIL"),
        )
        val engine = newEngine(discovery = discovery, rediscoverAfterAttempts = 2)

        server.enqueue(pingResponse())
        server.enqueue(pairResponse())
        server.enqueue(sseHeld(":connected\n\n", pads = 2))

        assertNotNull(runBlocking { engine.pair("127.0.0.1", server.port, "123456", "wear-test") })
        awaitCondition { engine.state.value == ConnectionState.Connected }
        val originalPort = server.port

        server.shutdown() // DHCP move: self-heal fires but the foreign id is refused
        awaitCondition { discovery.calls.get() > 0 }
        Thread.sleep(500) // give any (wrongful) relocation a chance to persist

        // The stored endpoint is UNCHANGED: never relocated to the stranger,
        // and the stranger was never contacted (no token leaked, no stream).
        val creds = runBlocking { store.read().credentials }!!
        assertEquals("127.0.0.1", creds.hostIp)
        assertEquals(originalPort, creds.port)
        assertEquals("tok-1", creds.token)
        assertEquals("the stranger must never be contacted", 0, stranger.requestCount)
    }

    @Test
    fun selfHealSkippedForLegacyPairingWithoutBridgeId() {
        // A legacy pairing persisted no bridgeId. Cold-start restores it with a
        // null pin; the self-heal must never relocate a pairing it cannot
        // verify, even at the most eager threshold.
        runBlocking {
            store.saveCredentials(
                BridgeCredentials("tok-legacy", "127.0.0.1", server.port, bridgeId = null),
            )
        }
        val pathBroken = AtomicBoolean(false)
        val discovery = RecordingDiscovery(
            BridgeDiscovery.Discovered("127.0.0.1", 7999, "b-anything"),
        )
        val engine = newEngine(
            discovery = discovery,
            rediscoverAfterAttempts = 1,
            clientFactory = { hostIp, port ->
                object : BridgeClient(hostIp, port) {
                    override fun ping(): ApiResult {
                        if (pathBroken.get()) throw SocketTimeoutException("simulated broken path")
                        return super.ping()
                    }
                }
            },
        )

        server.enqueue(pingResponse()) // cold-start preflight (pin null → any bridge accepted)
        server.enqueue(sseHeld(":connected\n\n", pads = 2))

        engine.start()
        awaitCondition { engine.state.value == ConnectionState.Connected }

        pathBroken.set(true)
        awaitCondition {
            engine.state.value is ConnectionState.Reconnecting ||
                engine.state.value is ConnectionState.Connecting
        }
        // Several reconnect cycles at 40-200ms backoff. With threshold=1 a
        // PINNED bridge would have scanned many times by now — a legacy one
        // (null pin) must never scan at all.
        Thread.sleep(1_000)
        assertEquals(
            "legacy pairing (null bridgeId) must never self-heal",
            0,
            discovery.calls.get(),
        )
    }

    // -- Discover-pairing LIST + code-less pair (issue #23 follow-up) ----------
    //
    // The engine's discoverAllForPairing() wrapper and the code-less
    // pairByDiscovery() path, against MockWebServer. The NSD framework glue is
    // still HITL (emulator NAT drops multicast); the recording BridgeDiscovery
    // fake stands in for the scan exactly as the self-heal tests above use it.

    @Test
    fun discoverAllForPairingReturnsTheScannedBridges() {
        val bridges = listOf(
            BridgeDiscovery.DiscoveredBridge("Mac-Studio", "127.0.0.1", 7860, "b-1"),
            BridgeDiscovery.DiscoveredBridge("Deck", "10.0.0.9", 7861, "b-2"),
        )
        val engine = newEngine(discovery = RecordingDiscovery(result = null, all = bridges))
        val found = runBlocking { engine.discoverAllForPairing() }
        assertEquals("the wrapper returns the scan's bridges verbatim", bridges, found)
    }

    @Test
    fun pairByDiscoverySendsNoCodeAndConnectsOnSuccess() {
        server.enqueue(pingResponse())      // doPair preflight
        server.enqueue(pairResponse())      // code-less /v1/pair -> token
        server.enqueue(sseHeld(":connected\n\n"))

        val engine = newEngine()
        val outcome = runBlocking { engine.pairByDiscovery("127.0.0.1", server.port, "wear-test") }
        assertTrue(
            "a successful code-less pair yields Success",
            outcome is ConnectionEngine.PairOutcome.Success,
        )
        awaitCondition { engine.state.value == ConnectionState.Connected }

        assertEquals("/v1/ping", takeRequest().path)
        val pairRequest = takeRequest()
        assertEquals("/v1/pair", pairRequest.path)
        val body = JSONObject(pairRequest.body.readUtf8())
        // The whole point of the Discover path: the code key is genuinely OMITTED
        // (an empty-string code would still hit the bridge's code check).
        assertFalse("a code-less pair must OMIT the code key", body.has("code"))
        assertEquals("the proto gate still applies to a code-less pair", 3, body.getInt("proto"))
        assertEquals("wear-test", body.getString("deviceName"))
    }

    @Test
    fun pairByDiscoveryClosedWindowMapsToWindowClosed() {
        server.enqueue(pingResponse()) // doPair preflight succeeds...
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":"Already paired. Re-pairing requires explicit authorization on the bridge."}""",
            ),
        )

        val engine = newEngine()
        val outcome = runBlocking { engine.pairByDiscovery("127.0.0.1", server.port, "wear-test") }
        // A 403 lockout is the bridge's closed pairing window: the UI turns THIS
        // into the honest "open pairing (SIGUSR1)" hint, so it must be its own
        // outcome, never folded into a generic Failed.
        assertTrue(
            "a 403 lockout must map to WindowClosed, not a generic failure: $outcome",
            outcome is ConnectionEngine.PairOutcome.WindowClosed,
        )
        // The pairing was NOT committed — nothing persisted, credentials clean.
        assertNull(runBlocking { store.read().credentials })
    }
}
