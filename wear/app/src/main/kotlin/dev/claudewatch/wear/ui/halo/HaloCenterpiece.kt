// The center of every Halo page: the time (this app replaces the watch face
// while foregrounded, so it must keep telling the time) plus a page-specific
// subtitle. Ticks once per minute — a per-second clock would burn battery for
// a display that only shows minutes.
package dev.claudewatch.wear.ui.halo

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Centered time + subtitle slot. The WHOLE area is the tap target ([onTap]
 * routes to the first waiting item), so callers size it with the space the
 * ring encloses rather than wrapping the text.
 */
@Composable
fun HaloCenterpiece(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    ambient: Boolean = false,
    subtitle: @Composable ColumnScope.() -> Unit = {},
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
            color = if (ambient) Halo.Palette.TextSecondary else Halo.Palette.TextPrimary,
        )
        if (!ambient) subtitle()
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
