package dev.claudewatch.wear

import dev.claudewatch.shared.terminal.TerminalLineType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
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
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        server.enqueue(MockResponse().setBody("""{"ok":true,"sessionId":"s-new","agent":"claude"}"""))
        viewModel.spawnSession("claude")
        val spawnRequest = server.takeRequest(10, TimeUnit.SECONDS)
            ?: throw AssertionError("no spawn request")
        assertEquals("/v1/command", spawnRequest.path)
        val spawnBody = JSONObject(spawnRequest.body.readUtf8())
        assertEquals("claude", spawnBody.getString("spawn"))
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
    }

    /**
     * The thinking cursor: sending a command echoes `> text` into the target
     * session's terminal and raises `thinking` immediately (before the POST
     * resolves); the session's next SSE output clears it and lands after the
     * echo.
     */
    @Test
    fun commandSendRaisesTheThinkingCursorAndTheNextOutputClearsIt() {
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        // The session announcement arrives promptly; the pty-output sits
        // behind a large throttled pad so it lands seconds AFTER the command
        // below is sent (generous margin: the host may be under load).
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

        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.sendCommand("say hello")

        // The echo is synchronous: cursor up and `> command` in the terminal
        // before any response (let alone output) has arrived.
        val echoed = viewModel.state.value.bridge.sessions.getValue("s-1")
        assertTrue("thinking cursor must raise on send", echoed.thinking)
        val echoLine = echoed.terminal.items.last()
        assertEquals("> say hello", echoLine.text)
        assertEquals(TerminalLineType.COMMAND, echoLine.type)

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
}
