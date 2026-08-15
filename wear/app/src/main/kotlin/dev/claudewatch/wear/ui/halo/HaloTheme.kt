// Halo design tokens — the single source of truth for the "Halo" Wear OS
// direction (design_handoff_claude_watch_halo/README.md, high-fidelity).
// AMOLED-first: pure black screens, terracotta = "waiting for you".
// All px measurements in the handoff are at a 450×450 reference; we express
// them as dp/sp proportionally (450px ≈ the full round display).
package dev.claudewatch.wear.ui.halo

import androidx.compose.animation.core.CubicBezierEasing
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
        // Code-chip fill (#128, user-tuned twice): Surface2 was barely-there
        // on the black OLED, and a brighter grey still read as surface, not
        // highlight. A deep terracotta-tinted well instead — the warm ramp
        // (UserEntry, CommandWellBorder) is the one hue family the state
        // colours don't use for signalling, so it highlights without reading
        // as waiting/running/delegated. Cream TextPrimary on it stays ~9:1.
        val CodeChip = Color(0xFF4E3226)
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
        /**
         * Clearance between the ring's INNER stroke edge and the outer edge of
         * a page dot. The dots ride an arc concentric with the ring (see
         * PageDots): laid out in a straight row they hold that clearance only
         * at 6 o'clock, and the outermost dots of a 5+ page row run straight
         * into the ring as the curve drops away from them.
         */
        const val DotArcGap = 10f            // px at 450 ref, ring inner → dot
        val SafeInset = 28.dp                // ~56px circular safe-area inset
        val CardRadius = 17.dp               // cards/wells 16–18px
        val RowRadius = 13.dp                // session rows 26px
        val TouchMin = 48.dp
        /** The dictate pill's microphone icon. Tuned on the real 454px watch
         *  rather than derived: at the caption cap-height it replaced (17dp)
         *  the mic was too small to read as one object. Sized the hand-drawn
         *  glyph before the #104 feedback swapped in the real icon. */
        val MicGlyph = 20.dp
        /** The ⊘ struck over the muted mic when dictation is unavailable:
         *  wide enough to ENCLOSE the icon's ink — a slash alone would read
         *  as part of the microphone at this size. */
        val MicOffOverlay = 24.dp
        // Top-anchored lists (session list, spawn picker, discover list) start
        // their scrollable content this far below the top edge, in place of
        // ScalingLazyColumn's default autoCentering — which reserves ~half a
        // screen above item 0 so it can reach center and, as a side effect,
        // buries the first rows in the lower half of the round face. Tuned on a
        // round 454px watch so the caption clears the bezel and the first two
        // rows are both on screen at rest. The bottom inset lets the final row
        // scroll fully clear of the curve. One knob for all three lists.
        val ListTopInset = 40.dp
        val ListBottomInset = 48.dp

        // ── v2 ring channel (Halo v2, epic #94) ─────────────────────────────
        // Tokens for the persistent morphing ring, drawn from the root by
        // HaloRingHost (the S3 seam; live since S4 — HaloRingState animates
        // between targets). Angles are Canvas degrees (clockwise-positive, 0°
        // at 3 o'clock); px are at the 450 reference.

        /**
         * Centreline radius for EVERY ring stroke — solid 9 ([RingStroke]),
         * dashed 4, hero 10, feed 6. Fixed on purpose: the v2 morphs animate
         * stroke WIDTH, so the v1 edge-derived radius (which re-centres per
         * stroke, see [RingEdgeGap]) would make the ring breathe radially
         * with every weight change. The ring must fatten and thin in place.
         */
        const val RingChannel = 214f
        /** Segment gap; a solo session tightens to [RingGapSoloDeg] so one
         *  arc still reads as "a segment", not a circle with a glitch. */
        const val RingGapDeg = 8.5f
        const val RingGapSoloDeg = 8f
        /**
         * Arc k ENDS at this minus k·(360/n): the first segment closes just
         * left of midnight and the ring winds anticlockwise (design geometry,
         * replacing v1's −90°-centred slices and 10° gap).
         */
        const val RingAnchorDeg = -94f

        // Per-layer stroke weights (the solid layer keeps [RingStroke] = 9).
        const val RingStrokeDashed = 4f
        const val RingStrokeHero = 10f
        const val RingStrokeFeed = 6f

        /**
         * Dashed session layer: 2.5 on / 11 off. The 13.5 period is CONSTANT
         * through the split/merge morph — only the on-length grows, so each
         * dash fuses in place instead of the pattern crawling along the ring.
         */
        const val DashOnPx = 2.5f
        const val DashPeriodPx = 13.5f
        const val DashedLayerAlpha = 0.65f
        const val FeedRingAlpha = 0.85f
        /** A collapsing arc below this sweep is skipped outright: a round cap
         *  at zero sweep would leave a lit dot outliving its arc. */
        const val MinDrawSweepDeg = 0.5f

        /**
         * Page-dot clearance re-based on the channel: the channel's inner
         * stroke edge ([RingChannel] − [RingStroke]/2) minus where the v1
         * derivation puts the dot's outer edge (display radius − [RingEdgeGap]
         * − [RingStroke] − [DotArcGap] = 200). Comes to 9.5, not [DotArcGap]'s
         * 10, because the fixed channel sits half a ref-px inside the v1
         * centreline — the dots must NOT follow that half-pixel: expressing
         * the clearance this way keeps their visual position bit-identical
         * when PageDots re-derives from the channel.
         */
        const val DotChannelClearance =
            (RingChannel - RingStroke / 2f) - (HALO_REF_PX / 2f - RingEdgeGap - RingStroke - DotArcGap)

        /**
         * The #115 dots lift (user direction, 2026-08-12: "the nav circles
         * on the main project page can be lifted a little higher"): an EXTRA
         * inward inset for the page dots, ON TOP of [DotChannelClearance].
         * A separate token on purpose — the clearance above is expressed to
         * reproduce the v1 dot position bit-exactly (its whole doc), and
         * this is the deliberate departure from that position, kept where
         * the departure is visible. 12 ref-px ≈ one current-dot diameter:
         * reads as clearly higher at arm's length, while the dot slots still
         * clear the usage page's freshness-label band above them. Dots ONLY:
         * the ring, clock group and content geometry take nothing from it.
         */
        const val DotLiftPx = 12f

        /**
         * Outer arc padding for the root TimeText: display edge to the clock
         * glyphs' OUTER edge, sized so the platform-curved time sits INSIDE
         * the ring channel with 2 ref-px to spare against the channel's
         * deepest inner reach (the hero's stroke 10 — the widest stroke any
         * regime draws on the fixed channel). The platform default (2dp) hugs
         * the rim, which is exactly where the list's dotted ring and the feed
         * circle live: at 12 o'clock the clock printed straight through them
         * (#104 carry-over from the #103 morph recordings). One inset for
         * every depth, so the fixed clock never shifts when the ring morphs
         * under it or collapses away on the glance pages.
         */
        const val ClockRingClearance =
            HALO_REF_PX / 2f - (RingChannel - RingStrokeHero / 2f) + 2f

        // ── Centerpiece group centring + Answer pill (#104 user feedback) ───
        // Roboto's vertical metrics as em fractions (hhea table, 2048 upem:
        // ascent 1900, descent 500, cap height 1456) — the numbers that say
        // where glyphs actually paint inside a line box whose height equals
        // the font size (the centerpiece clock's 88px/1).
        const val FontAscentEm = 1900f / 2048f
        const val FontDescentEm = 500f / 2048f
        const val FontCapHeightEm = 1456f / 2048f

        /** The centerpiece clock's font size == line height at the 450 ref. */
        const val ClockFontPx = 88f
        /** Clock → subtitle spacing (design 2px flex gap + 4px margin) and
         *  the fixed subtitle slot (30px line box) — see HaloCenterpiece. */
        const val ClockSubtitleGapPx = 6f
        const val ClockSubtitleSlotPx = 30f

        /**
         * The clock Text's measured line box: ascent + descent ≈ 103 ref-px,
         * NOT the 88px/1 line height — Compose does not trim a lone line to
         * its lineHeight (that takes an explicit LineHeightStyle), so the
         * single-line clock always measures the full font box. Verified on
         * the emulator (the box came out ~102.5 device-px at 44sp/density 2;
         * hhea metrics put it at 103.1 — sub-px rounding).
         */
        const val ClockLineBoxPx = (FontAscentEm + FontDescentEm) * ClockFontPx
        /** The clock + gap + subtitle-slot column, in layout boxes. */
        const val ClockGroupPx = ClockLineBoxPx + ClockSubtitleGapPx + ClockSubtitleSlotPx

        /**
         * Dead leading above the clock digits: the baseline sits [FontAscentEm]
         * below the line box's top, and digits are cap-height tall with no
         * descenders, so everything above their cap tops — (ascent − cap) ≈
         * 19 ref-px — is invisible box. Box-centring the clock+subtitle
         * column (Arrangement.Center) counts that dead band as clock and
         * sinks the visible mass; the #104 user feedback pins the fix: the
         * vertical centre line is computed over the clock + subheading as a
         * GROUP, i.e. over its VISUAL extent (digit cap tops → subtitle slot
         * bottom), which rides the group [ClockDeadLeadingPx]/2 higher than
         * box-centring puts it. (The slot's own bottom is honest mass — the
         * caption's descenders reach it — so only the top band corrects.)
         */
        const val ClockDeadLeadingPx =
            (FontAscentEm - FontCapHeightEm) * ClockFontPx

        /**
         * The re-centred clock group's bottom edge (the subtitle slot's
         * bottom): box-centred bottom, lifted by half the dead leading.
         */
        const val ClockGroupBottomPx =
            (HALO_REF_PX - ClockGroupPx - ClockDeadLeadingPx) / 2f + ClockGroupPx

        /** The prototype's Answer-pill clearance below the clock group (the
         *  epic's "308px = clock-group bottom + 21px" derivation, re-based
         *  on the re-centred group instead of a screen-absolute number). */
        const val AnswerPillClearancePx = 21f
        const val AnswerPillTopPx = ClockGroupBottomPx + AnswerPillClearancePx
    }

    // ── Motion (Halo v2, epic #94) ──────────────────────────────────────────
    // The ring's animation vocabulary, verbatim from the design's CSS: paint
    // and geometry are SEPARATE channels on purpose — colour blends fast and
    // ease-in-out, geometry follows on a longer decel curve, and when one
    // update drives both, geometry additionally waits [GeometryDelayMs] so
    // the recolour reads first (window-gated in HaloRingMath.geometryDelayMs).
    object Motion {
        /** Colour/alpha blends: .3s CSS ease-in-out. */
        const val PaintMs = 300
        val PaintEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

        /** Geometry (end/sweep) retargets; [GeometryEasing] is also the
         *  grow/shrink and dash-morph curve — one decel family. */
        const val GeometryMs = 550
        val GeometryEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
        const val GeometryDelayMs = 220
        const val GeometryDelayWindowMs = 850L

        /** New arcs snap in pre-coloured and only FADE, drawn beneath the
         *  settled ring until [NewArcBeneathMs] expires. */
        const val NewArcFadeMs = 300
        const val NewArcBeneathMs = 1300L

        /** Pager highlight rotation (shortest-path, accumulated). */
        const val HighlightMs = 400
        val HighlightEasing = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)

        /** list↔feed: the selected arc grows/shrinks to/from the full circle
         *  while the hero stroke eases 10↔6 — never its alpha. */
        const val GrowShrinkMs = 650

        /** page↔list dash split/merge (stroke 9↔4, alpha 1↔.65, hero 9↔10);
         *  the real solid layer hides during close and swaps back atomically
         *  once the morph has settled at [MorphSettleMs]. */
        const val DashMorphMs = 500
        const val MorphSettleMs = 1000L

        /** Content crossfades inside morphs: out fast, in late — the ring is
         *  the continuity, content just follows it. */
        const val ContentFadeOutMs = 250
        const val ContentFadeInMs = 450
        const val ContentFadeInDelayMs = 100
        /** The list→page return fade (the quick path back). */
        const val ListToPageFadeMs = 300
    }

    /** Per-session state that colors a ring segment and a row dot. */
    /**
     * [DELEGATED] means subagents are in flight (issue #60, refined by #67):
     * distinct from [IDLE] (nothing is happening) and, deliberately, ranked
     * ABOVE [RUNNING] — while a workflow runs the main loop is idle or
     * mostly-idle shepherding the fleet, and it never reports a clean stop
     * (Claude Code holds the turn open), so keying blue on "any subagents
     * running" is what makes it reachable at all. From the wrist these are
     * different answers to "should I expect something to change, and will it
     * answer ME?".
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
