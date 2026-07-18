// Issue #57 — the usage page LEFT of home: one REMAINING bar per plan window
// the bridge reports (render-what-you-get: 5-hour session, weekly all-models,
// weekly model-scoped today — but any entry the wire carries gets a bar, so
// new upstream windows appear without an app update). Glanceable by design:
// label + bar + reset time, no charts, no scrolling for the expected three
// bars, no centerpiece and no drill-down (HaloNav no-ops both). Fetch-on-open
// drives the states: spinner while loading, bars on data (with a faint
// staleness line when the bridge served its cache fallback), message + retry
// on error. Round-safe: everything lives inside the circular safe inset.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import dev.claudewatch.wear.BridgeViewModel.UsageLimit
import dev.claudewatch.wear.BridgeViewModel.UsageUi
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Remaining fraction below which a bar turns "waiting for you" orange. */
private const val LOW_REMAINING_PERCENT = 20.0

@Composable
fun HaloUsageScreen(
    usage: UsageUi,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    Box(
        modifier = modifier.fillMaxSize().testTag("haloUsage"),
        contentAlignment = Alignment.Center,
    ) {
        when (usage) {
            // Idle renders like Loading: the page's fetch-on-open fires the
            // moment it becomes current, so Idle is at most one frame old —
            // a distinct empty state would only flash.
            UsageUi.Idle, UsageUi.Loading -> CircularProgressIndicator(
                indicatorColor = Halo.Palette.TextFaint,
                trackColor = Halo.Palette.Idle,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp).testTag("haloUsageLoading"),
            )
            is UsageUi.Error -> UsageError(message = usage.message, onRetry = onRetry)
            is UsageUi.Data -> UsageBars(data = usage, nowMs = nowMs)
        }
    }
}

@Composable
private fun UsageError(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(Halo.Geo.SafeInset),
    ) {
        Text(
            text = message,
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        // The retry re-calls onUsageOpen — the same fetch the page entry
        // fires, so retry and re-entry are indistinguishable to the VM.
        Text(
            text = "retry",
            fontSize = Halo.Type.Body,
            fontWeight = FontWeight.Medium,
            color = Halo.Palette.WaitingForYou,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable(onClick = onRetry)
                .testTag("haloUsageRetry")
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun UsageBars(data: UsageUi.Data, nowMs: () -> Long) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = Halo.Geo.SafeInset),
    ) {
        Text(
            text = "usage left",
            fontSize = Halo.Type.Min,
            color = Halo.Palette.TextFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (data.limits.isEmpty()) {
            // A 200 with zero windows (plan without limits?): honest empty
            // state rather than a blank page.
            Text(
                text = "no usage windows reported",
                fontSize = Halo.Type.Caption,
                color = Halo.Palette.TextFaint,
                textAlign = TextAlign.Center,
            )
        }
        data.limits.forEach { limit -> UsageBarRow(limit) }
        // The bridge served its CACHE fallback (its upstream call failed):
        // the bars are real but old — say how old instead of pretending live.
        if (data.source == "cache" && data.fetchedAtMs != null) {
            val ageMin = ((nowMs() - data.fetchedAtMs) / 60_000L).coerceAtLeast(0L)
            Text(
                text = "as of ${ageMin}m ago",
                fontSize = Halo.Type.Min,
                color = Halo.Palette.TextFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 5.dp).testTag("haloUsageStale"),
            )
        }
    }
}

/** Label, remaining bar, reset time — one glanceable row per plan window. */
@Composable
private fun UsageBarRow(limit: UsageLimit) {
    val remaining = (100.0 - limit.percent).coerceIn(0.0, 100.0)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = limit.label,
            fontSize = Halo.Type.Caption,
            color = Halo.Palette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .fillMaxWidth()
                .height(5.dp)
                .background(Halo.Palette.Idle, CircleShape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (remaining / 100.0).toFloat())
                    .fillMaxHeight()
                    .background(
                        // Plenty left = calm green; nearly drained = the
                        // "waiting for you" terracotta, the palette's one
                        // attention color.
                        if (remaining < LOW_REMAINING_PERCENT) {
                            Halo.Palette.WaitingForYou
                        } else {
                            Halo.Palette.Running
                        },
                        CircleShape,
                    ),
            )
        }
        usageResetLabel(limit.kind, limit.resetsAt)?.let { resets ->
            Text(
                text = resets,
                fontSize = Halo.Type.Min,
                color = Halo.Palette.TextFaint,
                maxLines = 1,
            )
        }
    }
}

/**
 * "resets 19:10" for the 5-hour session window (it lands today), "resets Fri"
 * for the weekly kinds — and for any UNKNOWN kind, whose cadence we cannot
 * guess (render-what-you-get includes the reset line). A malformed/absent
 * resetsAt yields null: the bar renders without a reset line, never a crash
 * and never a dropped bar. Pure, so plain-JVM tests can pin the formats.
 */
internal fun usageResetLabel(kind: String, resetsAt: String?): String? {
    if (resetsAt == null) return null
    val parsed = try {
        OffsetDateTime.parse(resetsAt)
    } catch (_: Exception) {
        return null
    }
    val local = parsed.atZoneSameInstant(ZoneId.systemDefault())
    val pattern = if (kind == "session") "HH:mm" else "EEE"
    return "resets " + local.format(DateTimeFormatter.ofPattern(pattern))
}
