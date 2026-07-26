// The Answer pill (Halo v2, epic #94): the ONE affordance that jumps straight
// to a pending prompt, now that the centerpiece tap opens the session list
// instead. Shared between the main pages (S3: absolute-positioned below the
// clock group, shown only when the page's scope has a waiting session) and
// the session-list pager cards (S5) — one composable so the two renditions
// can never drift apart.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text

/** Terracotta pill, h 50px fully rounded, "Answer" 600 22px on #1A0F0A. */
@Composable
fun HaloAnswerPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(25.dp) // 50px pill
            .clip(CircleShape) // fully rounded
            .background(Halo.Palette.WaitingForYou)
            .clickable(onClick = onClick)
            .testTag("haloAnswerPill")
            .padding(horizontal = 15.dp), // 30px side padding
    ) {
        Text(
            text = "Answer",
            fontSize = 11.sp, // 22px
            fontWeight = FontWeight.SemiBold,
            color = Halo.Palette.ApproveText,
        )
    }
}
