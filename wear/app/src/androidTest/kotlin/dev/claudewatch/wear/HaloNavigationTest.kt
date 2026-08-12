package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performTouchInput
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
 * Halo navigation over live sessions (successor of the old SessionPagerTest:
 * page-per-session coverage became ring-home → list pager → feed nav
 * coverage), fed by fixture events reduced through the shared reducer — the
 * same `{id, event, data}` frames the bridge buffers. Every session is
 * reachable from home (tap the face → its pager card → its actions menu →
 * "open feed" → its live feed with
 * human-readable, ANSI-stripped lines — v3: the face tap is the ONE list
 * entry and swipe-right the one gesture back; #114 put the menu between
 * card and feed), the thinking indicator
 * renders from per-session state, and a killed session's feed backs out
 * onto the healed pager instead of ghosting. Pure UI test — no bridge, no
 * network.
 *
 * Page order (issue #57): settings (slot 0) | usage (slot 1) | home/All (slot
 * 2, the landing page) | one page per project. The session-drill tests below
 * navigate RELATIVELY from the initial home page, so the settings/usage
 * insertion to the far LEFT leaves them untouched; the added test reaches the
 * new leftmost settings page and pins its confirm-gated Unpair.
 */
@RunWith(AndroidJUnit4::class)
class HaloNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private val alpha = "5f0d2c9a-8b1e-4c3f-9a67-2e51b4c8d0aa"
    private val beta = "b7e3f1c2-4d5a-4b8e-a2f0-9c6d1e7a3b55"

    /** The two-session slice of the fixture corpus timeline (shared test resources). */
    private fun fixtureFrames(): List<SseFrame> = listOf(
        SseFrame("1", "session", """{"state":"connected"}"""),
        SseFrame(
            "2",
            "session",
            """{"state":"running","agent":"claude","cwd":"/home/dev/projects/alpha","folderName":"alpha","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "3",
            "pty-output",
            """{"text":"$ claude\r\n\u001b[1mWelcome to Claude Code!\u001b[0m\r\n","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "4",
            "tool-output",
            """{"tool_name":"Read","tool_input":{"file_path":"/home/dev/projects/alpha/README.md"},""" +
                """"tool_output":"file contents here","cwd":"/home/dev/projects/alpha","source":"claude","sessionId":"$alpha"}""",
        ),
        SseFrame(
            "5",
            "session",
            """{"state":"running","agent":"codex","cwd":"/home/dev/projects/beta","folderName":"beta","sessionId":"$beta"}""",
        ),
        SseFrame(
            "6",
            "tool-output",
            """{"source":"codex","tool_name":"Bash","tool_input":{"command":"npm test"},"tool_output":null,"sessionId":"$beta"}""",
        ),
    )

    private fun fold(frames: List<SseFrame>, initial: BridgeState = BridgeState()): BridgeState =
        frames.fold(initial) { state, frame ->
            when (val result = BridgeEventReducer.reduce(state, frame, 1_000_000L)) {
                is BridgeEventReducer.Applied -> result.state
                is BridgeEventReducer.Rejected ->
                    throw AssertionError("fixture frame ${frame.id} rejected: ${result.error}")
            }
        }

    private fun ui(bridge: BridgeState) =
        BridgeViewModel.UiState(status = "paired, stream open", paired = true, bridge = bridge)

    /** Home → the session list pager: the face tap, v3's ONE list entry (the
     *  centerpiece carries a 300ms swipe-suppression guard on real uptime,
     *  waited out in case a page swipe preceded). */
    private fun drillToList() {
        Thread.sleep(350)
        compose.onNodeWithTag("haloCenter").performClick()
        compose.waitForIdle()
    }

    /**
     * Step the pager to a session's card. One session per screen now: an
     * out-of-view session isn't a lazy-list row to scroll to but a slot to
     * STEP to — chevron clicks, so the card-tap swipe guard never arms.
     */
    private fun stepToCard(sessionId: String) {
        var steps = 0
        while (
            steps < 10 &&
            compose.onAllNodes(hasTestTag("haloPagerCard-$sessionId")).fetchSemanticsNodes().isEmpty()
        ) {
            compose.onNodeWithTag("haloNext").performClick()
            compose.waitForIdle()
            steps++
        }
    }

    private fun openFeed(sessionId: String) {
        stepToCard(sessionId)
        // The card's tap opens the actions MENU (#114); the feed lives
        // behind the menu's own "open feed" row.
        compose.onNodeWithTag("haloPagerCard-$sessionId").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloMenuFeed").performClick()
        compose.waitForIdle()
    }

    @Test
    fun everySessionIsReachableFromHomeAndItsFeedRendersItsOwnLines() {
        val bridge = fold(fixtureFrames())
        assertEquals(2, bridge.sessions.size)
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }

        // Home: the glance census counts both sessions across both projects.
        // The census lives inside the clickable centerpiece (its whole area is
        // the tap-to-open target, so it mergeDescendants); its testTag is only
        // visible in the unmerged tree.
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("2 projects · 2 sessions").assertIsDisplayed()

        // Tap the face: the pager lands on the scope's first card (alpha).
        drillToList()
        compose.onNodeWithTag("haloPagerCard-$alpha").assertIsDisplayed()

        // Alpha's feed — human-readable, ANSI-stripped lines, no
        // cross-contamination from beta.
        openFeed(alpha)
        compose.onNodeWithTag("haloFeed-$alpha").assertIsDisplayed()
        compose.onNodeWithText("Welcome to Claude Code!").assertIsDisplayed()
        compose.onNodeWithText("Read README.md").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodes(hasText("npm test", substring = true)).fetchSemanticsNodes().size,
        )

        // Swipe right (v3's one gesture back) steps back to the pager — WITH
        // the selection preserved (back-from-feed keeps the session, #95),
        // so alpha's own card is up and beta is one step away; beta's feed
        // renders ITS lines.
        fingerDrag("haloFeed-$alpha", Offset(30f, 0f))
        compose.onNodeWithTag("haloPagerCard-$alpha").assertIsDisplayed()
        openFeed(beta)
        compose.onNodeWithTag("haloFeed-$beta").assertIsDisplayed()
        compose.onNode(hasText("npm test", substring = true)).assertIsDisplayed()
    }

    /**
     * The v3 vertical purge's regression pin (#109, user-decided
     * 2026-08-02): vertical drags NAVIGATE NOWHERE any more. Swipe-up on
     * home no longer drills (the face tap is the one list entry) and
     * swipe-down on the pager no longer backs out — both stay put. Real
     * finger frame-by-frame drags, so a rebuilt detector could not hide
     * behind batched-injection artifacts.
     */
    @Test
    fun verticalDragsNoLongerNavigateAnywhere() {
        val bridge = fold(fixtureFrames())
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }

        // Swipe up on home: still home — no drill.
        fingerDrag("haloRoot", Offset(0f, -30f))
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()

        // Swipe down on the pager: still the pager — no back.
        drillToList()
        compose.onNodeWithTag("haloPagerCard-$alpha").assertIsDisplayed()
        fingerDrag("haloRoot", Offset(0f, 30f))
        compose.onNodeWithTag("haloPagerCard-$alpha").assertIsDisplayed()
    }

    /** A real finger's frame-by-frame drag on [tag]: distinct timestamped
     *  moves, because per-delta detectors and nested scroll must see what a
     *  finger produces — a batched swipe crosses everything in deltas no
     *  finger makes and has greened broken gesture paths before. */
    private fun fingerDrag(tag: String, step: Offset) {
        compose.onNodeWithTag(tag).performTouchInput {
            down(center)
            repeat(10) { moveBy(step, delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
    }

    /**
     * The v2 feed (S6) scrolls by TOUCH on its reversed list — content
     * scrolling explicitly SURVIVES the v3 purge (it is not navigation) —
     * but the old at-top pull-down back is GONE: a pull past the top now
     * stays in the feed, and only the swipe-right leaves it. Real per-frame
     * drags on the emulator.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun feedTouchScrollsHistoryAndOnlySwipeRightGoesBack() {
        val history = (0 until 60).map { i ->
            SseFrame("${10 + i}", "pty-output", """{"text":"history-line-$i\r\n","sessionId":"$alpha"}""")
        }
        val bridge = fold(fixtureFrames() + history)
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }
        drillToList()
        openFeed(alpha)

        // At the tail: the newest line is on screen, the oldest is not even
        // composed (lazy) — the feed rests bottom-anchored.
        compose.onNodeWithText("history-line-59").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("$ claude")).fetchSemanticsNodes().size)

        // Touch-scroll into history: content follows the finger DOWN
        // (reverseLayout renders older lines upward) until the oldest line —
        // the visual top — arrives.
        var drags = 0
        while (drags < 40 && compose.onAllNodes(hasText("$ claude")).fetchSemanticsNodes().isEmpty()) {
            fingerDrag("haloFeed-$alpha", Offset(0f, 45f))
            drags++
        }
        compose.onNodeWithText("$ claude").assertIsDisplayed()

        // Rotary still scrolls too (kept verbatim from the rotary-only v1
        // feed): positive detents walk back toward the tail — the crown
        // direction that matches the session list's. Loop until the newest
        // line recomposes, then a few bound-absorbed extras so "composed a
        // screen away" has settled into "resting at the tail".
        var spins = 0
        while (
            spins < 20 &&
            compose.onAllNodes(hasText("history-line-59")).fetchSemanticsNodes().isEmpty()
        ) {
            compose.onNodeWithTag("haloFeed-$alpha")
                .performRotaryScrollInput { rotateToScrollVertically(800f) }
            compose.waitForIdle()
            spins++
        }
        repeat(3) {
            compose.onNodeWithTag("haloFeed-$alpha")
                .performRotaryScrollInput { rotateToScrollVertically(800f) }
            compose.waitForIdle()
        }
        compose.onNodeWithText("history-line-59").assertIsDisplayed()

        // Touch-scroll back to the visual top for the purge pin.
        drags = 0
        while (drags < 40 && compose.onAllNodes(hasText("$ claude")).fetchSemanticsNodes().isEmpty()) {
            fingerDrag("haloFeed-$alpha", Offset(0f, 45f))
            drags++
        }
        compose.onNodeWithText("$ claude").assertIsDisplayed()

        // The purge pin: pulls past the resting top used to be back — now
        // they are dead ends, the feed STAYS (vertical = scroll, never
        // navigation).
        repeat(3) { fingerDrag("haloFeed-$alpha", Offset(0f, 45f)) }
        compose.onNodeWithTag("haloFeed-$alpha").assertIsDisplayed()

        // Swipe right is the one way back to the pager, selection
        // preserved (#95).
        fingerDrag("haloFeed-$alpha", Offset(30f, 0f))
        compose.onNodeWithTag("haloPagerCard-$alpha").assertIsDisplayed()
    }

    /** Swipe right on the feed = back to the pager on the SAME session (v2:
     *  sibling cycling died with the feed header — position lives in the
     *  list pager now, so the gesture that used to cycle now goes back). */
    @Test
    fun feedSwipeRightReturnsToThePagerOnTheSameSession() {
        val bridge = fold(fixtureFrames())
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }
        drillToList()
        openFeed(beta)
        compose.onNodeWithTag("haloFeed-$beta").assertIsDisplayed()

        fingerDrag("haloFeed-$beta", Offset(30f, 0f))
        // Not the first card of the scope: landing on beta's own card proves
        // swipe-right is BACK-preserving-selection, not a sibling cycle.
        compose.onNodeWithTag("haloPagerCard-$beta").assertIsDisplayed()
    }

    @Test
    fun thinkingIndicatorRendersOnCommandEchoAndClearsOnNextOutput() {
        val initial = fold(fixtureFrames())
        var bridge by mutableStateOf(initial)
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }
        drillToList()
        openFeed(alpha)

        // No indicator while idle-on-arrival.
        assertEquals(0, compose.onAllNodes(hasTestTag("haloThinking")).fetchSemanticsNodes().size)

        // Command echo raises the indicator and shows the echoed user line.
        bridge = bridge.echoCommand(alpha, "say hello")
        compose.waitForIdle()
        compose.onNodeWithText("> say hello").assertIsDisplayed()
        compose.onNodeWithTag("haloThinking").assertIsDisplayed()

        // The next output for the session clears it.
        bridge = fold(
            listOf(SseFrame("7", "pty-output", """{"text":"hello!\r\n","sessionId":"$alpha"}""")),
            initial = bridge,
        )
        compose.waitForIdle()
        compose.onNodeWithText("hello!").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag("haloThinking")).fetchSemanticsNodes().size)
    }

    @Test
    fun settingsIsTheLeftmostPageAndConfirmedUnpairFires() {
        val bridge = fold(fixtureFrames())
        var unpairs = 0
        compose.setContent {
            HaloApp(ui = ui(bridge), actions = HaloActions(onUnpair = { unpairs++ }))
        }
        // Home is the landing page (slot 2): the census in the merged centerpiece.
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()

        // Two pages right: home → usage → settings, the new leftmost slot.
        repeat(2) {
            compose.onNodeWithTag("haloRoot").performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(width / 12f, 0f), delayMillis = 16L) }
                up()
            }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("haloSettings").assertIsDisplayed()

        // Confirm-gated Unpair: one tap only ARMS (no wipe), the second fires.
        compose.onNodeWithTag("haloSettingsUnpair").performClick()
        compose.waitForIdle()
        assertEquals("a lone tap never unpairs", 0, unpairs)
        compose.onNodeWithTag("haloSettingsUnpair").performClick()
        compose.waitForIdle()
        assertEquals("confirm-then-tap fires onUnpair once", 1, unpairs)
    }

    /**
     * The page dots ride an arc concentric with the status ring (a straight row
     * clears it only at 6 o'clock and its end dots collide with the curve), so
     * their tap targets are placed by hand-rolled polar geometry instead of a
     * Row. Both ENDS of the arc — the dots that moved furthest — must still
     * select their own page.
     */
    @Test
    fun curvedPageDotsSelectTheirOwnPage() {
        val bridge = fold(fixtureFrames())
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()

        // Last dot: the second project's page (5 pages — settings, usage, home,
        // alpha, beta).
        compose.onNodeWithTag("haloDot-4").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("beta").assertIsDisplayed()

        // First dot: settings, the far end of the arc.
        compose.onNodeWithTag("haloDot-0").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloSettings").assertIsDisplayed()
    }

    @Test
    fun killedSessionsFeedBacksOutOntoTheHealedPager() {
        var bridge by mutableStateOf(fold(fixtureFrames()))
        compose.setContent { HaloApp(ui = ui(bridge), actions = HaloActions()) }
        drillToList()
        openFeed(alpha)
        compose.onNodeWithTag("haloFeed-$alpha").assertIsDisplayed()

        // The bridge announces alpha's death (killed via /v1/command).
        bridge = fold(
            listOf(
                SseFrame(
                    "8",
                    "session",
                    """{"state":"ended","agent":"claude","folderName":"alpha","killed":true,"sessionId":"$alpha"}""",
                ),
            ),
            initial = bridge,
        )
        compose.waitForIdle()

        // The dead feed backs out to the pager, which parks the dead id as
        // the LIST selection (#95) — and the self-heal re-resolves it to the
        // remembered-index neighbour: beta's card, not a ghost with every
        // step a no-op.
        assertEquals(0, compose.onAllNodes(hasTestTag("haloFeed-$alpha")).fetchSemanticsNodes().size)
        assertEquals(
            0,
            compose.onAllNodes(hasTestTag("haloPagerCard-$alpha")).fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("haloPagerCard-$beta").assertIsDisplayed()
    }
}
