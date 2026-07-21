package dev.claudewatch.wear

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import dev.claudewatch.wear.ui.halo.PairFieldInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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

    // Pairing flips `paired`, which triggers the POST_NOTIFICATIONS ask on
    // API 33+ (issue #24) — a SYSTEM dialog that would cover the compose tree
    // and swallow every interaction below. Pre-granting keeps the run
    // dialog-free (WatchApp only launches the request when not yet granted).
    @get:Rule
    val notificationPermission: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

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

    // Arm the pairing-field seam before the UI is touched: each field now
    // launches the Wear RemoteInput activity (no headless IME in this
    // harness), so a field tap short-circuits to its canned value instead.
    @Before
    fun armPairFieldSeam() {
        PairFieldInput.override = { label ->
            when (label) {
                "host" -> bridgeHost
                "port" -> bridgePort.toString()
                "code" -> pairingCode
                else -> null
            }
        }
    }

    // Process-global seam: clear it so it never leaks into another test class
    // in the same instrumentation run.
    @After
    fun clearPairFieldSeam() {
        PairFieldInput.override = null
    }

    // "Filling" a pairing field is now just tapping it: the armed seam
    // supplies the value the RemoteInput activity would have returned.
    private fun fill(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun waitForText(tag: String, substring: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) {
            // Unmerged tree: some tagged Texts (e.g. haloCensus) sit inside a
            // clickable that mergeDescendants, which absorbs their testTag out
            // of the merged tree — they are only findable unmerged.
            compose.onAllNodes(
                hasTestTag(tag) and hasText(substring, substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Gate on the app leaving the offline screen for the online home — Halo's
     * observable "stream open" signal. The status line only exists on the
     * offline/pairing screen (the home pager underneath it has none) and that
     * screen is torn down the instant the stream connects, so `status` can
     * never read "paired, stream open"; its DISAPPEARANCE is the signal that
     * the app paired and reached the connecting/connected home. The pager is
     * the always-composed base layer, so it can't be used directly (it reads
     * as displayed under the offline overlay). The bridge replays its buffered
     * backlog and a running-session snapshot on connect (transport-sse.js), so
     * a hook fired in the pair→connect window is still delivered.
     */
    private fun waitForOnlineHome(timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { !tagExists("status") }
    }

    private fun tagExists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /**
     * Bring a lazy session-list item (a row, or the trailing spawn row) into
     * view. The list is a ScalingLazyColumn, which only composes items near
     * the viewport, so an offscreen item is not a node yet — performScrollTo
     * (needs an existing node) can't reach it; performScrollToNode on the
     * scrollable scrolls until it composes.
     */
    private fun scrollListTo(tag: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
    }

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

    /** The session ids of the rows CURRENTLY composed (the lazy viewport). */
    private fun rowIds(): Set<String> =
        compose.onAllNodes(hasTestTagPrefix("haloRow-")).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("haloRow-") }
            .toSet()

    /**
     * Every session id in the list, not just the composed viewport. The list
     * is a ScalingLazyColumn — [rowIds] only sees the rows near the viewport,
     * so diffing two viewport reads at different scroll offsets is meaningless
     * (a scroll alone changes the set). Page top→bottom by item index, ending
     * once the trailing spawn row composes, unioning the rows revealed.
     */
    private fun allRowIds(): Set<String> {
        val list = compose.onNode(hasScrollAction())
        val ids = rowIds().toMutableSet()
        var i = 1
        while (i <= 60 && !tagExists("haloSpawn")) {
            val scrolled = runCatching {
                list.performScrollToIndex(i)
                compose.waitForIdle()
            }.isSuccess
            if (!scrolled) break
            ids += rowIds()
            i++
        }
        return ids
    }

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

    /**
     * Run [command] through UiAutomation and read the output FULLY. The pfd
     * must be drained to the end: dumpsys writes into a pipe, and abandoning
     * it early both truncates the output and can block the shell side.
     */
    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    /**
     * The BridgeSessionService is alive AND foregrounded (issue #24), per the
     * activity manager's own books: a ServiceRecord exists for it and it is
     * flagged isForeground — dumpsys, not app-side APIs, because the whole
     * point is what the SYSTEM believes survives backgrounding.
     */
    private fun serviceIsForeground(): Boolean {
        val dump = shell("dumpsys activity services dev.claudewatch.wear/.BridgeSessionService")
        return dump.contains("ServiceRecord") && dump.contains("isForeground=true")
    }

    private fun awaitServiceForeground(timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (serviceIsForeground()) return
            Thread.sleep(300)
        }
        throw AssertionError("BridgeSessionService not foregrounded within ${timeoutMs}ms")
    }

    @Test
    fun pairStreamApproveQuestionSpawnKill() {
        // --- Pair via manual IP:port + code entry -------------------------
        // The offline screen now fronts two paths with a Choose pane (issue #23
        // follow-up): "Manual" (host/port/code) and "Discover" (code-less list).
        // The manual host/port/code form lives behind the "Manual" chip, so the
        // pair leg taps through to it. The credential store also outlives
        // reinstalls, so a previous run can leave the app PAIRED (to a long-gone
        // bridge): then the "re-pair watch" chip fronts the Choose pane instead.
        // waitUntil, not waitForIdle: on a cold-booted emulator the activity can
        // still be inflating when the rule returns, and the first fill() raced
        // it into a missing-'host'-node failure once — wait for the offline
        // screen's first interactive node before touching anything.
        compose.waitUntil(30_000) {
            tagExists("host") || tagExists("repairButton") || tagExists("manualButton")
        }
        // Paired-but-offline: reveal the Choose pane first.
        if (tagExists("repairButton")) {
            compose.onNodeWithTag("repairButton").performScrollTo().performClick()
            compose.waitUntil(5_000) { tagExists("manualButton") || tagExists("host") }
        }
        // Choose pane: open the Manual form.
        if (tagExists("manualButton")) {
            compose.onNodeWithTag("manualButton").performScrollTo().performClick()
            compose.waitUntil(5_000) { tagExists("host") }
        }
        fill("host")
        fill("port")
        fill("code")
        compose.onNodeWithTag("pairButton").performScrollTo().performClick()
        // Gate on the app reaching the online home (the stream is open, or
        // connecting with backlog replay to follow), not on a status string:
        // Halo tears the status line down the moment it leaves the offline
        // screen, so it never reads "paired, stream open".
        waitForOnlineHome()

        // --- Foreground service + chip + backgrounding (issue #24) --------
        // Pairing flipped `paired` true, which starts BridgeSessionService
        // from the RESUMED activity; the system must record it as a
        // FOREGROUND service (the process-lifetime guarantee, not a plain
        // started service Doze can shrug off).
        awaitServiceForeground()
        // The FGS notification exists, on our channel — the OngoingActivity
        // chip rides this notification, so its presence is the chip's.
        val notificationDump = shell("dumpsys notification --noredact")
        assertTrue(
            "the FGS notification (channel ${BridgeSessionService.CHANNEL_ID}) must exist",
            notificationDump.contains(BridgeSessionService.CHANNEL_ID),
        )
        // Acceptance 1: the connection survives app backgrounding. HOME the
        // app (keyevent 3), give the system a moment to settle, and the
        // service — and with it the SSE stream — must still be foreground.
        shell("input keyevent 3")
        Thread.sleep(2_000)
        assertTrue(
            "the service must survive app backgrounding",
            serviceIsForeground(),
        )
        // Bring the activity back for the rest of the flow and wait until it
        // is actually RESUMED with the compose tree answering again — the
        // remaining legs (approve/question/spawn/kill) drive that tree.
        shell("am start -n dev.claudewatch.wear/.MainActivity")
        // NOT compose.activity.lifecycle: on a memory-tight image the system
        // may have DESTROYED the backgrounded activity, making `am start`
        // create a fresh instance the rule's activity reference knows nothing
        // about — that wait would poll a corpse for 30s and fail even though
        // the service (the thing under test) survived. The lifecycle monitor
        // registry tracks whichever MainActivity instance is ACTUALLY
        // resumed, old or new; compose node queries find the new tree either
        // way (root discovery is process-global, not scenario-scoped).
        compose.waitUntil(30_000) {
            var resumed = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { it is MainActivity }
            }
            resumed
        }
        compose.waitForIdle()
        waitForOnlineHome()

        // --- An SSE event arrives and renders in the session's feed -------
        // The tool-output hook auto-creates a bridge session; its "session"
        // event puts a row on the list and a segment on the home ring. The
        // marker rides the Bash COMMAND: the feed renders a Bash tool-output
        // as its `$ <command>` line, whereas Read/Edit/Write render only the
        // filename and drop the tool_output (ToolOutputFormatter, watchOS
        // parity), so a marker in tool_output would never surface.
        val marker = "wear-e2e-marker-${System.currentTimeMillis()}"
        postHook(
            "/hooks/tool-output",
            JSONObject()
                .put("tool_name", "Bash")
                .put("cwd", "/tmp/wear-e2e-project")
                .put("tool_input", JSONObject().put("command", marker)),
        ).use { assertEquals(200, it.code) }
        waitForText("haloCensus", "1 session")
        drillToList()
        val markerSession = rowIds().single()
        compose.onNodeWithTag("haloRow-$markerSession").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText(marker, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        // Back home the way a user does: swipe down twice (feed → list →
        // page). The clock is deliberately not a tap target anymore.
        compose.onNodeWithTag("haloFeed-$markerSession").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        waitForText("haloCensus", "session")

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
        // Enumerate the WHOLE list, not the viewport: the spawn adds one
        // session and we must tell its row from every pre-existing one, even
        // those the lazy list hasn't composed yet.
        val before = allRowIds()
        scrollListTo("haloSpawn")
        compose.onNodeWithTag("haloSpawn").performClick()
        // Issue #56: the spawn row opens the TARGET picker instead of firing
        // blind. The skeleton takes the "no project" home entry — the "~"
        // sentinel the bridge resolves to its own user's home, always a valid
        // spawn directory on the real bridge under test. The picker overlays
        // the still-composed session list, so its scrollable must be
        // addressed BY ANCESTOR (a bare hasScrollAction() now matches both);
        // the home entry trails the per-project entries and may need the
        // scroll to compose (lazy list).
        compose.waitForIdle()
        compose.onNode(
            hasScrollAction() and hasAnyAncestor(hasTestTag("haloSpawnPicker")),
        ).performScrollToNode(hasTestTag("haloSpawnPickHome"))
        compose.onNodeWithTag("haloSpawnPickHome").performClick()
        compose.waitForIdle()
        val deadline = System.currentTimeMillis() + 30_000
        var found: String? = null
        while (System.currentTimeMillis() < deadline && found == null) {
            val fresh = allRowIds() - before
            when {
                fresh.size == 1 -> found = fresh.single()
                fresh.size > 1 -> throw AssertionError("spawn added more than one row: $fresh")
                else -> {
                    compose.waitForIdle()
                    Thread.sleep(200)
                }
            }
        }
        val spawnedId = found ?: throw AssertionError("spawned session row never appeared")

        // The spawned PTY's stub output reaches THIS session's feed. Scope
        // the match to the feed subtree so text from another (prefetched or
        // composed) surface can't satisfy it.
        scrollListTo("haloRow-$spawnedId")
        compose.onNodeWithTag("haloRow-$spawnedId").performClick()
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
        scrollListTo("haloRow-$spawnedId")
        compose.onNodeWithTag("haloRow-$spawnedId").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNode(
            hasTestTag("haloRowClose") and hasAnyAncestor(hasTestTag("haloRow-$spawnedId")),
        ).assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { !tagExists("haloRow-$spawnedId") }
        assertTrue("the list must stay usable after the kill", tagExists("haloSpawn"))
    }
}
