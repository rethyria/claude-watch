package dev.claudewatch.wear

import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.test.swipeLeft
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
 * (reachable via 10.0.2.2) and this test drives the actual app UI through the
 * full loop — pair with the code scraped from bridge stdout, watch an SSE
 * event render as raw text, send a session-id-scoped command, and answer a
 * blocking permission hook so it unblocks with the chosen decision.
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

    private fun textOf(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun tagExists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    // Placement-gated existence: HorizontalPager PREFETCHES neighbor pages,
    // composing them unplaced (semantics bounds anchored at origin), so a bare
    // fetchSemanticsNodes existence check matches a page one swipe before it
    // is actually in front. assertIsDisplayed rejects those unplaced nodes.
    private fun tagDisplayed(tag: String): Boolean =
        runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    @Test
    fun pairStreamCommandApprove() {
        // --- Pair via manual IP:port + code entry -------------------------
        fill("host", bridgeHost)
        fill("port", bridgePort.toString())
        fill("code", pairingCode)
        compose.onNodeWithTag("pairButton").performScrollTo().performClick()
        // Gate on the SSE stream being open, not on pair success: a hook fired
        // in the pair-to-stream-open window would race the connect. (The app
        // also requests a full replay on first connect, but the stream-open
        // signal is the honest go-signal for the steps below.)
        waitForText("status", "paired, stream open")

        // --- An SSE event arrives and renders as raw text -----------------
        // The tool-output hook also auto-creates a bridge session, whose
        // "session" event gives the app the session id used below.
        val marker = "wear-e2e-marker-${System.currentTimeMillis()}"
        postHook(
            "/hooks/tool-output",
            JSONObject()
                .put("tool_name", "Read")
                .put("cwd", "/tmp/wear-e2e-project")
                .put("tool_output", marker),
        ).use { assertEquals(200, it.code) }
        waitForText("eventLog", marker)
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("sessionId") and hasText("session:none"))
                .fetchSemanticsNodes().isEmpty()
        }

        // --- A session-id-scoped text command reaches the bridge (2xx) ----
        fill("commandInput", "say hello from the watch")
        compose.onNodeWithTag("sendButton").performScrollTo().performClick()
        waitForText("commandResult", "command:200")

        // --- A blocking permission hook is answered from the watch --------
        val executor = Executors.newSingleThreadExecutor()
        val hookFuture = executor.submit<Pair<Int, String>> {
            postHook(
                "/hooks/permission",
                JSONObject()
                    .put("tool_name", "Bash")
                    .put("cwd", "/tmp/wear-e2e-project")
                    .put("tool_input", JSONObject().put("command", "rm -rf ./build")),
            ).use { it.code to (it.body?.string() ?: "") }
        }
        try {
            waitForText("permissionTool", "Bash")
            // The prompt is on screen but nothing has answered it: the hook
            // must still be blocked.
            Thread.sleep(500)
            assertFalse("permission hook must block until a decision arrives", hookFuture.isDone)

            compose.onNodeWithTag("allowButton").performScrollTo().performClick()
            waitForText("decisionResult", "decision:200")

            val (hookStatus, hookBody) = hookFuture.get(30, TimeUnit.SECONDS)
            assertEquals(200, hookStatus)
            val decision = JSONObject(hookBody)
                .getJSONObject("hookSpecificOutput")
                .getJSONObject("decision")
            assertEquals("allow", decision.getString("behavior"))
            assertTrue(
                "permission card should clear after an accepted decision",
                compose.onAllNodes(hasTestTag("permissionTool")).fetchSemanticsNodes().isEmpty(),
            )
        } finally {
            executor.shutdownNow()
        }

        // --- Spawn a session, page over to its live terminal, kill it ----
        // The real bridge PTY-spawns the stubbed `claude` binary (see
        // .github/scripts/wear-e2e.sh); its ready line arrives as pty-output.
        val hookSessionText = textOf("sessionId")
        compose.onNodeWithTag("spawnClaudeButton").performScrollTo().performClick()
        waitForText("sessionActionResult", "spawn:200")
        compose.waitUntil(30_000) { textOf("sessionId") != hookSessionText }
        val spawnedId = textOf("sessionId").removePrefix("session:")

        // Pager navigation over live sessions: swipe until the spawned
        // session's terminal page is in FRONT (control page + the
        // hook-created session's page come first). Gate on placement, not
        // existence — the pager prefetches the neighbor page, so a bare
        // existence check would stop one swipe early and the kill click
        // below would be injected at the unplaced node's origin bounds.
        for (unused in 0 until 4) {
            if (tagDisplayed("terminal-$spawnedId")) break
            compose.onNodeWithTag("sessionPager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }
        assertTrue(
            "spawned session's terminal page must be fronted by swiping",
            tagDisplayed("terminal-$spawnedId"),
        )
        // The spawned PTY's stub output reached THIS page as terminal lines.
        // Scope the match to the spawned session's terminal subtree: pager
        // prefetch composes neighbor pages too, so an unscoped hasText could
        // match text that never rendered on the fronted page.
        compose.waitUntil(60_000) {
            compose.onAllNodes(
                hasText("stub-claude", substring = true) and
                    hasAnyAncestor(hasTestTag("terminal-$spawnedId")),
            ).fetchSemanticsNodes().isNotEmpty()
        }

        // Kill it from its page header; the bridge answers the /v1/command
        // kill and pushes `session ended killed:true`, which prunes the page.
        // assertIsDisplayed first: a click on an unplaced node would silently
        // land at (0,0) and hit nothing.
        compose.onNodeWithTag("kill-$spawnedId").assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { !tagExists("terminal-$spawnedId") }
    }
}
