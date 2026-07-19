package dev.claudewatch.wear

import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.wear.net.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collector's DECISION branches (issues #25 + #59), tabled in plain JVM
 * against a recording [ApprovalNotificationSink] — the seam that exists
 * precisely because these branches were otherwise unreachable by any test:
 * every instrumented scenario runs with the UI invisible (no activity ever
 * starts in that harness), so the visible-UI gating and the posted-vs-known
 * bookkeeping behind cancelAllPosted had ZERO coverage. Each test here is
 * keyed to a one-line sabotage that used to pass the whole gate untouched:
 *
 *  - delete the `if (!visibility.value)` guard  → heads-up buzz over the very
 *    in-app card the user is reading ([visibleUiSwallowsThePost]);
 *  - drop the visible→hidden edge handler (or post ids no longer queued)  →
 *    replayed catch-up prompts never buzz / departed prompts buzz late
 *    ([withheldPromptPostsWhenTheUiGoesAwayIfStillQueued] and friends);
 *  - iterate knownIds instead of postedIds in cancelAllPosted  → a dying
 *    service cancels notifications it never owned
 *    ([cancelAllPostedCancelsExactlyThePostedSubset]);
 *  - drop the `postedIds -= id` bookkeeping  → a dying service re-cancels
 *    prompts that already resolved
 *    ([departedIdLeavesThePostedSetSoTeardownNeverTouchesItAgain]);
 *  - skip adoption, run the adopted diff undeferred, adjudicate survivors
 *    WHOLESALE on the first post-Connected emission (a partial replay set —
 *    the reducer emits once per replayed frame), or gate the verdict on a
 *    queue emission that an unchanged queue never produces  → orphaned
 *    zombie notifications after a process kill, or a cancel+re-post buzz
 *    for a still-pending survivor (the #59 adoption tests);
 *  - drop the `postedIds += id` bookkeeping on the background-edge post  →
 *    every later visible→hidden cycle re-buzzes the same prompt and the
 *    graceful sweep misses it
 *    ([backgroundEdgePostIsBookkeptSoLaterEdgesAndTeardownOwnIt]).
 *
 * The flow-driven tests attach with [Dispatchers.Unconfined]: every StateFlow
 * write runs the collector's reaction synchronously on the test thread, so
 * ordering is deterministic without a test-dispatcher dependency — all the
 * collector's inputs are StateFlows and all its outputs are recorded sink
 * calls.
 */
class ApprovalNotificationCollectorTest {

    /**
     * Records exactly what would have reached the shade, in call order —
     * plus the SHADE ITSELF ([shade]): activeTags() is what a fresh
     * collector adopts at attach (#59), so the fake must model
     * notifications SURVIVING the recording collector's death. Tests
     * pre-seed [shade] to stand in for a killed process's leftovers.
     */
    private class RecordingSink : ApprovalNotificationSink {
        val posted = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val shade = mutableSetOf<String>()
        override fun post(model: ApprovalNotificationModel) {
            posted += model.permissionId
            shade += model.permissionId
        }
        override fun cancel(permissionId: String) {
            cancelled += permissionId
            shade -= permissionId
        }
        override fun activeTags(): Set<String> = shade.toSet()
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
    private val visible = MutableStateFlow(false)
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    private val state = MutableStateFlow(BridgeViewModel.UiState())
    // visibilityDebounceMs = 0: delay(0) never suspends, so the flap filter
    // is transparent under Unconfined and every flow-driven edge stays
    // synchronous. The filter itself is pinned by the virtual-time test
    // [activityRecreationFlapNeverPostsOverTheVisibleUi].
    private val collector =
        ApprovalNotificationCollector(sink, visibility = visible, visibilityDebounceMs = 0L)

    // Unconfined: attach()'s collectors run each emission synchronously on
    // the writer's thread — the JVM-deterministic stand-in for the service's
    // Main.immediate scope.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun attach() = collector.attach(scope, state, connection)

    private fun emitQueue(vararg prompts: BridgeViewModel.PendingPermission) {
        state.value = BridgeViewModel.UiState(permissionQueue = prompts.toList())
    }

    // ------------------------------------------------------------------
    // Foreground gating (#25) — post-time reads of the visibility flow.
    // ------------------------------------------------------------------

    @Test
    fun hiddenUiPostsNewPrompts() {
        visible.value = false
        collector.onQueue(listOf(prompt("perm-a")))
        assertEquals(listOf("perm-a"), sink.posted)
        assertEquals(emptyList<String>(), sink.cancelled)
    }

    @Test
    fun visibleUiSwallowsThePost() {
        // While the UI is on screen the in-app card is the approval surface;
        // a heads-up buzz over the card the user is reading is noise.
        visible.value = true
        collector.onQueue(listOf(prompt("perm-a")))
        assertEquals(emptyList<String>(), sink.posted)
    }

    // ------------------------------------------------------------------
    // Post-on-background (#59, edge 3). DELIBERATE SPEC CHANGE: the
    // pre-#59 contract here ("swallowIsPermanentAcrossLaterBackgrounded-
    // Emissions") pinned the withhold as PERMANENT — background the app and
    // a prompt that arrived while visible never buzzed, ever. That rule was
    // written for prompts arriving under the user's nose, but the reconnect
    // replay delivers every pending prompt ~1s after every app open — while
    // visible — so catch-up prompts were silently muted in exactly the AFK
    // scenario the notifications exist for (the third #59 finding). The
    // tests below REPLACE the permanence assertions with the new contract:
    // withhold while visible, post on the visible→hidden edge iff still
    // queued.
    // ------------------------------------------------------------------

    @Test
    fun activityRecreationFlapNeverPostsOverTheVisibleUi() = runTest {
        // Activity recreation flaps visibility true→false→true (the old
        // instance's ON_STOP lands between the new one's dispatches), and
        // StateFlow conflation across separate main-thread dispatches is
        // not guaranteed — the collector may well OBSERVE the transient
        // false. The debounce (default 400ms) is the filter: only a hidden
        // state that HOLDS posts the withheld prompts. Virtual time, real
        // default debounce.
        val sink = RecordingSink()
        val visibility = MutableStateFlow(true)
        val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val state = MutableStateFlow(BridgeViewModel.UiState(permissionQueue = listOf(prompt("perm-a"))))
        ApprovalNotificationCollector(sink, visibility = visibility)
            .attach(backgroundScope, state, connection)
        runCurrent()
        assertEquals("visible: withheld", emptyList<String>(), sink.posted)

        // The flap: hidden for 100ms — well under the debounce — then the
        // recreated activity is visible again.
        visibility.value = false
        advanceTimeBy(100)
        runCurrent()
        visibility.value = true
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(
            "a recreation flap must not buzz over the visible UI",
            emptyList<String>(),
            sink.posted,
        )

        // A REAL background: the hidden edge holds past the debounce.
        visibility.value = false
        advanceTimeBy(ApprovalNotificationCollector.VISIBILITY_FLAP_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals("still inside the debounce", emptyList<String>(), sink.posted)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("held hidden: NOW it posts", listOf("perm-a"), sink.posted)
    }

    @Test
    fun withheldPromptPostsWhenTheUiGoesAwayIfStillQueued() {
        attach()
        visible.value = true
        emitQueue(prompt("perm-a"))
        assertEquals("visible: the in-app card owns it", emptyList<String>(), sink.posted)
        visible.value = false
        assertEquals("hidden with perm-a still pending: NOW it buzzes", listOf("perm-a"), sink.posted)
        // And exactly once: a later queue pass still containing perm-a must
        // not re-post it (it is posted and known). Driven through onQueue
        // DIRECTLY, not emitQueue: UiState and its prompts are data classes,
        // so re-writing an equal value to the StateFlow is NOT an emission
        // (conflation swallows it) — the emitQueue idiom here asserted
        // against a diff that never ran (review finding on this very test).
        collector.onQueue(listOf(prompt("perm-a")))
        assertEquals(listOf("perm-a"), sink.posted)
        assertEquals(emptyList<String>(), sink.cancelled)
    }

    @Test
    fun backgroundingPostsTheWithheldPromptAlongsideTheNewOne() {
        // The old permanence test's exact scenario, re-pinned to the new
        // contract: perm-a arrives while the card is open, the user
        // backgrounds the app WITHOUT answering, and perm-b arrives. Under
        // #59 BOTH buzz — perm-a on the visibility edge (it is still
        // pending and the user can no longer see the card), perm-b as an
        // ordinary hidden-arrival post.
        attach()
        visible.value = true
        emitQueue(prompt("perm-a"))
        visible.value = false
        emitQueue(prompt("perm-a"), prompt("perm-b"))
        assertEquals(listOf("perm-a", "perm-b"), sink.posted)
    }

    @Test
    fun visibilityLossAfterThePromptDepartedPostsNothing() {
        // Departed-while-visible: the departure cancelled it (idempotent —
        // it was never posted), and the later background edge finds nothing
        // still queued. No late buzz for a prompt that no longer exists.
        attach()
        visible.value = true
        emitQueue(prompt("perm-a"))
        emitQueue()
        assertEquals(listOf("perm-a"), sink.cancelled)
        visible.value = false
        assertEquals(emptyList<String>(), sink.posted)
    }

    @Test
    fun postedThenDepartedWhileVisibleCancelsOnlyAndTheEdgeAddsNothing() {
        // Posted while hidden, then the UI opens and the prompt resolves:
        // the departure cancels the real notification, and the following
        // background edge must not resurrect it.
        attach()
        emitQueue(prompt("perm-a"))
        assertEquals(listOf("perm-a"), sink.posted)
        visible.value = true
        emitQueue()
        assertEquals(listOf("perm-a"), sink.cancelled)
        visible.value = false
        assertEquals(listOf("perm-a"), sink.posted)
    }

    @Test
    fun backgroundEdgePostIsBookkeptSoLaterEdgesAndTeardownOwnIt() {
        // Pins the `postedIds += id` line in onVisibility (review finding:
        // it was deletable with the whole suite staying green). Without it
        // the edge-posted notification is invisible to the ownership
        // bookkeeping, which breaks in BOTH directions asserted here: every
        // later visible→hidden cycle re-posts (re-BUZZES) the same
        // still-pending prompt, and cancelAllPosted — the graceful-death
        // sweep — misses it, leaving a zombie whose action PendingIntents
        // point at a dead instance: the exact class #24/#25 exist to kill.
        attach()
        visible.value = true
        emitQueue(prompt("perm-a"))
        visible.value = false
        assertEquals(listOf("perm-a"), sink.posted)
        // Open and background again with perm-a still queued: exactly once.
        visible.value = true
        visible.value = false
        assertEquals("a later background edge must not re-post", listOf("perm-a"), sink.posted)
        // And the graceful sweep owns what the edge posted.
        collector.cancelAllPosted()
        assertEquals(listOf("perm-a"), sink.cancelled)
    }

    // ------------------------------------------------------------------
    // Departure = cancellation, unchanged by #59 for LIVE ids.
    // ------------------------------------------------------------------

    @Test
    fun departuresCancelEvenWhileVisibleAndEvenIfNeverPosted() {
        // Cancellation ignores visibility AND posted-ness: cancel(tag) on a
        // never-posted id is an idempotent no-op at the NotificationManager,
        // and unconditional cancel is what keeps the diff the single
        // cancellation path (no "was it posted?" branch to rot).
        visible.value = true
        collector.onQueue(listOf(prompt("perm-a")))
        collector.onQueue(emptyList())
        assertEquals(listOf("perm-a"), sink.cancelled)
        assertEquals(emptyList<String>(), sink.posted)
    }

    @Test
    fun liveIdsStillCancelImmediatelyOnDepartureEvenBeforeConnected() {
        // The #59 deferral is scoped to ADOPTED ids only: a prompt this
        // collector itself posted is proven-live bookkeeping, and its
        // departure cancels immediately even while the connection is still
        // reconnecting — deferring live cancels would leave answered
        // prompts in the shade for the length of every reconnect.
        attach() // connection stays Stopped throughout
        emitQueue(prompt("perm-live"))
        assertEquals(listOf("perm-live"), sink.posted)
        emitQueue()
        assertEquals(listOf("perm-live"), sink.cancelled)
    }

    // ------------------------------------------------------------------
    // Adoption + the post-Connected settle window (#59, edges 1-2).
    //
    // CONTRACT REWRITE (second-round review finding): the first cut gated
    // the adopted-survivor verdict on "the first queue emission after
    // Connected" — and that emission is NOT the authoritative pending set.
    // The reducer applies each replayed permission-request frame as its own
    // state emission, so the first one can be a PARTIAL set (wholesale
    // adjudication on it cancelled a still-pending survivor and re-buzzed
    // it one emission later), and when the replay changes nothing at all
    // (the only pending prompt resolved while the watch was dead — the
    // issue's HEADLINE case) the distinctUntilChanged'd queue never
    // re-emits and the verdict never fired: the zombie lingered forever.
    // The verdict is now TIME-driven: Connected arms a settle window
    // (REPLAY_SETTLE_MS), emissions inside it only ever GRADUATE survivors
    // (never cancel them), and the window's close cancels exactly the
    // never-re-confirmed leftovers against the freshest queue. Tests drive
    // the verdict via adjudicateAdopted directly (a default-window timer
    // cannot fire inside a JVM test); the zero-window test pins the
    // Connected→delay→adjudicate glue itself.
    // ------------------------------------------------------------------

    @Test
    fun adoptedSurvivorsAreNotCancelledByThePreReplayEmptyQueue() {
        // The shade holds a dead process's perm-a/perm-b; the fresh
        // collector attaches into an EMPTY queue (the replay has not landed)
        // with the connection not yet Connected. An undeferred diff here is
        // the cancel+re-buzz bug: it would cancel both survivors now and
        // re-post (re-alert — setOnlyAlertOnce does not survive a
        // cancel+notify cycle) whichever the replay re-adds.
        sink.shade += setOf("perm-a", "perm-b")
        attach()
        assertEquals(emptyList<String>(), sink.cancelled)
        assertEquals(emptyList<String>(), sink.posted)
        // Still nothing while the engine works through reconnecting.
        connection.value = ConnectionState.Reconnecting(attempt = 1, reason = "process death")
        emitQueue()
        assertEquals(emptyList<String>(), sink.cancelled)
        assertEquals(emptyList<String>(), sink.posted)
    }

    @Test
    fun partialReplayEmissionsNeverCancelThenRebuzzAStillPendingSurvivor() {
        // THE second-round finding's deterministic repro, now pinned: the
        // replay of two pending prompts lands as TWO emissions ([x], then
        // [x,y]) because the reducer emits per frame. The pre-fix wholesale
        // adjudication on the first post-Connected emission cancelled
        // still-pending perm-y at [x] and re-posted (re-BUZZED — postTime
        // reset, setOnlyAlertOnce voided by the cancel) it at [x,y]: the
        // exact cycle adoption exists to kill, inflicted by the fix itself.
        // Under the settle-window contract the emissions only graduate.
        sink.shade += setOf("perm-x", "perm-y")
        attach() // default window: the timer cannot fire inside this test
        connection.value = ConnectionState.Connected
        emitQueue(prompt("perm-x")) // frame 1 applied, frame 2 still in flight
        assertEquals("a partial set must not adjudicate perm-y", emptyList<String>(), sink.cancelled)
        emitQueue(prompt("perm-x"), prompt("perm-y")) // frame 2 lands
        assertEquals(emptyList<String>(), sink.cancelled)
        assertEquals("no re-post (no re-buzz) at any point", emptyList<String>(), sink.posted)
        // The window's eventual verdict: everything re-confirmed, nothing
        // left to judge.
        collector.adjudicateAdopted(state.value.permissionQueue)
        assertEquals(emptyList<String>(), sink.cancelled)
        assertEquals(emptyList<String>(), sink.posted)
        // Both graduated to ordinary live ids: departures cancel at once.
        emitQueue(prompt("perm-x"))
        assertEquals(listOf("perm-y"), sink.cancelled)
    }

    @Test
    fun settleWindowVerdictCancelsExactlyTheNeverReconfirmedSurvivor() {
        // perm-b resolved while the process was dead — no replay frame ever
        // re-confirms it; perm-a's frame lands inside the window. The
        // verdict cancels perm-b exactly once and leaves perm-a's ORIGINAL
        // notification wholly untouched: the recording sink proves ZERO
        // calls for perm-a — no cancel, no re-post, hence no second buzz.
        sink.shade += setOf("perm-a", "perm-b")
        attach()
        connection.value = ConnectionState.Connected
        emitQueue(prompt("perm-a"))
        assertEquals("graduation is not adjudication", emptyList<String>(), sink.cancelled)
        collector.adjudicateAdopted(state.value.permissionQueue)
        assertEquals(listOf("perm-b"), sink.cancelled)
        assertEquals(emptyList<String>(), sink.posted)
        // From here on perm-a is an ordinary live id: its later departure
        // cancels like any other.
        emitQueue()
        assertEquals(listOf("perm-b", "perm-a"), sink.cancelled)
    }

    @Test
    fun survivorResolvedWhileDeadCancelsAfterTheWindowWithNoQueueEmissionAtAll() {
        // Issue #59's HEADLINE orphan, unfixable by any emission-gated
        // verdict: the ONLY pending prompt was answered from the desktop
        // while the watch was dead, so the replay changes nothing and the
        // distinctUntilChanged'd queue NEVER re-emits after the initial
        // pre-replay empty delivery — the first-cut gate simply never fired
        // and the zombie Approve/Deny lingered forever (second-round review
        // finding). The verdict is time-since-Connected, so it fires anyway.
        // Zero window + Unconfined pins the Connected→delay→adjudicate glue
        // deterministically: delay(0) never suspends, so the Connected
        // write runs the whole chain synchronously on the test thread.
        sink.shade += setOf("perm-x")
        val instant = ApprovalNotificationCollector(sink, visibility = visible, settleMs = 0L, visibilityDebounceMs = 0L)
        instant.attach(scope, state, connection)
        assertEquals("pre-replay empty queue: survivor untouched", emptyList<String>(), sink.cancelled)
        connection.value = ConnectionState.Connected
        assertEquals(
            "window closed, nothing re-confirmed perm-x: the zombie goes",
            listOf("perm-x"),
            sink.cancelled,
        )
        assertEquals(emptyList<String>(), sink.posted)
    }

    @Test
    fun verdictGraduatesRatherThanCancelsAReconfirmationItRaced() {
        // The timer's final read is the CURRENT queue, not the last one the
        // collect processed: a replay emission can still be in dispatch
        // flight when the window closes, and an id present in the queue at
        // verdict time is pending, full stop — graduate it, never cancel.
        sink.shade += setOf("perm-a")
        attach()
        connection.value = ConnectionState.Connected
        // No emission has reached onQueue; the verdict is handed the queue
        // the racing emission carries.
        collector.adjudicateAdopted(listOf(prompt("perm-a")))
        assertEquals(emptyList<String>(), sink.cancelled)
        assertEquals(emptyList<String>(), sink.posted)
        // And it graduated: the emission arriving right after changes
        // nothing, a later departure cancels like any live id.
        emitQueue(prompt("perm-a"))
        assertEquals(emptyList<String>(), sink.posted)
        emitQueue()
        assertEquals(listOf("perm-a"), sink.cancelled)
    }

    @Test
    fun adoptedIdReconfirmedByAnyEmissionGraduatesToLiveSemantics() {
        // A queue emission that CONTAINS an adopted id proves it pending
        // regardless of the settle window: from that moment it is an
        // ordinary live id, so a later departure cancels immediately — even
        // if Connected was never observed (a hook-abort push or local
        // dismiss can remove it mid-reconnect).
        sink.shade += setOf("perm-a")
        attach()
        emitQueue(prompt("perm-a")) // pre-Connected re-confirmation
        assertEquals(emptyList<String>(), sink.posted) // known: never re-posted
        emitQueue()
        assertEquals(listOf("perm-a"), sink.cancelled)
    }

    // ------------------------------------------------------------------
    // Teardown bookkeeping (#25), now composed with adoption (#59).
    // ------------------------------------------------------------------

    @Test
    fun cancelAllPostedCancelsExactlyThePostedSubset() {
        // perm-a reached the shade; perm-b was withheld while the UI was
        // visible. Teardown owns ONLY what it posted — a knownIds sweep here
        // would cancel a notification this collector never created.
        visible.value = false
        collector.onQueue(listOf(prompt("perm-a")))
        visible.value = true
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
        // "exactly what this collector owns" claim true over time.
        visible.value = false
        collector.onQueue(listOf(prompt("perm-a"), prompt("perm-b")))
        collector.onQueue(listOf(prompt("perm-b")))
        assertEquals(listOf("perm-a"), sink.cancelled)
        sink.cancelled.clear()
        collector.cancelAllPosted()
        assertEquals(listOf("perm-b"), sink.cancelled)
    }

    @Test
    fun cancelAllPostedIsIdempotent() {
        visible.value = false
        collector.onQueue(listOf(prompt("perm-a")))
        collector.cancelAllPosted()
        collector.cancelAllPosted()
        assertEquals(listOf("perm-a"), sink.cancelled)
    }

    @Test
    fun gracefulStopEmptiesTheShadeSoTheNextAttachAdoptsNothing() {
        // The #59 composition invariant: adoption exists for UNGRACEFUL
        // death only. A graceful stop (onDestroy → cancelAllPosted) sweeps
        // the shade — including anything this collector had itself ADOPTED
        // — so the successor's attach finds no survivors and the ordinary
        // empty-start behavior holds. Without this, every restart would
        // "adopt" ghosts of notifications that no longer exist.
        sink.shade += setOf("perm-old") // survivor from two processes ago
        attach()
        emitQueue(prompt("perm-new")) // posted for real (hidden UI)
        collector.cancelAllPosted() // graceful death: posted AND adopted go
        assertTrue("the shade must be empty after a graceful stop", sink.shade.isEmpty())
        assertEquals(setOf("perm-old", "perm-new"), sink.cancelled.toSet())

        // The successor: fresh collector, same (now-empty) shade.
        sink.posted.clear()
        sink.cancelled.clear()
        val successor = ApprovalNotificationCollector(sink, visibility = visible, visibilityDebounceMs = 0L)
        val successorState = MutableStateFlow(BridgeViewModel.UiState())
        val successorConnection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        successor.attach(scope, successorState, successorConnection)
        successorState.value = BridgeViewModel.UiState(permissionQueue = emptyList())
        assertEquals("nothing adopted, nothing to cancel", emptyList<String>(), sink.cancelled)
        assertEquals(emptyList<String>(), sink.posted)
    }
}
