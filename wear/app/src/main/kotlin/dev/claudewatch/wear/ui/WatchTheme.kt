// Design tokens carried over from the watchOS app (ClaudeWatchWatchApp.swift):
// Claude orange / success green / error red on pure black (OLED).
package dev.claudewatch.wear.ui

import androidx.compose.ui.graphics.Color
import dev.claudewatch.shared.terminal.TerminalLine
import dev.claudewatch.shared.terminal.TerminalLineType

object WatchTheme {
    val Background = Color.Black
    val ClaudeOrange = Color(0xFFE87A35)
    val Success = Color(0xFF34C759)
    val Error = Color(0xFFFF3B30)
    val TextSecondary = Color(0xFF9E9E9E)
    val Command = Color.White

    /** Terminal line color; diff-style `  + ` output renders success-green. */
    fun colorFor(line: TerminalLine): Color = when (line.type) {
        TerminalLineType.OUTPUT ->
            if (line.text.startsWith("+ ") || line.text.startsWith("  + ")) Success else ClaudeOrange
        TerminalLineType.COMMAND -> Command
        TerminalLineType.SYSTEM -> TextSecondary
        TerminalLineType.ERROR -> Error
    }
}
