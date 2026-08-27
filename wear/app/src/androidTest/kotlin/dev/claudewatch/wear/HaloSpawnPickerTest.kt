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
 * home sentinel, and the trailing cancel row (v3: the purged pull-down
 * cancel's tappable successor) spawns NOTHING — with the pager underneath
 * keeping its own gestures afterwards. A PROJECT pager's spawn card (#130)
 * opens the same picker with its own project PRESELECTED — hoisted to the
 * top row — so the confirm is one tap and the request carries that
 * project's spawn root.
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

    /** Home → pager → step to the trailing spawn card → tap → picker up.
     *  The face tap is v3's one list entry. */
    private fun openPicker() {
        compose.onNodeWithTag("haloCenter").performClick()
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

    /** Project page → pager → its trailing spawn card → the SCOPED picker
     *  (#130). Dot taps walk the pages (no swipe guard arms); dot slots are
     *  settings 0, usage 1, All 2, then the projects NEWEST-FIRST (matching
     *  Zed) — beta 3, alpha 4. */
    // Walks a PROJECT pager to its trailing spawn card and taps it. Since
    // #130's user-directed revision the tap spawns DIRECTLY — no picker —
    // so this ends at the tap; callers assert the spawn that resulted.
    private fun tapScopedSpawnCard(dot: Int, lastCardTag: String) {
        compose.onNodeWithTag("haloDot-$dot").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCenter").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(lastCardTag).assertIsDisplayed()
        compose.onNodeWithTag("haloNext").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloSpawn").performClick()
        compose.waitForIdle()
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
    fun projectScopedSpawnCardSpawnsDirectlyInItsOwnProject() {
        val spawns = mutableListOf<Pair<String, String?>>()
        setContent(spawns)
        // Beta is FIRST in model order — projects run newest-first to match
        // Zed, and beta was opened after alpha. Alpha's card at the far dot
        // (the next test) spawning alpha's root is what proves the resolution
        // is per-scope rather than an accident of leading-project order.
        tapScopedSpawnCard(dot = 3, lastCardTag = "haloPagerCard-s-b")
        assertEquals(listOf("claude" to "/home/dev/beta"), spawns)
        assertEquals("no picker for a project-scoped spawn", 0, pickerCount())
    }

    @Test
    fun projectScopedSpawnCarriesTheMainCheckoutForAWorktreeProject() {
        val spawns = mutableListOf<Pair<String, String?>>()
        setContent(spawns)
        // Alpha exists only through a worktree session: the direct spawn
        // must carry the MAIN checkout — the same repoRoot-beats-cwd rule
        // as the unscoped pick — never the throwaway worktree dir.
        tapScopedSpawnCard(dot = 4, lastCardTag = "haloPagerCard-s-wt")
        assertEquals(listOf("claude" to "/home/dev/alpha"), spawns)
        assertEquals(0, pickerCount())
    }

    @Test
    fun cancelRowCancelsWithoutSpawningAndThePagerKeepsItsGestures() {
        val spawns = mutableListOf<Pair<String, String?>>()
        setContent(spawns)
        openPicker()

        // The trailing cancel row — v3's tappable successor to the purged
        // pull-down cancel (the overlay must keep a visible non-gesture
        // escape; the system back is its twin, pinned in HaloSystemBackTest).
        scrollPickerTo("haloSpawnCancel")
        compose.onNodeWithTag("haloSpawnCancel").performClick()
        compose.waitForIdle()

        assertEquals("cancel spawns nothing", 0, spawns.size)
        assertEquals("cancel closes the picker", 0, pickerCount())

        // The pager underneath is intact, still on the spawn card it was
        // summoned from: ‹ steps back to the last session card — which is
        // alpha's, since projects run newest-first (beta leads) and the All
        // pager is the flatten of that grouping.
        compose.onNodeWithTag("haloSpawn").assertIsDisplayed()
        compose.onNodeWithTag("haloPrev").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-s-wt").assertIsDisplayed()

        // …and the pager's own swipe-rights still walk out: card by card to
        // the first slot, then off the list to home. Frame-by-frame for the
        // real-finger injection discipline.
        repeat(2) {
            compose.onNodeWithTag("haloRoot").performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(width / 12f, 0f), delayMillis = 16L) }
                up()
            }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("still no spawn after the whole dance", spawns.isEmpty())
    }
}
