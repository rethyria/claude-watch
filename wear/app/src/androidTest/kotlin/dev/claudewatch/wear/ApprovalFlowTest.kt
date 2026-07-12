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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.protocol.AskUserQuestion
import dev.claudewatch.shared.protocol.AskUserQuestionOption
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

    // ------------------------------------------------------------------
    // AskUserQuestion card (issue #18)
    // ------------------------------------------------------------------

    private val askPrompt = BridgeViewModel.PendingPermission(
        permissionId = "perm-ask",
        sessionId = "s-1",
        toolName = "AskUserQuestion",
        requestSummary = "[AskUserQuestion]",
        sessionLabel = "alpha",
        options = emptyList(),
        questions = listOf(
            AskUserQuestion(
                question = "Which color scheme?",
                header = "Color",
                options = listOf(
                    AskUserQuestionOption("Blue"),
                    AskUserQuestionOption("Green"),
                ),
            ),
            AskUserQuestion(
                question = "Tabs or spaces?",
                header = "Indent",
                options = listOf(
                    AskUserQuestionOption("Tabs"),
                    AskUserQuestionOption("Spaces"),
                ),
            ),
        ),
    )

    /**
     * Issue #18 acceptance: a multi-question payload renders EVERY question
     * (the legacy client silently answered only the first), Send stays
     * disabled until each has an answer, and the sent answers are positional
     * — one per question in the payload's question order, the array form the
     * bridge zips back onto the blocked hook's questions.
     */
    @Test
    fun multiQuestionCardRendersEveryQuestionAndSendsAllAnswers() {
        val sent = mutableListOf<Pair<String, List<String>>>()
        compose.setContent {
            SessionPagerScreen(
                ui = ui(listOf(askPrompt)),
                actions = SessionPagerActions(
                    onAnswerQuestions = { id, answers -> sent += id to answers },
                ),
            )
        }

        // The same single-presenter sheet, in question mode: which session
        // asks, every question, each question's own options.
        compose.onNodeWithTag("permissionSheet").assertIsDisplayed()
        compose.onNodeWithText("alpha").assertIsDisplayed()
        compose.onNodeWithTag("questionText-0").assertIsDisplayed()
        compose.onNodeWithText("Which color scheme?").assertIsDisplayed()
        compose.onNodeWithTag("questionText-1").performScrollTo()
        compose.onNodeWithText("Tabs or spaces?").assertIsDisplayed()

        // Half-answered: Send must not fire — a partial answer set would
        // silently drop the unanswered question's hook answer.
        compose.onNodeWithTag("questionOption-0-Blue").performScrollTo().performClick()
        compose.onNodeWithTag("questionsSend").performScrollTo().performClick()
        assertEquals("send must be gated until every question is answered", 0, sent.size)

        // Fully answered: one POST payload with BOTH answers in question
        // order, keyed to the rendered card's permissionId.
        compose.onNodeWithTag("questionOption-1-Spaces").performScrollTo().performClick()
        compose.onNodeWithTag("questionsSend").performScrollTo().performClick()
        assertEquals(
            listOf("perm-ask" to listOf("Blue", "Spaces")),
            sent,
        )
    }

    /** Issue #18 acceptance: the free-text path answers a question with typed text. */
    @Test
    fun freeTextAnswerIsSentForItsQuestion() {
        val sent = mutableListOf<Pair<String, List<String>>>()
        compose.setContent {
            SessionPagerScreen(
                ui = ui(listOf(askPrompt)),
                actions = SessionPagerActions(
                    onAnswerQuestions = { id, answers -> sent += id to answers },
                ),
            )
        }

        // Question 0 by option, question 1 by typed free text.
        compose.onNodeWithTag("questionOption-0-Green").performScrollTo().performClick()
        compose.onNodeWithTag("questionFreeText-1").performScrollTo().performTextInput("two-space soft tabs")
        compose.onNodeWithTag("questionsSend").performScrollTo().performClick()
        assertEquals(
            listOf("perm-ask" to listOf("Green", "two-space soft tabs")),
            sent,
        )
    }

    /** A multiSelect question toggles options and joins the picks in option order. */
    @Test
    fun multiSelectQuestionJoinsToggledOptionsInOptionOrder() {
        val sent = mutableListOf<Pair<String, List<String>>>()
        val multi = askPrompt.copy(
            permissionId = "perm-multi",
            questions = listOf(
                AskUserQuestion(
                    question = "Which linters?",
                    header = "Lint",
                    options = listOf(
                        AskUserQuestionOption("ktlint"),
                        AskUserQuestionOption("detekt"),
                        AskUserQuestionOption("android-lint"),
                    ),
                    multiSelect = true,
                ),
            ),
        )
        compose.setContent {
            SessionPagerScreen(
                ui = ui(listOf(multi)),
                actions = SessionPagerActions(
                    onAnswerQuestions = { id, answers -> sent += id to answers },
                ),
            )
        }

        // Toggle in REVERSE order, and toggle one off again: the answer joins
        // the still-selected labels in the question's option order.
        compose.onNodeWithTag("questionOption-0-android-lint").performScrollTo().performClick()
        compose.onNodeWithTag("questionOption-0-detekt").performScrollTo().performClick()
        compose.onNodeWithTag("questionOption-0-ktlint").performScrollTo().performClick()
        compose.onNodeWithTag("questionOption-0-android-lint").performScrollTo().performClick()
        compose.onNodeWithTag("questionsSend").performScrollTo().performClick()
        assertEquals(
            listOf("perm-multi" to listOf("ktlint, detekt")),
            sent,
        )
    }

    /**
     * Regression (review finding): a payload whose questions share the SAME
     * question text must still be fully answerable. Completeness is gated by
     * question index — a text-keyed answer map collapses duplicate texts to
     * one entry, so Send could never enable and the swipe-immune sheet would
     * deadlock with no escape hatch (the local dismiss only unlocks after
     * failed POSTs, which can't happen if Send never fires). The positional
     * answer array carries each duplicate's own answer, aligned by index.
     */
    @Test
    fun duplicateQuestionTextsStillCountSeparatelyAndSendPositionalAnswers() {
        val sent = mutableListOf<Pair<String, List<String>>>()
        val duplicated = askPrompt.copy(
            permissionId = "perm-dup",
            questions = listOf(
                AskUserQuestion(
                    question = "Proceed?",
                    header = "Step 1",
                    options = listOf(AskUserQuestionOption("Yes"), AskUserQuestionOption("No")),
                ),
                AskUserQuestion(
                    question = "Proceed?",
                    header = "Step 2",
                    options = listOf(AskUserQuestionOption("Yes"), AskUserQuestionOption("No")),
                ),
            ),
        )
        compose.setContent {
            SessionPagerScreen(
                ui = ui(listOf(duplicated)),
                actions = SessionPagerActions(
                    onAnswerQuestions = { id, answers -> sent += id to answers },
                ),
            )
        }

        // Answering only the first duplicate must not satisfy the gate.
        compose.onNodeWithTag("questionOption-0-Yes").performScrollTo().performClick()
        compose.onNodeWithTag("questionsSend").performScrollTo().performClick()
        assertEquals("one of two duplicate-text questions is not complete", 0, sent.size)

        // Answering the second — with a DIFFERENT pick — enables Send, and
        // both answers travel positionally instead of collapsing into one.
        compose.onNodeWithTag("questionOption-1-No").performScrollTo().performClick()
        compose.onNodeWithTag("questionsSend").performScrollTo().performClick()
        assertEquals(
            listOf("perm-dup" to listOf("Yes", "No")),
            sent,
        )
    }

    /**
     * Issue #18 acceptance: dismissal/restore semantics match the approval
     * card — gesture-undismissable, a failed send keeps the card with the
     * error surfaced AND the user's picks intact for retry, and the
     * no-decision local-dismiss escape hatch unlocks only after repeated
     * failures.
     */
    @Test
    fun questionCardMatchesTheApprovalCardsDismissalAndRestoreSemantics() {
        val sent = mutableListOf<Pair<String, List<String>>>()
        val dismissals = mutableListOf<String>()
        var state by mutableStateOf(ui(listOf(askPrompt)))
        compose.setContent {
            SessionPagerScreen(
                ui = state,
                actions = SessionPagerActions(
                    onAnswerQuestions = { id, answers -> sent += id to answers },
                    onDismissPermission = { id -> dismissals += id },
                ),
            )
        }

        // Swipe-immune in every direction, exactly like the approval card.
        compose.onNodeWithTag("permissionSheet").performTouchInput { swipeRight() }
        compose.onNodeWithTag("permissionSheet").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("permissionSheet").performTouchInput { swipeDown() }
        compose.onNodeWithTag("permissionSheet").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("permissionSheet").assertIsDisplayed()

        // Answer both questions and send; the POST fails (the ViewModel keeps
        // the card queued, surfaces the error, counts the failure).
        compose.onNodeWithTag("questionOption-0-Blue").performScrollTo().performClick()
        compose.onNodeWithTag("questionOption-1-Tabs").performScrollTo().performClick()
        compose.onNodeWithTag("questionsSend").performScrollTo().performClick()
        assertEquals(1, sent.size)
        state = ui(listOf(askPrompt), error = "Decision failed: HTTP 500", failureCount = 1)
        compose.waitForIdle()

        // Restored: error surfaced, the card still up, and the user's picks
        // intact — the retry sends the SAME answers without re-entering them.
        // The error renders next to the Send chip (where the user is looking
        // after tapping Send), but scroll to it anyway: on a 454x454 round
        // viewport the tall two-question card leaves little slack, and this
        // assertion is about the error being surfaced and reachable, not
        // about a specific scroll offset.
        compose.onNodeWithTag("permissionError").performScrollTo().assertIsDisplayed()
        assertEquals(
            "dismiss must not be offered below the failure threshold",
            0,
            compose.onAllNodes(hasTestTag("permissionDismiss")).fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("questionsSend").performScrollTo().performClick()
        assertEquals(2, sent.size)
        assertEquals(
            "the restored card must retry with the picks it was rendered with",
            sent[0],
            sent[1],
        )

        // Threshold reached: the explicit no-decision dismiss appears and
        // sends NO answers — identical to the approval card's escape hatch.
        state = ui(
            listOf(askPrompt),
            error = "Decision failed: HTTP 500",
            failureCount = LOCAL_DISMISS_AFTER_FAILURES,
        )
        compose.waitForIdle()
        compose.onNodeWithTag("permissionDismiss").performScrollTo().performClick()
        assertEquals(listOf("perm-ask"), dismissals)
        assertEquals("dismiss must never send answers", 2, sent.size)

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
