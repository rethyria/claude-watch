package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.protocol.PermissionOption
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
 * The v2 session-list pager (Halo v2 S5, #99; action arc → actions menu by
 * #114), driven with fixture UiStates — no bridge, no network. One session
 * per screen: entry from home and project pages lands on the scope's first
 * card, swipes and chevrons step with no wrap (every scope ends on the
 * trailing spawn card — a project's since #130), stepping right at the
 * start is BACK, a card tap opens the session-actions MENU (the feed lives
 * behind its "open feed" row) while the waiting card's Answer pill opens the
 * prompt OVER the pager (never falling through to the menu), the menu's
 * close row carries honest close semantics (✕ kill wherever the bridge can
 * really end the session — owned PTY or ACP via #88's close frame — ⊘ hide
 * for a hook-observed one, stubs visible but dead, every row finger-sized)
 * and renders LAST below the stubs (#116: destructive at the bottom), and a
 * session killed under the cursor self-heals to the remembered-index
 * neighbour instead of stranding the pager on a ghost.
 */
@RunWith(AndroidJUnit4::class)
class HaloSessionPagerTest {

    @get:Rule
    val compose = createComposeRule()

    /** alpha: two owned sessions (one waiting); beta: one EXTERNAL session. */
    private fun fixtureBridge(vararg ids: String) = BridgeState(
        sessions = listOf(
            "s-a1" to SessionState(
                sessionId = "s-a1",
                agent = "claude",
                cwd = "/home/dev/alpha",
                folderName = "alpha",
            ),
            "s-a2" to SessionState(
                sessionId = "s-a2",
                agent = "claude",
                cwd = "/home/dev/alpha",
                folderName = "alpha",
            ),
            "s-b1" to SessionState(
                sessionId = "s-b1",
                agent = "claude",
                cwd = "/home/dev/beta",
                folderName = "beta",
                external = true,
            ),
            // An ACP session: external (Zed's process) AND killable, because
            // the bridge can end it through the adapter's close frame (#88).
            // Opt-in only — the default fixture keeps three cards.
            "s-b2" to SessionState(
                sessionId = "s-b2",
                agent = "claude",
                cwd = "/home/dev/beta",
                folderName = "beta",
                external = true,
                kind = "acp",
                dictatable = true,
            ),
        ).filter { it.first in ids }.toMap(),
    )

    private val alphaPrompt = BridgeViewModel.PendingPermission(
        permissionId = "perm-a2",
        sessionId = "s-a2",
        toolName = "Write",
        requestSummary = "Write notes.txt",
        sessionLabel = "alpha",
        options = listOf(PermissionOption("allow", "Yes"), PermissionOption("deny", "No")),
    )

    private fun ui(
        ids: List<String> = listOf("s-a1", "s-a2", "s-b1"),
        queue: List<BridgeViewModel.PendingPermission> = listOf(alphaPrompt),
    ) = BridgeViewModel.UiState(
        status = "paired, stream open",
        paired = true,
        bridge = fixtureBridge(*ids.toTypedArray()),
        // A prompt whose session left the fixture would resurface as an
        // orphan synthetic session; keep the queue consistent with the ids.
        permissionQueue = queue.filter { it.sessionId in ids },
    )

    /** Home/project page → the pager: the face tap, v3's ONE list entry (the
     *  centerpiece carries a 300ms swipe-suppression guard on real uptime,
     *  waited out in case a page swipe preceded). */
    private fun drill() {
        Thread.sleep(350)
        compose.onNodeWithTag("haloCenter").performClick()
        compose.waitForIdle()
    }

    private fun next() {
        compose.onNodeWithTag("haloNext").performClick()
        compose.waitForIdle()
    }

    /** Frame-by-frame page swipe (the real-finger injection discipline). */
    private fun onePageRight() {
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-width / 12f, 0f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
    }

    private fun tagCount(tag: String): Int =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun textCount(text: String): Int =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    /** The open menu's scrollable list. The close row is the menu's LAST row
     *  now (#116: destructive at the bottom, below the stubs) and the list is
     *  LAZY — scroll a row into composition before matching on it. */
    private fun menuList() =
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("haloSessionMenu")))

    @Test
    fun entryLandsOnTheScopesFirstCardFromHomeAndProjectPages() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }

        // Home → the All pager: first card is the flat order's first session.
        drill()
        compose.onNodeWithTag("haloPagerCard-s-a1").assertIsDisplayed()
        // Back out from the first card: swipe right (the v3 at-start rule —
        // the swipe-down back died in the vertical purge).
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeRight() }
        compose.waitForIdle()

        // Beta's page → ITS pager: the project scope's first (and only)
        // session card. #130: the project scope ends on the SAME trailing
        // spawn card as All now, so › stays visible on the last session…
        onePageRight()
        onePageRight()
        compose.onNodeWithText("beta").assertIsDisplayed()
        drill()
        compose.onNodeWithTag("haloPagerCard-s-b1").assertIsDisplayed()
        compose.onNodeWithText("›").assertIsDisplayed()

        // …one step lands on the spawn card — the true end, where › hides —
        // and ‹ steps back onto the session, not out of the list (no wrap).
        next()
        compose.onNodeWithTag("haloSpawn").assertIsDisplayed()
        assertEquals("› hides on the project scope's spawn card", 0, textCount("›"))
        compose.onNodeWithTag("haloPrev").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-s-b1").assertIsDisplayed()
    }

    @Test
    fun stepsBySwipeAndChevronWithTheSpawnCardAsTheTrueEnd() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()
        compose.onNodeWithTag("haloPagerCard-s-a1").assertIsDisplayed()

        // Swipe left = next (content follows the finger).
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()

        // Chevron steps too; the All scope ends on the spawn card, where ›
        // goes invisible (the cell keeps its space, the glyph goes).
        next()
        compose.onNodeWithTag("haloPagerCard-s-b1").assertIsDisplayed()
        compose.onNodeWithText("›").assertIsDisplayed()
        next()
        compose.onNodeWithTag("haloSpawn").assertIsDisplayed()
        assertEquals("› hides on the true last slot", 0, textCount("›"))

        // No wrap: ‹ steps back off the spawn card onto the last session.
        compose.onNodeWithTag("haloPrev").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-s-b1").assertIsDisplayed()
    }

    @Test
    fun steppingRightAtTheFirstCardIsBack() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }

        // By swipe…
        drill()
        compose.onNodeWithTag("haloPagerCard-s-a1").assertIsDisplayed()
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()

        // …and by the ‹ chevron: the same at-start rule.
        drill()
        compose.onNodeWithTag("haloPrev").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun cardTapOpensTheActionsMenuAndItsOpenFeedRowReachesTheFeed() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()
        // The tap's destination is the MENU now (#114), never the feed.
        compose.onNodeWithTag("haloPagerCard-s-a1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloSessionMenu").assertIsDisplayed()
        assertEquals("a card tap must not drill into the feed", 0, tagCount("haloFeed-s-a1"))

        // "Open feed" goes where the tap used to.
        compose.onNodeWithTag("haloMenuFeed").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloFeed-s-a1").assertIsDisplayed()
        assertEquals(0, tagCount("haloSessionMenu"))
    }

    @Test
    fun menuBackReturnsToItsOwnCardAndTheFeedsBackSkipsTheMenu() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()
        next()
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()

        // Swipe right on the menu: back onto the SAME card — one step in,
        // never a list step under the floating menu.
        compose.onNodeWithTag("haloPagerCard-s-a2").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloSessionMenu").assertIsDisplayed()
        compose.onNodeWithTag("haloSessionMenu").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(0, tagCount("haloSessionMenu"))
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()

        // Through the menu into the feed, then back: the feed's swipe-right
        // lands on the pager card DIRECTLY — the menu is a pass-through
        // launcher, not a second stop on the way out.
        compose.onNodeWithTag("haloPagerCard-s-a2").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloMenuFeed").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloFeed-s-a2").assertIsDisplayed()
        compose.onNodeWithTag("haloFeed-s-a2").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()
        assertEquals("the feed's back must skip the menu", 0, tagCount("haloSessionMenu"))
    }

    @Test
    fun answerPillOpensTheCardOverThePagerWithoutFallingThroughToTheMenu() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()
        next()
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()

        // The pill is its own click target ABOVE the card's: tapping it must
        // raise the session's OWN prompt, not the menu underneath (#114 —
        // the card's whole surface summons the menu now).
        compose.onNodeWithTag("haloAnswerPill").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        compose.onNodeWithText("Write notes.txt").assertIsDisplayed()
        assertEquals("answering must not summon the menu", 0, tagCount("haloSessionMenu"))

        // "Decide later" (the explicit control — v3 purged the card's
        // swipe-down) lands right back on the same pager card — the whole
        // point of opening OVER the list.
        compose.onNodeWithTag("haloDecideLater").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(0, tagCount("haloCard"))
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()
        assertEquals(0, tagCount("haloSessionMenu"))
    }

    @Test
    fun answerPillOutranksTheCardsMenuTapInTheirOverlapBand() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()
        next()
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()

        // The pill stays the pager's TOPMOST layer (the 46f8489 hoist, kept
        // as defence-in-depth with the arc gone): the whole card beneath it
        // is the menu's tap target, so a finger aiming at Answer's lower
        // half must raise the PROMPT, never the actions menu — this tap
        // targets the pill's bottom band BY COORDINATE, not by node.
        val pill = compose.onNodeWithTag("haloAnswerPill").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(Offset(pill.center.x, pill.bottom - 2f))
            up()
        }
        compose.waitForIdle()
        assertEquals("a tap on the pill must never summon the menu", 0, tagCount("haloSessionMenu"))
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        compose.onNodeWithText("Write notes.txt").assertIsDisplayed()
    }

    @Test
    fun menuCloseKillsOwnedSessionsAndHidesExternalOnes() {
        val kills = mutableListOf<String>()
        val hides = mutableListOf<String>()
        compose.setContent {
            HaloApp(
                ui = ui(ids = listOf("s-a1", "s-a2", "s-b1", "s-b2")),
                actions = HaloActions(
                    onKill = { kills += it },
                    onHide = { hides += it },
                ),
            )
        }
        drill()

        /** Fire the open menu's close row; the action tap closes the menu
         *  back onto [sessionId]'s card (the fixture never shrinks here, so
         *  the card stays until a real model would remove it). */
        fun fireClose(sessionId: String) {
            compose.onNodeWithTag("haloRowClose").performClick()
            compose.waitForIdle()
            assertEquals("the action tap must close the menu", 0, tagCount("haloSessionMenu"))
            compose.onNodeWithTag("haloPagerCard-$sessionId").assertIsDisplayed()
        }

        // Owned session: the red ✕ row, wired to a REAL kill.
        compose.onNodeWithTag("haloPagerCard-s-a1").performClick()
        compose.waitForIdle()
        menuList().performScrollToNode(hasTestTag("haloRowClose"))
        compose.onNode(hasTestTag("haloRowClose") and hasText("✕")).assertIsDisplayed()
        fireClose("s-a1")
        assertEquals(listOf("s-a1"), kills)
        assertEquals(0, hides.size)

        // Hook-observed session: the honest ⊘ hide — never a fake kill (#53).
        next()
        next()
        compose.onNodeWithTag("haloPagerCard-s-b1").performClick()
        compose.waitForIdle()
        menuList().performScrollToNode(hasTestTag("haloRowClose"))
        compose.onNode(hasTestTag("haloRowClose") and hasText("⊘")).assertIsDisplayed()
        assertEquals(
            "a hook-observed session must not offer a kill",
            0,
            compose.onAllNodes(hasTestTag("haloRowClose") and hasText("✕")).fetchSemanticsNodes().size,
        )
        fireClose("s-b1")
        assertEquals(listOf("s-b1"), hides)
        assertEquals("hide must never kill", listOf("s-a1"), kills)

        // ACP session: external, but the bridge really can end it through the
        // adapter (#88's close frame) — so the ✕ is honest here, not a hide
        // wearing a kill's clothes.
        next()
        compose.onNodeWithTag("haloPagerCard-s-b2").performClick()
        compose.waitForIdle()
        menuList().performScrollToNode(hasTestTag("haloRowClose"))
        compose.onNode(hasTestTag("haloRowClose") and hasText("✕")).assertIsDisplayed()
        fireClose("s-b2")
        assertEquals(listOf("s-a1", "s-b2"), kills)
        assertEquals("an ACP close must not degrade to a local hide", listOf("s-b1"), hides)
    }

    @Test
    fun menuRowsAreFingerSizedWithStubsVisibleButDisabled() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()
        compose.onNodeWithTag("haloPagerCard-s-a1").performClick()
        compose.waitForIdle()

        // #114's headline acceptance: every action row is a full-width
        // fingertip target — ≥48dp tall — where the arc's 33dp cells could
        // not reach the minimum without overlapping. The menu list is LAZY
        // (and the close row its LAST, #116), so rows scroll into
        // composition before they are measured.
        val minPx = 48f * compose.density.density - 1f
        for (tag in listOf("haloMenuFeed", "haloRowClose")) {
            menuList().performScrollToNode(hasTestTag(tag))
            val row = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must be finger-sized (${row.height} < $minPx)", row.height >= minPx)
        }

        // The stubs keep their arc treatment: visible, dead, dimmed.
        for (tag in listOf("haloMenu-model", "haloMenu-mode", "haloMenu-compact", "haloMenu-handover")) {
            menuList().performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertExists().assertIsNotEnabled()
        }
    }

    @Test
    fun theCloseRowRendersLastBelowTheStubs() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()
        compose.onNodeWithTag("haloPagerCard-s-a1").performClick()
        compose.waitForIdle()

        // #116 (user feedback): destructive last. Resting on the close row
        // after the scroll, the LAST stub sits above it — this pins the
        // ORDER, not just the row's survival.
        menuList().performScrollToNode(hasTestTag("haloRowClose"))
        val close = compose.onNodeWithTag("haloRowClose").fetchSemanticsNode().boundsInRoot
        val lastStub = compose.onNodeWithTag("haloMenu-handover").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the close row (top ${close.top}) must render below the last stub (top ${lastStub.top})",
            close.top > lastStub.top,
        )
    }

    @Test
    fun spawnCardOpensThePickerNotTheMenu() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()
        // The spawn card has nothing to close or configure: its tap keeps
        // opening the target picker, never a session menu.
        repeat(3) { next() }
        compose.onNodeWithTag("haloSpawn").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloSpawnPicker").assertIsDisplayed()
        assertEquals(0, tagCount("haloSessionMenu"))
        assertEquals(0, tagCount("haloRowClose"))
    }

    @Test
    fun projectScopeStepsToItsOwnSpawnCardAndOneConfirmSpawnsInItsCwd() {
        val spawns = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            HaloApp(
                ui = ui(queue = emptyList()),
                actions = HaloActions(onSpawn = { agent, cwd -> spawns += agent to cwd }),
            )
        }

        // Alpha's page → ITS pager: the full #130 walk — s-a1 › s-a2 › the
        // trailing spawn card (the true end, › hidden).
        onePageRight()
        drill()
        compose.onNodeWithTag("haloPagerCard-s-a1").assertIsDisplayed()
        next()
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()
        next()
        compose.onNodeWithTag("haloSpawn").assertIsDisplayed()
        assertEquals(0, textCount("›"))

        // The tap spawns DIRECTLY (#130, user-directed: "instantly spawn for
        // that project, not a menu") — navigating into the project WAS the
        // choice, so no picker appears and the spawn request carries the
        // PROJECT's cwd at the action boundary.
        compose.onNodeWithTag("haloSpawn").performClick()
        compose.waitForIdle()
        assertEquals(listOf("claude" to "/home/dev/alpha"), spawns)
        assertEquals("no picker for a project-scoped spawn", 0, tagCount("haloSpawnPicker"))
    }

    @Test
    fun killUnderTheOpenMenuClosesItOntoTheHealedNeighbour() {
        var state by mutableStateOf(ui())
        compose.setContent { HaloApp(ui = state, actions = HaloActions()) }
        drill()
        next()
        compose.onNodeWithTag("haloPagerCard-s-a2").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloSessionMenu").assertIsDisplayed()

        // The menu's session dies under it (the bridge announcing a kill this
        // very menu fired, say): the heal closes the menu WITH the repair —
        // a surviving menu would offer the neighbour's ending under the dead
        // session's title.
        state = ui(ids = listOf("s-a1", "s-b1"))
        compose.waitForIdle()
        assertEquals(0, tagCount("haloSessionMenu"))
        compose.onNodeWithTag("haloPagerCard-s-b1").assertIsDisplayed()
    }

    @Test
    fun killUnderTheCursorHealsToTheRememberedIndexNeighbour() {
        var state by mutableStateOf(ui())
        compose.setContent { HaloApp(ui = state, actions = HaloActions()) }
        drill()
        next()
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()

        // The selected session (slot 1) dies: the heal lands on the session
        // NOW at slot 1 — its next-door neighbour — and the pager stays
        // steppable rather than stranding on a ghost with ‹/› no-ops.
        state = ui(ids = listOf("s-a1", "s-b1"))
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-s-b1").assertIsDisplayed()

        // The END dies under the cursor: the remembered index clamps to the
        // new last slot.
        state = ui(ids = listOf("s-a1"))
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-s-a1").assertIsDisplayed()

        // The scope empties entirely: the All pager degrades to its sole
        // remaining slot, the spawn card — still inside the list, not a
        // surprise back-out.
        state = ui(ids = emptyList())
        compose.waitForIdle()
        compose.onNodeWithTag("haloSpawn").assertIsDisplayed()
    }

    /**
     * The S9 subheading (#102), fixture-fed end to end through HaloModel: an
     * ACP session's wire meta renders as `model · mode · use%` in display
     * form (prefix-stripped, short-labelled), and a session without the wire
     * fields — every PTY/hook session — renders no subheading at all rather
     * than an empty row or invented values. The ≥80 terracotta threshold
     * itself is pinned by HaloSubheadingTest; here 85% proves the hot part
     * still renders as text.
     */
    @Test
    fun subheadingRendersWireMetaInDisplayFormAndStaysAbsentWithoutIt() {
        val bridge = BridgeState(
            sessions = mapOf(
                "s-acp" to SessionState(
                    sessionId = "s-acp",
                    agent = "claude",
                    cwd = "/home/dev/alpha",
                    folderName = "alpha",
                    external = true,
                    kind = "acp",
                    model = "Claude Opus 4.6",
                    mode = "acceptEdits",
                    contextPct = 85,
                ),
                "s-pty" to SessionState(
                    sessionId = "s-pty",
                    agent = "claude",
                    cwd = "/home/dev/alpha",
                    folderName = "alpha",
                ),
            ),
        )
        compose.setContent {
            HaloApp(
                ui = BridgeViewModel.UiState(status = "paired, stream open", paired = true, bridge = bridge),
                actions = HaloActions(),
            )
        }
        drill()

        // The ACP card: "Opus 4.6 · edits · 85%", each part its own node.
        compose.onNodeWithTag("haloPagerCard-s-acp").assertIsDisplayed()
        compose.onNodeWithText("Opus 4.6").assertIsDisplayed()
        compose.onNodeWithText("edits").assertIsDisplayed()
        compose.onNodeWithText("85%").assertIsDisplayed()

        // The meta-less PTY card: no subheading parts at all.
        next()
        compose.onNodeWithTag("haloPagerCard-s-pty").assertIsDisplayed()
        assertEquals(0, textCount("Opus 4.6"))
        assertEquals(0, textCount("edits"))
        assertEquals(0, textCount("85%"))
    }

    @Test
    fun emptyAllScopeIsTheSpawnCardWithBackAndPickerBothLive() {
        compose.setContent { HaloApp(ui = ui(ids = emptyList()), actions = HaloActions()) }

        // Zero sessions: the drill still lands somewhere real — the spawn
        // card, both start and end — and the at-start back works from it.
        drill()
        compose.onNodeWithTag("haloSpawn").assertIsDisplayed()
        compose.onNodeWithTag("haloRoot").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()

        // And the card itself opens the unchanged spawn target picker.
        drill()
        compose.onNodeWithTag("haloSpawn").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloSpawnPicker").assertIsDisplayed()
    }
}
