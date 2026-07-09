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

// Protocol version advertised over Bonjour (txt.version) and returned by the
// unauthenticated GET /ping discovery endpoint.
export const PROTOCOL_VERSION = "2";

// Extra Host-header allow-list entries beyond the built-ins (localhost,
// loopback, this machine's interface addresses, and 10.0.2.2 — the Android
// emulator's alias for its host). Extensible two ways:
//   env:  CLAUDE_WATCH_ALLOWED_HOSTS="bridge.lan,192.168.7.7" (comma-separated)
//   flag: --allow-host=bridge.lan (repeatable)
export const EXTRA_ALLOWED_HOSTS = [
  ...(process.env.CLAUDE_WATCH_ALLOWED_HOSTS || "")
    .split(",")
    .map((h) => h.trim())
    .filter(Boolean),
  ...cliArgs
    .filter((a) => a.startsWith("--allow-host="))
    .map((a) => a.slice("--allow-host=".length))
    .filter(Boolean),
];

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

// Test-only override hook: lets the test suite shorten long production
// timeouts/bounds via environment variables so timeout/pruning paths can be
// exercised in seconds instead of minutes. Production deployments must never
// set these variables; any unset/invalid value falls back to the production
// default.
function testOverridableMs(envName, productionValue) {
  const raw = process.env[envName];
  if (raw === undefined) return productionValue;
  const parsed = parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : productionValue;
}

// Overridable via CLAUDE_WATCH_PERMISSION_TIMEOUT_MS (test-only).
export const PERMISSION_TIMEOUT_MS = testOverridableMs(
  "CLAUDE_WATCH_PERMISSION_TIMEOUT_MS",
  600_000, // 10 minutes
);

// Maximum bytes allowed to queue in a single SSE client's response stream.
// writableLength only grows once the kernel socket buffers are full, so a
// client this far behind is stalled or dead — it gets destroyed and can
// reconnect with Last-Event-ID replay.
export const SSE_MAX_BUFFERED_BYTES = 1024 * 1024; // 1 MiB

// TCP keepalive probe delay for SSE sockets: detects silently-dropped peers
// (network loss, no FIN/RST) instead of waiting ~15 minutes for the OS
// default to notice.
export const SSE_TCP_KEEPALIVE_MS = 30_000;

// Ended sessions stay in the sessions map (and thus in /status and /pair
// snapshots) for this grace period so clients observe the "ended" state,
// then get pruned. Overridable via CLAUDE_WATCH_SESSION_PRUNE_GRACE_MS /
// CLAUDE_WATCH_SESSION_PRUNE_INTERVAL_MS (test-only).
export const SESSION_PRUNE_GRACE_MS = testOverridableMs(
  "CLAUDE_WATCH_SESSION_PRUNE_GRACE_MS",
  5 * 60 * 1000,
);
export const SESSION_PRUNE_INTERVAL_MS = testOverridableMs(
  "CLAUDE_WATCH_SESSION_PRUNE_INTERVAL_MS",
  60_000,
);
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
