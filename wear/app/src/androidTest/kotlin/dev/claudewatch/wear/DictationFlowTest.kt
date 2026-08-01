package dev.claudewatch.wear

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.claudewatch.wear.data.AesGcmTokenCipher
import dev.claudewatch.wear.data.CredentialStore
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * Dictated commands with ack-gated echo (issue #20), driven through the Halo
 * UI + real ViewModel against an on-device MockWebServer bridge. Real voice
 * recognition cannot run headlessly, so — as the issue allows — the
 * RecognizerIntent activity result is STUBBED: `onDictate` delivers a fixed
 * transcription to [BridgeViewModel.dictationResult], exactly where
 * MainActivity's activity-result callback lands the real one. (The real-voice
 * smoke test lives in the hardware QA checklist.)
 *
 * Pinned here, against the confirmed watchOS trap (echo + thinking cursor
 * BEFORE the network call, every failure swallowed):
 *  - the feed's Dictate → command POSTed → the voice overlay holds on
 *    "sending… waiting for ack" and the echo appears only after the 2xx;
 *  - injected 5xx → no echo, the overlay flips to "not delivered", the text
 *    survives in the draft, and Retry re-sends the same text to the SAME
 *    session;
 *  - unpaired → the input path refuses cleanly (no POST, no pending, error
 *    surfaced, text kept) instead of pretending to send.
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
        // Engine first, server second: killing the server under a live engine
        // kicks off an endless reconnect + port-probe loop that outlives the
        // test in the shared instrumentation process (and can steal a later
        // test's MockWebServer responses if Linux reuses the ephemeral port).
        // shutdown() is the kdoc-designated teardown for test-constructed
        // instances — same ordering as CatchUpFlowTest.
        viewModel.shutdown()
        server.shutdown()
    }

    private fun setAppContent() {
        compose.setContent {
            val ui by viewModel.state.collectAsState()
            HaloApp(
                ui = ui,
                actions = HaloActions(
                    onSendCommand = viewModel::sendCommand,
                    onCommandDraftChange = viewModel::updateCommandDraft,
                    // The stub: a fixed-text recognizer activity result,
                    // keeping the session the dictation was started FROM.
                    onDictate = { sessionId -> viewModel.dictationResult(recognized, sessionId) },
                ),
            )
        }
    }

    /** Pair against the fake bridge and stream one running session (s-1). */
    private fun pairWithSession() {
        // The engine's discovery preflight pings before every pair.
        server.enqueue(
            MockResponse().setBody("""{"proto":"3","bridgeId":"b-1","machineName":"m"}"""),
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
        compose.waitUntil(30_000) { viewModel.state.value.status == "paired, stream open" }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events
    }

    /** Home → all-sessions list → s-1's feed, where the Dictate pill lives. */
    private fun openFeedAndDictate() {
        openFeed("s-1")
        compose.onNodeWithTag("haloDictate").assertIsDisplayed().performClick()
    }

    /** Home → the session pager → the named session's feed (no dictation).
     *  These fixtures stream ONE session, so the drill's resolved selection
     *  IS its card — tap it, no stepping needed. */
    private fun openFeed(sessionId: String) {
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-$sessionId").performClick()
        compose.waitForIdle()
    }

    /** Pair and stream one running session described by [sessionData], waiting
     *  until it is present in bridge state. */
    private fun pairStreamingSession(sessionData: String, awaitId: String) {
        server.enqueue(
            MockResponse().setBody("""{"proto":"3","bridgeId":"b-1","machineName":"m"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val sseBody = buildString {
            append(":connected\n\n")
            append("id: 1\nevent: session\n")
            append("data: $sessionData")
            append("\n\n")
            append(":pad\n\n".repeat(10_000))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(512, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )
        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        compose.waitUntil(30_000) { viewModel.state.value.bridge.sessions.containsKey(awaitId) }
        compose.waitUntil(30_000) { viewModel.state.value.status == "paired, stream open" }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events
    }

    private fun waitForNode(tag: String, substring: String? = null, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) {
            val matcher =
                if (substring != null) hasTestTag(tag) and hasText(substring, substring = true)
                else hasTestTag(tag)
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun nodeCount(tag: String): Int =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

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
        openFeedAndDictate()

        // The voice overlay holds on "sending…" with the exact transcript…
        waitForNode("haloVoiceStatus")
        compose.onNode(hasTestTag("haloVoiceTranscript") and hasText(recognized)).assertIsDisplayed()
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

        // The POST carried the recognized text, scoped to the feed's session.
        val request = server.takeRequest(15, TimeUnit.SECONDS)
            ?: throw AssertionError("no /v1/command request")
        assertEquals("/v1/command", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(recognized, body.getString("command"))
        assertEquals("s-1", body.getString("sessionId"))

        // The 2xx ack lands: the overlay resolves and the echo appears in the
        // feed — never shown as sent before the bridge acked.
        compose.waitUntil(30_000) { viewModel.state.value.commandResult == "command:200" }
        compose.waitUntil(30_000) { nodeCount("haloVoice") == 0 }
        assertEquals("> $recognized", terminalLines("s-1").last())
        compose.onNodeWithText("> $recognized").assertIsDisplayed()
        compose.onNodeWithTag("haloThinking").assertIsDisplayed()
    }

    @Test
    fun injectedFailureEchoesNothingRestoresTheTextAndRetryResendsIt() {
        setAppContent()
        pairWithSession()

        // Injected failure: the bridge answers the command POST with 500.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        openFeedAndDictate()

        waitForNode("haloVoiceError")
        server.takeRequest(10, TimeUnit.SECONDS) // the failed /v1/command
        // No echo, nothing pending — and the transcription is RESTORED into
        // the draft, rendered on the overlay for retry instead of being lost
        // (the watchOS trap).
        assertTrue(
            "a failed send must never echo: ${terminalLines("s-1")}",
            terminalLines("s-1").none { it == "> $recognized" },
        )
        assertEquals(null, viewModel.state.value.commandInFlightText)
        assertEquals(recognized, viewModel.state.value.commandDraft)
        compose.onNode(hasTestTag("haloVoiceTranscript") and hasText(recognized)).assertIsDisplayed()

        // Retry: re-sends the SAME text to the SAME session.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        compose.onNodeWithTag("haloVoiceRetry").performClick()
        val retry = server.takeRequest(15, TimeUnit.SECONDS)
            ?: throw AssertionError("no retry /v1/command request")
        val retryBody = JSONObject(retry.body.readUtf8())
        assertEquals(recognized, retryBody.getString("command"))
        assertEquals("s-1", retryBody.getString("sessionId"))

        compose.waitUntil(30_000) { viewModel.state.value.commandResult == "command:200" }
        compose.waitUntil(30_000) { nodeCount("haloVoice") == 0 }
        assertEquals("> $recognized", terminalLines("s-1").last())
        compose.onNodeWithText("> $recognized").assertIsDisplayed()
    }

    /**
     * Issue #78: an ACP session is EXTERNAL (Zed's process — the row offers
     * Hide, not a fake Kill) yet still DICTATABLE. The Dictate pill gates on
     * `dictatable`, not on `!external`, so it must show here.
     */
    @Test
    fun anAcpSessionIsDictatableDespiteBeingExternal() {
        setAppContent()
        pairStreamingSession(
            """{"state":"running","agent":"claude","cwd":"/tmp/acp","folderName":"acp","external":true,"kind":"acp","dictatable":true,"sessionId":"s-acp"}""",
            "s-acp",
        )
        openFeed("s-acp")

        compose.onNodeWithTag("haloDictate").assertIsDisplayed()
        // The affordance is the real microphone icon (#104 user feedback,
        // superseding the hand-drawn glyph).
        compose.onNodeWithTag("haloDictateMic", useUnmergedTree = true).assertExists()
        assertEquals("a dictatable session shows no 'unavailable' affordance", 0, nodeCount("haloDictateUnavailable"))
    }

    /**
     * Issue #78: a session the bridge cannot reach live (a PTY-less external
     * hook session — external:true, no dictatable) shows the honest
     * "unavailable" affordance instead of a Dictate pill that would do nothing,
     * and no command can be sent to it.
     */
    @Test
    fun aNonDictatableSessionShowsTheHonestUnavailableAffordance() {
        setAppContent()
        pairStreamingSession(
            """{"state":"running","agent":"claude","cwd":"/tmp/ext","folderName":"ext","external":true,"sessionId":"s-ext"}""",
            "s-ext",
        )
        openFeed("s-ext")

        waitForNode("haloDictateUnavailable")
        // #104 user feedback: the unavailable state is the SAME microphone
        // icon struck with the ⊘ overlay — muted and truly non-interactive
        // (no click action AT ALL, not a dead one), keeping #78's honesty.
        compose.onNodeWithTag("haloDictateUnavailable").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithTag("haloDictateMicOff", useUnmergedTree = true).assertExists()
        assertEquals("no Dictate pill on a non-dictatable session", 0, nodeCount("haloDictate"))
        assertNull("a non-dictatable session sends nothing", server.takeRequest(1, TimeUnit.SECONDS))
    }

    /**
     * Issue #78 / #53, through the session pager's action arc: an ACP session
     * is external (Zed's process, not one the bridge owns), so its card's
     * close action must HIDE it (⊘ → onHide, local) — never a fake Kill (✕)
     * that pretends to stop a process the bridge cannot. The arc is unlabelled
     * (v2 design), so the semantics are asserted by glyph on the stable close
     * tag. ACP close-frame limits are #88's scope — this pins today's exact
     * kill/hide split, ported unchanged from the retired row strip.
     */
    @Test
    fun anAcpCardsCloseActionIsHideNotAFakeKill() {
        setAppContent()
        pairStreamingSession(
            """{"state":"running","agent":"claude","cwd":"/tmp/acp","folderName":"acp","external":true,"kind":"acp","dictatable":true,"sessionId":"s-acp"}""",
            "s-acp",
        )
        // The pager: the lone ACP session is the resolved selection, its
        // action arc already on screen — no reveal swipe in v2.
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-s-acp").assertIsDisplayed()

        waitForNode("haloRowClose")
        // The close cell is the honest Hide glyph…
        compose.onNode(hasTestTag("haloRowClose") and hasText("⊘")).assertIsDisplayed()
        // …and never the fake Kill.
        assertEquals(
            "an ACP card must not offer a fake Kill",
            0,
            compose.onAllNodes(hasTestTag("haloRowClose") and hasText("✕"))
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun unpairedDictationRefusesCleanlyInsteadOfPretendingToSend() {
        setAppContent()
        // Never paired: Halo shows the offline/pairing takeover, so no send
        // surface exists — but a transcription can still land (the recognizer
        // round-trips through an activity result; the pairing can drop while
        // it is up). It must refuse cleanly: no POST, no pending, the text
        // kept — never a pretend-send.
        viewModel.dictationResult(recognized)

        compose.waitUntil(30_000) {
            viewModel.state.value.commandError?.contains("Not paired") == true
        }
        assertEquals(null, viewModel.state.value.commandInFlightText)
        // The transcription is kept in the draft, not lost.
        assertEquals(recognized, viewModel.state.value.commandDraft)
        assertEquals("no request may leave the device", 0, server.requestCount)
        // The UI never pretends: the pairing screen is up, no voice overlay.
        compose.onNodeWithTag("status").assertIsDisplayed()
        assertEquals(0, nodeCount("haloVoice"))
    }
}
