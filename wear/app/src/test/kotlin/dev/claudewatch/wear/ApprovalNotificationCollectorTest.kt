package dev.claudewatch.wear

import dev.claudewatch.shared.protocol.PermissionOption
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The collector's DECISION branches (issue #25), tabled in plain JVM against
 * a recording [ApprovalNotificationSink] — the seam that exists precisely
 * because these branches were otherwise unreachable by any test: every
 * instrumented scenario runs with the UI invisible (no activity ever starts
 * in that harness), so the visible-UI swallow, its permanence, and the
 * posted-vs-known bookkeeping behind cancelAllPosted had ZERO coverage.
 * Each test here is keyed to a one-line sabotage that used to pass the
 * whole gate untouched:
 *
 *  - delete the `if (!uiVisible())` guard  → heads-up buzz over the very
 *    in-app card the user is reading ([visibleUiSwallowsThePost]);
 *  - mark swallowed ids unknown (or post on the NEXT emission)  → a card the
 *    user already has open retroactively buzzes when they background the app
 *    ([swallowIsPermanentAcrossLaterBackgroundedEmissions]);
 *  - iterate knownIds instead of postedIds in cancelAllPosted  → a dying
 *    service cancels notifications it never owned
 *    ([cancelAllPostedCancelsExactlyThePostedSubset]);
 *  - drop the `postedIds -= id` bookkeeping  → a dying service re-cancels
 *    prompts that already resolved
 *    ([departedIdLeavesThePostedSetSoTeardownNeverTouchesItAgain]).
 */
class ApprovalNotificationCollectorTest {

    /** Records exactly what would have reached the shade, in call order. */
    private class RecordingSink : ApprovalNotificationSink {
        val posted = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override fun post(model: ApprovalNotificationModel) {
            posted += model.permissionId
        }
        override fun cancel(permissionId: String) {
            cancelled += permissionId
        }
    }

    private fun prompt(id: String) = BridgeViewModel.PendingPermission(
        permissionId = id,
        sessionId = "s-1",
        toolName = "Bash",
        requestSummary = "$ rm -rf ./build",
        sessionLabel = "alpha",
        options = listOf(
            PermissionOption("allow", "Yes"),
            PermissionOption("deny", "No"),
        ),
        questions = emptyList(),
    )

    private val sink = RecordingSink()
    private var visible = false
    private val collector = ApprovalNotificationCollector(sink, uiVisible = { visible })

    @Test
    fun hiddenUiPostsNewPrompts() {
        visible = false
        collector.onQueue(listOf(prompt("perm-a")))
        assertEquals(listOf("perm-a"), sink.posted)
        assertEquals(emptyList<String>(), sink.cancelled)
    }

    @Test
    fun visibleUiSwallowsThePost() {
        // While the UI is on screen the in-app card IS the approval surface;
        // a heads-up buzz over the card the user is reading is noise.
        visible = true
        collector.onQueue(listOf(prompt("perm-a")))
        assertEquals(emptyList<String>(), sink.posted)
    }

    @Test
    fun swallowIsPermanentAcrossLaterBackgroundedEmissions() {
        // The prompt arrives while the card is open; the user backgrounds the
        // app WITHOUT answering, and a NEW prompt arrives. Only the new one
        // may buzz: the swallowed id is already known, and backgrounding must
        // never retroactively alert for a card the user saw and chose to
        // leave pending.
        visible = true
        collector.onQueue(listOf(prompt("perm-a")))
        visible = false
        collector.onQueue(listOf(prompt("perm-a"), prompt("perm-b")))
        assertEquals(listOf("perm-b"), sink.posted)
    }

    @Test
    fun departuresCancelEvenWhileVisibleAndEvenIfNeverPosted() {
        // Cancellation ignores visibility AND posted-ness: cancel(tag) on a
        // never-posted id is an idempotent no-op at the NotificationManager,
        // and unconditional cancel is what keeps the diff the single
        // cancellation path (no "was it posted?" branch to rot).
        visible = true
        collector.onQueue(listOf(prompt("perm-a")))
        collector.onQueue(emptyList())
        assertEquals(listOf("perm-a"), sink.cancelled)
        assertEquals(emptyList<String>(), sink.posted)
    }

    @Test
    fun cancelAllPostedCancelsExactlyThePostedSubset() {
        // perm-a reached the shade; perm-b was swallowed while the UI was
        // visible. Teardown owns ONLY what it posted — a knownIds sweep here
        // would cancel a notification this collector never created.
        visible = false
        collector.onQueue(listOf(prompt("perm-a")))
        visible = true
        collector.onQueue(listOf(prompt("perm-a"), prompt("perm-b")))
        sink.cancelled.clear()
        collector.cancelAllPosted()
        assertEquals(listOf("perm-a"), sink.cancelled)
    }

    @Test
    fun departedIdLeavesThePostedSetSoTeardownNeverTouchesItAgain() {
        // perm-a resolved (answered / cleared / expired) and its departure
        // cancelled it; the later teardown must address only perm-b — the
        // bookkeeping (`postedIds -= id`) is what keeps cancelAllPosted's
        // "exactly what this collector posted" claim true over time.
        visible = false
        collector.onQueue(listOf(prompt("perm-a"), prompt("perm-b")))
        collector.onQueue(listOf(prompt("perm-b")))
        assertEquals(listOf("perm-a"), sink.cancelled)
        sink.cancelled.clear()
        collector.cancelAllPosted()
        assertEquals(listOf("perm-b"), sink.cancelled)
    }

    @Test
    fun cancelAllPostedIsIdempotent() {
        visible = false
        collector.onQueue(listOf(prompt("perm-a")))
        collector.cancelAllPosted()
        collector.cancelAllPosted()
        assertEquals(listOf("perm-a"), sink.cancelled)
    }
}
