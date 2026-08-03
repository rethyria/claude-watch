package dev.claudewatch.wear

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.claudewatch.wear.data.AesGcmTokenCipher
import dev.claudewatch.wear.data.BridgeCredentials
import dev.claudewatch.wear.data.CredentialStore
import dev.claudewatch.wear.net.BackoffPolicy
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import dev.claudewatch.wear.ui.halo.PairFieldInput
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * Issue #106 — the pairing screen is success-aware, not last-response-aware.
 * The live trace: a 401 "code expired", then a pair that actually SUCCEEDED
 * (bridge log: paired + SSE connected), yet the UI stayed on its error, so
 * the user kept retrying against the now-locked single-use window (403 /
 * closed). These are the issue's two instrumented acceptance criteria,
 * driven through the REAL pairing form (the PairFieldInput seam stands in
 * for the Wear RemoteInput IME only — the network path is the real engine
 * against an on-device MockWebServer bridge, the CatchUpFlowTest pattern):
 *
 *  1. a 401-expired attempt followed by a successful retry lands the UI
 *     connected with no stale error anywhere in the tree;
 *  2. a 403 already-paired while a valid stored credential exists resolves
 *     by VERIFICATION (the stream reopens with the held token) — connected,
 *     and no error ever surfaced.
 *
 * The offline takeover owns the `status` tag; the app tears it down the
 * moment the state turns paired, so the tag's disappearance is Halo's
 * observable "the pairing UI dismissed" signal (the WalkingSkeleton gate).
 *
 * Fresh VM + temp store per test, never the production singleton — pairing
 * the singleton to this test's MockWebServer would leave the persistent
 * store pointing at a dead server for every later test class in the shared
 * instrumentation process.
 */
@RunWith(AndroidJUnit4::class)
class PairingRecoveryFlowTest {

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
        val storeFile = File.createTempFile("pairing-recovery", ".bin", context.cacheDir)
        store = CredentialStore({ storeFile }, AesGcmTokenCipher { key })
        // The pairing fields launch the Wear RemoteInput activity (no
        // headless IME in this harness); the seam short-circuits a field tap
        // to its canned value — the same fields, chips and engine a finger
        // drives, minus only the system input surface.
        PairFieldInput.override = { label ->
            when (label) {
                "host" -> "127.0.0.1"
                "port" -> server.port.toString()
                "code" -> "111222"
                else -> null
            }
        }
    }

    @After
    fun tearDown() {
        // Process-global seam: never leak it into another test class.
        PairFieldInput.override = null
        // Engine before server (the CatchUpFlowTest ordering): the tests end
        // Connected to a held stream, and a server-first shutdown would send
        // the engine into reconnect churn under every later test class.
        if (::viewModel.isInitialized) viewModel.shutdown()
        server.shutdown()
    }

    private fun setAppContent() {
        compose.setContent {
            val ui by viewModel.state.collectAsState()
            HaloApp(ui = ui, actions = HaloActions(onPair = viewModel::pair))
        }
    }

    private fun ping() =
        MockResponse().setBody("""{"proto":"3","bridgeId":"b-1","machineName":"m"}""")

    /** SSE stream held open with comment padding — the connected end state. */
    private fun sseHeld() = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .throttleBody(512, 250, TimeUnit.MILLISECONDS)
        .setBody(":connected\n\n" + ":pad\n\n".repeat(2_000))

    private fun tagExists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** "Filling" a pairing field is tapping it: the armed seam supplies the value. */
    private fun fill(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun noTextAnywhere(substring: String) {
        assertTrue(
            "no node may still read \"$substring\"",
            compose.onAllNodes(hasText(substring, substring = true))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    /** Criterion 1: 401-expired, then a successful retry → connected, no error visible. */
    @Test
    fun expiredCode401ThenRetryLandsConnectedWithNoStaleError() {
        viewModel = BridgeViewModel(store)
        setAppContent()

        // Unpaired: Choose pane → the Manual form.
        compose.waitUntil(30_000) { tagExists("manualButton") }
        compose.onNodeWithTag("manualButton").performScrollTo().performClick()
        compose.waitUntil(10_000) { tagExists("pairButton") }
        fill("host")
        fill("port")
        fill("code")

        // Attempt 1: the startup code has expired — the bridge 401s (and
        // mints a fresh code on its own side). The error must show: it is
        // the truth at this point.
        server.enqueue(ping())
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"error":"Pairing code expired. A new code has been generated."}""",
            ),
        )
        compose.onNodeWithTag("pairButton").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(
                hasTestTag("status") and hasText("Pairing code expired", substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }

        // Attempt 2 — the retry (with the fresh code, in the real flow):
        // succeeds within seconds, the stream opens.
        server.enqueue(ping())
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        server.enqueue(sseHeld())
        compose.onNodeWithTag("pairButton").performScrollTo().performClick()

        // The pairing UI reaches connected: the takeover (and its error
        // line) is torn down, and the stale 401 does not survive anywhere.
        compose.waitUntil(30_000) { !tagExists("status") }
        compose.waitUntil(30_000) { viewModel.state.value.status == "paired, stream open" }
        noTextAnywhere("pair failed")
        noTextAnywhere("Pairing code expired")
    }

    /** Criterion 2: 403 already-paired + a stored credential → verified connected, no error. */
    @Test
    fun alreadyPaired403WithStoredCredentialLandsConnectedWithoutError() {
        runBlocking {
            store.saveCredentials(BridgeCredentials("tok-1", "127.0.0.1", server.port, "b-1"))
        }
        // Cold start resumes from the credential: preflight ping, then a
        // stream that dies at once. The huge injected backoff parks the
        // engine in Reconnecting for the whole test — the paired-but-offline
        // pairing screen stays up, and the reconnect that heals it below can
        // only be the 403's verification kick, never the scheduled retry.
        server.enqueue(ping())
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(":connected\n\n"),
        )
        viewModel = BridgeViewModel(store, backoff = BackoffPolicy(baseMs = 300_000, maxMs = 300_000))
        setAppContent()

        // Paired-but-offline: the quiet re-pair chip fronts the chooser.
        compose.waitUntil(30_000) { tagExists("repairButton") }
        compose.onNodeWithTag("repairButton").performScrollTo().performClick()
        compose.waitUntil(10_000) { tagExists("manualButton") }
        compose.onNodeWithTag("manualButton").performScrollTo().performClick()
        compose.waitUntil(10_000) { tagExists("pairButton") }
        fill("host")
        fill("port")
        fill("code")

        // The pair hits the locked single-use window — but a valid credential
        // for this bridge is stored, so the engine VERIFIES instead of
        // erroring: the stream reopens with the held token.
        server.enqueue(ping())
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":"Already paired. Re-pairing requires explicit authorization on the bridge."}""",
            ),
        )
        server.enqueue(ping())
        server.enqueue(sseHeld())
        compose.onNodeWithTag("pairButton").performScrollTo().performClick()

        // Connected, screen dismissed, and no error was surfaced en route.
        compose.waitUntil(30_000) { !tagExists("status") }
        compose.waitUntil(30_000) { viewModel.state.value.status == "paired, stream open" }
        noTextAnywhere("pair failed")
        assertTrue(viewModel.state.value.discover is BridgeViewModel.DiscoverUi.Idle)
    }
}
