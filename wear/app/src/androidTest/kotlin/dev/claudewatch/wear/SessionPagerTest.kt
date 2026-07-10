package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.state.BridgeEventReducer
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.wear.ui.SessionPagerActions
import dev.claudewatch.wear.ui.SessionPagerScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pager over live sessions, fed by fixture events reduced through the shared
 * reducer (the same `{id, event, data}` frames the bridge buffers): page 0 is
 * the control page, each session gets its own terminal page with
 * human-readable lines, and the thinking cursor renders from per-session
 * state. Pure UI test — no bridge, no network.
 */
@RunWith(AndroidJUnit4::class)
class SessionPagerTest {

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

    private fun swipeToNextPage() {
        compose.onNodeWithTag("sessionPager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    @Test
    fun pagerNavigatesControlPageAndOneTerminalPagePerLiveSession() {
        val bridge = fold(fixtureFrames())
        assertEquals(2, bridge.sessions.size)
        compose.setContent {
            SessionPagerScreen(
                ui = BridgeViewModel.UiState(status = "paired, stream open", bridge = bridge),
                actions = SessionPagerActions(),
            )
        }

        // Page 0: the control page.
        compose.onNodeWithTag("status").assertIsDisplayed()

        // Page 1: alpha's terminal — human-readable, ANSI-stripped lines.
        swipeToNextPage()
        compose.onNodeWithTag("sessionHeader-$alpha").assertIsDisplayed()
        compose.onNodeWithText("Welcome to Claude Code!").assertIsDisplayed()
        compose.onNodeWithText("Read README.md").assertIsDisplayed()

        // Page 2: beta's terminal — codex-prefixed, no cross-contamination.
        swipeToNextPage()
        compose.onNodeWithTag("sessionHeader-$beta").assertIsDisplayed()
        compose.onNodeWithText("[codex] $ npm test").assertIsDisplayed()

        // And back: swiping right returns to alpha.
        compose.onNodeWithTag("sessionPager").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("sessionHeader-$alpha").assertIsDisplayed()
    }

    @Test
    fun thinkingCursorRendersOnCommandEchoAndClearsOnNextOutput() {
        val initial = fold(fixtureFrames())
        var bridge by mutableStateOf(initial)
        compose.setContent {
            SessionPagerScreen(
                ui = BridgeViewModel.UiState(bridge = bridge),
                actions = SessionPagerActions(),
            )
        }
        swipeToNextPage() // alpha's page

        // No cursor while idle-on-arrival.
        compose.onAllNodes(hasTestTag("thinkingCursor")).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }

        // Command echo raises the cursor and shows the echoed line.
        bridge = bridge.echoCommand(alpha, "say hello")
        compose.waitForIdle()
        compose.onNodeWithText("> say hello").assertIsDisplayed()
        compose.onNodeWithTag("thinkingCursor").assertIsDisplayed()

        // The next output for the session clears it.
        bridge = fold(
            listOf(SseFrame("7", "pty-output", """{"text":"hello!\r\n","sessionId":"$alpha"}""")),
            initial = bridge,
        )
        compose.waitForIdle()
        compose.onNodeWithText("hello!").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag("thinkingCursor")).fetchSemanticsNodes().size)
    }

    @Test
    fun killedSessionsPageIsPrunedFromThePager() {
        var bridge by mutableStateOf(fold(fixtureFrames()))
        compose.setContent {
            SessionPagerScreen(
                ui = BridgeViewModel.UiState(bridge = bridge),
                actions = SessionPagerActions(),
            )
        }
        swipeToNextPage()
        compose.onNodeWithTag("sessionHeader-$alpha").assertIsDisplayed()

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

        // Alpha's page is gone; the pager clamps onto beta's page.
        assertEquals(0, compose.onAllNodes(hasTestTag("sessionHeader-$alpha")).fetchSemanticsNodes().size)
        compose.onNodeWithTag("sessionHeader-$beta").assertIsDisplayed()
    }
}
