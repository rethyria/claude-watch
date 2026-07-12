package dev.claudewatch.wear

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
        SseFrame(
            "2",
            "session",
            """{"state":"running","agent":"claude","cwd":"/home/dev/projects/claude-watch","folderName":"claude-watch","sessionId":"$alpha"}""",
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
        SseFrame(
            "6",
            "session",
            """{"state":"running","agent":"codex","cwd":"/home/dev/projects/bridge","folderName":"bridge","sessionId":"$beta"}""",
        ),
        SseFrame(
            "7",
            "tool-output",
            """{"source":"codex","tool_name":"Bash","tool_input":{"command":"npm test"},"tool_output":null,"sessionId":"$beta"}""",
        ),
        SseFrame(
            "8",
            "session",
            """{"state":"running","agent":"claude","cwd":"/home/dev/projects/bridge","folderName":"bridge","sessionId":"$gamma"}""",
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

    @Test
    fun homeAllPage() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        hold()
    }

    @Test
    fun sessionList() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeUp() }
        hold()
    }

    @Test
    fun sessionFeed() {
        previewEnabled()
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloRow-$alpha").performScrollTo().performClick()
        hold()
    }

    @Test
    fun approvalCard() {
        previewEnabled()
        compose.setContent { HaloApp(ui = uiWithPrompt(), actions = HaloActions()) }
        compose.onNodeWithTag("haloCenter").performClick()
        hold()
    }
}
