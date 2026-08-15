package dev.claudewatch.wear.ui.halo

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.claudewatch.shared.terminal.ProseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Compose mapping half of #128 on the JVM: ProseMarkup → AnnotatedString.
 * The parser's own contract lives in :shared (ProseMarkdownTest); this pins
 * what the mapper adds — every line becomes its own paragraph with the '\n'
 * separators dropped (paragraph ranges break lines, and keeping the newline
 * characters too would render phantom empties), list paragraphs carry the
 * hanging indent, and block-global spans clip per line.
 */
class HaloProseTest {

    private fun annotated(raw: String) = proseAnnotated(ProseMarkdown.parse(raw))

    @Test
    fun everyLineIsItsOwnParagraphWithNewlinesDropped() {
        val styled = annotated("## Head\n- item\nplain **bold**")
        // Flattened text: markers consumed, line separators carried by the
        // paragraph ranges rather than characters.
        assertEquals("Head• itemplain bold", styled.text)
        assertEquals(3, styled.paragraphStyles.size)
        // Contiguous tiling: paragraph k ends where k+1 begins.
        assertEquals(0, styled.paragraphStyles[0].start)
        assertEquals(styled.paragraphStyles[0].end, styled.paragraphStyles[1].start)
        assertEquals(styled.paragraphStyles[1].end, styled.paragraphStyles[2].start)
        assertEquals(styled.text.length, styled.paragraphStyles[2].end)
        // Only the list line indents.
        assertEquals(
            listOf(false, true, false),
            styled.paragraphStyles.map { it.item.textIndent != null },
        )
    }

    @Test
    fun crossLineSpanClipsIntoOneRangePerLine() {
        val styled = annotated("a **b\nc** d")
        assertEquals("a bc d", styled.text)
        val bold = styled.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(
            listOf("b", "c"),
            bold.map { styled.text.substring(it.start, it.end) },
        )
    }

    @Test
    fun vocabularyMapsToItsSpanStyles() {
        val styled = annotated("**b** *i* ~~s~~ `c` [l](u)")
        assertEquals("b i s c l", styled.text)
        fun at(text: String) = styled.text.indexOf(text)
        assertTrue(styled.spanStyles.any { it.start == at("b") && it.item.fontWeight == FontWeight.Bold })
        assertTrue(styled.spanStyles.any { it.start == at("i") && it.item.fontStyle == FontStyle.Italic })
        assertTrue(styled.spanStyles.any { it.start == at("s") && it.item.textDecoration == TextDecoration.LineThrough })
        assertTrue(
            styled.spanStyles.any {
                it.start == at("c") && it.item.fontFamily != null && it.item.background != androidx.compose.ui.graphics.Color.Unspecified
            },
        )
        assertTrue(styled.spanStyles.any { it.start == at("l") && it.item.textDecoration == TextDecoration.Underline })
    }

    @Test
    fun degradedInputPassesThroughVerbatim() {
        val raw = "half **open and `stray"
        val styled = annotated(raw)
        assertEquals(raw, styled.text)
        assertFalse(styled.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }
}
