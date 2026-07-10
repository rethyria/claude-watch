package dev.claudewatch.wear

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun awaitState(
        timeoutMs: Long = 30_000,
        predicate: (BridgeViewModel.UiState) -> Boolean,
    ): BridgeViewModel.UiState {
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

    /**
     * The reducer wiring: real /v1 event frames arriving over SSE surface as
     * typed state — session id from the `session` event, log lines rendered
     * from typed models (not raw "$type $data" appending), pending permission
     * from `permission-request`, and lastEventId committed per applied frame.
     */
    @Test
    fun sseEventsFlowThroughTheSharedReducerIntoUiState() {
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append("id: 1\nevent: session\n")
            append("""data: {"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj","sessionId":"s-1"}""")
            append("\n\n")
            append("id: 2\nevent: tool-output\n")
            append("""data: {"tool_name":"Read","tool_input":{"file_path":"/tmp/proj/README.md"},"tool_output":"marker-xyz","cwd":"/tmp/proj","source":"claude","sessionId":"s-1"}""")
            append("\n\n")
            append("id: 3\nevent: permission-request\n")
            append(
                """data: {"permissionId":"perm-1","tool_name":"Bash","tool_input":{"command":"ls"},""" +
                    """"options":[{"behavior":"allow","label":"Yes","description":"Allow this once"},""" +
                    """{"behavior":"deny","label":"No","description":"Deny this request"}],"sessionId":"s-1"}""",
            )
            append("\n\n")
            // Keep the stream open while the assertions below run.
            append(":pad\n\n".repeat(40))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")

        val state = awaitState { it.pendingPermission != null }
        assertEquals("perm-1", state.pendingPermission?.permissionId)
        assertEquals("Bash", state.pendingPermission?.toolName)
        assertEquals("s-1", state.sessionId)
        assertEquals("3", state.bridge.lastEventId)
        assertEquals("s-1", state.bridge.currentSessionId)
        val log = state.eventLog.joinToString("\n")
        assertTrue("typed tool-output line must carry the payload text: $log", log.contains("marker-xyz"))
        assertTrue("session line must be rendered from the typed model: $log", log.contains("session running proj"))
    }

    private fun permissionRequestFrame(id: Int, permissionId: String, toolName: String): String =
        "id: $id\nevent: permission-request\n" +
            """data: {"permissionId":"$permissionId","tool_name":"$toolName","tool_input":{},""" +
            """"options":[{"behavior":"allow","label":"Yes","description":"Allow this once"},""" +
            """{"behavior":"deny","label":"No","description":"Deny this request"}],"sessionId":"s-1"}""" +
            "\n\n"

    /**
     * The bridge pushes NO permission-cleared when a prompt is resolved from
     * another paired device via /v1/command or times out server-side, so an
     * older pending entry can go stale in client state. The screen must show
     * the newest pending prompt — a stale entry must never shadow a live
     * prompt that arrived after it.
     */
    @Test
    fun newestPendingPermissionIsDisplayedOverAStaleOlderOne() {
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append(permissionRequestFrame(1, "perm-stale", "Bash"))
            append(permissionRequestFrame(2, "perm-live", "Read"))
            // Keep the stream open while the assertions below run.
            append(":pad\n\n".repeat(40))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")

        val state = awaitState { it.bridge.pendingPermissions.size == 2 }
        assertEquals(
            "the newest prompt must be displayed, not the possibly-stale oldest one",
            "perm-live",
            state.pendingPermission?.permissionId,
        )
        assertEquals("Read", state.pendingPermission?.toolName)
    }

    /**
     * Regression: answering a permission that no longer exists on the bridge
     * (resolved by another paired device, or timed out server-side) returns
     * 404 and no permission-cleared event ever arrives for it. The client
     * must treat that 404 as authoritative-gone and drop the prompt locally;
     * keeping it would wedge a zombie prompt in state forever.
     */
    @Test
    fun a404AnswerRemovesTheZombiePermissionLocally() {
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append(permissionRequestFrame(1, "perm-zombie", "Bash"))
            // Keep the stream open so the reconnect path can't steal the
            // queued 404 response from the answer below.
            append(":pad\n\n".repeat(60))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.pendingPermission?.permissionId == "perm-zombie" }

        // The prompt was resolved elsewhere in the meantime: the bridge
        // answers the decision with 404 and pushes no cleared event.
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error":"No pending permission with that ID"}"""),
        )
        viewModel.answerPermission("allow")

        val state = awaitState {
            it.decisionResult == "decision:404" && it.pendingPermission == null
        }
        assertTrue(
            "a 404-answered permission must leave bridge state entirely",
            state.bridge.pendingPermissions.isEmpty(),
        )
    }
}
