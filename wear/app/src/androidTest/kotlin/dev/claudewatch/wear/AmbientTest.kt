package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.ambient.AmbientLifecycleObserver
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
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
 * exists ONLY while ambient.
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
}
