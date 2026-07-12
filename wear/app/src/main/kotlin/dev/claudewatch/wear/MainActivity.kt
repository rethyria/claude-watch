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
import dev.claudewatch.wear.ui.halo.HaloActions
import dev.claudewatch.wear.ui.halo.HaloApp

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
    HaloApp(
        ui = state,
        actions = HaloActions(
            onPair = viewModel::pair,
            onUnpair = viewModel::unpair,
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
