package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.wear.ui.LOCAL_DISMISS_AFTER_FAILURES
import dev.claudewatch.wear.ui.SessionPagerActions
import dev.claudewatch.wear.ui.SessionPagerScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The approval sheet (issue #17), driven with fixture UiStates — no bridge,
 * no network. Covers the sheet as the single presenter over the pager: WHAT
 * (tool + input summary) and WHICH session, answers keyed to the RENDERED
 * card's permissionId, gesture-undismissable, error surfaced without
 * dropping the prompt.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val defaultOptions = listOf(
        PermissionOption("allow", "Yes", "Allow this once"),
        PermissionOption("deny", "No", "Deny this request"),
    )

    private val bashPrompt = BridgeViewModel.PendingPermission(
        permissionId = "perm-bash",
        sessionId = "s-1",
        toolName = "Bash",
        requestSummary = "$ rm -rf ./build",
        sessionLabel = "alpha",
        options = defaultOptions,
    )

    private val writePrompt = BridgeViewModel.PendingPermission(
        permissionId = "perm-write",
        sessionId = "s-2",
        toolName = "Write",
        requestSummary = "Write notes.txt",
        sessionLabel = "beta",
        options = defaultOptions,
    )

    private fun ui(
        queue: List<BridgeViewModel.PendingPermission>,
        inFlightId: String? = null,
        error: String? = null,
        failureCount: Int = 0,
    ) = BridgeViewModel.UiState(
        status = "paired, stream open",
        permissionQueue = queue,
        decisionInFlightId = inFlightId,
        decisionError = error,
        decisionFailureCount = failureCount,
    )

    @Test
    fun sheetShowsWhatIsAskedWhichSessionAsksAndTheQueueDepth() {
        val answers = mutableListOf<Pair<String, String>>()
        // Newest-first queue: the write prompt arrived last and is rendered.
        var state by mutableStateOf(ui(listOf(writePrompt, bashPrompt)))
        compose.setContent {
            SessionPagerScreen(
                ui = state,
                actions = SessionPagerActions(
                    onAnswerPermission = { id, behavior -> answers += id to behavior },
                ),
            )
        }

        compose.onNodeWithTag("permissionSheet").assertIsDisplayed()
        compose.onNodeWithTag("permissionTool").assertIsDisplayed()
        compose.onNodeWithText("Write").assertIsDisplayed()
        // WHAT is being asked...
        compose.onNodeWithText("Write notes.txt").assertIsDisplayed()
        // ...by WHICH session...
        compose.onNodeWithText("beta").assertIsDisplayed()
        // ...and how many more are waiting behind it.
        compose.onNodeWithText("1 more waiting").assertIsDisplayed()

        // Answering is keyed to the RENDERED card's permissionId.
        compose.onNodeWithTag("permissionOption-deny").performClick()
        assertEquals(listOf("perm-write" to "deny"), answers)

        // Ack arrives (ViewModel would shrink the queue): the next card fronts.
        state = ui(listOf(bashPrompt))
        compose.waitForIdle()
        compose.onNodeWithText("$ rm -rf ./build").assertIsDisplayed()
        compose.onNodeWithText("alpha").assertIsDisplayed()
        compose.onNodeWithTag("permissionOption-allow").performClick()
        assertEquals(listOf("perm-write" to "deny", "perm-bash" to "allow"), answers)

        // Queue empty: the sheet is gone, the pager is back in charge.
        state = ui(emptyList())
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasTestTag("permissionSheet")).fetchSemanticsNodes().size)
    }

    @Test
    fun sheetCannotBeSwipeDismissedIntoADroppedApproval() {
        val answers = mutableListOf<Pair<String, String>>()
        compose.setContent {
            SessionPagerScreen(
                ui = ui(listOf(bashPrompt)),
                actions = SessionPagerActions(
                    onAnswerPermission = { id, behavior -> answers += id to behavior },
                ),
            )
        }
        compose.onNodeWithTag("permissionSheet").assertIsDisplayed()

        // Every direction of swipe: the sheet stays put — there is no gesture
        // path that drops an approval into a 10-minute auto-deny.
        compose.onNodeWithTag("permissionSheet").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("permissionSheet").assertIsDisplayed()
        compose.onNodeWithTag("permissionSheet").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("permissionSheet").assertIsDisplayed()
        compose.onNodeWithTag("permissionSheet").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithTag("permissionSheet").assertIsDisplayed()
        compose.onNodeWithTag("permissionSheet").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("permissionSheet").assertIsDisplayed()

        // And the prompt is still answerable after all that swiping.
        compose.onNodeWithTag("permissionOption-allow").performClick()
        assertEquals(listOf("perm-bash" to "allow"), answers)
    }

    @Test
    fun failedAnswerKeepsTheSheetWithTheErrorSurfaced() {
        // The ViewModel reported a failed POST: prompt still queued, error set.
        var state by mutableStateOf(ui(listOf(bashPrompt), error = "Decision failed: HTTP 500"))
        val answers = mutableListOf<Pair<String, String>>()
        compose.setContent {
            SessionPagerScreen(
                ui = state,
                actions = SessionPagerActions(
                    onAnswerPermission = { id, behavior -> answers += id to behavior },
                ),
            )
        }

        compose.onNodeWithTag("permissionSheet").assertIsDisplayed()
        compose.onNodeWithTag("permissionError").assertIsDisplayed()
        compose.onNodeWithText("Decision failed: HTTP 500").assertIsDisplayed()

        // Retry from the restored card still lands on the same permissionId.
        compose.onNodeWithTag("permissionOption-allow").performClick()
        assertEquals(listOf("perm-bash" to "allow"), answers)

        // While the retry is in flight the buttons are disabled and the
        // in-flight marker shows — no double-answers, no premature dismissal.
        state = ui(listOf(bashPrompt), inFlightId = "perm-bash")
        compose.waitForIdle()
        compose.onNodeWithTag("permissionSending").assertIsDisplayed()
        compose.onNodeWithTag("permissionOption-allow").performClick()
        assertEquals("clicks while in flight must not re-answer", 1, answers.size)
    }

    /**
     * The availability escape hatch: an unreachable/restarted bridge must not
     * wedge the whole app behind the undismissable sheet. After
     * [LOCAL_DISMISS_AFTER_FAILURES] consecutive failed answers a "Dismiss"
     * button appears that drops the card locally and sends NO decision —
     * before that threshold it must NOT exist (a one-blip dismissal would
     * reintroduce the swipe-into-auto-deny defect class by another door).
     */
    @Test
    fun dismissEscapeHatchAppearsOnlyAfterRepeatedFailuresAndSendsNoAnswer() {
        val answers = mutableListOf<Pair<String, String>>()
        val dismissals = mutableListOf<String>()
        var state by mutableStateOf(
            ui(listOf(bashPrompt), error = "Decision failed: HTTP 500", failureCount = 1),
        )
        compose.setContent {
            SessionPagerScreen(
                ui = state,
                actions = SessionPagerActions(
                    onAnswerPermission = { id, behavior -> answers += id to behavior },
                    onDismissPermission = { id -> dismissals += id },
                ),
            )
        }

        // One failure: error surfaced, but no dismiss — retry is the only path.
        compose.onNodeWithTag("permissionError").assertIsDisplayed()
        assertEquals(
            "dismiss must not be offered below the failure threshold",
            0,
            compose.onAllNodes(hasTestTag("permissionDismiss")).fetchSemanticsNodes().size,
        )

        // Threshold reached: the explicit no-decision dismiss appears.
        state = ui(
            listOf(bashPrompt),
            error = "Decision failed: HTTP 500",
            failureCount = LOCAL_DISMISS_AFTER_FAILURES,
        )
        compose.waitForIdle()
        compose.onNodeWithTag("permissionDismiss").assertIsDisplayed().performClick()
        assertEquals(listOf("perm-bash"), dismissals)
        assertEquals("dismiss must never send an answer", emptyList<Pair<String, String>>(), answers)

        // The ViewModel dropped the card: the sheet leaves, the app is usable.
        state = ui(emptyList())
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasTestTag("permissionSheet")).fetchSemanticsNodes().size)
    }

    @Test
    fun allowAlwaysOptionRendersFromTheBehaviorFieldAndAnswersWithIt() {
        val answers = mutableListOf<Pair<String, String>>()
        val withAlways = bashPrompt.copy(
            permissionId = "perm-always",
            options = listOf(
                PermissionOption("allow", "Yes", "Allow this once"),
                PermissionOption("allow-always", "Yes, don't ask again", "Allow and apply the suggested permission rules"),
                PermissionOption("deny", "No", "Deny this request"),
            ),
        )
        compose.setContent {
            SessionPagerScreen(
                ui = ui(listOf(withAlways)),
                actions = SessionPagerActions(
                    onAnswerPermission = { id, behavior -> answers += id to behavior },
                ),
            )
        }

        // The button is keyed by the machine-readable behavior, not its label.
        compose.onNodeWithTag("permissionOption-allow-always").assertIsDisplayed().performClick()
        assertEquals(listOf("perm-always" to "allow-always"), answers)
    }
}
