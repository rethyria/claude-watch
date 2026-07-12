package dev.claudewatch.wear

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.claudewatch.wear.data.AesGcmTokenCipher
import dev.claudewatch.wear.data.CredentialStore
import dev.claudewatch.wear.ui.SessionPagerActions
import dev.claudewatch.wear.ui.SessionPagerScreen
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * Dictated commands with ack-gated echo (issue #20), driven through the real
 * UI + real ViewModel against an on-device MockWebServer bridge. Real voice
 * recognition cannot run headlessly, so — as the issue allows — the
 * RecognizerIntent activity result is STUBBED: `onDictate` delivers a fixed
 * transcription to [BridgeViewModel.dictationResult], exactly where
 * MainActivity's activity-result callback lands the real one. (The real-voice
 * smoke test lives in the hardware QA checklist.)
 *
 * Pinned here, against the confirmed watchOS trap (echo + thinking cursor
 * BEFORE the network call, every failure swallowed):
 *  - stubbed recognizer result → command POSTed → echo appears only after 2xx;
 *  - injected 5xx → no echo, error surfaced, the text restored into the
 *    input, and retry re-sends the same text;
 *  - unpaired → the input path refuses cleanly (no POST, no pending, error
 *    shown) instead of pretending to send.
 */
@RunWith(AndroidJUnit4::class)
class DictationFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var viewModel: BridgeViewModel

    /** The fixed transcription the stubbed recognizer "hears". */
    private val recognized = "run the tests"

    // Fresh unpaired store per test (AES key in memory, file in the app's
    // cache) — the production Keystore singleton would leak pairings between
    // tests.
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storeFile = File.createTempFile("dictation-conn", ".bin", context.cacheDir)
        viewModel = BridgeViewModel(
            CredentialStore({ storeFile }, AesGcmTokenCipher { key }),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun setAppContent() {
        compose.setContent {
            val ui by viewModel.state.collectAsState()
            SessionPagerScreen(
                ui = ui,
                actions = SessionPagerActions(
                    onSendCommand = viewModel::sendCommand,
                    onCommandDraftChange = viewModel::updateCommandDraft,
                    // The stub: a fixed-text recognizer activity result.
                    onDictate = { viewModel.dictationResult(recognized) },
                ),
            )
        }
    }

    /** Pair against the fake bridge and stream one running session (s-1). */
    private fun pairWithSession() {
        // The engine's discovery preflight pings before every pair.
        server.enqueue(
            MockResponse().setBody("""{"proto":"2","bridgeId":"b-1","machineName":"m"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append("id: 1\nevent: session\n")
            append("""data: {"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj","sessionId":"s-1"}""")
            append("\n\n")
            // Big throttled pad: keeps the stream open for the whole test so
            // SSE reconnects can't steal the queued command responses.
            append(":pad\n\n".repeat(10_000))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(512, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )
        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        compose.waitUntil(30_000) { viewModel.state.value.sessionId == "s-1" }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events
    }

    private fun clickDictate() {
        compose.onNodeWithTag("dictateButton").performScrollTo().performClick()
    }

    private fun waitForNode(tag: String, substring: String? = null, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) {
            val matcher =
                if (substring != null) hasTestTag(tag) and hasText(substring, substring = true)
                else hasTestTag(tag)
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun terminalLines(sessionId: String): List<String> =
        viewModel.state.value.bridge.sessions[sessionId]?.terminal?.items?.map { it.text }
            ?: emptyList()

    @Test
    fun stubbedRecognizerResultIsPostedAndEchoesOnlyAfterTheAck() {
        setAppContent()
        pairWithSession()

        // Hold the ack back so the pre-ack (pending) window is observable.
        server.enqueue(
            MockResponse()
                .setHeadersDelay(3_000, TimeUnit.MILLISECONDS)
                .setBody("""{"ok":true}"""),
        )
        clickDictate()

        // Pending until the bridge acks: the indicator shows the exact text…
        waitForNode("commandPending", recognized)
        // …and this state snapshot proves no echo happened yet (echo and
        // pending-clear are one atomic ViewModel update, so any state that is
        // still pending provably has no echo — no timing sensitivity).
        val pending = viewModel.state.value
        assertEquals(recognized, pending.commandInFlightText)
        assertFalse(
            "no thinking cursor before the ack",
            pending.bridge.sessions.getValue("s-1").thinking,
        )
        assertTrue(
            "no echo before the ack: ${terminalLines("s-1")}",
            terminalLines("s-1").none { it == "> $recognized" },
        )

        // The POST carried the recognized text, session-scoped.
        val request = server.takeRequest(15, TimeUnit.SECONDS)
            ?: throw AssertionError("no /v1/command request")
        assertEquals("/v1/command", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(recognized, body.getString("command"))
        assertEquals("s-1", body.getString("sessionId"))

        // The 2xx ack lands: pending resolves and the echo appears.
        waitForNode("commandResult", "command:200")
        assertEquals(0, compose.onAllNodes(hasTestTag("commandPending")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasTestTag("commandError")).fetchSemanticsNodes().size)
        assertEquals("> $recognized", terminalLines("s-1").last())

        // And it is really on the session's terminal page, cursor raised.
        compose.onNodeWithTag("sessionPager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("> $recognized").assertIsDisplayed()
        compose.onNodeWithTag("thinkingCursor").assertIsDisplayed()
    }

    @Test
    fun injectedFailureEchoesNothingRestoresTheTextAndRetryResendsIt() {
        setAppContent()
        pairWithSession()

        // Injected failure: the bridge answers the command POST with 500.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        clickDictate()

        waitForNode("commandError", "Send failed: HTTP 500")
        server.takeRequest(10, TimeUnit.SECONDS) // the failed /v1/command
        // No echo, nothing pending — and the transcription is RESTORED into
        // the input for retry instead of being lost (the watchOS trap).
        assertTrue(
            "a failed send must never echo: ${terminalLines("s-1")}",
            terminalLines("s-1").none { it == "> $recognized" },
        )
        assertEquals(0, compose.onAllNodes(hasTestTag("commandPending")).fetchSemanticsNodes().size)
        compose.onNodeWithTag("commandInput").assert(hasText(recognized))

        // Retry via Send: re-sends the SAME text straight from the input.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        compose.onNodeWithTag("sendButton").performScrollTo().performClick()
        val retry = server.takeRequest(15, TimeUnit.SECONDS)
            ?: throw AssertionError("no retry /v1/command request")
        assertEquals(recognized, JSONObject(retry.body.readUtf8()).getString("command"))

        waitForNode("commandResult", "command:200")
        assertEquals(0, compose.onAllNodes(hasTestTag("commandError")).fetchSemanticsNodes().size)
        assertEquals("> $recognized", terminalLines("s-1").last())
    }

    @Test
    fun unpairedDictationRefusesCleanlyInsteadOfPretendingToSend() {
        setAppContent()
        // Never paired: the stubbed transcription must not produce a request.
        clickDictate()

        waitForNode("commandError", "Not paired")
        assertEquals(0, compose.onAllNodes(hasTestTag("commandPending")).fetchSemanticsNodes().size)
        // The transcription is kept in the input, not lost.
        compose.onNodeWithTag("commandInput").assert(hasText(recognized))
        assertEquals("no request may leave the device", 0, server.requestCount)
    }
}
