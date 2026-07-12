package dev.claudewatch.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudewatch.shared.protocol.AskUserQuestion
import dev.claudewatch.shared.protocol.AskUserQuestionOption
import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.wear.ui.LOCAL_DISMISS_AFTER_FAILURES
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Halo approval and question cards (issues #17/#18 semantics on the Halo
 * surfaces), driven with fixture UiStates — no bridge, no network. Covers:
 * WHAT is asked (tool + summary) and WHICH session asks; answers keyed to the
 * RENDERED card's permissionId (pinned across chaining); no gesture path that
 * sends a decision or drops a prompt (the Halo card's swipe-down is "decide
 * later": nothing sent, prompt still queued and re-openable — the watchOS
 * defect was a swipe that became a 10-minute auto-deny); failed answers keep
 * the card with the error surfaced; the local-dismiss escape hatch unlocks
 * only after repeated failures; and the question card's buffered positional
 * answers, including the dictated free-text path.
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

    /**
     * The prompts' sessions as the bridge reports them: resolveHookSession
     * gives every relayed permission hook a session, which OUTLIVES the
     * prompt's resolution. Without these, each prompt's session would be a
     * queue-derived synthetic that vanishes the moment its prompt is
     * answered — and HaloApp's model-shrink back-out would close the card
     * over the vanished session instead of chaining, which is not the
     * production shape these tests pin.
     */
    private val fixtureSessions = BridgeState(
        sessions = mapOf(
            "s-1" to SessionState(
                sessionId = "s-1",
                agent = "claude",
                cwd = "/home/dev/alpha",
                folderName = "alpha",
            ),
            "s-2" to SessionState(
                sessionId = "s-2",
                agent = "claude",
                cwd = "/home/dev/beta",
                folderName = "beta",
            ),
        ),
    )

    private fun ui(
        queue: List<BridgeViewModel.PendingPermission>,
        inFlightId: String? = null,
        error: String? = null,
        failureCount: Int = 0,
    ) = BridgeViewModel.UiState(
        status = "paired, stream open",
        paired = true,
        bridge = fixtureSessions,
        permissionQueue = queue,
        decisionInFlightId = inFlightId,
        decisionError = error,
        decisionFailureCount = failureCount,
    )

    /** Tap the centerpiece: Halo's way to the first waiting item's card. */
    private fun openCard() {
        compose.onNodeWithTag("haloCenter").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        armCard()
    }

    /**
     * The cards ignore taps for ~400ms after appearing (a card sliding in
     * under a mid-gesture finger must not swallow the tap as a DECISION), and
     * that guard runs on real uptime — so decision taps wait it out.
     */
    private fun armCard() {
        Thread.sleep(500)
    }

    private fun cardCount(): Int =
        compose.onAllNodes(hasTestTag("haloCard")).fetchSemanticsNodes().size

    @Test
    fun cardShowsWhatIsAskedWhichSessionAsksAndTheQueueDepth() {
        val answers = mutableListOf<Pair<String, String>>()
        // Newest-first queue: the write prompt arrived last and fronts it.
        var state by mutableStateOf(ui(listOf(writePrompt, bashPrompt)))
        compose.setContent {
            HaloApp(
                ui = state,
                actions = HaloActions(
                    onAnswerPermission = { id, behavior -> answers += id to behavior },
                ),
            )
        }

        openCard()
        // WHAT is being asked...
        compose.onNodeWithTag("haloSummary").assertIsDisplayed()
        compose.onNodeWithText("Write notes.txt").assertIsDisplayed()
        compose.onNode(hasTestTag("haloTool") and hasText("Write", substring = true))
            .assertIsDisplayed()
        // ...by WHICH session...
        assertTrue(
            "the asking session's label must render",
            compose.onAllNodes(hasText("beta")).fetchSemanticsNodes().isNotEmpty(),
        )
        // ...and how many are waiting in total.
        compose.onNode(hasTestTag("haloWaitingCount") and hasText("2 waiting")).assertIsDisplayed()

        // Answering is keyed to the RENDERED card's permissionId.
        compose.onNodeWithTag("haloDeny").performClick()
        assertEquals(listOf("perm-write" to "deny"), answers)

        // Ack arrives (ViewModel would shrink the queue): the next prompt
        // chains in — resolving a card slides in the next waiting item.
        state = ui(listOf(bashPrompt))
        compose.waitForIdle()
        compose.onNodeWithText("$ rm -rf ./build").assertIsDisplayed()
        armCard()
        compose.onNodeWithTag("haloApprove").performClick()
        assertEquals(listOf("perm-write" to "deny", "perm-bash" to "allow"), answers)

        // Queue empty: the card is gone, home is back in charge.
        state = ui(emptyList())
        compose.waitForIdle()
        assertEquals(0, cardCount())
    }

    @Test
    fun noGesturePathSendsADecisionOrDropsThePrompt() {
        val answers = mutableListOf<Pair<String, String>>()
        var state by mutableStateOf(ui(listOf(bashPrompt)))
        compose.setContent {
            HaloApp(
                ui = state,
                actions = HaloActions(
                    onAnswerPermission = { id, behavior -> answers += id to behavior },
                ),
            )
        }
        openCard()

        // Horizontal and upward swipes: the card stays put.
        compose.onNodeWithTag("haloCard").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        compose.onNodeWithTag("haloCard").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        compose.onNodeWithTag("haloCard").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()

        // Swipe down is "decide later" BY DESIGN (handoff §5): the card
        // closes but NOTHING is sent and the prompt stays queued — never the
        // watchOS swipe-into-auto-deny.
        compose.onNodeWithTag("haloCard").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(0, cardCount())
        assertEquals("no gesture may send a decision", emptyList<Pair<String, String>>(), answers)

        // The prompt is intact and re-openable: the session's feed still
        // carries the waiting banner, and the reopened card is answerable.
        compose.onNodeWithTag("haloWaitingBanner").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        armCard()
        compose.onNodeWithTag("haloApprove").performClick()
        assertEquals(listOf("perm-bash" to "allow"), answers)
    }

    @Test
    fun failedAnswerKeepsTheCardWithTheErrorSurfacedAndRetryReanswers() {
        val answers = mutableListOf<Pair<String, String>>()
        var state by mutableStateOf(ui(listOf(bashPrompt)))
        compose.setContent {
            HaloApp(
                ui = state,
                actions = HaloActions(
                    onAnswerPermission = { id, behavior -> answers += id to behavior },
                ),
            )
        }
        openCard()
        compose.onNodeWithTag("haloApprove").performClick()
        assertEquals(listOf("perm-bash" to "allow"), answers)

        // While the POST is in flight the buttons are disabled and the
        // in-flight marker shows — no double-answers, no premature dismissal.
        state = ui(listOf(bashPrompt), inFlightId = "perm-bash")
        compose.waitForIdle()
        compose.onNode(hasTestTag("haloDecisionStatus") and hasText("sending…")).assertIsDisplayed()
        compose.onNodeWithTag("haloApprove").performClick()
        assertEquals("clicks while in flight must not re-answer", 1, answers.size)

        // The ViewModel reported a failed POST: prompt still queued, error
        // surfaced on the card, and the retry lands on the SAME permissionId.
        state = ui(listOf(bashPrompt), error = "Decision failed: HTTP 500", failureCount = 1)
        compose.waitForIdle()
        compose.onNode(
            hasTestTag("haloDecisionStatus") and hasText("HTTP 500", substring = true),
        ).assertIsDisplayed()
        compose.onNodeWithTag("haloApprove").performClick()
        assertEquals(listOf("perm-bash" to "allow", "perm-bash" to "allow"), answers)
    }

    /**
     * The availability escape hatch: an unreachable/restarted bridge must not
     * wedge the whole app behind an unanswerable card. After
     * [LOCAL_DISMISS_AFTER_FAILURES] consecutive failed answers a dismiss
     * appears that drops the card locally and sends NO decision — before
     * that threshold it must NOT exist (a one-blip dismissal would
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
            HaloApp(
                ui = state,
                actions = HaloActions(
                    onAnswerPermission = { id, behavior -> answers += id to behavior },
                    onDismissPermission = { id -> dismissals += id },
                ),
            )
        }
        openCard()

        // One failure: retry is the only path — no local dismiss yet.
        assertEquals(
            "dismiss must not be offered below the failure threshold",
            0,
            compose.onAllNodes(hasTestTag("haloDismissLocal")).fetchSemanticsNodes().size,
        )

        // Threshold reached: the explicit no-decision dismiss appears.
        state = ui(
            listOf(bashPrompt),
            error = "Decision failed: HTTP 500",
            failureCount = LOCAL_DISMISS_AFTER_FAILURES,
        )
        compose.waitForIdle()
        compose.onNodeWithTag("haloDismissLocal").performScrollTo().performClick()
        assertEquals(listOf("perm-bash"), dismissals)
        assertEquals("dismiss must never send an answer", emptyList<Pair<String, String>>(), answers)

        // The ViewModel dropped the card: the overlay leaves, the app is usable.
        state = ui(emptyList())
        compose.waitForIdle()
        assertEquals(0, cardCount())
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
            HaloApp(
                ui = ui(listOf(withAlways)),
                actions = HaloActions(
                    onAnswerPermission = { id, behavior -> answers += id to behavior },
                ),
            )
        }
        openCard()

        // The action is keyed by the machine-readable behavior, not a label.
        compose.onNodeWithTag("haloAlwaysAllow").performScrollTo().performClick()
        assertEquals(listOf("perm-always" to "allow-always"), answers)
    }

    // ------------------------------------------------------------------
    // AskUserQuestion card (issue #18 semantics on the Halo card)
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
     * Issue #18 acceptance on Halo: a multi-question payload walks EVERY
     * question (the legacy client silently answered only the first), answers
     * are BUFFERED — nothing is sent until each question has one — and the
     * single submit is positional: one answer per question in payload order,
     * the array form the bridge zips back onto the blocked hook's questions.
     */
    @Test
    fun multiQuestionCardWalksEveryQuestionAndSubmitsAllAnswersTogether() {
        val sent = mutableListOf<Pair<String, List<String>>>()
        compose.setContent {
            HaloApp(
                ui = ui(listOf(askPrompt)),
                actions = HaloActions(
                    onAnswerQuestions = { id, answers -> sent += id to answers },
                ),
            )
        }
        openCard()

        // Question 1 of 2 renders with its own options.
        compose.onNode(hasTestTag("haloQuestionCount") and hasText("question 1 of 2"))
            .assertIsDisplayed()
        compose.onNodeWithText("Which color scheme?").assertIsDisplayed()

        // Answering advances — and must NOT fire a partial submit, which
        // would silently drop the unanswered question's hook answer.
        compose.onNodeWithTag("haloQOption-0-Blue").performClick()
        compose.waitForIdle()
        assertEquals("answers are buffered until every question has one", 0, sent.size)
        compose.onNodeWithText("Tabs or spaces?").assertIsDisplayed()

        // The last answer submits ONE payload, both answers in question order.
        compose.onNodeWithTag("haloQOption-1-Spaces").performClick()
        compose.waitForIdle()
        assertEquals(listOf("perm-ask" to listOf("Blue", "Spaces")), sent)
    }

    /**
     * Issue #18 acceptance: the free-answer path. Halo has no keyboard — the
     * free answer is DICTATED ("Dictate an answer…", always the last option)
     * and lands on its question in the same positional array.
     */
    @Test
    fun dictatedFreeTextAnswerIsSentForItsQuestion() {
        val sent = mutableListOf<Pair<String, List<String>>>()
        compose.setContent {
            HaloApp(
                ui = ui(listOf(askPrompt)),
                actions = HaloActions(
                    onAnswerQuestions = { id, answers -> sent += id to answers },
                    // The stub recognizer: a fixed transcription, delivered
                    // exactly where MainActivity's sink lands the real one.
                    onDictateAnswer = { onResult -> onResult("two-space soft tabs") },
                ),
            )
        }
        openCard()

        // Question 0 by option, question 1 by dictated free text.
        compose.onNodeWithTag("haloQOption-0-Green").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloQDictate").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("perm-ask" to listOf("Green", "two-space soft tabs")), sent)
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
            HaloApp(
                ui = ui(listOf(multi)),
                actions = HaloActions(
                    onAnswerQuestions = { id, answers -> sent += id to answers },
                ),
            )
        }
        openCard()

        // Toggle in REVERSE order, and toggle one off again: the answer joins
        // the still-selected labels in the question's option order.
        compose.onNodeWithTag("haloQOption-0-android-lint").performScrollTo().performClick()
        compose.onNodeWithTag("haloQOption-0-detekt").performScrollTo().performClick()
        compose.onNodeWithTag("haloQOption-0-ktlint").performScrollTo().performClick()
        compose.onNodeWithTag("haloQOption-0-android-lint").performScrollTo().performClick()
        compose.onNodeWithTag("haloQNext").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("perm-multi" to listOf("ktlint, detekt")), sent)
    }

    /**
     * Regression (review finding): a payload whose questions share the SAME
     * question text must still be fully answerable, with each duplicate's own
     * answer travelling positionally. Answer state is indexed by POSITION —
     * a text-keyed map would collapse the duplicates into one answer.
     */
    @Test
    fun duplicateQuestionTextsStillCountSeparatelyAndSubmitPositionalAnswers() {
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
            HaloApp(
                ui = ui(listOf(duplicated)),
                actions = HaloActions(
                    onAnswerQuestions = { id, answers -> sent += id to answers },
                ),
            )
        }
        openCard()

        // Answering the first duplicate must not satisfy the submit gate.
        compose.onNodeWithTag("haloQOption-0-Yes").performClick()
        compose.waitForIdle()
        assertEquals("one of two duplicate-text questions is not complete", 0, sent.size)

        // The second — with a DIFFERENT pick — completes the buffer, and both
        // answers travel positionally instead of collapsing into one.
        compose.onNodeWithTag("haloQOption-1-No").performClick()
        compose.waitForIdle()
        assertEquals(listOf("perm-dup" to listOf("Yes", "No")), sent)
    }

    /**
     * Issue #18 acceptance: dismissal/restore semantics match the approval
     * card — no gesture sends answers or drops the prompt ("answer later"
     * loses nothing), a failed send keeps the card with the error surfaced
     * AND the buffered answers intact for retry, and the no-decision
     * local-dismiss escape hatch unlocks only after repeated failures.
     */
    @Test
    fun questionCardMatchesTheApprovalCardsDismissalAndRestoreSemantics() {
        val sent = mutableListOf<Pair<String, List<String>>>()
        val dismissals = mutableListOf<String>()
        var state by mutableStateOf(ui(listOf(askPrompt)))
        compose.setContent {
            HaloApp(
                ui = state,
                actions = HaloActions(
                    onAnswerQuestions = { id, answers -> sent += id to answers },
                    onDismissPermission = { id -> dismissals += id },
                ),
            )
        }
        openCard()

        // Swipe-immune horizontally and upward, exactly like the approval card.
        compose.onNodeWithTag("haloCard").performTouchInput { swipeRight() }
        compose.onNodeWithTag("haloCard").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("haloCard").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()

        // Swipe down = "answer later": nothing sent, prompt intact, and the
        // reopened card starts the walk again — no half-buffered ghost state.
        compose.onNodeWithTag("haloCard").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(0, cardCount())
        assertEquals("no gesture may submit answers", 0, sent.size)
        compose.onNodeWithTag("haloWaitingBanner").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloCard").assertIsDisplayed()
        armCard()

        // Answer both questions; the submit POST fails (the ViewModel keeps
        // the card queued, surfaces the error, counts the failure).
        compose.onNodeWithTag("haloQOption-0-Blue").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("haloQOption-1-Tabs").performClick()
        compose.waitForIdle()
        assertEquals(1, sent.size)
        state = ui(listOf(askPrompt), error = "Decision failed: HTTP 500", failureCount = 1)
        compose.waitForIdle()

        // Restored: error surfaced, the buffered answers intact — the retry
        // sends the SAME positional payload without re-entering anything.
        compose.onNode(
            hasTestTag("haloDecisionStatus") and hasText("HTTP 500", substring = true),
        ).assertIsDisplayed()
        assertEquals(
            "dismiss must not be offered below the failure threshold",
            0,
            compose.onAllNodes(hasTestTag("haloDismissLocal")).fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("haloQRetry").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(2, sent.size)
        assertEquals(
            "the restored card must retry with the answers it was rendered with",
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
        compose.onNodeWithTag("haloDismissLocal").performScrollTo().performClick()
        assertEquals(listOf("perm-ask"), dismissals)
        assertEquals("dismiss must never send answers", 2, sent.size)

        // The ViewModel dropped the card: the overlay leaves, the app is usable.
        state = ui(emptyList())
        compose.waitForIdle()
        assertEquals(0, cardCount())
    }
}
