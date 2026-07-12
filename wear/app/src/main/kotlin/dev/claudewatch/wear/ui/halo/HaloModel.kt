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
)

/** A project groups the sessions sharing a working directory. */
data class HaloProject(
    val name: String,
    val sessions: List<HaloSession>,
)

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
                val project = s.folderName
                    ?: s.cwd?.trimEnd('/')?.substringAfterLast('/').takeUnless { it.isNullOrBlank() }
                    ?: "workspace"
                HaloSession(
                    id = s.sessionId,
                    title = pending?.sessionLabel?.takeIf { it.isNotBlank() }
                        ?: s.folderName
                        ?: "${s.agent ?: "session"} · ${s.sessionId.take(6)}",
                    projectName = project,
                    state = state,
                    pending = pending,
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
            val all = halo + orphans

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
