// PLACEHOLDER — handoff §3 (session list: grouped rows, wrapping titles,
// swipe-revealed quick actions, rotary scroll) is implemented by a follow-up
// agent. The signature below is the contract HaloApp already calls; keep it.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Text

@Composable
fun HaloSessionList(
    model: HaloModel,
    scope: ListScope,
    onOpenSession: (String) -> Unit,
    onKill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (scope) {
        ListScope.All -> "all sessions"
        is ListScope.Project -> "${scope.name} · sessions"
    }
    Box(modifier = modifier.fillMaxSize().padding(Halo.Geo.SafeInset)) {
        Text(
            text = title,
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
