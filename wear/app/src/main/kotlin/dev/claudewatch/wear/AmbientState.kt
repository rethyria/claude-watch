package dev.claudewatch.wear

import androidx.wear.ambient.AmbientLifecycleObserver
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The ambient (always-on, wrist-down) flag as a plain holder (issue #24):
 * [callback] is handed to an [AmbientLifecycleObserver] on the activity's
 * lifecycle, and [isAmbient] is what composition collects. A separate class
 * rather than activity state so instrumented tests can drive the callbacks
 * DIRECTLY — the emulator cannot be put into real ambient mode on demand —
 * and assert the flag they feed into HaloApp.
 */
class AmbientState {

    val isAmbient = MutableStateFlow(false)

    val callback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient.value = true
        }

        override fun onUpdateAmbient() {
            // Deliberately nothing: Halo's ambient rendering is static (the
            // scrim + frozen animations in HaloApp) and TimeText redraws
            // itself, so the per-minute poke has no pixels to refresh.
        }

        override fun onExitAmbient() {
            isAmbient.value = false
        }
    }
}
