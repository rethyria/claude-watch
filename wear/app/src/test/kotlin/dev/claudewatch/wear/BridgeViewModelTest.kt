package dev.claudewatch.wear

import dev.claudewatch.wear.data.AesGcmTokenCipher
import dev.claudewatch.wear.data.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * ViewModel-level behavior over the real ConnectionEngine + CredentialStore
 * (AES cipher on a temp file — the production Keystore key only differs in
 * where the key material lives):
 *
 *  - The pair-to-stream-open gap regression: the first connect after pairing
 *    requests a full replay (Last-Event-ID: 0) and "paired" is only reported
 *    once the stream is actually open (the go-signal the instrumented e2e
 *    gates on).
 *  - A 404 on a permission answer means the prompt expired on the bridge:
 *    the user sees "expired" and the dead Allow/Deny card is cleared.
 */
class BridgeViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var storeScope: CoroutineScope
    private lateinit var viewModel: BridgeViewModel

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = CredentialStore(
            { File(tmp.root, "conn.bin") },
            AesGcmTokenCipher { key },
            storeScope,
        )
        viewModel = BridgeViewModel(store)
    }

    @After
    fun tearDown() {
        runBlocking {
            val job = storeScope.coroutineContext[Job]!!
            job.cancel()
            job.join()
        }
        try {
            server.shutdown()
        } catch (_: Exception) {
            // A held-open SSE writer can outlive the shutdown grace period;
            // the sockets are closed either way.
        }
    }

    private fun enqueuePing() {
        server.enqueue(MockResponse().setBody("""{"proto":"2","bridgeId":"b-1","machineName":"m"}"""))
    }

    private fun awaitUi(timeoutMs: Long = 20_000, predicate: (BridgeViewModel.UiState) -> Boolean): BridgeViewModel.UiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel.state.value
            if (predicate(state)) return state
            Thread.sleep(10)
        }
        throw AssertionError("timed out; last state: ${viewModel.state.value}")
    }

    @Test
    fun firstConnectRequestsFullReplayAndPairedWaitsForStreamOpen() {
        enqueuePing()
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        // Hold the SSE response headers to widen the pair-to-stream-open
        // window, then drip the body so the stream stays open once it is.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setHeadersDelay(400, TimeUnit.MILLISECONDS)
                .throttleBody(1, 500, TimeUnit.MILLISECONDS)
                .setBody(":connected\n\n" + ":pad\n\n".repeat(20)),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")

        val pingRequest = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no /v1/ping request")
        assertEquals("/v1/ping", pingRequest.path)

        val pairRequest = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no /v1/pair request")
        assertEquals("/v1/pair", pairRequest.path)

        val eventsRequest = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no /v1/events request")
        assertEquals("/v1/events", eventsRequest.path)
        // The first connect must ask the bridge to replay its whole ring
        // buffer; anything pushed before the socket registered is in there.
        assertEquals("0", eventsRequest.getHeader("Last-Event-ID"))

        // The stream response is still held back (headers delay): the app
        // must not claim "paired" while events could still be missed.
        assertFalse(
            "status must not report paired before the stream is open",
            viewModel.state.value.status.contains("paired"),
        )

        awaitUi { it.status == "paired, stream open" }
    }

    @Test
    fun expiredPermissionAnswerShowsExpiredAndClearsThePrompt() {
        enqueuePing()
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val events = ":connected\n\n" +
            "id: 5\nevent: permission-request\ndata: {\"permissionId\":\"perm-1\",\"tool_name\":\"Bash\"}\n\n"
        // Deliver the permission prompt, then hold the stream open (2 s pads
        // keep it healthy under the default 25 s heartbeat window while
        // letting the server writer notice a closed socket at shutdown).
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(events + ":pad\n\n".repeat(50))
                .throttleBody(events.toByteArray().size.toLong(), 2, TimeUnit.SECONDS),
        )
        // The bridge no longer knows the prompt: answered elsewhere/expired.
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error":"No pending permission with that ID"}"""),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitUi { it.pendingPermission?.permissionId == "perm-1" }

        viewModel.answerPermission("allow")

        val state = awaitUi { it.decisionResult?.contains("expired") == true }
        assertNull("the dead Allow/Deny card must clear", state.pendingPermission)
        assertTrue(state.decisionResult!!.startsWith("decision:expired"))
    }
}
