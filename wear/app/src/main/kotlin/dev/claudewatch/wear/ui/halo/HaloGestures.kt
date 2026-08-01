// Shared "pull-from-the-top-bound to go back" gesture for the touch-scroll
// lists (session feed, spawn picker, discover list). A scrollable owns every
// vertical drag, so back is rebuilt from the nested-scroll leftovers the list
// rejects at its top: a pull past the threshold steps back.
//
// The gate that matters (the reported bug): back fires ONLY when the list was
// ALREADY at its top when the gesture BEGAN. Scrolling up until you hit the top
// and continuing must NOT spill into a back — you have to lift and pull again
// from the resting top. Captured at the first user-input scroll of each gesture
// (the [atTop] predicate before anything is consumed) and reset when the
// gesture ends (onPreFling).
//
// "Top" is the CALLER'S bound, not this file's: a top-anchored list rests at
// its backward bound (!canScrollBackward), but the feed's reverseLayout list
// renders its END upward — its visual top is the FORWARD bound. The screen
// gesture is identical either way (nested-scroll leftovers arrive in screen
// coordinates, so a downward pull is available.y > 0 for both); only the
// bound test inverts, which is why it is a predicate and not a state read.
package dev.claudewatch.wear.ui.halo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
 * Per-drag bookkeeping for the #109 stand-down: while a SYSTEM predictive
 * back gesture is in flight ([LocalHaloSystemBackInFlight]), its touches
 * ALSO arrive at the app window as an ordinary drag, and a surface detector
 * acting on that drag is the double-consumption that killed round 1 — on the
 * SM-L330 the drag navigated nav to home MID-GESTURE, the enabled-flag
 * BackHandler unregistered itself, and the release committed the system's
 * own back (the activity died). A drag is marked system-owned if the gesture
 * was in flight at ANY point during it — not just at the end — because the
 * system's commit (which drops the flag) and the app's own UP handling race
 * on the main thread with no ordering guarantee. One claim per detector,
 * [start] on every drag start; deltas keep being CONSUMED by their detectors
 * as before (nothing else in-app may act on a system-owned gesture either) —
 * only the end-of-drag ACTION is withheld.
 */
internal class SystemBackDragClaim(private val inFlight: State<Boolean>) {
    private var owned = false

    /** Call from onDragStart: opens a fresh drag's claim. */
    fun start() {
        owned = inFlight.value
    }

    /** Call on every drag delta: an in-flight gesture poisons the whole drag. */
    fun update() {
        if (inFlight.value) owned = true
    }

    /** True when the system back gesture owns this drag: do not act on it. */
    val owns: Boolean get() = owned
}

/**
 * A [NestedScrollConnection] that calls [onBack] when the user pulls the list
 * down past the threshold FROM its resting top — i.e. [atTop] was already true
 * when the gesture began. Rotary scrolling bypasses nested scroll, so the
 * bezel never triggers it.
 */
@Composable
internal fun rememberAtTopBackConnection(
    atTop: () -> Boolean,
    onBack: () -> Unit,
): NestedScrollConnection {
    val currentAtTop by rememberUpdatedState(atTop)
    val currentOnBack by rememberUpdatedState(onBack)
    val thresholdPx = with(LocalDensity.current) { AT_TOP_BACK_THRESHOLD.toPx() }
    val systemBackInFlight = LocalHaloSystemBackInFlight.current
    return remember(thresholdPx, systemBackInFlight) {
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
            // #109 stand-down (same poison rule as SystemBackDragClaim): this
            // connection FIRES MID-DRAG, so a system back gesture's touches
            // spilling in here as nested-scroll leftovers would navigate nav
            // while the gesture is still in flight — the exact race.
            private var systemBackOwned = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // First user-input delta of the gesture: capture the resting
                // position before the list consumes anything.
                if (source == NestedScrollSource.UserInput && !gestureSeen) {
                    gestureSeen = true
                    startedAtTop = currentAtTop()
                }
                if (source == NestedScrollSource.UserInput && systemBackInFlight.value) {
                    systemBackOwned = true
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (systemBackInFlight.value) systemBackOwned = true
                if (startedAtTop && !systemBackOwned && available.y > 0f && currentAtTop()) {
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
                systemBackOwned = false
                return Velocity.Zero
            }
        }
    }
}

/** The top-anchored-list form: at the resting top means at the backward bound. */
@Composable
internal fun rememberAtTopBackConnection(
    listState: ScalingLazyListState,
    onBack: () -> Unit,
): NestedScrollConnection =
    rememberAtTopBackConnection(atTop = { !listState.canScrollBackward }, onBack = onBack)

/**
 * The MODAL surfaces' pull-down exit (the approval card's "decide later", the
 * question card's "answer later", the voice overlay's Cancel): their
 * verticalScroll consumes every vertical drag — starving the host Box's
 * swipe-down detector in HaloApp — so the exit is rebuilt from the
 * nested-scroll leftovers, firing at end-of-drag (onPreFling). No at-top gate,
 * unlike [rememberAtTopBackConnection]: leftovers only exist past the scroll
 * bound, and on a modal surface any committed overpull means "leave".
 *
 * Carries the #109 stand-down like every other surface detector
 * ([SystemBackDragClaim]'s poison rule — owned if the system gesture was in
 * flight at ANY point during the drag): a system back gesture's unconsumed
 * droop spills in here as leftovers too, and firing the exit races the root
 * handler's completion — the completion then routes from the ALREADY-mutated
 * state (a second hierarchy step on the cards; on the voice overlay over
 * home, systemBack sees no overlay left and routes null — the deliberate
 * activity finish, for a gesture that meant "close the overlay").
 */
@Composable
internal fun rememberOverscrollExitConnection(onExit: () -> Unit): NestedScrollConnection {
    val currentOnExit by rememberUpdatedState(onExit)
    val thresholdPx = with(LocalDensity.current) { AT_TOP_BACK_THRESHOLD.toPx() }
    val systemBackInFlight = LocalHaloSystemBackInFlight.current
    return remember(thresholdPx, systemBackInFlight) {
        object : NestedScrollConnection {
            // Unconsumed pull-down over the whole drag.
            private var overscroll = 0f
            private var systemBackOwned = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && systemBackInFlight.value) {
                    systemBackOwned = true
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (systemBackInFlight.value) systemBackOwned = true
                if (available.y > 0f) overscroll += available.y
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // The flag is re-read at the decision point too: a gesture
                // going in flight between the last delta and the release
                // must still poison the drag.
                if (systemBackInFlight.value) systemBackOwned = true
                if (!systemBackOwned && overscroll > thresholdPx) currentOnExit()
                overscroll = 0f
                systemBackOwned = false
                return Velocity.Zero
            }
        }
    }
}
