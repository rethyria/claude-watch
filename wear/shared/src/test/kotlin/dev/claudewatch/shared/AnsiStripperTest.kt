package dev.claudewatch.shared

import dev.claudewatch.shared.terminal.AnsiStripper
import org.junit.Assert.assertEquals
import org.junit.Test

class AnsiStripperTest {

    private val esc = "\u001B"
    private val bel = "\u0007"

    @Test
    fun stripsSgrColorSequences() {
        assertEquals(
            "Welcome to Claude Code!",
            AnsiStripper.strip("$esc[1mWelcome to $esc[38;5;208mClaude Code!$esc[0m"),
        )
    }

    @Test
    fun stripsPrivateModeAndCursorControlCsi() {
        // ESC[?25l (hide cursor), ESC[2J (clear), ESC[H (home) — the naive
        // `[0-9;]*[a-zA-Z]` regex leaves the `?25l` form on screen.
        assertEquals("ready", AnsiStripper.strip("$esc[?25l$esc[2J$esc[Hready$esc[?25h"))
    }

    @Test
    fun stripsOscTitleSequencesWithBelAndStTerminators() {
        assertEquals("done", AnsiStripper.strip("$esc]0;claude - proj${bel}done"))
        assertEquals("done", AnsiStripper.strip("$esc]2;title$esc\\done"))
    }

    @Test
    fun dropsUnterminatedOscBodyInsteadOfRenderingItRaw() {
        // A chunk boundary can split an OSC sequence; the stranded body must
        // not appear as terminal text.
        assertEquals("", AnsiStripper.strip("$esc]0;half a title"))
    }

    @Test
    fun stripsBareTwoByteEscapes() {
        // Two-byte escapes: ESC + one byte in 0x40-0x5F, e.g. ESC M (reverse
        // index).
        assertEquals("up", AnsiStripper.strip("${esc}Mup"))
    }

    @Test
    fun normalizesCrLfAndDropsStrayControlCharacters() {
        assertEquals("a\nb", AnsiStripper.strip("a\r\nb"))
        assertEquals("ab", AnsiStripper.strip("a\rb"))
        assertEquals("ab", AnsiStripper.strip("a\bb"))
        // \n and \t survive: they are layout, not noise.
        assertEquals("a\tb\nc", AnsiStripper.strip("a\tb\nc"))
    }

    @Test
    fun plainTextPassesThroughUntouched() {
        assertEquals("$ npm test", AnsiStripper.strip("$ npm test"))
    }
}
