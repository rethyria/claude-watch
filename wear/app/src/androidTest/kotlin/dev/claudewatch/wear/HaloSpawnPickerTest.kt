package dev.claudewatch.wear

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
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
 * bridge, no network. The pager's trailing "+ new session" card (the v2
 * rendition of the old list row, same testTag) opens the picker instead of
 * spawning blind; picking a project fires onSpawn with THAT project's root
 * (the MAIN checkout for a worktree-only project), "no project" fires the "~"
 * home sentinel, and the swipe-down cancel spawns NOTHING — with the pager
 * underneath keeping its own gestures afterwards.
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

    /** Home → pager → step to the trailing spawn card → tap → picker up. */
    private fun openPicker() {
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeUp() }
        compose.waitForIdle()
        // The spawn card is the All pager's true end: step ›-wards until it
        // is the current card (chevron clicks, so no swipe tap-guard arms).
        var steps = 0
        while (
            steps < 10 &&
            compose.onAllNodes(hasTestTag("haloSpawn")).fetchSemanticsNodes().isEmpty()
        ) {
            compose.onNodeWithTag("haloNext").performClick()
            compose.waitForIdle()
            steps++
        }
        compose.onNodeWithTag("haloSpawn").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloSpawnPicker").assertIsDisplayed()
    }

    /** Scroll the PICKER's lazy list until [tag] composes (offscreen entries
     *  aren't nodes yet; ancestor-scoped because the pager sits underneath). */
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
    fun swipeDownCancelsWithoutSpawningAndThePagerKeepsItsGestures() {
        val spawns = mutableListOf<Pair<String, String?>>()
        setContent(spawns)
        openPicker()

        // A real finger's pull-down: frame-by-frame moves, never a batched
        // swipe — the picker's cancel is rebuilt from nested-scroll leftovers,
        // the very interaction the API 31+ stretch-overscroll used to eat (a
        // batched swipe crosses the threshold in one delta and false-greens
        // over the broken finger path).
        compose.onNodeWithTag("haloSpawnPicker").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 30f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()

        assertEquals("cancel spawns nothing", 0, spawns.size)
        assertEquals("cancel closes the picker", 0, pickerCount())

        // The pager underneath is intact, still on the spawn card it was
        // summoned from: ‹ steps back to the last session card with its
        // action arc live…
        compose.onNodeWithTag("haloSpawn").assertIsDisplayed()
        compose.onNodeWithTag("haloPrev").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-s-b").assertIsDisplayed()
        compose.onNodeWithTag("haloRowClose").assertIsDisplayed()

        // …and the app-wide swipe-down-back still steps home. Frame-by-frame
        // for the same real-finger injection discipline.
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(center)
            repeat(14) { moveBy(Offset(0f, 30f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("still no spawn after the whole dance", spawns.isEmpty())
    }
}
