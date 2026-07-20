package dev.claudewatch.wear

import android.content.Context
import dev.claudewatch.shared.protocol.AskUserQuestion
import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.shared.protocol.PermissionRequestEvent
import dev.claudewatch.shared.protocol.SessionEvent
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.state.BridgeEventReducer
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.terminal.ToolOutputFormatter
import dev.claudewatch.wear.data.CredentialStore
import dev.claudewatch.wear.net.BackoffPolicy
import dev.claudewatch.wear.net.BridgeClient
import dev.claudewatch.wear.net.ConnectionEngine
import dev.claudewatch.wear.net.ConnectionState
import dev.claudewatch.wear.net.NetworkEscalator
import dev.claudewatch.wear.net.WifiNetworkEscalator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * State for the watch UI: pair with the bridge, stream SSE events, send
 * session-scoped commands, answer permission and question prompts.
 *
 * All connection lifecycle behavior lives in [ConnectionEngine] — this class
 * only renders its state and feeds it user intents. All event handling goes
 * through :shared's pure [BridgeEventReducer]: this class only does I/O and
 * mirrors the reduced [BridgeState] into [UiState].
 *
 * PROCESS-lifetime, not activity-lifetime (issue #24): production obtains
 * this class only through [singleton], never a ViewModelProvider — the old
 * androidx ViewModel base died with its activity's ViewModelStore
 * (onCleared → engine.shutdown()), which killed the SSE stream on every
 * activity destroy. The engine's owner is now the process; the foreground
 * [BridgeSessionService] is what keeps that process (and the socket) alive
 * with the screen off, and [shutdown] survives only for tests that construct
 * their own instances.
 */
class BridgeViewModel(
    store: CredentialStore,
    clientFactory: (String, Int) -> BridgeClient = { hostIp, port -> BridgeClient(hostIp, port) },
    backoff: BackoffPolicy = BackoffPolicy(),
    escalator: NetworkEscalator = NetworkEscalator.NOOP,
) {

    /**
     * One queued approval card, fully resolved for rendering: WHAT is being
     * asked ([toolName] + [requestSummary]) and WHICH session is asking
     * ([sessionLabel]) — the live-testing feedback on the single-slot card
     * that only said "Bash". [options] is the bridge's canonical
     * behavior-keyed list (never inferred from labels or position).
     * [questions] is non-empty exactly for AskUserQuestion prompts (which
     * carry no canonical options): EVERY question of the payload, each with
     * its own option list, rendered by the question card and answered
     * per-question via [answerQuestions].
     */
    data class PendingPermission(
        val permissionId: String,
        val sessionId: String?,
        val toolName: String,
        val requestSummary: String,
        val sessionLabel: String,
        val options: List<PermissionOption>,
        val questions: List<AskUserQuestion> = emptyList(),
    )

    /**
     * One plan-usage window as GET /v1/usage reports it (issue #57).
     * Render-what-you-get: the bridge preserves whatever windows the upstream
     * returns, and the client renders one bar per entry — [kind] is matched
     * only for display niceties (reset-time formatting), never as a filter,
     * so an unknown kind still gets its bar. [percent] is USED percent
     * exactly as upstream reports; the screen renders REMAINING (100 − it).
     * [resetsAt] is the wire's ISO8601 string, parsed at render time (a
     * malformed one degrades to no reset line, not a dropped bar).
     * [severity] is the SERVER's own color coding, upstream-verbatim (the
     * bridge omits the key when the upstream sent none, so absent ⇒ null):
     * its thresholds are undocumented, so when present and non-"normal" it is
     * the authoritative tier — the screen's local thresholds are only a
     * fallback (see `usageTier`).
     */
    data class UsageLimit(
        val kind: String,
        val label: String,
        val percent: Double,
        val resetsAt: String?,
        val severity: String? = null,
    )

    /**
     * The usage page's state (issue #57). Fetch-on-open: [fetchUsage] flips
     * to [Loading] on a page entry from Idle/Error — but a fetch started
     * while [Data] is already on screen is a SILENT refresh (the bars stay
     * put and swap when the result lands; see [fetchUsage]). No client
     * caching — [Data.source] == "cache" is the BRIDGE's fallback (its own
     * OAuth call failed). [Data.fetchedAtMs] is WHEN THESE NUMBERS WERE
     * CURRENT — a client-model guarantee, always non-null: a cache result
     * keeps the bridge's value (the data's true age), a live api result is
     * stamped System.currentTimeMillis() at parse time. (The WIRE still only
     * sends fetchedAtMs for cache fallbacks — the stamp is ours.) The screen
     * renders it as the tappable "as of 5 minutes ago" freshness label once
     * the data is over a minute old.
     */
    sealed interface UsageUi {
        /** Never fetched (the page has not been opened this session). */
        data object Idle : UsageUi
        data object Loading : UsageUi
        data class Data(
            val limits: List<UsageLimit>,
            val source: String,
            val fetchedAtMs: Long,
        ) : UsageUi
        data class Error(val message: String) : UsageUi
    }

    data class UiState(
        val status: String = "unpaired",
        val paired: Boolean = false,
        /** Non-null when the user must pair again; rendered as an explanation. */
        val repairExplanation: String? = null,
        val sessionId: String? = null,
        /**
         * Approval queue, newest-first. The FRONT is the rendered card. Newest
         * first because the bridge pushes no permission-cleared for a prompt
         * resolved from ANOTHER paired device (its own device drops it locally
         * on the 2xx): a stale entry must never shadow a live prompt that
         * arrived after it (answering the stale one 404s, which drops it and
         * reveals the next). Server-side expiry now DOES broadcast
         * permission-cleared (issue #63), so that path self-heals — but the
         * ack-to-advance machinery is still the only cover for the
         * other-device case.
         */
        val permissionQueue: List<PendingPermission> = emptyList(),
        /** permissionId of the answer POST currently in flight, if any. */
        val decisionInFlightId: String? = null,
        /**
         * permissionId of the prompt [decisionResult] belongs to. decisionResult
         * is connection-global and STICKY ("decision:200" survives from any
         * earlier prompt), so a card must never read it alone: a prompt that
         * resolves without this watch's POST (hook-abort push, answered
         * elsewhere) would otherwise flash a stale success as its own ack.
         */
        val decisionForId: String? = null,
        /** Why the last answer failed; the prompt it belongs to is still queued. */
        val decisionError: String? = null,
        /**
         * Consecutive answer attempts that failed retryably (transport error
         * or a 5xx) since the last ack. Resets on any authoritative outcome.
         * The sheet uses it to unlock the local-dismiss escape hatch: without
         * one, a bridge that stopped answering (host gone, network changed)
         * would wedge the whole app behind an unanswerable sheet forever.
         */
        val decisionFailureCount: Int = 0,
        /**
         * The command box's text, owned HERE (not by the composable) because
         * the ack-gated send owns its lifecycle: cleared when a send goes in
         * flight, RESTORED when the send fails so the exact text can be
         * retried — never silently lost (the watchOS trap: dictated text
         * swallowed by any transport error/401/404/500 while the UI claimed
         * it was sent).
         */
        val commandDraft: String = "",
        /**
         * Text of the send currently awaiting the bridge's ack. Rendered as a
         * pending indicator; the terminal echo happens only on the 2xx ack.
         */
        val commandInFlightText: String? = null,
        /** Why the last send failed/was refused; the text is back in [commandDraft]. */
        val commandError: String? = null,
        val commandResult: String? = null,
        val decisionResult: String? = null,
        /** Last spawn/kill outcome, e.g. "spawn:200" / "kill:200". */
        val sessionActionResult: String? = null,
        val eventLog: List<String> = emptyList(),
        val bridge: BridgeState = BridgeState(),
        /**
         * Session ids the user honest-hid (issue #53): EXTERNAL, hook-created
         * sessions the bridge cannot kill, dropped from the derived Halo model
         * LOCALLY (no network). Cleared per id when any applied event for that
         * session arrives ("until it speaks again" — see [handleEvent]).
         */
        val hiddenSessions: Set<String> = emptySet(),
        /** The usage page's fetch-on-open state (issue #57); see [UsageUi]. */
        val usage: UsageUi = UsageUi.Idle,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /**
     * The haptic grammar spoken on command outcomes. Defaults to the no-op so
     * plain-JVM unit tests can construct the ViewModel; [MainActivity] swaps
     * in [VibratorHaptics], tests may swap in a recorder. Volatile: outcomes
     * fire from the IO dispatcher.
     */
    @Volatile
    var haptics: Haptics = Haptics.None

    // Owns the engine's lifetime; viewModelScope was avoided even before the
    // ViewModel base class went (it requires Dispatchers.Main, which plain
    // JVM unit tests don't have) — which is also why process scoping was a
    // natural move: nothing here ever depended on an activity's lifecycle.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = ConnectionEngine(store, engineScope, clientFactory, backoff, escalator = escalator)

    /**
     * The engine's raw connection state, exposed for [BridgeSessionService]:
     * the service derives its notification/chip text from it and stops itself
     * on the terminal states (Stopped / AuthExpired / BridgeMismatch — nothing
     * left to keep alive). The UI keeps rendering the flattened [UiState]
     * instead; this VM stays the single owner of the engine either way.
     */
    val connection: StateFlow<ConnectionState> = engine.state

    init {
        engineScope.launch {
            engine.state.collect { connection ->
                _state.update {
                    it.copy(
                        status = statusText(connection),
                        paired = connection.isPairedState(),
                        repairExplanation = repairExplanation(connection),
                    )
                }
            }
        }
        engineScope.launch {
            engine.events.collect { handleEvent(it.id, it.type, it.data) }
        }
        engine.start()
    }

    fun pair(host: String, portText: String, code: String) {
        engineScope.launch {
            val port = portText.trim().toIntOrNull()
            if (port == null) {
                _state.update { it.copy(status = "pair failed: invalid port: $portText") }
                return@launch
            }
            val body = engine.pair(host, port, code.trim(), deviceName = "wear-client") ?: return@launch
            // Seed the session id from the pair snapshot when one is running.
            var sessionId: String? = null
            val sessions = body.optJSONArray("sessions")
            if (sessions != null) {
                for (i in 0 until sessions.length()) {
                    val session = sessions.optJSONObject(i) ?: continue
                    if (session.optString("state") == "running") {
                        sessionId = session.optString("id").takeUnless { it.isEmpty() }
                    }
                }
            }
            if (sessionId != null) {
                _state.update { it.copy(sessionId = sessionId) }
            }
        }
    }

    /**
     * User unpair: engine stops, credentials are wiped, nothing retries. The
     * screen model resets to the unpaired default (status/paired follow from
     * the engine's Stopped state through the collector).
     */
    fun unpair() {
        engine.stop()
        _state.update { UiState(status = it.status, paired = false) }
    }

    /**
     * Issue #24: the foreground-service notification's Disconnect action —
     * stop the stream WITHOUT the credential wipe, unlike [unpair]. The next
     * [resume] (any activity ON_START, or a sticky service restart)
     * reconnects from the persisted credentials and replays from the
     * persisted ack cursor. The screen model is deliberately NOT reset: the
     * cursor only ever skips frames the reducer already APPLIED into this
     * state, so retained state + the reconnect replay is exactly the
     * sessions' truth — wiping it would just re-earn it from a full replay.
     */
    fun disconnect() {
        engine.disconnect()
    }

    /**
     * Reconnect a disconnected engine (issue #24's catch-up-on-open).
     * [ConnectionEngine.start] is already guarded: it no-ops unless the
     * engine is actually stopped AND surfaced as Stopped, and it stays
     * Stopped without persisted credentials — so this is safe to fire on
     * EVERY activity ON_START and every service (re)start command. Reopening
     * the app after a notification Disconnect or a process death therefore
     * resumes the stream from the persisted Last-Event-ID instead of
     * silently staying dead.
     */
    fun resume() {
        engine.start()
    }

    /** Mirror the command box into [UiState.commandDraft] (see its doc for why the VM owns it). */
    fun updateCommandDraft(text: String) {
        _state.update { it.copy(commandDraft = text) }
    }

    /**
     * A recognizer result (RecognizerIntent.ACTION_RECOGNIZE_SPEECH) landed:
     * put the transcription in the draft — so a refused/failed send leaves it
     * visible and retryable instead of vanishing — and send it through the
     * exact same ack-gated path as typed text. [toSession] pins the target to
     * the session the user dictated FROM (see [sendCommand]).
     *
     * The draft is filled only when EMPTY: an occupied draft holds text a
     * previous failed send restored (or the user typed), and overwriting it
     * would silently destroy that text — the exact loss class issue #20
     * exists to prevent. If this send then fails, [sendFailed]'s conditional
     * restore keeps the transcription visible inside the surfaced error.
     */
    fun dictationResult(text: String, toSession: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _state.update {
            if (it.commandDraft.isEmpty()) it.copy(commandDraft = trimmed) else it
        }
        sendCommand(trimmed, toSession)
    }

    /**
     * A new recognizer launch is starting: clear a stale [UiState.commandError]
     * left by an earlier refusal/failure so the voice overlay (which opens on
     * an error while armed) can't resurface an old failure as this
     * dictation's outcome.
     */
    fun dictationStarted() {
        _state.update { it.copy(commandError = null) }
    }

    /** The watch has no speech recognizer activity: surface it, send nothing. */
    fun dictationUnavailable() {
        haptics.commandFailed()
        _state.update { it.copy(commandError = "No speech recognizer on this watch — type the command") }
    }

    /**
     * The user explicitly discarded a failed send from the voice overlay:
     * drop the restored draft AND its surfaced error together. This is the
     * deliberate-loss exit — the only path allowed to destroy the text.
     */
    fun discardCommand() {
        _state.update { it.copy(commandDraft = "", commandError = null) }
    }

    /**
     * Ack-gated command send (issue #20). The confirmed watchOS trap was the
     * inverse: echo "> command" + thinking cursor BEFORE the network call and
     * swallow every failure — the transcription silently lost while the UI
     * claimed it was sent. Here:
     *  - the text leaves the draft and shows as PENDING
     *    ([UiState.commandInFlightText]) until the bridge answers;
     *  - the terminal echo + thinking cursor happen only on a 2xx ack (in the
     *    same atomic state update that clears the pending marker), with the
     *    ack tick of the haptic grammar;
     *  - any failure (transport, timeout, non-2xx) echoes NOTHING, surfaces
     *    the error, buzzes, and RESTORES the text into the draft so retry
     *    re-sends the same text — unless newer text claimed the draft while
     *    the send was pending, in which case the newer text wins and the
     *    failed text rides the error message (see [sendFailed]);
     *  - unpaired / no session / send-already-in-flight refuse cleanly up
     *    front: error surfaced, text kept in the draft, no POST, no echo —
     *    never pretending to send.
     */
    fun sendCommand(text: String, toSession: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // The fallback (the most recently WORKING session) predates screens
        // that show one specific session; those pass [toSession] so the
        // command — and its "> text" echo — land in the feed on screen.
        val sessionId = toSession ?: _state.value.sessionId
        fun refuse(result: String, error: String) {
            haptics.commandFailed()
            _state.update {
                // Same non-clobbering rule as sendFailed: the refused text
                // only fills a draft it already owns (or that is empty).
                // Other text in the draft — restored by an earlier failure —
                // stays; the refused text rides the error instead.
                val draftFree = it.commandDraft.isEmpty() || it.commandDraft == trimmed
                it.copy(
                    commandResult = result,
                    commandError = if (draftFree) error else "$error — not sent: “$trimmed”",
                    commandDraft = if (draftFree) trimmed else it.commandDraft,
                )
            }
        }
        if (!_state.value.paired) {
            refuse("command:not-paired", "Not paired — command not sent")
            return
        }
        if (sessionId == null) {
            refuse("command:no-session", "No session — command not sent")
            return
        }
        if (_state.value.commandInFlightText != null) {
            // One send at a time: a second command while the first awaits its
            // ack is refused into the draft (kept, never silently dropped).
            refuse("command:busy", "Still sending the previous command")
            return
        }
        // Pending, NOT echoed: the terminal shows nothing until the bridge
        // acks. The draft empties so the pending indicator owns the text —
        // but only a draft holding THIS text (a dictation with an occupied
        // draft sends without ever claiming it; wiping it here would destroy
        // the other text the draft is preserving).
        _state.update {
            it.copy(
                commandInFlightText = trimmed,
                commandError = null,
                commandDraft = if (it.commandDraft == trimmed) "" else it.commandDraft,
            )
        }
        engineScope.launch {
            try {
                val result = engine.sendCommand(sessionId, trimmed)
                when {
                    // The engine lost its pairing while the send was queued
                    // (stop/401 teardown): nothing was sent, same restore
                    // contract as any other failure.
                    result == null ->
                        sendFailed(trimmed, "command:not-paired", "not paired")
                    result.ok -> {
                        haptics.commandAcked()
                        _state.update { ui ->
                            // Echo and pending-clear are one atomic update: there
                            // is no observable state where the command is echoed
                            // but still pending, or acked but not echoed.
                            val echoed = ui.withBridge(ui.bridge.echoCommand(sessionId, trimmed))
                            echoed.copy(
                                commandResult = "command:${result.status}",
                                commandInFlightText = null,
                            )
                        }
                    }
                    else -> sendFailed(trimmed, "command:${result.status}", "HTTP ${result.status}")
                }
            } catch (e: Exception) {
                // Transport error or timeout: same contract as a non-2xx.
                sendFailed(trimmed, "command:error ${e.message}", "${e.message}")
            }
        }
    }

    /**
     * A send that went in flight failed (non-2xx, transport error, timeout):
     * echo nothing, surface the error, buzz, and put the text back where a
     * retry finds it. The restore is CONDITIONAL: it only refills the draft
     * this send emptied. Text typed or dictated during the pending window
     * owns the draft — overwriting it with the old in-flight text would
     * silently destroy the newer text, the exact loss class issue #20 exists
     * to prevent. When the draft is occupied, the failed text stays visible
     * inside the surfaced error instead, so it is still never silently lost.
     */
    private fun sendFailed(trimmed: String, result: String, reason: String) {
        haptics.commandFailed()
        _state.update {
            val draftFree = it.commandDraft.isEmpty()
            it.copy(
                commandResult = result,
                commandInFlightText = null,
                commandError = if (draftFree) {
                    "Send failed: $reason"
                } else {
                    "Send failed: $reason — not sent: “$trimmed”"
                },
                commandDraft = if (draftFree) trimmed else it.commandDraft,
            )
        }
    }

    /**
     * Spawn a fresh agent session ("claude" or "codex") in a bridge-owned
     * PTY. [cwd] is the spawn TARGET (issue #56): a known project's root from
     * the picker, the literal `"~"` for a no-project session in the bridge
     * user's home, or null for the bridge's own default cwd chain (the
     * pre-picker behavior, kept for any caller without a target). An invalid
     * directory is the bridge's 400 — surfaced via sessionActionResult, no
     * session slot created.
     */
    fun spawnSession(agent: String, cwd: String? = null) {
        engineScope.launch {
            try {
                val result = engine.spawnSession(agent, cwd)
                if (result == null) {
                    _state.update { it.copy(sessionActionResult = "spawn:not-paired") }
                    return@launch
                }
                val spawnedId = result.body?.optString("sessionId").takeUnless { it.isNullOrEmpty() }
                _state.update {
                    it.copy(
                        sessionActionResult = "spawn:${result.status}",
                        // Target the new session right away; the SSE `running`
                        // event makes it the current session moments later.
                        sessionId = spawnedId ?: it.sessionId,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(sessionActionResult = "spawn:error ${e.message}") }
            }
        }
    }

    /**
     * Kill [sessionId]; the page disappears when the bridge's `ended` event
     * prunes it. For a PTY-backed (external=false) session the bridge owns the
     * process, so this is a real stop; an EXTERNAL session uses [hideSession]
     * instead (the Halo row picks which by the session's `external` flag).
     */
    fun killSession(sessionId: String) {
        engineScope.launch {
            try {
                val result = engine.killSession(sessionId)
                _state.update {
                    it.copy(sessionActionResult = if (result == null) "kill:not-paired" else "kill:${result.status}")
                }
            } catch (e: Exception) {
                _state.update { it.copy(sessionActionResult = "kill:error ${e.message}") }
            }
        }
    }

    /**
     * Honest hide (issue #53): drop an EXTERNAL (hook-created, PTY-less)
     * session from view LOCALLY — no network, nothing killed. The bridge does
     * not own its process, so there is no real kill to perform; a ✕-that-stops
     * would pretend to end a session it cannot. The session reappears the
     * moment the bridge reports any activity for it again (the un-hide in
     * [handleEvent]). PTY-backed sessions keep the real [killSession].
     */
    fun hideSession(sessionId: String) {
        _state.update { it.copy(hiddenSessions = it.hiddenSessions + sessionId) }
    }

    /**
     * Client-side floor between LIVE usage refetches. The upstream endpoint
     * aggressively 429s pollers (GitHub issues #31637/#30930/#31021), and the
     * page-entry seam fires on EVERY landing — swipe past, dot tap, depth
     * round trip — so unthrottled fetch-on-open is a polling loop wearing a
     * different hat. 5 minutes; `internal` so tests can tune it to 0.
     */
    internal var usageRateLimitMs = 300_000L

    /**
     * Epoch ms of the last SUCCESSFUL `source == "api"` usage parse — the
     * ONLY thing that arms the limiter. Errors and cache fallbacks never
     * touch it, so the Error screen's Retry and a cache-served page entry
     * always refetch without needing force. 0 = never.
     */
    private var lastUsageApiSuccessMs = 0L

    /**
     * Fetch GET /v1/usage for the usage page (issue #57). Called on EVERY
     * entry to the page (and by its error-retry tap, and the on-page ~5min
     * auto-poll) — all non-forced; the freshness label's tap passes
     * [force] = true. Render-what-you-get parsing: every `limits[]` entry
     * becomes a bar, unknown kinds included; a 503 (neither the bridge's API
     * call nor its cache fallback yielded data) surfaces the bridge's error
     * string.
     *
     * Rate-limited (see [usageRateLimitMs]) unless [force]d: when fresh LIVE
     * bars are already on screen, a non-forced re-entry inside the window
     * returns WITHOUT any state change and no request leaves the watch. The
     * gate deliberately keys on the CURRENT state being a live
     * [UsageUi.Data]: an Error or cache-fallback state means the user is
     * looking at something worth replacing, so those always refetch. [force]
     * (the label tap — an explicit "refresh NOW") bypasses the limiter but
     * never the honesty contract below.
     *
     * SILENT refresh (2026-07-18): when the current state is already
     * [UsageUi.Data] — forced or not — the fetch does NOT flip to [Loading]:
     * the bars stay on screen and simply swap when the result lands, and a
     * FAILED silent refresh keeps the old Data rather than replacing live
     * bars with an error page (the aging "as of" label is the honest signal
     * that refreshes are not landing). Error/Idle starts still flip to
     * Loading exactly as before — there is nothing on screen worth keeping.
     */
    fun fetchUsage(force: Boolean = false) {
        val current = _state.value.usage
        if (!force && current is UsageUi.Data && current.source == "api" &&
            System.currentTimeMillis() - lastUsageApiSuccessMs < usageRateLimitMs
        ) {
            return
        }
        // Silent-refresh rule: only flip to Loading when there are no bars
        // to keep on screen (Idle/Loading/Error starts).
        if (current !is UsageUi.Data) {
            _state.update { it.copy(usage = UsageUi.Loading) }
        }
        engineScope.launch {
            val next = try {
                val result = engine.fetchUsage()
                when {
                    result == null -> UsageUi.Error("Not paired")
                    result.ok -> result.body?.let(::parseUsage)
                        ?: UsageUi.Error("Malformed usage payload")
                    else -> UsageUi.Error(
                        result.body?.optString("error").takeUnless { it.isNullOrEmpty() }
                            ?: "HTTP ${result.status}",
                    )
                }
            } catch (e: Exception) {
                // Transport error or timeout: surfaced with the retry
                // affordance, same honesty contract as every other fetch.
                UsageUi.Error("${e.message}")
            }
            // Only a live-API success arms the limiter (see
            // [lastUsageApiSuccessMs] for why cache/error never do).
            if (next is UsageUi.Data && next.source == "api") {
                lastUsageApiSuccessMs = System.currentTimeMillis()
            }
            _state.update {
                // A failed SILENT refresh keeps the Data on screen (checked
                // at landing time: a silent start never wrote Loading, so
                // Data-on-screen here means there are live bars to protect;
                // an Error/Idle start is Loading here and lands the Error).
                if (next is UsageUi.Error && it.usage is UsageUi.Data) it
                else it.copy(usage = next)
            }
        }
    }

    /** GET /v1/usage 200 body → [UsageUi.Data]; null when `limits` is missing. */
    private fun parseUsage(body: JSONObject): UsageUi.Data? {
        val limitsJson = body.optJSONArray("limits") ?: return null
        val limits = buildList {
            for (i in 0 until limitsJson.length()) {
                val entry = limitsJson.optJSONObject(i) ?: continue
                val kind = entry.optString("kind")
                add(
                    UsageLimit(
                        kind = kind,
                        // A label-less entry still renders — its kind is the
                        // most honest label available.
                        label = entry.optString("label").takeUnless { it.isEmpty() } ?: kind,
                        percent = entry.optDouble("percent", 0.0),
                        resetsAt = entry.optString("resetsAt").takeUnless { it.isEmpty() },
                        severity = entry.optString("severity").takeUnless { it.isEmpty() },
                    ),
                )
            }
        }
        return UsageUi.Data(
            limits = limits,
            source = body.optString("source").takeUnless { it.isEmpty() } ?: "api",
            // ALWAYS non-null in the client model ("when these numbers were
            // current"): the wire carries fetchedAtMs only for cache
            // fallbacks — keep that value (the data's true age) — while a
            // live api payload is stamped NOW at parse time. A
            // contract-violating cache body without the key also degrades to
            // the now-stamp: least-wrong, and the label reads "just now".
            fetchedAtMs = if (body.has("fetchedAtMs")) {
                body.optLong("fetchedAtMs")
            } else {
                System.currentTimeMillis()
            },
        )
    }

    /**
     * Answer the queued prompt [permissionId] — always the id of the RENDERED
     * card, passed down from the UI, never "whatever is globally current"
     * (the watchOS race that approved the wrong permission). Dismissal is
     * ack-gated: the prompt leaves the queue only on an authoritative outcome
     *  - 2xx: we resolved it ourselves;
     *  - 404: gone (resolved from another paired device — keeping it would
     *    wedge a zombie prompt forever, since that path pushes no
     *    permission-cleared; server-side expiry does now broadcast one, but
     *    racing our own answer against it can still 404 first);
     *  - 401/403: this device's token is dead (bridge restarted, device
     *    revoked). No retry with this token can ever succeed and this device
     *    can no longer receive a permission-cleared, so keeping the card would
     *    wedge the whole app — pairing page included — behind an unanswerable
     *    sheet. The card is dropped WITHOUT deciding anything (the bridge's
     *    own expiry owns the real outcome) and a re-pair error is surfaced.
     * Any retryable failure (transport, 5xx) keeps the prompt queued and
     * surfaces the error — never a silent inversion of an approval into a
     * 10-minute auto-deny — and bumps [UiState.decisionFailureCount], which
     * unlocks the sheet's [dismissPermissionLocally] escape hatch.
     */
    fun answerPermission(permissionId: String, behavior: String) {
        sendDecision(permissionId) {
            val message = if (behavior == "deny") "Denied from the watch" else null
            engine.answerPermission(permissionId, behavior, message)
        }
    }

    /**
     * Answer the queued AskUserQuestion prompt [permissionId] with an answer
     * for EVERY question — [answers] is positional, one entry per question in
     * the prompt's question order (a selected option's label or free typed
     * text); the bridge zips the array with the questions into
     * `updatedInput.answers` for the blocked hook. Positional, not text-keyed:
     * questions with duplicate text must still each count as answered (a
     * text-keyed map collapses them and would deadlock the send gate). Same
     * ack-gated dismissal and failure semantics as [answerPermission]: the
     * card leaves the queue only on 2xx/404 (or dead-token 401/403), a
     * retryable failure keeps it rendered with the error surfaced and counts
     * toward the local-dismiss escape hatch.
     */
    fun answerQuestions(permissionId: String, answers: List<String>) {
        if (answers.isEmpty()) return
        sendDecision(permissionId) {
            engine.answerQuestions(permissionId, answers)
        }
    }

    private fun sendDecision(
        permissionId: String,
        post: suspend () -> BridgeClient.ApiResult?,
    ) {
        // Answer only a prompt that is actually still queued.
        if (_state.value.bridge.pendingPermissions.none { it.permissionId == permissionId }) return
        _state.update { it.copy(decisionInFlightId = permissionId, decisionError = null) }
        engineScope.launch {
            try {
                val result = post()
                if (result == null) {
                    // Engine not paired: nothing was sent; the prompt stays
                    // queued (retryable — same class as a transport failure).
                    _state.update {
                        it.copy(
                            decisionResult = "decision:not-paired",
                            decisionForId = permissionId,
                            decisionInFlightId = null,
                            decisionError = "Not paired — decision not sent",
                            decisionFailureCount = it.decisionFailureCount + 1,
                        )
                    }
                    return@launch
                }
                _state.update { ui ->
                    // The bridge pushes no permission-cleared when a prompt is
                    // resolved via /v1/command from another paired device (that
                    // device drops it locally on the 2xx). Expiry and
                    // answered-elsewhere DO broadcast now (issue #63), but our
                    // own answer can still race ahead of that SSE frame. So
                    // both outcomes here mean the prompt is gone and must be
                    // dropped locally:
                    //  - ok: we resolved it ourselves.
                    //  - 404: the bridge says it no longer exists (answered
                    //    elsewhere or expired). Keeping it would wedge a
                    //    zombie prompt in state forever.
                    val gone = result.ok || result.status == 404
                    // Dead token (bridge restarted / device revoked): also
                    // dropped — see the function doc. Nothing is decided on
                    // the user's behalf; only the local card goes away.
                    val authDead = result.status == 401 || result.status == 403
                    val next = ui.copy(
                        decisionResult = "decision:${result.status}",
                        decisionForId = permissionId,
                        decisionInFlightId = null,
                        decisionError = when {
                            result.ok -> null
                            result.status == 404 -> "Already resolved elsewhere"
                            authDead -> "Not authorized — re-pair with the bridge"
                            else -> "Decision failed: HTTP ${result.status}"
                        },
                        decisionFailureCount =
                            if (gone || authDead) 0 else ui.decisionFailureCount + 1,
                    )
                    if (gone || authDead) next.withBridge(next.bridge.resolvePermission(permissionId)) else next
                }
            } catch (e: Exception) {
                // Transport failure: the prompt stays queued (it may well still
                // be pending server-side) and the error is surfaced on the card.
                _state.update {
                    it.copy(
                        decisionResult = "decision:error ${e.message}",
                        decisionForId = permissionId,
                        decisionInFlightId = null,
                        decisionError = "Decision failed: ${e.message}",
                        decisionFailureCount = it.decisionFailureCount + 1,
                    )
                }
            }
        }
    }

    /**
     * Drop [permissionId] from the LOCAL queue without sending any decision —
     * the escape hatch for a bridge that stopped answering (host gone,
     * network changed, laptop closed). Never a false resolve: nothing is
     * decided on the user's behalf, the bridge's own pending-permission
     * timeout owns the real outcome, and if the prompt is in fact still live
     * the user simply stops seeing it here. The sheet offers this only after
     * repeated failed answer attempts ([UiState.decisionFailureCount]);
     * without it an unreachable bridge would wedge the whole app — pairing
     * page included — behind an unanswerable full-screen sheet.
     */
    fun dismissPermissionLocally(permissionId: String) {
        _state.update { ui ->
            val next = ui.copy(
                decisionError = null,
                decisionFailureCount = 0,
                decisionInFlightId = ui.decisionInFlightId.takeUnless { it == permissionId },
            )
            next.withBridge(next.bridge.resolvePermission(permissionId))
        }
    }

    private fun handleEvent(id: String?, type: String, data: String) {
        val frame = SseFrame(id, type, data)
        // Whether the reducer APPLIED this frame — decided purely by parse, so
        // it is stable across any update()-retry (the flag reflects the
        // committed run). Only an applied frame is acked back to the engine.
        var applied = false
        _state.update { ui ->
            when (val result = BridgeEventReducer.reduce(ui.bridge, frame, System.currentTimeMillis())) {
                is BridgeEventReducer.Applied -> {
                    applied = true
                    val next = ui.withBridge(result.state)
                    // Issue #53: a GENUINE-ACTIVITY event for a hidden session
                    // un-hides it ("until it speaks again"). A bare `session`
                    // metadata frame does NOT count as speaking: the bridge
                    // re-sends `session running` for every live slot on every
                    // reconnect (and on revive / title / project-root rebind),
                    // so un-hiding on it would let any routine reconnect defeat
                    // an honest hide. Output, tool-output, turn-end and a raised
                    // prompt are what reveal a hidden external session again —
                    // and a revive always rides in with one of those.
                    val sid = result.event.sessionId
                    if (result.event !is SessionEvent && sid != null && sid in next.hiddenSessions) {
                        next.copy(hiddenSessions = next.hiddenSessions - sid)
                    } else {
                        next
                    }
                }
                // Contract violation: drop the frame, leave state (incl.
                // lastEventId) untouched so a reconnect replays it.
                is BridgeEventReducer.Rejected -> ui
            }
        }
        // Issue #48: ACK only APPLIED frames back to the engine, so its
        // persisted + reconnect cursor never runs ahead of what the reducer
        // applied — a frame rejected during a reconnect window is then replayed.
        // A Rejected frame is deliberately NOT acked.
        if (applied && !id.isNullOrEmpty()) engine.ackApplied(id)
    }

    /** Mirror the reduced bridge state into the flat fields the screen renders. */
    private fun UiState.withBridge(bridge: BridgeState): UiState = copy(
        bridge = bridge,
        eventLog = bridge.eventLog,
        // Sticky fallback: keep targeting the last known session when none is
        // currently running (matches the skeleton's previous behavior).
        sessionId = bridge.currentSessionId ?: sessionId,
        // The full pending queue, newest-first (see UiState.permissionQueue
        // for why), each entry resolved against the CURRENT session table so
        // late-arriving session metadata still labels an earlier prompt.
        permissionQueue = bridge.pendingPermissions.asReversed().map { it.toPending(bridge) },
    )

    private fun PermissionRequestEvent.toPending(bridge: BridgeState): PendingPermission {
        val session = sessionId?.let { bridge.sessions[it] }
        // AskUserQuestion prompts are content, not permission decisions: they
        // carry per-question option lists instead of canonical options, and
        // the question card answers them via answerQuestions. A question
        // payload that parses to nothing degrades to the plain allow/deny
        // card below rather than an unanswerable sheet.
        val askQuestions = questions
        return PendingPermission(
            permissionId = permissionId,
            sessionId = sessionId,
            toolName = toolName ?: "?",
            requestSummary = ToolOutputFormatter.describeToolRequest(toolName, toolInput),
            sessionLabel = session?.folderName
                ?: session?.agent
                ?: sessionId?.take(8)
                ?: "unknown session",
            // Behavior-keyed options straight from the bridge; a legacy event
            // without options still gets behavior-based allow/deny (never
            // label matching). Question prompts render questions, not options.
            options = if (askQuestions.isNotEmpty()) emptyList() else options.ifEmpty {
                listOf(
                    PermissionOption("allow", "Yes"),
                    PermissionOption("deny", "No"),
                )
            },
            questions = askQuestions,
        )
    }

    private fun statusText(connection: ConnectionState): String = when (connection) {
        ConnectionState.Stopped -> "unpaired"
        ConnectionState.Pairing -> "pairing"
        is ConnectionState.PairFailed -> "pair failed: ${connection.message}"
        is ConnectionState.ProtoMismatch ->
            "bridge proto ${connection.bridgeProto ?: "unknown"} unsupported (need >= ${connection.minProto})"
        // Deliberately does not contain "paired": events could still be
        // missed until the stream is actually open (see BridgeViewModelTest).
        is ConnectionState.Connecting -> "connecting stream"
        ConnectionState.Connected -> "paired, stream open"
        is ConnectionState.Reconnecting -> "paired, reconnecting (${connection.reason})"
        is ConnectionState.AuthExpired -> "re-pair required"
        is ConnectionState.BridgeMismatch -> "re-pair required (different bridge)"
    }

    private fun repairExplanation(connection: ConnectionState): String? = when (connection) {
        is ConnectionState.AuthExpired -> connection.reason
        is ConnectionState.ProtoMismatch ->
            "This bridge speaks protocol ${connection.bridgeProto ?: "unknown"} but the app needs " +
                "${connection.minProto} or newer. Update the bridge skill on your computer, then pair again."
        is ConnectionState.BridgeMismatch ->
            "A different bridge (${connection.actualBridgeId ?: "unknown"}) answered at the paired " +
                "address — expected ${connection.expectedBridgeId ?: "unknown"}. Nothing was sent to it. " +
                "If your computer's address changed, pair again from the bridge banner."
        else -> null
    }

    private fun ConnectionState.isPairedState(): Boolean = when (this) {
        is ConnectionState.Connecting, ConnectionState.Connected, is ConnectionState.Reconnecting -> true
        else -> false
    }

    /**
     * Cancel all network activity WITHOUT clearing credentials. Production
     * never calls this — the [singleton] lives exactly as long as the
     * process, and process death IS the teardown (that engine death on
     * activity destroy was the whole bug #24 exists to fix). Kept public for
     * tests that construct their own instances and must not leak sockets
     * between cases.
     */
    fun shutdown() {
        engine.shutdown()
        engineScope.cancel()
    }

    companion object {
        @Volatile
        private var instance: BridgeViewModel? = null

        /**
         * Process-wide singleton with the production wiring: the
         * Keystore-encrypted [CredentialStore.singleton] and held-Wi-Fi
         * escalation through the real ConnectivityManager. Same
         * double-checked pattern as the store's, for the same reason turned
         * up to eleven (issue #24): the engine and its SSE stream must have
         * PROCESS lifetime — MainActivity and [BridgeSessionService] both
         * attach to this one instance, and no activity teardown may kill it.
         */
        fun singleton(context: Context): BridgeViewModel =
            instance ?: synchronized(this) {
                instance ?: BridgeViewModel(
                    CredentialStore.singleton(context),
                    escalator = WifiNetworkEscalator(context.applicationContext),
                ).also { instance = it }
            }

        /**
         * PEEK at the singleton WITHOUT constructing it (issue #28's
         * passivity rule). [singleton] constructs on first touch, and
         * construction is not free: init fires engine.start(), which reads
         * persisted credentials and OPENS THE STREAM. That is the right
         * behavior for MainActivity and BridgeSessionService — surfaces the
         * user deliberately entered — and exactly the wrong behavior for a
         * glanceable: the tile carousel calls onTileRequest on a SWIPE PAST,
         * and a swipe is not consent to spin up the network. Glanceables
         * peek; null means "the app process holds no engine right now" and
         * renders honestly as disconnected + tap-to-open, never as a freshly
         * started connection the user didn't ask for.
         */
        fun peek(): BridgeViewModel? = instance
    }
}
