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

/** `permission-cleared` — dismiss a pending prompt (hook aborted, Codex resolved it, ...). */
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
        "permission-request" -> json.decodeFromString<PermissionRequestEvent>(data)
        "permission-cleared" -> json.decodeFromString<PermissionClearedEvent>(data)
        "stop" -> json.decodeFromString<StopEvent>(data)
        "task-complete" -> json.decodeFromString<TaskCompleteEvent>(data)
        "notification" -> json.decodeFromString<NotificationEvent>(data)
        "error" -> json.decodeFromString<ErrorEvent>(data)
        else -> UnknownEvent(type, data)
    }

    fun parse(frame: SseFrame): BridgeEvent = parse(frame.type, frame.data)
}
