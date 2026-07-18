package dev.claudewatch.wear.ui.halo

import dev.claudewatch.shared.protocol.AgentsActivity
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionActivity
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.BridgeViewModel.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HaloModel title preference (issue #50): the bridge-derived wire title wins;
 * without one the `agent · short-id` fallback keeps sibling sessions in one
 * project distinguishable.
 */
class HaloModelTest {

    private fun session(
        id: String,
        agent: String? = "claude",
        title: String? = null,
        folderName: String? = "proj",
        cwd: String? = "/home/dev/proj",
        external: Boolean = false,
        branch: String? = null,
        worktree: Boolean = false,
        repoRoot: String? = null,
        agents: AgentsActivity? = null,
    ) = SessionState(
        sessionId = id,
        agent = agent,
        cwd = cwd,
        folderName = folderName,
        title = title,
        external = external,
        branch = branch,
        worktree = worktree,
        repoRoot = repoRoot,
        agents = agents,
        activity = SessionActivity.WORKING,
        activeSinceMs = 1_000L,
    )

    private fun uiState(vararg sessions: SessionState, hidden: Set<String> = emptySet()) = UiState(
        bridge = BridgeState(sessions = sessions.associateBy { it.sessionId }),
        hiddenSessions = hidden,
    )

    @Test
    fun wireTitleIsPreferredOverTheAgentShortIdFallback() {
        val model = HaloModel.from(
            uiState(session("5f0d2c9a-8b1e", title = "Fix the flaky auth tests")),
        )
        assertEquals("Fix the flaky auth tests", model.sessions.single().title)
    }

    @Test
    fun withoutAWireTitleTheAgentShortIdFallbackRemains() {
        val model = HaloModel.from(
            uiState(
                session("5f0d2c9a-8b1e", title = null),
                session("b7e3f1c2-4d5a", agent = "codex", title = null),
                session("c9a8b7c6-d5e4", agent = null, title = null),
            ),
        )
        val titles = model.sessions.map { it.title }
        assertEquals(listOf("claude · 5f0d2c", "codex · b7e3f1", "session · c9a8b7"), titles)
    }

    /** Issue #53: the wire `external` flag is threaded onto the HaloSession so
     *  the row can pick honest-hide vs real-kill by it. */
    @Test
    fun externalFlagIsThreadedOntoTheHaloSession() {
        val model = HaloModel.from(
            uiState(
                session("s-pty", external = false),
                session("s-ext", external = true),
            ),
        )
        assertFalse("a PTY session is killable", model.sessions.single { it.id == "s-pty" }.external)
        assertTrue("a hook-created session is external (hide, not kill)", model.sessions.single { it.id == "s-ext" }.external)
    }

    /** Issue #53: an honest-hidden external session is filtered OUT of the
     *  derived model — the local view filter behind the row's "hide" action. */
    @Test
    fun aHiddenExternalSessionIsFilteredOutOfTheDerivedModel() {
        val visible = HaloModel.from(uiState(session("s-ext", external = true)))
        assertEquals(1, visible.sessionCount)

        val hiddenModel = HaloModel.from(
            uiState(session("s-ext", external = true), hidden = setOf("s-ext")),
        )
        assertTrue("a hidden session leaves the derived model entirely", hiddenModel.sessions.isEmpty())
        assertEquals(0, hiddenModel.projectCount)
    }

    /** Issue #54: a worktree session groups under basename(repoRoot), so it
     *  joins its real project alongside the main-checkout session instead of
     *  forming a lonely group named after the worktree directory. */
    @Test
    fun aWorktreeSessionGroupsUnderItsMainRepoRoot() {
        val model = HaloModel.from(
            uiState(
                session(
                    "s-main",
                    folderName = "alpha",
                    cwd = "/home/dev/alpha",
                    branch = "main",
                ),
                session(
                    "s-wt",
                    folderName = "alpha-issue-53",
                    cwd = "/home/dev/worktrees/alpha-issue-53",
                    branch = "issue-53-fix",
                    worktree = true,
                    repoRoot = "/home/dev/alpha",
                ),
            ),
        )
        assertEquals(1, model.projectCount)
        assertEquals("alpha", model.projects.single().name)
        assertEquals(listOf("s-main", "s-wt"), model.projects.single().sessions.map { it.id })
    }

    /** Issue #54: branch/worktree are threaded onto the HaloSession, and the
     *  ⎇ badge label renders only when a branch is known (back-compat). */
    @Test
    fun branchAndWorktreeAreThreadedOntoTheHaloSession() {
        val model = HaloModel.from(
            uiState(
                session("s-git", branch = "main"),
                session("s-wt", branch = "issue-53-fix", worktree = true, repoRoot = "/home/dev/proj"),
                session("s-plain"),
            ),
        )
        val git = model.sessions.single { it.id == "s-git" }
        assertEquals("main", git.branch)
        assertFalse(git.worktree)
        assertEquals("⎇ main", git.branchLabel)

        val wt = model.sessions.single { it.id == "s-wt" }
        assertTrue(wt.worktree)
        assertEquals("⎇ issue-53-fix · wt", wt.branchLabel)

        // No branch known (non-git root, older bridge): no badge at all.
        val plain = model.sessions.single { it.id == "s-plain" }
        assertEquals(null, plain.branch)
        assertEquals(null, plain.branchLabel)
    }

    /** Issue #55: agentsRunning is threaded onto the HaloSession; the
     *  indicator condition (running > 0) is false both when no workflow was
     *  ever observed AND after the explicit {running:0} completion clear. */
    @Test
    fun agentsRunningIsThreadedAndZeroAfterTheExplicitClear() {
        val model = HaloModel.from(
            uiState(
                session("s-busy", agents = AgentsActivity(running = 3, done = 1)),
                session("s-done", agents = AgentsActivity(running = 0, done = 4)),
                session("s-quiet"),
            ),
        )
        assertEquals(3, model.sessions.single { it.id == "s-busy" }.agentsRunning)
        assertTrue(model.sessions.single { it.id == "s-busy" }.agentsRunning > 0)
        // Completed workflow (explicit present-but-zero) and never-observed
        // both hide the indicator: running > 0 is the ONLY show condition.
        assertEquals(0, model.sessions.single { it.id == "s-done" }.agentsRunning)
        assertEquals(0, model.sessions.single { it.id == "s-quiet" }.agentsRunning)
    }

    @Test
    fun mixedSessionsKeepEachTheirOwnLabel() {
        val model = HaloModel.from(
            uiState(
                session("5f0d2c9a-8b1e", title = "Port pairing to WearOS"),
                session("b7e3f1c2-4d5a", title = null),
            ),
        )
        assertEquals(
            listOf("Port pairing to WearOS", "claude · b7e3f1"),
            model.sessions.map { it.title },
        )
        // Both still group under the same project regardless of title source.
        assertEquals(1, model.projectCount)
        assertEquals("proj", model.projects.single().name)
    }
}
