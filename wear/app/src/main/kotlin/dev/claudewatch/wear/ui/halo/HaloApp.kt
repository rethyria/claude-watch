// The Halo root: ONE ring host and a fixed clock as app-level chrome over
// nav-owned pages (settings and usage left of home, All, one page per
// project). Horizontal swipes and tappable dots change the page and only page
// CONTENT slides — the v2 shell (epic #94 S3) dropped HorizontalPager because
// the design has no drag-follow: halo, clock and dots hold still during page
// navigation. Vertical swipes drive depth with content FADES (S7: the ring's
// morphs are the spatial continuity, content follows), a decorative
// (non-tappable) TimeText renders at the root whenever the centre clock is
// hidden, and the approval/question card rides as a top overlay chained off
// the waiting queue.
// Navigation state itself is the pure HaloNavState machine (HaloNav.kt); this
// file only binds gestures, animation, and the screen composables to it.
package dev.claudewatch.wear.ui.halo

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TimeTextDefaults
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.claudewatch.wear.BridgeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Everything Halo can ask the ViewModel to do (mirror of the VM's actions). */
data class HaloActions(
    val onPair: (host: String, port: String, code: String) -> Unit = { _, _, _ -> },
    /**
     * Issue #23 zero-typing: the offline/unpaired screen appeared — fire an
     * NSD scan so a found bridge's host+port pre-fill the pairing form. Fired
     * on every offline-screen entry; the scan is single-flighted and
     * best-effort, so a no-op default and NOOP discovery leave manual entry
     * fully intact.
     */
    val onDiscoverForPairing: () -> Unit = {},
    /**
     * Issue #23 follow-up — the Discover LIST scan: the user opened the Discover
     * pane. Fires an all-bridges NSD scan; results land in [UiState.discover].
     */
    val onDiscoverBridges: () -> Unit = {},
    /** Code-less pair with a bridge tapped in the Discover list (no code entered). */
    val onPairByDiscovery: (dev.claudewatch.wear.net.BridgeDiscovery.DiscoveredBridge) -> Unit = {},
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
    /**
     * Spawn a fresh [agent] session at [cwd] — a project root chosen in the
     * spawn picker (issue #56), `"~"` for a no-project home session, or null
     * for the bridge's default cwd chain.
     */
    val onSpawn: (agent: String, cwd: String?) -> Unit = { _, _ -> },
    val onKill: (sessionId: String) -> Unit = {},
    /**
     * Honest-hide an EXTERNAL (hook-created, PTY-less) session from view
     * (issue #53): local only, no bridge kill — the row picks this over
     * [onKill] by the session's `external` flag.
     */
    val onHide: (sessionId: String) -> Unit = {},
    /**
     * The usage page became current (issue #57): fetch GET /v1/usage. Fired
     * on EVERY entry (fetch-on-open is the whole caching policy), by the
     * usage screen's error-retry tap, and by the on-page auto-poll — all the
     * NON-FORCED path (the VM's rate limit may skip it). An action seam —
     * not a VM call — so instrumented tests can record entries and inject
     * states.
     */
    val onUsageOpen: () -> Unit = {},
    /**
     * Manual usage refresh (2026-07-18): the freshness label's tap. Wired to
     * fetchUsage(force = true) — bypasses the VM's rate limiter; the
     * silent-refresh rule keeps the bars on screen while it lands.
     */
    val onUsageRefresh: () -> Unit = {},
)

/**
 * Ambient mode (issue #24, handoff §9's minimal rendition), for whatever
 * composable needs to know it without threading a parameter through every
 * screen: currently the usage skeleton, whose infinite pulse must FREEZE
 * wrist-down (an infinite transition burns watts redrawing a display nobody
 * is looking at). Provided by [HaloApp] from the activity's
 * AmbientLifecycleObserver flag.
 */
internal val LocalHaloAmbient = compositionLocalOf { false }

/** Handoff motion: 300ms cubic-bezier(0.2,0.7,0.3,1), 70px slide at 450 ref. */
private val HaloEasing = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)
private const val TRANSITION_MS = 300
private const val SLIDE_FRACTION = 70f / HALO_REF_PX

/** Swipe threshold ≈60px at the 450 reference. */
private const val SWIPE_THRESHOLD_FRACTION = 60f / HALO_REF_PX

/** A swipe suppresses the synthetic tap that can follow it for this long. */
private const val TAP_GUARD_MS = 300L

/**
 * The Answer pill's top edge on the main pages, DERIVED from the clock group
 * (#104 user feedback, superseding S3's screen-absolute 154dp): the
 * re-centred group's bottom plus the prototype's 21px clearance — the same
 * derivation that produced the design's own "top 238px inside the 70px-inset
 * face container" (= 308px = box-centred group bottom + 21px), re-based on
 * the group's computed centre line so the pill follows the clock up. Still
 * OUT OF FLOW on purpose: the clock+subtitle group never shifts when the
 * pill appears. The session pager does NOT share this number any more — its
 * pill rides the card group per the prototype's own pager geometry (see
 * HaloSessionPager's pill layer).
 */
internal val ANSWER_PILL_TOP = (Halo.Geo.AnswerPillTopPx / 2f).dp

/**
 * On-page usage auto-poll period: the VM's `usageRateLimitMs` (300_000L)
 * plus a 10s buffer. The buffer guarantees each non-forced poll lands PAST
 * the limiter even with clock jitter between delay's monotonic clock and
 * the limiter's System.currentTimeMillis() — delay never fires early, so
 * every tick is a real refetch, never a silently-skipped one.
 */
private const val USAGE_AUTO_POLL_MS = 310_000L

@Composable
fun HaloApp(
    ui: BridgeViewModel.UiState,
    actions: HaloActions,
    // Wrist-down (issue #24). Defaulted false so every existing call site and
    // fixture-driven test stays source-compatible.
    ambient: Boolean = false,
) {
    // The flag also rides a CompositionLocal so DEEP consumers (the usage
    // skeleton's pulse) can read it without a parameter chain through every
    // screen composable; the body itself takes it as a plain parameter.
    CompositionLocalProvider(LocalHaloAmbient provides ambient) {
        HaloAppBody(ui = ui, actions = actions, ambient = ambient)
    }
}

@Composable
private fun HaloAppBody(
    ui: BridgeViewModel.UiState,
    actions: HaloActions,
    ambient: Boolean,
) {
    val model = HaloModel.from(ui)
    var nav by remember { mutableStateOf(HaloNavState()) }
    // Gesture lambdas live inside pointerInput(Unit) blocks that never
    // restart; these keep them reading the current state, not a stale capture.
    val currentModel by rememberUpdatedState(model)
    val currentUi by rememberUpdatedState(ui)
    var lastSwipeAtMs by remember { mutableLongStateOf(0L) }

    // Issue #56: the spawn picker overlay. The list's "+ new claude session"
    // row OPENS it instead of firing blind; a pick spawns-and-closes, the
    // swipe-down cancel closes without spawning. A plain flag (like the
    // overlays below, not a nav depth): it floats over the list it was
    // summoned from and closing must land exactly there.
    var spawnPickerOpen by remember { mutableStateOf(false) }

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

    // The pager selection's last RESOLVED slot index, for the kill-under-
    // cursor self-heal below: when the selected session vanishes, the session
    // now sitting at this index is the dead one's next-door neighbour. Plain
    // Int state (not derived) because the whole point is remembering a
    // position the CURRENT model can no longer answer.
    var lastListIndex by remember { mutableIntStateOf(0) }

    // The last pager step's direction, for the ring engine's 2-session
    // backstep retrace (RingInputs.stepDir): with two sessions every step is
    // an exact half turn, and only this tells the highlight to retrace
    // instead of orbiting. Reset on each list entry so a stale BACK from a
    // previous visit can never flip a fresh drill's first rotation.
    var lastStepDir by remember { mutableStateOf(StepDir.NONE) }

    // The model can shrink under the navigation (session killed, project's
    // last session gone, queue resolved elsewhere): back out to something
    // that still exists rather than rendering a ghost.
    LaunchedEffect(model, nav) {
        val scope = nav.listScope
        if (nav.depth == HaloDepth.LIST) {
            val at = model.sessionsIn(scope).indexOfFirst { it.id == nav.sessionId }
            if (at >= 0) lastListIndex = at
        }
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
            // The LIST-depth self-heal (S5): back-from-a-vanished-feed parks
            // the dead id as the pager selection and step/atListStart
            // deliberately dead-end on it — re-resolve to the remembered-index
            // neighbour (or All's spawn card) before the pager strands on a
            // ghost with ‹/›/step all no-ops. Ordered AFTER the vanished-
            // project branch: an emptied project scope backs out above.
            nav.depth == HaloDepth.LIST && nav.sessionId != null &&
                model.sessionsIn(scope).none { it.id == nav.sessionId } ->
                nav.healListSelection(model, lastListIndex)
            // A vanished project can also strand the PAGE index past the end:
            // clamp back to the last page, exactly what the retired pager's
            // shrinking pageCount used to do.
            nav.page > model.projects.size -> nav.copy(page = model.projects.size)
            else -> nav
        }
    }

    // Issue #109: the SYSTEM back — one BackHandler at the root, enabled off
    // the pure systemBack (HaloNav.kt), which owns routing and priority:
    // topmost overlay first, then the card, then a depth step, then non-home
    // pages jump home; null disables the handler so the system's own exit
    // stands. The in-app surface gestures are untouched — they never reach
    // this dispatcher.
    //
    // Predictive-back finding (do not remove the manifest opt-in): Wear
    // never TRANSLATES the left-bezel edge swipe into this callback.
    // Measured on the API 33 AND API 34 (Wear OS 5, the SM-L330's
    // generation) emulator images, the flag-gated dispatch is a SUPPRESSION
    // switch instead: while this handler is enabled, androidx registers a
    // window OnBackInvokedCallback and the system leaves the edge swipe
    // alone — the activity CANNOT be swiped away mid-session, and the
    // swipe's touches reach the app like any drag, where the surface
    // gestures (feed swipe-right = back, pager step, page step) own it (the
    // manifest flag's comment holds the full measured story); while it is
    // disabled (home at rest) nothing is registered and the system
    // swipe-dismiss exits to the watch face. What lands HERE is KEYCODE_BACK —
    // the watch's hardware back button and Espresso's pressBack — routed
    // through the OnBackInvokedDispatcher when enabled, falling through to
    // the system exit when not. Without the flag the system dismisses from
    // ANYWHERE without consulting any dispatcher — the #109 mid-session
    // exit. HaloSystemBackTest pins the keyevent walks end-to-end and the
    // armed suppression half with a real shell-injected bezel swipe; the
    // live wrist walk on the SM-L330 is the issue's remaining checklist
    // item.
    val backRoute = systemBack(nav, overlayOpen = voiceOpen || spawnPickerOpen)
    BackHandler(enabled = backRoute != null) {
        when (backRoute) {
            SystemBack.DismissOverlay -> when {
                // The voice overlay renders ABOVE the picker (both can be up:
                // an armed send can fail while the picker is open), so it
                // dismisses first — under the overlay's own modality rule: a
                // FAILED send keeps it up (Retry/Discard are the only exits,
                // same as its swipe-down), yet back stays intercepted — it
                // must not navigate the hierarchy under a modal overlay.
                voiceOpen -> if (currentUi.commandError == null) voiceOpen = false
                else -> spawnPickerOpen = false
            }
            is SystemBack.Navigate -> nav = backRoute.nav
            // Unreachable: a null route disables the handler above.
            null -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Halo.Palette.Background).testTag("haloRoot")) {
        // The ONE ring (v2 shell, S7 morphs): the PERSISTENT bottom layer,
        // fed the full nav-derived snapshot — page arcs, the list's dotted
        // ring + hero highlight, the feed's full circle, and the morphs
        // between them all live in the host's engine. The depth layers above
        // stopped painting opaque backgrounds (InnerScreen) so it shows
        // through; only true overlays (cards, voice, spawn picker, offline)
        // still cover it.
        HaloRingHost(inputs = HaloRingMath.ringInputs(nav, model, lastStepDir))

        AnimatedContent(
            targetState = layerOf(nav),
            transitionSpec = { depthTransition() },
            label = "haloDepth",
        ) { layer ->
            when (layer) {
                Layer.Pager -> PageLayer(
                    model = model,
                    page = nav.page,
                    usage = ui.usage,
                    // The connection status feeds the settings page's honest
                    // "you are paired" line above its Unpair (the only
                    // connection descriptor on the UiState).
                    status = ui.status,
                    onStepPage = { delta ->
                        lastSwipeAtMs = SystemClock.uptimeMillis()
                        nav = nav.stepPage(delta, currentModel)
                    },
                    onSelectPage = { nav = nav.copy(page = it) },
                    onDrill = {
                        lastSwipeAtMs = SystemClock.uptimeMillis()
                        lastStepDir = StepDir.NONE
                        nav = nav.drillToList(currentModel)
                    },
                    // The centerpiece tap opens the session list too (v2 nav:
                    // "tap face or swipe up"); its old jump-to-prompt job
                    // moved to the Answer pill below.
                    onTapCenter = {
                        if (SystemClock.uptimeMillis() - lastSwipeAtMs > TAP_GUARD_MS) {
                            lastStepDir = StepDir.NONE
                            nav = nav.drillToList(currentModel)
                        }
                    },
                    onAnswer = { nav = nav.openFirstWaiting(currentModel) },
                    onUsageOpen = actions.onUsageOpen,
                    onUsageRefresh = actions.onUsageRefresh,
                    // Finally consumed: onUnpair has been declared + VM-wired
                    // since issue #23 but never rendered — the settings page's
                    // confirm-gated Unpair is its first invocation.
                    onUnpair = actions.onUnpair,
                )
                is Layer.SessionList -> InnerScreen(
                    onBack = { nav = nav.back() },
                ) {
                    // The v2 pager (S5) has no scrollable: vertical drags fall
                    // straight through to InnerScreen's back detector — the
                    // app-wide swipe-down-back, kept by approved deviation.
                    HaloSessionPager(
                        model = model,
                        scope = layer.scope,
                        selectedId = nav.sessionId,
                        // The at-start-goes-back rule stays the nav's pinned
                        // predicate; the pager only obeys it.
                        atStart = nav.atListStart(model),
                        onStep = { delta ->
                            lastStepDir = if (delta < 0) StepDir.BACK else StepDir.FORWARD
                            nav = nav.step(delta, currentModel)
                        },
                        onBack = { nav = nav.back() },
                        onOpenSession = { nav = nav.drillToSession(it) },
                        // The Answer pill: the card OVER the list, pinned to
                        // this session's own prompt — never a feed drill.
                        onAnswer = { session -> nav = nav.openCardForListSession(session) },
                        onKill = actions.onKill,
                        onHide = actions.onHide,
                        // Issue #56: the spawn card summons the target picker
                        // overlay; the actual onSpawn fires from a pick.
                        onSpawn = { spawnPickerOpen = true },
                        // Reclaim the bezel when the picker closes (its
                        // rotary node stole focus and is now disposed).
                        rotaryActive = !spawnPickerOpen,
                    )
                }
                is Layer.Feed -> InnerScreen(
                    onBack = { nav = nav.back() },
                ) {
                    HaloSessionFeed(
                        model = model,
                        sessionId = layer.sessionId,
                        ui = ui,
                        // The feed tap belongs to THIS session: pin its own
                        // prompt, not whatever sits at the global queue front.
                        onOpenCard = {
                            val pending = currentModel.sessions
                                .firstOrNull { it.id == layer.sessionId }?.pending
                            nav = nav.openCard(pending?.permissionId)
                        },
                        // Dictation from a feed goes to THAT session.
                        onDictate = { dictate(layer.sessionId) },
                        // Swipe-right and the at-top pull-down both land here;
                        // back() keeps the session as the pager selection (#95).
                        onBack = { nav = nav.back() },
                    )
                }
            }
        }

        // The decorative top clock, lifted OUT of the screens (v2 shell): it
        // shows whenever the centre clock doesn't — the inner depths and the
        // depth-less glance pages — and, living at the root, it can never
        // fade or slide with content (the coming morphs animate content
        // hard; the time must not blink). Still deliberately NOT a tap
        // target: an invisible hotspot over the time read as an
        // accidental-jump trap in live testing. Padded off the rim into the
        // ring channel's clear interior (ClockRingClearance): the list's
        // dotted ring and the feed circle own the edge band the platform
        // default hugs, and the ambient clock contract (handoff §Ambient)
        // keeps this TimeText on those depths — hiding it was not an option.
        if (nav.depth != HaloDepth.PAGE || nav.page < 0) {
            TimeText(
                timeTextStyle = TimeTextDefaults.timeTextStyle(
                    color = Color(0xFF7E7C76),
                    fontSize = Halo.Type.Min,
                ),
                // top → the curved row's OUTER arc padding on round screens.
                contentPadding = PaddingValues(top = (Halo.Geo.ClockRingClearance / 2f).dp),
            )
        }

        // Issue #56: the spawn target picker, over the list that summoned it
        // and UNDER the approval card / offline takeover (a prompt or a
        // dropped stream outranks choosing a spawn directory).
        if (spawnPickerOpen) {
            // NO gesture-swallowing wrapper: consuming the down in the Main
            // pass reads to the picker list's scrollable as "another detector
            // claimed this gesture", cancelling its drag recognition — which
            // silently killed the nested-scroll swipe-down cancel for real
            // fingers (device-bisected). The offline takeover below carried
            // the exact same bug for its tap targets — the pair Chip and text
            // fields — until it too dropped the wrapper. No shield is needed
            // either: the picker's ScalingLazyColumn is fillMaxSize, so ITS
            // handlers own every hit on screen and nothing falls through to
            // the session list below; and this overlay is a root-Box sibling,
            // so no ancestor back detector can double-handle the pull.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Halo.Palette.Background)
                    .testTag("haloSpawnPicker"),
            ) {
                // Same API 31+ trap as the session list: the stretch-
                // overscroll would consume every post-overpull drag delta
                // before nested scroll sees it, making the picker's rebuilt
                // swipe-down cancel unreachable by a real finger.
                @OptIn(ExperimentalFoundationApi::class)
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                    HaloSpawnPicker(
                        model = model,
                        onPick = { cwd ->
                            spawnPickerOpen = false
                            actions.onSpawn("claude", cwd)
                        },
                        onCancel = { spawnPickerOpen = false },
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
                    .background(Halo.Palette.Background),
                // NO gesture-swallowing wrapper — the same real-touch trap the
                // spawn picker above was device-bisected out of. Consuming the
                // whole down..up stream in the Main pass reads to a CHILD's tap
                // recognizer as "another detector claimed this gesture", so the
                // pair Chip and the host/port/code fields never registered a
                // real finger's tap: only synthetic adb/instrumented taps —
                // whose instant single-event stream slips through — worked,
                // which is why every gate stayed green while on-wrist pairing
                // was impossible (the whole reason this screen exists). No
                // shield is needed either, for the picker's reasons: this Box
                // is a root-Box sibling (no ancestor back detector can
                // double-handle a gesture) and HaloOfflineScreen's Column is
                // fillMaxSize, so its own controls own every hit — and offline
                // there are no sessions behind it to drive anyway.
            ) {
                HaloOfflineScreen(
                    ui = ui,
                    onPair = actions.onPair,
                    onDiscoverForPairing = actions.onDiscoverForPairing,
                    onDiscoverBridges = actions.onDiscoverBridges,
                    onPairByDiscovery = actions.onPairByDiscovery,
                )
            }
        }

        // Ambient (issue #24, handoff §9's minimal rendition — the wrist-down
        // terminal): one dimming scrim over the WHOLE root, offline takeover
        // included, so every lit pixel drops together; TimeText underneath
        // stays visible — it is the ambient clock. Unlike the takeover above
        // this is a TRUE scrim: a bare background does not hit-test, so the
        // wake-tap the system delivers on exit still lands where it aimed.
        // The infinite animations are frozen separately via LocalHaloAmbient
        // (see the usage skeleton). The tag exists ONLY while ambient, which
        // is what the instrumented tests assert the mode by.
        if (ambient) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .testTag("haloAmbient"),
            )
        }
    }
}

/** True when the bridge cannot currently receive commands or answers. */
private fun BridgeViewModel.UiState.isOffline(): Boolean =
    !paired || status.contains("reconnecting")


// ── Depth layers & motion ───────────────────────────────────────────────────

/** What AnimatedContent keys on: page changes animate INSIDE the pager layer
 *  (its own content AnimatedContent), never at this level. */
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
 * Depth transitions are FADES since the S7 morphs: the persistent ring is the
 * spatial continuity now — content just follows it (out fast, in late), so a
 * slide would fight the dash split happening beneath. The adjacent
 * transitions use the morph windows (out .25s / in .45s delayed .1s), the
 * list→page return is the quick fade home, and the rank-2 jumps (Answer-pill
 * page→feed, jump-home from a resolved card) SNAP — they happen under the
 * opaque card, where an animation would be unseen theatre and the ring snaps
 * with them. Same-rank changes (cycling feed sessions) keep the fast fade.
 */
private fun androidx.compose.animation.AnimatedContentTransitionScope<Layer>.depthTransition(): ContentTransform {
    val ranks = targetState.rank - initialState.rank
    return when {
        ranks > 1 || ranks < -1 ->
            fadeIn(snap()).togetherWith(fadeOut(snap()))
        ranks < 0 && targetState is Layer.Pager ->
            fadeIn(tween(Halo.Motion.ListToPageFadeMs, easing = HaloEasing))
                .togetherWith(fadeOut(tween(Halo.Motion.ContentFadeOutMs, easing = HaloEasing)))
        ranks != 0 ->
            fadeIn(
                tween(
                    Halo.Motion.ContentFadeInMs,
                    delayMillis = Halo.Motion.ContentFadeInDelayMs,
                    easing = HaloEasing,
                ),
            ).togetherWith(fadeOut(tween(Halo.Motion.ContentFadeOutMs, easing = HaloEasing)))
        else -> fadeIn(tween(150)).togetherWith(fadeOut(tween(150)))
    }
}

// ── The page layer (depth = PAGE) ───────────────────────────────────────────

/**
 * Horizontal page slide (the pager's replacement): only CONTENT moves — the
 * ring, clock and dots are chrome outside these AnimatedContents. The offset
 * is the layer-derived 70px (passed in), NOT a fraction of the entering
 * content: the centerpiece's subtitle slot and the full-screen page body run
 * this same spec, and a content-relative offset would make the small subtitle
 * crawl while the page body slides. Clipping is off for the same reason — the
 * subtitle must slide out of its slot's bounds, not be sheared at them.
 */
private fun androidx.compose.animation.AnimatedContentTransitionScope<Int>.pageTransition(
    slidePx: Int,
): ContentTransform {
    val spec = tween<Float>(TRANSITION_MS, easing = HaloEasing)
    val slide = tween<androidx.compose.ui.unit.IntOffset>(TRANSITION_MS, easing = HaloEasing)
    val transform = when {
        targetState > initialState ->
            (slideInHorizontally(slide) { slidePx } + fadeIn(spec))
                .togetherWith(slideOutHorizontally(slide) { -slidePx } + fadeOut(spec))
        targetState < initialState ->
            (slideInHorizontally(slide) { -slidePx } + fadeIn(spec))
                .togetherWith(slideOutHorizontally(slide) { slidePx } + fadeOut(spec))
        else -> fadeIn(tween(150)).togetherWith(fadeOut(tween(150)))
    }
    return transform using SizeTransform(clip = false)
}

@Composable
private fun PageLayer(
    model: HaloModel,
    page: Int,
    usage: BridgeViewModel.UsageUi,
    status: String,
    onStepPage: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
    onDrill: () -> Unit,
    onTapCenter: () -> Unit,
    onAnswer: () -> Unit,
    onUsageOpen: () -> Unit,
    onUsageRefresh: () -> Unit,
    onUnpair: () -> Unit,
) {
    // Dot slot 0 is SETTINGS, slot 1 is USAGE (issue #57), so dotIndex =
    // nav.page + 2: All keeps nav.page 0 (slot 2, the landing page), usage
    // keeps nav.page -1 (slot 1) and settings is nav.page -2 (slot 0).
    val pageCount = 3 + model.projects.size
    val currentPage by rememberUpdatedState(page)
    val usageOpen by rememberUpdatedState(onUsageOpen)
    LaunchedEffect(Unit) {
        snapshotFlow { currentPage }.collect { p ->
            // Fetch-on-open (issue #57): EVERY landing on the usage page —
            // swipe, dot tap, re-entry after a depth round trip (this effect
            // restarts with the layer and re-emits the current page) —
            // re-fetches. No client cache by design; this snapshotFlow IS the
            // "page became current" seam. (The sit-and-watch case is the
            // separate auto-poll loop below.)
            if (p == USAGE_PAGE) usageOpen()
        }
    }
    // On-page auto-poll (2026-07-18, user-directed): sitting on the usage
    // page refreshes when the data hits the rate-limit age instead of
    // waiting for a re-navigation. STRICTLY FOREGROUND-ONLY ("only auto-poll
    // if the page is open, don't poll in the background"): being the current
    // page is NOT enough — a backgrounded activity keeps its composition
    // alive — so the delay loop ALSO gates on the lifecycle being RESUMED
    // via the standard repeatOnLifecycle idiom. Leaving the page
    // (collectLatest cancels the inner block), leaving the screen (the
    // LaunchedEffect dies with the composition), backgrounding the app, or
    // the watch going ambient/inactive (repeatOnLifecycle suspends below
    // RESUMED) all stop the poll; returning restarts the wait from zero —
    // and the page-ENTRY fetch above already covers the return-to-app case,
    // so the loop only ever handles sit-and-watch. Each poll goes through
    // the NON-FORCED onUsageOpen: the VM's limiter still owns the request
    // budget, and the silent-refresh rule makes the swap invisible.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        snapshotFlow { currentPage == USAGE_PAGE }.collectLatest { onUsagePage ->
            if (onUsagePage) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        delay(USAGE_AUTO_POLL_MS)
                        usageOpen()
                    }
                }
            }
        }
    }

    val drill by rememberUpdatedState(onDrill)
    val step by rememberUpdatedState(onStepPage)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Swipe up drills into the list under the current page. Vertical
            // only — threshold per the handoff. On the settings/usage pages
            // the drill lands in HaloNav's no-op (#57): both are flat,
            // depth-less glance surfaces.
            .pointerInput(Unit) {
                val threshold = size.height * SWIPE_THRESHOLD_FRACTION
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -threshold) drill() },
                ) { _, dragAmount -> total += dragAmount }
            }
            // Horizontal swipes step the nav-owned page (no drag-follow by
            // design). Deltas are CONSUMED so the centerpiece's whole-screen
            // clickable sees the gesture as claimed and cancels its press —
            // the tap guard stays as the second line of defence.
            .pointerInput(Unit) {
                val threshold = size.width * SWIPE_THRESHOLD_FRACTION
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        when {
                            // Finger travels right → the page to the LEFT.
                            total > threshold -> step(-1)
                            total < -threshold -> step(1)
                        }
                    },
                ) { change, dragAmount ->
                    total += dragAmount
                    change.consume()
                }
            },
    ) {
        // The shared content-slide offset: 70px at the 450 ref, derived from
        // the layer (== screen) size once, so every page AnimatedContent
        // moves in lockstep (see pageTransition).
        val slidePx = (constraints.maxWidth * SLIDE_FRACTION).roundToInt()

        // The fixed centre clock: home and project pages only — the glance
        // pages carry the root TimeText instead, so the centerpiece fades
        // (never slides) across that boundary. Its subtitle SLOT is the one
        // piece of the group that changes per page, sliding in lockstep with
        // the page body below.
        AnimatedVisibility(
            visible = page >= 0,
            enter = fadeIn(tween(TRANSITION_MS, easing = HaloEasing)),
            exit = fadeOut(tween(TRANSITION_MS, easing = HaloEasing)),
        ) {
            HaloCenterpiece(onTap = onTapCenter, modifier = Modifier.testTag("haloCenter")) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = { pageTransition(slidePx) },
                    label = "haloSubtitle",
                ) { p ->
                    when {
                        p == 0 -> Text(
                            text = haloCensusText(model.projectCount, model.sessionCount),
                            fontSize = Halo.Type.Caption,
                            color = Halo.Palette.TextSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.testTag("haloCensus"),
                        )
                        p >= 1 -> model.projects.getOrNull(p - 1)?.let { project ->
                            Text(
                                text = project.name,
                                fontSize = Halo.Type.Title,
                                color = Halo.Palette.TextSecondary,
                                textAlign = TextAlign.Center,
                                // Folder names are unbounded; the slot is not.
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // page < 0 only renders mid-fade-out; nothing to say.
                        else -> {}
                    }
                }
            }
        }

        AnimatedContent(
            targetState = page,
            transitionSpec = { pageTransition(slidePx) },
            label = "haloPage",
        ) { p ->
            when {
                p == SETTINGS_PAGE -> HaloSettingsScreen(onUnpair = onUnpair, status = status)
                // The usage page: bars only, retry re-fires the same fetch
                // the entry did; the freshness label's tap is the
                // forced-refresh seam.
                p == USAGE_PAGE -> HaloUsageScreen(
                    usage = usage,
                    onRetry = usageOpen,
                    onRefresh = onUsageRefresh,
                )
                else -> Box(modifier = Modifier.fillMaxSize()) {
                    // Home/project body: the clock is chrome, so all that
                    // slides here is the Answer pill — the jump to the scope's
                    // first waiting prompt (home = global queue front, a
                    // project = its own first waiting item), shown only while
                    // one exists. Absolutely positioned below the clock group
                    // (out of flow): the clock+subtitle never shift, with or
                    // without it. `getOrNull` guards the one frame between a
                    // project vanishing and the self-heal clamping the page.
                    val scopeWaiting = if (p == 0) {
                        model.queue.isNotEmpty()
                    } else {
                        model.projects.getOrNull(p - 1)
                            ?.let { project -> model.queue.any { it.projectName == project.name } }
                            ?: false
                    }
                    if (scopeWaiting) {
                        HaloAnswerPill(
                            onClick = onAnswer,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = ANSWER_PILL_TOP),
                        )
                    }
                }
            }
        }

        // Full-screen, not bottom-aligned: the dots place themselves on an arc
        // measured from the display centre, so they need the whole face.
        PageDots(
            count = pageCount,
            current = page + 2,
            onSelect = { onSelectPage(it - 2) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Arc-length pitch between dot centres, and the tap slot built around it. */
private val DOT_PITCH = 14.dp
private val DOT_SLOT_HEIGHT = 20.dp
private val DOT_SIZE_CURRENT = 5.5.dp
private val DOT_SIZE_OTHER = 4.dp

/**
 * Widest arc the row may occupy. Past this the dots would climb the sides of
 * the face rather than read as a bottom-of-screen row, so a long pager tightens
 * its pitch instead of spreading further.
 */
private const val DOT_ARC_MAX_DEGREES = 120f

/**
 * Page dots, curved along the bottom of the face (handoff: current 11px cream,
 * others 8px grey, tappable). They sit on an arc CONCENTRIC with the status
 * ring, [Halo.Geo.DotChannelClearance] inside its inner stroke edge, so every
 * dot holds the same clearance from the ring however many pages there are — a
 * straight row only clears at 6 o'clock and collides at the ends, where the
 * ring curves down to meet it.
 *
 * Tap targets are deliberately larger than the dots; a full 48dp per dot would
 * overflow the curve with 4+ pages. The TWO LEADING dots are the non-session
 * pages (issue #57): slot 0 is settings, slot 1 is usage, both drawn as an
 * outlined ring instead of a fill — visually distinct and faint, so the
 * home/project pages' dots keep reading as the row's "real" content.
 */
@Composable
private fun PageDots(
    count: Int,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (count <= 1) return
    Layout(
        modifier = modifier,
        content = {
            repeat(count) { index ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = DOT_PITCH, height = DOT_SLOT_HEIGHT)
                        .clickable { onSelect(index) }
                        .testTag("haloDot-$index"),
                ) {
                    val isCurrent = index == current
                    val color = if (isCurrent) Halo.Palette.DotCurrent else Halo.Palette.DotOther
                    val dotSize = if (isCurrent) DOT_SIZE_CURRENT else DOT_SIZE_OTHER
                    if (index <= 1) {
                        // The settings (slot 0) and usage (slot 1) dots: same
                        // footprint, ring not fill — the flat glance pages read
                        // as a distinct pair leading the session dots.
                        Box(modifier = Modifier.size(dotSize).border(1.dp, color, CircleShape))
                    } else {
                        Box(modifier = Modifier.size(dotSize).background(color, CircleShape))
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val slots = measurables.map { it.measure(Constraints()) }

        // Channel-derived (Halo v2): the ring's inner stroke edge is the fixed
        // channel centreline minus half the solid stroke; the clearance token
        // and the dot's own radius — the largest one, so the current dot
        // growing never eats the gap — come off that. DotChannelClearance is
        // expressed so this arithmetic lands bit-identical to the old
        // edge-derived radius (pinned by HaloRingMathTest): the token swap
        // must not move a dot by a pixel.
        val minDim = minOf(width, height).toFloat()
        val scale = minDim / HALO_REF_PX
        val dotEdge =
            (Halo.Geo.RingChannel - Halo.Geo.RingStroke / 2f - Halo.Geo.DotChannelClearance) * scale
        val radius = dotEdge - DOT_SIZE_CURRENT.toPx() / 2f

        // Arc-length pitch → angle, so dot spacing looks identical to the old
        // straight row at 6 o'clock and stays even all the way round.
        val pitch = if (radius > 0f) DOT_PITCH.toPx() / radius else 0f
        val maxSpan = DOT_ARC_MAX_DEGREES * PI.toFloat() / 180f
        val step = if (pitch * (count - 1) > maxSpan) maxSpan / (count - 1) else pitch
        val centerX = width / 2f
        val centerY = height / 2f

        layout(width, height) {
            slots.forEachIndexed { index, slot ->
                // 6 o'clock is +90° with y pointing down, and x = cos θ runs
                // right-to-left as θ grows, so the angle DECREASES as the page
                // index rises — page 0 stays the leftmost dot.
                val angle = PI.toFloat() / 2f - (index - (count - 1) / 2f) * step
                val x = centerX + radius * cos(angle)
                val y = centerY + radius * sin(angle)
                slot.place(
                    x = (x - slot.width / 2f).roundToInt(),
                    y = (y - slot.height / 2f).roundToInt(),
                )
            }
        }
    }
}

// ── Inner-screen chrome (depth = LIST / SESSION) ────────────────────────────

/**
 * Wraps every screen below the pager with the shared chrome: the
 * swipe-down-to-go-back gesture — and, since the S7 morphs, NO background:
 * the root ring host is the persistent bottom layer, and the dash split /
 * grow morphs play through these screens' content fades (the feed's mask and
 * the pager's insets already keep content off the ring channel). The top
 * clock these screens show is the ROOT TimeText (v2 shell) — lifted so the
 * morphs can fade content without blinking the time. The back detector sits
 * UNDER the content, so it only covers screens without a full-screen
 * scrollable: a scrollable child consumes every vertical drag (its leftover
 * goes to nested scroll, never back to pointer input) and has to re-provide
 * back itself — the touch-scrolling feed (v2 S6) does, via its at-top
 * pull-down connection, exactly as the retired session list did. The v2
 * pager (and the feed's empty state) has no scrollable, so vertical drags
 * there fall through to this one detector.
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
    }
}
