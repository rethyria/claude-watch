package dev.claudewatch.wear

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp
import dev.claudewatch.wear.ui.halo.HaloModel

/**
 * Entry point: the Halo UI (see ui/halo/HaloApp.kt) — ring home, per-project
 * pages, drill-down lists/feeds, approval cards — rendered from the shared
 * reducer's state. The previous pager (ui/SessionPagerScreen.kt) is kept
 * compiling for the instrumented tests until they migrate to Halo.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WatchApp()
            }
        }
    }
}

@Composable
fun WatchApp(
    viewModel: BridgeViewModel = viewModel(
        factory = BridgeViewModel.factory(LocalContext.current.applicationContext),
    ),
) {
    val context = LocalContext.current
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
        actions = HaloActions(
            onPair = viewModel::pair,
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
        ),
    )
}
