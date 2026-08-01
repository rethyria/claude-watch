package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.ui.halo.EmptyRingStyle
import dev.claudewatch.wear.ui.halo.Halo
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import dev.claudewatch.wear.ui.halo.HaloRingHost
import dev.claudewatch.wear.ui.halo.HaloRingMath
import dev.claudewatch.wear.ui.halo.HaloRingState
import dev.claudewatch.wear.ui.halo.RingInputs
import dev.claudewatch.wear.ui.halo.RingLevel
import dev.claudewatch.wear.ui.halo.StepDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The S7 morph choreography (Halo v2, #103) under the MANUAL mainClock —
 * autoAdvance off, every assertion at a chosen frame time. Two seams:
 *
 * - HaloApp content fades: the three depth transitions crossfade (out .25s /
 *   in .45s delayed .1s; list→page the .3s fast return), so BOTH contents are
 *   composed through the whole window — no frame shows neither (the "no flash
 *   frames" criterion at the content layer) — while the Answer-pill jump
 *   SNAPS under the opaque card.
 * - The ring host's engine across the close-swap: the dashed layer must cover
 *   every frame until the settle, then the solid layer from the very same
 *   frame — the atomic swap that makes the prototype's end-of-morph black
 *   flash structurally impossible. (The morph maths themselves are pinned on
 *   the JVM in HaloRingStateTest; this is the on-device integration.)
 */
@RunWith(AndroidJUnit4::class)
class HaloRingMorphTest {

    @get:Rule
    val compose = createComposeRule()

    private fun fixtureBridge() = BridgeState(
        sessions = mapOf(
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
        ),
    )

    private val alphaPrompt = BridgeViewModel.PendingPermission(
        permissionId = "perm-a2",
        sessionId = "s-a2",
        toolName = "Write",
        requestSummary = "Write notes.txt",
        sessionLabel = "alpha",
        options = listOf(PermissionOption("allow", "Yes"), PermissionOption("deny", "No")),
    )

    private fun ui(queue: List<BridgeViewModel.PendingPermission> = emptyList()) =
        BridgeViewModel.UiState(
            status = "paired, stream open",
            paired = true,
            bridge = fixtureBridge(),
            permissionQueue = queue,
        )

    private fun tagCount(tag: String, unmerged: Boolean = false): Int =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged).fetchSemanticsNodes().size

    /** A real finger's frame-by-frame drag on [tag] (the injection discipline
     *  the nav tests pinned: per-delta detectors must see finger-like moves). */
    private fun fingerDrag(tag: String, step: Offset) {
        compose.onNodeWithTag(tag).performTouchInput {
            down(center)
            repeat(10) { moveBy(step, delayMillis = 16L) }
            up()
        }
    }

    // ── Content fades ───────────────────────────────────────────────────────

    @Test
    fun openAndCloseCrossfadeContentThroughTheMorphWindows() {
        compose.mainClock.autoAdvance = false
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        compose.mainClock.advanceTimeBy(200)
        assertEquals(1, tagCount("haloCensus", unmerged = true))

        // Drill: page → list. Mid-window BOTH contents are composed — the
        // page fading out (.25s) while the pager card fades in late.
        fingerDrag("haloRoot", Offset(0f, -30f))
        compose.mainClock.advanceTimeBy(96)
        assertEquals(1, tagCount("haloCensus", unmerged = true))
        assertEquals(1, tagCount("haloPagerCard-s-a1"))

        // Window over (max 550ms): only the pager remains.
        compose.mainClock.advanceTimeBy(600)
        assertEquals(0, tagCount("haloCensus", unmerged = true))
        assertEquals(1, tagCount("haloPagerCard-s-a1"))

        // Back out: the list→page return is the .3s fast fade — same
        // both-composed property mid-window, page-only after.
        fingerDrag("haloRoot", Offset(0f, 30f))
        compose.mainClock.advanceTimeBy(96)
        assertEquals(1, tagCount("haloPagerCard-s-a1"))
        assertEquals(1, tagCount("haloCensus", unmerged = true))
        compose.mainClock.advanceTimeBy(500)
        assertEquals(0, tagCount("haloPagerCard-s-a1"))
        assertEquals(1, tagCount("haloCensus", unmerged = true))
    }

    @Test
    fun growAndShrinkCrossfadeFeedContent() {
        compose.mainClock.autoAdvance = false
        compose.setContent { HaloApp(ui = ui(), actions = HaloActions()) }
        compose.mainClock.advanceTimeBy(200)
        fingerDrag("haloRoot", Offset(0f, -30f))
        compose.mainClock.advanceTimeBy(700)
        assertEquals(1, tagCount("haloPagerCard-s-a1"))

        // Into the feed: card and feed both composed through the window.
        compose.onNodeWithTag("haloPagerCard-s-a1").performClick()
        compose.mainClock.advanceTimeBy(96)
        assertEquals(1, tagCount("haloPagerCard-s-a1"))
        assertEquals(1, tagCount("haloFeed-s-a1"))
        compose.mainClock.advanceTimeBy(600)
        assertEquals(0, tagCount("haloPagerCard-s-a1"))
        assertEquals(1, tagCount("haloFeed-s-a1"))

        // Swipe right = back to the list: the exact reverse crossfade.
        fingerDrag("haloFeed-s-a1", Offset(30f, 0f))
        compose.mainClock.advanceTimeBy(96)
        assertEquals(1, tagCount("haloFeed-s-a1"))
        assertEquals(1, tagCount("haloPagerCard-s-a1"))
        compose.mainClock.advanceTimeBy(600)
        assertEquals(0, tagCount("haloFeed-s-a1"))
        assertEquals(1, tagCount("haloPagerCard-s-a1"))
    }

    @Test
    fun answerPillJumpSnapsContentUnderTheCard() {
        compose.mainClock.autoAdvance = false
        compose.setContent { HaloApp(ui = ui(queue = listOf(alphaPrompt)), actions = HaloActions()) }
        compose.mainClock.advanceTimeBy(200)
        assertEquals(1, tagCount("haloAnswerPill"))

        // The pill jumps page → feed UNDER the opaque card: a SNAP, not a
        // fade — the page content is gone within a couple of frames (a fade
        // would keep it composed for 250ms).
        compose.onNodeWithTag("haloAnswerPill").performClick()
        compose.mainClock.advanceTimeBy(64)
        assertEquals(0, tagCount("haloCensus", unmerged = true))
        assertEquals(1, tagCount("haloCard"))
        assertEquals(1, tagCount("haloFeed-s-a2"))
    }

    // ── The ring across the close-swap ──────────────────────────────────────

    @Test
    fun closeSwapIsAtomicAndLeavesNoUncoveredFrame() {
        val engine = HaloRingState()
        val states = listOf(Halo.SessionState.RUNNING, Halo.SessionState.IDLE)
        var inputs by mutableStateOf(
            RingInputs(
                level = RingLevel.PLIST,
                states = states,
                emptyStyle = EmptyRingStyle.IDLE_CIRCLE,
                selectedIndex = 0,
                stepDir = StepDir.NONE,
                feedState = null,
            ),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent { HaloRingHost(inputs = inputs, engine = engine) }
        compose.mainClock.advanceTimeBy(64)

        // First render snapped straight into the LIST regime.
        assertEquals(0f, engine.merge.value, 0f)
        assertEquals(1f, engine.dashAlpha.value, 0f)
        assertTrue(engine.heroShown)
        engine.slots.forEach { assertEquals(0f, it.alpha.value, 0f) }

        // Close. Walk every frame to past the settle: at NO frame is the ring
        // uncovered — the dashed layer holds the screen until the very frame
        // the solid layer is up, and at that frame the dashes are fully
        // merged (stroke 9, alpha 1, no off-interval): pixel-identical.
        inputs = inputs.copy(level = RingLevel.PAGE, selectedIndex = -1)
        var swapSeenAtMs = -1L
        var elapsed = 0L
        while (elapsed < 1400L) {
            compose.mainClock.advanceTimeBy(16)
            elapsed += 16
            val dashUp = engine.dashAlpha.value > 0f
            val solidUp = engine.slots.all { it.alpha.value == 1f }
            assertTrue("uncovered frame at ${elapsed}ms", dashUp || solidUp)
            if (solidUp && swapSeenAtMs < 0) {
                swapSeenAtMs = elapsed
                // The swap frame: merge settled at 1 BEFORE the reveal, and
                // the dashed layer left in the same frame the solid arrived.
                assertEquals(1f, engine.merge.value, 0f)
                assertEquals(0f, engine.dashAlpha.value, 0f)
                assertFalse(engine.heroShown)
            }
        }
        // The swap happened, and only at the 1s settle — not during the
        // 500ms merge (the "real solid layer hidden throughout" close rule).
        assertTrue("the close-swap never fired", swapSeenAtMs > 0)
        assertTrue("swap fired mid-merge at ${swapSeenAtMs}ms", swapSeenAtMs >= 1000L)

        // Settled PAGE ring: the layout is the plain 2-slot page geometry.
        assertEquals(HaloRingMath.endAngle(0, 2), engine.slots[0].end.value, 0f)
        assertEquals(HaloRingMath.sweepDegrees(2), engine.slots[0].sweep.value, 0f)
    }
}
