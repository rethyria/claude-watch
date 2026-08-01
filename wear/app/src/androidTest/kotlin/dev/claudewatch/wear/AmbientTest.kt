package dev.claudewatch.wear

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.ambient.AmbientLifecycleObserver
import dev.claudewatch.wear.ui.halo.EmptyRingStyle
import dev.claudewatch.wear.ui.halo.Halo
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import dev.claudewatch.wear.ui.halo.HaloRingHost
import dev.claudewatch.wear.ui.halo.HaloRingMath
import dev.claudewatch.wear.ui.halo.HaloRingState
import dev.claudewatch.wear.ui.halo.LocalHaloAmbient
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
 * Ambient mode (issue #24). The emulator cannot be pushed into REAL ambient
 * on demand, so the two halves are tested at their seams: the holder's
 * callback flips the flag (what the platform observer will drive on
 * hardware), and HaloApp's `ambient` parameter produces — and, exiting,
 * removes — the ambient rendering, asserted by the mode's testTag, which
 * exists ONLY while ambient. The S4 ring engine adds a third seam: entering
 * ambient must land the live ring ON ITS TARGETS, settled and frozen — never
 * a mid-morph frame kept lit on a wrist that is down.
 */
@RunWith(AndroidJUnit4::class)
class AmbientTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ambientCallbacksFlipTheHoldersFlag() {
        val holder = AmbientState()
        assertFalse("starts interactive", holder.isAmbient.value)

        holder.callback.onEnterAmbient(
            AmbientLifecycleObserver.AmbientDetails(
                burnInProtectionRequired = false,
                deviceHasLowBitAmbient = false,
            ),
        )
        assertTrue("onEnterAmbient flips the flag", holder.isAmbient.value)

        // The per-minute poke is a no-op beyond staying ambient.
        holder.callback.onUpdateAmbient()
        assertTrue("onUpdateAmbient keeps the flag", holder.isAmbient.value)

        holder.callback.onExitAmbient()
        assertFalse("onExitAmbient restores interactive", holder.isAmbient.value)
    }

    @Test
    fun ambientTagsTheRootOnlyWhileAmbient() {
        // A paired fixture so the ordinary home renders under the scrim —
        // the same fixture-UiState pattern as ApprovalFlowTest.
        val ui = BridgeViewModel.UiState(status = "paired, stream open", paired = true)
        var ambient by mutableStateOf(true)
        compose.setContent {
            HaloApp(ui = ui, actions = HaloActions(), ambient = ambient)
        }

        compose.onNodeWithTag("haloAmbient").assertExists()

        // Wake: the tag must vanish WITH the mode — a stale ambient scrim
        // over an interactive screen would dim every surface for no reason.
        ambient = false
        compose.waitForIdle()
        compose.onNodeWithTag("haloAmbient").assertDoesNotExist()
    }

    @Test
    fun enteringAmbientSnapsTheRingSettled() {
        // The injectable-engine seam exists for exactly this: the test drives
        // the host's real trigger (snapshotFlow + LocalHaloAmbient) under the
        // manual mainClock and watches the Animatables land.
        val engine = HaloRingState()
        var states by mutableStateOf(listOf(Halo.SessionState.RUNNING, Halo.SessionState.IDLE))
        var ambient by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalHaloAmbient provides ambient) {
                HaloRingHost(
                    inputs = RingInputs(
                        level = RingLevel.PAGE,
                        states = states,
                        emptyStyle = EmptyRingStyle.IDLE_CIRCLE,
                        selectedIndex = -1,
                        stepDir = StepDir.NONE,
                        feedState = null,
                    ),
                    engine = engine,
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)

        // First render snapped to the current model, no animation pending.
        assertEquals(2, engine.slots.size)
        assertEquals(HaloRingMath.endAngle(1, 2), engine.slots[1].end.value, 0f)
        assertFalse(engine.slots[1].end.isRunning)

        // A session arrives: the re-slice tween is genuinely mid-flight.
        states = listOf(Halo.SessionState.RUNNING, Halo.SessionState.IDLE, Halo.SessionState.WAITING_PERM)
        compose.mainClock.advanceTimeBy(350)
        val midEnd = engine.slots[1].end.value
        assertTrue(
            "expected a mid-flight re-slice, got $midEnd",
            midEnd > HaloRingMath.endAngle(1, 2) && midEnd < HaloRingMath.endAngle(1, 3),
        )

        // Wrist down mid-morph: the ring must freeze SETTLED on its targets.
        ambient = true
        compose.mainClock.advanceTimeBy(200)
        engine.slots.forEachIndexed { k, slot ->
            assertEquals(HaloRingMath.endAngle(k, 3), slot.end.value, 0f)
            assertEquals(HaloRingMath.sweepDegrees(3), slot.sweep.value, 0f)
            assertEquals(1f, slot.alpha.value, 0f)
            assertFalse(slot.end.isRunning)
            assertFalse(slot.sweep.isRunning)
            assertFalse(slot.color.isRunning)
            assertFalse(slot.alpha.isRunning)
        }

        // And STAY frozen: ambient never animates, whatever time passes.
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(HaloRingMath.endAngle(1, 3), engine.slots[1].end.value, 0f)
    }
}
