package dev.claudewatch.wear.ui.halo

import dev.claudewatch.shared.protocol.AgentsActivity
import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.state.BridgeEventReducer
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionActivity
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.BridgeViewModel.PendingPermission
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
        dictatable: Boolean = false,
        kind: String? = null,
        branch: String? = null,
        worktree: Boolean = false,
        repoRoot: String? = null,
        agents: AgentsActivity? = null,
        model: String? = null,
        mode: String? = null,
        contextPct: Int? = null,
    ) = SessionState(
        sessionId = id,
        agent = agent,
        cwd = cwd,
        folderName = folderName,
        title = title,
        external = external,
        dictatable = dictatable,
        kind = kind,
        branch = branch,
        worktree = worktree,
        repoRoot = repoRoot,
        agents = agents,
        model = model,
        mode = mode,
        contextPct = contextPct,
        activity = SessionActivity.WORKING,
        activeSinceMs = 1_000L,
    )

    private fun uiState(vararg sessions: SessionState, hidden: Set<String> = emptySet()) = UiState(
        bridge = BridgeState(sessions = sessions.associateBy { it.sessionId }),
        hiddenSessions = hidden,
    )

    /**
     * The red ring, end to end from a bridge `error` frame. SessionState.ERROR
     * had a colour and no producer before this — nothing in the app could ever
     * make a session red, so the state was decorative.
     */
    @Test
    fun anErroredSessionRendersRedAndClearsWhenItSpeaksAgain() {
        fun modelAfter(vararg frames: SseFrame): HaloModel {
            val state = frames.fold(BridgeState()) { acc, frame ->
                (BridgeEventReducer.reduce(acc, frame, 1_000L) as BridgeEventReducer.Applied).state
            }
            return HaloModel.from(UiState(bridge = state))
        }
        val running = SseFrame(null, "session", """{"state":"running","agent":"claude","cwd":"/home/dev/proj","folderName":"proj","sessionId":"A"}""")
        val errored = SseFrame(null, "error", """{"error":"boom","sessionId":"A"}""")

        assertEquals(
            Halo.SessionState.ERROR,
            modelAfter(running, errored).sessions.single().state,
        )
        // Red outranks RUNNING: the session never left WORKING when it broke,
        // and painting it green would bury the one thing worth surfacing.
        assertEquals(
            Halo.SessionState.RUNNING,
            modelAfter(running).sessions.single().state,
        )
        // Speaking again clears it.
        assertEquals(
            Halo.SessionState.RUNNING,
            modelAfter(
                running,
                errored,
                SseFrame(null, "message", """{"role":"assistant","text":"carrying on","sessionId":"A"}"""),
            ).sessions.single().state,
        )
    }

    /** A BLOCKING prompt still outranks red — the card is the way out of it. */
    @Test
    fun aPendingPromptOutranksTheErroredRed() {
        val model = HaloModel.from(
            UiState(
                bridge = BridgeState(
                    sessions = mapOf("A" to session("A").copy(errored = true)),
                ),
                permissionQueue = listOf(
                    PendingPermission(
                        permissionId = "p-1",
                        sessionId = "A",
                        toolName = "Bash",
                        requestSummary = "run it",
                        sessionLabel = "proj",
                        options = listOf(PermissionOption("allow", "Yes")),
                    ),
                ),
            ),
        )
        assertEquals(Halo.SessionState.WAITING_PERM, model.sessions.single().state)
    }

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

    /** Issue #78: the wire `dictatable`/`kind` discriminator is threaded onto the
     *  HaloSession so the feed can pick the Dictate pill vs the honest
     *  "unavailable" affordance by it — independently of `external` (an ACP
     *  session is both external AND dictatable). */
    @Test
    fun dictatableAndKindAreThreadedOntoTheHaloSession() {
        val model = HaloModel.from(
            uiState(
                session("s-pty", external = false, dictatable = true, kind = null),
                session("s-hook", external = true, dictatable = false, kind = null),
                session("s-acp", external = true, dictatable = true, kind = "acp"),
            ),
        )
        val pty = model.sessions.single { it.id == "s-pty" }
        assertTrue("a bridge-owned PTY session is dictatable", pty.dictatable)

        val hook = model.sessions.single { it.id == "s-hook" }
        assertFalse("a PTY-less hook session is not dictatable", hook.dictatable)

        val acp = model.sessions.single { it.id == "s-acp" }
        assertTrue("an ACP session is dictatable", acp.dictatable)
        assertTrue("an ACP session is still external (hide, not kill)", acp.external)
        assertEquals("acp", acp.kind)
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

    /**
     * Issue #60, end to end through the layer that actually shows the colour.
     * The live bug was reported as "green", not as "activity == WORKING": a
     * session idled before the watch connected arrived in the connect-time
     * snapshot, the reducer created it WORKING, and Halo painted it RUNNING.
     * Drive a real snapshot frame through the reducer and assert the DOT, so
     * a regression anywhere along that path is caught where it was seen.
     */
    @Test
    fun aSnapshotSessionFlaggedIdleRendersIdleNotRunning() {
        fun modelFor(payload: String): HaloModel {
            val applied = BridgeEventReducer.reduce(
                BridgeState(),
                SseFrame(null, "session", payload),
                1_000L,
            ) as BridgeEventReducer.Applied
            return HaloModel.from(UiState(bridge = applied.state))
        }

        val idled = modelFor(
            """{"state":"running","agent":"claude","cwd":"/home/dev/claypot","folderName":"claypot","external":true,"idle":true,"sessionId":"A"}""",
        )
        assertEquals(
            "a session the bridge reports idle must not render as running",
            Halo.SessionState.IDLE,
            idled.sessions.single().state,
        )

        // The flagless snapshot (working session, or an older bridge) keeps
        // rendering RUNNING — the deliberate default, so genuinely-live
        // sessions never go grey on a reconnect.
        val working = modelFor(
            """{"state":"running","agent":"claude","cwd":"/home/dev/claypot","folderName":"claypot","external":true,"sessionId":"A"}""",
        )
        assertEquals(Halo.SessionState.RUNNING, working.sessions.single().state)
    }

    /**
     * #60's defence in depth, at the same layer: the closing `session-sync` of
     * a connect-time snapshot (#66) carries the bridge's turn-state verdict for
     * every slot it lists, and an entry with NO verdict means the bridge has
     * observed no turn signal at all. That is not a claim of work — so the dot
     * goes grey, not green, even though the `session` frame that preceded it
     * carried no `idle` flag and created the session WORKING.
     */
    @Test
    fun aSnapshotSessionTheBridgeCannotVouchForRendersIdleNotRunning() {
        fun modelAfterSnapshot(syncEntry: String): HaloModel {
            val frames = listOf(
                SseFrame(null, "session", """{"state":"running","agent":"claude","cwd":"/home/dev/claypot","folderName":"claypot","external":true,"sessionId":"A"}"""),
                SseFrame(null, "session-sync", """{"sessions":[$syncEntry],"complete":true}"""),
            )
            val state = frames.fold(BridgeState()) { acc, frame ->
                (BridgeEventReducer.reduce(acc, frame, 1_000L) as BridgeEventReducer.Applied).state
            }
            return HaloModel.from(UiState(bridge = state))
        }

        assertEquals(
            "a session the bridge cannot vouch for must not render green",
            Halo.SessionState.IDLE,
            modelAfterSnapshot("""{"id":"A"}""").sessions.single().state,
        )
        // ...and a sync that says a turn IS in flight paints it green, which is
        // what keeps the defence from becoming the opposite bug.
        assertEquals(
            Halo.SessionState.RUNNING,
            modelAfterSnapshot("""{"id":"A","idle":false}""").sessions.single().state,
        )
    }

    /**
     * The #60 follow-up: "the turn ended" and "nothing is happening" are NOT
     * the same claim. A session that yields its turn while a workflow's
     * subagents keep running is neither RUNNING (it will not answer you) nor
     * IDLE (work is in flight) — it is DELEGATED, its own colour. Before
     * this, the state derivation ignored `agents` entirely, so every session
     * went grey the instant its main loop stopped, however large the fleet it
     * had just launched — the exact stretch the watch exists to report on.
     *
     * Sabotage that this catches: delete the `agents.running > 0` branch and
     * the idled-with-a-fleet row reverts to IDLE; drop it BELOW the WORKING
     * branch (its pre-#67 position) and the working-with-a-fleet row reverts to
     * RUNNING — the exact green-forever bug #67 fixed.
     */
    @Test
    fun aStoppedSessionWithRunningSubagentsIsDelegatedNotIdleOrRunning() {
        fun stateOf(activity: SessionActivity, running: Int, done: Int = 0): Halo.SessionState =
            HaloModel.from(
                uiState(
                    session("s-1", agents = AgentsActivity(running = running, done = done))
                        .copy(activity = activity),
                ),
            ).sessions.single().state

        // Main loop stopped, fleet still running → the new blue state.
        assertEquals(
            "a yielded turn with subagents in flight is delegated, not idle",
            Halo.SessionState.DELEGATED,
            stateOf(SessionActivity.IDLE, running = 3),
        )
        // The fleet finishes: nothing is left in flight → genuinely idle.
        assertEquals(
            "agents at zero means the session really is idle",
            Halo.SessionState.IDLE,
            stateOf(SessionActivity.IDLE, running = 0, done = 3),
        )
        // Delegation outranks a working main loop (issue #67): a running
        // fleet paints blue even if the main loop also reports WORKING, because
        // in practice a workflow holds the turn open (the Stop that would set
        // idle never fires), so "WORKING + fleet" IS the delegated case and
        // would otherwise be stuck green forever. Any subagents → blue.
        assertEquals(
            "a running fleet outranks a working main loop — delegated, not running",
            Halo.SessionState.DELEGATED,
            stateOf(SessionActivity.WORKING, running = 3),
        )
    }

    /** A pending prompt outranks delegation: needing you is the top signal. */
    @Test
    fun aPendingPromptOutranksDelegation() {
        val model = HaloModel.from(
            uiState(
                session("s-1", agents = AgentsActivity(running = 2))
                    .copy(activity = SessionActivity.IDLE),
            ).copy(
                permissionQueue = listOf(
                    PendingPermission(
                        permissionId = "p-1",
                        sessionId = "s-1",
                        toolName = "Bash",
                        requestSummary = "rm -rf ./build",
                        sessionLabel = "proj",
                        options = emptyList(),
                    ),
                ),
            ),
        )
        assertEquals(
            "a prompt waiting on the user must not be masked by subagent activity",
            Halo.SessionState.WAITING_PERM,
            model.sessions.single().state,
        )
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

    /** Issue #56: per-session spawn root — the MAIN repoRoot beats cwd, so a
     *  worktree session offers the main checkout, never its worktree dir. */
    @Test
    fun spawnRootPrefersTheMainRepoRootOverCwd() {
        val model = HaloModel.from(
            uiState(
                session(
                    "s-wt",
                    folderName = "alpha-issue-53",
                    cwd = "/home/dev/worktrees/alpha-issue-53",
                    branch = "issue-53-fix",
                    worktree = true,
                    repoRoot = "/home/dev/alpha",
                ),
                session("s-plain", folderName = "beta", cwd = "/home/dev/beta"),
            ),
        )
        assertEquals("/home/dev/alpha", model.sessions.single { it.id == "s-wt" }.spawnRoot)
        assertEquals("/home/dev/beta", model.sessions.single { it.id == "s-plain" }.spawnRoot)
    }

    /** Issue #56: spawnTargets — one entry per known project, root from the
     *  first session that knows one; a project whose ONLY session is a
     *  worktree still offers the MAIN root. */
    @Test
    fun spawnTargetsOfferOneEntryPerProjectWithTheWorktreesMainRoot() {
        val model = HaloModel.from(
            uiState(
                // alpha is represented ONLY by its worktree session: the
                // picker must offer /home/dev/alpha, not the worktree dir.
                session(
                    "s-wt",
                    folderName = "alpha-issue-53",
                    cwd = "/home/dev/worktrees/alpha-issue-53",
                    branch = "issue-53-fix",
                    worktree = true,
                    repoRoot = "/home/dev/alpha",
                ),
                session("s-b1", folderName = "beta", cwd = "/home/dev/beta"),
                session("s-b2", folderName = "beta", cwd = "/home/dev/beta"),
            ),
        )
        assertEquals(
            listOf(
                SpawnTarget("alpha", "/home/dev/alpha"),
                SpawnTarget("beta", "/home/dev/beta"),
            ),
            model.spawnTargets,
        )
    }

    /** Issue #56: a project whose sessions know neither repoRoot nor cwd (a
     *  queue-orphan synthetic) is SKIPPED — no target beats a lying one that
     *  would spawn in the bridge's own cwd. */
    @Test
    fun projectsWithoutAnyKnownRootOfferNoSpawnTarget() {
        val orphanPrompt = PendingPermission(
            permissionId = "perm-orphan",
            sessionId = null,
            toolName = "Bash",
            requestSummary = "$ make",
            sessionLabel = "ghost",
            options = emptyList(),
        )
        val model = HaloModel.from(
            UiState(
                bridge = BridgeState(
                    sessions = mapOf(
                        "s-known" to session("s-known", folderName = "proj", cwd = "/home/dev/proj"),
                    ),
                ),
                permissionQueue = listOf(orphanPrompt),
            ),
        )
        // The orphan still renders (its own project group)…
        assertEquals(2, model.projectCount)
        // …but only the real project is spawnable.
        assertEquals(listOf(SpawnTarget("proj", "/home/dev/proj")), model.spawnTargets)
    }

    /**
     * Halo v2 S1: ONE session order everywhere. The bridge interleaves
     * projects freely (alpha, beta, alpha), and the All ring used to draw
     * that insertion order while the list grouped by project — two orders
     * that diverge the moment projects interleave, so the pager's ring
     * highlight could sit on the wrong segment. The flat list is now the
     * project flatten BY CONSTRUCTION — a deliberate, user-visible All-ring
     * reorder for interleaved-project bridges.
     */
    @Test
    fun theFlatSessionListIsProjectGroupedByConstruction() {
        val model = HaloModel.from(
            uiState(
                session("s-a1", folderName = "alpha", cwd = "/home/dev/alpha"),
                session("s-b1", folderName = "beta", cwd = "/home/dev/beta"),
                session("s-a2", folderName = "alpha", cwd = "/home/dev/alpha"),
            ),
        )
        // Bridge insertion order was a1, b1, a2; project grouping (first-seen
        // project order) regroups a2 next to its sibling.
        assertEquals(listOf("s-a1", "s-a2", "s-b1"), model.sessions.map { it.id })
        // Ring (model.sessions), pager (sessionsIn(All)), and grouped list
        // (projects flattened) are the SAME list by construction — assert the
        // identities, not three hand-copied orders.
        assertEquals(model.sessions, model.sessionsIn(ListScope.All))
        assertEquals(model.projects.flatMap { it.sessions }, model.sessions)
        // A project scope is exactly its own group.
        assertEquals(
            model.projects.single { it.name == "beta" }.sessions,
            model.sessionsIn(ListScope.Project("beta")),
        )
    }

    /** Halo v2 S9 (#102): the session-meta trio reaches the HaloSession in
     *  DISPLAY form — model prefix-stripped, mode through the short-label
     *  map, use% copied by presence — so the S5 subheading just prints. */
    @Test
    fun sessionMetaIsThreadedOntoTheHaloSessionInDisplayForm() {
        val model = HaloModel.from(
            uiState(
                session("s-acp", kind = "acp", model = "Claude Opus 4.6", mode = "acceptEdits", contextPct = 85),
                session("s-fresh", kind = "acp", model = "Sonnet", mode = "default", contextPct = 0),
                session("s-pty"),
            ),
        )
        val acp = model.sessions.single { it.id == "s-acp" }
        assertEquals("Opus 4.6", acp.modelName)
        assertEquals("edits", acp.modeName)
        assertEquals(85, acp.usePercent)

        // Presence, never truthiness: a fresh session's real 0% survives to
        // the card instead of vanishing as falsy.
        val fresh = model.sessions.single { it.id == "s-fresh" }
        assertEquals("Sonnet", fresh.modelName)
        assertEquals("manual", fresh.modeName)
        assertEquals(0, fresh.usePercent)

        // No wire fields (PTY/hook session): all three stay null, so the card
        // renders a clean empty row — absence never becomes a guess.
        val pty = model.sessions.single { it.id == "s-pty" }
        assertEquals(null, pty.modelName)
        assertEquals(null, pty.modeName)
        assertEquals(null, pty.usePercent)
    }

    /** Halo v2 S9 (#102): the mode label table, id → wrist word. */
    @Test
    fun modeLabelMapsTheKnownAcpIdsAndPassesUnknownOnesVerbatim() {
        assertEquals("manual", modeLabel("default"))
        assertEquals("plan", modeLabel("plan"))
        assertEquals("edits", modeLabel("acceptEdits"))
        assertEquals("bypass", modeLabel("bypassPermissions"))
        assertEquals("no-ask", modeLabel("dontAsk"))
        // The vocabulary is the agent's and may grow: an unknown id shows
        // itself rather than hiding the mode.
        assertEquals("yolo", modeLabel("yolo"))
    }

    /** Halo v2 S9 (#102): only a LEADING "Claude " strips; everything else —
     *  short names, raw third-party ids — renders verbatim. */
    @Test
    fun modelDisplayNameStripsOnlyALeadingClaudePrefix() {
        assertEquals("Opus 4.6", modelDisplayName("Claude Opus 4.6"))
        assertEquals("Opus", modelDisplayName("Opus"))
        assertEquals("Default", modelDisplayName("Default"))
        assertEquals("gpt-5o-mini", modelDisplayName("gpt-5o-mini"))
        assertEquals("Not Claude Model", modelDisplayName("Not Claude Model"))
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
