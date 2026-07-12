package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private fun openFeed(sessionId: String) {
        compose.onNodeWithTag("haloRow-$sessionId").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun everySessionIsReachableFromHomeAndItsFeedRendersItsOwnLines() {
        val bridge = fold(fixtureFrames())
        assertEquals(2, bridge.sessions.size)
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }

        // Home: the glance census counts both sessions across both projects.
        compose.onNodeWithTag("haloCensus").assertIsDisplayed()
        compose.onNodeWithText("2 projects · 2 sessions").assertIsDisplayed()

        // Swipe up: the all-sessions list, one row per session.
        drillToList()
        compose.onNodeWithTag("haloRow-$alpha").assertIsDisplayed()
        compose.onNodeWithTag("haloRow-$beta").performScrollTo().assertIsDisplayed()

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

        // Swipe down steps back to the list; beta's feed renders ITS lines.
        compose.onNodeWithTag("haloFeed-$alpha").performTouchInput { swipeDown() }
        compose.waitForIdle()
        openFeed(beta)
        compose.onNodeWithTag("haloFeed-$beta").assertIsDisplayed()
        compose.onNode(hasText("npm test", substring = true)).assertIsDisplayed()
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
        compose.onNodeWithTag("haloRow-$beta").performScrollTo().assertIsDisplayed()
    }
}
