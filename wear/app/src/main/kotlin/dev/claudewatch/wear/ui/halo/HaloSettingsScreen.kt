// The settings page LEFT of usage (leftmost of the whole pager): a flat glance
// surface — no ring, no centerpiece, no drill-down, exactly the usage page's
// "flat page" treatment (HaloNav no-ops both a drill and a centerpiece tap from
// here) — carrying the destructive Unpair. Unpair wipes the paired credentials
// and forces a full re-pair, so it is TWO-TAP: the first tap only ARMS ("tap to
// confirm"), a second tap on the armed control actually fires. A stray
// swipe-tap that overshoots onto this leftmost page can't wipe the pairing, and
// leaving the page disarms it (the confirm flag is screen-local `remember`).
// This screen is the FIRST surface to actually invoke HaloActions.onUnpair —
// declared and VM-wired since issue #23's pairing work, but never rendered by
// any Halo UI until now.
package dev.claudewatch.wear.ui.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TimeTextDefaults

// ─── Pure confirm-gate step (plain function so a JVM unit test can pin the ───
// ─── two-tap contract without composing) ──────────────────────────────────────

/** The result of one tap on the Unpair control: the next armed flag, and
 *  whether THIS tap fires the (destructive) unpair. */
internal data class UnpairStep(val armed: Boolean, val fire: Boolean)

/**
 * The Unpair confirm gate, as a pure step. Unpair is destructive (wipes the
 * paired credentials, forces a re-pair), so a SINGLE tap must never fire it:
 * the first tap only ARMS the control, a second tap on the armed control
 * actually unpairs (and disarms). Extracted from the composable — like the
 * usage page's presentation math — so a plain-JVM test can prove
 * confirm-then-tap fires exactly once and a lone tap does not, without an
 * emulator.
 */
internal fun unpairTap(armed: Boolean): UnpairStep =
    if (armed) UnpairStep(armed = false, fire = true) else UnpairStep(armed = true, fire = false)

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun HaloSettingsScreen(
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The connection status line (e.g. "paired, stream open") — honest context
     * for the destructive Unpair below: you can SEE you are paired before you
     * sever it. Blank renders nothing; it is the only connection descriptor
     * cheaply on the UiState (host:port lives in the encrypted credential
     * store, off the model), so no new plumbing is invented for it.
     */
    status: String = "",
) {
    // Screen-local confirm flag — the same `remember` idiom the usage page uses
    // for its per-page ephemeral mode toggle. Leaving or recomposing this page
    // disarms it, which IS the stray-tap guard: a wipe always needs a
    // deliberate second tap on a freshly-armed control.
    var armed by remember { mutableStateOf(false) }

    // Root: transparent over haloRoot's pure black (like the usage page, no
    // background of its own); center-aligned; its own page tag.
    Box(
        modifier = modifier.fillMaxSize().testTag("haloSettings"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize().padding(horizontal = Halo.Geo.SafeInset),
        ) {
            // Page heading — TextPrimary/Type.Title: it is the page's primary
            // label with no competing data, so it takes the heading tone rather
            // than a faint one (TextFaint is the usage EYEBROW's token — a
            // section label — and readability beats tonal-ramp purity here).
            Text(
                text = "settings",
                fontSize = Halo.Type.Title,
                fontWeight = FontWeight.Medium,
                color = Halo.Palette.TextPrimary,
                textAlign = TextAlign.Center,
            )
            if (status.isNotBlank()) {
                Text(
                    text = status,
                    fontSize = Halo.Type.Min, // 20px caption
                    color = Halo.Palette.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The destructive Unpair pill (template = the usage page's Retry
            // pill): restrained Surface2 at rest so it never invites a tap, then
            // a RED (Error) fill on the armed step so "tap to confirm" reads as
            // the warning it is. The two-tap logic lives in the pure `unpairTap`
            // so the JVM test pins it; the pill just renders the current arm and
            // fires onUnpair when the step says to.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(32.dp) // 64px pill
                    .clip(CircleShape)
                    .background(if (armed) Halo.Palette.Error else Halo.Palette.Surface2)
                    .clickable {
                        val step = unpairTap(armed)
                        armed = step.armed
                        if (step.fire) onUnpair()
                    }
                    .testTag("haloSettingsUnpair")
                    .padding(horizontal = 20.dp), // 40px side padding
            ) {
                Text(
                    text = if (armed) "tap to confirm" else "Unpair",
                    fontSize = 12.sp, // 24px
                    fontWeight = FontWeight.Medium,
                    color = if (armed) Halo.Palette.TextPrimary else Halo.Palette.TextSecondary,
                )
            }
        }
        // The decorative top clock — the InnerScreen idiom, identical to the
        // usage page: same style, and deliberately NOT a tap target.
        TimeText(
            timeTextStyle = TimeTextDefaults.timeTextStyle(
                color = Color(0xFF7E7C76),
                fontSize = Halo.Type.Min,
            ),
        )
    }
}
