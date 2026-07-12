package dev.claudewatch.shared

import dev.claudewatch.shared.protocol.BridgeEventParser
import dev.claudewatch.shared.protocol.ToolOutputEvent
import dev.claudewatch.shared.terminal.TerminalLine
import dev.claudewatch.shared.terminal.TerminalLineType
import dev.claudewatch.shared.terminal.ToolOutputFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputFormatterTest {

    private fun event(json: String): ToolOutputEvent =
        BridgeEventParser.parse("tool-output", json) as ToolOutputEvent

    // ------------------------------------------------------------------
    // Per-tool rendering
    // ------------------------------------------------------------------

    @Test
    fun bashRendersCommandLinePlusFirstOutputLines() {
        val lines = ToolOutputFormatter.format(
            event(
                """{"tool_name":"Bash","tool_input":{"command":"npm test"},""" +
                    """"tool_output":"line1\n\n  line2  \nline3\nline4\nline5\nline6\nline7"}""",
            ),
        )
        assertEquals(TerminalLine("$ npm test", TerminalLineType.COMMAND), lines.first())
        // Blank lines dropped, whitespace trimmed, capped at BASH_OUTPUT_LINES.
        assertEquals(
            listOf("line1", "line2", "line3", "line4", "line5"),
            lines.drop(1).map { it.text },
        )
        assertTrue(lines.drop(1).all { it.type == TerminalLineType.OUTPUT })
    }

    @Test
    fun bashOutputIsAnsiStripped() {
        val lines = ToolOutputFormatter.format(
            event(
                """{"tool_name":"Bash","tool_input":{"command":"ls"},""" +
                    // \u001b survives the raw string and is decoded by the
                    // JSON parser into a real ESC character.
                    """"tool_output":"\u001b[32mPASS\u001b[0m all good"}""",
            ),
        )
        assertEquals(listOf("$ ls", "PASS all good"), lines.map { it.text })
    }

    @Test
    fun bashWithoutOutputRendersOnlyTheCommand() {
        val lines = ToolOutputFormatter.format(
            event("""{"tool_name":"Bash","tool_input":{"command":"true"},"tool_output":null}"""),
        )
        assertEquals(listOf(TerminalLine("$ true", TerminalLineType.COMMAND)), lines)
    }

    @Test
    fun readEditWriteRenderAsToolNamePlusFileName() {
        for (tool in listOf("Read", "Edit", "Write")) {
            val lines = ToolOutputFormatter.format(
                event("""{"tool_name":"$tool","tool_input":{"file_path":"/home/dev/proj/src/Main.kt"}}"""),
            )
            assertEquals(listOf(TerminalLine("$tool Main.kt", TerminalLineType.SYSTEM)), lines)
        }
    }

    @Test
    fun grepRendersThePattern() {
        val lines = ToolOutputFormatter.format(
            event("""{"tool_name":"Grep","tool_input":{"pattern":"TODO|FIXME"}}"""),
        )
        assertEquals(listOf(TerminalLine("grep \"TODO|FIXME\"", TerminalLineType.COMMAND)), lines)
    }

    @Test
    fun codexSourceGetsTheCodexPrefixOnEveryShape() {
        val bash = ToolOutputFormatter.format(
            event("""{"tool_name":"Bash","tool_input":{"command":"npm test"},"tool_output":null,"source":"codex"}"""),
        )
        assertEquals("[codex] $ npm test", bash.single().text)

        val read = ToolOutputFormatter.format(
            event("""{"tool_name":"Read","tool_input":{"file_path":"/a/b.txt"},"source":"codex"}"""),
        )
        assertEquals("[codex] Read b.txt", read.single().text)

        val message = ToolOutputFormatter.format(
            event("""{"tool_name":"CodexMessage","tool_output":"I ran the tests.","source":"codex"}"""),
        )
        assertEquals(
            listOf(TerminalLine("[codex] I ran the tests.", TerminalLineType.OUTPUT)),
            message,
        )
    }

    @Test
    fun codexMessageWithoutTextRendersNothing() {
        val lines = ToolOutputFormatter.format(
            event("""{"tool_name":"CodexMessage","tool_output":null,"source":"codex"}"""),
        )
        assertTrue(lines.isEmpty())
    }

    @Test
    fun unknownToolRendersAsBracketedName() {
        val lines = ToolOutputFormatter.format(
            event("""{"tool_name":"WebSearch","tool_input":{"query":"kotlin"}}"""),
        )
        assertEquals(listOf(TerminalLine("[WebSearch]", TerminalLineType.SYSTEM)), lines)
    }

    @Test
    fun everyLineIsCappedAtMaxLineChars() {
        val longCmd = "x".repeat(300)
        val lines = ToolOutputFormatter.format(
            event("""{"tool_name":"Bash","tool_input":{"command":"$longCmd"},"tool_output":"${"y".repeat(300)}"}"""),
        )
        assertTrue(lines.all { it.text.length <= ToolOutputFormatter.MAX_LINE_CHARS })
    }

    // ------------------------------------------------------------------
    // Tool REQUEST summaries (permission cards: WHAT is being asked)
    // ------------------------------------------------------------------

    @Test
    fun toolRequestSummaryShowsTheActualAsk() {
        fun inputOf(json: String) =
            (BridgeEventParser.parse("tool-output", json) as ToolOutputEvent).toolInput

        assertEquals(
            "$ rm -rf ./build",
            ToolOutputFormatter.describeToolRequest(
                "Bash",
                inputOf("""{"tool_input":{"command":"rm -rf ./build"}}"""),
            ),
        )
        assertEquals(
            "Write notes.txt",
            ToolOutputFormatter.describeToolRequest(
                "Write",
                inputOf("""{"tool_input":{"file_path":"/tmp/beta/notes.txt"}}"""),
            ),
        )
        assertEquals(
            "grep \"TODO\"",
            ToolOutputFormatter.describeToolRequest(
                "Grep",
                inputOf("""{"tool_input":{"pattern":"TODO"}}"""),
            ),
        )
        assertEquals("[WebSearch]", ToolOutputFormatter.describeToolRequest("WebSearch", null))
        assertEquals("[tool]", ToolOutputFormatter.describeToolRequest(null, null))
    }

    @Test
    fun toolRequestSummaryIsCappedAtMaxLineChars() {
        val long = ToolOutputFormatter.describeToolRequest(
            "Bash",
            (
                BridgeEventParser.parse(
                    "tool-output",
                    """{"tool_input":{"command":"${"x".repeat(300)}"}}""",
                ) as ToolOutputEvent
                ).toolInput,
        )
        assertTrue(long.length <= ToolOutputFormatter.MAX_LINE_CHARS)
    }

    // ------------------------------------------------------------------
    // PTY output
    // ------------------------------------------------------------------

    @Test
    fun ptyOutputIsStrippedSplitAndBlankFiltered() {
        val lines = ToolOutputFormatter.formatPtyOutput(
            "$ claude\r\n\u001B[1mWelcome to Claude Code!\u001B[0m\r\n\r\n",
        )
        assertEquals(listOf("$ claude", "Welcome to Claude Code!"), lines.map { it.text })
        assertTrue(lines.all { it.type == TerminalLineType.OUTPUT })
    }

    @Test
    fun blankPtyKeepaliveProducesNoLines() {
        assertTrue(ToolOutputFormatter.formatPtyOutput("\r\n \u001B[0m \r\n").isEmpty())
    }
}
