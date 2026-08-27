package dev.claudewatch.wear

import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.claudewatch.wear.data.AesGcmTokenCipher
import dev.claudewatch.wear.data.CredentialStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * The haptic grammar on a real device (issue #129). Two claims only — the
 * dedupe discipline is JVM territory (AttentionHapticsTest):
 *
 *  - the five verbs are DISTINCT waveforms (the classify-blind contract) and
 *    the #20 command pair is exactly what shipped, asserted over the pure
 *    effect factories — VibrationEffect implements value equality, so this
 *    is deterministic — plus the real [VibratorHaptics] speaking all five
 *    through the device vibrator, and the platform's own ledger asserting
 *    they were not THROWN AWAY (see the ledger test: "it did not crash" is
 *    what let the attention verbs ship dead for eleven days);
 *  - the #20 flows still speak the OLD verbs: a dictated send's 2xx ack and
 *    an injected 5xx route to commandAcked/commandFailed — through the real
 *    engine against an on-device MockWebServer bridge — with the recording
 *    wrapper DELEGATING to the real grammar, so every recorded verb was also
 *    genuinely vibrated.
 */
@RunWith(AndroidJUnit4::class)
class HapticsSmokeTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: BridgeViewModel

    // Fresh unpaired store per test — the production Keystore singleton would
    // leak pairings between tests (see CatchUpFlowTest's kdoc).
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storeFile = File.createTempFile("haptics-conn", ".bin", context.cacheDir)
        viewModel = BridgeViewModel(
            CredentialStore({ storeFile }, AesGcmTokenCipher { key }),
        )
    }

    @After
    fun tearDown() {
        // Engine first, server second — same ordering (and reasoning) as
        // DictationFlowTest's teardown.
        viewModel.shutdown()
        server.shutdown()
    }

    /** Records the verb AND speaks it through the real vibrator. */
    private class RecordingRealHaptics(private val real: Haptics) : Haptics {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun commandAcked() { events += "acked"; real.commandAcked() }
        override fun commandFailed() { events += "failed"; real.commandFailed() }
        override fun needsYou() { events += "needsYou"; real.needsYou() }
        override fun workFinished() { events += "workFinished"; real.workFinished() }
        override fun wentWrong() { events += "wentWrong"; real.wentWrong() }
    }

    private fun awaitState(
        timeoutMs: Long = 30_000,
        predicate: (BridgeViewModel.UiState) -> Boolean,
    ): BridgeViewModel.UiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel.state.value
            if (predicate(state)) return state
            Thread.sleep(10)
        }
        throw AssertionError("timed out; last state: ${viewModel.state.value}")
    }

    @Test
    fun grammarEffectsArePairwiseDistinctAndTheCommandPairIsUnchanged() {
        val effects = linkedMapOf(
            "commandAcked" to commandAckedEffect(),
            "commandFailed" to commandFailedEffect(),
            "needsYou" to needsYouEffect(),
            "workFinished" to workFinishedEffect(),
            "wentWrong" to wentWrongEffect(),
        )
        val names = effects.keys.toList()
        for (i in names.indices) {
            for (j in i + 1 until names.size) {
                assertNotEquals(
                    "${names[i]} and ${names[j]} must be classifiable blind",
                    effects.getValue(names[i]),
                    effects.getValue(names[j]),
                )
            }
        }
        // The #20 pair, byte-identical to what shipped: issue #129's grammar
        // extension must not re-teach the user's existing vocabulary.
        assertEquals(
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
            effects.getValue("commandAcked"),
        )
        assertEquals(
            VibrationEffect.createWaveform(longArrayOf(0, 90, 90, 90), -1),
            effects.getValue("commandFailed"),
        )

        // And the real grammar speaks all five through the device vibrator.
        val real = VibratorHaptics(InstrumentationRegistry.getInstrumentation().targetContext)
        real.commandAcked()
        real.commandFailed()
        real.needsYou()
        real.workFinished()
        real.wentWrong()
    }

    /**
     * The attention verbs must REACH the vibrator, not merely be dispatched
     * without throwing.
     *
     * This is the regression pin for the eleven days the grammar was silently
     * dead: every attention verb carried `VibrationAttributes.USAGE_NOTIFICATION`,
     * which an ordinary app on Wear is not permitted to use, so the platform
     * discarded all three as `ignored_app_ops` — on the emulator AND on real
     * hardware, DND on and off, 68 consecutive drops observed on the user's
     * SM-L330. Nothing in the suite noticed, because `vibrate()` fails
     * SILENTLY: it returns void and the call site cannot tell a played effect
     * from a binned one. Only the platform's own ledger can, so the test reads
     * it.
     *
     * Scoped by DIFF (lines present after but not before), because the ledger
     * retains entries across runs and installs — an assertion over the whole
     * history would fail on any device that ever ran the broken build. Phrased
     * as "nothing of ours was discarded" rather than "something of ours
     * played", so it holds under quiet hours too: with DND on the new code
     * skips the vibrate entirely, which adds no ledger line and is correct.
     */
    @Test
    fun attentionVerbsAreNotDiscardedByThePlatform() {
        val before = vibrationLedgerLines()
        val real = VibratorHaptics(InstrumentationRegistry.getInstrumentation().targetContext)
        real.needsYou()
        real.workFinished()
        real.wentWrong()
        // The ledger is written by the system server on its own thread; give
        // it a moment to catch up with three fire-and-forget calls.
        Thread.sleep(1_000)

        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val discarded = (vibrationLedgerLines() - before)
            .filter { it.contains(pkg) && it.contains("ignored_app_ops") }
        assertEquals(
            "the platform discarded attention verbs instead of playing them: $discarded",
            emptyList<String>(),
            discarded,
        )
    }

    /**
     * `dumpsys vibrator_manager` as a set of lines. Shell access comes from
     * UiAutomation — the instrumentation runs as the app, which cannot read
     * this service directly. Returns empty on any failure (a device whose dump
     * format or service name differs degrades to a vacuous pass rather than a
     * false alarm).
     */
    private fun vibrationLedgerLines(): Set<String> = runCatching {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("dumpsys vibrator_manager"),
        ).bufferedReader().useLines { lines -> lines.map { it.trim() }.toSet() }
    }.getOrDefault(emptySet())

    @Test
    fun dictationAckAndFailureStillSpeakTheCommandVerbs() {
        val haptics = RecordingRealHaptics(
            VibratorHaptics(InstrumentationRegistry.getInstrumentation().targetContext),
        )
        viewModel.haptics = haptics
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
            // Keep the stream open across both sends below.
            append(":pad\n\n".repeat(10_000))
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(512, 250, TimeUnit.MILLISECONDS)
                .setBody(sseBody),
        )
        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        awaitState { it.sessionId == "s-1" && it.status == "paired, stream open" }
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/ping (pair preflight)
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/pair
        server.takeRequest(10, TimeUnit.SECONDS) // /v1/events

        // A dictated send's 2xx ack: the ack tick, nothing else.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        viewModel.dictationResult("say hello")
        awaitState { it.commandResult == "command:200" }
        assertEquals(listOf("acked"), haptics.events.toList())

        // An injected 5xx on the retry: the failure buzz.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        viewModel.sendCommand("say hello")
        awaitState { it.commandResult == "command:500" }
        assertEquals(listOf("acked", "failed"), haptics.events.toList())
    }
}
