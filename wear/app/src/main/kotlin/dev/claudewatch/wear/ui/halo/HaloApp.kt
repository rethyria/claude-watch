// The Halo root: horizontal pager (All + one page per project) with tappable
// dots, vertical swipes for depth, 300ms directional slide+fade between
// depths, a decorative (non-tappable) TimeText on inner screens, and the
// approval/question card as a top overlay chained off the waiting queue.
// Navigation state itself is the pure HaloNavState machine (HaloNav.kt); this
// file only binds gestures, animation, and the screen composables to it.
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
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
    /** Send [text] to [toSession] (null = the VM's default session). */
    val onSendCommand: (text: String, toSession: String?) -> Unit = { _, _ -> },
    val onCommandDraftChange: (String) -> Unit = {},
    /**
     * Start dictation targeting [sessionId] — the session whose screen asked,
     * not the ViewModel's "most recently working" default, which points at a
     * DIFFERENT session whenever the user dictates from an idle feed. Null
     * keeps the default (no specific session on screen).
     */
    val onDictate: (sessionId: String?) -> Unit = {},
    /**
     * Dictate a question ANSWER: the transcription lands in [onResult] (the
     * question card's answer buffer) instead of being sent as a command —
     * the agent is blocked on AskUserQuestion, so only answerQuestions can
     * resume it.
     */
    val onDictateAnswer: (onResult: (String) -> Unit) -> Unit = {},
    val onAnswerPermission: (permissionId: String, behavior: String) -> Unit = { _, _ -> },
    val onAnswerQuestions: (permissionId: String, answers: List<String>) -> Unit = { _, _ -> },
    val onDismissPermission: (permissionId: String) -> Unit = {},
    /** Voice-overlay Discard: drop the failed draft AND its error together. */
    val onDiscardCommand: () -> Unit = {},
    val onSpawn: (agent: String) -> Unit = {},
    val onKill: (sessionId: String) -> Unit = {},
    /**
     * Honest-hide an EXTERNAL (hook-created, PTY-less) session from view
     * (issue #53): local only, no bridge kill — the row picks this over
     * [onKill] by the session's `external` flag.
     */
    val onHide: (sessionId: String) -> Unit = {},
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

    // §6 "answer later ↓ exits, losing nothing": the question card's answer
    // buffer lives HERE, keyed by prompt id, because the card composable
    // unmounts on every overlay exit (answer later, swipe-down, a reconnect
    // blip) and composition-local state would restart a half-answered prompt
    // at question 1. Entries are pruned once their prompt is resolved.
    val answerDrafts = remember { mutableStateMapOf<String, SnapshotStateList<String?>>() }

    // §7 voice overlay. The listening phase is the system recognizer activity
    // (it covers the screen; see HaloVoiceScreen's header), so the overlay's
    // lifecycle keys off the SEND: armed when a Halo surface launches
    // dictation, opened when its send goes in flight OR its launch/send is
    // refused (commandError with no new send — e.g. no recognizer on the
    // watch, busy refusal), held open on failure (Retry/Discard), closed on
    // the ack — the echo is then in the feed. Cancel while sending stops
    // WATCHING but stays ARMED: an eventual failure must reopen the overlay,
    // because nothing else in Halo renders the restored draft — closing for
    // good would silently lose the text at the rendering layer. Question-card
    // dictation never arms it: those transcriptions are answer buffer
    // entries, not sends. All of it rememberSaveable: the recognizer result
    // is redelivered across activity recreation, and plain remember would
    // drop the armed overlay for a send that is still very much in flight.
    var voiceArmed by rememberSaveable { mutableStateOf(false) }
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    // True once this arm's send/failure has reached the overlay: gates the
    // "concluded quietly" disarm below so the recognizer round-trip (no send
    // yet, no error) isn't mistaken for a finished send.
    var voiceWatched by rememberSaveable { mutableStateOf(false) }
    // Retry must re-target the session of the ORIGINAL dictation, captured at
    // launch: the VM's fallback (most recently working session) can differ.
    var voiceTarget by rememberSaveable { mutableStateOf<String?>(null) }
    // A send already in flight when dictation starts is NOT this dictation's
    // send: without this pin, dictating during another send's ack window
    // would open the overlay showing the OLD text labeled with the NEW target.
    var voicePriorInFlight by rememberSaveable { mutableStateOf<String?>(null) }
    val dictate: (String?) -> Unit = { sessionId ->
        voiceTarget = sessionId
        voicePriorInFlight = ui.commandInFlightText
        voiceArmed = true
        voiceWatched = false
        actions.onDictate(sessionId)
    }
    // Idempotent snapshot writes (same discipline as cardHold below).
    if (voiceArmed && !voiceOpen) {
        val sendAppeared = !voiceWatched && ui.commandInFlightText != null &&
            ui.commandInFlightText != voicePriorInFlight
        if (sendAppeared || ui.commandError != null) {
            voiceOpen = true
            voiceWatched = true
        }
    }
    if (voiceOpen && ui.commandInFlightText == null && ui.commandError == null) {
        // Acked: the echo is in the feed; nothing left to show.
        voiceOpen = false
        voiceArmed = false
        voiceWatched = false
    }
    if (voiceArmed && !voiceOpen && voiceWatched &&
        ui.commandInFlightText == null && ui.commandError == null
    ) {
        // The send the user cancelled off concluded successfully: disarm.
        voiceArmed = false
        voiceWatched = false
    }

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

    Box(modifier = Modifier.fillMaxSize().background(Halo.Palette.Background).testTag("haloRoot")) {
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
                ) {
                    // Same API 31+ trap as the card overlay below: once the
                    // stretch-overscroll starts (first overpull frame), it
                    // consumes every subsequent drag delta BEFORE the list's
                    // nested-scroll chain sees it, so the list's rebuilt
                    // swipe-down-back accumulates only the first frame's few
                    // px and never crosses its threshold — a real finger can
                    // never swipe back, only the synthetic single-delta test
                    // swipe could. The list sits on a round watch with edge
                    // fades, not a stretchy phone surface: drop the effect so
                    // the pull-down leftovers reach the back detector.
                    @OptIn(ExperimentalFoundationApi::class)
                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                        HaloSessionList(
                            model = model,
                            scope = layer.scope,
                            onOpenSession = { nav = nav.drillToSession(it) },
                            onKill = actions.onKill,
                            onHide = actions.onHide,
                            onSpawn = actions.onSpawn,
                            // The list's scrollable eats every vertical drag, so
                            // InnerScreen's back detector can't fire under it; the
                            // list re-triggers back itself via nested scroll.
                            onBack = { nav = nav.back() },
                        )
                    }
                }
                is Layer.Feed -> InnerScreen(
                    onBack = { nav = nav.back() },
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
                        // Dictation from a feed goes to THAT session.
                        onDictate = { dictate(layer.sessionId) },
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
        // Prune resolved prompts' answer drafts (idempotent: only writes when
        // something is actually stale). The held prompt's draft survives its
        // result flash, during which it has already left the queue.
        run {
            val liveIds = ui.permissionQueue.mapTo(mutableSetOf()) { it.permissionId }
            cardHold?.let { liveIds += it.permissionId }
            if (answerDrafts.keys.any { it !in liveIds }) answerDrafts.keys.retainAll(liveIds)
        }
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
                    // without it the back detector under the card still
                    // receives input — and owns swipe-down as the "decide
                    // later" exit.
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
                    }
                    .testTag("haloCard"),
            ) {
                // No stretch-overscroll under the card: on API 31+ the
                // platform stretch effect consumes every drag delta past the
                // scroll bound AND the fling velocity, so the leftovers never
                // reach the cards' overscroll-exit NestedScrollConnections and
                // swipe-down ("decide later" / "answer later") is unreachable
                // by touch. The card is a modal surface, not a stretchy list —
                // disabling the effect restores the §5/§6 pull-down exit.
                @OptIn(ExperimentalFoundationApi::class)
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
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
                            // The prompt's hoisted answer buffer (created on first
                            // open, reused on reopen). Rebuilt if the question
                            // count ever changes under a by-id metadata refresh.
                            val draft = answerDrafts.getOrPut(card.permissionId) {
                                mutableStateListOf<String?>().apply { repeat(card.questions.size) { add(null) } }
                            }
                            if (draft.size != card.questions.size) {
                                draft.clear()
                                repeat(card.questions.size) { draft.add(null) }
                            }
                            HaloQuestionCard(
                                card = card,
                                model = model,
                                ui = ui,
                                answers = draft,
                                onAnswers = actions.onAnswerQuestions,
                                onDismiss = actions.onDismissPermission,
                                // A dictated ANSWER goes to the card's buffer,
                                // never out as a command (the agent is blocked).
                                onDictate = actions.onDictateAnswer,
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
        }

        // §7 voice overlay, above the card (a feed's Dictate and the card
        // both summon it) and below the offline takeover. Modal like the
        // card. Swipe-down (= Cancel) only applies while SENDING: it stops
        // watching but stays armed, so an eventual failure reopens the
        // overlay — nothing else renders the restored draft. In the FAILED
        // state the overlay is deliberately modal: Retry and Discard are the
        // only exits, because a swipe-away would strand the restored text in
        // a draft no Halo surface shows (the silent-loss class issue #20
        // exists to prevent, at the rendering layer this time).
        if (voiceOpen) {
            val cancelVoice = {
                // Reads currentUi: the gesture closure below never restarts.
                if (currentUi.commandError == null) voiceOpen = false
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Halo.Palette.Background)
                    .pointerInput(Unit) {
                        val threshold = size.height * SWIPE_THRESHOLD_FRACTION
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total > threshold) cancelVoice() },
                        ) { change, dragAmount ->
                            total += dragAmount
                            change.consume()
                        }
                    },
            ) {
                HaloVoiceScreen(
                    ui = ui,
                    targetSessionTitle = voiceTarget?.let { id ->
                        model.sessions.firstOrNull { it.id == id }?.title
                    },
                    onRetry = { actions.onSendCommand(currentUi.commandDraft, voiceTarget) },
                    onDiscard = {
                        // The deliberate-loss exit: drops draft AND error (a
                        // lingering error would instantly reopen the overlay).
                        actions.onDiscardCommand()
                        voiceOpen = false
                        voiceArmed = false
                        voiceWatched = false
                    },
                    // No VM abort exists for an in-flight send: Cancel stops
                    // WATCHING it. An eventual failure reopens the overlay
                    // (still armed); an ack echoes into the feed and disarms.
                    onCancel = cancelVoice,
                )
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
 * Wraps every screen below the pager with the shared chrome: a purely
 * decorative top TimeText and the swipe-down-to-go-back gesture. The clock is
 * deliberately NOT a tap target: swipe-down is the one way back up the depth
 * stack (an invisible hotspot over the time read as an accidental-jump trap
 * in live testing, and a clock that navigates surprises more than it helps).
 * The back detector sits UNDER the content, so it only covers screens without
 * a full-screen scrollable: a scrollable child consumes every vertical drag
 * (its leftover goes to nested scroll, never back to pointer input) and must
 * re-provide back itself, as HaloSessionList does.
 */
@Composable
private fun InnerScreen(
    onBack: () -> Unit,
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
    }
}
