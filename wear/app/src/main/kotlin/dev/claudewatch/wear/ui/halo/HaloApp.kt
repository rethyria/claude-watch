// The Halo root: horizontal pager (All + one page per project) with tappable
// dots, vertical swipes for depth, 300ms directional slide+fade between
// depths, TimeText-as-home on inner screens, and the approval/question card
// as a top overlay chained off the waiting queue. Navigation state itself is
// the pure HaloNavState machine (HaloNav.kt); this file only binds gestures,
// animation, and the screen composables to it.
package dev.claudewatch.wear.ui.halo

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TimeTextDefaults
import dev.claudewatch.wear.BridgeViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Everything Halo can ask the ViewModel to do (mirror of the VM's actions). */
data class HaloActions(
    val onPair: (host: String, port: String, code: String) -> Unit = { _, _, _ -> },
    val onUnpair: () -> Unit = {},
    val onSendCommand: (String) -> Unit = {},
    val onCommandDraftChange: (String) -> Unit = {},
    val onDictate: () -> Unit = {},
    val onAnswerPermission: (permissionId: String, behavior: String) -> Unit = { _, _ -> },
    val onAnswerQuestions: (permissionId: String, answers: List<String>) -> Unit = { _, _ -> },
    val onDismissPermission: (permissionId: String) -> Unit = {},
    val onSpawn: (agent: String) -> Unit = {},
    val onKill: (sessionId: String) -> Unit = {},
)

/** Handoff motion: 300ms cubic-bezier(0.2,0.7,0.3,1), 70px slide at 450 ref. */
private val HaloEasing = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)
private const val TRANSITION_MS = 300
private const val SLIDE_FRACTION = 70f / HALO_REF_PX

/** Swipe threshold ≈60px at the 450 reference. */
private const val SWIPE_THRESHOLD_FRACTION = 60f / HALO_REF_PX

/** A swipe suppresses the synthetic tap that can follow it for this long. */
private const val TAP_GUARD_MS = 300L

@Composable
fun HaloApp(ui: BridgeViewModel.UiState, actions: HaloActions) {
    val model = HaloModel.from(ui)
    var nav by remember { mutableStateOf(HaloNavState()) }
    // Gesture lambdas live inside pointerInput(Unit) blocks that never
    // restart; these keep them reading the current state, not a stale capture.
    val currentModel by rememberUpdatedState(model)
    val currentUi by rememberUpdatedState(ui)
    var lastSwipeAtMs by remember { mutableLongStateOf(0L) }

    // §5/§6 result flash: an answered prompt leaves ui.permissionQueue at ACK
    // time, but its card must stay composed while the 1.4s ✓/✕ flash plays.
    // Once the shown prompt vanishes from the queue it is held here until the
    // card composable reports done — only then does the next front slide in
    // (queue chaining) or the overlay close.
    var cardHold by remember { mutableStateOf<BridgeViewModel.PendingPermission?>(null) }

    // The model can shrink under the navigation (session killed, project's
    // last session gone, queue resolved elsewhere): back out to something
    // that still exists rather than rendering a ghost.
    LaunchedEffect(model, nav) {
        val scope = nav.listScope
        nav = when {
            // Empty queue returns home (spec) — but not while a resolved
            // card's result flash is still playing; its onDone navigates.
            nav.cardOpen && ui.permissionQueue.isEmpty() && cardHold == null -> nav.jumpHome()
            // The pinned prompt was resolved (chaining moved to the queue
            // front): drop the stale id so nav reflects what is rendered.
            nav.cardPermissionId != null &&
                ui.permissionQueue.none { it.permissionId == nav.cardPermissionId } ->
                nav.copy(cardPermissionId = null)
            nav.depth == HaloDepth.SESSION && model.sessions.none { it.id == nav.sessionId } -> nav.back()
            scope is ListScope.Project &&
                nav.depth != HaloDepth.PAGE &&
                model.projects.none { it.name == scope.name } -> nav.jumpHome()
            else -> nav
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Halo.Palette.Background)) {
        AnimatedContent(
            targetState = layerOf(nav),
            transitionSpec = { depthTransition() },
            label = "haloDepth",
        ) { layer ->
            when (layer) {
                Layer.Pager -> PagerLayer(
                    model = model,
                    page = nav.page,
                    onPageChange = { nav = nav.copy(page = it) },
                    onDrill = {
                        lastSwipeAtMs = SystemClock.uptimeMillis()
                        nav = nav.drillToList(currentModel)
                    },
                    onTapCenter = {
                        if (SystemClock.uptimeMillis() - lastSwipeAtMs > TAP_GUARD_MS) {
                            nav = nav.openFirstWaiting(currentModel)
                        }
                    },
                )
                is Layer.SessionList -> InnerScreen(
                    onBack = { nav = nav.back() },
                    onHome = { nav = nav.jumpHome() },
                ) {
                    HaloSessionList(
                        model = model,
                        scope = layer.scope,
                        onOpenSession = { nav = nav.drillToSession(it) },
                        onKill = actions.onKill,
                    )
                }
                is Layer.Feed -> InnerScreen(
                    onBack = { nav = nav.back() },
                    onHome = { nav = nav.jumpHome() },
                ) {
                    HaloSessionFeed(
                        model = model,
                        sessionId = layer.sessionId,
                        ui = ui,
                        // The banner belongs to THIS session: pin its own
                        // prompt, not whatever sits at the global queue front.
                        onOpenCard = {
                            val pending = currentModel.sessions
                                .firstOrNull { it.id == layer.sessionId }?.pending
                            nav = nav.openCard(pending?.permissionId)
                        },
                        onDictate = actions.onDictate,
                        onCycle = { nav = nav.copy(sessionId = it) },
                    )
                }
            }
        }

        // The card overlay: renders the prompt the card was opened FOR
        // (nav.cardPermissionId — a project page or feed banner targets its
        // own session's prompt, which need not be the global queue front),
        // falling back to the front once that prompt resolves. Chaining is a
        // horizontal slide keyed on the prompt's id — resolving one card
        // slides the next in from the right (handoff §5). Withheld while
        // offline: a disconnected bridge can't receive the answer.
        val front = nav.cardPermissionId
            ?.let { id -> ui.permissionQueue.firstOrNull { it.permissionId == id } }
            ?: ui.permissionQueue.firstOrNull()
        // Hold update (idempotent snapshot writes): `front` is taken only when
        // nothing is held — once a prompt is shown it stays PINNED until the
        // card reports done. The queue is newest-first, so following the live
        // front would let every new arrival slide in over the card mid-read
        // (and a steady prompt stream would starve the shown one forever).
        // While the held prompt is still queued it is refreshed BY ID so late
        // metadata (a session label arriving) still lands; once it leaves the
        // queue it freezes for the result flash. Closing the overlay
        // (swipe-down, decide later) drops any hold.
        if (!nav.cardOpen) {
            if (cardHold != null) cardHold = null
        } else {
            val held = cardHold
            if (held == null) {
                cardHold = front
            } else {
                val live = ui.permissionQueue.firstOrNull { it.permissionId == held.permissionId }
                if (live != null && live != held) cardHold = live
            }
        }
        val display = if (nav.cardOpen) cardHold else null
        // The card composable reports done: after its result flash (resolved
        // prompt) or on "decide later" (prompt still queued). Resolving the
        // last prompt goes home (spec); chaining otherwise happens through
        // recomposition — releasing the hold lets the next front slide in.
        val finishCard: (BridgeViewModel.PendingPermission) -> Unit = { finished ->
            cardHold = null
            val queue = currentUi.permissionQueue
            nav = when {
                queue.any { it.permissionId == finished.permissionId } -> nav.back()
                queue.isEmpty() -> nav.jumpHome()
                else -> nav.copy(cardPermissionId = null)
            }
        }
        if (nav.cardOpen && display != null && !ui.isOffline()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Halo.Palette.Background)
                    // The card is modal (handoff §5: answering or "decide
                    // later" are the only exits): this pointer node keeps
                    // taps and swipes off the invisible screens underneath —
                    // without it the top TimeText strip and the back detector
                    // still receive input — and owns swipe-down as the
                    // "decide later" exit.
                    .pointerInput(Unit) {
                        val threshold = size.height * SWIPE_THRESHOLD_FRACTION
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total > threshold) nav = nav.back() },
                        ) { change, dragAmount ->
                            total += dragAmount
                            change.consume()
                        }
                    },
            ) {
                AnimatedContent(
                    targetState = display,
                    transitionSpec = {
                        (slideInHorizontally(tween(TRANSITION_MS, easing = HaloEasing)) { (it * SLIDE_FRACTION).roundToInt() } +
                            fadeIn(tween(TRANSITION_MS, easing = HaloEasing)))
                            .togetherWith(fadeOut(tween(TRANSITION_MS / 2)))
                    },
                    // Key on the id so metadata refreshes (e.g. a session
                    // label arriving late) update in place; carrying the
                    // VALUE keeps the exiting layer rendering the resolved
                    // card, which is already gone from the queue.
                    contentKey = { it.permissionId },
                    label = "haloCard",
                ) { card ->
                    if (card.questions.isNotEmpty()) {
                        HaloQuestionCard(
                            card = card,
                            model = model,
                            ui = ui,
                            onAnswers = actions.onAnswerQuestions,
                            onDismiss = actions.onDismissPermission,
                            onDictate = actions.onDictate,
                            onDone = { finishCard(card) },
                        )
                    } else {
                        HaloApprovalCard(
                            card = card,
                            model = model,
                            ui = ui,
                            onAnswer = actions.onAnswerPermission,
                            onDismiss = actions.onDismissPermission,
                            onDone = { finishCard(card) },
                        )
                    }
                }
            }
        }

        // Offline/unpaired takes the whole screen: state colors and pending
        // approvals are stale the moment the stream drops (handoff §8).
        if (ui.isOffline()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Halo.Palette.Background)
                    // A takeover, not a scrim: swallow every gesture so the
                    // hidden pager/centerpiece/back detectors can't be driven
                    // invisibly while offline. The screen's own controls are
                    // children and are hit first.
                    .consumeAllGestures(),
            ) {
                HaloOfflineScreen(ui = ui, onPair = actions.onPair)
            }
        }
    }
}

/** True when the bridge cannot currently receive commands or answers. */
private fun BridgeViewModel.UiState.isOffline(): Boolean =
    !paired || status.contains("reconnecting")

/**
 * Modal scrim: a pointer node here removes the sibling layers underneath
 * from the hit path (a bare background does NOT hit-test, so without this
 * every gesture falls through), and consuming keeps ancestors quiet too.
 * Children of the overlay still receive their events first.
 */
private fun Modifier.consumeAllGestures(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

// ── Depth layers & motion ───────────────────────────────────────────────────

/** What AnimatedContent keys on: page changes animate via the pager instead. */
private sealed interface Layer {
    val rank: Int

    data object Pager : Layer {
        override val rank = 0
    }

    data class SessionList(val scope: ListScope) : Layer {
        override val rank = 1
    }

    data class Feed(val sessionId: String) : Layer {
        override val rank = 2
    }
}

private fun layerOf(nav: HaloNavState): Layer = when (nav.depth) {
    HaloDepth.PAGE -> Layer.Pager
    HaloDepth.LIST -> Layer.SessionList(nav.listScope)
    // sessionId is non-null at SESSION by construction (drillToSession /
    // openFirstWaiting); the fallback only guards a hand-built state.
    HaloDepth.SESSION -> nav.sessionId?.let { Layer.Feed(it) } ?: Layer.Pager
}

/**
 * Directional slide+fade: drilling slides the new screen up from below,
 * backing out reverses it; same-rank changes (cycling sessions) and
 * non-spatial jumps (tap-time home) get a fast fade.
 */
private fun androidx.compose.animation.AnimatedContentTransitionScope<Layer>.depthTransition(): ContentTransform {
    val spec = tween<Float>(TRANSITION_MS, easing = HaloEasing)
    val slide = tween<androidx.compose.ui.unit.IntOffset>(TRANSITION_MS, easing = HaloEasing)
    return when {
        targetState.rank > initialState.rank ->
            (slideInVertically(slide) { (it * SLIDE_FRACTION).roundToInt() } + fadeIn(spec))
                .togetherWith(slideOutVertically(slide) { -(it * SLIDE_FRACTION).roundToInt() } + fadeOut(spec))
        targetState.rank < initialState.rank ->
            (slideInVertically(slide) { -(it * SLIDE_FRACTION).roundToInt() } + fadeIn(spec))
                .togetherWith(slideOutVertically(slide) { (it * SLIDE_FRACTION).roundToInt() } + fadeOut(spec))
        else -> fadeIn(tween(150)).togetherWith(fadeOut(tween(150)))
    }
}

// ── The pager (depth = PAGE) ────────────────────────────────────────────────

@Composable
private fun PagerLayer(
    model: HaloModel,
    page: Int,
    onPageChange: (Int) -> Unit,
    onDrill: () -> Unit,
    onTapCenter: () -> Unit,
) {
    val pageCount = 1 + model.projects.size
    // The remembered PagerState outlives this composition's model; the lambda
    // must read the CURRENT one or the page count freezes at first render.
    val currentModel by rememberUpdatedState(model)
    val pagerState = rememberPagerState(
        initialPage = page.coerceIn(0, pageCount - 1),
        pageCount = { 1 + currentModel.projects.size },
    )
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect(onPageChange)
    }

    val drill by rememberUpdatedState(onDrill)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Swipe up drills into the list under the current page. Vertical
            // only — the pager owns horizontal. Threshold per the handoff.
            .pointerInput(Unit) {
                val threshold = size.height * SWIPE_THRESHOLD_FRACTION
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -threshold) drill() },
                ) { _, dragAmount -> total += dragAmount }
            },
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
            if (index == 0) {
                HaloAllPage(model = model, onTapCenter = onTapCenter)
            } else {
                // The pager can briefly ask for a page past a shrunken model.
                val project = model.projects.getOrNull(index - 1) ?: return@HorizontalPager
                HaloProjectPage(project = project, onTapCenter = onTapCenter)
            }
        }
        PageDots(
            count = pageCount,
            current = pagerState.currentPage,
            onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

/**
 * Bottom-center page dots (handoff: current 11px cream, others 8px grey,
 * tappable). Tap targets are deliberately larger than the dots; a full 48dp
 * per dot would overflow the curve with 4+ pages.
 */
@Composable
private fun PageDots(
    count: Int,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (count <= 1) return
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 14.dp, height = 20.dp)
                    .clickable { onSelect(index) },
            ) {
                val isCurrent = index == current
                Box(
                    modifier = Modifier
                        .size(if (isCurrent) 5.5.dp else 4.dp)
                        .background(
                            if (isCurrent) Halo.Palette.DotCurrent else Halo.Palette.DotOther,
                            CircleShape,
                        ),
                )
            }
        }
    }
}

// ── Inner-screen chrome (depth = LIST / SESSION) ────────────────────────────

/**
 * Wraps every screen below the pager with the shared chrome: top TimeText
 * (tap = jump home) and the swipe-down-to-go-back gesture. The back detector
 * sits UNDER the content so a scrolling child (session list/feed) consumes
 * its own drags first.
 */
@Composable
private fun InnerScreen(
    onBack: () -> Unit,
    onHome: () -> Unit,
    content: @Composable () -> Unit,
) {
    val back by rememberUpdatedState(onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Halo.Palette.Background)
            .pointerInput(Unit) {
                val threshold = size.height * SWIPE_THRESHOLD_FRACTION
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > threshold) back() },
                ) { _, dragAmount -> total += dragAmount }
            },
    ) {
        content()
        TimeText(
            timeTextStyle = TimeTextDefaults.timeTextStyle(
                color = Color(0xFF7E7C76),
                fontSize = Halo.Type.Min,
            ),
        )
        // TimeText itself is not clickable; a transparent strip over it is
        // the actual "jump home" target (48dp-tall per touch minimums).
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(96.dp)
                .height(36.dp)
                .clickable(onClick = onHome),
        )
    }
}
