// The terminal presentation model: what a session's on-watch terminal shows,
// one value per rendered line. Produced by the pure formatters in this
// package and folded into per-session state by BridgeEventReducer — the
// typed replacement for the walking skeleton's raw "$type $data" JSON spam.
package dev.claudewatch.shared.terminal

/** Render class of a terminal line; the UI maps each to a design-token color. */
enum class TerminalLineType {
    /** Something the user (or agent) typed: `$ cmd`, `> dictated text`. */
    COMMAND,

    /** Tool/PTY output text. */
    OUTPUT,

    /**
     * The agent talking, not tool noise (#79) — assistant prose from an ACP
     * session. Rendered as speech: proportional, brightest text role, larger
     * than the dense mono of tool output.
     *
     * A distinct type rather than a marker inside the text, so the renderer
     * chooses styling from the model instead of sniffing a prefix. (The
     * `[codex] ` prefix is a different job — it marks PROVENANCE, and rides
     * COMMAND lines too — so it stays.)
     */
    PROSE,

    /** Meta lines: `Read Foo.kt`, `— stopped —`, session lifecycle notes. */
    SYSTEM,

    /** Bridge/agent errors. */
    ERROR,
}

/** One rendered terminal line. */
data class TerminalLine(val text: String, val type: TerminalLineType)
