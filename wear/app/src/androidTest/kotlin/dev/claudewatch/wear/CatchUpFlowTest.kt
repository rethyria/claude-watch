package dev.claudewatch.wear

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.claudewatch.wear.data.AesGcmTokenCipher
import dev.claudewatch.wear.data.CredentialStore
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * Issue #24 acceptance 4 — catch-up after a disconnect — as the in-process
 * rendition of "kill the service from the notification, then reopen the
 * app": [BridgeViewModel.disconnect] is exactly what the service's
 * Disconnect action calls, [BridgeViewModel.resume] exactly what
 * MainActivity's ON_START observer fires, so driving the pair through them
 * against an on-device MockWebServer bridge (the DictationFlowTest pattern)
 * exercises the real seam without needing to kill a real service.
 *
 * The load-bearing assertion is the reconnect request's Last-Event-ID
 * header: it must equal the last APPLIED (reducer-acked, issue #48) id from
 * before the disconnect — proving catch-up resumes from the PERSISTED ack
 * cursor, never FULL_REPLAY ("0") and never a mere-receipt id that ran
 * ahead of what actually rendered.
 *
 * Deliberately a fresh VM + temp store per test, NOT the production
 * [BridgeViewModel.singleton]: the singleton now outlives test classes in
 * the shared instrumentation process, and pairing IT to this test's
 * MockWebServer would leave the persistent store pointing at a dead server
 * for every later test (the exact cross-test leak DictationFlowTest's
 * header warns about). The disconnect/resume semantics under test live in
 * the VM + engine + store, which are identical either way.
 */
@RunWith(AndroidJUnit4::class)
class CatchUpFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var store: CredentialStore
    private lateinit var viewModel: BridgeViewModel

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storeFile = File.createTempFile("catchup-conn", ".bin", context.cacheDir)
        store = CredentialStore({ storeFile }, AesGcmTokenCipher { key })
        viewModel = BridgeViewModel(store)
    }

    @After
    fun tearDown() {
        // Kill the engine BEFORE the server: the test ends Connected to
        // stream2's held-open body, so shutting the server down first would
        // fail the stream and send the (test-constructed, non-singleton)
        // engine into scheduleReconnect forever — port re-pings plus the
        // 7860-7869 relocation probe churning under every later test class
        // in the shared instrumentation process. shutdown() is the
        // kdoc-designated teardown for exactly this fixture shape: it
        // cancels the engineScope so no reconnect survives.
        viewModel.shutdown()
        server.shutdown()
    }

    private fun setAppContent() {
        compose.setContent {
            val ui by viewModel.state.collectAsState()
            HaloApp(ui = ui, actions = HaloActions())
        }
    }

    private fun takeRequest(label: String) =
        server.takeRequest(30, TimeUnit.SECONDS) ?: throw AssertionError("no request: $label")

    @Test
    fun reconnectAfterDisconnectReplaysFromTheLastAppliedIdAndRendersTheCatchUp() {
        setAppContent()

        // --- Pair; stream 1 delivers an event that APPLIES ----------------
        server.enqueue(
            MockResponse().setBody("""{"proto":"3","bridgeId":"b-1","machineName":"m"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        val stream1 = buildString {
            append(":connected\n\n")
            append("id: 1\nevent: session\n")
            append("""data: {"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj","sessionId":"s-1"}""")
            append("\n\n")
            append("id: 2\nevent: pty-output\n")
            append("""data: {"text":"catchup-before\r\n","sessionId":"s-1"}""")
            append("\n\n")
            // Held open: the disconnect below is what ends this stream, not
            // the body running out into a reconnect that would race the test.
            append(":pad\n\n".repeat(2_000))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(512, 250, TimeUnit.MILLISECONDS)
                .setBody(stream1),
        )
        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        compose.waitUntil(30_000) { viewModel.state.value.status == "paired, stream open" }
        // Event 2 APPLIED (it is in the terminal) …
        compose.waitUntil(30_000) {
            viewModel.state.value.bridge.sessions["s-1"]?.terminal?.items
                ?.any { it.text == "catchup-before" } == true
        }
        // … and its ack PERSISTED (issue #48's ack-to-advance): the persisted
        // cursor is the exact thing the resume below must read back, so the
        // disconnect may only land after the write did.
        compose.waitUntil(30_000) { runBlocking { store.read().lastEventId } == "2" }

        assertEquals("/v1/ping", takeRequest("pair preflight").path)
        assertEquals("/v1/pair", takeRequest("pair").path)
        takeRequest("first events connect").let {
            assertEquals("/v1/events", it.path)
            // Fresh pairing: full replay — the baseline the catch-up header
            // below must differ from.
            assertEquals("0", it.getHeader("Last-Event-ID"))
        }

        // --- Disconnect (the service's stop affordance) -------------------
        viewModel.disconnect()
        compose.waitUntil(30_000) { !viewModel.state.value.paired }

        // --- The bridge moves on while the watch is disconnected ----------
        server.enqueue(
            MockResponse().setBody("""{"proto":"3","bridgeId":"b-1","machineName":"m"}"""),
        )
        val stream2 = buildString {
            append(":connected\n\n")
            append("id: 3\nevent: pty-output\n")
            append("""data: {"text":"catchup-after\r\n","sessionId":"s-1"}""")
            append("\n\n")
            append(":pad\n\n".repeat(2_000))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(512, 250, TimeUnit.MILLISECONDS)
                .setBody(stream2),
        )

        // --- Resume (what MainActivity fires on every ON_START) -----------
        viewModel.resume()
        assertEquals("/v1/ping", takeRequest("resume preflight").path)
        takeRequest("reconnect events").let {
            assertEquals("/v1/events", it.path)
            // THE load-bearing assertion: the reconnect resumes from the
            // last APPLIED id — the persisted ack cursor — not FULL_REPLAY
            // and not anything receipt-advanced past it.
            assertEquals("2", it.getHeader("Last-Event-ID"))
        }
        compose.waitUntil(30_000) { viewModel.state.value.status == "paired, stream open" }

        // --- The caught-up event renders ----------------------------------
        // Drill into s-1's feed and see the post-disconnect line on screen
        // (the pre-disconnect state survived the disconnect, so both lines
        // are in the terminal — the new one proves the replay DELIVERED).
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloRow-s-1").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("catchup-after", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
