package dev.claudewatch.shared

import dev.claudewatch.shared.terminal.ProseLineKind
import dev.claudewatch.shared.terminal.ProseMark
import dev.claudewatch.shared.terminal.ProseMarkdown
import dev.claudewatch.shared.terminal.ProseMarkup
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The #128 parser's contract: the vocabulary agents actually emit renders
 * with its markers consumed; everything the parser cannot prove closed —
 * unterminated pairs, marker soup, cross-block stragglers — renders as the
 * raw text it is. Never a crash, never a lost character: the styled text's
 * plain content equals the input minus consumed markers, or the raw input
 * verbatim when degraded. The two pinned traps from the issue live here:
 * a span opened on one line and closed on the next INSIDE one flushed block
 * (certain → styled across the '\n'), and opened-never-closed (raw).
 */
class ProseMarkdownTest {

    private fun spansOf(m: ProseMarkup, mark: ProseMark): List<String> =
        m.spans.filter { it.mark == mark }.map { m.text.substring(it.start, it.end) }

    private fun lineTexts(m: ProseMarkup): List<String> =
        m.lines.map { m.text.substring(it.start, it.end) }

    private fun lineKinds(m: ProseMarkup): List<ProseLineKind> = m.lines.map { it.kind }

    // ------------------------------------------------------------------
    // Vocabulary
    // ------------------------------------------------------------------

    @Test
    fun boldConsumesItsMarkers() {
        val m = ProseMarkdown.parse("a **bold** move")
        assertEquals("a bold move", m.text)
        assertEquals(listOf("bold"), spansOf(m, ProseMark.BOLD))
    }

    @Test
    fun italicWorksWithBothMarkers() {
        assertEquals("lean text", ProseMarkdown.parse("*lean* text").text)
        assertEquals(listOf("lean"), spansOf(ProseMarkdown.parse("*lean* text"), ProseMark.ITALIC))
        assertEquals(listOf("lean"), spansOf(ProseMarkdown.parse("_lean_ text"), ProseMark.ITALIC))
    }

    @Test
    fun underscoresInsideIdentifiersStayText() {
        // Agents narrate snake_case constantly; intra-word `_` is never
        // emphasis (the CommonMark rule kept on purpose).
        val m = ProseMarkdown.parse("set tool_input and file_path here")
        assertEquals("set tool_input and file_path here", m.text)
        assertTrue(m.spans.isEmpty())
    }

    @Test
    fun strikethroughAndUnderlineTag() {
        val struck = ProseMarkdown.parse("~~gone~~ now")
        assertEquals("gone now", struck.text)
        assertEquals(listOf("gone"), spansOf(struck, ProseMark.STRIKE))

        val under = ProseMarkdown.parse("<u>held</u> up")
        assertEquals("held up", under.text)
        assertEquals(listOf("held"), spansOf(under, ProseMark.UNDERLINE))
    }

    @Test
    fun inlineCodeBecomesAChipAndProtectsItsContent() {
        val m = ProseMarkdown.parse("run `npm test` now")
        assertEquals("run npm test now", m.text)
        assertEquals(listOf("npm test"), spansOf(m, ProseMark.CODE))

        // Markers inside code are content, not markup.
        val guarded = ProseMarkdown.parse("`**not bold**`")
        assertEquals("**not bold**", guarded.text)
        assertEquals(listOf("**not bold**"), spansOf(guarded, ProseMark.CODE))
        assertTrue(spansOf(guarded, ProseMark.BOLD).isEmpty())
    }

    @Test
    fun doubleBacktickQuotesSingleBackticks() {
        val m = ProseMarkdown.parse("`` `quoted` ``")
        assertEquals(" `quoted` ", m.text)
        assertEquals(listOf(" `quoted` "), spansOf(m, ProseMark.CODE))
    }

    @Test
    fun headingStripsItsMarkerAndKeepsInlineStyling() {
        val m = ProseMarkdown.parse("## Test **report**")
        assertEquals("Test report", m.text)
        assertEquals(listOf(ProseLineKind.HEADING), lineKinds(m))
        assertEquals(listOf("report"), spansOf(m, ProseMark.BOLD))

        // No space, or 7+ hashes: not a heading — raw.
        assertEquals("##nope", ProseMarkdown.parse("##nope").text)
        assertEquals(listOf(ProseLineKind.PARAGRAPH), lineKinds(ProseMarkdown.parse("##nope")))
        assertEquals("####### deep", ProseMarkdown.parse("####### deep").text)
    }

    @Test
    fun listMarkersBecomeTidyBulletsAndNumbersKeepTheirs() {
        val m = ProseMarkdown.parse("- first\n* second\n  - nested\n1. one\n12) twelve")
        assertEquals(
            listOf("• first", "• second", "  • nested", "1. one", "12) twelve"),
            lineTexts(m),
        )
        assertEquals(
            listOf(
                ProseLineKind.BULLET, ProseLineKind.BULLET, ProseLineKind.BULLET,
                ProseLineKind.NUMBERED, ProseLineKind.NUMBERED,
            ),
            lineKinds(m),
        )
    }

    @Test
    fun starWithoutSpaceIsItalicsNotABullet() {
        val m = ProseMarkdown.parse("*item*")
        assertEquals("item", m.text)
        assertEquals(listOf(ProseLineKind.PARAGRAPH), lineKinds(m))
        assertEquals(listOf("item"), spansOf(m, ProseMark.ITALIC))
    }

    @Test
    fun yearsAndBareDashesAreNotLists() {
        assertEquals("2026. the year", ProseMarkdown.parse("2026. the year").text)
        assertEquals(listOf(ProseLineKind.PARAGRAPH), lineKinds(ProseMarkdown.parse("2026. the year")))
        assertEquals("---", ProseMarkdown.parse("---").text)
        assertEquals("-dash", ProseMarkdown.parse("-dash").text)
    }

    @Test
    fun blockquoteSwapsTheMarkerForTheLeadGlyph() {
        val m = ProseMarkdown.parse("> the ask")
        assertEquals("${ProseMarkdown.QUOTE_PREFIX}the ask", m.text)
        assertEquals(listOf(ProseLineKind.QUOTE), lineKinds(m))
    }

    @Test
    fun linksKeepTheirLabelAndDropTheUrl() {
        val m = ProseMarkdown.parse("see [the docs](https://example.com/x) now")
        assertEquals("see the docs now", m.text)
        assertEquals(listOf("the docs"), spansOf(m, ProseMark.LINK))

        // Image form: alt text kept, `!` consumed with the markers.
        assertEquals("alt here", ProseMarkdown.parse("![alt](u) here").text)

        // Half a link is not a link.
        assertEquals("[dangling](no", ProseMarkdown.parse("[dangling](no").text)
        assertEquals("[text] (spaced)", ProseMarkdown.parse("[text] (spaced)").text)
    }

    @Test
    fun saneNestingStyles() {
        val m = ProseMarkdown.parse("**bold `code` bold**")
        assertEquals("bold code bold", m.text)
        assertEquals(listOf("bold code bold"), spansOf(m, ProseMark.BOLD))
        assertEquals(listOf("code"), spansOf(m, ProseMark.CODE))

        val both = ProseMarkdown.parse("***both***")
        assertEquals("both", both.text)
        assertEquals(listOf("both"), spansOf(both, ProseMark.BOLD))
        assertEquals(listOf("both"), spansOf(both, ProseMark.ITALIC))

        val linked = ProseMarkdown.parse("[**strong** label](u)")
        assertEquals("strong label", linked.text)
        assertEquals(listOf("strong"), spansOf(linked, ProseMark.BOLD))
        assertEquals(listOf("strong label"), spansOf(linked, ProseMark.LINK))
    }

    @Test
    fun fencedBlocksRenderVerbatimAsCodeLines() {
        val m = ProseMarkdown.parse("```kotlin\nval x = **1**\n# comment\n- not a list\n```")
        // The fence markers are consumed; the info string survives (it is
        // characters, and characters never drop); the interior keeps every
        // marker byte and takes no block classification.
        assertEquals(listOf("kotlin", "val x = **1**", "# comment", "- not a list"), lineTexts(m))
        assertTrue(lineKinds(m).all { it == ProseLineKind.CODE })
        assertTrue(m.spans.isEmpty())
    }

    @Test
    fun unpairedFenceDegradesToRawText() {
        val m = ProseMarkdown.parse("```\nstill prose **here**")
        assertEquals("```\nstill prose here", m.text)
        assertEquals(listOf(ProseLineKind.PARAGRAPH, ProseLineKind.PARAGRAPH), lineKinds(m))
        assertEquals(listOf("here"), spansOf(m, ProseMark.BOLD))
    }

    // ------------------------------------------------------------------
    // The two pinned traps
    // ------------------------------------------------------------------

    @Test
    fun spanOpenedOnOneLineClosesOnTheNextInsideOneBlock() {
        val m = ProseMarkdown.parse("start **bold\nstill** end")
        assertEquals("start bold\nstill end", m.text)
        assertEquals(listOf("start bold", "still end"), lineTexts(m))
        // ONE span, crossing the '\n': the flushed block made the close
        // certain, so both halves style.
        assertEquals(listOf("bold\nstill"), spansOf(m, ProseMark.BOLD))
    }

    @Test
    fun spanOpenedButNeverClosedRendersRaw() {
        val m = ProseMarkdown.parse("start **bold\nnever closed")
        assertEquals("start **bold\nnever closed", m.text)
        assertTrue(m.spans.isEmpty())
    }

    @Test
    fun continuityEndsAtBlockBoundaries() {
        // A blank line ends the paragraph: the `**` on either side of it
        // never pair, and both render raw.
        val blank = ProseMarkdown.parse("**open\n\nclose** x")
        assertEquals("**open\n\nclose** x", blank.text)
        assertTrue(blank.spans.isEmpty())

        // So does a heading between them.
        val heading = ProseMarkdown.parse("**open\n## H\nclose** x")
        assertEquals("**open\nH\nclose** x", heading.text)
        assertTrue(heading.spans.isEmpty())
    }

    // ------------------------------------------------------------------
    // Degradation: unbalanced, soup, empty — raw, never lossy
    // ------------------------------------------------------------------

    @Test
    fun unbalancedAndUnanchoredMarkersStayRaw() {
        for (raw in listOf(
            "**",
            "****",
            "a ** b",
            "spaced * stars * inline",
            "~single~ tilde",
            "~~~three~~~",
            "trailing **",
            "</u> without open",
            "<u> without close",
            "**a *b",
        )) {
            val m = ProseMarkdown.parse(raw)
            assertEquals("degraded verbatim: $raw", raw, m.text)
            assertTrue("no spans for: $raw", m.spans.isEmpty())
        }
    }

    @Test
    fun improperNestingDegradesTheUncertainPart() {
        // The close jumps over the inner `*`: bold is certain, the straggler
        // reverts to raw — predictable, character-exact.
        val m = ProseMarkdown.parse("**a *b** c")
        assertEquals("a *b c", m.text)
        assertEquals(listOf("a *b"), spansOf(m, ProseMark.BOLD))
        assertTrue(spansOf(m, ProseMark.ITALIC).isEmpty())
    }

    @Test
    fun emptyAndBlankBlocksParse() {
        assertEquals("", ProseMarkdown.parse("").text)
        assertEquals(listOf(""), lineTexts(ProseMarkdown.parse("")))
        assertEquals(listOf("one", "", "two"), lineTexts(ProseMarkdown.parse("one\n\ntwo")))
    }

    @Test
    fun crReturnsNormalizeWithoutGhosts() {
        assertEquals(listOf("a", "b"), lineTexts(ProseMarkdown.parse("a\r\nb")))
        assertEquals(listOf("a", "b"), lineTexts(ProseMarkdown.parse("a\rb")))
    }

    // ------------------------------------------------------------------
    // Property-style: adversarial soup — no crash, no character loss
    // ------------------------------------------------------------------

    /** Model-wide invariants every parse must satisfy, whatever the input. */
    private fun assertWellFormed(raw: String, m: ProseMarkup) {
        for (s in m.spans) {
            assertTrue("span in bounds: $raw", s.start in 0..s.end && s.end <= m.text.length)
        }
        // Lines tile the text with exactly one '\n' between neighbours.
        var expectedStart = 0
        for (line in m.lines) {
            assertEquals("line tiling: $raw", expectedStart, line.start)
            assertTrue("line in bounds: $raw", line.end in line.start..m.text.length)
            expectedStart = line.end + 1
        }
        assertEquals("lines cover the text: $raw", m.text.length, m.lines.last().end)
    }

    @Test
    fun randomSoupNeverCrashesOrDropsLettersAndDigits() {
        // Block markers, emphasis, fences, digits — everything EXCEPT link
        // syntax and <u> tags, whose by-design consumption (urls, tag chars)
        // is covered by the exact cases above.
        val alphabet = "ab c*_~`#->.\n12"
        val rng = Random(128)
        repeat(300) {
            val raw = buildString {
                repeat(rng.nextInt(0, 400)) { append(alphabet[rng.nextInt(alphabet.length)]) }
            }
            val m = ProseMarkdown.parse(raw)
            assertWellFormed(raw, m)
            assertEquals(
                "letters/digits survive: $raw",
                raw.filter { it.isLetterOrDigit() },
                m.text.filter { it.isLetterOrDigit() },
            )
        }
    }

    @Test
    fun inlineSoupConsumesNothingButItsOwnMarkers() {
        // Lines pinned to start with a letter: no block markers can form, so
        // the ONLY consumable characters are the emphasis/code markers
        // themselves — everything else must survive byte for byte, spaces
        // and newlines included.
        val alphabet = "ab c*_~`"
        val rng = Random(129)
        repeat(300) {
            val raw = (0 until rng.nextInt(1, 6)).joinToString("\n") {
                "x" + buildString {
                    repeat(rng.nextInt(0, 120)) { append(alphabet[rng.nextInt(alphabet.length)]) }
                }
            }
            val m = ProseMarkdown.parse(raw)
            assertWellFormed(raw, m)
            assertEquals(
                "non-markers survive: $raw",
                raw.filter { it !in "*_~`" },
                m.text.filter { it !in "*_~`" },
            )
        }
    }

    @Test
    fun bracketSoupNeverCrashes() {
        // Link syntax consumes urls by design, so only totality and model
        // invariants are asserted here.
        val alphabet = "a[]()!<u>/ `*\n"
        val rng = Random(130)
        repeat(300) {
            val raw = buildString {
                repeat(rng.nextInt(0, 300)) { append(alphabet[rng.nextInt(alphabet.length)]) }
            }
            assertWellFormed(raw, ProseMarkdown.parse(raw))
        }
    }

    @Test
    fun hugeLinesParseWholeAndKeepEveryLetter() {
        // No link syntax here: a dropped url is BY-DESIGN character
        // consumption, and this test's whole claim is zero loss.
        for (raw in listOf(
            "*a".repeat(30_000),
            "**bold** and `code` plus **more** ".repeat(2_000),
            "[".repeat(50_000),
            "`".repeat(50_000),
            "word ".repeat(12_000),
        )) {
            val m = ProseMarkdown.parse(raw)
            assertWellFormed(raw, m)
            assertEquals(
                raw.filter { it.isLetterOrDigit() }.length,
                m.text.filter { it.isLetterOrDigit() }.length,
            )
        }
    }
}
