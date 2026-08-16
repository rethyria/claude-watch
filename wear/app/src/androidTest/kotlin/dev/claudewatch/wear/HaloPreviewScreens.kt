package dev.claudewatch.wear

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.state.BridgeEventReducer
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test: an on-device preview harness for capturing Halo screens with
 * `adb shell screencap` — each "test" composes a fixture-fed screen and holds
 * it on the display long enough to photograph. Skipped unless invoked with
 * `-e preview 1`, so CI's connected runs never pay for the sleeps:
 *
 *   adb shell am instrument -w -e preview 1 \
 *     -e class dev.claudewatch.wear.HaloPreviewScreens#homeAllPage \
 *     dev.claudewatch.wear.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class HaloPreviewScreens {

    @get:Rule
    val compose = createComposeRule()

    private val alpha = "5f0d2c9a-8b1e-4c3f-9a67-2e51b4c8d0aa"
    private val beta = "b7e3f1c2-4d5a-4b8e-a2f0-9c6d1e7a3b55"
    private val gamma = "c9d4a2b1-7e6f-4a1b-8c3d-5f2e9a7b4c11"

    private fun fixtureFrames(): List<SseFrame> = listOf(
        SseFrame("1", "session", """{"state":"connected"}"""),
        // Alpha carries the #54 branch so the pager card's detail line
        // ("⎇ main") is on the reference capture — branch alone, because
        // agents.running > 0 would flip the card DELEGATED (blue outranks
        // green by design) and this capture is the RUNNING reference.
        SseFrame(
            "2",
            "session",
            """{"state":"running","agent":"claude","cwd":"/home/dev/projects/claude-watch","folderName":"claude-watch",""" +
                """"branch":"main","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "3",
            "pty-output",
            """{"text":"$ claude\r\nWelcome to Claude Code!\r\n","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "4",
            "tool-output",
            """{"tool_name":"Read","tool_input":{"file_path":"/home/dev/projects/claude-watch/README.md"},""" +
                """"tool_output":"file contents here","cwd":"/home/dev/projects/claude-watch","source":"claude","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "5",
            "tool-output",
            """{"tool_name":"Bash","tool_input":{"command":"./gradlew test"},""" +
                """"tool_output":"BUILD SUCCESSFUL — 42 passed","cwd":"/home/dev/projects/claude-watch","source":"claude","sessionId":"$alpha"}""",
        ),
        // Beta carries the S9 session-meta trio (#97/#102), so the pager
        // card's `model · mode · use%` subheading is on the capture too.
        SseFrame(
            "6",
            "session",
            """{"state":"running","agent":"codex","cwd":"/home/dev/projects/bridge","folderName":"bridge",""" +
                """"model":"Claude Opus 4.6","mode":"acceptEdits","contextPct":57,"sessionId":"$beta"}""",
        ),
        SseFrame(
            "7",
            "tool-output",
            """{"source":"codex","tool_name":"Bash","tool_input":{"command":"npm test"},"tool_output":null,"sessionId":"$beta"}""",
        ),
        // Gamma carries the #55 workflow fleet: DELEGATED blue on the home
        // ring (agents outrank the working main loop — issue #67's ranking).
        SseFrame(
            "8",
            "session",
            """{"state":"running","agent":"claude","cwd":"/home/dev/projects/bridge","folderName":"bridge",""" +
                """"agents":{"running":2,"done":1},"sessionId":"$gamma"}""",
        ),
        // Enough tail that the masked feed capture (v2 S6) shows lines
        // dissolving into the circular fade band rather than a short stub.
        SseFrame(
            "9",
            "tool-output",
            """{"tool_name":"Bash","tool_input":{"command":"git status --short"},""" +
                """"tool_output":" M wear/app/src/main/kotlin/HaloApp.kt","cwd":"/home/dev/projects/claude-watch","source":"claude","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "10",
            "tool-output",
            """{"tool_name":"Read","tool_input":{"file_path":"/home/dev/projects/claude-watch/wear/design/HALO_HANDOFF.md"},""" +
                """"tool_output":"1183 lines","cwd":"/home/dev/projects/claude-watch","source":"claude","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "11",
            "tool-output",
            """{"tool_name":"Bash","tool_input":{"command":"./gradlew :app:testDebugUnitTest"},""" +
                """"tool_output":"BUILD SUCCESSFUL — 298 passed, 3 skipped","cwd":"/home/dev/projects/claude-watch","source":"claude","sessionId":"$alpha"}""",
        ),
    )

    private fun fold(frames: List<SseFrame>): BridgeState =
        frames.fold(BridgeState()) { state, frame ->
            when (val result = BridgeEventReducer.reduce(state, frame, 1_000_000L)) {
                is BridgeEventReducer.Applied -> result.state
                is BridgeEventReducer.Rejected ->
                    throw AssertionError("fixture frame ${frame.id} rejected: ${result.error}")
            }
        }

    private fun ui() = BridgeViewModel.UiState(
        status = "paired, stream open",
        paired = true,
        bridge = fold(fixtureFrames()).echoCommand(alpha, "run the test suite"),
    )

    private fun uiWithPrompt(): BridgeViewModel.UiState {
        val prompt = BridgeViewModel.PendingPermission(
            permissionId = "perm-preview",
            sessionId = beta,
            toolName = "Bash",
            requestSummary = "$ rm -rf ./build",
            sessionLabel = "bridge",
            options = listOf(
                dev.claudewatch.shared.protocol.PermissionOption("allow", "Yes"),
                dev.claudewatch.shared.protocol.PermissionOption("allow-always", "Always"),
                dev.claudewatch.shared.protocol.PermissionOption("deny", "No"),
            ),
        )
        return ui().copy(permissionQueue = listOf(prompt))
    }

    private fun previewEnabled() {
        assumeTrue(
            "preview harness: pass -e preview 1",
            InstrumentationRegistry.getArguments().getString("preview") == "1",
        )
    }

    private fun hold() {
        compose.waitForIdle()
        Thread.sleep(8_000)
    }

    /** Drill home → session list (the face tap, v3's one list entry),
     *  settling on the scope's first card. */
    private fun drillToList() {
        compose.onNodeWithTag("haloCenter").performClick()
        compose.waitForIdle()
    }

    /** Step the pager [times] cards forward via the › chevron. */
    private fun stepForward(times: Int) {
        repeat(times) {
            compose.onNodeWithTag("haloNext").performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun homeAllPage() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        hold()
    }

    @Test
    fun homeAnswerPill() {
        previewEnabled()
        // A queued prompt puts the terracotta Answer pill on the home page
        // (out of flow — the clock group must sit exactly as on homeAllPage).
        compose.setContent { HaloApp(ui = uiWithPrompt(), actions = HaloActions()) }
        hold()
    }

    @Test
    fun pagerRunning() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        // Alpha is the drill's resolved selection: a RUNNING card with the
        // #54/#55 detail line and the dotted position ring (arc-free, #114).
        drillToList()
        hold()
    }

    @Test
    fun pagerWaiting() {
        previewEnabled()
        compose.setContent { HaloApp(ui = uiWithPrompt(), actions = HaloActions()) }
        drillToList()
        // Beta (All order is project-grouped: alpha, beta, gamma, spawn) is
        // the WAITING card: Answer pill over the card, subheading from the
        // S9 wire trio, hero highlight in terracotta.
        stepForward(1)
        hold()
    }

    @Test
    fun pagerSpawn() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drillToList()
        // The trailing "+ new session" card — the All scope's true end: ›
        // hidden, ring highlight faded, dashed ring carrying the screen.
        stepForward(3)
        hold()
    }

    /** #130: a PROJECT pager's true end — the same trailing spawn card, the
     *  hero faded over the project's own dashed ring. Dot 4 is the second
     *  project ("bridge", two sessions), so one visible step precedes it. */
    @Test
    fun projectPagerSpawn() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        compose.onNodeWithTag("haloDot-4").performClick()
        compose.waitForIdle()
        drillToList()
        stepForward(2)
        hold()
    }


    /** Card → the session-actions menu (#114) → its "open feed" row. */
    private fun openFeedFromCard(sessionId: String) {
        compose.onNodeWithTag("haloPagerCard-$sessionId").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloMenuFeed").performClick()
        compose.waitForIdle()
    }

    @Test
    fun sessionFeed() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drillToList()
        // Alpha is the drill's resolved selection (the scope's first card):
        // the chrome-free masked feed inside the full-circle state ring —
        // resting at the tail, i.e. the scrolled-to-BOTTOM extreme whose
        // last line #116 requires whole inside the legibility band.
        openFeedFromCard(alpha)
        hold()
    }

    /** The other #116 extreme: the feed scrolled all the way into history,
     *  so the capture proves the TOP-most reachable line rests complete
     *  inside the legibility band. */
    @Test
    fun sessionFeedScrolledToTop() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drillToList()
        openFeedFromCard(alpha)
        // Dragging down walks into history on the reversed list; overshoot
        // so the tail rests hard against its end.
        repeat(4) {
            compose.onNodeWithTag("haloFeed-$alpha").performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        hold()
    }

    @Test
    fun sessionMenuRunning() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drillToList()
        // Alpha is bridge-owned: the menu's reference capture — "open feed"
        // still first; the live red ✕ "end session" row sits LAST, below the
        // stubs (#116), so it is off this at-rest frame.
        compose.onNodeWithTag("haloPagerCard-$alpha").performClick()
        hold()
    }

    /** The menu's tail (#116): scrolled to its end, so the capture shows the
     *  destructive close row rendering LAST, under the stubs. */
    @Test
    fun sessionMenuScrolledToClose() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drillToList()
        compose.onNodeWithTag("haloPagerCard-$alpha").performClick()
        compose.waitForIdle()
        compose.onNode(
            hasScrollAction() and hasAnyAncestor(hasTestTag("haloSessionMenu")),
        ).performScrollToNode(hasTestTag("haloRowClose"))
        hold()
    }

    /** The EXTERNAL variant: a hook-observed session's menu carries the
     *  honest ⊘ "hide session" row instead of a kill (#53 unchanged). */
    @Test
    fun sessionMenuExternal() {
        previewEnabled()
        val delta = "d2f8c4a7-1b3e-4d6f-8a9c-7e5b2c4d8f22"
        compose.setContent { HaloApp(ui = externalSessionUi(delta), actions = HaloActions()) }
        drillToList()
        compose.onNodeWithTag("haloPagerCard-$delta").performClick()
        hold()
    }

    @Test
    fun approvalCard() {
        previewEnabled()
        compose.setContent { HaloApp(ui = uiWithPrompt(), actions = HaloActions()) }
        // The Answer pill is the prompt jump now (v2 shell); the centerpiece
        // tap opens the session list instead.
        compose.onNodeWithTag("haloAnswerPill").performClick()
        hold()
    }

    @Test
    fun feedDictateMic() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drillToList()
        // Alpha is bridge-owned (dictatable): the bottom slot carries the
        // microphone-icon Dictate pill (#104 user feedback).
        openFeedFromCard(alpha)
        hold()
    }

    /** A lone EXTERNAL hook session (dictatable = false): the fixture behind
     *  the muted-mic and ⊘-hide-menu references. Its own fixture, so the
     *  main captures' three-session ring stays untouched. */
    private fun externalSessionUi(delta: String): BridgeViewModel.UiState {
        val frames = listOf(
            SseFrame("1", "session", """{"state":"connected"}"""),
            SseFrame(
                "2",
                "session",
                """{"state":"running","agent":"claude","cwd":"/home/dev/projects/claude-watch","folderName":"claude-watch",""" +
                    """"external":true,"sessionId":"$delta"}""",
            ),
            SseFrame(
                "3",
                "tool-output",
                """{"tool_name":"Read","tool_input":{"file_path":"/home/dev/projects/claude-watch/README.md"},""" +
                    """"tool_output":"file contents here","cwd":"/home/dev/projects/claude-watch","source":"claude","sessionId":"$delta"}""",
            ),
            SseFrame(
                "4",
                "tool-output",
                """{"tool_name":"Bash","tool_input":{"command":"git log --oneline -3"},""" +
                    """"tool_output":"46f8489 wear: the Answer pill outranks the kill cell","cwd":"/home/dev/projects/claude-watch","source":"claude","sessionId":"$delta"}""",
            ),
        )
        return BridgeViewModel.UiState(
            status = "paired, stream open",
            paired = true,
            bridge = fold(frames),
        )
    }

    @Test
    fun feedDictateUnavailable() {
        previewEnabled()
        val delta = "d2f8c4a7-1b3e-4d6f-8a9c-7e5b2c4d8f22"
        compose.setContent { HaloApp(ui = externalSessionUi(delta), actions = HaloActions()) }
        drillToList()
        openFeedFromCard(delta)
        hold()
    }

    /** A lone ACP session whose feed tail is [lastFrame] — the #128 rich-text
     *  fixtures. Its own fixture, same doctrine as [externalSessionUi]: the
     *  main captures' three-session ring stays untouched. */
    private fun richTextUi(rho: String, lastFrame: SseFrame): BridgeViewModel.UiState {
        val frames = listOf(
            SseFrame("1", "session", """{"state":"connected"}"""),
            SseFrame(
                "2",
                "session",
                """{"state":"running","agent":"claude","cwd":"/home/dev/projects/claude-watch","folderName":"claude-watch",""" +
                    """"kind":"acp","dictatable":true,"sessionId":"$rho"}""",
            ),
            lastFrame,
        )
        return BridgeViewModel.UiState(
            status = "paired, stream open",
            paired = true,
            bridge = fold(frames),
        )
    }

    /** #128's prose reference: one coalesced turn flush carrying the whole
     *  vocabulary — heading, bold/italic/strike, code chip, list, quote,
     *  link — rendered styled with the raw markers gone. */
    @Test
    fun richProseFeed() {
        previewEnabled()
        val rho = "e4a1b7c9-2d5f-4e8a-9b3c-6f1d8e2a7b44"
        val ui = richTextUi(
            rho,
            SseFrame(
                "3",
                "message",
                """{"role":"assistant","text":"## Test report\n**All green** on the *first* rerun\n""" +
                    """`RingBuffer` keeps ~~199~~ 200 lines\n- masks stay put\n1. rotary reclaimed\n""" +
                    """> ship it\nsee [the notes](https://example.com)","sessionId":"$rho"}""",
            ),
        )
        compose.setContent { HaloApp(ui = ui, actions = HaloActions()) }
        drillToList()
        openFeedFromCard(rho)
        hold()
    }

    /** #128's verbatim control: a tool RESULT keeps its asterisks (and the
     *  pass-count highlight) — rich parsing is a prose-role privilege. */
    @Test
    fun richProseToolResultVerbatim() {
        previewEnabled()
        val rho = "e4a1b7c9-2d5f-4e8a-9b3c-6f1d8e2a7b44"
        val ui = richTextUi(
            rho,
            SseFrame(
                "3",
                "tool-output",
                """{"tool_name":"Bash","tool_input":{"command":"npm test"},""" +
                    """"tool_output":"12 passed, 2 **quarantined** — see *notes*","sessionId":"$rho"}""",
            ),
        )
        compose.setContent { HaloApp(ui = ui, actions = HaloActions()) }
        drillToList()
        openFeedFromCard(rho)
        hold()
    }
}
