package dev.claudewatch.wear

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.claudewatch.shared.protocol.PermissionOption
import dev.claudewatch.wear.data.AesGcmTokenCipher
import dev.claudewatch.wear.data.CredentialStore
import dev.claudewatch.wear.net.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * Actionable approval notifications end-to-end (issue #25): a permission
 * request over a fake bridge's SSE stream posts a real notification whose
 * REAL action PendingIntents answer through the REAL service — modeled on
 * CatchUpFlowTest's fake-bridge fixture (MockWebServer + own ViewModel + own
 * CredentialStore; engine shutdown BEFORE server shutdown in @After, that
 * test's ordering war story).
 *
 * The singleton problem, and its resolution: the production notifier rides
 * BridgeSessionService + BridgeViewModel.singleton, and pairing the SINGLETON
 * to this test's throwaway server would leave its persistent store pointing
 * at a dead port for every later test class (the leak CatchUpFlowTest's kdoc
 * warns about). So this test attaches the SAME extracted collector
 * ([ApprovalNotificationCollector] — the exact class the service hosts) to
 * its OWN ViewModel and a real NotificationManager, and for the answer path —
 * where .send() necessarily starts the real service, because the action
 * intents name it — swaps [BridgeSessionService.viewModelResolver] to this
 * test's ViewModel. One code path, two hosts; the singleton store is never
 * touched.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalNotificationFlowTest {

    // Without POST_NOTIFICATIONS on API 33+, notify() is silently dropped and
    // activeNotifications stays empty — the WalkingSkeletonTest idiom.
    @get:Rule
    val notificationPermission: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var viewModel: BridgeViewModel
    private lateinit var notificationManager: NotificationManager
    private lateinit var collectorScope: CoroutineScope

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        notificationManager = context.getSystemService(NotificationManager::class.java)
        // A previous test's leftovers (any tag/channel) must not satisfy or
        // pollute this test's presence assertions.
        notificationManager.cancelAll()
        server = MockWebServer()
        server.start()
        val storeFile = File.createTempFile("approval-conn", ".bin", context.cacheDir)
        viewModel = BridgeViewModel(CredentialStore({ storeFile }, AesGcmTokenCipher { key }))
        // The backgrounded-app premise of the whole feature, made explicit:
        // in this harness no activity ever starts, but the flag is process
        // state another test (MainActivity-based) may have left true.
        AppVisibility.uiVisible = false
        // The answer path's seam (see the resolver's kdoc): the service a
        // .send() starts must answer through THIS test's ViewModel.
        BridgeSessionService.viewModelResolver = { viewModel }
        // The REAL collector — the class the service hosts — on this test's
        // ViewModel and the real NotificationManager. The connection flow
        // rides along (#59): attach adopts shade survivors (none here — the
        // cancelAll above guarantees an empty shade) and gates their
        // adjudication on it.
        collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ApprovalNotificationCollector(ApprovalNotifier(context))
            .attach(collectorScope, viewModel.state, viewModel.connection)
    }

    @After
    fun tearDown() {
        // The service first (tests that .send() an action started the real
        // one, attached to this test's ViewModel): its onDestroy cancels its
        // posted notifications and kills its collectors before the ViewModel
        // they collect goes down.
        BridgeSessionService.stop(context)
        BridgeSessionService.viewModelResolver = { BridgeViewModel.singleton(it) }
        collectorScope.cancel()
        // Engine BEFORE server — the CatchUpFlowTest ordering war story: the
        // test ends Connected to a held-open stream, and a server shutdown
        // under a live engine spawns reconnect + port-probe churn that
        // outlives the test in the shared instrumentation process.
        viewModel.shutdown()
        server.shutdown()
        notificationManager.cancelAll()
    }

    // ------------------------------------------------------------------
    // Fixture plumbing
    // ------------------------------------------------------------------

    private fun waitFor(message: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for: $message")
    }

    /** The approval notification for [permissionId], if currently in the shade. */
    private fun approvalNotification(permissionId: String): StatusBarNotification? =
        notificationManager.activeNotifications.firstOrNull {
            it.tag == permissionId && it.id == ApprovalNotifier.APPROVAL_NOTIFICATION_ID
        }

    /** [permissionId]'s currently posted Approve action — fails loudly if absent. */
    private fun approveActionOf(permissionId: String): android.app.Notification.Action =
        approvalNotification(permissionId)!!.notification.actions.orEmpty()
            .firstOrNull { it.title.toString() == "Approve" }
            ?: throw AssertionError("$permissionId has no Approve action")

    private fun takeRequest(label: String) =
        server.takeRequest(30, TimeUnit.SECONDS) ?: throw AssertionError("no request: $label")

    private val sessionEvent =
        """{"state":"running","agent":"claude","cwd":"/tmp/proj","folderName":"proj","sessionId":"s-1"}"""

    private fun permissionRequest(permissionId: String, toolName: String, command: String) =
        """{"permissionId":"$permissionId","sessionId":"s-1","tool_name":"$toolName",""" +
            """"tool_input":{"command":"$command"},""" +
            """"options":[{"behavior":"allow","label":"Yes"},{"behavior":"deny","label":"No"}]}"""

    private fun frame(id: Int, type: String, data: String) =
        "id: $id\nevent: $type\ndata: $data\n\n"

    /**
     * Pair against the fake bridge with [streamBody] as the (held-open,
     * throttled) SSE response — the DictationFlowTest pattern. The throttle
     * doubles as this fixture's event sequencer: pad comments between events
     * become wall-clock gaps, which is how the cancellation test observes
     * "posted, THEN cleared" instead of a conflated final state.
     */
    private fun pair(streamBody: String) {
        server.enqueue(
            MockResponse().setBody("""{"proto":"3","bridgeId":"b-1","machineName":"m"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"token":"tok-1","bridgeId":"b-1","sessions":[]}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(512, 250, TimeUnit.MILLISECONDS)
                .setBody(streamBody),
        )
        viewModel.pair("127.0.0.1", server.port.toString(), "123456")
        waitFor("stream open") { viewModel.state.value.status == "paired, stream open" }
        takeRequest("pair preflight") // /v1/ping
        takeRequest("pair")           // /v1/pair
        takeRequest("events")         // /v1/events
    }

    /** Enough held-open pad to outlast the test (512 B / 250 ms ≈ 2 KB/s). */
    private fun holdOpenPad() = ":pad\n\n".repeat(20_000)

    // ------------------------------------------------------------------
    // Acceptance 1: a streamed permission request posts an actionable
    // notification on the approvals channel, tagged by its permissionId.
    // ------------------------------------------------------------------

    @Test
    fun permissionRequestOverSsePostsATaggedActionableNotification() {
        pair(
            buildString {
                append(":connected\n\n")
                append(frame(1, "session", sessionEvent))
                append(frame(2, "permission-request", permissionRequest("perm-a", "Bash", "rm -rf ./build")))
                append(holdOpenPad())
            },
        )

        waitFor("perm-a notification") { approvalNotification("perm-a") != null }
        val posted = approvalNotification("perm-a")!!
        assertEquals(ApprovalNotifier.CHANNEL_ID, posted.notification.channelId)
        // The canonical option list, verbatim order, behavior-labeled.
        val titles = posted.notification.actions.orEmpty().map { it.title.toString() }
        assertEquals(listOf("Approve", "Deny"), titles)
    }

    // ------------------------------------------------------------------
    // Acceptance 2: two concurrent prompts get two distinct notifications,
    // and EACH one's Approve action answers ITS OWN permissionId — the
    // load-bearing distinctness proof (PendingIntent identity ignores
    // extras; a recycled intent here would approve the wrong permission).
    //
    // Both actions are extracted up front and the FIRST-posted prompt's is
    // sent FIRST, and that ordering is itself load-bearing: under the
    // recycling bug this guards against (a shared requestCode + a
    // filterEquals-identical intent + UPDATE_CURRENT), the SECOND
    // getService() rewrites the SHARED registration's extras to perm-b — so
    // the LAST-posted action always answers correctly and only the
    // FIRST-posted one carries the corruption. A test that taps only
    // perm-b's Approve (this test's original shape, review finding) passes
    // under the very bug it documents; tapping perm-a's is where a recycled
    // intent produces the wrong-id POST and the assertion bites.
    // ------------------------------------------------------------------

    @Test
    fun concurrentPromptsApproveActionsEachAnswerTheirOwnId() {
        pair(
            buildString {
                append(":connected\n\n")
                append(frame(1, "session", sessionEvent))
                append(frame(2, "permission-request", permissionRequest("perm-a", "Bash", "rm -rf ./build")))
                append(frame(3, "permission-request", permissionRequest("perm-b", "Bash", "npm test")))
                append(holdOpenPad())
            },
        )

        waitFor("both notifications") {
            approvalNotification("perm-a") != null && approvalNotification("perm-b") != null
        }

        // Snapshot BOTH actions before answering anything: answering perm-a
        // cancels its notification, and the registrations as they exist
        // AFTER both posts are exactly what a cross-id recycling bug would
        // have corrupted. (The Notification objects keep their PendingIntent
        // references alive past the cancel.)
        val approveA = approveActionOf("perm-a")
        val approveB = approveActionOf("perm-b")

        // The answer POST's response, queued BEFORE the send. The send is
        // the real thing: it starts the real BridgeSessionService, whose
        // ACTION_ANSWER path answers through (the resolver-swapped) ViewModel.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        approveA.actionIntent.send()

        val first = takeRequest("perm-a answer POST")
        assertEquals("/v1/command", first.path)
        val firstBody = JSONObject(first.body.readUtf8())
        assertEquals(
            "the FIRST-posted prompt's tap must answer ITS OWN id",
            "perm-a",
            firstBody.getString("permissionId"),
        )
        assertEquals("allow", firstBody.getJSONObject("decision").getString("behavior"))

        // The 2xx ack drops perm-a from the queue; the diff cancels exactly
        // its tag. perm-b — unanswered — must survive untouched.
        waitFor("perm-a cancelled") { approvalNotification("perm-a") == null }
        assertNotNull("perm-b must remain posted", approvalNotification("perm-b"))

        // And the other direction: perm-b's own action names perm-b.
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        approveB.actionIntent.send()

        val second = takeRequest("perm-b answer POST")
        assertEquals("/v1/command", second.path)
        val secondBody = JSONObject(second.body.readUtf8())
        assertEquals(
            "the SECOND-posted prompt's tap must answer ITS OWN id",
            "perm-b",
            secondBody.getString("permissionId"),
        )
        assertEquals("allow", secondBody.getJSONObject("decision").getString("behavior"))
        waitFor("perm-b cancelled") { approvalNotification("perm-b") == null }
    }

    // ------------------------------------------------------------------
    // Acceptance 3: a permission-cleared SSE event cancels exactly the
    // cleared prompt's notification (the cancellation IS the queue diff —
    // no separate cleared listener exists to drift from it).
    // ------------------------------------------------------------------

    @Test
    fun permissionClearedEventCancelsExactlyThatTag() {
        pair(
            buildString {
                append(":connected\n\n")
                append(frame(1, "session", sessionEvent))
                append(frame(2, "permission-request", permissionRequest("perm-a", "Bash", "rm -rf ./build")))
                append(frame(3, "permission-request", permissionRequest("perm-b", "Write", "notes.txt")))
                // ~10 s of throttled pad: the wall-clock gap that lets the
                // test OBSERVE both notifications posted before the cleared
                // event streams in — without it the queue emissions can
                // conflate and the cancel would be asserted against a prompt
                // that never visibly posted.
                append(":pad\n\n".repeat(3_000))
                append(frame(4, "permission-cleared", """{"permissionId":"perm-a","reason":"hook-abort"}"""))
                append(holdOpenPad())
            },
        )

        waitFor("both notifications") {
            approvalNotification("perm-a") != null && approvalNotification("perm-b") != null
        }
        // The cleared event lands after the pad: exactly perm-a goes.
        waitFor("perm-a cancelled by permission-cleared") { approvalNotification("perm-a") == null }
        assertNotNull("perm-b must remain posted", approvalNotification("perm-b"))
    }

    // ------------------------------------------------------------------
    // Acceptance 4: a single-question AskUserQuestion prompt's RemoteInput
    // reply answers with the entered text.
    // ------------------------------------------------------------------

    @Test
    fun singleQuestionRemoteInputReplyAnswersWithTheText() {
        val question =
            """{"permissionId":"perm-q","sessionId":"s-1","tool_name":"AskUserQuestion",""" +
                """"tool_input":{"questions":[{"question":"Ship it?","options":[{"label":"Yes please"}]}]}}"""
        pair(
            buildString {
                append(":connected\n\n")
                append(frame(1, "session", sessionEvent))
                append(frame(2, "permission-request", question))
                append(holdOpenPad())
            },
        )

        waitFor("perm-q notification") { approvalNotification("perm-q") != null }
        val posted = approvalNotification("perm-q")!!
        val reply = posted.notification.actions.orEmpty()
            .firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: throw AssertionError("single-question prompt has no RemoteInput action")

        // The reply surface speaks the AGENT's language and only that: the
        // question's own option labels are the choice chips, and Wear's
        // ML-generated Smart Replies are banned (live-demo lesson: "Good
        // question" rendered as if the agent offered it, and a mis-tap
        // answers a blocked session with Google's guess).
        assertEquals(
            listOf("Yes please"),
            reply.remoteInputs!!.single().choices.orEmpty().map { it.toString() },
        )
        assertTrue(
            "generated smart replies must be off on the reply action",
            !reply.allowGeneratedReplies,
        )

        // Simulate the system UI delivering the typed/dictated reply: the
        // results ride a fill-in intent exactly as RemoteInput specifies —
        // which is also why the action's PendingIntent must be MUTABLE (an
        // immutable one strips the fill-in on API 31+).
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(
            reply.remoteInputs,
            fillIn,
            Bundle().apply {
                putCharSequence(ApprovalNotifier.KEY_QUESTION_ANSWER, "yes please")
            },
        )
        reply.actionIntent.send(context, 0, fillIn)

        val request = takeRequest("answers POST")
        assertEquals("/v1/command", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("perm-q", body.getString("permissionId"))
        val decision = body.getJSONObject("decision")
        assertEquals("allow", decision.getString("behavior"))
        val answers = decision.getJSONArray("answers")
        assertEquals(1, answers.length())
        assertEquals("yes please", answers.getString(0))

        // And the ack-driven dismissal: answered means gone from the shade.
        waitFor("perm-q cancelled") { approvalNotification("perm-q") == null }
        assertTrue(
            "no other approval notification may linger",
            notificationManager.activeNotifications.none {
                it.id == ApprovalNotifier.APPROVAL_NOTIFICATION_ID
            },
        )
    }

    // ------------------------------------------------------------------
    // Second live-demo lesson: setChoices chips render NOWHERE on this Wear
    // image, so the option labels double as plain one-tap action BUTTONS —
    // the only deterministically rendered surface. This leg drives one.
    // ------------------------------------------------------------------

    @Test
    fun optionButtonAnswersWithItsOwnLabel() {
        val question =
            """{"permissionId":"perm-opt","sessionId":"s-1","tool_name":"AskUserQuestion",""" +
                """"tool_input":{"questions":[{"question":"Ship now or wait?",""" +
                """"options":[{"label":"Ship now"},{"label":"Wait for review"}]}]}}"""
        pair(
            buildString {
                append(":connected\n\n")
                append(frame(1, "session", sessionEvent))
                append(frame(2, "permission-request", question))
                append(holdOpenPad())
            },
        )

        waitFor("perm-opt notification") { approvalNotification("perm-opt") != null }
        val posted = approvalNotification("perm-opt")!!
        val actions = posted.notification.actions.orEmpty()
        // Both option buttons + the free-text Reply, in that order — the
        // full 3-action budget, never a truncated option menu.
        assertEquals(
            listOf("Ship now", "Wait for review", "Reply"),
            actions.map { it.title.toString() },
        )

        // Tap the SECOND option's button: the answer POST must carry THAT
        // label (a collapsed PendingIntent would answer with the first's).
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        actions.first { it.title.toString() == "Wait for review" }.actionIntent.send()

        val request = takeRequest("option answer POST")
        assertEquals("/v1/command", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("perm-opt", body.getString("permissionId"))
        val decision = body.getJSONObject("decision")
        assertEquals("allow", decision.getString("behavior"))
        val answers = decision.getJSONArray("answers")
        assertEquals(1, answers.length())
        assertEquals("Wait for review", answers.getString(0))

        waitFor("perm-opt cancelled") { approvalNotification("perm-opt") == null }
    }

    // ------------------------------------------------------------------
    // Acceptance 4's guard rail: a blank or resultless RemoteInput delivery
    // is DROPPED — "never answer with empty text". An accidental empty
    // dictation must not become the agent's answer; before this test
    // existed, a one-line sabotage (deleting the isNullOrEmpty guard or the
    // trim in handleApprovalAnswer) passed the entire suite, because no
    // test ever delivered a blank result (review finding).
    // ------------------------------------------------------------------

    @Test
    fun blankOrResultlessRemoteInputReplyIsDroppedAndThePromptStaysQueued() {
        val question =
            """{"permissionId":"perm-q","sessionId":"s-1","tool_name":"AskUserQuestion",""" +
                """"tool_input":{"questions":[{"question":"Ship it?","options":[{"label":"Yes please"}]}]}}"""
        pair(
            buildString {
                append(":connected\n\n")
                append(frame(1, "session", sessionEvent))
                append(frame(2, "permission-request", question))
                append(holdOpenPad())
            },
        )

        waitFor("perm-q notification") { approvalNotification("perm-q") != null }
        val posted = approvalNotification("perm-q")!!
        val reply = posted.notification.actions.orEmpty()
            .firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: throw AssertionError("single-question prompt has no RemoteInput action")

        // The accidental empty dictation: whitespace-only results. NOTHING
        // is enqueued on the server — a POST would go unanswered and hang
        // the engine's call, but more to the point the takeRequest below
        // proves no POST was even attempted.
        val blankFillIn = Intent()
        RemoteInput.addResultsToIntent(
            reply.remoteInputs,
            blankFillIn,
            Bundle().apply {
                putCharSequence(ApprovalNotifier.KEY_QUESTION_ANSWER, "   ")
            },
        )
        reply.actionIntent.send(context, 0, blankFillIn)

        // The tapped notification still cancels IMMEDIATELY — the
        // responsiveness contract (never re-raise; the in-app card is the
        // retry surface) applies to dropped replies too. This wait doubles
        // as the proof the first delivery was fully handled before the
        // no-POST window below starts.
        waitFor("perm-q cancelled on receipt despite the drop") {
            approvalNotification("perm-q") == null
        }

        // And the degenerate delivery: no results bundle at all. The reply
        // intent carries no behavior extra either, so BOTH answer routes
        // must decline it.
        reply.actionIntent.send(context, 0, Intent())

        // Neither delivery may reach the bridge. pair() consumed the
        // fixture's three requests (ping, pair, events), so ANY request
        // arriving in this window would be an answer POST.
        assertNull(
            "a blank/resultless reply must never become an answer POST",
            server.takeRequest(3, TimeUnit.SECONDS),
        )

        // Nothing was sent, so nothing was resolved: the prompt stays
        // queued, and the in-app question card remains the way to answer it
        // properly.
        assertTrue(
            "the unanswered prompt must stay queued in-app",
            viewModel.state.value.permissionQueue.any { it.permissionId == "perm-q" },
        )
    }

    // ------------------------------------------------------------------
    // Issue #59 — restart edges. The scenarios below simulate a process
    // kill WITHOUT onDestroy: no cancelAllPosted runs, so the shade keeps
    // notifications that no fresh in-memory collector remembers.
    // ------------------------------------------------------------------

    /** A fully-formed prompt for driving collectors (and the notifier) directly. */
    private fun pending(id: String) = BridgeViewModel.PendingPermission(
        permissionId = id,
        sessionId = "s-1",
        toolName = "Bash",
        requestSummary = "$ npm test",
        sessionLabel = "proj",
        options = listOf(
            PermissionOption("allow", "Yes"),
            PermissionOption("deny", "No"),
        ),
    )

    // ------------------------------------------------------------------
    // #59 edge 1: adoption. Collector A posts two prompts and dies WITHOUT
    // teardown (job cancel = the process-kill stand-in; its posted-ids die
    // with it). Collector B — same real NotificationManager, fresh memory —
    // must adopt both survivors, hold fire through the pre-replay window
    // (empty queue + Reconnecting), and once the post-Connected settle
    // window (REPLAY_SETTLE_MS) closes, cancel exactly the one no replay
    // emission re-confirmed. (Second-round review moved the verdict from
    // "the first post-Connected queue emission" to this timer: the reducer
    // emits once per replayed frame, so that first emission can be a
    // PARTIAL pending set — adjudicating on it re-buzzed still-pending
    // survivors — and an unchanged queue never re-emits at all, which left
    // the resolved-while-dead orphan lingering forever.) The survivor's
    // ORIGINAL notification must remain untouched: postTime is stamped by
    // notify(), so an equal postTime across the whole dance proves no
    // cancel+re-post cycle (which would also re-buzz — setOnlyAlertOnce
    // does not survive a cancel).
    // ------------------------------------------------------------------

    @Test
    fun processKillSurvivorsAreAdoptedAndAdjudicatedAfterTheSettleWindow() {
        // Collector A: the pre-kill process. Backgrounded (setUp's flag), so
        // both prompts post for real.
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stateA = MutableStateFlow(
            BridgeViewModel.UiState(permissionQueue = listOf(pending("perm-x"), pending("perm-y"))),
        )
        val connA = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        ApprovalNotificationCollector(ApprovalNotifier(context)).attach(scopeA, stateA, connA)
        waitFor("perm-x and perm-y posted") {
            approvalNotification("perm-x") != null && approvalNotification("perm-y") != null
        }
        val originalPostTime = approvalNotification("perm-x")!!.postTime

        // The kill: the scope dies, cancelAllPosted NEVER runs (that is the
        // graceful path a SIGKILL skips) — the shade keeps both.
        scopeA.cancel()

        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Collector B: the restarted process — fresh in-memory sets, an
            // empty pre-replay queue, a connection still working on it.
            val stateB = MutableStateFlow(BridgeViewModel.UiState())
            val connB = MutableStateFlow<ConnectionState>(
                ConnectionState.Reconnecting(attempt = 1, reason = "process death"),
            )
            ApprovalNotificationCollector(ApprovalNotifier(context)).attach(scopeB, stateB, connB)

            // The pre-replay window: the empty queue emission has been
            // delivered (StateFlow delivers current value at collect), and
            // it must cancel NOTHING — an undeferred diff here is the
            // cancel-then-re-buzz bug this issue exists to kill.
            Thread.sleep(1_000)
            assertNotNull("perm-x must survive the pre-replay window", approvalNotification("perm-x"))
            assertNotNull("perm-y must survive the pre-replay window", approvalNotification("perm-y"))

            // Connected arms the settle window; the replay emission landing
            // inside it graduates perm-x (perm-y was answered from the
            // desktop while the watch was dead, so no emission ever
            // re-confirms it). The sleep between the two writes mirrors the
            // real engine's order — Connected at stream open, replay frames
            // after — and keeps the emission comfortably inside the window.
            connB.value = ConnectionState.Connected
            Thread.sleep(500)
            stateB.value = BridgeViewModel.UiState(permissionQueue = listOf(pending("perm-x")))

            // The verdict fires REPLAY_SETTLE_MS (~3 s) after Connected —
            // the waitFor absorbs it.
            waitFor("perm-y (resolved while dead) cancelled by the window's close") {
                approvalNotification("perm-y") == null
            }
            val survivor = approvalNotification("perm-x")
            assertNotNull("perm-x must still be posted", survivor)
            assertEquals(
                "perm-x's ORIGINAL notification must be untouched — no cancel, no re-post",
                originalPostTime,
                survivor!!.postTime,
            )
        } finally {
            scopeB.cancel()
        }
    }

    // ------------------------------------------------------------------
    // #59 edge 2: a notification answer racing the reconnect replay. The
    // tap's PendingIntent fires while the ViewModel's queue does NOT yet
    // hold the prompt (the fixture holds the replay back behind throttled
    // pad — the on-wire stand-in for "the tap itself recreated the dead
    // process and the engine is still catching up"). The service must NOT
    // answer synchronously into the still-queued guard's silent drop; it
    // defers until the id appears, then answers with the SAME payload.
    // ------------------------------------------------------------------

    @Test
    fun notificationAnswerDefersUntilTheReplayDeliversThePromptThenAnswers() {
        pair(
            buildString {
                append(":connected\n\n")
                append(frame(1, "session", sessionEvent))
                // ~4.5 s of held-back replay (512 B / 250 ms throttle): the
                // window in which the tap lands on an empty queue.
                append(":pad\n\n".repeat(1_500))
                append(frame(2, "permission-request", permissionRequest("perm-z", "Bash", "npm test")))
                append(holdOpenPad())
            },
        )

        // The dead process's survivor, posted through the REAL notifier so
        // its Approve action carries the REAL service-addressed
        // PendingIntent (there is no other dispatcher).
        ApprovalNotifier(context).post(approvalNotificationModel(pending("perm-z")))
        waitFor("perm-z survivor posted") { approvalNotification("perm-z") != null }
        assertTrue(
            "premise: the queue must not know perm-z at tap time",
            viewModel.state.value.permissionQueue.none { it.permissionId == "perm-z" },
        )

        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        approveActionOf("perm-z").actionIntent.send()

        // The tap's instant-response contract is deferral-independent: the
        // tapped notification cancels immediately, answer sent or not.
        waitFor("perm-z cancelled on tap") { approvalNotification("perm-z") == null }

        // The deferred answer fires once the replay delivers perm-z
        // (~seconds from now, well inside ANSWER_REPLAY_WAIT_MS) — same
        // entry point, same payload as a synchronous answer. Pre-#59 this
        // request never arrived: the still-queued guard swallowed the
        // decision and the replay re-raised the prompt as "it didn't take".
        val request = takeRequest("deferred answer POST")
        assertEquals("/v1/command", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("perm-z", body.getString("permissionId"))
        assertEquals("allow", body.getJSONObject("decision").getString("behavior"))
    }

    @Test
    fun notificationAnswerWhoseReplayNeverDeliversThePromptSendsNothing() {
        // The prompt resolved (or expired) while the watch was dead: the
        // replay simply never re-delivers perm-gone. The deferred answer
        // must time out doing NOTHING — no POST invented for a prompt whose
        // truth we never re-established; the re-posted notification / the
        // in-app card is the retry surface if the prompt is in fact alive.
        pair(
            buildString {
                append(":connected\n\n")
                append(frame(1, "session", sessionEvent))
                append(holdOpenPad())
            },
        )

        ApprovalNotifier(context).post(approvalNotificationModel(pending("perm-gone")))
        waitFor("perm-gone survivor posted") { approvalNotification("perm-gone") != null }

        approveActionOf("perm-gone").actionIntent.send()

        // Instant cancel still applies (the tap deserves its response even
        // when the answer cannot be delivered)...
        waitFor("perm-gone cancelled on tap") { approvalNotification("perm-gone") == null }

        // ...but no request may ever leave the watch. pair() consumed the
        // fixture's three requests (ping, pair, events), so ANY request in
        // this bounded window would be the invented answer POST.
        assertNull(
            "an answer for a prompt the replay never delivered must never POST",
            server.takeRequest(5, TimeUnit.SECONDS),
        )
    }
}
