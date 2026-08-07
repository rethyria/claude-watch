package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v2 session-list pager (Halo v2 S5, #99), driven with fixture UiStates —
 * no bridge, no network. One session per screen: entry from home and project
 * pages lands on the scope's first card, swipes and chevrons step with no
 * wrap (the All scope ends on the trailing spawn card, a project on its last
 * session), stepping right at the start is BACK, a card tap opens the feed
 * while the waiting card's Answer pill opens the prompt OVER the pager
 * (never falling through to the feed), the action arc carries honest close
 * semantics (✕ kill wherever the bridge can really end the session — owned
 * PTY or ACP via #88's close frame — ⊘ hide for a hook-observed one, stubs
 * visible but dead),
 * and a session killed under the cursor self-heals to the remembered-index
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

        // Beta's page → ITS pager: the project scope's first (and only) card,
        // with no trailing spawn slot — › is invisible on the true last card
        // and no spawn card exists in a project scope.
        onePageRight()
        onePageRight()
        compose.onNodeWithText("beta").assertIsDisplayed()
        drill()
        compose.onNodeWithTag("haloPagerCard-s-b1").assertIsDisplayed()
        assertEquals("› hides on a project scope's last card", 0, textCount("›"))
        assertEquals("a project scope has no spawn card", 0, tagCount("haloSpawn"))
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
    fun cardTapOpensTheSessionFeed() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()
        compose.onNodeWithTag("haloPagerCard-s-a1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloFeed-s-a1").assertIsDisplayed()
    }

    @Test
    fun answerPillOpensTheCardOverThePagerWithoutFallingThroughToTheFeed() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()
        next()
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()

        // The pill is its own click target ABOVE the card's: tapping it must
        // raise the session's OWN prompt, not open the feed underneath.
        compose.onNodeWithTag("haloAnswerPill").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        compose.onNodeWithText("Write notes.txt").assertIsDisplayed()
        assertEquals("answering must not drill into the feed", 0, tagCount("haloFeed-s-a2"))

        // "Decide later" (the explicit control — v3 purged the card's
        // swipe-down) lands right back on the same pager card — the whole
        // point of opening OVER the list.
        compose.onNodeWithTag("haloDecideLater").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(0, tagCount("haloCard"))
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()
        assertEquals(0, tagCount("haloFeed-s-a2"))
    }

    @Test
    fun answerPillOutranksTheKillCellInTheirOverlapBand() {
        val kills = mutableListOf<String>()
        compose.setContent {
            HaloApp(ui = ui(), actions = HaloActions(onKill = { kills += it }))
        }
        drill()
        next()
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()

        // The #104 in-flow position no longer overlaps the arc, but the pill
        // stays the pager's TOPMOST layer as defence-in-depth: whatever the
        // card group's height puts under the pill's lower edge, a finger
        // aiming at Answer must never kill the session — this tap targets
        // the pill's bottom band BY COORDINATE, not by node.
        val pill = compose.onNodeWithTag("haloAnswerPill").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(Offset(pill.center.x, pill.bottom - 2f))
            up()
        }
        compose.waitForIdle()
        assertEquals("a tap on the pill must never reach the ✕ cell", 0, kills.size)
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        compose.onNodeWithText("Write notes.txt").assertIsDisplayed()
    }

    @Test
    fun answerPillRidesTheCardGroupAndClearsTheActionArc() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()
        next()
        compose.onNodeWithTag("haloPagerCard-s-a2").assertIsDisplayed()

        // #104 user feedback: the pill follows the prototype's own pager
        // geometry — in flow below the card's text stack — instead of the
        // main pages' screen-absolute slot, which planted it squarely on the
        // ✕ kill cell and grazing its neighbours. Geometric acceptance: no
        // arc cell shares a pixel with the pill.
        val pill = compose.onNodeWithTag("haloAnswerPill").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("haloArc-model", "haloArc-mode", "haloRowClose", "haloArc-compact", "haloArc-handover")) {
            val cell = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertFalse("the pill must clear the $tag cell", pill.overlaps(cell))
        }
    }

    @Test
    fun actionArcCloseKillsOwnedSessionsAndHidesExternalOnes() {
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

        // Owned session: the red ✕, wired to a REAL kill.
        compose.onNode(hasTestTag("haloRowClose") and hasText("✕")).assertIsDisplayed()
        compose.onNodeWithTag("haloRowClose").performClick()
        assertEquals(listOf("s-a1"), kills)
        assertEquals(0, hides.size)

        // Hook-observed session: the honest ⊘ hide — never a fake kill (#53).
        next()
        next()
        compose.onNodeWithTag("haloPagerCard-s-b1").assertIsDisplayed()
        compose.onNode(hasTestTag("haloRowClose") and hasText("⊘")).assertIsDisplayed()
        compose.onNodeWithTag("haloRowClose").performClick()
        assertEquals(listOf("s-b1"), hides)
        assertEquals("hide must never kill", listOf("s-a1"), kills)

        // ACP session: external, but the bridge really can end it through the
        // adapter (#88's close frame) — so the ✕ is honest here, not a hide
        // wearing a kill's clothes.
        next()
        compose.onNodeWithTag("haloPagerCard-s-b2").assertIsDisplayed()
        compose.onNode(hasTestTag("haloRowClose") and hasText("✕")).assertIsDisplayed()
        compose.onNodeWithTag("haloRowClose").performClick()
        assertEquals(listOf("s-a1", "s-b2"), kills)
        assertEquals("an ACP close must not degrade to a local hide", listOf("s-b1"), hides)
    }

    @Test
    fun actionArcStubsAreVisibleButDisabledAndTheSpawnCardHasNoArc() {
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        drill()

        // The four stubs render (the arc reads complete) but take no input.
        for (tag in listOf("haloArc-model", "haloArc-mode", "haloArc-compact", "haloArc-handover")) {
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsNotEnabled()
        }

        // The spawn card has nothing to close or configure: no arc at all.
        repeat(3) { next() }
        compose.onNodeWithTag("haloSpawn").assertIsDisplayed()
        assertEquals(0, tagCount("haloRowClose"))
        assertEquals(0, tagCount("haloArc-model"))
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
