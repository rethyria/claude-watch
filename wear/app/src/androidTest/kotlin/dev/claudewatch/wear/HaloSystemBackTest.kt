package dev.claudewatch.wear

import android.os.ParcelFileDescriptor
import androidx.activity.BackEventCompat
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
 * The SYSTEM back route (issue #109, round 2): one root ALWAYS-ENABLED
 * PredictiveBackHandler routing completions through the pure systemBack
 * (HaloNav.kt), so the left-bezel edge swipe — Wear's system back gesture —
 * means BACK everywhere and exits only from home, as the handler's OWN
 * deliberate finish. Driven with fixture UiStates — no bridge, no network.
 * Instrumented injections never start at the bezel (that is how the exit
 * shipped unnoticed), so these tests attack the DISPATCH seams instead:
 *
 *  - Espresso.pressBack / pressBackUnconditionally inject a real system
 *    KEYCODE_BACK, which the opted-in app (manifest
 *    enableOnBackInvokedCallback) routes through the same
 *    OnBackInvokedDispatcher the edge gesture lands on;
 *  - onBackPressedDispatcher.onBackPressed() drives the androidx seam the
 *    system callback is bridged into (what the SM-L330's hardware back
 *    button commits);
 *  - dispatchOnBackStarted/-Progressed drive the PREDICTIVE half of that
 *    same seam — exactly what the API-34 OnBackAnimationCallback feeds it —
 *    which is how the mid-gesture race tests hold a system gesture in
 *    flight while injecting the app-surface drags that killed round 1;
 *  - one test shell-injects a true LEFT-EDGE swipe (system-level input, the
 *    gesture the compose harness cannot make) and pins the measured Wear
 *    dispatch's stable half: a registered handler suppresses the system
 *    swipe-dismiss — no more mid-session exits.
 *
 * Priority: overlays first (voice, spawn picker), then the card (position
 * under it preserved), then depth, then non-home pages jump home — and the
 * handler stays REGISTERED at the home resting state (round 2's invariant:
 * an enabled flag that could drop mid-gesture was the round-1 exit), where
 * a completed back is the one deliberate activity.finish().
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

        // Home reached — and the handler is STILL registered (round 2's
        // always-enabled invariant): the NEXT back is OURS too, a deliberate
        // finish, never the system's own commit.
        assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
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
        // Registered at home too (round 2): landing home must never blink
        // the callback off — that blink was the round-1 exit.
        assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
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
    fun backAtHomeFinishesThroughTheAlwaysRegisteredHandler() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        assertAtHome()

        // Round 2's registration invariant, asserted at the exact state
        // round 1 used to unregister in: the handler stays on the dispatcher
        // even at home, so the system-side OnBackInvokedCallback never drops
        // and the system can never commit its own back (nor run the
        // home-preview face-peek) — anywhere.
        assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())

        // End-to-end: KEYCODE_BACK routes INTO the handler, systemBack
        // routes null (home at rest), and the handler finishes the activity
        // ITSELF — the one deliberate exit, guarded to fire exactly once
        // (finish() flips isFinishing synchronously on the main thread).
        Espresso.pressBackUnconditionally()
        val deadline = System.currentTimeMillis() + 10_000
        while (
            compose.activityRule.scenario.state != Lifecycle.State.DESTROYED &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50)
        }
        assertEquals(
            "back at home must finish the activity via the handler",
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
     * whole proof. Since round 2 the handler never unregisters, so there is
     * no disarmed state left ANYWHERE: the home edge swipe is now the
     * handler's own deliberate finish, pinned by the keyevent exit test
     * above (a shell edge injection at home races emulator SysUI too
     * erratically under instrumentation to gate on) plus the on-wrist
     * checklist.
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

    /**
     * THE round-1 killer, replayed deterministically (#109 round 2): a
     * predictive system back gesture goes in flight — the androidx test
     * dispatch seam (dispatchOnBackStarted/-Progressed) drives
     * handleOnBackStarted exactly as the API-34 OnBackAnimationCallback
     * does — and MID-GESTURE the gesture's touches arrive at the app
     * surface as an ordinary rightward drag. On the SM-L330 that drag
     * stepped a project page to home-at-rest, the enabled-flag BackHandler
     * unregistered itself while the gesture was in flight, and the release
     * committed the SYSTEM's own back — activity dead ('app-request', zero
     * crashes in ApplicationExitInfo). This pins both halves of the round-2
     * fix: the drag is suppressed (SystemBackDragClaim — nav must NOT move
     * mid-gesture, so alpha is still on screen before the commit), and the
     * completion routes the pure systemBack from where the app really is —
     * alpha jumps home, the activity SURVIVES, the handler stays registered.
     */
    @Test
    fun midGestureSurfaceDragCannotCommitAnExitOrDoubleNavigate() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        // One page right of home: alpha (same injection as the jump-home
        // test above).
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-width / 12f, 0f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("alpha").assertIsDisplayed()

        // The system gesture starts and progresses: the handler is now
        // collecting and the in-flight flag is up. waitForIdle after the
        // start dispatch matters — the onBack coroutine launches on the
        // composition dispatcher and must run before the drag begins.
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(
                BackEventCompat(2f, 200f, 0f, BackEventCompat.EDGE_LEFT),
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackProgressed(
                BackEventCompat(80f, 200f, 0.4f, BackEventCompat.EDGE_LEFT),
            )
        }
        compose.waitForIdle()

        // Mid-gesture, the gesture's touches reach the surface layer: the
        // rightward drag that would step alpha → home (round 1's fatal nav
        // mutation). The claim must eat it — still on alpha.
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(width / 12f, 0f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("alpha").assertIsDisplayed()

        // The release commits: completion routes systemBack(alpha) — the
        // jump home — and the activity survives the exact frame round 1
        // died on, with the handler still registered for the next gesture.
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertAtHome()
        assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    /**
     * The race's OTHER live seam (#109 round 2 review): on the modal card
     * surfaces the system gesture's touches never reach the poisoned host-Box
     * drag detector — the card's verticalScroll wins the drag, and the
     * unconsumed droop surfaces as nested-scroll leftovers in the shared
     * overscroll-exit connection (rememberOverscrollExitConnection; the
     * platform overscroll under the card is disabled precisely so those
     * leftovers arrive). Unpoisoned, a mid-gesture droop past the threshold
     * fires "decide later" racing the handler's completion — both orderings
     * double-navigate (and the voice-overlay twin over home routes the
     * completion to the deliberate finish). This pins the connection's
     * stand-down: the droop is withheld (card still up), and the commit then
     * routes exactly ONE step — card closed, pager position kept, activity
     * alive.
     */
    @Test
    fun midGestureCardPullDownCannotFireDecideLaterOrDoubleNavigate() {
        compose.setContent { HaloApp(ui = ui(queue = listOf(betaPrompt)), actions = HaloActions()) }
        drillToList()
        stepTo("haloPagerCard-s-2")
        compose.onNodeWithTag("haloAnswerPill").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()

        // The system gesture goes in flight (same seam discipline as the
        // page-drag race test above: waitForIdle lets the handler's collect
        // coroutine raise the in-flight flag before the drag begins).
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(
                BackEventCompat(2f, 200f, 0f, BackEventCompat.EDGE_LEFT),
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackProgressed(
                BackEventCompat(80f, 200f, 0.4f, BackEventCompat.EDGE_LEFT),
            )
        }
        compose.waitForIdle()

        // Mid-gesture, a full-height downward droop lands on the card's
        // scrollable: at its top nothing is consumable, so every delta spills
        // into the overscroll-exit connection — far past the ~30dp threshold.
        // The poison must withhold the exit: still on the card.
        compose.onNodeWithTag("haloCard").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, height / 10f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()

        // The release commits: ONE hierarchy step — the card closes onto the
        // pager exactly where it was (a leaked decide-later would have taken
        // that step already, landing this commit a level deeper).
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(0, nodeCount("haloCard"))
        compose.onNodeWithTag("haloPagerCard-s-2").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
        assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }
}
