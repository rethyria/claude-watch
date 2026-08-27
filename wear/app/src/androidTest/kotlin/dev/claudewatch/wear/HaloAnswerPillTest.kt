package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.ui.halo.Halo
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v2 shell's fixed clock + Answer pill (Halo v2 S3, #98), driven with
 * fixture UiStates — no bridge, no network. The pill is the ONE prompt-jump
 * affordance on the main pages now (the centerpiece tap opens the session
 * list instead): it exists only while the page's scope has a waiting session
 * — the global queue on home, the project's own on a project page — sits OUT
 * OF FLOW below the clock group (the clock never shifts, with or without it),
 * and opens the right scope's first waiting prompt. The clock itself is fixed
 * app-level chrome: pixel-identical between All and project pages while only
 * the subtitle slides. The root ring host draws on every page, the empty
 * scope included.
 */
@RunWith(AndroidJUnit4::class)
class HaloAnswerPillTest {

    @get:Rule
    val compose = createComposeRule()

    /** Beta is REGISTERED first so alpha leads the pager: projects render
     *  newest-first (matching Zed), so insertion order is the reverse of pager
     *  order. These are answer-pill scoping tests; the ordering contract
     *  itself lives in HaloModelTest. */
    private fun fixtureBridge() = BridgeState(
        sessions = mapOf(
            "s-2" to SessionState(
                sessionId = "s-2",
                agent = "claude",
                cwd = "/home/dev/beta",
                folderName = "beta",
            ),
            "s-1" to SessionState(
                sessionId = "s-1",
                agent = "claude",
                cwd = "/home/dev/alpha",
                folderName = "alpha",
            ),
        ),
    )

    /** Beta's blocked prompt: on home it is ALSO the global queue front. */
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

    /** Frame-by-frame page swipe (the real-finger injection discipline). */
    private fun onePage(direction: Int) {
        compose.onNodeWithTag("haloRoot").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(direction * width / 12f, 0f), delayMillis = 16L) }
            up()
        }
        compose.waitForIdle()
    }

    /** The fixed clock's bounds (unmerged: it lives inside the centerpiece's
     *  merged clickable, like haloCensus). */
    private fun clockBounds(): Rect =
        compose.onNodeWithTag("haloClock", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

    @Test
    fun pillAppearsOnlyWithAWaitingSessionAndTheClockNeverShifts() {
        var state by mutableStateOf(ui(queue = emptyList()))
        compose.setContent { HaloApp(ui = state, actions = HaloActions()) }

        // No waiting session: no pill — and the ring host is drawing.
        compose.onNodeWithTag("haloAnswerPill").assertDoesNotExist()
        compose.onNodeWithTag("haloRingHost").assertExists()
        val before = clockBounds()

        // A prompt arrives: the pill appears, out of flow — the clock+subtitle
        // group must not move by a pixel.
        state = ui(queue = listOf(betaPrompt))
        compose.waitForIdle()
        compose.onNodeWithTag("haloAnswerPill").assertIsDisplayed()
        assertEquals("the clock must hold its place when the pill appears", before, clockBounds())
    }

    @Test
    fun clockGroupCentresOnItsVisualExtentAndThePillHangsThePrototypeClearanceBelowIt() {
        compose.setContent { HaloApp(ui = ui(queue = listOf(betaPrompt)), actions = HaloActions()) }
        val density = compose.density.density
        val clock = clockBounds()
        val root = compose.onNodeWithTag("haloRoot").fetchSemanticsNode().boundsInRoot

        // The clock's line box is MEASURED (Compose gives a lone line the
        // full ascent+descent font box, not the 88px/1 line height); only
        // the fixed gap + slot + phantom come from the tokens.
        val gapAndSlotPx = ((Halo.Geo.ClockSubtitleGapPx + Halo.Geo.ClockSubtitleSlotPx) / 2f) * density
        val phantomPx = (Halo.Geo.ClockDeadLeadingPx / 2f) * density
        val groupHeightPx = clock.height + gapAndSlotPx

        // The #104 mechanism, half one: the group's centre line is computed
        // over its VISUAL extent — the phantom spacer mirrors the clock's
        // dead leading below the group, so the group's layout boxes ride
        // half of it above dead centre.
        assertEquals(
            "the clock group must centre on its visual extent, riding the lift",
            (root.height - groupHeightPx - phantomPx) / 2f,
            clock.top,
            2f,
        )

        // Half two: the Answer pill hangs the prototype's 21 ref-px below
        // the re-centred group's bottom — derived, never screen-absolute.
        // Tolerance 3px: the constant is exact at the 450 reference, this
        // glass is 454 and the platform font box rounds sub-pixel.
        val pill = compose.onNodeWithTag("haloAnswerPill").fetchSemanticsNode().boundsInRoot
        assertEquals(
            "the pill must hang the prototype clearance below the clock group",
            clock.top + groupHeightPx + (Halo.Geo.AnswerPillClearancePx / 2f) * density,
            pill.top,
            3f,
        )
    }

    @Test
    fun homePillOpensTheGlobalQueueFront() {
        compose.setContent { HaloApp(ui = ui(queue = listOf(betaPrompt)), actions = HaloActions()) }
        compose.onNodeWithTag("haloAnswerPill").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        compose.onNodeWithText("Write notes.txt").assertIsDisplayed()
    }

    @Test
    fun projectPagesScopeThePillToTheirOwnWaitingSessions() {
        compose.setContent { HaloApp(ui = ui(queue = listOf(betaPrompt)), actions = HaloActions()) }
        val homeClock = clockBounds()

        // Alpha's page: no waiting session in scope — no pill, even though
        // the GLOBAL queue has one. The "↑ sessions" hint is gone too, and
        // the fixed clock sits exactly where home drew it.
        onePage(-1)
        compose.onNodeWithText("alpha").assertIsDisplayed()
        compose.onNodeWithTag("haloAnswerPill").assertDoesNotExist()
        assertEquals(
            0,
            compose.onAllNodes(hasText("↑ sessions")).fetchSemanticsNodes().size,
        )
        assertEquals(
            "the clock is fixed chrome: identical position on All and project pages",
            homeClock,
            clockBounds(),
        )

        // Beta's page: its own session waits — the pill shows and opens
        // BETA's prompt.
        onePage(-1)
        compose.onNodeWithText("beta").assertIsDisplayed()
        compose.onNodeWithTag("haloAnswerPill").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        compose.onNodeWithText("Write notes.txt").assertIsDisplayed()
    }

    @Test
    fun centerpieceTapOpensTheSessionListNotTheCard() {
        // The retargeted centerpiece (v2 nav: tap face → session list): even
        // with a waiting prompt queued, the tap drills to the list pager —
        // the prompt jump belongs to the pill alone.
        compose.setContent { HaloApp(ui = ui(queue = listOf(betaPrompt)), actions = HaloActions()) }
        compose.onNodeWithTag("haloCenter").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloPagerCard-s-1").assertIsDisplayed()
        assertEquals(
            "a centerpiece tap must never raise the card",
            0,
            compose.onAllNodes(hasTestTag("haloCard")).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun emptyHomeStillRingsTheFaintIdleCircle() {
        // A paired watch with zero sessions: the ring host stays composed —
        // the faint idle circle keeps the layout readable (the collapse-away
        // treatment is reserved for the depth-less glance pages).
        compose.setContent {
            HaloApp(
                ui = BridgeViewModel.UiState(status = "paired, stream open", paired = true),
                actions = HaloActions(),
            )
        }
        compose.onNodeWithTag("haloRingHost").assertExists()
        compose.onNodeWithTag("haloCensus", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("no sessions").assertIsDisplayed()
    }
}
