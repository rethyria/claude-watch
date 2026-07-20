package dev.claudewatch.wear

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material.MaterialTheme
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import dev.claudewatch.wear.ui.halo.HaloModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * POST_NOTIFICATIONS is asked at most ONCE per process (issue #24): the
 * paired trigger below fires on every return to a paired app, and re-asking
 * a user who dismissed the dialog on every resume is nagging, not consent.
 * Denial is tolerated everywhere — the foreground service still runs, only
 * its notification/chip is invisible.
 */
private var notificationPermissionAsked = false

/**
 * Entry point: the Halo UI (see ui/halo/HaloApp.kt) — ring home, per-project
 * pages, drill-down lists/feeds, approval cards — rendered from the shared
 * reducer's state. The previous pager (ui/SessionPagerScreen.kt) is kept
 * compiling for the instrumented tests until they migrate to Halo.
 *
 * The activity is a THIN attachment point since issue #24: the engine lives
 * in [BridgeViewModel.singleton] with process lifetime, held open by
 * [BridgeSessionService] — this activity only renders it, resumes it on
 * every ON_START (catch-up-on-open after a notification Disconnect or a
 * process death), and feeds it the ambient flag.
 */
class MainActivity : ComponentActivity() {

    private val ambientState = AmbientState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wrist-down detection (issue #24): the platform observer drives the
        // holder's flag; HaloApp renders the dimmed, animation-free terminal.
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientState.callback))
        // Catch-up-on-open: EVERY return to the activity resumes the engine.
        // start() is guarded upstream — a live engine no-ops, and without
        // credentials it stays Stopped — so this is free when nothing was
        // disconnected and load-bearing when something was.
        //
        // The same observer owns the process-wide visibility flag (issue
        // #25): while the UI is on screen the in-app card is the approval
        // surface and the service's collector must not post notifications
        // over it. The flag flips BEFORE resume() on purpose — prompts the
        // resume-reconnect replays land with the UI already marked visible,
        // so opening the app never buzzes for the card it is about to show.
        // (The withhold is no longer permanent, though — issue #59: the
        // ON_STOP flip below is a real signal the collector observes via
        // AppVisibility.visible, and prompts still pending when the user
        // backgrounds the app post THEN. This observer stays the single
        // WRITER of the flag; the collector only reads/reacts.)
        // ON_START/ON_STOP (not RESUME/PAUSE): a permission dialog or the
        // recognizer activity pauses us while our UI is still the surface
        // underneath, and buzzing during those would be the same noise.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppVisibility.uiVisible = true
                BridgeViewModel.singleton(applicationContext).resume()
            }

            override fun onStop(owner: LifecycleOwner) {
                AppVisibility.uiVisible = false
            }
        })
        setContent {
            MaterialTheme {
                val ambient by ambientState.isAmbient.collectAsState()
                WatchApp(ambient = ambient)
            }
        }
    }
}

@Composable
fun WatchApp(
    ambient: Boolean = false,
    // The process-lifetime singleton, NOT viewModel(factory): a
    // ViewModelStore-scoped instance died with the activity (onCleared →
    // engine.shutdown()), killing the SSE stream on every destroy — the
    // exact defect issue #24 exists to fix.
    viewModel: BridgeViewModel = BridgeViewModel.singleton(LocalContext.current.applicationContext),
) {
    val context = LocalContext.current
    // Foreground-service wiring (issue #24): once the UI state turns paired,
    // ask for POST_NOTIFICATIONS (API 33+, once per process — see the flag's
    // doc) and start BridgeSessionService. Gated on RESUMED via the standard
    // repeatOnLifecycle idiom (same as HaloApp's usage auto-poll): Android
    // 12+ only allows FGS starts from a foreground app, and while the
    // composition being alive USUALLY implies that, a backgrounded activity
    // keeps its composition — the lifecycle gate makes it airtight. Paired
    // flipping FALSE needs nothing from here: the service watches the
    // connection state itself and dies on terminal states.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* denial tolerated: the FGS runs, only the chip is invisible */ }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.map { it.paired }.distinctUntilChanged().collect { paired ->
                if (!paired) return@collect
                if (Build.VERSION.SDK_INT >= 33 &&
                    !notificationPermissionAsked &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionAsked = true
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                BridgeSessionService.start(context)
            }
        }
    }
    // Swap the no-op default for the real vibrator grammar (issue #20).
    LaunchedEffect(viewModel) { viewModel.haptics = VibratorHaptics(context) }
    val state by viewModel.state.collectAsState()
    // Voice input rides the system recognizer activity
    // (RecognizerIntent.ACTION_RECOGNIZE_SPEECH) — never a raw
    // SpeechRecognizer on Wear. The transcription follows the exact same
    // ack-gated send path as typed text (BridgeViewModel.dictationResult);
    // a cancelled or empty recognition sends nothing.
    // The recognizer round-trips through an activity result, so the session
    // the dictation was started FOR is captured at launch and re-attached to
    // the transcription here — the ViewModel's own default is the most
    // recently WORKING session, which is the wrong target when the user
    // dictates from another session's feed. Saveable, because the
    // ActivityResult API redelivers the result across activity recreation:
    // plain remember{} would reset the target and mis-route the text.
    val dictationTarget = rememberSaveable { mutableStateOf<String?>(null) }
    // True while a QUESTION-ANSWER dictation is out: the transcription
    // belongs to the question card's answer buffer, not the command path —
    // the agent is blocked on AskUserQuestion and only answerQuestions can
    // resume it. One recognizer launch is out at a time (it is a full-screen
    // activity), so a single slot cannot be crossed. Saveable for the same
    // redelivery reason: after recreation the answer sink below is gone, and
    // without this flag the redelivered ANSWER would fall through to the
    // command path and be POSTed to the default session.
    val dictationIsAnswer = rememberSaveable { mutableStateOf(false) }
    // The answer sink itself is a lambda into live composition state and
    // cannot be saved; a redelivered answer with no sink is DROPPED (the
    // still-queued prompt simply re-prompts) rather than mis-routed.
    val dictationSink = remember { mutableStateOf<((String) -> Unit)?>(null) }
    val speech = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val sink = dictationSink.value
        dictationSink.value = null
        val wasAnswer = dictationIsAnswer.value
        dictationIsAnswer.value = false
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) {
            when {
                !wasAnswer -> viewModel.dictationResult(spoken, dictationTarget.value)
                sink != null -> sink(spoken)
                // An ANSWER redelivered after recreation: the card's buffer
                // is gone. Sending it as a command would poke the wrong
                // session while the agent stays blocked — drop it instead.
                else -> Unit
            }
        }
    }
    fun launchRecognizer(prompt: String) {
        try {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt),
            )
        } catch (_: ActivityNotFoundException) {
            // No recognizer on this image (common on emulators):
            // refuse cleanly instead of crashing or pretending.
            dictationSink.value = null
            dictationIsAnswer.value = false
            viewModel.dictationUnavailable()
        }
    }
    HaloApp(
        ui = state,
        ambient = ambient,
        actions = HaloActions(
            onPair = viewModel::pair,
            onDiscoverForPairing = viewModel::discoverForPairing,
            onUnpair = viewModel::unpair,
            onSendCommand = viewModel::sendCommand,
            onCommandDraftChange = viewModel::updateCommandDraft,
            onDictate = { sessionId ->
                dictationSink.value = null
                dictationIsAnswer.value = false
                dictationTarget.value = sessionId
                // A stale commandError would reopen the voice overlay with an
                // old failure as this dictation's outcome; clear it first.
                viewModel.dictationStarted()
                // §7 names the target during listening; the recognizer's
                // prompt line is where that survives the system activity.
                val title = sessionId?.let { id ->
                    HaloModel.from(state).sessions.firstOrNull { it.id == id }?.title
                }
                launchRecognizer(if (title != null) "To $title" else "Command for the agent")
            },
            onDictateAnswer = { onResult ->
                dictationSink.value = onResult
                dictationIsAnswer.value = true
                launchRecognizer("Answer the agent's question")
            },
            onAnswerPermission = viewModel::answerPermission,
            onAnswerQuestions = viewModel::answerQuestions,
            onDismissPermission = viewModel::dismissPermissionLocally,
            onDiscardCommand = viewModel::discardCommand,
            onSpawn = viewModel::spawnSession,
            onKill = viewModel::killSession,
            onHide = viewModel::hideSession,
            // Page entry / retry / auto-poll: the NON-FORCED fetch (the VM's
            // rate limit may skip it). The freshness label's tap is the
            // explicit "refresh NOW" and bypasses the limiter.
            onUsageOpen = { viewModel.fetchUsage() },
            onUsageRefresh = { viewModel.fetchUsage(force = true) },
        ),
    )
}
