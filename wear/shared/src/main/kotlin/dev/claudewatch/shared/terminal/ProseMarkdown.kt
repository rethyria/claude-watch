// Inline markdown for the feed's prose roles (issue #128). Agents emit real
// markdown — `**bold**`, `` `code` ``, headings, lists — and the wire carries
// it verbatim (the bridge coalesces agent_message_chunk text per turn and
// flushes it untouched), so rendering it is a WATCH-side job. This is the
// pure half: one flushed prose block in, a span model out — JVM-testable
// with no Compose anywhere. The Compose half (HaloProse.kt) maps the model
// onto AnnotatedString styles.
//
// Doctrine, from the issue: NEVER crash, NEVER drop a character. A marker is
// consumed only when its pair is CERTAIN — both ends inside this block, sane
// nesting — and everything else (unterminated `**`, marker soup, 4+ runs,
// tags we don't speak) renders as the raw text it is. Tool output never
// comes near this parser: PROSE and the `[codex] ` prose branch only, so a
// test result keeps its asterisks byte for byte.
package dev.claudewatch.shared.terminal

/** Inline treatments a matched marker pair applies to its content. */
enum class ProseMark { BOLD, ITALIC, STRIKE, UNDERLINE, CODE, LINK }

/** One styled range in [ProseMarkup.text]; ranges may nest/overlap. */
data class ProseSpan(val start: Int, val end: Int, val mark: ProseMark)

/** Block treatment of one rendered line. */
enum class ProseLineKind { PARAGRAPH, HEADING, BULLET, NUMBERED, QUOTE, CODE }

/** One rendered line: `[start, end)` into [ProseMarkup.text], excluding the
 *  separating '\n' — the renderer emits each line as its own paragraph. */
data class ProseLine(val start: Int, val end: Int, val kind: ProseLineKind)

/**
 * The parsed block. [text] is the rendered content: consumed markers gone,
 * block markers transformed per the vocabulary (`- ` → `• `, `> ` → the
 * quote glyph, `## ` stripped), everything else verbatim — including every
 * marker the parser could not prove closed.
 */
data class ProseMarkup(val text: String, val spans: List<ProseSpan>, val lines: List<ProseLine>)

object ProseMarkdown {

    /**
     * Blockquote lead glyph replacing the stripped "> " marker. Deliberately
     * NOT "> " itself: the feed's COMMAND branch reads a literal "> " prefix
     * as the user-echo discriminator — PROSE never runs that check, so there
     * is no logic collision, but re-emitting "> " would make an agent's
     * quote visually indistinguishable from the user's own dictated line.
     */
    const val QUOTE_PREFIX = "│ "

    /** What `- `/`* `/`+ ` bullets normalize to. */
    const val BULLET = '•'

    /**
     * Lookahead cap for `[text](url)`: a link's whole pattern must land
     * within this window or the `[` stays literal. Bounds the per-bracket
     * scan on adversarial input (a 50k block of `[`s must stay linear-ish),
     * and no honest wrist-sized link comes close.
     */
    private const val LINK_LOOKAHEAD = 512

    /** Nested-link recursion cap; deeper than this is marker soup, and soup
     *  degrades to raw instead of risking the stack. */
    private const val LINK_DEPTH_MAX = 8

    fun parse(raw: String): ProseMarkup {
        // The wire is '\n'-based but a stray CR must not become a phantom
        // glyph — normalize, never drop content.
        val src = raw.replace("\r\n", "\n").replace('\r', '\n')
        val srcLines = src.split('\n')
        val fence = fenceStates(srcLines)

        val rows = ArrayList<Row>(srcLines.size)
        for (i in srcLines.indices) {
            val line = srcLines[i]
            when (fence[i]) {
                FENCE_CODE -> rows += Row(ProseLineKind.CODE, "", line, scan = false)
                FENCE_MARK -> {
                    // The fence itself is a consumed marker, but its info
                    // string ("```kotlin") is characters, and characters are
                    // never dropped — it survives as the block's first line.
                    val tag = line.trimStart().removePrefix("```").trim()
                    if (tag.isNotEmpty()) rows += Row(ProseLineKind.CODE, "", tag, scan = false)
                }
                else -> rows += classify(line)
            }
        }

        val out = StringBuilder(src.length)
        val spans = ArrayList<ProseSpan>()
        val lines = ArrayList<ProseLine>()
        var i = 0
        while (i < rows.size) {
            if (lines.isNotEmpty()) out.append('\n')
            val row = rows[i]
            if (row.kind == ProseLineKind.PARAGRAPH && row.content.isNotBlank()) {
                // Consecutive non-blank paragraph lines are ONE flushed
                // paragraph: an inline span may open on one line and close on
                // the next (the issue's cross-line case) — within the block
                // that close is certain, so the pair matches across the '\n'.
                // A blank line, a heading, a list item all end the paragraph:
                // continuity never crosses a block boundary, so a `**` left
                // open there stays raw instead of grabbing a stray closer
                // half a message away.
                var j = i
                while (j + 1 < rows.size &&
                    rows[j + 1].kind == ProseLineKind.PARAGRAPH &&
                    rows[j + 1].content.isNotBlank()
                ) j++
                val (text, segSpans) = scanInline((i..j).joinToString("\n") { rows[it].content })
                val base = out.length
                out.append(text)
                for (s in segSpans) spans += ProseSpan(base + s.start, base + s.end, s.mark)
                // '\n' is never a marker, so the emitted text has exactly the
                // segment's line structure — split it back into ProseLines.
                var lineStart = base
                for (k in base until out.length) {
                    if (out[k] == '\n') {
                        lines += ProseLine(lineStart, k, ProseLineKind.PARAGRAPH)
                        lineStart = k + 1
                    }
                }
                lines += ProseLine(lineStart, out.length, ProseLineKind.PARAGRAPH)
                i = j + 1
            } else {
                val start = out.length
                out.append(row.prefix)
                if (row.scan) {
                    val (text, rowSpans) = scanInline(row.content)
                    val cbase = out.length
                    out.append(text)
                    for (s in rowSpans) spans += ProseSpan(cbase + s.start, cbase + s.end, s.mark)
                } else {
                    out.append(row.content)
                }
                lines += ProseLine(start, out.length, row.kind)
                i++
            }
        }
        return ProseMarkup(out.toString(), spans, lines)
    }

    // ── Block level ─────────────────────────────────────────────────────────

    private class Row(val kind: ProseLineKind, val prefix: String, val content: String, val scan: Boolean)

    private const val FENCE_NONE = 0
    private const val FENCE_MARK = 1
    private const val FENCE_CODE = 2

    /**
     * Pair ``` fences sequentially — the first fence after an opener closes
     * it, exactly markdown's rule — and mark the interior as code lines: no
     * block classification (a shell comment `# x` inside a block is not a
     * heading) and no inline scanning. An UNPAIRED trailing fence is not a
     * fence at all: certainty ran out, so its line classifies normally and
     * its backticks render raw.
     */
    private fun fenceStates(lines: List<String>): IntArray {
        val states = IntArray(lines.size)
        val fences = lines.indices.filter { lines[it].trimStart().startsWith("```") }
        var f = 0
        while (f + 1 < fences.size) {
            states[fences[f]] = FENCE_MARK
            states[fences[f + 1]] = FENCE_MARK
            for (i in fences[f] + 1 until fences[f + 1]) states[i] = FENCE_CODE
            f += 2
        }
        return states
    }

    /**
     * One line's block shape. Marker rules are deliberately strict — marker,
     * ONE space, rest verbatim — so a line that only looks like markdown
     * ("--- divider ---"? "*emphasis*"?) falls through to PARAGRAPH and the
     * inline scanner decides. Bullets require the space, which is exactly
     * what separates `* item` (bullet) from `*item*` (italics).
     */
    private fun classify(line: String): Row {
        // Heading: 1–6 #s at column 0, then a space; the marker strips.
        if (line.startsWith("#")) {
            val hashes = runEnd(line, 0, '#')
            if (hashes <= 6 && hashes < line.length && line[hashes] == ' ') {
                return Row(ProseLineKind.HEADING, "", line.substring(hashes + 1), scan = true)
            }
        }
        // Blockquote: one level, column 0; content keeps any nested "> ".
        if (line.startsWith("> ")) {
            return Row(ProseLineKind.QUOTE, QUOTE_PREFIX, line.substring(2), scan = true)
        }
        val indent = line.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) line.length else it }
        if (indent < line.length) {
            val c = line[indent]
            if ((c == '-' || c == '*' || c == '+') && indent + 1 < line.length && line[indent + 1] == ' ') {
                return Row(
                    ProseLineKind.BULLET,
                    line.substring(0, indent) + BULLET + ' ',
                    line.substring(indent + 2),
                    scan = true,
                )
            }
            if (c.isDigit()) {
                // `1. ` / `1)` lists keep their marker verbatim (the number
                // is content); ≥4 digits is a year mid-sentence, not a list.
                val digits = line.indexOfFirst2(indent) { !it.isDigit() }
                if (digits - indent in 1..3 && digits + 1 < line.length &&
                    (line[digits] == '.' || line[digits] == ')') && line[digits + 1] == ' '
                ) {
                    return Row(
                        ProseLineKind.NUMBERED,
                        line.substring(0, digits + 2),
                        line.substring(digits + 2),
                        scan = true,
                    )
                }
            }
        }
        return Row(ProseLineKind.PARAGRAPH, "", line, scan = true)
    }

    private inline fun String.indexOfFirst2(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < length && !predicate(this[i])) i++
        return i
    }

    // ── Inline level ────────────────────────────────────────────────────────
    //
    // Three phases over one segment, so degradation never has to un-emit:
    // tokenize (literal runs, resolved code spans, resolved links, POTENTIAL
    // emphasis delimiters) → match (stack-pair the delimiters; whatever
    // cannot pair stays unmatched) → emit (matched pairs become spans,
    // unmatched delimiters print their literal text back, character-exact).

    private sealed interface Tok
    private class TextTok(val text: String) : Tok
    private class CodeTok(val content: String) : Tok
    private class LinkTok(val inner: List<Tok>) : Tok
    private class DelimTok(
        /** What a matched pair applies — `***` carries BOLD and ITALIC. */
        val marks: List<ProseMark>,
        /** The marker verbatim, for the unmatched-degrade path. */
        val literal: String,
        /** Pairing identity: same character AND same run length only. */
        val key: String,
        val canOpen: Boolean,
        val canClose: Boolean,
    ) : Tok {
        var matched = false
        var opens = false
    }

    private fun scanInline(s: String): Pair<String, List<ProseSpan>> {
        val toks = matchDelims(tokenize(s, 0))
        val out = StringBuilder(s.length)
        val spans = ArrayList<ProseSpan>()
        emitToks(toks, out, spans)
        return out.toString() to spans
    }

    private fun tokenize(s: String, depth: Int): List<Tok> {
        val toks = ArrayList<Tok>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                toks += TextTok(text.toString())
                text.clear()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '`' -> {
                    // A backtick run pairs only with the next run of the SAME
                    // length (`` `` quoting). Empty content degrades too: a
                    // chip with nothing in it would consume `` invisibly.
                    val end = runEnd(s, i, '`')
                    val close = nextBacktickRun(s, end, end - i)
                    if (close > end) {
                        flush()
                        toks += CodeTok(s.substring(end, close))
                        i = close + (end - i)
                    } else {
                        text.append(s, i, end)
                        i = end
                    }
                }
                c == '*' || c == '_' || c == '~' -> {
                    val end = runEnd(s, i, c)
                    val marks = emphasisMarks(c, end - i)
                    val prev = if (i > 0) s[i - 1] else '\n'
                    val next = if (end < s.length) s[end] else '\n'
                    // CommonMark-ish flanking: open into a word, close out of
                    // one. `_` additionally never fires intra-word, so every
                    // snake_case_identifier agents narrate stays plain text.
                    var open = marks != null && !next.isWhitespace()
                    var close = marks != null && !prev.isWhitespace()
                    if (c == '_') {
                        open = open && !prev.isLetterOrDigit()
                        close = close && !next.isLetterOrDigit()
                    }
                    if (marks != null && (open || close)) {
                        flush()
                        toks += DelimTok(marks, s.substring(i, end), "$c${end - i}", open, close)
                    } else {
                        text.append(s, i, end)
                    }
                    i = end
                }
                c == '<' && s.startsWith("<u>", i) -> {
                    // Markdown has no underline; honour a literal <u> tag if
                    // one appears (the issue's "treat it, otherwise skip
                    // honestly") through the same pairing machinery.
                    flush()
                    toks += DelimTok(listOf(ProseMark.UNDERLINE), "<u>", "<u>", canOpen = true, canClose = false)
                    i += 3
                }
                c == '<' && s.startsWith("</u>", i) -> {
                    flush()
                    toks += DelimTok(listOf(ProseMark.UNDERLINE), "</u>", "<u>", canOpen = false, canClose = true)
                    i += 4
                }
                (c == '[' || (c == '!' && i + 1 < s.length && s[i + 1] == '[')) && depth < LINK_DEPTH_MAX -> {
                    // `[text](url)`: styled text only, url dropped — a watch
                    // has no tap for it. The label re-tokenizes (its own
                    // emphasis works) but matches in its own scope.
                    val open = if (c == '!') i + 1 else i
                    val link = linkEnd(s, open)
                    if (link != null) {
                        flush()
                        toks += LinkTok(matchDelims(tokenize(s.substring(open + 1, link.first), depth + 1)))
                        i = link.second + 1
                    } else {
                        text.append(c)
                        i++
                    }
                }
                else -> {
                    text.append(c)
                    i++
                }
            }
        }
        flush()
        return toks
    }

    /** `[label](url)` bounds, or null when the pattern is not certain here.
     *  Single-line ONLY: a dropped url must never swallow a '\n' — the line
     *  model counts them. */
    private fun linkEnd(s: String, open: Int): Pair<Int, Int>? {
        var cb = -1
        for (k in open + 1 until minOf(s.length, open + LINK_LOOKAHEAD)) {
            val ch = s[k]
            if (ch == '\n') return null
            if (ch == ']') {
                cb = k
                break
            }
        }
        if (cb < 0 || cb + 1 >= s.length || s[cb + 1] != '(') return null
        for (k in cb + 2 until minOf(s.length, cb + 2 + LINK_LOOKAHEAD)) {
            val ch = s[k]
            if (ch == '\n') return null
            if (ch == ')') return cb to k
        }
        return null
    }

    /** Run length → treatment; null is the degrade verdict (4+ is soup). */
    private fun emphasisMarks(c: Char, n: Int): List<ProseMark>? = when {
        c == '~' -> if (n == 2) STRIKE_ONLY else null
        n == 1 -> ITALIC_ONLY
        n == 2 -> BOLD_ONLY
        n == 3 -> BOLD_ITALIC
        else -> null
    }

    private val ITALIC_ONLY = listOf(ProseMark.ITALIC)
    private val BOLD_ONLY = listOf(ProseMark.BOLD)
    private val BOLD_ITALIC = listOf(ProseMark.BOLD, ProseMark.ITALIC)
    private val STRIKE_ONLY = listOf(ProseMark.STRIKE)

    /**
     * Stack-pair the delimiters. A closer takes the INNERMOST open of its
     * exact key; opens it jumps over (`**a *b** …`) fall off the stack
     * unmatched — improper nesting degrades to raw rather than guessing.
     * Whatever is still open at the segment's end was never certain, so it
     * stays unmatched and emits its marker verbatim (the issue's
     * opened-never-closed trap).
     */
    private fun matchDelims(toks: List<Tok>): List<Tok> {
        val stack = ArrayList<DelimTok>()
        for (t in toks) {
            if (t !is DelimTok) continue
            if (t.canClose) {
                val at = stack.indexOfLast { it.key == t.key }
                if (at >= 0) {
                    while (stack.size > at + 1) stack.removeAt(stack.size - 1)
                    val open = stack.removeAt(stack.size - 1)
                    open.matched = true
                    open.opens = true
                    t.matched = true
                    continue
                }
            }
            if (t.canOpen) stack += t
        }
        return toks
    }

    private fun emitToks(toks: List<Tok>, out: StringBuilder, spans: MutableList<ProseSpan>) {
        // Matched pairs are properly nested within one token list by
        // construction (stack discipline above), so this open stack mirrors
        // the matcher's exactly.
        val opens = ArrayList<Pair<DelimTok, Int>>()
        for (t in toks) when (t) {
            is TextTok -> out.append(t.text)
            is CodeTok -> {
                val start = out.length
                out.append(t.content)
                spans += ProseSpan(start, out.length, ProseMark.CODE)
            }
            is LinkTok -> {
                val start = out.length
                emitToks(t.inner, out, spans)
                if (out.length > start) spans += ProseSpan(start, out.length, ProseMark.LINK)
            }
            is DelimTok -> when {
                !t.matched -> out.append(t.literal)
                t.opens -> opens += t to out.length
                opens.isEmpty() -> out.append(t.literal) // unreachable; degrade, never throw
                else -> {
                    val (open, start) = opens.removeAt(opens.size - 1)
                    if (out.length > start) {
                        for (m in open.marks) spans += ProseSpan(start, out.length, m)
                    }
                }
            }
        }
        // Unreachable by construction — but the no-character-loss doctrine
        // holds even against our own bugs: a marker nothing closed prints,
        // position sacrificed, characters kept.
        for ((open, _) in opens) out.append(open.literal)
    }

    private fun runEnd(s: String, i: Int, c: Char): Int {
        var j = i
        while (j < s.length && s[j] == c) j++
        return j
    }

    private fun nextBacktickRun(s: String, from: Int, n: Int): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '`') {
                val end = runEnd(s, i, '`')
                if (end - i == n) return i
                i = end
            } else {
                i++
            }
        }
        return -1
    }
}
