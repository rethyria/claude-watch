package dev.claudewatch.wear

import dev.claudewatch.shared.terminal.TerminalLineType
import dev.claudewatch.wear.data.AesGcmTokenCipher
import dev.claudewatch.wear.data.CredentialStore
import dev.claudewatch.wear.ui.halo.HaloModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        server.enqueue(MockResponse().setBody("""{"proto":"3","bridgeId":"b-1","machineName":"m"}"""))
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
        enqueuePing() // the engine's discovery preflight precedes every pair
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

    // The single-slot "expired permission" test from the connection-lifecycle
    // branch is superseded by a404AnswerRemovesTheZombiePermissionLocally
    // below: same 404-is-authoritative-gone contract, exercised against the
    // permission QUEUE (the single-slot pendingPermission API no longer
    // exists).

    /**
     * The reducer wiring: real /v1 event frames arriving over SSE surface as
     * typed state — session id from the `session` event, log lines rendered
     * from typed models (not raw "$type $data" appending), pending permission
     * from `permission-request`, and lastEventId committed per applied frame.
     */
    @Test
    fun sseEventsFlowThroughTheSharedReducerIntoUiState() {
        enqueuePing() // the engine's discovery preflight precedes every pair
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

        val state = awaitState { it.permissionQueue.isNotEmpty() }
        val rendered = state.permissionQueue.first()
        assertEquals("perm-1", rendered.permissionId)
        assertEquals("Bash", rendered.toolName)
        // The card says WHAT is asked and WHICH session asks (issue #17).
        assertEquals("$ ls", rendered.requestSummary)
        assertEquals("proj", rendered.sessionLabel)
        assertEquals(listOf("allow", "deny"), rendered.options.map { it.behavior })
        assertEquals("s-1", state.sessionId)
        assertEquals("3", state.bridge.lastEventId)
        assertEquals("s-1", state.bridge.currentSessionId)
        val log = state.eventLog.joinToString("\n")
        assertTrue("typed tool-output line must carry the payload text: $log", log.contains("marker-xyz"))
        assertTrue("session line must be rendered from the typed model: $log", log.contains("session running proj"))
    }

    private fun permissionRequestFrame(
        id: Int,
        permissionId: String,
        toolName: String,
        sessionId: String = "s-1",
        toolInput: String = "{}",
        options: String =
            """[{"behavior":"allow","label":"Yes","description":"Allow this once"},""" +
                """{"behavior":"deny","label":"No","description":"Deny this request"}]""",
    ): String =
        "id: $id\nevent: permission-request\n" +
            """data: {"permissionId":"$permissionId","tool_name":"$toolName","tool_input":$toolInput,""" +
            """"options":$options,"sessionId":"$sessionId"}""" +
            "\n\n"

    private fun sessionRunningFrame(id: Int, sessionId: String, folderName: String): String =
        "id: $id\nevent: session\n" +
            """data: {"state":"running","agent":"claude","cwd":"/tmp/$folderName","folderName":"$folderName","sessionId":"$sessionId"}""" +
            "\n\n"

    /**
     * The bridge pushes NO permission-cleared when a prompt is resolved from
     * another paired device via /v1/command or times out server-side, so an
     * older pending entry can go stale in client state. The queue keeps BOTH
     * (nothing is orphaned), but the RENDERED front is the newest — a stale
     * entry must never shadow a live prompt that arrived after it.
     */
    @Test
    fun newestPendingPermissionIsRenderedWhileTheOlderStaysQueued() {
        enqueuePing() // the engine's discovery preflight precedes every pair
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

        val state = awaitState { it.permissionQueue.size == 2 }
        assertEquals(
            "the newest prompt must be rendered first, not the possibly-stale oldest one",
            listOf("perm-live", "perm-stale"),
            state.permissionQueue.map { it.permissionId },
        )
        assertEquals("Read", state.permissionQueue.first().toolName)
    }

    /**
     * Issue #17 acceptance: two sessions request permission concurrently —
     * both queued (neither orphans the other), each answerable, and every
     * answer POST carries the RENDERED card's permissionId, not whatever
     * happens to be globally newest by the time the click lands.
     *
     * The anti-race property is pinned by answering the OLDER, NON-FRONT
     * card FIRST while both are still queued: this is exactly "a new prompt
     * fronted the queue between render and answer" (the watchOS defect), so
     * an implementation that substitutes the queue front for the passed id
     * fails here on the POST-body assertion instead of surviving the suite.
     */
    @Test
    fun concurrentPromptsFromTwoSessionsAreEachAnsweredOnTheirOwnPermissionId() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append(sessionRunningFrame(1, "s-1", "alpha"))
            append(sessionRunningFrame(2, "s-2", "beta"))
            append(
                permissionRequestFrame(
                    3, "perm-a", "Bash",
                    sessionId = "s-1",
                    toolInput = """{"command":"rm -rf ./build"}""",
                ),
            )
            append(
                permissionRequestFrame(
                    4, "perm-b", "Write",
                    sessionId = "s-2",
                    toolInput = """{"file_path":"/tmp/beta/notes.txt"}""",
                ),
            )
            // Keep the stream open so reconnects can't steal the queued
            // decision responses below.
            append(":pad\n\n".repeat(80))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        val state = awaitState { it.permissionQueue.size == 2 }
        // Both queued, newest first, each card saying WHAT and WHICH session.
        val rendered = state.permissionQueue[0]
        val queued = state.permissionQueue[1]
        assertEquals("perm-b", rendered.permissionId)
        assertEquals("beta", rendered.sessionLabel)
        assertEquals("Write notes.txt", rendered.requestSummary)
        assertEquals("perm-a", queued.permissionId)
        assertEquals("alpha", queued.sessionLabel)
        assertEquals("$ rm -rf ./build", queued.requestSummary)

        // Answer the OLDER, NON-FRONT card first, while perm-b still fronts
        // the queue: the render-to-answer race, materialized. The POST must
        // carry perm-a — the id the click was keyed to — NOT the queue front.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.answerPermission("perm-a", "allow")
        val allowRequest = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no decision request for perm-a")
        val allowBody = JSONObject(allowRequest.body.readUtf8())
        assertEquals(
            "the answer must land on the passed permissionId, never the queue front",
            "perm-a",
            allowBody.getString("permissionId"),
        )
        assertEquals("allow", allowBody.getJSONObject("decision").getString("behavior"))

        // Ack-gated: exactly perm-a left the queue; the front is untouched.
        val afterAllow = awaitState { it.permissionQueue.size == 1 }
        assertEquals("perm-b", afterAllow.permissionQueue.first().permissionId)

        // Then the remaining (front) card, answered on its own id.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.answerPermission("perm-b", "deny")
        val denyRequest = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no decision request for perm-b")
        val denyBody = JSONObject(denyRequest.body.readUtf8())
        assertEquals("perm-b", denyBody.getString("permissionId"))
        assertEquals("deny", denyBody.getJSONObject("decision").getString("behavior"))
        awaitState { it.permissionQueue.isEmpty() }
    }

    /**
     * Issue #17 acceptance: a failed answer POST must NOT clear the prompt
     * optimistically (the watchOS defect silently inverted an approval into a
     * 10-minute auto-deny). The card stays queued with the error surfaced,
     * and a retry that acks then dismisses it.
     */
    @Test
    fun failedAnswerKeepsThePromptQueuedAndSurfacesTheError() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append(permissionRequestFrame(1, "perm-flaky", "Bash", toolInput = """{"command":"ls"}"""))
            append(":pad\n\n".repeat(80))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.permissionQueue.any { p -> p.permissionId == "perm-flaky" } }

        // Injected POST failure: the bridge answers 500.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        viewModel.answerPermission("perm-flaky", "allow")

        val failed = awaitState { it.decisionResult == "decision:500" }
        assertEquals(
            "a failed answer must keep the prompt queued (never optimistic clearing)",
            listOf("perm-flaky"),
            failed.permissionQueue.map { it.permissionId },
        )
        assertEquals("Decision failed: HTTP 500", failed.decisionError)
        assertEquals("no answer may be left marked in-flight", null, failed.decisionInFlightId)

        // Retry succeeds: only the 2xx ack dismisses the prompt.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.answerPermission("perm-flaky", "allow")
        val acked = awaitState { it.decisionResult == "decision:200" && it.permissionQueue.isEmpty() }
        assertEquals("the surfaced error clears once the retry acks", null, acked.decisionError)
    }

    /**
     * Issue #17 acceptance: allow-always is sent as the machine-readable
     * `behavior` from the bridge's canonical option list (never inferred from
     * label wording), and the acked prompt leaves the queue for good — the
     * bridge persists the hook's permission suggestions so it does not recur.
     */
    @Test
    fun allowAlwaysSendsTheBehaviorBasedAnswer() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append(
                permissionRequestFrame(
                    1, "perm-always", "Bash",
                    toolInput = """{"command":"npm test"}""",
                    // The bridge offers allow-always only when the hook sent
                    // permission suggestions to persist (permissions.js).
                    options =
                    """[{"behavior":"allow","label":"Yes","description":"Allow this once"},""" +
                        """{"behavior":"allow-always","label":"Yes, don't ask again","description":"Allow and apply the suggested permission rules"},""" +
                        """{"behavior":"deny","label":"No","description":"Deny this request"}]""",
                ),
            )
            append(":pad\n\n".repeat(80))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        val state = awaitState { it.permissionQueue.isNotEmpty() }
        assertEquals(
            listOf("allow", "allow-always", "deny"),
            state.permissionQueue.first().options.map { it.behavior },
        )

        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.answerPermission("perm-always", "allow-always")
        val request = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no decision request")
        val body = JSONObject(request.body.readUtf8())
        assertEquals("perm-always", body.getString("permissionId"))
        assertEquals(
            "the decision must carry the option's machine-readable behavior",
            "allow-always",
            body.getJSONObject("decision").getString("behavior"),
        )
        // Acked and gone — and nothing re-queues it (the bridge persisted the
        // suggestions server-side; no new permission-request arrives).
        val cleared = awaitState { it.permissionQueue.isEmpty() }
        assertEquals("decision:200", cleared.decisionResult)
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
        enqueuePing() // the engine's discovery preflight precedes every pair
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
        awaitState { it.permissionQueue.any { p -> p.permissionId == "perm-zombie" } }

        // The prompt was resolved elsewhere in the meantime: the bridge
        // answers the decision with 404 and pushes no cleared event.
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error":"No pending permission with that ID"}"""),
        )
        viewModel.answerPermission("perm-zombie", "allow")

        val state = awaitState {
            it.decisionResult == "decision:404" && it.permissionQueue.isEmpty()
        }
        assertTrue(
            "a 404-answered permission must leave bridge state entirely",
            state.bridge.pendingPermissions.isEmpty(),
        )
        // No false "Approved": the 404 is surfaced, not swallowed.
        assertEquals("Already resolved elsewhere", state.decisionError)
    }

    /**
     * Regression (availability): the bridge restarted, so this device's token
     * is dead — every answer 401s and the restarted bridge will never push
     * permission-cleared. Keeping the card queued would wedge the WHOLE app
     * (the full-screen sheet covers the pairing page) with no recovery. A
     * 401/403 is authoritative "this token can never resolve it": drop the
     * card locally — deciding NOTHING on the user's behalf — and surface the
     * re-pair error so page 0's pairing controls are reachable again.
     */
    @Test
    fun a401AnswerDropsTheDeadTokenPromptWithoutFalseResolving() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append(permissionRequestFrame(1, "perm-wedge", "Bash"))
            // Keep the stream open so the reconnect path can't steal the
            // queued 401 response from the answer below.
            append(":pad\n\n".repeat(60))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.permissionQueue.any { p -> p.permissionId == "perm-wedge" } }

        // The bridge was restarted: the old token is unknown, answers 401.
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""),
        )
        viewModel.answerPermission("perm-wedge", "allow")

        val state = awaitState {
            it.decisionResult == "decision:401" && it.permissionQueue.isEmpty()
        }
        assertTrue(
            "a dead-token prompt must leave bridge state so the app is usable again",
            state.bridge.pendingPermissions.isEmpty(),
        )
        // Not swallowed as success — the user is told to re-pair.
        assertEquals("Not authorized — re-pair with the bridge", state.decisionError)
    }

    /**
     * Regression (availability): the bridge host is unreachable (laptop
     * closed, network changed) — every answer fails at the transport layer,
     * so no 2xx/404 can ever arrive and no SSE permission-cleared either.
     * The prompt must stay queued (it may well still be live server-side; no
     * optimistic clearing), but repeated failures count up and unlock the
     * sheet's local-dismiss escape hatch, which drops the card WITHOUT
     * sending any decision — otherwise the undismissable full-screen sheet
     * wedges the entire app forever.
     */
    @Test
    fun transportFailuresUnlockALocalDismissThatSendsNoDecision() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append(permissionRequestFrame(1, "perm-stuck", "Bash"))
            append(":pad\n\n".repeat(60))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.permissionQueue.any { p -> p.permissionId == "perm-stuck" } }

        // The bridge host disappears: every subsequent request — answers and
        // SSE reconnects alike — fails at the transport layer.
        server.shutdown()

        viewModel.answerPermission("perm-stuck", "allow")
        val once = awaitState { it.decisionFailureCount == 1 }
        assertEquals(
            "a transport-failed answer must keep the prompt queued",
            listOf("perm-stuck"),
            once.permissionQueue.map { it.permissionId },
        )
        assertTrue(
            "the failure must be surfaced on the card: ${once.decisionError}",
            once.decisionError?.startsWith("Decision failed:") == true,
        )

        viewModel.answerPermission("perm-stuck", "allow")
        awaitState { it.decisionFailureCount == 2 && it.decisionInFlightId == null }

        // The escape hatch: drops the card locally, decides nothing (the
        // server is unreachable — no request can even be attempted), and
        // clears the surfaced error so the app is usable again.
        viewModel.dismissPermissionLocally("perm-stuck")
        val dismissed = awaitState { it.permissionQueue.isEmpty() }
        assertTrue(
            "the dismissed prompt must leave bridge state entirely",
            dismissed.bridge.pendingPermissions.isEmpty(),
        )
        assertEquals(null, dismissed.decisionError)
        assertEquals(0, dismissed.decisionFailureCount)
    }

    /**
     * An AskUserQuestion permission-request frame: no top-level options, ALL
     * questions in tool_input.questions — the exact event shape hooks.js
     * broadcasts for tool_name AskUserQuestion.
     */
    private fun askUserQuestionFrame(id: Int, permissionId: String, sessionId: String = "s-1"): String =
        "id: $id\nevent: permission-request\n" +
            """data: {"permissionId":"$permissionId","tool_name":"AskUserQuestion","tool_input":{"questions":[""" +
            """{"question":"Which color scheme?","header":"Color","multiSelect":false,""" +
            """"options":[{"label":"Blue"},{"label":"Green"}]},""" +
            """{"question":"Tabs or spaces?","header":"Indent","multiSelect":false,""" +
            """"options":[{"label":"Tabs"},{"label":"Spaces"}]}]},"sessionId":"$sessionId"}""" +
            "\n\n"

    /**
     * Issue #18 acceptance: a multi-question AskUserQuestion payload surfaces
     * EVERY question typed on the queued card (no top-level options — the
     * question card, not the behavior buttons), and answerQuestions POSTs the
     * bridge's /v1 array decision shape — `decision.answers` as a positional
     * array aligned with the payload's question order, free-text values
     * verbatim — which the bridge zips with the questions into the blocked
     * hook's `updatedInput.answers` (collectAskUserQuestionAnswers in
     * hooks.js). Positional rather than text-keyed so questions sharing the
     * same text still each carry their own answer on the wire. Ack-gated
     * exactly like a behavior answer: only the 2xx dismisses the card.
     */
    @Test
    fun askUserQuestionAnswersEveryQuestionInOnePost() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append(askUserQuestionFrame(1, "perm-ask"))
            append(":pad\n\n".repeat(80))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        val state = awaitState { it.permissionQueue.isNotEmpty() }
        val rendered = state.permissionQueue.first()
        // Every question of the payload is on the card, with its own options.
        assertEquals(
            listOf("Which color scheme?", "Tabs or spaces?"),
            rendered.questions.map { it.question },
        )
        assertEquals(listOf("Blue", "Green"), rendered.questions[0].options.map { it.label })
        assertEquals(listOf("Tabs", "Spaces"), rendered.questions[1].options.map { it.label })
        // Question prompts render questions, not behavior buttons.
        assertEquals(emptyList<String>(), rendered.options.map { it.behavior })

        // One answer per question: an option label and a free-text answer.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.answerQuestions(
            "perm-ask",
            listOf("Blue", "two-space soft tabs"),
        )
        val request = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no decision request")
        assertEquals("/v1/command", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("perm-ask", body.getString("permissionId"))
        val decision = body.getJSONObject("decision")
        assertEquals("allow", decision.getString("behavior"))
        val answers = decision.getJSONArray("answers")
        assertEquals("every question gets its answer", 2, answers.length())
        assertEquals("answers align with the question order", "Blue", answers.getString(0))
        assertEquals(
            "free-text answers travel verbatim",
            "two-space soft tabs",
            answers.getString(1),
        )

        // Only the 2xx ack dismissed the card.
        val acked = awaitState { it.permissionQueue.isEmpty() }
        assertEquals("decision:200", acked.decisionResult)
    }

    /**
     * Issue #18 acceptance: dismissal semantics match the approval card — a
     * failed answers POST keeps the question card queued with the error
     * surfaced (never optimistic clearing) and counts toward the local-dismiss
     * escape hatch; the retry's 2xx is what dismisses it.
     */
    @Test
    fun failedQuestionAnswersKeepTheCardQueuedUntilARetryAcks() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append(askUserQuestionFrame(1, "perm-ask-flaky"))
            append(":pad\n\n".repeat(80))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.permissionQueue.any { p -> p.permissionId == "perm-ask-flaky" } }

        val answers = listOf("Green", "Tabs")
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        viewModel.answerQuestions("perm-ask-flaky", answers)

        val failed = awaitState { it.decisionResult == "decision:500" }
        assertEquals(
            "a failed answers POST must keep the question card queued",
            listOf("perm-ask-flaky"),
            failed.permissionQueue.map { it.permissionId },
        )
        assertEquals("Decision failed: HTTP 500", failed.decisionError)
        assertEquals(
            "failures count toward the local-dismiss escape hatch",
            1,
            failed.decisionFailureCount,
        )

        // Retry succeeds: only the 2xx ack dismisses the card, error cleared.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.answerQuestions("perm-ask-flaky", answers)
        val acked = awaitState { it.decisionResult == "decision:200" && it.permissionQueue.isEmpty() }
        assertEquals(null, acked.decisionError)
        assertEquals(0, acked.decisionFailureCount)
    }

    /**
     * Spawn and kill ride POST /v1/command with the bridge's `spawn` / `kill`
     * body shapes (commands.js handleCommand); the results surface in
     * [BridgeViewModel.UiState.sessionActionResult] and spawn retargets the
     * command box at the fresh session. (The full round-trip against the real
     * bridge — spawned page appears, killed page prunes — is the instrumented
     * WalkingSkeletonTest.)
     */
    @Test
    fun spawnAndKillGoOverV1CommandWithTheBridgeBodyShapes() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(16, 250, TimeUnit.MILLISECONDS)
                .setBody(":connected\n\n" + ":pad\n\n".repeat(400)),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitStatus { it == "paired, stream open" }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        server.enqueue(MockResponse().setBody("""{"ok":true,"sessionId":"s-new","agent":"claude"}"""))
        viewModel.spawnSession("claude")
        val spawnRequest = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no spawn request")
        assertEquals("/v1/command", spawnRequest.path)
        val spawnBody = JSONObject(spawnRequest.body.readUtf8())
        assertEquals("claude", spawnBody.getString("spawn"))
        // No picker target = the pre-#56 wire shape: the cwd key is OMITTED
        // (the bridge's default cwd chain), never null-valued.
        assertFalse(spawnBody.has("cwd"))
        val afterSpawn = awaitState { it.sessionActionResult == "spawn:200" }
        assertEquals("s-new", afterSpawn.sessionId)

        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.killSession("s-new")
        val killRequest = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no kill request")
        assertEquals("/v1/command", killRequest.path)
        val killBody = JSONObject(killRequest.body.readUtf8())
        assertTrue(killBody.getBoolean("kill"))
        assertEquals("s-new", killBody.getString("sessionId"))
        awaitState { it.sessionActionResult == "kill:200" }

        // Issue #56: a picker target rides the same body as `cwd` — here the
        // "no project" home sentinel the bridge resolves to ITS user's home.
        server.enqueue(MockResponse().setBody("""{"ok":true,"sessionId":"s-home","agent":"claude"}"""))
        viewModel.spawnSession("claude", "~")
        val homeSpawnRequest = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no targeted spawn request")
        val homeSpawnBody = JSONObject(homeSpawnRequest.body.readUtf8())
        assertEquals("claude", homeSpawnBody.getString("spawn"))
        assertEquals("~", homeSpawnBody.getString("cwd"))
        awaitState { it.sessionId == "s-home" }
    }

    /** Pair against the mock bridge and drain the ping/pair/events requests,
     *  leaving the request queue clean for the test body's own calls. */
    private fun pairAndDrain() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(16, 250, TimeUnit.MILLISECONDS)
                .setBody(":connected\n\n" + ":pad\n\n".repeat(400)),
        )
        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitStatus { it == "paired, stream open" }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events
    }

    /**
     * Issue #57: fetchUsage GETs /v1/usage with the paired bearer token,
     * passes through Loading, and parses render-what-you-get — EVERY limits[]
     * entry becomes a bar, an UNKNOWN kind included (a new upstream window
     * must appear without an app update), labels from the payload (kind as
     * the fallback), percent as USED percent untouched. A live "api" payload
     * carries no fetchedAtMs on the WIRE — the client model stamps NOW at
     * parse time ("when these numbers were current" is always non-null, so
     * the screen's freshness label is always-on).
     */
    @Test
    fun usageFetchParsesRenderWhatYouGetIncludingUnknownKinds() {
        pairAndDrain()

        val beforeMs = System.currentTimeMillis()
        server.enqueue(
            MockResponse()
                // Held back briefly so the Loading state is observable.
                .setHeadersDelay(300, TimeUnit.MILLISECONDS)
                .setBody(
                    """{"limits":[
                        {"kind":"session","label":"5-hour","percent":37.5,"resetsAt":"2026-07-18T19:10:00Z","severity":"normal"},
                        {"kind":"weekly_all","label":"weekly","percent":80,"resetsAt":"2026-07-24T00:00:00Z"},
                        {"kind":"weekly_scoped","label":"Fable","percent":12,"resetsAt":"2026-07-24T00:00:00Z"},
                        {"kind":"lunar_window","percent":5,"resetsAt":"2026-08-01T00:00:00Z"}
                    ],"source":"api"}""",
                ),
        )
        viewModel.fetchUsage()
        awaitState { it.usage is BridgeViewModel.UsageUi.Loading }

        val request = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no usage request")
        assertEquals("GET", request.method)
        assertEquals("/v1/usage", request.path)
        assertEquals("Bearer tok-1", request.getHeader("Authorization"))

        val state = awaitState { it.usage is BridgeViewModel.UsageUi.Data }
        val data = state.usage as BridgeViewModel.UsageUi.Data
        assertEquals("api", data.source)
        // The wire sent no fetchedAtMs (api result) — the parse stamped NOW,
        // bracketed by the wall clock around the fetch: sane, never zero.
        val afterMs = System.currentTimeMillis()
        assertTrue(
            "an api parse stamps fetchedAtMs at parse time (got ${data.fetchedAtMs}, " +
                "expected within $beforeMs..$afterMs)",
            data.fetchedAtMs in beforeMs..afterMs,
        )
        assertEquals(
            "every entry renders, unknown kinds included",
            listOf("session", "weekly_all", "weekly_scoped", "lunar_window"),
            data.limits.map { it.kind },
        )
        assertEquals(
            "labels come from the payload; kind is the label-less fallback",
            listOf("5-hour", "weekly", "Fable", "lunar_window"),
            data.limits.map { it.label },
        )
        assertEquals(37.5, data.limits[0].percent, 0.0001)
        assertEquals("2026-07-18T19:10:00Z", data.limits[0].resetsAt)
        // The server's own color coding rides along upstream-verbatim; an
        // entry the bridge sent WITHOUT the key parses to null (the tier
        // logic keys on presence).
        assertEquals("normal", data.limits[0].severity)
        assertNull("absent severity must parse to null", data.limits[1].severity)
    }

    /** Issue #57: the bridge's cache fallback (`source: "cache"`) carries its
     *  fetchedAtMs through UNTOUCHED — the data's true age, never re-stamped
     *  — so the screen's "updated Xm ago" label tells the truth. */
    @Test
    fun usageCacheFallbackCarriesItsStalenessMetadata() {
        pairAndDrain()

        server.enqueue(
            MockResponse().setBody(
                """{"limits":[{"kind":"session","label":"5-hour","percent":50,"resetsAt":"2026-07-18T19:10:00Z"}],""" +
                    """"source":"cache","fetchedAtMs":1752850000000}""",
            ),
        )
        viewModel.fetchUsage()
        val state = awaitState { it.usage is BridgeViewModel.UsageUi.Data }
        val data = state.usage as BridgeViewModel.UsageUi.Data
        assertEquals("cache", data.source)
        assertEquals(1752850000000L, data.fetchedAtMs)
    }

    /**
     * Issue #57: a 503 (neither the bridge's API call nor its cache yielded
     * data) surfaces the bridge's error string with the retry affordance, and
     * a retry — the same fetchUsage the page entry fires — REPARSES a later
     * success over the error (fetch-on-open, no sticky failure).
     */
    @Test
    fun usageErrorSurfacesTheBridgeMessageAndRetryReparses() {
        pairAndDrain()

        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"error":"usage unavailable: no credentials"}"""),
        )
        viewModel.fetchUsage()
        val failed = awaitState { it.usage is BridgeViewModel.UsageUi.Error }
        assertEquals(
            "usage unavailable: no credentials",
            (failed.usage as BridgeViewModel.UsageUi.Error).message,
        )

        server.enqueue(
            MockResponse().setBody(
                """{"limits":[{"kind":"session","label":"5-hour","percent":10,"resetsAt":"2026-07-18T19:10:00Z"}],"source":"api"}""",
            ),
        )
        viewModel.fetchUsage()
        val recovered = awaitState { it.usage is BridgeViewModel.UsageUi.Data }
        assertEquals(1, (recovered.usage as BridgeViewModel.UsageUi.Data).limits.size)
    }

    /**
     * Client-side rate limit (2026-07-18): the upstream usage endpoint
     * aggressively 429s pollers, and the page-entry seam fires on EVERY
     * landing — so a re-entry within [BridgeViewModel.usageRateLimitMs] of a
     * LIVE api success must be a complete no-op: no request leaves the watch
     * and the state never leaves Data (no Loading flicker; the fresh bars
     * stay on screen and re-entry is instant).
     */
    @Test
    fun usageRefetchWithinTheRateLimitWindowIsANoOp() {
        pairAndDrain()

        server.enqueue(
            MockResponse().setBody(
                """{"limits":[{"kind":"session","label":"5-hour","percent":50,"resetsAt":"2026-07-18T19:10:00Z"}],"source":"api"}""",
            ),
        )
        viewModel.fetchUsage()
        val first = awaitState { it.usage is BridgeViewModel.UsageUi.Data }
        assertEquals("api", (first.usage as BridgeViewModel.UsageUi.Data).source)
        server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("the first page entry must fetch")

        // Re-entry inside the (default 5-minute) window. The limiter returns
        // BEFORE the synchronous Loading flip, so the usage state still being
        // the SAME Data instance right after the call proves the state never
        // left Data — a Loading flicker would have replaced it.
        viewModel.fetchUsage()
        assertSame(first.usage, viewModel.state.value.usage)
        assertNull(
            "a rate-limited re-entry must not reach the bridge (exactly ONE /v1/usage served)",
            server.takeRequest(500, TimeUnit.MILLISECONDS),
        )
    }

    /**
     * The limiter is armed ONLY by a successful `source == "api"` parse: a
     * cache-fallback result (the bridge's upstream call failed; the bars are
     * stale) never arms it, so the next page entry refetches immediately —
     * re-entry is exactly the chance that the API came back.
     */
    @Test
    fun usageCacheResultNeverArmsTheRateLimiter() {
        pairAndDrain()

        server.enqueue(
            MockResponse().setBody(
                """{"limits":[{"kind":"session","label":"5-hour","percent":50,"resetsAt":"2026-07-18T19:10:00Z"}],""" +
                    """"source":"cache","fetchedAtMs":1752850000000}""",
            ),
        )
        viewModel.fetchUsage()
        val stale = awaitState { it.usage is BridgeViewModel.UsageUi.Data }
        assertEquals("cache", (stale.usage as BridgeViewModel.UsageUi.Data).source)
        server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("the first page entry must fetch")

        server.enqueue(
            MockResponse().setBody(
                """{"limits":[{"kind":"session","label":"5-hour","percent":51,"resetsAt":"2026-07-18T19:10:00Z"}],"source":"api"}""",
            ),
        )
        viewModel.fetchUsage()
        val live = awaitState {
            (it.usage as? BridgeViewModel.UsageUi.Data)?.source == "api"
        }
        assertEquals(51.0, (live.usage as BridgeViewModel.UsageUi.Data).limits[0].percent, 0.0001)
        // BOTH page entries reached the bridge: cache results never gate.
        server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("a cache-fallback page entry must refetch")
    }

    /** Records the haptic grammar so JVM tests can assert ack vs failure verbs. */
    private class RecordingHaptics : Haptics {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun commandAcked() { events += "acked" }
        override fun commandFailed() { events += "failed" }
    }

    /**
     * Issue #20 acceptance: a stubbed recognizer result (fixed transcription
     * fed to [BridgeViewModel.dictationResult] — real voice cannot run
     * headlessly) is POSTed to /v1/command and echoed into the terminal ONLY
     * after the bridge's 2xx ack. While the ack is held back the command is
     * PENDING: no `> text` echo, no thinking cursor — the inverse of the
     * watchOS trap that echoed before the network call. The ack also speaks
     * the haptic grammar's tick, and the session's next SSE output still
     * clears the raised cursor.
     */
    @Test
    fun dictatedCommandIsPostedAndEchoedOnlyAfterTheBridgeAcks() {
        val haptics = RecordingHaptics()
        viewModel.haptics = haptics
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        // The session announcement arrives promptly; the pty-output sits
        // behind a large throttled pad so it lands seconds AFTER the command
        // below is acked (generous margin: the host may be under load).
        val sseBody = buildString {
            append(":connected\n\n")
            append("id: 1\nevent: session\n")
            append("""data: {"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj","sessionId":"s-1"}""")
            append("\n\n")
            append(":pad\n\n".repeat(2000))
            append("id: 2\nevent: pty-output\n")
            append("""data: {"text":"hello from the agent\r\n","sessionId":"s-1"}""")
            append("\n\n")
            append(":tail\n\n".repeat(40))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(1024, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.sessionId == "s-1" }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        // Hold the ack back so the pending (pre-ack) state is observable.
        server.enqueue(
            MockResponse()
                .setHeadersDelay(2_500, TimeUnit.MILLISECONDS)
                .setBody("""{"ok":true}"""),
        )
        viewModel.dictationResult("say hello")

        // Pending is set synchronously; echo/pending-clear is one atomic
        // update, so THIS snapshot proves the no-echo-before-ack invariant
        // without any timing sensitivity.
        val pending = awaitState { it.commandInFlightText == "say hello" }
        val preAck = pending.bridge.sessions.getValue("s-1")
        assertFalse("no thinking cursor before the ack", preAck.thinking)
        assertTrue(
            "no echo before the ack: ${preAck.terminal.items.map { it.text }}",
            preAck.terminal.items.none { it.text == "> say hello" },
        )
        assertEquals("the draft moved into the pending marker", "", pending.commandDraft)
        assertTrue("no haptic verb before the outcome", haptics.events.isEmpty())

        // The POST carried the recognized text, session-scoped.
        val commandRequest = server.takeRequest(15, TimeUnit.SECONDS)
            ?: throw AssertionError("no /v1/command request")
        assertEquals("/v1/command", commandRequest.path)
        val commandBody = JSONObject(commandRequest.body.readUtf8())
        assertEquals("say hello", commandBody.getString("command"))
        assertEquals("s-1", commandBody.getString("sessionId"))

        // Only the 2xx ack echoes: `> text` + thinking cursor + ack tick.
        val acked = awaitState(timeoutMs = 60_000) { it.commandResult == "command:200" }
        assertEquals(null, acked.commandInFlightText)
        assertEquals(null, acked.commandError)
        val echoed = acked.bridge.sessions.getValue("s-1")
        assertTrue("thinking cursor raises on ack", echoed.thinking)
        val echoLine = echoed.terminal.items.last()
        assertEquals("> say hello", echoLine.text)
        assertEquals(TerminalLineType.COMMAND, echoLine.type)
        assertEquals(listOf("acked"), haptics.events.toList())

        // The delayed pty-output clears the cursor and lands after the echo.
        val cleared = awaitState(timeoutMs = 60_000) {
            it.bridge.sessions["s-1"]?.thinking == false
        }
        val terminal = cleared.bridge.sessions.getValue("s-1").terminal.items
        assertEquals(
            listOf("> say hello", "hello from the agent"),
            terminal.takeLast(2).map { it.text },
        )
    }

    /**
     * Issue #20 acceptance: an injected 5xx echoes NOTHING (the watchOS trap
     * claimed "sent" and lost the text), surfaces the error with the failure
     * buzz, and restores the exact text into the draft — so retrying re-sends
     * the same text, which then echoes on its 2xx ack. (The transport-error/
     * timeout half of the criterion is pinned separately by
     * [timedOutSendClearsPendingRestoresTheDraftAndRetryResendsTheSameText].)
     */
    @Test
    fun failedSendEchoesNothingSurfacesTheErrorAndRetryResendsTheSameText() {
        val haptics = RecordingHaptics()
        viewModel.haptics = haptics
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append("id: 1\nevent: session\n")
            append("""data: {"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj","sessionId":"s-1"}""")
            append("\n\n")
            // Keep the stream open so reconnects can't steal the queued
            // command responses below.
            append(":pad\n\n".repeat(80))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.sessionId == "s-1" }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        // Injected failure: the bridge answers the command POST with 500.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        viewModel.dictationResult("deploy it")

        val failed = awaitState { it.commandResult == "command:500" }
        assertEquals("Send failed: HTTP 500", failed.commandError)
        assertEquals("the failed text is restored for retry", "deploy it", failed.commandDraft)
        assertEquals(null, failed.commandInFlightText)
        val afterFail = failed.bridge.sessions.getValue("s-1")
        assertFalse("no thinking cursor on failure", afterFail.thinking)
        assertTrue(
            "a failed send must never echo: ${afterFail.terminal.items.map { it.text }}",
            afterFail.terminal.items.none { it.text == "> deploy it" },
        )
        assertEquals(listOf("failed"), haptics.events.toList())
        server.takeRequest(10, TimeUnit.SECONDS) // the failed /v1/command

        // Retry re-sends the SAME text (straight from the restored draft).
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.sendCommand(failed.commandDraft)
        val retryRequest = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no retry /v1/command request")
        val retryBody = JSONObject(retryRequest.body.readUtf8())
        assertEquals("deploy it", retryBody.getString("command"))
        assertEquals("s-1", retryBody.getString("sessionId"))

        val acked = awaitState { it.commandResult == "command:200" }
        assertEquals("the surfaced error clears once the retry acks", null, acked.commandError)
        assertEquals("the draft clears once the text is really sent", "", acked.commandDraft)
        assertEquals("> deploy it", acked.bridge.sessions.getValue("s-1").terminal.items.last().text)
        assertEquals(listOf("failed", "acked"), haptics.events.toList())
    }

    /**
     * Issue #20 acceptance, timeout/transport half (sendCommand's catch
     * branch): the bridge accepts the command POST but never answers — the
     * client's 20s read timeout fires, so no HTTP status ever arrives. Same
     * contract as the injected 5xx: echo NOTHING, clear the pending marker
     * (a stuck marker would wedge every later send behind command:busy
     * forever — the exact watchOS trap for the timeout case), surface the
     * error with the failure buzz, and restore the exact text into the draft
     * — from which a retry re-sends the same text.
     */
    @Test
    fun timedOutSendClearsPendingRestoresTheDraftAndRetryResendsTheSameText() {
        val haptics = RecordingHaptics()
        viewModel.haptics = haptics
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append("id: 1\nevent: session\n")
            append("""data: {"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj","sessionId":"s-1"}""")
            append("\n\n")
            // The stream must outlive the 20s read timeout below, or its
            // reconnect steals the queued retry ack: ~42KB at 256B/250ms
            // keeps it open for well over a minute.
            append(":pad\n\n".repeat(6_000))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.sessionId == "s-1" }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        // The bridge goes silent: the POST is read but never answered, so
        // the failure is a client-side read timeout, not a status code.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        viewModel.dictationResult("say hello")

        // Pending while waiting on the never-coming answer, and no echo.
        val pending = awaitState { it.commandInFlightText == "say hello" }
        assertEquals("the draft moved into the pending marker", "", pending.commandDraft)

        // The 20s read timeout fires (generous margin: host under load).
        val failed = awaitState(timeoutMs = 90_000) { it.commandError != null }
        assertTrue(
            "the timeout must surface as a failure: ${failed.commandError}",
            failed.commandError!!.startsWith("Send failed:"),
        )
        assertEquals(
            "a stuck pending marker would wedge all later sends behind command:busy",
            null,
            failed.commandInFlightText,
        )
        assertEquals("the failed text is restored for retry", "say hello", failed.commandDraft)
        val afterFail = failed.bridge.sessions.getValue("s-1")
        assertFalse("no thinking cursor on a timeout", afterFail.thinking)
        assertTrue(
            "a timed-out send must never echo: ${afterFail.terminal.items.map { it.text }}",
            afterFail.terminal.items.none { it.text == "> say hello" },
        )
        assertEquals(listOf("failed"), haptics.events.toList())
        server.takeRequest(10, TimeUnit.SECONDS) // the timed-out /v1/command

        // Retry straight from the restored draft: the SAME text goes out,
        // and only its 2xx ack echoes it.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.sendCommand(failed.commandDraft)
        val retryRequest = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no retry /v1/command request")
        assertEquals("/v1/command", retryRequest.path)
        val retryBody = JSONObject(retryRequest.body.readUtf8())
        assertEquals("say hello", retryBody.getString("command"))
        assertEquals("s-1", retryBody.getString("sessionId"))
        val acked = awaitState(timeoutMs = 60_000) { it.commandResult == "command:200" }
        assertEquals("the surfaced error clears once the retry acks", null, acked.commandError)
        assertEquals("> say hello", acked.bridge.sessions.getValue("s-1").terminal.items.last().text)
        assertEquals(listOf("failed", "acked"), haptics.events.toList())
    }

    /**
     * Regression: text typed (or dictated into the draft via the
     * command:busy refusal) while a send is pending must survive that send's
     * failure. The failure branches used to restore the OLD in-flight text
     * unconditionally, silently destroying whatever entered the draft during
     * the pending window — the exact loss class issue #20 exists to prevent.
     * The restore only fills a draft the send itself emptied; the failed
     * text stays visible inside the surfaced error.
     */
    @Test
    fun textEnteredWhileASendIsPendingSurvivesThatSendsFailure() {
        val haptics = RecordingHaptics()
        viewModel.haptics = haptics
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append("id: 1\nevent: session\n")
            append("""data: {"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj","sessionId":"s-1"}""")
            append("\n\n")
            append(":pad\n\n".repeat(80))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.sessionId == "s-1" }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        // Hold the failure back so there is a pending window to type into.
        server.enqueue(
            MockResponse()
                .setHeadersDelay(2_500, TimeUnit.MILLISECONDS)
                .setResponseCode(500)
                .setBody("""{"error":"boom"}"""),
        )
        viewModel.dictationResult("first command")
        awaitState { it.commandInFlightText == "first command" }

        // The user types new text while the first send is still pending.
        viewModel.updateCommandDraft("second command")

        val failed = awaitState(timeoutMs = 60_000) { it.commandResult == "command:500" }
        assertEquals(
            "text entered during the pending window must not be clobbered",
            "second command",
            failed.commandDraft,
        )
        assertTrue(
            "the failed text must stay visible, not silently lost: ${failed.commandError}",
            failed.commandError!!.startsWith("Send failed: HTTP 500") &&
                failed.commandError!!.contains("first command"),
        )
        assertEquals(null, failed.commandInFlightText)
        assertEquals(listOf("failed"), haptics.events.toList())
    }

    /**
     * Issue #20 acceptance: unpaired, the input path refuses cleanly — no
     * POST is attempted, nothing echoes, nothing pends, the error + failure
     * buzz surface, and the transcription lands in the draft instead of being
     * lost.
     */
    @Test
    fun unpairedDictationRefusesCleanlyInsteadOfPretendingToSend() {
        val haptics = RecordingHaptics()
        viewModel.haptics = haptics

        // Never paired: the dictated text must not produce any request.
        viewModel.dictationResult("rm -rf everything")

        val refused = awaitState { it.commandResult == "command:not-paired" }
        assertEquals("Not paired — command not sent", refused.commandError)
        assertEquals(null, refused.commandInFlightText)
        assertEquals("the transcription is kept, not lost", "rm -rf everything", refused.commandDraft)
        assertEquals(listOf("failed"), haptics.events.toList())
        assertEquals("no request may leave the device", 0, server.requestCount)
    }

    /**
     * Regression (Halo review): a NEW dictation while the draft still holds a
     * previous failed send's restored text must not clobber that text — the
     * Halo UI renders the draft only on the voice overlay, so an overwritten
     * draft is text destroyed with no trace (the issue-#20 loss class). The
     * new transcription is sent without ever claiming the occupied draft, and
     * the send's in-flight transition must not wipe the draft either (it only
     * empties a draft holding the text being sent).
     */
    @Test
    fun dictationDoesNotClobberADraftRestoredByAFailedSend() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append("id: 1\nevent: session\n")
            append("""data: {"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj","sessionId":"s-1"}""")
            append("\n\n")
            append(":pad\n\n".repeat(80))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.sessionId == "s-1" }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        // First dictation fails: its text is restored into the draft.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        viewModel.dictationResult("first command")
        val failed = awaitState { it.commandResult == "command:500" }
        assertEquals("first command", failed.commandDraft)
        server.takeRequest(10, TimeUnit.SECONDS) // the failed /v1/command

        // Second dictation goes out while the restored text still owns the
        // draft: the new text is POSTed, the old text survives throughout.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.dictationResult("second command")
        val request = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no /v1/command request for the second dictation")
        assertEquals("second command", JSONObject(request.body.readUtf8()).getString("command"))
        val acked = awaitState { it.commandResult == "command:200" }
        assertEquals(
            "the restored failed text must survive a newer dictation",
            "first command",
            acked.commandDraft,
        )
        assertEquals(null, acked.commandInFlightText)
    }

    /**
     * Regression (Halo review): the up-front refusals (not-paired /
     * no-session / busy) share the non-clobbering rule — a refused
     * transcription must not overwrite other text already in the draft; it
     * rides the surfaced error instead, so neither text is silently lost.
     */
    @Test
    fun refusedDictationKeepsTheOccupiedDraftAndRidesTheFailedText() {
        viewModel.updateCommandDraft("typed text")
        viewModel.dictationResult("spoken command") // never paired: refused

        val refused = awaitState { it.commandResult == "command:not-paired" }
        assertEquals(
            "the pre-existing draft must not be clobbered by a refused dictation",
            "typed text",
            refused.commandDraft,
        )
        assertTrue(
            "the refused text must stay visible inside the error: ${refused.commandError}",
            refused.commandError!!.contains("Not paired") &&
                refused.commandError!!.contains("spoken command"),
        )
        assertEquals("no request may leave the device", 0, server.requestCount)
    }

    /**
     * Regression (Halo review): decisionResult is global and STICKY, so the
     * cards must be able to tell WHOSE outcome it is. Every decision outcome
     * stamps [BridgeViewModel.UiState.decisionForId] with its prompt id, and
     * a submit for a prompt that is no longer queued (resolved while a
     * dictated answer round-tripped through the recognizer) is a no-op that
     * leaves the previous outcome untouched — the card's ✓ flash keys on the
     * id matching, so the stale success can never masquerade as a delivered
     * answer.
     */
    @Test
    fun decisionOutcomesCarryTheirPromptIdAndAVanishedPromptIsANoOp() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append(permissionRequestFrame(1, "perm-mine", "Bash"))
            append(":pad\n\n".repeat(60))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.permissionQueue.any { p -> p.permissionId == "perm-mine" } }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.answerPermission("perm-mine", "allow")
        val acked = awaitState { it.decisionResult == "decision:200" && it.permissionQueue.isEmpty() }
        assertEquals("the outcome names its prompt", "perm-mine", acked.decisionForId)
        server.takeRequest(10, TimeUnit.SECONDS) // the decision /v1/command

        // A submit for a prompt that already left the queue (the stale-sink
        // race): nothing is POSTed and the sticky success stays attributed to
        // the prompt it belongs to.
        val requestsBefore = server.requestCount
        viewModel.answerQuestions("perm-vanished", listOf("too late"))
        Thread.sleep(500)
        val after = viewModel.state.value
        assertEquals("no POST for a prompt that is no longer queued", requestsBefore, server.requestCount)
        assertEquals("the previous outcome keeps its attribution", "perm-mine", after.decisionForId)
        assertEquals("decision:200", after.decisionResult)
        assertEquals(null, after.decisionInFlightId)
    }

    /**
     * The voice overlay's two hygiene hooks (Halo review): Discard drops the
     * restored draft AND its error together (a lingering error would reopen
     * the overlay), and a new recognizer launch clears a stale error while
     * leaving the draft alone (its text is still owed a surface).
     */
    @Test
    fun discardClearsDraftPlusErrorAndANewDictationClearsOnlyTheStaleError() {
        // Manufacture a failed state: unpaired refusal restores the text.
        viewModel.dictationResult("lost words")
        val refused = awaitState { it.commandError != null }
        assertEquals("lost words", refused.commandDraft)

        viewModel.dictationStarted()
        val started = viewModel.state.value
        assertEquals("a new dictation clears the stale error", null, started.commandError)
        assertEquals("but never touches the draft", "lost words", started.commandDraft)

        viewModel.dictationResult("more words") // refused again: error is back
        awaitState { it.commandError != null }

        viewModel.discardCommand()
        val discarded = viewModel.state.value
        assertEquals("Discard is the deliberate-loss exit for the draft", "", discarded.commandDraft)
        assertEquals("and takes the surfaced error with it", null, discarded.commandError)
    }

    /**
     * Issue #48 (restoring #16 across reconnects): a reducer-REJECTED frame is
     * REPLAYED on reconnect, not skipped. The ViewModel acks only APPLIED
     * frames back to the engine, so the engine's reconnect cursor never runs
     * past a rejected frame. A valid frame (id 4) is applied+acked; a malformed
     * frame (id 5, unknown session state → the reducer rejects it) is NOT — so
     * the reconnect's Last-Event-ID header carries 4 (replaying 5), never 5.
     */
    @Test
    fun aReducerRejectedFrameIsReplayedOnReconnectWhileTheAppliedOneAdvances() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        // Stream 1: a VALID session frame (id 4, applied+acked) then a MALFORMED
        // one (id 5, unknown state → reducer Rejected, never acked); then it
        // ends (finite body, no hold) so the engine reconnects.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    ":connected\n\n" +
                        sessionRunningFrame(4, "s-4", "proj") +
                        "id: 5\nevent: session\ndata: {\"state\":\"bogus\",\"sessionId\":\"s-5\"}\n\n",
                ),
        )
        enqueuePing() // the reconnect's discovery preflight
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(16, 250, TimeUnit.MILLISECONDS)
                .setBody(":connected\n\n" + ":pad\n\n".repeat(400)),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        // The valid frame applied (and was acked); the malformed one rejected.
        awaitState { it.bridge.sessions.containsKey("s-4") }

        assertEquals("/v1/ping", server.takeRequest(10, TimeUnit.SECONDS)?.path)
        assertEquals("/v1/pair", server.takeRequest(10, TimeUnit.SECONDS)?.path)
        val firstEvents = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no /v1/events request")
        assertEquals("/v1/events", firstEvents.path)
        assertEquals("0", firstEvents.getHeader("Last-Event-ID"))

        // Reconnect after stream 1 ended: the applied frame advanced the cursor
        // to 4, but the rejected frame 5 did NOT — so the header replays from 4.
        assertEquals("/v1/ping", server.takeRequest(10, TimeUnit.SECONDS)?.path)
        val reconnect = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no reconnect /v1/events request")
        assertEquals("/v1/events", reconnect.path)
        assertEquals(
            "the rejected frame must be replayed (cursor at the applied id, never the rejected one)",
            "4",
            reconnect.getHeader("Last-Event-ID"),
        )
        assertFalse(
            "the malformed frame never entered state",
            viewModel.state.value.bridge.sessions.containsKey("s-5"),
        )
    }

    /**
     * Issue #53: hiding an EXTERNAL (hook-created) session is LOCAL — it sends
     * nothing to the bridge and drops out of the derived Halo model while
     * staying in bridge state — and it reappears the moment the bridge reports
     * any activity for it again ("until it speaks again").
     */
    @Test
    fun hidingAnExternalSessionIsLocalAndItReappearsWhenItSpeaksAgain() {
        enqueuePing() // the engine's discovery preflight precedes every pair
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            // An external (hook-created) session: the bridge tags external:true.
            append("id: 1\nevent: session\n")
            append(
                """data: {"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj",""" +
                    """"external":true,"sessionId":"s-ext"}""",
            )
            append("\n\n")
            // A later event for the same session, delayed behind padding so it
            // lands AFTER the hide below: any applied event un-hides it.
            append(":pad\n\n".repeat(400))
            append("id: 2\nevent: pty-output\n")
            append("""data: {"text":"back again\r\n","sessionId":"s-ext"}""")
            append("\n\n")
            append(":tail\n\n".repeat(40))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 100, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        val running = awaitState { it.bridge.sessions["s-ext"]?.external == true }
        assertTrue(
            "an external session is visible in the derived model before hiding",
            HaloModel.from(running).sessions.any { it.id == "s-ext" },
        )

        // Hide it: local only (no request), filtered from the model, but still
        // present in bridge state (only the derived view filters it out).
        val requestsBefore = server.requestCount
        viewModel.hideSession("s-ext")
        val hidden = awaitState { it.hiddenSessions.contains("s-ext") }
        Thread.sleep(300)
        assertEquals("hide sends nothing to the bridge", requestsBefore, server.requestCount)
        assertTrue("bridge state still knows the session", hidden.bridge.sessions.containsKey("s-ext"))
        assertFalse(
            "a hidden external session is filtered out of the derived model",
            HaloModel.from(hidden).sessions.any { it.id == "s-ext" },
        )

        // The delayed event 2 lands: any applied event un-hides the session.
        val reappeared = awaitState(timeoutMs = 60_000) { !it.hiddenSessions.contains("s-ext") }
        assertTrue(
            "an applied event un-hides the session (until it speaks again)",
            HaloModel.from(reappeared).sessions.any { it.id == "s-ext" },
        )
    }

    /**
     * Issue #53 (follow-up to the review): a hidden external session must
     * SURVIVE a bare `session` metadata resync. The bridge re-announces every
     * live slot with `session running` on every reconnect (and on revive /
     * title / project-root rebind); that is not the session "speaking", so it
     * must not un-hide. Otherwise any routine reconnect would defeat an honest
     * hide. The resync here changes folderName so it is observably APPLIED —
     * proving the frame was processed yet did not reveal the session.
     */
    @Test
    fun aHiddenExternalSessionSurvivesABareSessionResync() {
        enqueuePing()
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append("id: 1\nevent: session\n")
            append(
                """data: {"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj",""" +
                    """"external":true,"sessionId":"s-ext"}""",
            )
            append("\n\n")
            // After the hide, the bridge re-announces the SAME live slot (as it
            // does on every reconnect). folderName changes so the frame is
            // observably applied — but it must NOT un-hide the session.
            append(":pad\n\n".repeat(400))
            append("id: 2\nevent: session\n")
            append(
                """data: {"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj-renamed",""" +
                    """"external":true,"sessionId":"s-ext"}""",
            )
            append("\n\n")
            append(":tail\n\n".repeat(40))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 100, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )

        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.bridge.sessions["s-ext"]?.external == true }
        viewModel.hideSession("s-ext")
        awaitState { it.hiddenSessions.contains("s-ext") }

        // Wait for the resync to be APPLIED (folderName updates), then assert it
        // did NOT un-hide: a bare metadata frame is not the session speaking.
        val afterResync = awaitState(timeoutMs = 60_000) {
            it.bridge.sessions["s-ext"]?.folderName == "proj-renamed"
        }
        assertTrue(
            "a bare session resync does not un-hide an honest-hidden session",
            afterResync.hiddenSessions.contains("s-ext"),
        )
        assertFalse(
            "the hidden session stays filtered out of the derived model after a resync",
            HaloModel.from(afterResync).sessions.any { it.id == "s-ext" },
        )
    }
}
