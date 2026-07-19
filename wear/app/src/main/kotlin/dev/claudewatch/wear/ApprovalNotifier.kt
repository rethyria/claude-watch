package dev.claudewatch.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import dev.claudewatch.shared.protocol.PermissionOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Actionable approval notifications (issue #25).
//
// The wrist buzzes while the app is backgrounded: a permission request
// arriving over the live SSE stream (held open by BridgeSessionService, #24)
// raises a HIGH-importance notification whose actions answer WITHOUT opening
// the app. Everything decision-shaped in this file is split in two layers:
//
//  - a PURE layer (the content model, the queue diff, the requestCode
//    derivation) that plain-JVM unit tests table exhaustively, because a
//    wrong notification here is a wrong PERMISSION DECISION surface — the
//    same stakes as the in-app card;
//  - an ANDROID layer (ApprovalNotifier + ApprovalNotificationCollector)
//    that is deliberately thin: it renders the pure model and forwards taps
//    to BridgeSessionService, which answers through the SAME BridgeViewModel
//    entry points as the in-app card (ack-gated, 404-drops — see
//    sendDecision), so the queue-removal → cancellation flow is identical no
//    matter which surface answered.
// ---------------------------------------------------------------------------

/**
 * Wear renders at most three notification actions; anything past that is
 * silently dropped by the system UI, so the model caps EXPLICITLY and the
 * comment says where the rest went: the in-app card renders the full
 * canonical option list, and the notification's content tap opens it. With
 * the bridge's current canonical behaviors (allow / allow-always / deny)
 * the cap never actually bites — it exists so a future fourth behavior
 * degrades to "open the app" instead of an invisible action.
 */
internal const val MAX_WEAR_NOTIFICATION_ACTIONS = 3

/**
 * Everything the Android layer needs to render one approval notification,
 * derived from a [BridgeViewModel.PendingPermission] by
 * [approvalNotificationModel] — pure, so the JVM tests pin the mapping.
 *
 * [options] is the bridge's canonical behavior-keyed list VERBATIM (order
 * kept, capped at [MAX_WEAR_NOTIFICATION_ACTIONS]) — #17's rule: actions are
 * keyed by the machine-readable behavior, never inferred from labels or
 * position. [remoteInputQuestion] is true exactly for a SINGLE-question
 * AskUserQuestion prompt, which becomes one free-text Reply action; a
 * MULTI-question prompt gets NO actions at all — a wrist notification cannot
 * walk a multi-question form (buffered positional answers, per-question
 * option lists), the in-app question card owns that flow, so the content
 * tap opening the app is the only affordance.
 *
 * [replyChoices] are the single question's own option labels, surfaced as
 * the RemoteInput's choice chips. Live-demo lesson: without explicit
 * choices, Wear decorates the Reply action with ML-GENERATED Smart Replies
 * ("Still waiting", "Good question") that look exactly like agent options —
 * and a mis-tap sends Google's guess to a blocked session as a real answer.
 * The agent's labels are the only chips allowed on this surface.
 *
 * [optionAnswers] are those same labels AS PLAIN ACTION BUTTONS — the
 * second live-demo lesson: this Wear image renders RemoteInput choices
 * NOWHERE (not on the card, whose chip row belongs to the banned
 * smart-reply machinery, and not on the input screen), so setChoices alone
 * quietly degraded the wrist to free-text only. Plain actions are the one
 * surface that renders deterministically (the Approve/Deny buttons prove
 * it), so the option labels become one-tap answer buttons — but ONLY when
 * the FULL option set fits under the action cap alongside Reply
 * (single-select, ≤ [MAX_OPTION_ANSWER_ACTIONS] options). A truncated menu
 * would misrepresent the agent's question; past the cap (or multiSelect,
 * whose answer is a joined toggle set no single button expresses) the
 * wrist keeps free-text Reply and the in-app card owns the full set.
 */
data class ApprovalNotificationModel(
    val permissionId: String,
    val title: String,
    val text: String,
    val options: List<PermissionOption>,
    val remoteInputQuestion: Boolean,
    val replyChoices: List<String> = emptyList(),
    val optionAnswers: List<String> = emptyList(),
)

/**
 * How many option-answer buttons fit next to the free-text Reply under
 * Wear's 3-action cap. All-or-nothing: see [ApprovalNotificationModel
 * .optionAnswers].
 */
internal const val MAX_OPTION_ANSWER_ACTIONS = MAX_WEAR_NOTIFICATION_ACTIONS - 1

/** Derive the wrist-rendered content for one queued prompt. Pure. */
internal fun approvalNotificationModel(
    prompt: BridgeViewModel.PendingPermission,
): ApprovalNotificationModel {
    val questions = prompt.questions
    return ApprovalNotificationModel(
        permissionId = prompt.permissionId,
        // WHO is asking, next to WHAT KIND of ask — the live-testing lesson
        // from the single-slot card that only said "Bash". Question prompts
        // say "Question" instead of the raw tool identifier
        // ("AskUserQuestion" is wire vocabulary, not wrist vocabulary).
        title = "${if (questions.isNotEmpty()) "Question" else prompt.toolName} · ${prompt.sessionLabel}",
        text = when {
            // The single question IS the text: the summary for question
            // prompts is the placeholder "[AskUserQuestion]", which tells
            // the user nothing they can answer from the wrist.
            questions.size == 1 -> questions.single().question
            // Multi-question: honest about what tapping through leads to.
            questions.size > 1 -> "${questions.size} questions — open to answer"
            else -> prompt.requestSummary
        },
        // Question prompts carry no canonical options (their per-question
        // lists live in tool_input and belong to the in-app card); plain
        // permission prompts keep the canonical list verbatim, capped.
        options = if (questions.isEmpty()) {
            prompt.options.take(MAX_WEAR_NOTIFICATION_ACTIONS)
        } else {
            emptyList()
        },
        remoteInputQuestion = questions.size == 1,
        // The single question's own option labels ride into the RemoteInput
        // as choice chips (see the field's doc); a choice-less question
        // stays pure free text. Multi-question lists keep their options on
        // the in-app card only, same as [options] above.
        replyChoices = if (questions.size == 1) {
            questions.single().options.map { it.label }
        } else {
            emptyList()
        },
        // ...and as one-tap action BUTTONS when the whole set fits (see the
        // field's doc for the all-or-nothing rule and the multiSelect
        // exclusion).
        optionAnswers = questions.singleOrNull()
            ?.takeUnless { it.multiSelect }
            ?.options?.map { it.label }
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_OPTION_ANSWER_ACTIONS }
            ?: emptyList(),
    )
}

/**
 * What one permission-queue emission means for the notification shade:
 * [toPost] are prompts whose ids are NEW (post exactly once, never re-post —
 * a re-notify on an unchanged id would re-alert the wrist for nothing),
 * [toCancelIds] are ids that LEFT the queue.
 */
data class PermissionNotificationDiff(
    val toPost: List<BridgeViewModel.PendingPermission>,
    val toCancelIds: Set<String>,
)

/**
 * Diff the permission queue against the previously seen ids. This diff IS
 * the entire cancellation path: answered here (2xx), answered elsewhere
 * (404 on our POST), expired server-side (also the 404 drop), the bridge's
 * permission-cleared push, and the local-dismiss escape hatch ALL flow
 * through the reducer/ViewModel's queue removal — so cancelling whatever id
 * departs covers every one of them without a separate cleared listener that
 * would only duplicate (and eventually contradict) the queue's truth.
 * Pure; the JVM tests table it.
 */
internal fun diffPermissionNotifications(
    previousIds: Set<String>,
    current: List<BridgeViewModel.PendingPermission>,
): PermissionNotificationDiff {
    val currentIds = current.mapTo(mutableSetOf()) { it.permissionId }
    return PermissionNotificationDiff(
        toPost = current.filter { it.permissionId !in previousIds },
        toCancelIds = previousIds - currentIds,
    )
}

/**
 * The PendingIntent requestCode for one (permissionId, behavior) action.
 *
 * Why this exists: a PendingIntent's identity is (creator, requestCode,
 * Intent.filterEquals) — and filterEquals IGNORES EXTRAS. Two concurrent
 * prompts' Approve actions differ ONLY in extras unless something else
 * distinguishes them, so with a shared requestCode the second getService()
 * would silently return (or, with FLAG_UPDATE_CURRENT, rewrite) the FIRST
 * prompt's PendingIntent — and a tap on prompt B's Approve would answer
 * prompt A: the exact approve-the-wrong-permission race class the watchOS
 * app shipped. Distinct requestCodes per (permissionId, behavior) keep the
 * PendingIntents distinct. The 31-mix below can still collide in 32 bits for
 * adversarial strings, which is why [ApprovalNotifier] ALSO stamps each
 * action intent with a per-pair data URI: filterEquals then differs even
 * under a requestCode collision, and either key alone keeps the intents
 * apart. Pure so the JVM test can assert distinctness over realistic pairs.
 */
internal fun approvalActionRequestCode(permissionId: String, behavior: String): Int {
    var h = permissionId.hashCode()
    h = 31 * h + behavior.hashCode()
    return h
}

/**
 * Wrist label for a behavior key — fixed strings by BEHAVIOR (the same rule
 * as the in-app card's Deny/Approve pills: the bridge's label wording is
 * rendered on the card where there is room; on a 48px action chip the short
 * canonical verb is the label). An unknown behavior falls back to the key
 * itself: honest, and it can only appear if the wire contract grows.
 */
internal fun approvalActionTitle(behavior: String): String = when (behavior) {
    "allow" -> "Approve"
    "allow-always" -> "Always allow"
    "deny" -> "Deny"
    else -> behavior
}

/**
 * Process-wide "is the app UI on screen" flag, flipped by MainActivity's
 * ON_START/ON_STOP (the AmbientState pattern: a plain holder so nothing here
 * depends on an activity reference, and tests can set it directly). The
 * approval collector posts ONLY while this is false: while the UI is
 * visible the in-app card is the approval surface, and a heads-up buzz over
 * the very card the user is reading would be noise. Volatile: the flag is
 * written on the main thread and read from whatever dispatcher hosts the
 * collector.
 */
object AppVisibility {
    @Volatile
    var uiVisible: Boolean = false
}

/**
 * The post/cancel seam [ApprovalNotificationCollector] drives. Exists so the
 * collector's DECISION logic — foreground gating, the permanent swallow, the
 * posted-vs-known bookkeeping behind [ApprovalNotificationCollector
 * .cancelAllPosted] — is plain-JVM testable against a recording fake
 * (ApprovalNotificationCollectorTest): [ApprovalNotifier] is a concrete
 * Android class whose constructor needs a real NotificationManager, so
 * without this seam the collector's branches could only be exercised through
 * the instrumented suite, and every instrumented scenario happens to run
 * with the UI invisible — the visible-UI swallow would go untested entirely.
 */
interface ApprovalNotificationSink {
    fun post(model: ApprovalNotificationModel)
    fun cancel(permissionId: String)
}

/**
 * The Android half: one notification per permissionId on the "approvals"
 * channel, tagged BY the permissionId so post and cancel address exactly one
 * prompt — notify(tag, fixed id) / cancel(tag, fixed id). Never a mutating
 * singleton notification: concurrent prompts coexist in the shade, and
 * resolving one never touches another's.
 */
class ApprovalNotifier(private val context: Context) : ApprovalNotificationSink {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    init {
        // IMPORTANCE_HIGH is the buzz: heads-up + vibration come from the
        // channel importance on Wear, so there is deliberately no custom
        // vibration code here — the platform owns the pattern, the user's
        // channel settings own the override.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Approvals", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    override fun post(model: ApprovalNotificationModel) {
        // Content tap always opens MainActivity: the in-app card renders the
        // full prompt (every option past the action cap, multi-question
        // walks, failure surfaces). Same intent for every prompt, so one
        // shared PendingIntent (requestCode 0) is correct — no per-prompt
        // identity needed where the target is identical.
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridge_chip)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setContentIntent(contentIntent)
            // REMINDER, not CALL and not RECOMMENDATION: CALL-class urgency
            // hijacks Wear's full-screen call surface for something that is
            // not a call, and RECOMMENDATION is ranked DOWN as passive
            // content — the opposite of "the agent is blocked on you".
            // REMINDER is the closest honest class ("the user needs to act
            // now") and Wear surfaces it promptly with the HIGH channel
            // doing the alerting.
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Post-once discipline, belt and braces: the diff never re-posts
            // an unchanged id, and if a host ever double-posts (a restarted
            // service re-announcing still-pending prompts), the same tag
            // replaces silently instead of buzzing again.
            .setOnlyAlertOnce(true)
        if (model.remoteInputQuestion) {
            // Option buttons FIRST (the agent's expected answers, one tap),
            // free-text Reply last — ≤ 3 total by construction (see
            // MAX_OPTION_ANSWER_ACTIONS).
            for (label in model.optionAnswers) {
                builder.addAction(optionAnswerAction(model.permissionId, label))
            }
            builder.addAction(replyAction(model))
        } else {
            for (option in model.options) {
                builder.addAction(behaviorAction(model.permissionId, option))
            }
        }
        manager.notify(model.permissionId, APPROVAL_NOTIFICATION_ID, builder.build())
    }

    /** Idempotent: cancelling an id that was never posted (or already cancelled) is a no-op. */
    override fun cancel(permissionId: String) {
        manager.cancel(permissionId, APPROVAL_NOTIFICATION_ID)
    }

    /**
     * One action per canonical option, answering through the service. Wear
     * forbids BroadcastReceiver notification actions, so every action is a
     * PendingIntent.getService to BridgeSessionService — whose ACTION_ANSWER
     * path calls the same ack-gated ViewModel entry points as the card. No
     * message extra: BridgeViewModel.answerPermission owns the deny message
     * (the canonical PermissionOption carries no message field), so the
     * intent carries exactly what the card's tap does — id + behavior.
     */
    private fun behaviorAction(permissionId: String, option: PermissionOption): NotificationCompat.Action {
        val intent = answerIntent(permissionId, discriminator = option.behavior)
            .putExtra(EXTRA_BEHAVIOR, option.behavior)
        val pending = PendingIntent.getService(
            context,
            approvalActionRequestCode(permissionId, option.behavior),
            intent,
            // IMMUTABLE: a plain action carries nothing the system needs to
            // fill in, and an immutable intent cannot have its extras — the
            // permission DECISION — rewritten by anything else holding it.
            // UPDATE_CURRENT so a re-post of the same prompt always carries
            // current extras rather than resurrecting a stale registration.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, approvalActionTitle(option.behavior), pending).build()
    }

    /**
     * One option label as a one-tap ANSWER button (see
     * [ApprovalNotificationModel.optionAnswers]): the service's answer path
     * reads [EXTRA_ANSWER_TEXT] and calls the same answerQuestions entry
     * point as the free-text reply — one wire shape, two input surfaces.
     * The "answer:" discriminator prefix keeps these PendingIntents distinct
     * from behavior actions AND from each other (the label is part of both
     * the requestCode mix and the data URI, so two options of one prompt
     * can never collapse into answering with the wrong label).
     */
    private fun optionAnswerAction(permissionId: String, label: String): NotificationCompat.Action {
        val discriminator = "answer:$label"
        val intent = answerIntent(permissionId, discriminator = discriminator)
            .putExtra(EXTRA_ANSWER_TEXT, label)
        val pending = PendingIntent.getService(
            context,
            approvalActionRequestCode(permissionId, discriminator),
            intent,
            // IMMUTABLE like the behavior actions: the label rides the
            // intent as-built; nothing needs filling in.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    /**
     * The single-question AskUserQuestion reply: free text via RemoteInput
     * (Wear renders voice/keyboard input straight off the action). MUTABLE is
     * load-bearing: the system must write the RemoteInput results INTO the
     * intent, and on API 31+ an immutable PendingIntent silently strips them
     * — the service would receive a reply with no text and drop it.
     */
    private fun replyAction(model: ApprovalNotificationModel): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_QUESTION_ANSWER)
            .setLabel(model.text) // the question itself prompts the input UI
            // The AGENT's option labels are the choice chips; free text via
            // keyboard/voice coexists with choices on the reply surface.
            .apply { if (model.replyChoices.isNotEmpty()) setChoices(model.replyChoices.toTypedArray()) }
            .build()
        val pending = PendingIntent.getService(
            context,
            approvalActionRequestCode(model.permissionId, REPLY_DISCRIMINATOR),
            answerIntent(model.permissionId, discriminator = REPLY_DISCRIMINATOR),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, "Reply", pending)
            .addRemoteInput(remoteInput)
            // Live-demo lesson (see ApprovalNotificationModel.replyChoices):
            // Wear's ML Smart Replies ("Good question") rendered as if they
            // were the agent's options, and a mis-tap answers a blocked
            // session with Google's guess. Only the agent speaks here.
            .setAllowGeneratedReplies(false)
            .build()
    }

    /**
     * The self-addressed answer intent. The data URI is the second identity
     * key (see [approvalActionRequestCode]): filterEquals compares data but
     * not extras, so the per-(prompt, behavior) URI keeps concurrent prompts'
     * PendingIntents distinct even under a 32-bit requestCode collision.
     */
    private fun answerIntent(permissionId: String, discriminator: String): Intent =
        Intent(context, BridgeSessionService::class.java)
            .setAction(ACTION_ANSWER)
            .setData(Uri.fromParts("claudewatch", "approval/$permissionId/$discriminator", null))
            .putExtra(EXTRA_PERMISSION_ID, permissionId)

    companion object {
        const val CHANNEL_ID = "approvals"

        /**
         * The fixed notification id; the TAG (the permissionId) is what
         * distinguishes prompts. 25 = the issue that demanded actionable
         * approvals, same convention as the FGS notification's 24.
         */
        const val APPROVAL_NOTIFICATION_ID = 25

        const val ACTION_ANSWER = "dev.claudewatch.wear.action.ANSWER"
        const val EXTRA_PERMISSION_ID = "dev.claudewatch.wear.extra.PERMISSION_ID"
        const val EXTRA_BEHAVIOR = "dev.claudewatch.wear.extra.BEHAVIOR"

        /**
         * A pre-composed question answer riding an option-button tap (see
         * [ApprovalNotificationModel.optionAnswers]) — the service answers
         * with this text through the same answerQuestions path as a typed
         * RemoteInput reply.
         */
        const val EXTRA_ANSWER_TEXT = "dev.claudewatch.wear.extra.ANSWER_TEXT"
        const val KEY_QUESTION_ANSWER = "dev.claudewatch.wear.remoteinput.QUESTION_ANSWER"

        /** Namespaces the reply action's requestCode/URI away from every behavior key. */
        private const val REPLY_DISCRIMINATOR = "remote-input-reply"
    }
}

/**
 * Drives an [ApprovalNotificationSink] (in production: [ApprovalNotifier])
 * from a permission-queue flow. Extracted from BridgeSessionService so the
 * instrumented test can attach the REAL post / cancel path to its OWN
 * MockWebServer-paired ViewModel — CatchUpFlowTest's kdoc explains why tests
 * must not pair the production singleton's persistent store to a throwaway
 * server — while the service attaches the same class to the singleton: one
 * code path, two hosts. The sink interface plus the [uiVisible] lambda are
 * the JVM seams: ApprovalNotificationCollectorTest tables the gating /
 * swallow / bookkeeping branches below against a recording fake, because
 * every instrumented scenario runs backgrounded and would never catch a
 * broken visible-UI branch.
 *
 * Ownership of state: [knownIds] is every id currently accounted for (posted
 * OR deliberately swallowed), [postedIds] only what actually reached the
 * shade — kept separately so [cancelAllPosted] can cancel PRECISELY what
 * this collector posted (a dead service's actions are dead ends, the same
 * zombie-notification reasoning as #24's onDestroy cancel) without touching
 * notifications it never owned.
 */
class ApprovalNotificationCollector(
    private val sink: ApprovalNotificationSink,
    private val uiVisible: () -> Boolean = { AppVisibility.uiVisible },
) {

    private var knownIds: Set<String> = emptySet()
    private val postedIds = mutableSetOf<String>()

    /**
     * Collect [state]'s permission queue in [scope]. The job lives and dies
     * with the host's scope — a dead service means a dead stream, so no new
     * prompts can arrive anyway; the host cancels the leftovers explicitly
     * via [cancelAllPosted].
     */
    fun attach(scope: CoroutineScope, state: StateFlow<BridgeViewModel.UiState>): Job =
        scope.launch {
            state.map { it.permissionQueue }.distinctUntilChanged().collect { onQueue(it) }
        }

    /** One queue emission: cancel departures, post arrivals (unless the UI owns them). */
    fun onQueue(queue: List<BridgeViewModel.PendingPermission>) {
        val diff = diffPermissionNotifications(knownIds, queue)
        // Departures ALWAYS cancel, visible or not — idempotent when the id
        // was never posted (the swallowed-while-visible case below).
        for (id in diff.toCancelIds) {
            sink.cancel(id)
            postedIds -= id
        }
        for (prompt in diff.toPost) {
            if (!uiVisible()) {
                sink.post(approvalNotificationModel(prompt))
                postedIds += prompt.permissionId
            }
            // While the UI is visible the in-app card is the surface: the
            // prompt is deliberately NOT posted — and because knownIds still
            // records it, it is never posted LATER either (backgrounding the
            // app does not retroactively buzz for a card the user already
            // has open and chose not to answer). Ids are marked known either
            // way, which is what makes the swallow permanent.
        }
        knownIds = queue.mapTo(mutableSetOf()) { it.permissionId }
    }

    /**
     * Cancel exactly the notifications THIS collector posted — the host is
     * going away and every action PendingIntent would restart it just to
     * answer into whatever state it wakes up with; better no notification
     * than one whose buttons outlived their truth. knownIds is deliberately
     * kept: this collector is done, its replacement starts fresh and
     * re-posts still-pending prompts (the recovery path after a sticky
     * service restart).
     */
    fun cancelAllPosted() {
        for (id in postedIds.toList()) sink.cancel(id)
        postedIds.clear()
    }
}
