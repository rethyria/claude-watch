// Typed wire models for the bridge's /v1 SSE event contract.
//
// Parsing policy (the drift lesson from the three hand-maintained Swift
// parsers): TOLERANT to unknown fields — hook bodies are forwarded verbatim
// by the bridge and carry whatever Claude Code adds next (`hook_event_name`,
// `transcript_path`, ...) — but STRICT on the contract itself. A missing
// `permissionId`, a `session` event without a `sessionId`, or a permission
// option without a machine-readable `behavior` fails loudly
// (IllegalArgumentException) instead of degrading into wrong UI.
package dev.claudewatch.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One raw SSE frame as delivered by the bridge: the `id:` line (used for
 * `Last-Event-ID` replay), the `event:` line, and the `data:` payload.
 * Mirrors the bridge's ring-buffer entry shape (`transport-sse.js`).
 */
data class SseFrame(val id: String?, val type: String, val data: String)

/** A parsed /v1 SSE event. */
sealed interface BridgeEvent {
    /** Bridge session-slot id this event is attributed to, when any. */
    val sessionId: String?
}

/** `state` values a `session` event may carry. Anything else is a contract violation. */
@Serializable
enum class SessionRunState {
    /** Bridge-level "a client connected" signal; carries no sessionId. */
    @SerialName("connected")
    CONNECTED,

    @SerialName("running")
    RUNNING,

    @SerialName("ended")
    ENDED;

    val wire: String get() = name.lowercase()
}

/**
 * Additive optional `agents` object on `session` payloads (issue #55): the
 * bridge's observed workflow activity for the slot — [running] subagents in
 * flight, [done] completed. Completion is signaled EXPLICITLY by re-announcing
 * with `{running: 0, done: N}` (present-but-zero), never by omitting the
 * field: absent means "preserve what you knew" under the same merge doctrine
 * as `title`, so omission cannot clear. Clients show an indicator only while
 * [running] > 0.
 */
@Serializable
data class AgentsActivity(
    val running: Int = 0,
    val done: Int = 0,
)

/**
 * `session` — session lifecycle. `running` and `ended` MUST be attributed to
 * a session; the end-of-life extras (`exitCode`/`signal`/`killed`/`reason`/
 * `error`) vary by how the session ended and are all optional.
 *
 * [title] is the additive optional session title the bridge derives from the
 * Claude Code transcript (last `ai-title` record, falling back to the first
 * user prompt — see PROTOCOL.md); absent until derivable, so clients keep
 * their own fallback label.
 */
@Serializable
data class SessionEvent(
    val state: SessionRunState,
    override val sessionId: String? = null,
    val agent: String? = null,
    val cwd: String? = null,
    val folderName: String? = null,
    val title: String? = null,
    /**
     * Additive optional flag: `true` for a HOOK-CREATED (external, PTY-less)
     * session whose process the bridge does not own; OMITTED (null) for
     * bridge-owned PTY slots, which older clients already tolerate and which
     * clients MUST treat as external=false (killable). Carried uniformly on
     * every session event of a hook-created slot (see PROTOCOL.md).
     */
    val external: Boolean? = null,
    /**
     * Additive session-type discriminator (issue #78): the session KIND —
     * currently "acp" for a session hosted by the Zed ACP adapter. OMITTED
     * (null) for bridge-owned PTY slots and hook-created slots (older clients
     * tolerate absence). Preserve-on-absence, exactly like [external].
     */
    val kind: String? = null,
    /**
     * Additive optional flag (issue #78): `true` when the bridge can deliver a
     * dictated prompt into this session LIVE — a bridge-owned PTY (stdin) or an
     * ACP session (inject over the loopback channel). OMITTED (null) for a
     * session dictation cannot reach without a detached fork. The Dictate
     * affordance gates on THIS, NOT on `external` — an ACP session is both
     * external AND dictatable. Preserve-on-absence, exactly like [external].
     */
    val dictatable: Boolean? = null,
    /**
     * Additive optional turn-end flag (issue #60). PRESENT (=true) means the
     * bridge's LAST lifecycle signal for this session was a turn END — a
     * `Stop` or `TaskCompleted` hook. OMITTED (null) means the bridge either
     * considers the session to be producing work, or predates the field.
     *
     * CONSUMED IN ONE DIRECTION ONLY. Every other additive field here follows
     * preserve-on-absence (absent = "keep what you knew"); this one is a
     * DYNAMIC state that flips both ways, so it must never be merged by that
     * rule. Instead: a PRESENT `true` may idle the session — at first sight,
     * and as a latch on a re-send for a session already known — while its
     * ABSENCE changes nothing, ever, and never wakes a session up.
     *
     * The asymmetry is deliberate. The bridge re-sends `running` for every live
     * slot on EVERY connect; letting that WAKE a session would restart its
     * elapsed clock on each routine reconnect (and absence also means "an
     * older bridge", which knows nothing at all). Letting it IDLE one can only
     * freeze a span, which is exactly the transition a missed `stop` would have
     * made. Live `stop`/`task-complete`/output events remain the authority for
     * every other transition.
     *
     * Its reason to exist is the turn that ended while the client was not
     * listening — before it ever connected, or during one of the SSE drops the
     * watch lives with. Either way that `stop` has aged out of the replay ring,
     * and a `session` event is the only place the truth can still arrive.
     */
    val idle: Boolean? = null,
    /**
     * Additive git metadata (issue #54): the branch of the session's project
     * root ("main", "issue-53-fix"; detached HEAD → the 7-char short sha).
     * Absent when the root is not a git checkout or HEAD is unreadable —
     * absent preserves what the client knew, same merge doctrine as [title].
     */
    val branch: String? = null,
    /**
     * PRESENT (=true) ONLY when the session's root is a LINKED git worktree
     * (its .git is a file, not a directory); omitted otherwise (issue #54).
     */
    val worktree: Boolean? = null,
    /**
     * The MAIN repo root path; PRESENT ONLY for worktrees, where it differs
     * from the session's own root. Clients group the session under
     * basename(repoRoot) when present (issue #54).
     */
    val repoRoot: String? = null,
    /** Workflow activity (issue #55) — see [AgentsActivity]; absent preserves. */
    val agents: AgentsActivity? = null,
    /**
     * Additive session-meta trio (issue #97, Halo v2): the pager subheading's
     * `model · mode · use%`. Only ACP (Zed-hosted) sessions ever carry them —
     * the hook and PTY paths have no equivalent signal — and a slot that has
     * them carries them on EVERY session event. Absence preserves what the
     * client knew, exactly like [title].
     *
     * [model] is the HUMAN DISPLAY name, `default`-alias already resolved
     * adapter-side (third-party backends can yield a raw id; the bridge
     * passes it verbatim rather than guessing). [mode] is the ACP
     * permission-mode id VERBATIM (`default`/`plan`/`acceptEdits`/... — the
     * vocabulary is the agent's and may grow, so no enum here). [contextPct]
     * is integer percent 0–100 of the context window USED, re-announced only
     * when the integer changes; 0 is a REAL value (a fresh session), so
     * consumers key on presence, never truthiness.
     */
    val model: String? = null,
    val mode: String? = null,
    val contextPct: Int? = null,
    val reason: String? = null,
    val exitCode: Int? = null,
    val signal: String? = null,
    val killed: Boolean? = null,
    val error: String? = null,
) : BridgeEvent {
    init {
        require(state == SessionRunState.CONNECTED || !sessionId.isNullOrEmpty()) {
            "session event with state=${state.wire} must carry a sessionId"
        }
    }
}

/** `pty-output` — raw terminal bytes from a bridge-spawned PTY. */
@Serializable
data class PtyOutputEvent(
    val text: String,
    override val sessionId: String? = null,
) : BridgeEvent

/**
 * `message` — assistant prose from an ACP (Zed-hosted) session (#79). The one
 * capability the hook channel never had: hooks carried tool activity and
 * lifecycle, never what the agent actually said.
 *
 * Assistant-only by construction — the adapter emits no `user_message_chunk` —
 * so the local echo stays the single authority for the user's own dictated
 * text and there is nothing to de-duplicate. `role` is defaulted (tolerant:
 * a future `user` role must not break an older build) but `text` is required:
 * it is the entire point of the event, and a frame without it would render as
 * an empty bubble on the wrist instead of failing loudly.
 */
@Serializable
data class MessageEvent(
    val text: String,
    val role: String = "assistant",
    override val sessionId: String? = null,
) : BridgeEvent

/**
 * `tool-output` — a PostToolUse hook body (or Codex tool call) forwarded
 * verbatim with `source` and `sessionId` injected by the bridge. Everything
 * except the attribution is hook-defined, so all fields are optional and
 * `tool_output` may be any JSON value (string for Claude hooks, null or
 * structured for Codex).
 */
@Serializable
data class ToolOutputEvent(
    override val sessionId: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_input") val toolInput: JsonObject? = null,
    @SerialName("tool_output") val toolOutput: JsonElement? = null,
    val cwd: String? = null,
    val source: String? = null,
) : BridgeEvent {
    /** `tool_output` rendered as display text (JSON re-stringified when structured). */
    val toolOutputText: String?
        get() = when (val out = toolOutput) {
            null, is JsonNull -> null
            is JsonPrimitive -> out.contentOrNull
            else -> out.toString()
        }
}

/**
 * One canonical permission option: the bridge normalizes every option through
 * `canonicalPermissionOptions()` so clients act on the machine-readable
 * [behavior], never on option position or label wording. An unknown behavior
 * is a contract violation, exactly as it is server-side.
 */
@Serializable
data class PermissionOption(
    val behavior: String,
    val label: String = "",
    val description: String? = null,
) {
    init {
        require(behavior in BEHAVIORS) {
            "Permission option without machine-readable behavior: $behavior"
        }
    }

    companion object {
        /** Mirrors PERMISSION_BEHAVIORS in skill/bridge/permissions.js. */
        val BEHAVIORS: Set<String> = setOf("allow", "allow-always", "deny")
    }
}

/**
 * One of the AGENT's own permission options, forwarded verbatim on a rich ACP
 * prompt (issue #110): [optionId] is the literal decision sent back, [kind]
 * is the ACP option kind that drives styling and the derived [behavior].
 * Bridge-normalized contract content, so strict like [PermissionOption]: the
 * bridge promises a known kind and non-blank id/label (an option that IS the
 * answer cannot be anonymous), and anything else is a contract violation.
 */
@Serializable
data class AgentPermissionOption(
    val optionId: String,
    val label: String,
    val kind: String,
) {
    init {
        require(optionId.isNotEmpty()) { "agent option without an optionId" }
        require(label.isNotEmpty()) { "agent option without a label" }
        require(kind in KINDS) { "agent option with unknown kind: $kind" }
    }

    /**
     * The canonical behavior this option's kind maps to — the same mapping as
     * the bridge's `behaviorForAcpOption()`, so the decision POST's `behavior`
     * (and the card's ✓/✕ flash) agree with what the bridge would say.
     */
    val behavior: String
        get() = when (kind) {
            "allow_once" -> "allow"
            "allow_always" -> "allow-always"
            else -> "deny"
        }

    companion object {
        /** Mirrors the kinds behaviorForAcpOption() maps in skill/bridge/acp.js. */
        val KINDS: Set<String> = setOf("allow_once", "allow_always", "reject_once", "reject_always")
    }
}

/**
 * One choice offered by an [AskUserQuestion]; [label] is both what the watch
 * renders and the literal answer string sent back for the question.
 */
data class AskUserQuestionOption(
    val label: String,
    val description: String? = null,
)

/**
 * One question of an AskUserQuestion prompt, extracted from
 * `tool_input.questions`. [question] is the answer key: the bridge's
 * `collectAskUserQuestionAnswers()` maps answers back to the blocked hook by
 * question text.
 */
data class AskUserQuestion(
    val question: String,
    val header: String? = null,
    val options: List<AskUserQuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
)

/**
 * `permission-request` — a blocking hook is waiting on a decision.
 * [permissionId] is the correlation key for the `POST /v1/command` answer and
 * is mandatory. [options] is the canonical top-level list; AskUserQuestion
 * prompts carry none (their per-question lists live in `tool_input.questions`,
 * forwarded verbatim in [toolInput] and surfaced typed via [questions]).
 * [agentOptions] is the agent's OWN option list (issue #110), present exactly
 * when the canonical flattening was lossy — a rich ACP prompt whose ambiguous
 * behaviors lost their canonical buttons; absence means today's canonical
 * card, presence means render these verbatim and answer with the optionId.
 */
@Serializable
data class PermissionRequestEvent(
    val permissionId: String,
    override val sessionId: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_input") val toolInput: JsonObject? = null,
    val cwd: String? = null,
    val source: String? = null,
    val options: List<PermissionOption> = emptyList(),
    val agentOptions: List<AgentPermissionOption> = emptyList(),
) : BridgeEvent {
    init {
        require(permissionId.isNotEmpty()) { "permission-request must carry a permissionId" }
    }

    /**
     * The AskUserQuestion questionnaire, typed. Unlike the event contract
     * itself this is HOOK CONTENT (`tool_input` is forwarded verbatim), so
     * per the tolerant/strict split it parses LENIENTLY: an entry that is not
     * an object or lacks a non-blank `question` (the answer key — see
     * `collectAskUserQuestionAnswers()` bridge-side) is skipped, as is any
     * option without a non-blank `label`; nothing here can fail the frame.
     * Empty for every other tool.
     */
    val questions: List<AskUserQuestion>
        get() {
            if (toolName != "AskUserQuestion") return emptyList()
            val raw = toolInput?.get("questions") as? JsonArray ?: return emptyList()
            return raw.mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val text = obj.stringOrNull("question")?.takeUnless { it.isBlank() }
                    ?: return@mapNotNull null
                AskUserQuestion(
                    question = text,
                    header = obj.stringOrNull("header"),
                    options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { opt ->
                        val option = opt as? JsonObject ?: return@mapNotNull null
                        val label = option.stringOrNull("label")?.takeUnless { it.isBlank() }
                            ?: return@mapNotNull null
                        AskUserQuestionOption(label, option.stringOrNull("description"))
                    },
                    multiSelect = (obj["multiSelect"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            }
        }
}

// NOT a companion member: kotlinx.serialization generates PermissionRequest-
// Event's `serializer()` accessor onto its companion object, and declaring a
// private companion of our own would make that accessor inaccessible
// (IllegalAccessError at parse time).
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * `permission-cleared` — dismiss a pending prompt. [reason] is an OPEN set
 * (`hook-aborted`, `answered-elsewhere`, `expired`, Codex clears, ...): an
 * unrecognised value must never fail the frame, because the drop is the
 * contract and the wording is only a courtesy.
 */
@Serializable
data class PermissionClearedEvent(
    val permissionId: String,
    val reason: String? = null,
    override val sessionId: String? = null,
) : BridgeEvent {
    init {
        require(permissionId.isNotEmpty()) { "permission-cleared must carry a permissionId" }
    }
}

/**
 * `permission-sync` — the bridge's AUTHORITATIVE set of live prompt ids, sent
 * on every connect. RETRACTION ONLY: drop every pending prompt absent from it,
 * never create one (payloads arrive as `permission-request` immediately
 * after). Exists because the connect-time re-send is additive and could not
 * retract a prompt that died while this client was offline — its
 * `permission-cleared` having long since been evicted from the bridge's ring
 * buffer — which left the card on the wrist until the app was force-stopped
 * (issue #63).
 *
 * [permissionIds] is deliberately NOT defaulted: an EMPTY list is a legal and
 * meaningful instruction ("retract everything"), so a missing field must
 * loud-fail as a contract violation rather than be silently mistaken for it.
 */
@Serializable
data class PermissionSyncEvent(
    val permissionIds: List<String>,
    override val sessionId: String? = null,
) : BridgeEvent

/**
 * One session in a [SessionSyncEvent]'s authoritative set: the slot [id] the
 * bridge still has, plus its turn-level truth as a TRI-STATE (issue #60).
 *
 * [idle] here is NOT [SessionEvent.idle]'s one-way latch. That flag is
 * present-only-when-true, so on a `session` payload "working" and "the bridge
 * has no idea" are the same absence — which is why a client meeting a session
 * for the first time had to guess, and guessed green for a session that had
 * been idle for hours. A sync DESCRIBES CURRENT STATE, so it says all three
 * out loud instead of leaning on a merge rule:
 *
 *  - `true`  — the bridge's last lifecycle signal was a turn END.
 *  - `false` — a turn is in flight; the session really is working.
 *  - absent  — the bridge has observed no turn signal at all for this slot
 *    (a session registered but never yet run, an older bridge). It is NOT a
 *    claim of work, and a client must not render one.
 */
@Serializable
data class SessionSyncEntry(
    val id: String,
    val idle: Boolean? = null,
) {
    init {
        require(id.isNotEmpty()) { "session-sync entry must carry an id" }
    }
}

/**
 * `session-sync` — the bridge's AUTHORITATIVE set of running sessions, sent at
 * the END of every connect-time snapshot (issue #66). The per-slot `session`
 * re-sends that precede it are ADDITIVE: they can create or refresh a session
 * but can never say "drop everything I did not mention", so a session the
 * bridge FORGOT — a restart, a crash, a cap eviction that happened while this
 * client was offline — was orphaned on the wrist forever, green and labelled
 * running, until the app was force-stopped. This frame is the whole truth.
 *
 * PRUNING ONLY, and only when [complete]. It never creates a session (payloads
 * arrive as `session` events just before it), and a frame that cannot claim to
 * describe the FULL set must not be allowed to drop anything — a partial or
 * interrupted sync is exactly the state in which dropping is most wrong. An
 * interrupted sync is additionally harmless by construction: the frame is
 * emitted last, so a client whose connection died mid-snapshot never receives
 * it at all.
 *
 * [sessions] is deliberately NOT defaulted, for the same reason as
 * [PermissionSyncEvent.permissionIds]: an EMPTY list is legal and meaningful
 * ("the bridge has nothing running"), so a missing field must loud-fail as a
 * contract violation rather than be mistaken for it and prune the world.
 * [complete] defaults to FALSE — an unrecognised or older framing gets the
 * safe reading, never the destructive one.
 */
@Serializable
data class SessionSyncEvent(
    val sessions: List<SessionSyncEntry>,
    val complete: Boolean = false,
    override val sessionId: String? = null,
) : BridgeEvent

/** `stop` — the Stop hook fired: the agent finished a turn (NOT the session's end). */
@Serializable
data class StopEvent(
    override val sessionId: String? = null,
) : BridgeEvent

/** `task-complete` — a task finished (Claude TaskCompleted hook or Codex turn end). */
@Serializable
data class TaskCompleteEvent(
    override val sessionId: String? = null,
    val source: String? = null,
) : BridgeEvent

/** `notification` — Notification hook body; `notification_type` is null when the hook omitted it. */
@Serializable
data class NotificationEvent(
    @SerialName("notification_type") val notificationType: String? = null,
    val message: String? = null,
    override val sessionId: String? = null,
) : BridgeEvent

/** `error` — bridge-side failure surfaced to clients (spawn failure, error hook, ...). */
@Serializable
data class ErrorEvent(
    val error: String? = null,
    override val sessionId: String? = null,
) : BridgeEvent

/**
 * An event type this client version doesn't know. Tolerated (never an error):
 * a newer bridge must be able to add event types without breaking replay for
 * older watches.
 */
data class UnknownEvent(
    val type: String,
    val data: String,
    override val sessionId: String? = null,
) : BridgeEvent

object BridgeEventParser {

    // ignoreUnknownKeys is the "tolerant" half of the policy; the "strict"
    // half is the non-defaulted fields and init-block requires above.
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse one SSE frame's payload by its `event:` type.
     *
     * @throws IllegalArgumentException on any contract violation (malformed
     *   JSON, missing required field, unknown enum value, behavior-less
     *   permission option). kotlinx's SerializationException IS-A
     *   IllegalArgumentException, so one catch covers the lot.
     */
    fun parse(type: String, data: String): BridgeEvent = when (type) {
        "session" -> json.decodeFromString<SessionEvent>(data)
        "pty-output" -> json.decodeFromString<PtyOutputEvent>(data)
        "tool-output" -> json.decodeFromString<ToolOutputEvent>(data)
        "message" -> json.decodeFromString<MessageEvent>(data)
        "permission-request" -> json.decodeFromString<PermissionRequestEvent>(data)
        "permission-cleared" -> json.decodeFromString<PermissionClearedEvent>(data)
        "permission-sync" -> json.decodeFromString<PermissionSyncEvent>(data)
        "session-sync" -> json.decodeFromString<SessionSyncEvent>(data)
        "stop" -> json.decodeFromString<StopEvent>(data)
        "task-complete" -> json.decodeFromString<TaskCompleteEvent>(data)
        "notification" -> json.decodeFromString<NotificationEvent>(data)
        "error" -> json.decodeFromString<ErrorEvent>(data)
        else -> UnknownEvent(type, data)
    }

    fun parse(frame: SseFrame): BridgeEvent = parse(frame.type, frame.data)
}
