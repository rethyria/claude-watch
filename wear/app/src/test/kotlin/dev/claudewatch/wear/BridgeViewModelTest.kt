package dev.claudewatch.wear

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for the pair-to-stream-open gap: an SSE event pushed by
 * the bridge after /v1/pair succeeds but before GET /v1/events is actually
 * connected used to be lost forever — the first connect sent no Last-Event-ID
 * (so no ring-buffer replay), and the bridge's connect-time sync event reuses
 * the newest buffered id, masking the miss from later reconnects. The fix is
 * two-sided: the first connect requests a full replay (Last-Event-ID: 0), and
 * "paired" is only reported once the stream is open (onOpen), which is the
 * go-signal the instrumented e2e gates on.
 */
class BridgeViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: BridgeViewModel

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        viewModel = BridgeViewModel()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun awaitStatus(timeoutMs: Long = 15_000, predicate: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = viewModel.state.value.status
            if (predicate(status)) return status
            Thread.sleep(10)
        }
        throw AssertionError("timed out; last status: ${viewModel.state.value.status}")
    }

    @Test
    fun firstConnectRequestsFullReplayAndPairedWaitsForStreamOpen() {
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

        awaitStatus { it == "paired, stream open" }
    }
}
