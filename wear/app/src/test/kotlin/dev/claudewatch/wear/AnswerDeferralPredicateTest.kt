package dev.claudewatch.wear

import dev.claudewatch.shared.protocol.PermissionOption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure defer-or-answer-now predicate behind the service's notification
 * answer path (issue #59): [isPermissionQueued] is the EXACT condition
 * BridgeViewModel.sendDecision's still-queued guard applies, extracted so
 * the service can ask it BEFORE answering — an answer fired while it is
 * false would be silently dropped by that very guard (the tap-recreates-a-
 * dead-process race), so the service defers until the reconnect replay
 * flips it true. The flow behavior lives in the instrumented suite
 * (ApprovalNotificationFlowTest); this table pins the predicate itself.
 */
class AnswerDeferralPredicateTest {

    private fun prompt(id: String) = BridgeViewModel.PendingPermission(
        permissionId = id,
        sessionId = "s-1",
        toolName = "Bash",
        requestSummary = "$ npm test",
        sessionLabel = "alpha",
        options = listOf(PermissionOption("allow", "Yes")),
    )

    @Test
    fun emptyQueueMeansNotQueued() {
        assertFalse(isPermissionQueued(BridgeViewModel.UiState(), "perm-a"))
    }

    @Test
    fun queuedIdIsFoundAnywhereInTheQueueNotJustAtTheFront() {
        val state = BridgeViewModel.UiState(
            permissionQueue = listOf(prompt("perm-b"), prompt("perm-a")),
        )
        assertTrue(isPermissionQueued(state, "perm-a"))
        assertTrue(isPermissionQueued(state, "perm-b"))
    }

    @Test
    fun otherPromptsQueuedDoesNotMakeAMissingIdQueued() {
        // The exact restart shape: the replay delivered SOME prompts, but
        // not the tapped one (it resolved while the watch was dead) — the
        // answer must keep waiting (and time out doing nothing), never
        // answer just because the queue is non-empty.
        val state = BridgeViewModel.UiState(permissionQueue = listOf(prompt("perm-b")))
        assertFalse(isPermissionQueued(state, "perm-a"))
    }

    // ------------------------------------------------------------------
    // The double-tap claim (see claimAnswerDelivery): one delivery answers,
    // its duplicate — same PendingIntent delivered twice, ms apart, racing
    // the notification cancel — is dropped; a re-raised prompt's fresh tap,
    // always past the window, claims again.
    // ------------------------------------------------------------------

    @Test
    fun firstDeliveryClaimsAndItsImmediateDuplicateIsRefused() {
        val claims = mutableMapOf<String, Long>()
        assertTrue(claimAnswerDelivery(claims, "perm-a", nowMs = 1_000))
        assertFalse(
            "the double-tap duplicate must be dropped",
            claimAnswerDelivery(claims, "perm-a", nowMs = 1_050),
        )
        // Right up to the window's edge it stays claimed.
        assertFalse(claimAnswerDelivery(claims, "perm-a", nowMs = 1_000 + ANSWER_DUPLICATE_WINDOW_MS - 1))
    }

    @Test
    fun claimExpiresSoAReRaisedPromptsFreshTapAnswers() {
        val claims = mutableMapOf<String, Long>()
        assertTrue(claimAnswerDelivery(claims, "perm-a", nowMs = 1_000))
        // The replay re-raised the prompt and the user tapped the NEW
        // notification — same permissionId, seconds later: must answer.
        assertTrue(claimAnswerDelivery(claims, "perm-a", nowMs = 1_000 + ANSWER_DUPLICATE_WINDOW_MS))
    }

    @Test
    fun distinctPromptsClaimIndependently() {
        val claims = mutableMapOf<String, Long>()
        assertTrue(claimAnswerDelivery(claims, "perm-a", nowMs = 1_000))
        assertTrue(
            "another prompt's tap is not perm-a's duplicate",
            claimAnswerDelivery(claims, "perm-b", nowMs = 1_001),
        )
    }
}
