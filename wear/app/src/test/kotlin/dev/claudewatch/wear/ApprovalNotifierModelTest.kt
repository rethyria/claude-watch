package dev.claudewatch.wear

import dev.claudewatch.shared.protocol.AskUserQuestion
import dev.claudewatch.shared.protocol.AskUserQuestionOption
import dev.claudewatch.shared.protocol.PermissionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the approval-notification surface (issue #25): content
 * model, queue diff, requestCode derivation. Tabled exhaustively because a
 * wrong answer here is a wrong PERMISSION DECISION surface — a mis-mapped
 * action approves something the user never saw, a mis-diffed queue leaves a
 * dead prompt's Approve button live on the wrist.
 */
class ApprovalNotifierModelTest {

    private fun prompt(
        id: String = "perm-1",
        toolName: String = "Bash",
        summary: String = "$ rm -rf ./build",
        label: String = "alpha",
        options: List<PermissionOption> = listOf(
            PermissionOption("allow", "Yes"),
            PermissionOption("deny", "No"),
        ),
        questions: List<AskUserQuestion> = emptyList(),
    ) = BridgeViewModel.PendingPermission(
        permissionId = id,
        sessionId = "s-1",
        toolName = toolName,
        requestSummary = summary,
        sessionLabel = label,
        options = options,
        questions = questions,
    )

    // ------------------------------------------------------------------
    // Content model
    // ------------------------------------------------------------------

    @Test
    fun permissionPromptMapsOptionsVerbatimWithBehaviorKeysAndOrder() {
        val options = listOf(
            PermissionOption("allow", "Yes", "Allow this once"),
            PermissionOption("allow-always", "Yes, don't ask again"),
            PermissionOption("deny", "No"),
        )
        val model = approvalNotificationModel(prompt(options = options))
        assertEquals("perm-1", model.permissionId)
        // WHAT is asked, by WHOM — the single-slot-card lesson carried over.
        assertEquals("Bash · alpha", model.title)
        assertEquals("$ rm -rf ./build", model.text)
        // Verbatim: the same behavior-keyed objects, in the bridge's order —
        // never re-sorted, never label-inferred.
        assertEquals(options, model.options)
        assertFalse(model.remoteInputQuestion)
    }

    @Test
    fun optionsPastWearsActionLimitAreCappedKeepingTheFirstThree() {
        // Behaviors must be canonical (PermissionOption's init requires it),
        // so a hypothetical fourth option reuses one — the cap logic only
        // sees list length, and the in-app card renders the overflow.
        val options = listOf(
            PermissionOption("allow", "Yes"),
            PermissionOption("allow-always", "Yes, always"),
            PermissionOption("deny", "No"),
            PermissionOption("deny", "No, and tell it why"),
        )
        val model = approvalNotificationModel(prompt(options = options))
        assertEquals(options.take(MAX_WEAR_NOTIFICATION_ACTIONS), model.options)
    }

    @Test
    fun singleQuestionPromptBecomesAFreeTextReplyWithNoOptionActions() {
        val model = approvalNotificationModel(
            prompt(
                id = "perm-q",
                toolName = "AskUserQuestion",
                summary = "[AskUserQuestion]",
                options = emptyList(),
                questions = listOf(
                    AskUserQuestion(
                        question = "Which database should the service use?",
                        options = listOf(AskUserQuestionOption("PostgreSQL")),
                    ),
                ),
            ),
        )
        assertTrue(model.remoteInputQuestion)
        // The question is the text — "[AskUserQuestion]" answers nothing.
        assertEquals("Which database should the service use?", model.text)
        assertEquals("Question · alpha", model.title)
        assertEquals(
            "a reply prompt must carry no behavior actions",
            emptyList<PermissionOption>(),
            model.options,
        )
        // The question's OWN option labels become the RemoteInput choice
        // chips — live-demo lesson: without them Wear invents ML Smart
        // Replies ("Good question") that masquerade as agent options.
        assertEquals(listOf("PostgreSQL"), model.replyChoices)
        // ...and one-tap action BUTTONS (second live-demo lesson: this Wear
        // image renders setChoices chips nowhere — plain actions are the
        // only deterministic surface).
        assertEquals(listOf("PostgreSQL"), model.optionAnswers)
    }

    @Test
    fun optionAnswerButtonsAreAllOrNothing() {
        fun questionWith(labels: List<String>, multiSelect: Boolean = false) =
            approvalNotificationModel(
                prompt(
                    id = "perm-q",
                    toolName = "AskUserQuestion",
                    summary = "[AskUserQuestion]",
                    options = emptyList(),
                    questions = listOf(
                        AskUserQuestion(
                            question = "Pick one",
                            options = labels.map { AskUserQuestionOption(it) },
                            multiSelect = multiSelect,
                        ),
                    ),
                ),
            )
        // Two options + Reply = the full 3-action budget: both render.
        assertEquals(listOf("A", "B"), questionWith(listOf("A", "B")).optionAnswers)
        // Three options CANNOT all fit next to Reply — a truncated menu
        // would misrepresent the agent's question, so none render (the
        // in-app card owns the full set; Reply stays for free text).
        assertEquals(emptyList<String>(), questionWith(listOf("A", "B", "C")).optionAnswers)
        // multiSelect answers are a JOINED toggle set — no single button
        // expresses one, so the wrist keeps Reply + the card.
        assertEquals(
            emptyList<String>(),
            questionWith(listOf("A", "B"), multiSelect = true).optionAnswers,
        )
        // The choice chips are unaffected by the button cap: they list every
        // label wherever the platform renders them.
        assertEquals(
            listOf("A", "B", "C"),
            questionWith(listOf("A", "B", "C")).replyChoices,
        )
    }

    @Test
    fun aChoicelessSingleQuestionStaysPureFreeText() {
        val model = approvalNotificationModel(
            prompt(
                id = "perm-open",
                toolName = "AskUserQuestion",
                summary = "[AskUserQuestion]",
                options = emptyList(),
                questions = listOf(AskUserQuestion(question = "What should it be called?")),
            ),
        )
        assertTrue(model.remoteInputQuestion)
        assertEquals(
            "no options on the question means no chips on the wrist",
            emptyList<String>(),
            model.replyChoices,
        )
    }

    @Test
    fun multiQuestionPromptGetsNoActionsAtAll() {
        // A wrist notification cannot walk a multi-question form (buffered
        // positional answers, per-question options) — the in-app card owns
        // that; the only affordance is the content tap opening the app.
        val model = approvalNotificationModel(
            prompt(
                id = "perm-multi",
                toolName = "AskUserQuestion",
                summary = "[AskUserQuestion]",
                options = emptyList(),
                questions = listOf(
                    AskUserQuestion(question = "Which database?"),
                    AskUserQuestion(question = "What name?"),
                ),
            ),
        )
        assertFalse(model.remoteInputQuestion)
        assertEquals(emptyList<PermissionOption>(), model.options)
        assertEquals("2 questions — open to answer", model.text)
        assertEquals(
            "multi-question options belong to the in-app card only",
            emptyList<String>(),
            model.replyChoices,
        )
    }

    // ------------------------------------------------------------------
    // Queue diff — the entire post/cancel truth table
    // ------------------------------------------------------------------

    @Test
    fun newIdPostsAndNothingCancels() {
        val a = prompt(id = "perm-a")
        val diff = diffPermissionNotifications(emptySet(), listOf(a))
        assertEquals(listOf(a), diff.toPost)
        assertEquals(emptySet<String>(), diff.toCancelIds)
    }

    @Test
    fun survivingIdNeitherPostsNorCancels() {
        // The forbidden re-post: an unchanged id must never notify again —
        // one notification per permissionId, never re-noisy.
        val a = prompt(id = "perm-a")
        val diff = diffPermissionNotifications(setOf("perm-a"), listOf(a))
        assertEquals(emptyList<BridgeViewModel.PendingPermission>(), diff.toPost)
        assertEquals(emptySet<String>(), diff.toCancelIds)
    }

    @Test
    fun departedIdCancels() {
        // Departure is the ONLY cancellation signal, and it covers every
        // resolution path: answered here, answered elsewhere, expired 404,
        // permission-cleared — all reach the queue as a removal.
        val diff = diffPermissionNotifications(setOf("perm-a"), emptyList())
        assertEquals(emptyList<BridgeViewModel.PendingPermission>(), diff.toPost)
        assertEquals(setOf("perm-a"), diff.toCancelIds)
    }

    @Test
    fun simultaneousAddAndRemoveDiffBothWays() {
        val b = prompt(id = "perm-b")
        val diff = diffPermissionNotifications(setOf("perm-a"), listOf(b))
        assertEquals(listOf(b), diff.toPost)
        assertEquals(setOf("perm-a"), diff.toCancelIds)
    }

    @Test
    fun emptyToEmptyIsANoOp() {
        val diff = diffPermissionNotifications(emptySet(), emptyList())
        assertEquals(emptyList<BridgeViewModel.PendingPermission>(), diff.toPost)
        assertEquals(emptySet<String>(), diff.toCancelIds)
    }

    @Test
    fun multipleNewIdsAllPostInQueueOrder() {
        val b = prompt(id = "perm-b")
        val a = prompt(id = "perm-a")
        // Newest-first queue order is preserved — the diff never re-sorts.
        val diff = diffPermissionNotifications(emptySet(), listOf(b, a))
        assertEquals(listOf(b, a), diff.toPost)
    }

    // ------------------------------------------------------------------
    // requestCode derivation
    // ------------------------------------------------------------------

    @Test
    fun requestCodesAreDistinctAcrossConcurrentPromptsAndBehaviors() {
        // The collision that matters: two concurrent prompts share the SAME
        // behaviors, differing only in permissionId — and PendingIntent
        // identity ignores extras, so these codes are what keeps prompt B's
        // Approve from firing prompt A's recycled intent. Every
        // (permissionId, behavior) pair over realistic ids must map to a
        // distinct code. (Residual 32-bit hash collisions are additionally
        // neutralized by the per-pair data URI — see approvalActionRequestCode.)
        val ids = listOf("perm-1751234567-abc", "perm-1751234568-def", "perm-q")
        val behaviors = listOf("allow", "allow-always", "deny", "remote-input-reply")
        val codes = mutableMapOf<Int, Pair<String, String>>()
        for (id in ids) {
            for (behavior in behaviors) {
                val code = approvalActionRequestCode(id, behavior)
                val clash = codes.put(code, id to behavior)
                assertEquals(
                    "requestCode collision between $clash and ${id to behavior}",
                    null,
                    clash,
                )
            }
        }
    }

    @Test
    fun requestCodeIsStableForTheSamePair() {
        // Stability matters for FLAG_UPDATE_CURRENT: re-posting the same
        // prompt must address the SAME registration, not leak a new one.
        assertEquals(
            approvalActionRequestCode("perm-a", "allow"),
            approvalActionRequestCode("perm-a", "allow"),
        )
    }
}
