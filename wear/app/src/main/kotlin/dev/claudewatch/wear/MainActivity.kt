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
import androidx.compose.runtime.DisposableEffect
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
import dev.claudewatch.wear.speech.DictationController
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
 * reducer's state. The pre-Halo pager (ui/SessionPagerScreen.kt) has no live
 * references left — the instrumented tests all migrated to Halo — and is
 * retained only as the wave-2 reference implementation; retiring it (and
 * ui/PermissionSheet.kt around the still-consumed LOCAL_DISMISS_AFTER_FAILURES
 * constant) is deliberately outside the Halo v2 sweep's scope (#104).
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
    // No haptics swap here: BridgeViewModel.singleton installs the real
    // grammar at construction (#129) — a composition-scoped swap would leave
    // a sticky-service-revived process on the silent no-op.
    val state by viewModel.state.collectAsState()
    // Voice input (issue #134): an IN-APP listening screen on a raw
    // SpeechRecognizer bound to the watch's recognition service — our own
    // window, so it can hold the screen on and stitch recogniser turns until
    // the user's own stop (DictationController). The system recognizer
    // activity (RecognizerIntent.ACTION_RECOGNIZE_SPEECH) remains the
    // FALLBACK for devices with no recognition service (the emulator) and for
    // a denied microphone permission (it records under its own permission).
    // Either way the transcription follows the exact same ack-gated send path
    // as typed text (BridgeViewModel.dictationResult); a cancelled or empty
    // recognition sends nothing.
    // The fallback round-trips through an activity result, so the session
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
    // Where a finished transcription goes — shared by the in-app session and
    // the fallback's activity result.
    fun deliverTranscription(spoken: String?) {
        val sink = dictationSink.value
        dictationSink.value = null
        val wasAnswer = dictationIsAnswer.value
        dictationIsAnswer.value = false
        if (spoken.isNullOrBlank()) return
        when {
            !wasAnswer -> viewModel.dictationResult(spoken, dictationTarget.value)
            sink != null -> sink(spoken)
            // An ANSWER redelivered after recreation: the card's buffer is
            // gone. Sending it as a command would poke the wrong session
            // while the agent stays blocked — so it is NOT sent, and since
            // the user may have watched it transcribe on our own screen
            // (issue #134), the loss is said out loud rather than silent.
            else -> viewModel.dictationFailed("Answer not delivered — dictate it again from the question")
        }
    }
    val speech = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        deliverTranscription(if (result.resultCode == Activity.RESULT_OK) spoken else null)
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
    // The in-app session (issue #134). Activity-scoped like the launchers
    // above: SpeechRecognizer wants a foreground context and the main thread.
    val dictation = remember { DictationController(context) }
    val listening by dictation.state.collectAsState()
    // Composition gone (activity recreation): no screen is left to render
    // or finish the session — release the microphone rather than leak it.
    DisposableEffect(dictation) { onDispose { if (dictation.running) dictation.discard() } }
    // The prompt of the dictation waiting on the microphone permission
    // dialog; the target/answer slots above already hold its routing.
    val pendingPrompt = rememberSaveable { mutableStateOf<String?>(null) }
    fun startInApp(prompt: String) {
        dictation.start(object : DictationController.Outcome {
            override fun onText(text: String) = deliverTranscription(text)
            override fun onFailed(reason: String, beforeSpeech: Boolean) {
                // A dead start (the service never opened the mic) loses
                // nothing yet: the system recognizer activity gets its turn.
                // A failure mid-speech with no text kept is surfaced as such.
                if (beforeSpeech) launchRecognizer(prompt) else {
                    dictationSink.value = null
                    dictationIsAnswer.value = false
                    viewModel.dictationFailed(reason)
                }
            }
        })
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val prompt = pendingPrompt.value ?: return@rememberLauncherForActivityResult
        pendingPrompt.value = null
        // Denied: the fallback records under the recognizer app's own grant.
        if (granted) startInApp(prompt) else launchRecognizer(prompt)
    }
    fun beginDictation(prompt: String) {
        when {
            !DictationController.recognitionAvailable(context) -> launchRecognizer(prompt)
            !DictationController.hasMicPermission(context) -> {
                pendingPrompt.value = prompt
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> startInApp(prompt)
        }
    }
    // Leaving the activity mid-dictation (wrist gesture, notification) parks
    // the text in REVIEW rather than letting the recogniser run unattended
    // in the background or dropping what was said.
    DisposableEffect(lifecycleOwner, dictation) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (dictation.running) dictation.review()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    HaloApp(
        ui = state,
        ambient = ambient,
        listening = listening,
        actions = HaloActions(
            onPair = viewModel::pair,
            onDiscoverForPairing = viewModel::discoverForPairing,
            onDiscoverBridges = viewModel::discoverBridgesForPairing,
            onPairByDiscovery = viewModel::pairByDiscovery,
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
                beginDictation(if (title != null) "To $title" else "Command for the agent")
            },
            onDictateAnswer = { onResult ->
                dictationSink.value = onResult
                dictationIsAnswer.value = true
                beginDictation("Answer the agent's question")
            },
            onListeningStop = dictation::stop,
            onListeningReview = dictation::review,
            onListeningSend = dictation::send,
            onListeningDiscard = {
                // Nothing will be delivered: drop the routing with the text.
                dictationSink.value = null
                dictationIsAnswer.value = false
                dictation.discard()
            },
            onAnswerPermission = viewModel::answerPermission,
            onAnswerOption = viewModel::answerAgentOption,
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
