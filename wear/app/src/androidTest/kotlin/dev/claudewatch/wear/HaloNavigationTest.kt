package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.state.BridgeEventReducer
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Halo navigation over live sessions (successor of the old SessionPagerTest:
 * page-per-session coverage became ring-home → list → feed nav coverage),
 * fed by fixture events reduced through the shared reducer — the same
 * `{id, event, data}` frames the bridge buffers. Every session is reachable
 * from home (swipe up → its list row → its live feed with human-readable,
 * ANSI-stripped lines), the thinking indicator renders from per-session
 * state, and a killed session's feed backs out instead of ghosting. Pure UI
 * test — no bridge, no network.
 */
@RunWith(AndroidJUnit4::class)
class HaloNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private val alpha = "5f0d2c9a-8b1e-4c3f-9a67-2e51b4c8d0aa"
    private val beta = "b7e3f1c2-4d5a-4b8e-a2f0-9c6d1e7a3b55"

    /** The two-session slice of the fixture corpus timeline (shared test resources). */
    private fun fixtureFrames(): List<SseFrame> = listOf(
        SseFrame("1", "session", """{"state":"connected"}"""),
        SseFrame(
            "2",
            "session",
            """{"state":"running","agent":"claude","cwd":"/home/dev/projects/alpha","folderName":"alpha","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "3",
            "pty-output",
            """{"text":"$ claude\r\n\u001b[1mWelcome to Claude Code!\u001b[0m\r\n","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "4",
            "tool-output",
            """{"tool_name":"Read","tool_input":{"file_path":"/home/dev/projects/alpha/README.md"},""" +
                """"tool_output":"file contents here","cwd":"/home/dev/projects/alpha","source":"claude","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "5",
            "session",
            """{"state":"running","agent":"codex","cwd":"/home/dev/projects/beta","folderName":"beta","sessionId":"$beta"}""",
        ),
        SseFrame(
            "6",
            "tool-output",
            """{"source":"codex","tool_name":"Bash","tool_input":{"command":"npm test"},"tool_output":null,"sessionId":"$beta"}""",
        ),
    )

    private fun fold(frames: List<SseFrame>, initial: BridgeState = BridgeState()): BridgeState =
        frames.fold(initial) { state, frame ->
            when (val result = BridgeEventReducer.reduce(state, frame, 1_000_000L)) {
                is BridgeEventReducer.Applied -> result.state
                is BridgeEventReducer.Rejected ->
                    throw AssertionError("fixture frame ${frame.id} rejected: ${result.error}")
            }
        }

    private fun ui(bridge: BridgeState) =
        BridgeViewModel.UiState(status = "paired, stream open", paired = true, bridge = bridge)

    /** Home → the all-sessions list (swipe up = drill, per the handoff IA). */
    private fun drillToList() {
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeUp() }
        compose.waitForIdle()
    }

    /**
     * Bring a session's list row into view. The list is a ScalingLazyColumn,
     * which only composes rows near the viewport, so an offscreen row does not
     * exist as a node yet — performScrollTo (needs an existing node) can't
     * reach it; performScrollToNode on the scrollable scrolls until it composes.
     */
    private fun scrollToRow(sessionId: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("haloRow-$sessionId"))
    }

    private fun openFeed(sessionId: String) {
        scrollToRow(sessionId)
        compose.onNodeWithTag("haloRow-$sessionId").performClick()
        compose.waitForIdle()
    }

    @Test
    fun everySessionIsReachableFromHomeAndItsFeedRendersItsOwnLines() {
        val bridge = fold(fixtureFrames())
        assertEquals(2, bridge.sessions.size)
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }

        // Home: the glance census counts both sessions across both projects.
        // The census lives inside the clickable centerpiece (its whole area is
        // the tap-to-open target, so it mergeDescendants); its testTag is only
        // visible in the unmerged tree.
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("2 projects · 2 sessions").assertIsDisplayed()

        // Swipe up: the all-sessions list. Alpha sits below the title on entry,
        // so open its feed first — from there its row is comfortably in view,
        // clear of the top "jump home" strip that overlays the list's top edge.
        drillToList()
        compose.onNodeWithTag("haloRow-$alpha").assertIsDisplayed()

        // Alpha's feed — human-readable, ANSI-stripped lines, no
        // cross-contamination from beta.
        openFeed(alpha)
        compose.onNodeWithTag("haloFeed-$alpha").assertIsDisplayed()
        compose.onNodeWithText("Welcome to Claude Code!").assertIsDisplayed()
        compose.onNodeWithText("Read README.md").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodes(hasText("npm test", substring = true)).fetchSemanticsNodes().size,
        )

        // Swipe down steps back to the list; beta is reachable by scrolling the
        // list down (its own row, below alpha's), and its feed renders ITS lines.
        compose.onNodeWithTag("haloFeed-$alpha").performTouchInput { swipeDown() }
        compose.waitForIdle()
        scrollToRow(beta)
        compose.onNodeWithTag("haloRow-$beta").assertIsDisplayed()
        openFeed(beta)
        compose.onNodeWithTag("haloFeed-$beta").assertIsDisplayed()
        compose.onNode(hasText("npm test", substring = true)).assertIsDisplayed()
    }

    /**
     * Swipe down ON THE LIST steps back to home. This is the OTHER back path:
     * the feed's swipe-down (tested above) reaches the screen-level detector
     * directly, but the list's scrollable owns every vertical drag, so its
     * back is rebuilt from nested-scroll leftovers — the path the API 31+
     * stretch-overscroll used to eat (only the first overpull frame reached
     * the detector; a real finger could never cross the 60px threshold, while
     * anything asserting via the feed path stayed green). The multi-frame
     * drag swipeDown() injects exercises exactly that stretch interaction.
     */
    @Test
    fun listSwipeDownStepsBackToHome() {
        val bridge = fold(fixtureFrames())
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }
        drillToList()
        compose.onNode(hasScrollAction()).assertIsDisplayed() // sanity: we are on the list

        // A real finger's drag: ~30px per 16ms frame, as DISTINCT timestamped
        // moves. swipeDown() would batch its path into one frame-sized delta
        // that crosses the back threshold before the stretch-overscroll ever
        // engages — the exact false-green that let this regression ship while
        // every real finger was broken. Frame-by-frame moves reproduce the
        // per-delta consumption the stretch does on API 31+.
        compose.onNode(hasScrollAction()).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 30f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
        // Unmerged: the census sits inside the centerpiece's mergeDescendants
        // clickable (same gotcha WalkingSkeletonTest documents for this tag).
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun thinkingIndicatorRendersOnCommandEchoAndClearsOnNextOutput() {
        val initial = fold(fixtureFrames())
        var bridge by mutableStateOf(initial)
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }
        drillToList()
        openFeed(alpha)

        // No indicator while idle-on-arrival.
        assertEquals(0, compose.onAllNodes(hasTestTag("haloThinking")).fetchSemanticsNodes().size)

        // Command echo raises the indicator and shows the echoed user line.
        bridge = bridge.echoCommand(alpha, "say hello")
        compose.waitForIdle()
        compose.onNodeWithText("> say hello").assertIsDisplayed()
        compose.onNodeWithTag("haloThinking").assertIsDisplayed()

        // The next output for the session clears it.
        bridge = fold(
            listOf(SseFrame("7", "pty-output", """{"text":"hello!\r\n","sessionId":"$alpha"}""")),
            initial = bridge,
        )
        compose.waitForIdle()
        compose.onNodeWithText("hello!").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag("haloThinking")).fetchSemanticsNodes().size)
    }

    @Test
    fun killedSessionsFeedBacksOutAndItsRowIsPruned() {
        var bridge by mutableStateOf(fold(fixtureFrames()))
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }
        drillToList()
        openFeed(alpha)
        compose.onNodeWithTag("haloFeed-$alpha").assertIsDisplayed()

        // The bridge announces alpha's death (killed via /v1/command).
        bridge = fold(
            listOf(
                SseFrame(
                    "8",
                    "session",
                    """{"state":"ended","agent":"claude","folderName":"alpha","killed":true,"sessionId":"$alpha"}""",
                ),
            ),
            initial = bridge,
        )
        compose.waitForIdle()

        // The dead feed backs out to the list; alpha's row is gone, beta's
        // survives — no ghost screens over a pruned session.
        assertEquals(0, compose.onAllNodes(hasTestTag("haloFeed-$alpha")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasTestTag("haloRow-$alpha")).fetchSemanticsNodes().size)
        scrollToRow(beta)
        compose.onNodeWithTag("haloRow-$beta").assertIsDisplayed()
    }
}
