package dev.claudewatch.wear

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
import java.util.concurrent.TimeUnit

/**
 * The walking skeleton, CI-scripted: a real bridge runs on the emulator host
 * (reachable via 10.0.2.2) and this test drives the actual Halo app through
 * the full loop — pair with the code scraped from bridge stdout, watch an SSE
 * event render in a session feed, answer queued permission prompts (single,
 * queued, allow-always) so each decision rides back to the agent, answer an
 * AskUserQuestion card, and spawn a claude session from the session pager
 * (the v2 one-session-per-screen list) — born in the harness's fake Zed fork
 * over the bridge's ACP inbox (issue #107) — then KILL it for real: the close
 * frame of issue #88 travels wrist → bridge → fork, and the fork's own
 * deregister is what takes the card off the pager. Since #114 a pager card's
 * tap opens the session-actions MENU: the feed sits behind its "open feed"
 * row, and the kill fires from the menu's close row.
 *
 * Sessions are fabricated over the ACP wire (#87 retired the hook channel):
 * this test plays a second Zed-fork against the throwaway bridge — its own
 * /acp/inbox connection, registers, turns, permission and input-request
 * frames — mirroring the adapter's wire moves the bridge and adapter suites
 * pin from both ends (skill/bridge/test/acp.test.js, skill/acp-agent). The
 * wrist behavior asserted is unchanged; only who fabricates changed.
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

    // Plays the role of the Zed-launched claude-agent-acp fork: the
    // loopback-only /acp/* uplink. The emulator's user-mode NAT egresses
    // every guest connection to 10.0.2.2 from the HOST's own loopback — which
    // is why the bridge's loopback gate passes — so an on-device test can
    // hold the fork's role end to end: register fabricated sessions, drive
    // turns, raise permission/question cards with the adapter's own frames,
    // and observe the decision frames the bridge writes back down the held
    // /acp/inbox SSE.
    private val acpHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // One connection id per run, like the real fork (HttpBridgeChannel).
    private val acpConnection = "wear-e2e-test-${System.currentTimeMillis()}"

    private fun postAcp(path: String, body: JSONObject): Response =
        acpHttp.newCall(
            Request.Builder()
                .url("http://$bridgeHost:$bridgePort$path")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()

    private fun acpRegister(sessionId: String, cwd: String) {
        postAcp(
            "/acp/register",
            JSONObject()
                .put("connection", acpConnection)
                .put("sessionId", sessionId)
                .put("sdkSessionId", sessionId)
                .put("cwd", cwd)
                .put("active", false),
        ).use { assertEquals(200, it.code) }
    }

    private fun acpUpdate(sessionId: String, kind: String, payload: JSONObject) {
        postAcp(
            "/acp/update",
            JSONObject()
                .put("connection", acpConnection)
                .put("sessionId", sessionId)
                .put("kind", kind)
                .put("payload", payload),
        ).use { assertEquals(200, it.code) }
    }

    /** One scripted assistant turn, the adapter's own frame order. Sequential
     *  on purpose: the bridge clears its prose buffer at a turn start and
     *  flushes the coalesced `message` at the end, so an out-of-order chunk
     *  would silently vanish instead of failing the leg. */
    private fun speak(sessionId: String, text: String) {
        acpUpdate(sessionId, "turn", JSONObject().put("phase", "start"))
        acpUpdate(
            sessionId,
            "session_update",
            JSONObject()
                .put("sessionId", sessionId)
                .put(
                    "update",
                    JSONObject()
                        .put("sessionUpdate", "agent_message_chunk")
                        .put("content", JSONObject().put("type", "text").put("text", text)),
                ),
        )
        acpUpdate(sessionId, "turn", JSONObject().put("phase", "end").put("stopReason", "end_turn"))
    }

    /** Raise a permission the way the adapter's forwardPermissionRequest does:
     *  a teed RequestPermissionRequest with the SDK's canonical option trio
     *  (allow_always / allow / reject — acp-agent.ts requestPermission). */
    private fun raisePermission(sessionId: String, toolCallId: String, title: String, rawInput: JSONObject) {
        acpUpdate(
            sessionId,
            "permission",
            JSONObject()
                .put("sessionId", sessionId)
                .put(
                    "toolCall",
                    JSONObject().put("toolCallId", toolCallId).put("title", title).put("rawInput", rawInput),
                )
                .put(
                    "options",
                    org.json.JSONArray()
                        .put(JSONObject().put("optionId", "allow_always").put("name", "Always Allow").put("kind", "allow_always"))
                        .put(JSONObject().put("optionId", "allow").put("name", "Allow").put("kind", "allow_once"))
                        .put(JSONObject().put("optionId", "reject").put("name", "Reject").put("kind", "reject_once")),
                ),
        )
    }

    /**
     * The held /acp/inbox SSE for [acpConnection]: the channel the bridge
     * writes `permission-decision` / `input-decision` frames down — the ONLY
     * place a wrist answer is observable to the agent side, so the "the
     * decision reached the agent" assertions read from here. A background
     * thread accumulates frames; waits poll the list.
     */
    private inner class AcpInbox : AutoCloseable {
        private val frames = mutableListOf<Pair<String, JSONObject>>()

        // No read timeout: the stream idles between frames (the bridge's 15s
        // heartbeats are comment lines and keep the socket honest).
        private val call = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
            .newCall(
                Request.Builder()
                    .url("http://$bridgeHost:$bridgePort/acp/inbox?connection=$acpConnection")
                    .header("Accept", "text/event-stream")
                    .build(),
            )

        private val thread = Thread {
            runCatching {
                call.execute().use { response ->
                    assertEquals(200, response.code)
                    val source = response.body!!.source()
                    var event = "message"
                    val data = StringBuilder()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> data.append(line.removePrefix("data:").removePrefix(" "))
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) {
                                    runCatching { JSONObject(data.toString()) }.getOrNull()?.let {
                                        synchronized(frames) { frames.add(event to it) }
                                    }
                                }
                                event = "message"
                                data.setLength(0)
                            }
                        }
                    }
                }
            } // A canceled call throws here; close() is the intended exit.
        }.apply { start() }

        fun hasFrame(event: String, predicate: (JSONObject) -> Boolean = { true }): Boolean =
            synchronized(frames) { frames.any { it.first == event && predicate(it.second) } }

        fun awaitFrame(event: String, timeoutMs: Long = 30_000, predicate: (JSONObject) -> Boolean = { true }): JSONObject {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                synchronized(frames) { frames.firstOrNull { it.first == event && predicate(it.second) } }
                    ?.let { return it.second }
                Thread.sleep(100)
            }
            throw AssertionError("no '$event' frame arrived on the ACP inbox within ${timeoutMs}ms")
        }

        override fun close() {
            call.cancel()
            thread.join(2_000)
        }
    }

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
     * an ACP frame teed in the pair→connect window is still delivered.
     */
    private fun waitForOnlineHome(timeoutMs: Long = 30_000) {
        try {
            compose.waitUntil(timeoutMs) { !tagExists("status") }
        } catch (e: ComposeTimeoutException) {
            // The app writes no logcat, so a bare timeout here is undebuggable
            // once the runner's emulator is gone — and the bridge log can only
            // prove what never arrived. The offline screen's own status line
            // carries the engine's verdict (unreachable, 401, proto mismatch),
            // so make the failure quote it.
            val status = runCatching {
                compose.onNodeWithTag("status").fetchSemanticsNode()
                    .config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString(" ") { it.text }
            }.getOrNull()
            throw AssertionError(
                "never reached the online home; the offline screen still reads: ${status ?: "<no status text>"}",
                e,
            )
        }
    }

    private fun tagExists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    // Placement-gated existence: during transitions AnimatedContent composes
    // BOTH the entering and exiting layers (and can hold an exiting node
    // unplaced), so a bare fetchSemanticsNodes existence check can match a
    // node that is not in front. assertIsDisplayed rejects those.
    private fun tagDisplayed(tag: String): Boolean =
        runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    private fun hasTestTagPrefix(prefix: String) =
        SemanticsMatcher("TestTag starts with $prefix") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }

    /** The session ids of the pager cards CURRENTLY composed — at rest that
     *  is exactly one (one session per screen); mid-step it can briefly be
     *  the exiting card too, which the enumeration below tolerates. */
    private fun pagerCardIds(): Set<String> =
        compose.onAllNodes(hasTestTagPrefix("haloPagerCard-")).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("haloPagerCard-") }
            .toSet()

    /**
     * Every session id in the pager, enumerated by STEPPING: one session per
     * screen now, so walking ›-wards from the current card to the trailing
     * spawn card — the All scope's true end — visits every slot. Chevron
     * clicks, not swipes, so the card-tap swipe guard never arms. Leaves the
     * pager parked on the spawn card.
     */
    private fun allPagerIds(): Set<String> {
        val ids = mutableSetOf<String>()
        var steps = 0
        while (steps <= 60 && !tagDisplayed("haloSpawn")) {
            ids += pagerCardIds()
            compose.onNodeWithTag("haloNext").performClick()
            compose.waitForIdle()
            steps++
        }
        return ids
    }

    /** Home → the All-scope session pager: the face tap, v3's ONE list entry
     *  (the centerpiece carries a 300ms swipe-suppression guard on real
     *  uptime, waited out because the page-walk helpers swipe). */
    private fun drillToList() {
        Thread.sleep(350)
        compose.onNodeWithTag("haloCenter").performClick()
        compose.waitForIdle()
    }

    /** Pager → home the v3 way: swipe right walks card by card to the
     *  scope's first slot and then out (the app-wide swipe-down back died in
     *  the #109 vertical purge). Bounded like the enumeration walks. */
    private fun pagerBackToHome() {
        var swipes = 0
        // The centerpiece exists only on the pages (never at LIST depth), so
        // its arrival IS the landed-home signal — findable in the merged
        // tree, unlike the census text it swallows.
        while (swipes <= 70 && !tagDisplayed("haloCenter")) {
            compose.onNodeWithTag("haloRoot").performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(width / 12f, 0f), delayMillis = 16L) }
                up()
            }
            compose.waitForIdle()
            swipes++
        }
    }

    /** A pager card's tap opens the session-actions MENU (issue #114); the
     *  feed lives behind its "open feed" row — this walks the pass-through. */
    private fun openFeedFromCard(sessionId: String) {
        compose.onNodeWithTag("haloPagerCard-$sessionId").performClick()
        compose.waitUntil(10_000) { tagExists("haloMenuFeed") }
        compose.onNodeWithTag("haloMenuFeed").performClick()
        compose.waitForIdle()
    }

    /** Step the pager to [sessionId]'s card, from wherever it is parked: back
     *  out to the page and drill again (re-resolving to the first slot), then
     *  walk ›-wards until the card is in front. */
    private fun openPagerCard(sessionId: String) {
        pagerBackToHome()
        drillToList()
        var steps = 0
        while (steps <= 60 && !tagDisplayed("haloPagerCard-$sessionId")) {
            compose.onNodeWithTag("haloNext").performClick()
            compose.waitForIdle()
            steps++
        }
        compose.onNodeWithTag("haloPagerCard-$sessionId").assertIsDisplayed()
    }

    /**
     * Tap the Answer pill until the waiting item's card opens: the prompt
     * travels bridge → SSE → queue asynchronously and the pill only EXISTS
     * once the scope has a waiting session (v2 shell — the centerpiece tap
     * opens the session list now), so poll for it instead of a bare wait.
     */
    private fun openFirstWaitingCard(timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (tagExists("haloCard")) {
                armCard()
                return
            }
            if (tagDisplayed("haloAnswerPill")) {
                compose.onNodeWithTag("haloAnswerPill").performClick()
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
        // The fabricated register announces a session (a row on the list, a
        // segment on the home ring); one scripted turn then speaks the marker
        // as assistant prose. An ACP feed's content IS the coalesced prose
        // the bridge flushes as a `message` event at turn end — the hook-era
        // `$ <command>` tool-output lines died with the hook channel, and the
        // per-tool rendering they exercised stays pinned by the
        // ToolOutputFormatter unit tests.
        val inbox = AcpInbox()
        val marker = "wear-e2e-marker-${System.currentTimeMillis()}"
        acpRegister("wear-e2e-project-session", "/tmp/wear-e2e-project")
        speak("wear-e2e-project-session", marker)
        waitForText("haloCensus", "1 session")
        drillToList()
        // The lone session is the pager's resolved selection: its card is up
        // — through the actions menu into its feed (#114).
        val markerSession = pagerCardIds().single()
        openFeedFromCard(markerSession)
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText(marker, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        // Back home the way a v3 user does: swipe right off the feed onto
        // the pager, then swipe-right-walk out (the swipe-down backs died in
        // the #109 purge; the clock is deliberately not a tap target).
        compose.onNodeWithTag("haloFeed-$markerSession").performTouchInput { swipeRight() }
        compose.waitForIdle()
        pagerBackToHome()
        waitForText("haloCensus", "session")

        // --- Concurrent permission prompts (issue #17, on the ACP wire) ---
        // Two sessions ask at once: both must queue (neither orphans the
        // other), each must be answerable, and each answer must ride back to
        // the agent as a permission-decision frame naming the RENDERED
        // card's tool call — the frame is the ACP era's "which decision
        // landed where" proof (the hook-era blocking-response body is gone).
        acpRegister("wear-e2e-session-a", "/tmp/wear-e2e-a")
        acpRegister("wear-e2e-session-b", "/tmp/wear-e2e-b")

        // Session A asks first: the Answer pill opens ITS card.
        raisePermission("wear-e2e-session-a", "tc-a", "Bash", JSONObject().put("command", "rm -rf ./build"))
        openFirstWaitingCard()
        // The card says WHAT is being asked, not just the tool name.
        waitForText("haloTool", "Bash")
        waitForText("haloSummary", "rm -rf ./build")

        // Session B asks while A's card is up: the rendered card stays
        // PINNED (a new arrival must not slide in over a card mid-read)
        // and the queue depth shows.
        raisePermission("wear-e2e-session-b", "tc-b", "Write", JSONObject().put("file_path", "/tmp/wear-e2e-b/notes.txt"))
        waitForText("haloWaitingCount", "2 waiting")

        // Nobody has answered yet: no decision may have reached the agent.
        Thread.sleep(500)
        assertFalse(
            "no decision frame may ride the inbox before the wrist answers",
            inbox.hasFrame("permission-decision"),
        )

        // Deny the RENDERED card (A's Bash — it was pinned first).
        // Ack-gated: the card leaves only on the 2xx ack, then queue
        // chaining slides B's Write card in.
        compose.onNodeWithTag("haloDeny").assertIsDisplayed().performClick()
        val decisionA = inbox.awaitFrame("permission-decision") { it.optString("toolCallId") == "tc-a" }
        assertEquals(
            "the deny must land on A — the request that was rendered",
            "deny",
            decisionA.getString("behavior"),
        )
        assertEquals("reject", decisionA.getString("optionId"))
        waitForText("haloTool", "Write")
        waitForText("haloSummary", "notes.txt")
        assertFalse(
            "B must stay undecided after A's answer",
            inbox.hasFrame("permission-decision") { it.optString("toolCallId") == "tc-b" },
        )

        // Allow the chained card (B's Write): the allow lands on B.
        armCard()
        compose.onNodeWithTag("haloApprove").assertIsDisplayed().performClick()
        val decisionB = inbox.awaitFrame("permission-decision") { it.optString("toolCallId") == "tc-b" }
        assertEquals(
            "the allow must land on B — the request that was rendered",
            "allow",
            decisionB.getString("behavior"),
        )
        assertEquals("allow", decisionB.getString("optionId"))
        waitForCardGone()

        // --- Allow-always names the agent's own allow_always option -------
        // The wrist's "always allow" answers with the bare behavior; the
        // bridge must map it onto the option the AGENT offered (#110) —
        // allow_always, never the allow-once option masquerading as a
        // standing grant. (Persisting the rule is the agent's job now; the
        // hook era's updatedPermissions echo died with the hook channel.)
        raisePermission("wear-e2e-session-a", "tc-c", "Bash", JSONObject().put("command", "npm test"))
        openFirstWaitingCard()
        waitForText("haloSummary", "npm test")
        compose.onNodeWithTag("haloAlwaysAllow").performScrollTo().performClick()
        val decisionC = inbox.awaitFrame("permission-decision") { it.optString("toolCallId") == "tc-c" }
        assertEquals("allow-always", decisionC.getString("behavior"))
        assertEquals(
            "the agent's allow_always option must be named, not invented",
            "allow_always",
            decisionC.getString("optionId"),
        )
        waitForCardGone()

        // --- AskUserQuestion: every question answered, buffered submit ----
        // The #111 input-request frame raises the question card in the
        // hook-era wire shape (tool_name AskUserQuestion, the questions in
        // tool_input) — the wear side is a zero-change consumer. The card
        // walks ALL questions; the answers are buffered and submitted
        // together, and reach the agent as ONE positional input-decision
        // frame aligned with the questions.
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
        acpUpdate(
            "wear-e2e-session-a",
            "input-request",
            JSONObject()
                .put("sessionId", "wear-e2e-session-a")
                .put("toolCallId", "tc-q")
                .put("questions", questions),
        )
        openFirstWaitingCard()
        waitForText("haloQuestionText", "Which database should the service use?")

        // Answer question 0; the submit is buffered until EVERY question
        // has an answer, so nothing may reach the agent yet.
        compose.onNodeWithTag("haloQOption-0-SQLite").performScrollTo().performClick()
        waitForText("haloQuestionText", "What should the service be called?")
        Thread.sleep(500)
        assertFalse(
            "no answers may ride the inbox until every question is answered",
            inbox.hasFrame("input-decision"),
        )

        // The last answer submits both positionally.
        compose.onNodeWithTag("haloQOption-1-api-server").performScrollTo().performClick()
        val answersQ = inbox.awaitFrame("input-decision") { it.optString("toolCallId") == "tc-q" }
            .getJSONArray("answers")
        assertEquals("the first answer must land on its question", "SQLite", answersQ.getString(0))
        assertEquals("the second answer must land on its question", "api-server", answersQ.getString(1))
        waitForCardGone()

        // --- The fabricating fork leaves; its sessions end with it --------
        // The wrist spawn below routes to the newest held inbox when no
        // running session matches the target cwd, so this test's connection
        // must be GONE first — otherwise the harness's fake Zed fork (the
        // one that actually services spawn frames) loses the election and
        // the spawn times out. Closing it is also the honest Zed-quit move:
        // the inbox drop ends every session this connection registered, so
        // the fabricated cards leave the pager the same way real ones do.
        inbox.close()
        waitForText("haloCensus", "no sessions")

        // --- Spawn a session from the pager, watch its feed, hide it ------
        // Claude spawns are born in the Zed fork since the ACP-only pivot —
        // no PTY, no pty-output. The spawn POST rides the bridge's /acp/inbox
        // to the harness's fake fork (.github/scripts/wear-e2e-fake-fork.mjs,
        // issue #107), which registers a detached session, acks the spawn,
        // and speaks one greeting turn; the bridge flushes that prose as a
        // `message` event, the feed evidence this leg waits on below.
        drillToList()
        // Enumerate the WHOLE pager by stepping to the trailing spawn card:
        // the spawn adds one session and we must tell its card from every
        // pre-existing one. The walk conveniently parks on the spawn card —
        // the very affordance the leg taps next.
        val before = allPagerIds()
        compose.onNodeWithTag("haloSpawn").performClick()
        // Issue #56: the spawn card opens the TARGET picker instead of firing
        // blind. The skeleton takes the "no project" home entry — the "~"
        // sentinel the bridge resolves to its own user's home, always a valid
        // spawn directory on the real bridge under test. The picker's
        // scrollable stays ancestor-scoped (the pager itself has none, but
        // scoping keeps this leg honest if one ever returns); the home entry
        // trails the per-project entries and may need the scroll to compose
        // (lazy list).
        compose.waitForIdle()
        compose.onNode(
            hasScrollAction() and hasAnyAncestor(hasTestTag("haloSpawnPicker")),
        ).performScrollToNode(hasTestTag("haloSpawnPickHome"))
        compose.onNodeWithTag("haloSpawnPickHome").performClick()
        compose.waitForIdle()
        val deadline = System.currentTimeMillis() + 30_000
        var found: String? = null
        while (System.currentTimeMillis() < deadline && found == null) {
            // Each poll re-enumerates from the pager's start: back out to the
            // page and drill again (the drill re-resolves the selection to
            // the scope's first slot), then step to the end.
            pagerBackToHome()
            drillToList()
            val fresh = allPagerIds() - before
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

        // The fake fork's greeting prose reaches THIS session's feed (the
        // coalesced `message` flush at its turn end). Scope the match to the
        // feed subtree so text from another (prefetched or composed) surface
        // can't satisfy it.
        openPagerCard(spawnedId)
        openFeedFromCard(spawnedId)
        compose.waitUntil(60_000) {
            compose.onAllNodes(
                hasText("wear-e2e-fake-fork", substring = true) and
                    hasAnyAncestor(hasTestTag("haloFeed-$spawnedId")),
            ).fetchSemanticsNodes().isNotEmpty()
        }

        // Back to the pager (swipe right, the feed's v3 back — landing on
        // the CARD directly: the menu is a pass-through, never a stop on the
        // way out) — the selection survives the feed round trip, so the
        // spawned card is up; its tap reopens the actions menu, whose close
        // row is the kill now (#114). An ACP slot is EXTERNAL and yet really
        // killable (issue #88): the ✕ POSTs a kill, the bridge relays a
        // `close` frame down the fork's inbox, and the fork's own deregister
        // ends the slot — so the card leaves because the SESSION ENDED, not
        // because the watch hid it. That distinction is invisible from in
        // here (both make a card vanish), so wear-e2e.sh checks the frame on
        // both sides of the loopback channel afterwards. The action tap
        // closes the menu back onto the card; the
        // close-under-cursor self-heal re-selects a neighbour and the pager
        // stays steppable.
        compose.onNodeWithTag("haloFeed-$spawnedId").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-$spawnedId").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { tagExists("haloMenuFeed") }
        // #116 sank the close to the menu's LAST row, below the stubs, and
        // the list is lazy: the row composes only once scrolled to.
        compose.onNode(
            hasScrollAction() and hasAnyAncestor(hasTestTag("haloSessionMenu")),
        ).performScrollToNode(hasTestTag("haloRowClose"))
        compose.onNode(hasTestTag("haloRowClose") and hasText("✕")).assertIsDisplayed()
        compose.onNodeWithTag("haloRowClose").performClick()
        compose.waitUntil(30_000) { !tagExists("haloPagerCard-$spawnedId") }
        allPagerIds()
        assertTrue("the pager must stay usable after the kill", tagDisplayed("haloSpawn"))
    }
}
