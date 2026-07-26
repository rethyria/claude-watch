// The center of the home/project pages: the time (this app replaces the watch
// face while foregrounded, so it must keep telling the time) plus a subtitle
// slot. Since the v2 shell (epic #94 S3) the centerpiece is FIXED app-level
// chrome — only the slot's content slides during page navigation — so the
// group's position must be constant by construction: the clock's line height
// is pinned to its font size (the design's 88px/1) and the subtitle sits in a
// fixed-height box, making total height independent of which page's subtitle
// (or none) is showing. Ticks once per minute — a per-second clock would burn
// battery for a display that only shows minutes.
package dev.claudewatch.wear.ui.halo

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Clock-to-subtitle spacing: the design's 2px flex gap + 4px margin. */
private val SubtitleGap = 3.dp

/**
 * The subtitle slot's fixed height (30px line box at the 450 ref — the design
 * gives the census and the project name the same line height, which is what
 * keeps the clock from shifting between pages).
 */
private val SubtitleSlotHeight = 15.dp

/**
 * Centered time + subtitle slot. The WHOLE area is the tap target ([onTap]
 * opens the current scope's session list, same as swipe-up), so callers size
 * it with the space the ring encloses rather than wrapping the text.
 */
@Composable
fun HaloCenterpiece(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable BoxScope.() -> Unit = {},
) {
    val time = rememberMinuteTime()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onTap)
            .padding(Halo.Geo.SafeInset),
    ) {
        Text(
            text = time,
            fontSize = Halo.Type.TimeCenter,
            fontWeight = Halo.Type.TimeCenterWeight,
            // Line height == font size (design 88px/1): the clock's measured
            // height must not float with the font's default leading, or the
            // fixed-position promise above quietly breaks across devices.
            lineHeight = Halo.Type.TimeCenter,
            color = Halo.Palette.TextPrimary,
            modifier = Modifier.testTag("haloClock"),
        )
        Spacer(modifier = Modifier.height(SubtitleGap))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.height(SubtitleSlotHeight),
            content = subtitle,
        )
    }
}

/**
 * "HH:mm"/"h:mm" (per system 12/24h setting), recomposing exactly on minute
 * boundaries: sleep out the remainder of the current minute, tick, repeat.
 */
@Composable
fun rememberMinuteTime(): String {
    val pattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    val formatter by rememberUpdatedState(remember(pattern) { DateTimeFormatter.ofPattern(pattern) })
    var text by remember(pattern) { mutableStateOf(LocalTime.now().format(formatter)) }
    LaunchedEffect(pattern) {
        while (true) {
            // +50ms so a coarse wakeup can't land a hair BEFORE the boundary
            // and render the old minute for the next 60s.
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
            text = LocalTime.now().format(formatter)
        }
    }
    return text
}
