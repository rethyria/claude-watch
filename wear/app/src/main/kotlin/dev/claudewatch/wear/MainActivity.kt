package dev.claudewatch.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    val state by viewModel.state.collectAsState()
    SessionPagerScreen(
        ui = state,
        actions = SessionPagerActions(
            onPair = viewModel::pair,
            onSendCommand = viewModel::sendCommand,
            onAnswerPermission = viewModel::answerPermission,
            onAnswerQuestions = viewModel::answerQuestions,
            onDismissPermission = viewModel::dismissPermissionLocally,
            onSpawn = viewModel::spawnSession,
            onKill = viewModel::killSession,
        ),
    )
}
