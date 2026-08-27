// Pure derivation of the Halo view-model from the wave-2 UiState/BridgeState.
// Halo's information architecture is projects → sessions; the bridge gives us
// a flat session map plus a permission queue, so we group by folderName and
// fold the queue back onto each session as its "waiting" state. No I/O, no
// Compose — trivially unit-testable.
package dev.claudewatch.wear.ui.halo

import dev.claudewatch.shared.state.SessionActivity
import dev.claudewatch.wear.BridgeViewModel.PendingPermission
import dev.claudewatch.wear.BridgeViewModel.UiState
import dev.claudewatch.wear.ui.halo.Halo.SessionState

/** One session as Halo renders it (ring segment, list row, feed header). */
data class HaloSession(
    val id: String,
    val title: String,
    val projectName: String,
    val state: SessionState,
    /** The queued prompt for this session, if it is waiting. */
    val pending: PendingPermission? = null,
    /**
     * True for a HOOK-CREATED (external, PTY-less) session the bridge does not
     * own: its row close action HIDES it honestly instead of a fake kill
     * (issue #53). False for bridge-owned PTY sessions (real kill).
     */
    val external: Boolean = false,
    /**
     * True when the bridge can deliver a dictated prompt into this session LIVE
     * (its own PTY, or an ACP inject). The Dictate pill shows ONLY when true;
     * other sessions get an honest "unavailable" affordance (issue #78). Gated
     * on this, NOT on !external — an ACP session is external AND dictatable.
     */
    val dictatable: Boolean = false,
    /** Session-type discriminator (issue #78): "acp" for a Zed-hosted session. */
    val kind: String? = null,
    /** Git branch of the session's root (issue #54); null hides the ⎇ badge. */
    val branch: String? = null,
    /** True when the session runs in a linked git worktree (issue #54). */
    val worktree: Boolean = false,
    /**
     * Workflow subagents currently in flight (issue #55). The indicator shows
     * ONLY while > 0 — indicator only, no control affordance (a watch cannot
     * stop a workflow; #53's honesty lesson).
     */
    val agentsRunning: Int = 0,
    /**
     * Where a NEW session for this session's project should spawn (issue
     * #56): the MAIN repo root when known — a worktree session offers the
     * main checkout, never its throwaway worktree directory — else the
     * session's own cwd. Null when neither is known (queue-orphan synthetic
     * sessions), in which case this session contributes no spawn target.
     */
    val spawnRoot: String? = null,
    /**
     * The pager card's `model · mode · use%` subheading parts (Halo v2
     * S5/S9), already in DISPLAY form: model prefix-stripped, mode through
     * the short-label map. Null whenever the bridge has not reported the
     * wire field — only ACP sessions ever do — and the card renders each
     * part only when present, so PTY/hook sessions keep a clean partial or
     * empty row.
     */
    val modelName: String? = null,
    val modeName: String? = null,
    /** Context-window use, 0–100; ≥80 renders terracotta (running hot). */
    val usePercent: Int? = null,
) {
    /**
     * The ⎇ badge text — "⎇ main", "⎇ issue-53-fix · wt" for a worktree — or
     * null when no branch is known (non-git root, older bridge: no badge).
     */
    val branchLabel: String?
        get() = branch?.let { if (worktree) "⎇ $it · wt" else "⎇ $it" }
}

/**
 * The subheading's model part (Halo v2 S9, #102): the bridge's display name
 * minus a leading "Claude " — the brand prefix says nothing a wrist glance
 * needs, and "Opus 4.6" fits where "Claude Opus 4.6" wraps at 9.5sp.
 * Everything else (short names like "Opus", third-party backend ids, the
 * bare "Default" fallback) renders verbatim, per the wire contract.
 */
internal fun modelDisplayName(model: String): String = model.removePrefix("Claude ")

/**
 * The subheading's mode part (Halo v2 S9, #102): short labels for the ACP
 * permission-mode ids we know — "default" reads as "manual" because it is
 * the ask-me-everything mode and the word "default" says nothing on a card.
 * The vocabulary is the agent's and may grow, so an unknown id passes
 * through verbatim rather than hiding the mode.
 */
internal fun modeLabel(mode: String): String = when (mode) {
    "default" -> "manual"
    "plan" -> "plan"
    "acceptEdits" -> "edits"
    "bypassPermissions" -> "bypass"
    "dontAsk" -> "no-ask"
    else -> mode
}

/** A project groups the sessions sharing a working directory. */
data class HaloProject(
    val name: String,
    val sessions: List<HaloSession>,
)

/** One spawn-picker entry (issue #56): a known project and its spawn root. */
data class SpawnTarget(val projectName: String, val root: String)

/** The whole derived tree plus the cross-cutting waiting queue. */
data class HaloModel(
    val projects: List<HaloProject>,
    /**
     * The flat session list, project-grouped BY CONSTRUCTION (the flatten of
     * [projects]): the All ring, the list pager, and the grouped list render
     * this ONE order, so they can never diverge however the bridge
     * interleaves projects (Halo v2 S1).
     */
    val sessions: List<HaloSession>,
    /** Waiting sessions in queue order: drives tap-center + card chaining. */
    val queue: List<HaloSession>,
) {
    val sessionCount: Int get() = sessions.size
    val projectCount: Int get() = projects.size
    val waitingCount: Int get() = queue.size

    /**
     * The spawn picker's per-project targets (issue #56): each known
     * project's spawn root is its sessions' FIRST known [HaloSession.spawnRoot]
     * (repoRoot beats cwd per session — a worktree session offers the MAIN
     * checkout). A project whose sessions know neither repoRoot nor cwd
     * (queue-orphan synthetics) offers no target: spawning "somewhere under
     * that name" would land the session in the bridge's own cwd, the exact
     * invisible-surprise #56 removes.
     */
    val spawnTargets: List<SpawnTarget>
        get() = projects.mapNotNull { project ->
            project.sessions.firstNotNullOfOrNull { it.spawnRoot }
                ?.let { SpawnTarget(project.name, it) }
        }

    companion object {
        fun from(ui: UiState): HaloModel {
            // Index the queue by session so a session's state reflects a real
            // pending prompt; a prompt with no sessionId still counts globally.
            val pendingBySession: Map<String?, PendingPermission> =
                ui.permissionQueue.associateBy { it.sessionId }

            val halo = ui.bridge.sessions.values.map { s ->
                val pending = pendingBySession[s.sessionId]
                val state = when {
                    pending != null && pending.questions.isNotEmpty() -> SessionState.WAITING_Q
                    pending != null -> SessionState.WAITING_PERM
                    // Red outranks every non-blocking state (issue #129's
                    // third verb finally has a colour to match): a session
                    // whose last word was an error is news the user has to
                    // act on, and showing it as green/blue because a stray
                    // frame arrived after the failure would bury exactly the
                    // thing worth surfacing. A pending prompt still wins —
                    // that one is BLOCKING, and the card is the way out of
                    // it. The latch clears the moment the session speaks
                    // again (BridgeStateReducer.working).
                    s.errored -> SessionState.ERROR
                    // Subagents in flight (issue #55's counts) outrank RUNNING
                    // deliberately (issue #67). The original design gated blue
                    // on the session ALSO being idle — main loop stopped AND
                    // agents running — but those never coincide: while a
                    // workflow runs, Claude Code holds the turn open, so the
                    // Stop that sets idle never fires, and the session stayed
                    // green the whole time and blue was unreachable. So we drop
                    // the idle precondition entirely: any running subagents ->
                    // blue, full stop. The wrist reading it as "delegated" is
                    // right even when the main loop is briefly foreground-active
                    // — during a workflow it is idle or mostly-idle, just
                    // shepherding the fleet. A genuinely actionable prompt
                    // (WAITING, above) still outranks this; nothing else does.
                    (s.agents?.running ?: 0) > 0 -> SessionState.DELEGATED
                    s.thinking || s.activity == SessionActivity.WORKING -> SessionState.RUNNING
                    else -> SessionState.IDLE
                }
                // A worktree session reports its MAIN repo root (issue #54):
                // grouping under basename(repoRoot) folds it into its real
                // project instead of a lonely group named after the worktree
                // directory. folderName/cwd remain the non-worktree path.
                val project = s.repoRoot?.trimEnd('/')?.substringAfterLast('/')
                    .takeUnless { it.isNullOrBlank() }
                    ?: s.folderName
                    ?: s.cwd?.trimEnd('/')?.substringAfterLast('/').takeUnless { it.isNullOrBlank() }
                    ?: "workspace"
                HaloSession(
                    id = s.sessionId,
                    // Prefer the real session title the bridge derives from
                    // the Claude Code transcript (additive `title` wire
                    // field). Until it arrives, agent · short-id stays the
                    // honest distinct fallback: folderName IS the project (and
                    // sessionLabel derives from it), so using either here
                    // would duplicate the divider/page label and make sibling
                    // sessions in one project indistinguishable.
                    title = s.title ?: "${s.agent ?: "session"} · ${s.sessionId.take(6)}",
                    projectName = project,
                    state = state,
                    pending = pending,
                    external = s.external,
                    dictatable = s.dictatable,
                    kind = s.kind,
                    branch = s.branch,
                    worktree = s.worktree,
                    agentsRunning = s.agents?.running ?: 0,
                    // repoRoot beats cwd: for a worktree session repoRoot IS
                    // the main checkout, and a new session belongs there.
                    spawnRoot = s.repoRoot ?: s.cwd,
                    // Session meta (S9, #102) maps to display form here so the
                    // card just prints. usePercent copies by PRESENCE — the
                    // Int? carries an explicit 0 (fresh session) through
                    // untouched, and a null stays null rather than a guess.
                    modelName = s.model?.let(::modelDisplayName),
                    modeName = s.mode?.let(::modeLabel),
                    usePercent = s.contextPct,
                )
            }

            // A queued prompt whose session the bridge doesn't report (null
            // sessionId, or the session already pruned) still BLOCKS the
            // agent; dropping it would leave no ring segment, no waiting
            // state, and no path that can ever open its card. Surface each as
            // a synthetic session under its resolved label instead.
            val known = ui.bridge.sessions.keys
            val orphans = ui.permissionQueue
                .filter { it.sessionId == null || it.sessionId !in known }
                .map { p ->
                    HaloSession(
                        id = p.sessionId ?: "prompt:${p.permissionId}",
                        title = p.sessionLabel,
                        projectName = p.sessionLabel,
                        state = if (p.questions.isNotEmpty()) SessionState.WAITING_Q else SessionState.WAITING_PERM,
                        pending = p,
                    )
                }
                .distinctBy { it.id }
            // Issue #53: honest-hidden external sessions drop OUT of the
            // derived model (a local view filter, not a bridge mutation); the
            // ViewModel un-hides an id the moment an applied event for it lands.
            val all = (halo + orphans).filterNot { it.id in ui.hiddenSessions }

            // Stable project order: first-seen wins, so the ring/pager don't
            // reshuffle as sessions transition state. A project's place is
            // therefore its EARLIEST live session — when the project showed
            // up, not what it has been doing since.
            val projects = LinkedHashMap<String, MutableList<HaloSession>>()
            for (session in all) {
                projects.getOrPut(session.projectName) { mutableListOf() }.add(session)
            }

            // Queue order follows the ViewModel's permissionQueue (newest-first,
            // the front is the rendered card), mapped onto the derived sessions.
            val byId = all.associateBy { it.id }
            val queue = ui.permissionQueue.mapNotNull { p -> byId[p.sessionId ?: "prompt:${p.permissionId}"] }

            // NEWEST PROJECT FIRST, to match Zed — which lists projects in the
            // order they were opened with the most recent at the top. The wrist
            // had the same sequence upside down: a fork registers with the
            // bridge when Zed starts its agent, so bridge-insertion order IS
            // opening order, and reading it forwards put the project opened
            // longest ago at the front of the pager.
            //
            // Reversing the PROJECT list (not the session iteration) is what
            // keeps first-seen-wins intact: each project stays pinned to its
            // earliest session, so this is the same stable sequence read from
            // the other end, not a re-key onto whatever spoke last.
            val grouped = projects.entries.reversed().map { (name, sessions) ->
                HaloProject(name, sessions)
            }
            return HaloModel(
                projects = grouped,
                // The flatten of the grouping, NOT bridge-insertion order: a
                // bridge that interleaves projects would otherwise give the
                // All ring one order and the grouped list another, and the v2
                // pager needs them to be the same list. A deliberate,
                // user-visible All-ring reorder for interleaved projects.
                sessions = grouped.flatMap { it.sessions },
                queue = queue,
            )
        }
    }
}
