package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.protocol.AgentPermissionOption
import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rich ACP option list on the approval card (issue #110): a prompt whose
 * agent offered several same-behavior options (Zed's ExitPlanMode carries up
 * to three allow_always mode switches) renders the AGENT's own options, and a
 * tap answers with that option's exact optionId — the canonical trio's
 * behavior-keyed answer could not say WHICH mode switch was meant, and on the
 * live wrist that election silently switched the session's permission mode.
 * Also pins the flip side: a payload WITHOUT agentOptions keeps today's
 * canonical card, and "decide later" survives on the rich card unchanged.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalOptionCardTest {

    @get:Rule
    val compose = createComposeRule()

    /** The adapter's ExitPlanMode option list, as the bridge forwards it —
     *  bypass FIRST (the adapter unshifts it), the worst-case wire order the
     *  card must defuse. */
    private val planOptions = listOf(
        AgentPermissionOption("bypassPermissions", "Yes, and bypass permissions", "allow_always"),
        AgentPermissionOption("auto", "Yes, and use \"auto\" mode", "allow_always"),
        AgentPermissionOption("acceptEdits", "Yes, and auto-accept edits", "allow_always"),
        AgentPermissionOption("default", "Yes, and manually approve edits", "allow_once"),
        AgentPermissionOption("plan", "No, keep planning", "reject_once"),
    )

    /** The guarded canonical menu that rides with it (ambiguous allow-always dropped). */
    private val guardedOptions = listOf(
        PermissionOption("allow", "Yes, and manually approve edits"),
        PermissionOption("deny", "No, keep planning"),
    )

    private val planPrompt = BridgeViewModel.PendingPermission(
        permissionId = "perm-plan",
        sessionId = "s-1",
        // Production's wire tool_name for a plan approval is the display
        // TITLE, not "ExitPlanMode" — the raw name never reaches the bridge.
        toolName = "Ready to code?",
        requestSummary = "[Ready to code?]",
        sessionLabel = "alpha",
        options = guardedOptions,
        agentOptions = planOptions,
        planText = "## Fix the card\n\nRender the **plan** with `ProseMarkdown`.",
    )

    private val simplePrompt = BridgeViewModel.PendingPermission(
        permissionId = "perm-bash",
        sessionId = "s-1",
        toolName = "Bash",
        requestSummary = "$ rm -rf ./build",
        sessionLabel = "alpha",
        options = listOf(
            PermissionOption("allow", "Yes", "Allow this once"),
            PermissionOption("deny", "No", "Deny this request"),
        ),
    )

    private val fixtureSessions = BridgeState(
        sessions = mapOf(
            "s-1" to SessionState(
                sessionId = "s-1",
                agent = "claude",
                cwd = "/home/dev/alpha",
                folderName = "alpha",
            ),
        ),
    )

    private fun ui(queue: List<BridgeViewModel.PendingPermission>) = BridgeViewModel.UiState(
        status = "paired, stream open",
        paired = true,
        bridge = fixtureSessions,
        permissionQueue = queue,
    )

    private fun openCard() {
        compose.onNodeWithTag("haloAnswerPill").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        // The cards ignore taps for ~400ms after appearing (see ARM_DELAY_MS).
        Thread.sleep(500)
    }

    /** Matches every agent-option pill, in the tree's (= Column's) order. */
    private val anyAgentOption = SemanticsMatcher("any agent option pill") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("haloAgentOption-") == true
    }

    @Test
    fun richPromptRendersEveryAgentOptionAndNoCanonicalButtons() {
        var state by mutableStateOf(ui(listOf(planPrompt)))
        compose.setContent { HaloApp(ui = state, actions = HaloActions()) }
        openCard()

        // Every one of the agent's options is on the card, in the AGENT'S OWN
        // order — the same order Zed shows (user-directed, 2026-08-21, after
        // two field rounds against the old rank-sort: "the options are the
        // wrong way round"). The earlier bypass-dead-last reseat (#110's
        // review call) is REVOKED: cross-device muscle memory beats the
        // mis-tap theory, and kind styling still makes every standing grant
        // unmistakable. (Tree order, not pixel bounds: below-the-fold clips.)
        assertEquals(
            listOf("bypassPermissions", "auto", "acceptEdits", "default", "plan")
                .map { "haloAgentOption-$it" },
            compose.onAllNodes(anyAgentOption).fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.TestTag] },
        )
        // The canonical buttons are GONE: rendering both would be two answers
        // for one decision, and the behavior-keyed trio is exactly the lossy
        // surface this card replaces.
        listOf("haloApprove", "haloDeny", "haloAlwaysAllow").forEach { tag ->
            assertEquals(
                "canonical button $tag must not render on a rich prompt",
                0,
                compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size,
            )
        }
    }

    @Test
    fun planCardRendersTheReadablePlanInsteadOfTheBracketSummary() {
        var state by mutableStateOf(ui(listOf(planPrompt)))
        compose.setContent { HaloApp(ui = state, actions = HaloActions()) }
        openCard()

        // The well holds the PLAN, rendered (markers consumed by #128's
        // prose pipeline — "plan" appears bold, the backticks are gone), not
        // the useless "[Ready to code?]" bracket summary.
        compose.onNodeWithTag("haloPlanBody").assertIsDisplayed()
        compose.onNodeWithTag("haloPlanBody").assert(
            hasText("Fix the card", substring = true),
        )
        compose.onNodeWithTag("haloPlanBody").assert(
            !hasText("**plan**", substring = true) and !hasText("`ProseMarkdown`", substring = true),
        )
        assertEquals(
            "the bracket summary must not render on a plan card",
            0,
            compose.onAllNodes(hasTestTag("haloSummary")).fetchSemanticsNodes().size,
        )
        // The options still render below it, agent order intact.
        assertEquals(
            5,
            compose.onAllNodes(anyAgentOption).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun tappingAnOptionAnswersWithThatExactOptionId() {
        val optionAnswers = mutableListOf<Pair<String, AgentPermissionOption>>()
        val behaviorAnswers = mutableListOf<Pair<String, String>>()
        var state by mutableStateOf(ui(listOf(planPrompt)))
        compose.setContent {
            HaloApp(
                ui = state,
                actions = HaloActions(
                    onAnswerPermission = { id, behavior -> behaviorAnswers += id to behavior },
                    onAnswerOption = { id, option -> optionAnswers += id to option },
                ),
            )
        }
        openCard()

        compose.onNodeWithTag("haloAgentOption-acceptEdits").performScrollTo().performClick()
        assertEquals(
            "the tap must answer with the tapped option, keyed to the rendered card",
            listOf("perm-plan" to planOptions.first { it.optionId == "acceptEdits" }),
            optionAnswers,
        )
        assertEquals("no behavior-keyed answer may ride along", emptyList<Pair<String, String>>(), behaviorAnswers)

        // The 2xx ack resolves the prompt: an allow-kind tap flashes Approved
        // — the shared resolution mechanics of the canonical card, unchanged.
        state = ui(emptyList()).copy(decisionForId = "perm-plan", decisionResult = "decision:200")
        compose.waitForIdle()
        compose.onNodeWithTag("haloResultFlash").assertIsDisplayed()
        compose.onNodeWithText("Approved").assertIsDisplayed()
    }

    @Test
    fun decideLaterSurvivesOnTheRichCardAndSendsNothing() {
        val optionAnswers = mutableListOf<Pair<String, AgentPermissionOption>>()
        var state by mutableStateOf(ui(listOf(planPrompt)))
        compose.setContent {
            HaloApp(
                ui = state,
                actions = HaloActions(
                    onAnswerOption = { id, option -> optionAnswers += id to option },
                ),
            )
        }
        openCard()

        compose.onNodeWithTag("haloDecideLater").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            "decide later must close the card without a decision",
            0,
            compose.onAllNodes(hasTestTag("haloCard")).fetchSemanticsNodes().size,
        )
        assertEquals(emptyList<Pair<String, AgentPermissionOption>>(), optionAnswers)
    }

    @Test
    fun simplePayloadKeepsTodaysCanonicalCard() {
        var state by mutableStateOf(ui(listOf(simplePrompt)))
        compose.setContent { HaloApp(ui = state, actions = HaloActions()) }
        openCard()

        compose.onNodeWithTag("haloApprove").assertIsDisplayed()
        compose.onNodeWithTag("haloDeny").assertIsDisplayed()
        assertEquals(
            "no agent-option pill may render without agentOptions",
            0,
            compose.onAllNodes(anyAgentOption).fetchSemanticsNodes().size,
        )
    }
}
