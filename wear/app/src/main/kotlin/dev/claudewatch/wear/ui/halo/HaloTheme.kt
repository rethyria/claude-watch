// Halo design tokens — the single source of truth for the "Halo" Wear OS
// direction (design_handoff_claude_watch_halo/README.md, high-fidelity).
// AMOLED-first: pure black screens, terracotta = "waiting for you".
// All px measurements in the handoff are at a 450×450 reference; we express
// them as dp/sp proportionally (450px ≈ the full round display).
package dev.claudewatch.wear.ui.halo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Halo {
    // ── Colors ──────────────────────────────────────────────────────────────
    object Palette {
        val Background = Color(0xFF000000)
        val DocBase = Color(0xFF0D0D0F)
        val Surface = Color(0xFF191B20)
        val Surface2 = Color(0xFF23262D)
        val InsetWell = Color(0xFF16181D)

        val TextPrimary = Color(0xFFF4F1EA)
        val TextSecondary = Color(0xFF8D8B84)
        val TextFaint = Color(0xFF63615B)

        // Loading-skeleton placeholder grey (usage page, per the Halo usage
        // design) — darker than Idle so a pulsing rect never reads as a bar.
        val SkeletonFill = Color(0xFF22242A)

        // Semantic session/state colors.
        val WaitingForYou = Color(0xFFD97757) // terracotta — perm & question
        val Running = Color(0xFF6CB289)
        // The agent yielded its turn but SUBAGENTS are still running: work is
        // in flight, yet nothing will answer you right now — a third reading
        // that green (I am working) and grey (nothing is happening) both got
        // wrong. Luminance-matched to Running (8.1:1 vs 8.2:1 on black) so the
        // ring reads as a peer state, not an alarm.
        val Delegated = Color(0xFF6BA8D8)
        val Idle = Color(0xFF3A3C42)
        val Error = Color(0xFFE5484D) // error / offline

        // Ambient (always-on) dimmed variants.
        val AmbientTerracotta = Color(0xFF7A4634)
        val AmbientNeutral = Color(0xFF222329)

        val ApproveText = Color(0xFF1A0F0A) // on terracotta fill
        val UserEntry = Color(0xFFE8A889) // dictated/user lines + "sending" state

        // Command well border, page dots.
        val CommandWellBorder = Color(0xFF35281F)
        val Divider = Color(0xFF26282E)
        val DotCurrent = Color(0xFFF4F1EA)
        val DotOther = Color(0xFF4A4C52)
        val OutlineButton = Color(0xFF3A3C42)
    }

    // ── Type (Roboto; Roboto Mono for commands/tool lines) ──────────────────
    // Minimum on-watch size is 20sp per the handoff.
    object Type {
        val TimeCenter = 44.sp; val TimeCenterWeight = FontWeight.Light // 88px ref
        val BigCount = 50.sp; val BigCountWeight = FontWeight.Bold       // 100px ref
        val Title = 13.sp                                               // 24–26px
        val Body = 12.5.sp                                              // 24–25px
        val Caption = 11.sp                                             // 20–22px
        val MonoCommand = 13.sp                                         // 26px mono
        val Min = 10.sp                                                 // 20px floor
    }

    // ── Geometry ────────────────────────────────────────────────────────────
    object Geo {
        // The ring is positioned by its OUTER STROKE EDGE, not its centerline:
        // the interactive and ambient strokes differ (9 vs 4), and a shared
        // centerline would leave the thinner ambient ring sitting visibly
        // further in — the rim line must land in the same place in both modes.
        // 6px at the 450 ref ≈ 3dp, matching what first-party Wear edge chrome
        // hugs to (M3 PaddingDefaults.edgePadding is 2.dp, EdgeButton 3.dp).
        // The handoff's original 205px radius was a bare number with no stated
        // rationale, and left ~7.8dp of bare rim; the ~56px safe inset below is
        // explicitly a TEXT rule ("never let text reach the curve"), which the
        // ring — decorative, non-interactive, wordless — was never subject to.
        const val RingEdgeGap = 6f           // px at 450 ref, edge → outer stroke
        const val RingStroke = 9f            // px at 450 ref (scaled at draw time)
        const val RingStrokeAmbient = 4f
        val SafeInset = 28.dp                // ~56px circular safe-area inset
        val CardRadius = 17.dp               // cards/wells 16–18px
        val RowRadius = 13.dp                // session rows 26px
        val TouchMin = 48.dp
    }

    /** Per-session state that colors a ring segment and a row dot. */
    /**
     * [DELEGATED] is the main loop having yielded while its subagents keep
     * running (issue #60 follow-up): distinct from [RUNNING] (the agent
     * itself is churning) and from [IDLE] (nothing at all is happening),
     * because from the wrist those are three different answers to "should I
     * expect something to change?".
     */
    enum class SessionState { WAITING_PERM, WAITING_Q, RUNNING, DELEGATED, IDLE, ERROR }

    /** Ring/dot color for a session state (interactive, not ambient). */
    fun colorFor(state: SessionState): Color = when (state) {
        SessionState.WAITING_PERM, SessionState.WAITING_Q -> Palette.WaitingForYou
        SessionState.RUNNING -> Palette.Running
        SessionState.DELEGATED -> Palette.Delegated
        SessionState.IDLE -> Palette.Idle
        SessionState.ERROR -> Palette.Error
    }

    fun ambientColorFor(state: SessionState): Color = when (state) {
        SessionState.WAITING_PERM, SessionState.WAITING_Q -> Palette.AmbientTerracotta
        else -> Palette.AmbientNeutral
    }
}
