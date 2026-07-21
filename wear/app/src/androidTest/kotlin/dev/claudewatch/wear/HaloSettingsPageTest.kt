package dev.claudewatch.wear

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings page — the leftmost pager slot (issue #57), one page LEFT of the
 * usage page — driven with a fixture UiState and a RECORDED onUnpair seam (no
 * bridge, no network). It is reachable from home by swiping right TWICE (home →
 * usage → settings), renders the flat glance surface, and its Unpair is
 * confirm-gated: a single tap only ARMS ("tap to confirm"), and only a second
 * tap on the armed control fires the destructive onUnpair — exactly once. A
 * stray swipe-tap that overshoots onto this leftmost page must never wipe the
 * pairing.
 */
@RunWith(AndroidJUnit4::class)
class HaloSettingsPageTest {

    @get:Rule
    val compose = createComposeRule()

    private fun fixtureBridge() = BridgeState(
        sessions = mapOf(
            "s-1" to SessionState(
                sessionId = "s-1",
                agent = "claude",
                cwd = "/home/dev/alpha",
                folderName = "alpha",
            ),
        ),
    )

    private fun ui() = BridgeViewModel.UiState(
        status = "paired, stream open",
        paired = true,
        bridge = fixtureBridge(),
    )

    /**
     * Pager swipes as a real finger drags them: frame-by-frame moves (a batched
     * swipe collapses into one frame-sized delta and can behave nothing like a
     * finger on API 31+ surfaces). Each call settles the HorizontalPager one
     * page RIGHT (toward the lower indices — usage, then settings).
     */
    private fun onePageRight() {
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(width / 12f, 0f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
    }

    /** Home (initial page) → usage → settings: two pages right. */
    private fun swipeToSettings() {
        onePageRight() // home → usage
        onePageRight() // usage → settings
    }

    @Test
    fun swipingRightTwiceReachesTheSettingsPageLeftmost() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        // Home is the initial page (the census inside the merged centerpiece).
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()

        swipeToSettings()
        compose.onNodeWithTag("haloSettings").assertIsDisplayed()
        compose.onNodeWithText("settings").assertIsDisplayed()
        // The honest connection line above the destructive Unpair.
        compose.onNodeWithText("paired, stream open").assertIsDisplayed()
        compose.onNodeWithTag("haloSettingsUnpair").assertIsDisplayed()
        compose.onNodeWithText("Unpair").assertIsDisplayed()
    }

    @Test
    fun aSingleTapArmsButDoesNotUnpair() {
        var unpairs = 0
        compose.setContent {
            HaloApp(ui = ui(), actions = HaloActions(onUnpair = { unpairs++ }))
        }
        swipeToSettings()
        compose.onNodeWithTag("haloSettingsUnpair").assertIsDisplayed()

        // One tap: the control ARMS (label flips) but nothing fires — a stray
        // swipe-tap onto the leftmost page can't wipe the pairing.
        compose.onNodeWithTag("haloSettingsUnpair").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("tap to confirm").assertIsDisplayed()
        assertEquals("a lone tap never fires the destructive unpair", 0, unpairs)
    }

    @Test
    fun confirmThenTapInvokesOnUnpairExactlyOnce() {
        var unpairs = 0
        compose.setContent {
            HaloApp(ui = ui(), actions = HaloActions(onUnpair = { unpairs++ }))
        }
        swipeToSettings()

        // Tap one arms, tap two on the armed control fires — exactly once.
        compose.onNodeWithTag("haloSettingsUnpair").performClick()
        compose.waitForIdle()
        assertEquals(0, unpairs)
        compose.onNodeWithTag("haloSettingsUnpair").performClick()
        compose.waitForIdle()
        assertEquals("confirm-then-tap fires onUnpair exactly once", 1, unpairs)
    }

    @Test
    fun theSettingsPageHasNoDrillDown() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        swipeToSettings()
        compose.onNodeWithTag("haloSettings").assertIsDisplayed()

        // Swipe up — the drill gesture — must be a no-op here (HaloNav's
        // settings/usage guard): still the settings page, no session list.
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -height / 12f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("haloSettings").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodes(hasText("all sessions")).fetchSemanticsNodes().size,
        )
    }
}
