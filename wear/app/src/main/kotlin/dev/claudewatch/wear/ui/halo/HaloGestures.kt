// Shared "pull-from-the-top-bound to go back" gesture for the touch-scroll
// lists (session list, spawn picker, discover list). A ScalingLazyColumn owns
// every vertical drag, so back is rebuilt from the nested-scroll leftovers the
// list rejects at its top: a pull past the threshold steps back.
//
// The gate that matters (the reported bug): back fires ONLY when the list was
// ALREADY at its top when the gesture BEGAN. Scrolling up until you hit the top
// and continuing must NOT spill into a back — you have to lift and pull again
// from the resting top. Captured at the first user-input scroll of each gesture
// (canScrollBackward before anything is consumed) and reset when the gesture
// ends (onPreFling).
package dev.claudewatch.wear.ui.halo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState

/** Pull past this to commit the back/cancel — ≈60px at the 450 reference. */
private val AT_TOP_BACK_THRESHOLD = 30.dp

/**
 * A [NestedScrollConnection] that calls [onBack] when the user pulls the list
 * down past the threshold FROM its resting top — i.e. the list was already at
 * the top when the gesture began. Rotary scrolling bypasses nested scroll, so
 * the bezel never triggers it.
 */
@Composable
internal fun rememberAtTopBackConnection(
    listState: ScalingLazyListState,
    onBack: () -> Unit,
): NestedScrollConnection {
    val currentOnBack by rememberUpdatedState(onBack)
    val thresholdPx = with(LocalDensity.current) { AT_TOP_BACK_THRESHOLD.toPx() }
    return remember(listState, thresholdPx) {
        object : NestedScrollConnection {
            // Unconsumed pull-down so far; any real scroll or upward motion
            // resets it, so only a continuous top-of-list pull counts.
            private var pulled = 0f
            private var fired = false
            // Whether the list was at its top when THIS gesture began. Only then
            // can the pull count as back — scrolling up to reach the top mid-
            // gesture must not back out.
            private var startedAtTop = false
            private var gestureSeen = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // First user-input delta of the gesture: capture the resting
                // position before the list consumes anything.
                if (source == NestedScrollSource.UserInput && !gestureSeen) {
                    gestureSeen = true
                    startedAtTop = !listState.canScrollBackward
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (startedAtTop && available.y > 0f && !listState.canScrollBackward) {
                    pulled += available.y
                    if (!fired && pulled > thresholdPx) {
                        fired = true
                        currentOnBack()
                    }
                } else if (consumed.y != 0f || available.y < 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                fired = false
                gestureSeen = false
                startedAtTop = false
                return Velocity.Zero
            }
        }
    }
}
