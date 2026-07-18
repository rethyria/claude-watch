package dev.claudewatch.wear

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The spawn target picker (issue #56), driven with fixture UiStates — no
 * bridge, no network. The list's "+ new claude session" row opens the picker
 * instead of spawning blind; picking a project fires onSpawn with THAT
 * project's root (the MAIN checkout for a worktree-only project), "no
 * project" fires the "~" home sentinel, and the swipe-down cancel spawns
 * NOTHING — with the underlying list's own gestures (row strip, rebuilt
 * swipe-down-back) intact afterwards.
 */
@RunWith(AndroidJUnit4::class)
class HaloSpawnPickerTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Two projects: alpha is represented ONLY by a worktree session (its
     * spawn root must be the MAIN checkout, /home/dev/alpha — never the
     * throwaway worktree dir), beta by a plain session (root = its cwd).
     */
    private fun fixtureBridge() = BridgeState(
        sessions = mapOf(
            "s-wt" to SessionState(
                sessionId = "s-wt",
                agent = "claude",
                cwd = "/home/dev/worktrees/alpha-issue-53",
                folderName = "alpha-issue-53",
                branch = "issue-53-fix",
                worktree = true,
                repoRoot = "/home/dev/alpha",
            ),
            "s-b" to SessionState(
                sessionId = "s-b",
                agent = "claude",
                cwd = "/home/dev/beta",
                folderName = "beta",
            ),
        ),
    )

    private fun ui() = BridgeViewModel.UiState(
        status = "paired, stream open",
        paired = true,
        bridge = fixtureBridge(),
    )

    private fun setContent(spawns: MutableList<Pair<String, String?>>) {
        compose.setContent {
            HaloApp(
                ui = ui(),
                actions = HaloActions(onSpawn = { agent, cwd -> spawns += agent to cwd }),
            )
        }
    }

    /** Home → All list → tap the spawn row → the picker overlay is up. */
    private fun openPicker() {
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeUp() }
        compose.waitForIdle()
        // Only the session list scrolls at this point, so the bare matcher
        // is unambiguous; once the picker is up there are TWO scrollables
        // and every further scroll must be ancestor-scoped.
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("haloSpawn"))
        compose.onNodeWithTag("haloSpawn").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloSpawnPicker").assertIsDisplayed()
    }

    /** Scroll the PICKER's lazy list until [tag] composes (see [openPicker]). */
    private fun scrollPickerTo(tag: String) {
        compose.onNode(
            hasScrollAction() and hasAnyAncestor(hasTestTag("haloSpawnPicker")),
        ).performScrollToNode(hasTestTag(tag))
    }

    private fun pickerCount(): Int =
        compose.onAllNodes(hasTestTag("haloSpawnPicker")).fetchSemanticsNodes().size

    @Test
    fun pickingAProjectSpawnsInItsMainRootAndCloses() {
        val spawns = mutableListOf<Pair<String, String?>>()
        setContent(spawns)
        openPicker()

        scrollPickerTo("haloSpawnPick-alpha")
        compose.onNodeWithTag("haloSpawnPick-alpha").performClick()
        compose.waitForIdle()

        // The worktree-only project spawns in the MAIN checkout.
        assertEquals(listOf("claude" to "/home/dev/alpha"), spawns)
        assertEquals("a pick closes the picker", 0, pickerCount())
    }

    @Test
    fun noProjectSpawnsTheHomeSentinel() {
        val spawns = mutableListOf<Pair<String, String?>>()
        setContent(spawns)
        openPicker()

        scrollPickerTo("haloSpawnPickHome")
        compose.onNodeWithTag("haloSpawnPickHome").performClick()
        compose.waitForIdle()

        // "~" is the wire sentinel the bridge resolves to ITS user's home.
        assertEquals(listOf("claude" to "~"), spawns)
        assertEquals(0, pickerCount())
    }

    @Test
    fun swipeDownCancelsWithoutSpawningAndTheListKeepsItsGestures() {
        val spawns = mutableListOf<Pair<String, String?>>()
        setContent(spawns)
        openPicker()

        // A real finger's pull-down: frame-by-frame moves, never a batched
        // swipe — the picker's cancel is rebuilt from nested-scroll leftovers
        // exactly like the list's back, the very interaction the API 31+
        // stretch-overscroll used to eat (a batched swipe crosses the
        // threshold in one delta and false-greens over the broken finger path).
        compose.onNodeWithTag("haloSpawnPicker").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 30f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()

        assertEquals("cancel spawns nothing", 0, spawns.size)
        assertEquals("cancel closes the picker", 0, pickerCount())

        // The list underneath is intact: a row swipe still reveals the
        // action strip…
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("haloRow-s-b"))
        compose.onNodeWithTag("haloRow-s-b").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNode(
            hasTestTag("haloRowClose") and hasAnyAncestor(hasTestTag("haloRow-s-b")),
        ).assertIsDisplayed()

        // …and the rebuilt swipe-down-back still steps home. Back fires only
        // from the list's TOP BOUND, and openPicker() scrolled to the bottom
        // (the spawn row): scroll explicitly to item 0 (the title — the true
        // bound; scrolling to a ROW leaves backward room that eats the pull).
        // Frame-by-frame for the same stretch-overscroll reason, with enough
        // travel to cover any residual pre-bound scroll.
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performTouchInput {
            down(center)
            repeat(14) { moveBy(Offset(0f, 30f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("still no spawn after the whole dance", spawns.isEmpty())
    }
}
