// ANSI stripping for PTY output shown on the watch. The watchOS app used the
// naive `\x1B\[[0-9;]*[a-zA-Z]` regex, which leaves OSC title sequences,
// intermediate-byte CSI forms (e.g. `ESC[?25l`), and bare two-byte escapes on
// screen as garbage. This covers the escape grammar the agents actually emit.
package dev.claudewatch.shared.terminal

object AnsiStripper {

    // Alternation order matters: CSI and OSC are matched before the generic
    // two-byte escape (whose final-byte range `[@-_]` would otherwise eat the
    // '[' / ']' introducer and strand the sequence body).
    //  - CSI:  ESC '[' parameter bytes (0x30-0x3F) + intermediates (0x20-0x2F)
    //          + one final byte in 0x40-0x7E
    //  - OSC:  ESC ']' body terminated by BEL or ST (ESC \); an unterminated
    //          body (chunk split) is dropped rather than rendered raw
    //  - two-byte escapes: ESC followed by one byte in 0x40-0x5F
    private val ANSI = Regex(
        "\\u001B(?:" +
            "\\[[\\u0030-\\u003F]*[\\u0020-\\u002F]*[\\u0040-\\u007E]" +
            "|\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)?" +
            "|[\\u0040-\\u005F]" +
            ")",
    )

    // Everything C0 except \n and \t (notably \r and \b) is dropped after
    // escape removal; \r\n line endings are normalized first.
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B-\\u001F\\u007F]")

    /** Remove ANSI escape sequences and non-printing control characters. */
    fun strip(text: String): String =
        CONTROL.replace(ANSI.replace(text.replace("\r\n", "\n"), ""), "")
}
