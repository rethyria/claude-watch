package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.state.BridgeEventReducer
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.wear.net.BridgeDiscovery
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #121's regression pins: every overlay claims rotary focus on entry
 * (its own rotaryScrollable/focusable grab) and Compose clears focus when the
 * overlay unmounts WITHOUT restoring anything — so the surface underneath
 * must re-request the bezel or the crown is dead until the user backs out and
 * re-drills. Touch keeps working throughout, which is why every other gate
 * stayed green: no test spun the crown AFTER an overlay round trip. These do
 * exactly that — the approval card (decide later), the voice overlay
 * (open → ack) and the offline takeover's discovery list, over both crown
 * surfaces (the feed's scroll, the pager's step). Injected rotary is
 * focus-routed like the real crown's (SOURCE_ROTARY_ENCODER through the root
 * view), so a dropped focus swallows it here exactly as on-wrist. Fixture
 * UiStates throughout — no bridge, no network.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HaloRotaryReclaimTest {

    @get:Rule
    val compose = createComposeRule()

    private val alpha = "5f0d2c9a-8b1e-4c3f-9a67-2e51b4c8d0aa"
    private val beta = "b7e3f1c2-4d5a-4b8e-a2f0-9c6d1e7a3b55"

    /** Two running sessions; alpha carries enough history to scroll into. */
    private fun fixtureFrames(): List<SseFrame> = listOf(
        SseFrame("1", "session", """{"state":"connected"}"""),
        SseFrame(
            "2",
            "session",
            """{"state":"running","agent":"claude","cwd":"/home/dev/projects/alpha","folderName":"alpha","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "3",
            "session",
            """{"state":"running","agent":"claude","cwd":"/home/dev/projects/beta","folderName":"beta","sessionId":"$beta"}""",
        ),
    ) + (0 until 30).map { i ->
        SseFrame("${10 + i}", "pty-output", """{"text":"history-line-$i\r\n","sessionId":"$alpha"}""")
    }

    private fun fold(frames: List<SseFrame>): BridgeState =
        frames.fold(BridgeState()) { state, frame ->
            when (val result = BridgeEventReducer.reduce(state, frame, 1_000_000L)) {
                is BridgeEventReducer.Applied -> result.state
                is BridgeEventReducer.Rejected ->
                    throw AssertionError("fixture frame ${frame.id} rejected: ${result.error}")
            }
        }

    /** Alpha's blocked prompt: gives its feed a tap-to-prompt surface and its
     *  pager card the Answer pill. */
    private val alphaPrompt = BridgeViewModel.PendingPermission(
        permissionId = "perm-alpha",
        sessionId = alpha,
        toolName = "Bash",
        requestSummary = "$ rm -rf ./build",
        sessionLabel = "alpha",
        options = listOf(PermissionOption("allow", "Yes"), PermissionOption("deny", "No")),
    )

    private fun ui(
        bridge: BridgeState,
        queue: List<BridgeViewModel.PendingPermission> = emptyList(),
    ) = BridgeViewModel.UiState(
        status = "paired, stream open",
        paired = true,
        bridge = bridge,
        permissionQueue = queue,
    )

    /** Home → the list pager: the face tap, v3's ONE list entry (its 300ms
     *  swipe-suppression guard runs on real uptime, waited out first). */
    private fun drillToList() {
        Thread.sleep(350)
        compose.onNodeWithTag("haloCenter").performClick()
        compose.waitForIdle()
    }

    /** List → alpha's feed behind the actions menu's "open feed" row (#114). */
    private fun openAlphaFeed() {
        drillToList()
        compose.onNodeWithTag("haloPagerCard-$alpha").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloMenuFeed").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloFeed-$alpha").assertIsDisplayed()
    }

    /** The cards ignore taps for ~400ms after appearing, on real uptime. */
    private fun armCard() = Thread.sleep(500)

    /** One crown gesture, injected at the root: delivery rides FOCUS, never
     *  the injection node, which is the whole point of these pins. */
    private fun spin(pixels: Float) {
        compose.onNodeWithTag("haloRoot").performRotaryScrollInput {
            rotateToScrollVertically(pixels)
        }
        compose.waitForIdle()
    }

    /** Spin until [text] composes: a focus-dead crown scrolls nothing and the
     *  budget runs out on the resting feed. Positive detents walk toward the
     *  tail, negative into history (the feed's session-list-matching
     *  direction). */
    private fun spinFeedUntil(text: String, pixels: Float) {
        var spins = 0
        while (spins < 30 && compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()) {
            spin(pixels)
            spins++
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    /** Prove the crown works BEFORE the round trip — so the post-round-trip
     *  assertion isolates the reclaim, not the injection — then rest back at
     *  the tail (the extra detents are bound-absorbed). */
    private fun proveCrownScrollsThenRestAtTail() {
        spinFeedUntil("history-line-0", -800f)
        spinFeedUntil("history-line-29", 800f)
        repeat(3) { spin(800f) }
        compose.onNodeWithText("history-line-29").assertIsDisplayed()
    }

    @Test
    fun crownStillScrollsTheFeedAfterADecideLaterCardRoundTrip() {
        val bridge = fold(fixtureFrames())
        compose.setContent {
            HaloApp(ui = ui(bridge, queue = listOf(alphaPrompt)), actions = HaloActions())
        }
        openAlphaFeed()
        proveCrownScrollsThenRestAtTail()

        // The round trip: the waiting feed's whole surface opens its own card
        // (which takes rotary focus for its scroll); "decide later" closes it
        // back onto the feed, nothing sent.
        compose.onNodeWithTag("haloFeedTap").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        armCard()
        compose.onNodeWithTag("haloDecideLater").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloFeed-$alpha").assertIsDisplayed()

        // The pin: the crown must still walk into history — without the
        // reclaim the card's unmount left NOTHING focused and every detent
        // vanished while touch kept working.
        spinFeedUntil("history-line-0", -800f)
    }

    @Test
    fun crownStillScrollsTheFeedAfterAVoiceOverlayRoundTrip() {
        val bridge = fold(fixtureFrames())
        var state by mutableStateOf(ui(bridge))
        compose.setContent {
            HaloApp(
                ui = state,
                actions = HaloActions(
                    // The recognizer stub: a dictation IS a send going in
                    // flight, which is what opens the voice overlay (§7).
                    onDictate = { state = state.copy(commandInFlightText = "run the tests") },
                ),
            )
        }
        openAlphaFeed()
        proveCrownScrollsThenRestAtTail()

        compose.onNodeWithTag("haloDictate").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloVoice").assertIsDisplayed()

        // The ack lands: the overlay (and its rotary focus grab) unmounts.
        state = state.copy(commandInFlightText = null)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasTestTag("haloVoice")).fetchSemanticsNodes().size)

        spinFeedUntil("history-line-0", -800f)
    }

    @Test
    fun crownStillStepsThePagerAfterAnAnswerCardRoundTrip() {
        val bridge = fold(fixtureFrames())
        compose.setContent {
            HaloApp(ui = ui(bridge, queue = listOf(alphaPrompt)), actions = HaloActions())
        }
        drillToList()
        compose.onNodeWithTag("haloPagerCard-$alpha").assertIsDisplayed()

        // Pre-check: one detent steps to beta and back — the crown works
        // before the round trip, so the failure below can only be the reclaim.
        spin(800f)
        compose.onNodeWithTag("haloPagerCard-$beta").assertIsDisplayed()
        spin(-800f)
        compose.onNodeWithTag("haloPagerCard-$alpha").assertIsDisplayed()

        // Answer raises alpha's card OVER the list; "decide later" lands back
        // on the same pager card (the #114 list-card path, no feed between).
        compose.onNodeWithTag("haloAnswerPill").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        armCard()
        compose.onNodeWithTag("haloDecideLater").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-$alpha").assertIsDisplayed()

        // The pin: the crown must still step the pager.
        spin(800f)
        compose.onNodeWithTag("haloPagerCard-$beta").assertIsDisplayed()
    }

    @Test
    fun crownStillScrollsTheFeedAfterAnOfflineTakeoverRoundTrip() {
        val bridge = fold(fixtureFrames())
        var state by mutableStateOf(ui(bridge))
        compose.setContent { HaloApp(ui = state, actions = HaloActions()) }
        openAlphaFeed()
        proveCrownScrollsThenRestAtTail()

        // The stream drops: the takeover covers the feed. Walking into the
        // Discover FOUND list exercises the takeover path that GRABS rotary
        // focus (its ScalingLazyColumn claims the bezel on entry).
        state = state.copy(
            status = "reconnecting (1/5)",
            discover = BridgeViewModel.DiscoverUi.Found(
                listOf(BridgeDiscovery.DiscoveredBridge("mach", "192.168.0.9", 7860, "b-9")),
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("repairButton").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("discoverButton").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("discoverBridge-b-9").assertIsDisplayed()

        // The bridge comes back: the takeover (and its focused list) unmounts.
        state = state.copy(status = "paired, stream open")
        compose.waitForIdle()
        compose.onNodeWithTag("haloFeed-$alpha").assertIsDisplayed()

        spinFeedUntil("history-line-0", -800f)
    }
}
