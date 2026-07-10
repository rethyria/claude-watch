package dev.claudewatch.shared

import dev.claudewatch.shared.BridgeEventFixtures.SESSION_ALPHA
import dev.claudewatch.shared.BridgeEventFixtures.SESSION_BETA
import dev.claudewatch.shared.protocol.SseFrame
import dev.claudewatch.shared.state.BridgeEventReducer
import dev.claudewatch.shared.state.BridgeState
import dev.claudewatch.shared.state.SessionState
import dev.claudewatch.shared.terminal.TerminalLine
import dev.claudewatch.shared.terminal.TerminalLineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal half of issue #19: events reduce into per-session human-readable
 * terminal lines (no raw JSON), the per-session ring buffer wraps at
 * [SessionState.TERMINAL_BUFFER_LINES], and the thinking cursor raised by a
 * local command echo clears on the session's next output.
 */
class TerminalReducerTest {

    private fun fold(frames: List<SseFrame>, initial: BridgeState = BridgeState()): BridgeState =
        frames.foldIndexed(initial) { i, state, frame ->
            when (val result = BridgeEventReducer.reduce(state, frame, 1_000_000L + i * 1_000L)) {
                is BridgeEventReducer.Applied -> result.state
                is BridgeEventReducer.Rejected ->
                    throw AssertionError("frame ${frame.id} rejected: ${result.error}")
            }
        }

    private fun running(id: String, folder: String = id) = SseFrame(
        null,
        "session",
        """{"state":"running","agent":"claude","cwd":"/p/$folder","folderName":"$folder","sessionId":"$id"}""",
    )

    // ------------------------------------------------------------------
    // Fixture corpus renders as human-readable terminal lines
    // ------------------------------------------------------------------

    @Test
    fun corpusRendersHumanReadablePerSessionTerminals() {
        val state = fold(BridgeEventFixtures.corpus().filter { it.id!!.toInt() <= 14 })

        // Alpha: PTY banner ANSI-stripped, Read summarized to its filename,
        // stop marker appended — and nothing that looks like raw JSON.
        val alpha = state.sessions.getValue(SESSION_ALPHA).terminal.items
        assertEquals(
            listOf(
                TerminalLine("$ claude", TerminalLineType.OUTPUT),
                TerminalLine("Welcome to Claude Code!", TerminalLineType.OUTPUT),
                TerminalLine("Read README.md", TerminalLineType.SYSTEM),
                TerminalLine("— stopped —", TerminalLineType.SYSTEM),
            ),
            alpha,
        )
        assertFalse(alpha.any { it.text.contains("{") || it.text.contains("tool_name") })

        // Beta (codex): its own terminal, codex-prefixed tool line, and no
        // cross-contamination from alpha's events.
        val beta = state.sessions.getValue(SESSION_BETA).terminal.items
        assertEquals(
            listOf(
                TerminalLine("[codex] running npm test", TerminalLineType.OUTPUT),
                TerminalLine("[codex] $ npm test", TerminalLineType.COMMAND),
            ),
            beta,
        )
    }

    @Test
    fun sessionAddressedErrorLandsInThatTerminalAsAnErrorLine() {
        val state = fold(
            listOf(
                running("A"),
                SseFrame(null, "error", """{"error":"Failed to spawn claude: ENOENT","sessionId":"A"}"""),
            ),
        )
        assertEquals(
            listOf(TerminalLine("Failed to spawn claude: ENOENT", TerminalLineType.ERROR)),
            state.sessions.getValue("A").terminal.items,
        )

        // A global (unaddressed) error touches no session terminal.
        val global = fold(listOf(running("B"), SseFrame(null, "error", """{"error":"boom"}""")))
        assertTrue(global.sessions.getValue("B").terminal.isEmpty())
    }

    // ------------------------------------------------------------------
    // Ring buffer wrap at the reducer level
    // ------------------------------------------------------------------

    @Test
    fun terminalRingBufferWrapsAtTheCapAndKeepsTheNewestLines() {
        val overflow = SessionState.TERMINAL_BUFFER_LINES + 25
        val frames = listOf(running("A")) + (1..overflow).map {
            SseFrame(null, "pty-output", """{"text":"line-$it\r\n","sessionId":"A"}""")
        }
        val terminal = fold(frames).sessions.getValue("A").terminal
        assertEquals(SessionState.TERMINAL_BUFFER_LINES, terminal.size)
        assertEquals("line-26", terminal.items.first().text)
        assertEquals("line-$overflow", terminal.items.last().text)
    }

    // ------------------------------------------------------------------
    // Thinking cursor lifecycle
    // ------------------------------------------------------------------

    @Test
    fun commandEchoRaisesTheCursorAndTheNextOutputClearsIt() {
        val ready = fold(listOf(running("A"), running("B")))

        val sent = ready.echoCommand("A", "say hello")
        val a = sent.sessions.getValue("A")
        assertTrue(a.thinking)
        assertEquals(TerminalLine("> say hello", TerminalLineType.COMMAND), a.terminal.items.last())
        // Only the addressed session thinks.
        assertFalse(sent.sessions.getValue("B").thinking)

        // Output for ANOTHER session must not clear A's cursor...
        val otherOutput = fold(
            listOf(SseFrame(null, "pty-output", """{"text":"b says hi\r\n","sessionId":"B"}""")),
            initial = sent,
        )
        assertTrue(otherOutput.sessions.getValue("A").thinking)

        // ...but A's own next output does, and the output lands after the echo.
        val cleared = fold(
            listOf(SseFrame(null, "pty-output", """{"text":"hello!\r\n","sessionId":"A"}""")),
            initial = otherOutput,
        )
        val clearedA = cleared.sessions.getValue("A")
        assertFalse(clearedA.thinking)
        assertEquals(
            listOf("> say hello", "hello!"),
            clearedA.terminal.items.takeLast(2).map { it.text },
        )
    }

    @Test
    fun toolOutputStopAndTaskCompleteAllClearTheCursor() {
        val base = fold(listOf(running("A"))).echoCommand("A", "go")

        val byTool = fold(
            listOf(SseFrame(null, "tool-output", """{"tool_name":"Read","tool_input":{"file_path":"/a.txt"},"sessionId":"A"}""")),
            initial = base,
        )
        assertFalse(byTool.sessions.getValue("A").thinking)

        val byStop = fold(listOf(SseFrame(null, "stop", """{"sessionId":"A"}""")), initial = base)
        assertFalse(byStop.sessions.getValue("A").thinking)
        assertEquals("— stopped —", byStop.sessions.getValue("A").terminal.items.last().text)

        val byTaskComplete = fold(
            listOf(SseFrame(null, "task-complete", """{"sessionId":"A"}""")),
            initial = base,
        )
        assertFalse(byTaskComplete.sessions.getValue("A").thinking)
    }

    @Test
    fun blankPtyKeepaliveNeitherAppendsNorClearsTheCursor() {
        val base = fold(listOf(running("A"))).echoCommand("A", "go")
        val after = fold(
            listOf(SseFrame(null, "pty-output", """{"text":"\r\n","sessionId":"A"}""")),
            initial = base,
        )
        val a = after.sessions.getValue("A")
        assertTrue(a.thinking)
        assertEquals(listOf("> go"), a.terminal.items.map { it.text })
    }

    @Test
    fun echoForUnknownOrAbsentSessionIsANoOp() {
        val state = fold(listOf(running("A")))
        assertEquals(state, state.echoCommand("nope", "hi"))
        assertEquals(state, state.echoCommand(null, "hi"))
    }
}
