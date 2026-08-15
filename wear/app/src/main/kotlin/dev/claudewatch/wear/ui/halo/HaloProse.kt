// The Compose half of the feed's rich prose (issue #128): ProseMarkdown's
// span model mapped onto AnnotatedString styles. Prose roles ONLY — the
// COMMAND/SYSTEM/OUTPUT-result branches of FeedLine never come through here,
// so tool output stays byte-verbatim.
//
// Each ProseLine renders as its OWN ParagraphStyle range with the separating
// '\n' characters dropped: consecutive paragraph ranges stack as separate
// line blocks, which is what gives list items a real hanging indent —
// whereas paragraph ranges laid over text that still contains its newlines
// split around them into phantom empty lines.
package dev.claudewatch.wear.ui.halo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.claudewatch.shared.terminal.ProseLineKind
import dev.claudewatch.shared.terminal.ProseMark
import dev.claudewatch.shared.terminal.ProseMarkdown
import dev.claudewatch.shared.terminal.ProseMarkup

/**
 * Hanging indent for a wrapped list item's continuation rows: roughly the
 * "• " advance at Body size, so wrapped text aligns under the item's first
 * character rather than under its bullet. An approximation on purpose — a
 * proportional font has no exact char width to derive from, and a tidy
 * constant beats measuring every marker.
 */
private val LIST_HANG_INDENT = 10.sp

/** The inline-code chip: monospace on a background rect — the issue's
 *  "highlight" treatment. CodeChip, not Surface2: the user field-tested
 *  Surface2 as too faint against the black watchface (2026-08-15). The text
 *  keeps whatever colour role its line already has (data stays TextPrimary). */
private val CODE_CHIP = SpanStyle(fontFamily = FontFamily.Monospace, background = Halo.Palette.CodeChip)

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)

/** Underline covers <u> AND link labels: a watch link has no tap action and
 *  its url is dropped, so the underline is the honest "this was a link". */
private val UNDERLINE = SpanStyle(textDecoration = TextDecoration.Underline)

/** Headings: marker stripped upstream, bold + the type scale's step up from
 *  Body. Bold carries the hierarchy — a watch has no room for an H1 ramp. */
private val HEADING = SpanStyle(fontWeight = FontWeight.Bold, fontSize = Halo.Type.Title)

/** Fenced-code lines: the chip treatment at Caption size — mono wraps badly
 *  at Body width on a round face, and code is dense by nature. */
private val CODE_LINE = CODE_CHIP.copy(fontSize = Halo.Type.Caption)

private val LIST_INDENT = ParagraphStyle(textIndent = TextIndent(firstLine = 0.sp, restLine = LIST_HANG_INDENT))
private val PLAIN_PARAGRAPH = ParagraphStyle()

/**
 * Parse-and-style, cached per line text: the feed list is keyed, so each
 * composed line pays the parse once on entry, not per recomposition — the
 * issue's watch-class-CPU constraint.
 */
@Composable
internal fun rememberProseText(raw: String): AnnotatedString =
    remember(raw) { proseAnnotated(ProseMarkdown.parse(raw)) }

internal fun proseAnnotated(markup: ProseMarkup): AnnotatedString = buildAnnotatedString {
    for (line in markup.lines) {
        val paragraph = when (line.kind) {
            ProseLineKind.BULLET, ProseLineKind.NUMBERED -> LIST_INDENT
            else -> PLAIN_PARAGRAPH
        }
        withStyle(paragraph) {
            val base = length
            append(markup.text, line.start, line.end)
            when (line.kind) {
                ProseLineKind.HEADING -> addStyle(HEADING, base, length)
                // Quote colour covers the lead glyph too — glyph and text
                // read as one quoted unit. Readability doctrine holds:
                // TextSecondary is the same role tool results wear.
                ProseLineKind.QUOTE -> addStyle(SpanStyle(color = Halo.Palette.TextSecondary), base, length)
                ProseLineKind.CODE -> addStyle(CODE_LINE, base, length)
                else -> {}
            }
            // Inline spans are block-global (a pair may cross lines); clip
            // each to this line and rebase into the builder's coordinates.
            for (span in markup.spans) {
                val s = maxOf(span.start, line.start)
                val e = minOf(span.end, line.end)
                if (s < e) addStyle(styleFor(span.mark), base + (s - line.start), base + (e - line.start))
            }
        }
    }
}

private fun styleFor(mark: ProseMark): SpanStyle = when (mark) {
    ProseMark.BOLD -> BOLD
    ProseMark.ITALIC -> ITALIC
    ProseMark.STRIKE -> STRIKE
    ProseMark.UNDERLINE, ProseMark.LINK -> UNDERLINE
    ProseMark.CODE -> CODE_CHIP
}
