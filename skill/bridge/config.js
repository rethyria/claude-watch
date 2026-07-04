// Configuration constants and agent binary discovery.
// Evaluated once at startup; the binary-discovery log lines below are part of
// the frozen startup output.
import crypto from "node:crypto";
import os from "node:os";
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { log } from "./util.js";

// ---------------------------------------------------------------------------
// Binary discovery
// ---------------------------------------------------------------------------

function findBinary(name, candidates) {
  for (const c of candidates) {
    try { fs.accessSync(c, fs.constants.X_OK); return c; } catch { /* continue */ }
  }
  try {
    return execSync(`which ${name} 2>/dev/null`, { encoding: "utf-8" }).trim();
  } catch { /* fall through */ }
  return null;
}

export const CLAUDE_BIN = findBinary("claude", [
  `${os.homedir()}/.local/bin/claude`,
  "/usr/local/bin/claude",
  "/opt/homebrew/bin/claude",
]);

export const CODEX_BIN = findBinary("codex", [
  `${os.homedir()}/.local/bin/codex`,
  "/usr/local/bin/codex",
  "/opt/homebrew/bin/codex",
]);

if (!CLAUDE_BIN) {
  log("warn", "Could not find 'claude' binary — Claude sessions will not be available.");
}
if (CODEX_BIN) {
  log("info", `Codex binary found: ${CODEX_BIN}`);
} else {
  log("info", "Codex not found — Codex sessions will not be available.");
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

// CLI arguments. The bridge historically takes one optional positional
// argument (the default working directory for spawned sessions); flags are
// filtered out so `node server.js --allow-pairing` doesn't turn the flag into
// a cwd.
const cliArgs = process.argv.slice(2);
export const ALLOW_PAIRING_FLAG = cliArgs.includes("--allow-pairing");
export const CLI_CWD = cliArgs.find((a) => !a.startsWith("--")) || null;

// Credential persistence. Overridable via env var so tests (and multi-bridge
// setups) never touch the real ~/.claude-watch.
export const CREDENTIALS_DIR =
  process.env.CLAUDE_WATCH_CREDENTIALS_DIR || path.join(os.homedir(), ".claude-watch");
export const CREDENTIALS_FILE = path.join(CREDENTIALS_DIR, "credentials.json");

export const PORT_RANGE_START = 7860;
export const PORT_RANGE_END = 7869;
export const PAIRING_CODE_TTL_MS = 5 * 60 * 1000;
export const RATE_LIMIT_WINDOW_MS = 5 * 60 * 1000;
export const RATE_LIMIT_MAX_ATTEMPTS = 5;
export const SSE_HEARTBEAT_INTERVAL_MS = 10_000;
export const SSE_BUFFER_SIZE = 500;
export const PERMISSION_TIMEOUT_MS = 600_000; // 10 minutes
export const CODEX_SESSION_SCAN_INTERVAL_MS = 1_500;
export const CODEX_SESSION_BOOTSTRAP_LOOKBACK_MS = 30 * 60 * 1000;
export const CODEX_SESSION_SCAN_LIMIT = 25;
export const CODEX_SESSION_ROOT = path.join(os.homedir(), ".codex", "sessions");
export const CODEX_LOG_FILE = path.join(os.homedir(), ".codex", "log", "codex-tui.log");
export const BRIDGE_ID = crypto.randomUUID();

export function availableAgentsList() {
  const agents = [];
  if (CLAUDE_BIN) agents.push("claude");
  if (CODEX_BIN) agents.push("codex");
  return agents;
}
