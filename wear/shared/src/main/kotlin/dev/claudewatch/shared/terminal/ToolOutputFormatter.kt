// Human-readable terminal lines from tool/PTY events — the presentation
// pipeline that replaces raw JSON on the watch. Rendering rules match the
// watchOS app's handleToolOutput (WatchViewState.swift): Bash as `$ cmd` plus
// the first output lines, Read/Edit/Write as the filename, Grep as the
// pattern, `[codex] ` prefix for Codex-sourced events, unknown tools as
// `[ToolName]`.
package dev.claudewatch.shared.terminal

import dev.claudewatch.shared.protocol.ToolOutputEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object ToolOutputFormatter {

    /** Bash tool output is summarized to its first lines (watch-sized). */
    const val BASH_OUTPUT_LINES = 5

    /** Hard cap per rendered line; the watch face fits ~4 wrapped rows of 11 sp mono. */
    const val MAX_LINE_CHARS = 80

    /**
     * One-line summary of a tool REQUEST (`tool_name` + `tool_input`) — what
     * a permission card must show the user before they approve: the actual
     * command for Bash, the target file for Read/Edit/Write, the pattern for
     * Grep, `[ToolName]` otherwise. Same per-tool rendering rules as [format],
     * minus outputs (a request has none yet) and the codex prefix.
     */
    fun describeToolRequest(toolName: String?, toolInput: JsonObject?): String {
        fun input(key: String): String? =
            (toolInput?.get(key) as? JsonPrimitive)?.contentOrNull

        return when (toolName) {
            "Bash" -> "$ ${input("command").orEmpty()}"
            "Read", "Edit", "Write" -> "$toolName ${fileName(input("file_path"))}"
            "Grep" -> "grep \"${input("pattern").orEmpty()}\""
            null -> "[tool]"
            else -> "[$toolName]"
        }.take(MAX_LINE_CHARS)
    }

    /** Format one `tool-output` event into terminal lines. */
    fun format(event: ToolOutputEvent): List<TerminalLine> {
        val prefix = if (event.source == "codex") "[codex] " else ""
        val toolName = event.toolName ?: "tool"

        fun input(key: String): String? =
            (event.toolInput?.get(key) as? JsonPrimitive)?.contentOrNull

        return when (toolName) {
            "Bash" -> buildList {
                add(line("$prefix$ ${input("command").orEmpty()}", TerminalLineType.COMMAND))
                val output = event.toolOutputText
                if (output != null) {
                    addAll(
                        displayLines(output)
                            .take(BASH_OUTPUT_LINES)
                            .map { line(it, TerminalLineType.OUTPUT) },
                    )
                }
            }

            "Read", "Edit", "Write" ->
                listOf(line("$prefix$toolName ${fileName(input("file_path"))}", TerminalLineType.SYSTEM))

            "Grep" ->
                listOf(line("${prefix}grep \"${input("pattern").orEmpty()}\"", TerminalLineType.COMMAND))

            // Codex assistant text: show the message itself (first line).
            "CodexMessage" ->
                event.toolOutputText
                    ?.let { displayLines(it).firstOrNull() }
                    ?.let { listOf(line("$prefix$it", TerminalLineType.OUTPUT)) }
                    ?: emptyList()

            else -> listOf(line("$prefix[$toolName]", TerminalLineType.SYSTEM))
        }
    }

    /** Format raw PTY bytes: ANSI-stripped, split, trimmed, blank lines dropped. */
    fun formatPtyOutput(text: String): List<TerminalLine> =
        displayLines(text).map { line(it, TerminalLineType.OUTPUT) }

    private fun displayLines(text: String): List<String> =
        AnsiStripper.strip(text)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    private fun line(text: String, type: TerminalLineType): TerminalLine =
        TerminalLine(text.take(MAX_LINE_CHARS), type)

    private fun fileName(path: String?): String =
        path.orEmpty().trimEnd('/').substringAfterLast('/')
}
