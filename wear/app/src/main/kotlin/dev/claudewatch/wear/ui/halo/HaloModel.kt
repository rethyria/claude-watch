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
) {
    /**
     * The ⎇ badge text — "⎇ main", "⎇ issue-53-fix · wt" for a worktree — or
     * null when no branch is known (non-git root, older bridge: no badge).
     */
    val branchLabel: String?
        get() = branch?.let { if (worktree) "⎇ $it · wt" else "⎇ $it" }
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
    val sessions: List<HaloSession>, // flat, project-order — drives the "All" ring
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
                    branch = s.branch,
                    worktree = s.worktree,
                    agentsRunning = s.agents?.running ?: 0,
                    // repoRoot beats cwd: for a worktree session repoRoot IS
                    // the main checkout, and a new session belongs there.
                    spawnRoot = s.repoRoot ?: s.cwd,
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
            // reshuffle as sessions transition state.
            val projects = LinkedHashMap<String, MutableList<HaloSession>>()
            for (session in all) {
                projects.getOrPut(session.projectName) { mutableListOf() }.add(session)
            }

            // Queue order follows the ViewModel's permissionQueue (newest-first,
            // the front is the rendered card), mapped onto the derived sessions.
            val byId = all.associateBy { it.id }
            val queue = ui.permissionQueue.mapNotNull { p -> byId[p.sessionId ?: "prompt:${p.permissionId}"] }

            return HaloModel(
                projects = projects.map { (name, sessions) -> HaloProject(name, sessions) },
                sessions = all,
                queue = queue,
            )
        }
    }
}
