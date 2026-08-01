// The v2 session list (Halo v2 S5, epic #94): one session per screen instead
// of scrolling rows. Each card is a wrapping title + `model · mode · use%`
// subheading with the shared Answer pill on waiting sessions; ‹ › chevrons and
// horizontal swipes step the selection (no wrap — the All scope ends on the
// trailing "+ new session" card, and stepping right at the start is BACK); an
// unlabelled five-icon action arc rides the bottom with today's exact close
// semantics (✕ kill for bridge-owned sessions, ⊘ honest hide for external
// ones — ACP close-frame limits are #88's scope). Selection itself lives in
// the HaloNav state machine — this file only renders nav.sessionId and maps
// gestures onto the caller's step/back/open lambdas, so every edge (spawn
// slot, empty scope, at-start back) stays pinned by the nav's JVM tests. The
// ring highlight for the selected card is the S4/S7 engine's job: nothing
// here draws or animates the halo.
// px values are at the 450 reference (≈ px/2 in dp, matching HaloTheme).
package dev.claudewatch.wear.ui.halo

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Step-swipe threshold ≈60px at the 450 reference (the app-wide gesture unit). */
private const val STEP_SWIPE_FRACTION = 60f / HALO_REF_PX

/** Step motion: the shared 300ms / 70px / handoff-easing content tokens. */
private val StepEasing = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)
private const val STEP_MS = 300
private const val STEP_SLIDE_FRACTION = 70f / HALO_REF_PX

/** A step swipe suppresses the synthetic tap that can follow it on-device. */
private const val TAP_GUARD_MS = 300L

/**
 * Rotary detent: accumulated crown pixels per pager step. A raw event is a
 * few px, a detent tick tens — 40 keeps one tick ≈ one step without a slow
 * roll skipping cards. Needs on-device tuning (epic #94's own caveat).
 */
private const val ROTARY_DETENT_PX = 40f

/** Card text geometry (epic constants): title 34px/1.14 inside 104px insets. */
private val CARD_TOP = 44.dp
private val CARD_INSET = 52.dp
private val SUBHEADING_GAP = 6.dp
private val SUBHEADING_DOT = 1.5.dp

/** Chevron cells: 48dp hit targets hugging the sides, glyphs 36px Light. */
private val ChevronCell = Halo.Geo.TouchMin

/**
 * The action arc (epic constants): five 52px circles whose centres sit on a
 * 144 ref-px radius from the display centre at 144/117/90/63/36° (90° = the
 * bottom of the face). Adjacent centres are only ~67 ref-px apart along the
 * chord, so the hit cells cannot reach the 48dp minimum without overlapping —
 * same accepted trade as the v1 action strip's 50px circles.
 */
private val ArcButton = 26.dp
private val ArcCell = 33.dp
private const val ARC_RADIUS = 144f
private val ARC_ANGLES = listOf(144f, 117f, 90f, 63f, 36f)

/** One `model · mode · use%` subheading part; [hot] = terracotta (use ≥80). */
internal data class SubheadingPart(val text: String, val hot: Boolean = false)

/**
 * The pager card's subheading, as data: each part only when present, in
 * model → mode → use% order, so the composable renders separators exactly
 * between present parts and omits the whole row when the list is empty.
 * Pure — the S9 wire fields land later (#102) and today's all-null sessions
 * must already produce a correct (empty) row.
 */
internal fun sessionSubheading(
    modelName: String?,
    modeName: String?,
    usePercent: Int?,
): List<SubheadingPart> = buildList {
    modelName?.let { add(SubheadingPart(it)) }
    modeName?.let { add(SubheadingPart(it)) }
    usePercent?.let { add(SubheadingPart("$it%", hot = it >= 80)) }
}

/**
 * One session per screen. [selectedId] is nav's LIST selection verbatim —
 * null renders the trailing spawn card (All scope) or an empty scope. The
 * at-start-goes-back decision is the caller's ([atStart], from the pinned
 * [HaloNavState.atListStart]): ‹ and a right swipe call [onBack] there,
 * [onStep] −1 otherwise. Rotary only ever steps — the nav's no-wrap makes
 * the edges no-ops, so a crown overshoot can never fall out of the list.
 */
@Composable
fun HaloSessionPager(
    model: HaloModel,
    scope: ListScope,
    selectedId: String?,
    atStart: Boolean,
    onStep: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onAnswer: (HaloSession) -> Unit,
    onKill: (String) -> Unit,
    onHide: (String) -> Unit,
    onSpawn: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * False while an overlay (the spawn picker) owns rotary focus above this
     * pager. Keying the focus claim on the flag re-requests the bezel when
     * the overlay closes — the same reclaim pattern the retired list used.
     */
    rotaryActive: Boolean = true,
) {
    // The slot row this screen pages through: scope order (ring order == pager
    // order, #95) plus the trailing spawn slot in All. Null slot = spawn card.
    val sessions = model.sessionsIn(scope)
    val slots: List<HaloSession?> =
        if (scope == ListScope.All) sessions + listOf(null) else sessions
    val selectedIndex = slots.indexOfFirst { it?.id == selectedId }

    // Gesture/rotary lambdas live in pointerInput(Unit)/remembered closures
    // that never restart; these keep them reading the current state.
    val currentAtStart by rememberUpdatedState(atStart)
    val step by rememberUpdatedState(onStep)
    val back by rememberUpdatedState(onBack)
    var lastSwipeAtMs by remember { mutableLongStateOf(0L) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(rotaryActive) { if (rotaryActive) focusRequester.requestFocus() }
    // Reset the detent accumulation whenever the selection lands somewhere
    // new: leftover crown momentum must not carry into the next card.
    val rotaryAccumulated = remember { floatArrayOf(0f) }
    LaunchedEffect(selectedId) { rotaryAccumulated[0] = 0f }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Horizontal swipe steps the pager; a right swipe on the first
            // slot is BACK (the nav's at-start rule). Deltas are CONSUMED so
            // the card's whole-screen clickable sees the gesture as claimed
            // and cancels its press; the uptime tap guard below is the
            // second line of defence against on-device synthetic taps.
            .pointerInput(Unit) {
                val threshold = size.width * STEP_SWIPE_FRACTION
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        when {
                            total > threshold -> {
                                lastSwipeAtMs = SystemClock.uptimeMillis()
                                if (currentAtStart) back() else step(-1)
                            }
                            total < -threshold -> {
                                lastSwipeAtMs = SystemClock.uptimeMillis()
                                step(1)
                            }
                        }
                    },
                ) { change, dragAmount ->
                    total += dragAmount
                    change.consume()
                }
            }
            // Rotary steps too (crown clockwise = next). Steps only, never
            // back: the nav's no-wrap absorbs edge overshoot, and the bezel
            // popping the whole list on a scroll-past would be a trap.
            .onRotaryScrollEvent { event ->
                rotaryAccumulated[0] += event.verticalScrollPixels
                if (rotaryAccumulated[0] >= ROTARY_DETENT_PX) {
                    rotaryAccumulated[0] = 0f
                    step(1)
                } else if (rotaryAccumulated[0] <= -ROTARY_DETENT_PX) {
                    rotaryAccumulated[0] = 0f
                    step(-1)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        // A vanished selection (or an emptied project scope) renders one
        // blank frame; HaloApp's self-heal re-resolves it before the next.
        if (selectedIndex < 0) return@Box

        // Only the CARD slides — chevrons and the action arc below are
        // chrome, holding still like the clock while content steps.
        AnimatedContent(
            targetState = selectedIndex,
            transitionSpec = { stepTransition() },
            label = "haloPagerStep",
        ) { index ->
            // The exiting layer can outlive a shrinking slot list by a frame.
            when (val session = slots.getOrNull(index)) {
                null -> SpawnCard(onSpawn = onSpawn)
                else -> SessionCard(
                    session = session,
                    swipedAtMs = { lastSwipeAtMs },
                    onOpen = { onOpenSession(session.id) },
                    onAnswer = { onAnswer(session) },
                )
            }
        }

        StepChevron(
            glyph = "‹",
            visible = true,
            // ‹ on the first slot is back — the same rule as the right swipe.
            onClick = { if (currentAtStart) back() else step(-1) },
            tag = "haloPrev",
            modifier = Modifier.align(Alignment.CenterStart),
        )
        StepChevron(
            glyph = "›",
            // Invisible on the true last slot (the spawn card in All, the
            // last session in a project) but the CELL stays, so the layout
            // never shifts as the end comes into view.
            visible = selectedIndex < slots.lastIndex,
            onClick = { step(1) },
            tag = "haloNext",
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        // The action arc belongs to sessions only: the spawn card has
        // nothing to close or configure.
        slots.getOrNull(selectedIndex)?.let { session ->
            ActionArc(
                external = session.external,
                onKill = { onKill(session.id) },
                onHide = { onHide(session.id) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The step slide+fade: same directional shape as the page transition. */
private fun androidx.compose.animation.AnimatedContentTransitionScope<Int>.stepTransition(): ContentTransform {
    val spec = tween<Float>(STEP_MS, easing = StepEasing)
    val slide = tween<androidx.compose.ui.unit.IntOffset>(STEP_MS, easing = StepEasing)
    val transform = when {
        targetState > initialState ->
            (slideInHorizontally(slide) { (it * STEP_SLIDE_FRACTION).roundToInt() } + fadeIn(spec))
                .togetherWith(slideOutHorizontally(slide) { -(it * STEP_SLIDE_FRACTION).roundToInt() } + fadeOut(spec))
        targetState < initialState ->
            (slideInHorizontally(slide) { -(it * STEP_SLIDE_FRACTION).roundToInt() } + fadeIn(spec))
                .togetherWith(slideOutHorizontally(slide) { (it * STEP_SLIDE_FRACTION).roundToInt() } + fadeOut(spec))
        else -> fadeIn(tween(150)).togetherWith(fadeOut(tween(150)))
    }
    return transform using SizeTransform(clip = false)
}

// ── The cards ───────────────────────────────────────────────────────────────

@Composable
private fun SessionCard(
    session: HaloSession,
    /** Read at tap time — the guard must see the LATEST swipe, not a capture. */
    swipedAtMs: () -> Long,
    onOpen: () -> Unit,
    onAnswer: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable {
                if (SystemClock.uptimeMillis() - swipedAtMs() > TAP_GUARD_MS) onOpen()
            }
            .testTag("haloPagerCard-${session.id}"),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = CARD_INSET, end = CARD_INSET, top = CARD_TOP),
        ) {
            Text(
                text = session.title,
                fontSize = 17.sp, // 34px Medium
                fontWeight = FontWeight.Medium,
                lineHeight = 19.4.sp, // 1.14
                color = Halo.Palette.TextPrimary,
                textAlign = TextAlign.Center,
                // Wraps by design; the cap only stops a pathological title.
                maxLines = 4,
            )
            val parts = sessionSubheading(session.modelName, session.modeName, session.usePercent)
            if (parts.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = SUBHEADING_GAP),
                ) {
                    parts.forEachIndexed { i, part ->
                        if (i > 0) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .size(SUBHEADING_DOT)
                                    .background(Halo.Palette.DotOther, CircleShape),
                            )
                        }
                        Text(
                            text = part.text,
                            fontSize = 9.5.sp,
                            color = if (part.hot) Halo.Palette.WaitingForYou else Halo.Palette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // The shared Answer pill, exactly where the main pages put it (S3's
        // measured 154dp — see ANSWER_PILL_TOP): its own click target ABOVE
        // the card's, so answering can never fall through into the feed.
        if (session.pending != null) {
            HaloAnswerPill(
                onClick = onAnswer,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = ANSWER_PILL_TOP),
            )
        }
    }
}

/**
 * The trailing "+ new session" card: the All scope's true end and the empty
 * scope's whole content. Same testTag as the retired spawn row — it is the
 * same affordance, opening the unchanged spawn target picker.
 */
@Composable
private fun SpawnCard(onSpawn: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onSpawn)
            .testTag("haloSpawn"),
    ) {
        Text(
            text = "+ new session",
            fontSize = Halo.Type.Body,
            color = Halo.Palette.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Chevrons ────────────────────────────────────────────────────────────────

/**
 * ‹ / › in a 48dp cell hugging the screen side, vertically centred. The cell
 * outlives the glyph (see the › call site); an invisible cell is also
 * disabled — a live tap target nobody can see would be an accidental-step
 * trap. TextSecondary rather than the mock's #3A3C42: the epic leaves the
 * control tint to the implementer, and the #61 readability rule (data the
 * user acts on takes the brighter role) applies to controls too.
 */
@Composable
private fun StepChevron(
    glyph: String,
    visible: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(ChevronCell)
            .clickable(enabled = visible, onClick = onClick)
            .testTag(tag),
    ) {
        if (visible) {
            Text(
                text = glyph,
                fontSize = 18.sp, // 36px Light
                fontWeight = FontWeight.Light,
                color = Halo.Palette.TextSecondary,
            )
        }
    }
}

// ── The action arc ──────────────────────────────────────────────────────────

/**
 * Five unlabelled icon buttons on an arc concentric with the face, centred on
 * 6 o'clock (the page dots' curved-Layout approach, so the row survives every
 * display size). Order along the arc: ◇ model · ◐ mode · close · ▤ compact ·
 * ⇄ handover — the outer four are visible, disabled stubs (0.35 alpha, the
 * v1 strip's treatment); the centre close is LIVE with today's exact
 * semantics: red ✕ kill for a bridge-owned session, ⊘ honest hide for an
 * EXTERNAL one the bridge cannot stop (issue #53 — ACP close-frame limits
 * are #88's scope, not this arc's). The close keeps the stable
 * "haloRowClose" testTag from the retired row strip on purpose: every
 * existing close-semantics test and #88's follow-up find it unmoved.
 */
@Composable
private fun ActionArc(
    external: Boolean,
    onKill: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            ArcButton(glyph = "◇", onClick = null, tag = "haloArc-model")
            ArcButton(glyph = "◐", onClick = null, tag = "haloArc-mode")
            if (external) {
                ArcButton(glyph = "⊘", onClick = onHide, tag = "haloRowClose")
            } else {
                ArcButton(glyph = "✕", tint = Halo.Palette.Error, onClick = onKill, tag = "haloRowClose")
            }
            ArcButton(glyph = "▤", onClick = null, tag = "haloArc-compact")
            ArcButton(glyph = "⇄", onClick = null, tag = "haloArc-handover")
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cells = measurables.map { it.measure(Constraints()) }
        val scale = minOf(width, height) / HALO_REF_PX
        val radius = ARC_RADIUS * scale
        val centerX = width / 2f
        val centerY = height / 2f
        layout(width, height) {
            cells.forEachIndexed { index, cell ->
                // Canvas-degree polar placement, y pointing down: 90° is the
                // bottom of the face, larger angles walk left — so the list
                // order above reads left→right on screen.
                val angle = ARC_ANGLES[index] * PI.toFloat() / 180f
                val x = centerX + radius * cos(angle)
                val y = centerY + radius * sin(angle)
                cell.place(
                    x = (x - cell.width / 2f).roundToInt(),
                    y = (y - cell.height / 2f).roundToInt(),
                )
            }
        }
    }
}

@Composable
private fun ArcButton(
    glyph: String,
    onClick: (() -> Unit)?,
    tag: String,
    tint: androidx.compose.ui.graphics.Color = Halo.Palette.TextPrimary,
) {
    val enabled = onClick != null
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ArcCell)
            .clickable(enabled = enabled, onClick = onClick ?: {})
            .testTag(tag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ArcButton) // 52px circle
                .background(Halo.Palette.Surface2, CircleShape),
        ) {
            Text(
                text = glyph,
                fontSize = 12.sp,
                color = if (enabled) tint else tint.copy(alpha = 0.35f),
            )
        }
    }
}
