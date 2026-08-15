package dev.claudewatch.wear

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * Rich prose in the feed (issue #128), pinned from the wire in: a `message`
 * event's markdown renders STYLED — the raw markers gone from the displayed
 * text — while a tool result in the same feed keeps its asterisks byte for
 * byte (rich parsing is a prose-role privilege; results are terminal text).
 * Fixture events reduced through the shared reducer, no bridge, no network.
 */
@RunWith(AndroidJUnit4::class)
class HaloRichProseTest {

    @get:Rule
    val compose = createComposeRule()

    private val alpha = "5f0d2c9a-8b1e-4c3f-9a67-2e51b4c8d0aa"

    private fun fixtureFrames(): List<SseFrame> = listOf(
        SseFrame("1", "session", """{"state":"connected"}"""),
        SseFrame(
            "2",
            "session",
            """{"state":"running","agent":"claude","cwd":"/home/dev/projects/alpha","folderName":"alpha","sessionId":"$alpha"}""",
        ),
        // The verbatim control: a RESULT whose asterisks must survive.
        SseFrame(
            "3",
            "tool-output",
            """{"tool_name":"Bash","tool_input":{"command":"npm test"},""" +
                """"tool_output":"rerun **flaky** suite","sessionId":"$alpha"}""",
        ),
        // One coalesced prose flush carrying the vocabulary.
        SseFrame(
            "4",
            "message",
            """{"role":"assistant","text":"## Report\n**bold move** with *lean* text and `chip` inline\n""" +
                """- first item\n1. second item\n> quoted aside\n[docs](https://example.com) linked\n""" +
                """snake_case stays, ~~struck~~ goes","sessionId":"$alpha"}""",
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

    private fun openAlphaFeed() {
        Thread.sleep(350) // the centerpiece's swipe-suppression guard, real uptime
        compose.onNodeWithTag("haloCenter").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-$alpha").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloMenuFeed").performClick()
        compose.waitForIdle()
    }

    private fun count(substring: String): Int =
        compose.onAllNodes(hasText(substring, substring = true)).fetchSemanticsNodes().size

    private fun assertShown(substring: String) {
        assertEquals("expected on screen: $substring", 1, count(substring))
    }

    private fun assertGone(substring: String) {
        assertEquals("expected consumed: $substring", 0, count(substring))
    }

    @Test
    fun proseRendersStyledWhileToolResultsKeepTheirMarkersVerbatim() {
        val ui = BridgeViewModel.UiState(
            status = "paired, stream open",
            paired = true,
            bridge = fold(fixtureFrames()),
        )
        compose.setContent { HaloApp(ui = ui, actions = HaloActions()) }
        openAlphaFeed()
        compose.onNodeWithTag("haloFeed-$alpha").assertExists()

        // The prose flush: every marker consumed, every word still there.
        assertShown("Report")
        assertGone("## Report")
        assertShown("bold move")
        assertGone("**bold move**")
        assertShown("lean")
        assertGone("*lean*")
        assertShown("chip")
        assertGone("`chip`")
        assertShown("• first item")
        assertGone("- first item")
        assertShown("1. second item")
        assertShown("│ quoted aside")
        assertGone("> quoted aside")
        assertShown("docs linked")
        assertGone("https://example.com")
        assertShown("snake_case stays")
        assertShown("struck")
        assertGone("~~struck~~")

        // The control: the tool RESULT's asterisks are untouched — rich
        // parsing never runs on the OUTPUT-result branch.
        assertShown("rerun **flaky** suite")
    }
}
