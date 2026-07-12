package dev.claudewatch.wear

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The walking skeleton, CI-scripted: a real bridge runs on the emulator host
 * (reachable via 10.0.2.2) and this test drives the actual Halo app through
 * the full loop — pair with the code scraped from bridge stdout, watch an SSE
 * event render in a session feed, answer blocking permission hooks (single,
 * queued, allow-always) so each unblocks with the chosen decision, answer an
 * AskUserQuestion payload, and spawn + kill a real PTY session from the list.
 *
 * The old control-page command box has no Halo equivalent (commands are
 * dictation-only and the recognizer cannot run headlessly); the ack-gated
 * command POST is covered end-to-end by DictationFlowTest against an
 * on-device MockWebServer, and the question card's free-text answer rides
 * the same recognizer, so here it is answered by option.
 *
 * Instrumentation arguments (see .github/scripts/wear-e2e.sh):
 *   bridgeHost   bridge address as seen from the emulator (default 10.0.2.2)
 *   bridgePort   bridge port scraped from the startup banner (default 7860)
 *   pairingCode  6-digit code scraped from bridge stdout (required)
 */
@RunWith(AndroidJUnit4::class)
class WalkingSkeletonTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val bridgeHost: String = args.getString("bridgeHost") ?: "10.0.2.2"
    private val bridgePort: Int = (args.getString("bridgePort") ?: "7860").toInt()
    private val pairingCode: String = args.getString("pairingCode")
        ?: error("pass the bridge pairing code: -e pairingCode <6 digits>")

    // Plays the role of Claude Code's hook scripts (curl on the host): raw
    // HTTP POSTs to the unauthenticated /hooks/* surface. The permission hook
    // blocks server-side until the watch decides, so its read timeout is long.
    private val hookHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun postHook(path: String, body: JSONObject): Response =
        hookHttp.newCall(
            Request.Builder()
                .url("http://$bridgeHost:$bridgePort$path")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()

    private fun fill(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(value)
    }

    private fun waitForText(tag: String, substring: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(hasTestTag(tag) and hasText(substring, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tagExists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    // Placement-gated existence: HorizontalPager PREFETCHES neighbor pages,
    // composing them unplaced (semantics bounds anchored at origin), so a bare
    // fetchSemanticsNodes existence check can match a node that is not in
    // front. assertIsDisplayed rejects those unplaced nodes.
    private fun tagDisplayed(tag: String): Boolean =
        runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    private fun hasTestTagPrefix(prefix: String) =
        SemanticsMatcher("TestTag starts with $prefix") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }

    /** The session ids of every listed row (list screen only). */
    private fun rowIds(): Set<String> =
        compose.onAllNodes(hasTestTagPrefix("haloRow-")).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("haloRow-") }
            .toSet()

    /** Swipe up from home into the all-sessions list. */
    private fun drillToList() {
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeUp() }
        compose.waitForIdle()
    }

    /**
     * Tap the centerpiece until the waiting item's card opens: the prompt
     * travels bridge → SSE → queue asynchronously, and tapping before it
     * lands is a spec'd no-op, so poll-click instead of a bare wait.
     */
    private fun openFirstWaitingCard(timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (tagExists("haloCard")) {
                armCard()
                return
            }
            if (tagDisplayed("haloCenter")) {
                compose.onNodeWithTag("haloCenter").performClick()
            }
            compose.waitForIdle()
            Thread.sleep(200)
        }
        throw AssertionError("no waiting card opened within ${timeoutMs}ms")
    }

    /** The cards ignore taps for ~400ms after appearing (real uptime). */
    private fun armCard() {
        compose.waitForIdle()
        Thread.sleep(500)
    }

    private fun waitForCardGone() {
        // Covers the 1.4s result flash before the card chains out or exits.
        compose.waitUntil(30_000) { !tagExists("haloCard") }
    }

    @Test
    fun pairStreamApproveQuestionSpawnKill() {
        // --- Pair via manual IP:port + code entry -------------------------
        fill("host", bridgeHost)
        fill("port", bridgePort.toString())
        fill("code", pairingCode)
        compose.onNodeWithTag("pairButton").performScrollTo().performClick()
        // Gate on the SSE stream being open, not on pair success: a hook fired
        // in the pair-to-stream-open window would race the connect.
        waitForText("status", "paired, stream open")

        // --- An SSE event arrives and renders in the session's feed -------
        // The tool-output hook auto-creates a bridge session; its "session"
        // event puts a row on the list and a segment on the home ring.
        val marker = "wear-e2e-marker-${System.currentTimeMillis()}"
        postHook(
            "/hooks/tool-output",
            JSONObject()
                .put("tool_name", "Read")
                .put("cwd", "/tmp/wear-e2e-project")
                .put("tool_output", marker),
        ).use { assertEquals(200, it.code) }
        waitForText("haloCensus", "1 session")
        drillToList()
        val markerSession = rowIds().single()
        compose.onNodeWithTag("haloRow-$markerSession").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText(marker, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        // TimeText tap = jump home, ready for the approval legs.
        compose.onNodeWithTag("haloHome").performClick()
        compose.waitForIdle()

        // --- Concurrent blocking permission hooks (issue #17) -------------
        // Two curl-simulated sessions ask at once: both must queue (neither
        // orphans the other), each must be answerable, and each answer must
        // land on the RENDERED card's permissionId — proven end-to-end by
        // which hook unblocks with which decision.
        val executor = Executors.newFixedThreadPool(2)
        fun permissionHook(body: JSONObject) = executor.submit<Pair<Int, String>> {
            postHook("/hooks/permission", body).use { it.code to (it.body?.string() ?: "") }
        }

        fun decisionOf(hookBody: String): JSONObject =
            JSONObject(hookBody).getJSONObject("hookSpecificOutput").getJSONObject("decision")

        try {
            // Session A asks first: tapping the centerpiece opens ITS card.
            val hookA = permissionHook(
                JSONObject()
                    .put("tool_name", "Bash")
                    .put("session_id", "wear-e2e-session-a")
                    .put("cwd", "/tmp/wear-e2e-a")
                    .put("tool_input", JSONObject().put("command", "rm -rf ./build")),
            )
            openFirstWaitingCard()
            // The card says WHAT is being asked, not just the tool name.
            waitForText("haloTool", "Bash")
            waitForText("haloSummary", "rm -rf ./build")

            // Session B asks while A's card is up: the rendered card stays
            // PINNED (a new arrival must not slide in over a card mid-read)
            // and the queue depth shows.
            val hookB = permissionHook(
                JSONObject()
                    .put("tool_name", "Write")
                    .put("session_id", "wear-e2e-session-b")
                    .put("cwd", "/tmp/wear-e2e-b")
                    .put("tool_input", JSONObject().put("file_path", "/tmp/wear-e2e-b/notes.txt")),
            )
            waitForText("haloWaitingCount", "2 waiting")

            // Neither hook has been answered yet: both must still block.
            Thread.sleep(500)
            assertFalse("hook A must block until a decision arrives", hookA.isDone)
            assertFalse("hook B must block until a decision arrives", hookB.isDone)

            // Deny the RENDERED card (A's Bash — it was pinned first).
            // Ack-gated: the card leaves only on the 2xx ack, then queue
            // chaining slides B's Write card in.
            compose.onNodeWithTag("haloDeny").assertIsDisplayed().performClick()
            val (statusA, bodyA) = hookA.get(30, TimeUnit.SECONDS)
            assertEquals(200, statusA)
            assertEquals(
                "the deny must land on A — the request that was rendered",
                "deny",
                decisionOf(bodyA).getString("behavior"),
            )
            waitForText("haloTool", "Write")
            waitForText("haloSummary", "notes.txt")
            assertFalse("hook B must still be blocked after A's answer", hookB.isDone)

            // Allow the chained card (B's Write): the allow lands on B.
            armCard()
            compose.onNodeWithTag("haloApprove").assertIsDisplayed().performClick()
            val (statusB, bodyB) = hookB.get(30, TimeUnit.SECONDS)
            assertEquals(200, statusB)
            assertEquals(
                "the allow must land on B — the request that was rendered",
                "allow",
                decisionOf(bodyB).getString("behavior"),
            )
            waitForCardGone()

            // --- Allow-always rides the machine-readable behavior field ---
            // The bridge offers allow-always only when the hook supplies
            // permission suggestions to persist; answering with it must make
            // the bridge apply those suggestions (updatedPermissions in the
            // hook decision) so the prompt does not recur.
            val suggestion = JSONObject()
                .put("type", "addRules")
                .put("rules", org.json.JSONArray().put(JSONObject().put("toolName", "Bash")))
            val hookC = permissionHook(
                JSONObject()
                    .put("tool_name", "Bash")
                    .put("session_id", "wear-e2e-session-a")
                    .put("cwd", "/tmp/wear-e2e-a")
                    .put("tool_input", JSONObject().put("command", "npm test"))
                    .put("permission_suggestions", org.json.JSONArray().put(suggestion)),
            )
            openFirstWaitingCard()
            waitForText("haloSummary", "npm test")
            compose.onNodeWithTag("haloAlwaysAllow").performScrollTo().performClick()
            val (statusC, bodyC) = hookC.get(30, TimeUnit.SECONDS)
            assertEquals(200, statusC)
            val decisionC = decisionOf(bodyC)
            // The bridge maps the behavior-based allow-always answer onto the
            // hook contract: allow + the persisted suggestions.
            assertEquals("allow", decisionC.getString("behavior"))
            assertEquals(
                "allow-always must persist the hook's permission suggestions",
                1,
                decisionC.getJSONArray("updatedPermissions").length(),
            )
            waitForCardGone()

            // --- AskUserQuestion: every question answered, buffered submit --
            // A multi-question payload walks ALL questions on the question
            // card (the legacy client answered only the first); the answers
            // are buffered and POSTed together as a positional array in
            // question order; the bridge zips it with the questions and the
            // blocked hook unblocks with BOTH answers keyed by question text
            // (updatedInput.answers — collectAskUserQuestionAnswers in
            // skill/bridge/hooks.js).
            val questions = org.json.JSONArray()
                .put(
                    JSONObject()
                        .put("question", "Which database should the service use?")
                        .put("header", "Database")
                        .put("multiSelect", false)
                        .put(
                            "options",
                            org.json.JSONArray()
                                .put(JSONObject().put("label", "PostgreSQL"))
                                .put(JSONObject().put("label", "SQLite")),
                        ),
                )
                .put(
                    JSONObject()
                        .put("question", "What should the service be called?")
                        .put("header", "Name")
                        .put("multiSelect", false)
                        .put("options", org.json.JSONArray().put(JSONObject().put("label", "api-server"))),
                )
            val hookQ = permissionHook(
                JSONObject()
                    .put("tool_name", "AskUserQuestion")
                    .put("session_id", "wear-e2e-session-a")
                    .put("cwd", "/tmp/wear-e2e-a")
                    .put("tool_input", JSONObject().put("questions", questions)),
            )
            openFirstWaitingCard()
            waitForText("haloQuestionText", "Which database should the service use?")

            // Answer question 0; the submit is buffered until EVERY question
            // has an answer, so the hook must still block.
            compose.onNodeWithTag("haloQOption-0-SQLite").performScrollTo().performClick()
            waitForText("haloQuestionText", "What should the service be called?")
            Thread.sleep(500)
            assertFalse("hook must block until every question is answered", hookQ.isDone)

            // The last answer submits both positionally.
            compose.onNodeWithTag("haloQOption-1-api-server").performScrollTo().performClick()
            val (statusQ, bodyQ) = hookQ.get(30, TimeUnit.SECONDS)
            assertEquals(200, statusQ)
            val decisionQ = decisionOf(bodyQ)
            assertEquals("allow", decisionQ.getString("behavior"))
            val answersQ = decisionQ.getJSONObject("updatedInput").getJSONObject("answers")
            assertEquals(
                "the first answer must land on its question",
                "SQLite",
                answersQ.getString("Which database should the service use?"),
            )
            assertEquals(
                "the second answer must land on its question",
                "api-server",
                answersQ.getString("What should the service be called?"),
            )
            waitForCardGone()
        } finally {
            executor.shutdownNow()
        }

        // --- Spawn a session from the list, watch its feed, kill it -------
        // The real bridge PTY-spawns the stubbed `claude` binary (see
        // .github/scripts/wear-e2e.sh); its ready line arrives as pty-output.
        drillToList()
        val before = rowIds()
        compose.onNodeWithTag("haloSpawn").performScrollTo().performClick()
        compose.waitUntil(30_000) { (rowIds() - before).isNotEmpty() }
        val spawnedId = (rowIds() - before).single()

        // The spawned PTY's stub output reaches THIS session's feed. Scope
        // the match to the feed subtree so text from another (prefetched or
        // composed) surface can't satisfy it.
        compose.onNodeWithTag("haloRow-$spawnedId").performScrollTo().performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodes(
                hasText("stub-claude", substring = true) and
                    hasAnyAncestor(hasTestTag("haloFeed-$spawnedId")),
            ).fetchSemanticsNodes().isNotEmpty()
        }

        // Back to the list; the row's quick-action strip (horizontal swipe)
        // carries close, which kills the session via /v1/command. The bridge
        // pushes `session ended killed:true`, which prunes the row.
        compose.onNodeWithTag("haloFeed-$spawnedId").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloRow-$spawnedId").performScrollTo()
            .performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNode(
            hasTestTag("haloRowClose") and hasAnyAncestor(hasTestTag("haloRow-$spawnedId")),
        ).assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { !tagExists("haloRow-$spawnedId") }
        assertTrue("the list must stay usable after the kill", tagExists("haloSpawn"))
    }
}
