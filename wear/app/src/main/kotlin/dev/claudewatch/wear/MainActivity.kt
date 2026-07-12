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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import dev.claudewatch.wear.ui.SessionPagerActions
import dev.claudewatch.wear.ui.SessionPagerScreen

/**
 * Entry point: the session pager (see ui/SessionPagerScreen.kt). Page 0 is
 * the walking skeleton's control/debug page; every live session gets its own
 * terminal page, rendered from the shared reducer's state.
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
fun WatchApp(viewModel: BridgeViewModel = viewModel()) {
    val context = LocalContext.current
    // Swap the no-op default for the real vibrator grammar (issue #20).
    LaunchedEffect(viewModel) { viewModel.haptics = VibratorHaptics(context) }
    val state by viewModel.state.collectAsState()
    // Voice input rides the system recognizer activity
    // (RecognizerIntent.ACTION_RECOGNIZE_SPEECH) — never a raw
    // SpeechRecognizer on Wear. The transcription follows the exact same
    // ack-gated send path as typed text (BridgeViewModel.dictationResult);
    // a cancelled or empty recognition sends nothing.
    val speech = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) {
            viewModel.dictationResult(spoken)
        }
    }
    SessionPagerScreen(
        ui = state,
        actions = SessionPagerActions(
            onPair = viewModel::pair,
            onSendCommand = viewModel::sendCommand,
            onCommandDraftChange = viewModel::updateCommandDraft,
            onDictate = {
                try {
                    speech.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Command for the agent"),
                    )
                } catch (_: ActivityNotFoundException) {
                    // No recognizer on this image (common on emulators):
                    // refuse cleanly instead of crashing or pretending.
                    viewModel.dictationUnavailable()
                }
            },
            onAnswerPermission = viewModel::answerPermission,
            onAnswerQuestions = viewModel::answerQuestions,
            onDismissPermission = viewModel::dismissPermissionLocally,
            onSpawn = viewModel::spawnSession,
            onKill = viewModel::killSession,
        ),
    )
}
