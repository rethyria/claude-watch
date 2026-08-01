package dev.claudewatch.wear

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SYSTEM back route (issue #109): one root BackHandler, enabled off the
 * pure systemBack (HaloNav.kt), so the left-bezel edge swipe — Wear's system
 * back gesture — means BACK everywhere and exits only from home. Driven with
 * fixture UiStates — no bridge, no network. Instrumented injections never
 * start at the bezel (that is how the exit shipped unnoticed), so these tests
 * attack the DISPATCH seams instead:
 *
 *  - Espresso.pressBack / pressBackUnconditionally inject a real system
 *    KEYCODE_BACK, which the opted-in app (manifest
 *    enableOnBackInvokedCallback) routes through the same
 *    OnBackInvokedDispatcher the edge gesture lands on;
 *  - onBackPressedDispatcher.onBackPressed() drives the androidx seam the
 *    system callback is bridged into (what the SM-L330's hardware back
 *    button commits);
 *  - one test shell-injects a true LEFT-EDGE swipe (system-level input, the
 *    gesture the compose harness cannot make) and pins the measured Wear
 *    dispatch's stable half: an armed handler suppresses the system
 *    swipe-dismiss — no more mid-session exits. (The disarmed half, edge
 *    swipe at home = system dismiss, is too injection-racy to gate on; see
 *    that test's comment.)
 *
 * Priority and fall-through: overlays first (voice, spawn picker), then the
 * card (position under it preserved), then depth, then non-home pages jump
 * home — and at the home resting state NOTHING is registered, so the system
 * exit stands (the one place the app may close).
 */
@RunWith(AndroidJUnit4::class)
class HaloSystemBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Two projects, one session each — alpha (s-1) first in the All order.
     *  dictatable is set the way the reducer would (a non-external session is
     *  reachable): the voice-overlay leg needs s-1's Dictate pill. */
    private fun fixtureBridge() = BridgeState(
        sessions = mapOf(
            "s-1" to SessionState(
                sessionId = "s-1",
                agent = "claude",
                cwd = "/home/dev/alpha",
                folderName = "alpha",
                dictatable = true,
            ),
            "s-2" to SessionState(
                sessionId = "s-2",
                agent = "claude",
                cwd = "/home/dev/beta",
                folderName = "beta",
                dictatable = true,
            ),
        ),
    )

    /** Beta's blocked prompt: makes s-2 a waiting session with an Answer pill. */
    private val betaPrompt = BridgeViewModel.PendingPermission(
        permissionId = "perm-beta",
        sessionId = "s-2",
        toolName = "Write",
        requestSummary = "Write notes.txt",
        sessionLabel = "beta",
        options = listOf(PermissionOption("allow", "Yes"), PermissionOption("deny", "No")),
    )

    private fun ui(queue: List<BridgeViewModel.PendingPermission> = emptyList()) =
        BridgeViewModel.UiState(
            status = "paired, stream open",
            paired = true,
            bridge = fixtureBridge(),
            permissionQueue = queue,
        )

    /**
     * The androidx dispatcher seam — where the system's OnBackInvokedCallback
     * is bridged to, i.e. what an edge swipe's commit invokes. Used for the
     * routing legs; the keyevent legs use Espresso's real injection.
     */
    private fun dispatchSystemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun nodeCount(tag: String): Int =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    /** Home → the session list pager (swipe up = drill, per the handoff IA). */
    private fun drillToList() {
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeUp() }
        compose.waitForIdle()
    }

    private fun openFeed(sessionId: String) {
        compose.onNodeWithTag("haloPagerCard-$sessionId").performClick()
        compose.waitForIdle()
    }

    /** Step the pager ›-wards until [tag] is the current card. */
    private fun stepTo(tag: String) {
        var steps = 0
        while (steps < 10 && nodeCount(tag) == 0) {
            compose.onNodeWithTag("haloNext").performClick()
            compose.waitForIdle()
            steps++
        }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun assertAtHome() {
        // Unmerged: the census lives inside the centerpiece's mergeDescendants
        // clickable (the standing gotcha for this tag).
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()
        assertFalse("the app must not be finishing", compose.activity.isFinishing)
    }

    @Test
    fun keyeventBackWalksFeedToListToHomeAndNeverFinishes() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drillToList()
        openFeed("s-1")
        compose.onNodeWithTag("haloFeed-s-1").assertIsDisplayed()

        // A real injected KEYCODE_BACK: feed → the pager, selection KEPT
        // (back-from-feed preserves the session, #95).
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-s-1").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)

        // Again: list → home.
        Espresso.pressBack()
        compose.waitForIdle()
        assertAtHome()

        // At the home resting state the handler has disabled itself: nothing
        // is registered, so the NEXT back is the system's — the app may exit.
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun backFromAProjectPageJumpsStraightHome() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        // One page right of home: alpha (frame-by-frame, the real-finger
        // injection discipline).
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-width / 12f, 0f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("alpha").assertIsDisplayed()

        dispatchSystemBack()
        assertAtHome()
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun backClosesTheAnswerCardAndKeepsThePagerPosition() {
        compose.setContent { HaloApp(ui = ui(queue = listOf(betaPrompt)), actions = HaloActions()) }
        drillToList()
        // Step to the WAITING session's card and raise its pinned Answer card.
        stepTo("haloPagerCard-s-2")
        compose.onNodeWithTag("haloAnswerPill").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()

        // Back = "decide later": the card closes and the pager is EXACTLY
        // where it was — s-2's card, one step in, not the scope's first.
        dispatchSystemBack()
        assertEquals(0, nodeCount("haloCard"))
        compose.onNodeWithTag("haloPagerCard-s-2").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun backDismissesTheVoiceOverlayAndLandsBackOnTheFeed() {
        var state by mutableStateOf(ui())
        compose.setContent { HaloApp(ui = state, actions = HaloActions()) }
        drillToList()
        openFeed("s-1")

        // Arm dictation from the feed, then let its send go in flight: the
        // voice overlay opens over the feed (the §7 lifecycle).
        compose.onNodeWithTag("haloDictate").performClick()
        compose.waitForIdle()
        state = state.copy(commandInFlightText = "run the tests")
        compose.waitUntil(10_000) { nodeCount("haloVoice") > 0 }

        // Back hits the OVERLAY first (== its swipe-down Cancel): the feed
        // underneath is untouched — back must not navigate under a modal.
        dispatchSystemBack()
        assertEquals(0, nodeCount("haloVoice"))
        compose.onNodeWithTag("haloFeed-s-1").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun backClosesTheSpawnPickerOverTheListItWasSummonedFrom() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drillToList()
        stepTo("haloSpawn")
        compose.onNodeWithTag("haloSpawn").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloSpawnPicker").assertIsDisplayed()

        // Back = the picker's cancel: closes it, spawning nothing, landing
        // exactly on the list slot that summoned it.
        dispatchSystemBack()
        assertEquals(0, nodeCount("haloSpawnPicker"))
        compose.onNodeWithTag("haloSpawn").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun backAtHomeIsNotInterceptedAndTheAppMayExit() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        assertAtHome()

        // The fall-through contract itself: at the home resting state the
        // enabled flag is false, so NOTHING is registered on the dispatcher —
        // under the opted-in manifest that also unregisters the system-side
        // OnBackInvokedCallback, restoring the default dismiss-and-exit.
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())

        // And end-to-end: an uninterception system back closes the activity.
        Espresso.pressBackUnconditionally()
        val deadline = System.currentTimeMillis() + 10_000
        while (
            compose.activityRule.scenario.state != Lifecycle.State.DESTROYED &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50)
        }
        assertEquals(
            "back at home must fall through to the system exit",
            Lifecycle.State.DESTROYED,
            compose.activityRule.scenario.state,
        )
    }

    private fun shell(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    /** A shell-injected LEFT-BEZEL edge swipe — system-level input the compose
     *  harness cannot make (it injects in-window, mid-screen; only the shell
     *  path traverses the system's swipe-dismiss handling). x=2 sits inside
     *  the leftmost edge band; 250ms is a deliberate drag, past commit. The
     *  wake + window-focus wait is the injection's precondition, not
     *  paranoia: shell input lands on the FOCUSED window, and an emulator
     *  drifting into ambient (or SysUI holding focus through a transition)
     *  swallows the swipe whole — the exact way both edge tests flaked. On
     *  the wrist the app is by definition focused when the thumb swipes. */
    private fun injectEdgeSwipe() {
        shell("input keyevent KEYCODE_WAKEUP")
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        val metrics = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics
        val y = metrics.heightPixels / 2
        val end = (metrics.widthPixels * 0.7f).toInt()
        shell("input swipe 2 $y $end $y 250")
    }

    /**
     * The EDGE GESTURE with the handler armed — the #109 regression itself:
     * before the manifest opt-in this exact injection killed the activity
     * mid-session. With a registered OnBackInvokedCallback the system's
     * swipe-dismiss is SUPPRESSED: in every measured run (API 33 and 34) the
     * armed activity SURVIVES the bezel swipe — the one stable half of the
     * dispatch; whether the gesture's touches then reach the app's own
     * surface detectors or die in the system's cancelled preview varies
     * run-to-run on the emulator, so no landing state is asserted. A dropped
     * injection would pass vacuously, which is why this is a TRIPWIRE (it
     * can only fail for real — a dismissal — never falsely) rather than the
     * whole proof; the disarmed half (edge swipe at home = system dismiss)
     * dismisses reliably by hand on both images but races emulator SysUI
     * state too erratically under instrumentation to gate on, so it lives in
     * the keyevent exit test above plus the on-wrist checklist.
     */
    @Test
    fun edgeSwipeWithTheHandlerArmedNeverDismissesTheActivity() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drillToList()
        stepTo("haloPagerCard-s-2")

        injectEdgeSwipe()

        // Give a would-be dismissal ample time to land before calling life.
        Thread.sleep(3_000)
        compose.waitForIdle()
        assertFalse("the edge swipe must not dismiss the activity", compose.activity.isFinishing)
        assertTrue(compose.activityRule.scenario.state.isAtLeast(Lifecycle.State.STARTED))
        compose.onNodeWithTag("haloRoot").assertExists()
    }
}
