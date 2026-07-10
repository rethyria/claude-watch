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

// CLAUDE_WATCH_CLAUDE_BIN / CLAUDE_WATCH_CODEX_BIN are test-only overrides:
// they point the bridge at stub agent binaries so the suite can exercise
// spawn/injection paths deterministically. Production deployments must never
// set them; unset falls back to normal discovery.
export const CLAUDE_BIN = process.env.CLAUDE_WATCH_CLAUDE_BIN || findBinary("claude", [
  `${os.homedir()}/.local/bin/claude`,
  "/usr/local/bin/claude",
  "/opt/homebrew/bin/claude",
]);

export const CODEX_BIN = process.env.CLAUDE_WATCH_CODEX_BIN || findBinary("codex", [
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

// Protocol version of the /v1 surface (see PROTOCOL.md for the versioning
// rules). Advertised over Bonjour (txt.v, plus the legacy txt.version key),
// returned by the unauthenticated GET /ping discovery endpoint (proto), and
// echoed in the /v1/pair success response (proto). A JSON number everywhere;
// TXT values are strings by mDNS nature.
export const PROTOCOL_VERSION = 3;

// Minimum client protocol version accepted at POST /v1/pair. A /v1 pair
// request whose `proto` is missing or below this is refused with 426 before
// the pairing code is even considered — an outdated app must fail with a
// clear "update the app" error, never an undetectable wire mismatch. The
// legacy /pair surface is frozen and performs no version check.
export const MIN_SUPPORTED_CLIENT_PROTO = 3;

// The Bonjour TXT record advertised on _claude-watch._tcp. `v` is the
// canonical protocol-version key (PROTOCOL.md); `version` and `sessionId`
// are frozen legacy aliases. Built here (next to the constants it exposes)
// so unit tests can assert the advertised record without spawning mDNS.
export function bonjourTxtRecord() {
  return {
    v: String(PROTOCOL_VERSION),
    version: String(PROTOCOL_VERSION), // backward compat
    bridgeId: BRIDGE_ID,
    sessionId: BRIDGE_ID, // backward compat
    machineName: os.hostname(),
  };
}

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

// Port file: the bridge publishes its ACTUAL bound port here on startup
// (single source of truth). The bridge walks PORT_RANGE_START..END when the
// default is taken (7860 is Gradio's default, notably), so the hook installer
// (setup-hooks.sh) and the codex-watch wrapper must read this file instead of
// assuming 7860 — otherwise every hook, including the blocking permission
// hook, posts to the wrong instance.
export const PORT_FILE = path.join(CREDENTIALS_DIR, "port");

// Test-only override hook (see testOverridable below): lets the test suite
// move the bridge into a private port range (so a decoy listener on the range
// start can't collide with parallel test files) and widen it (many concurrent
// test-runner processes exhaust the ten production ports). Production never
// sets these.
export const PORT_RANGE_START = testOverridable("CLAUDE_WATCH_PORT_RANGE_START", 7860);
export const PORT_RANGE_END = testOverridable("CLAUDE_WATCH_PORT_RANGE_END", 7869);
// Overridable via CLAUDE_WATCH_PAIRING_CODE_TTL_MS (test-only) so code-expiry
// paths can be exercised in milliseconds instead of minutes.
export const PAIRING_CODE_TTL_MS = testOverridable(
  "CLAUDE_WATCH_PAIRING_CODE_TTL_MS",
  5 * 60 * 1000,
);
export const RATE_LIMIT_WINDOW_MS = 5 * 60 * 1000;
export const RATE_LIMIT_MAX_ATTEMPTS = 5;
export const SSE_HEARTBEAT_INTERVAL_MS = 10_000;

// Test-only override hook: lets the test suite shorten long production
// timeouts/bounds (and relocate/widen the port range) via environment
// variables so timeout/pruning/eviction/port-walk paths can be exercised
// deterministically. Production deployments must never set these variables;
// any unset/invalid value falls back to the production default. (Function
// declaration — hoisted above the PORT_RANGE_* uses earlier in this module.)
function testOverridable(envName, productionValue) {
  const raw = process.env[envName];
  if (raw === undefined) return productionValue;
  const parsed = parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : productionValue;
}

// Overridable via CLAUDE_WATCH_SSE_BUFFER_SIZE (test-only): lets eviction
// tests roll the ring buffer over without pushing 500+ events.
export const SSE_BUFFER_SIZE = testOverridable("CLAUDE_WATCH_SSE_BUFFER_SIZE", 500);

// How many recent terminal events (pty-output / tool-output) a freshly
// connected SSE client receives as part of the connect-time snapshot, so a
// new pair (no Last-Event-ID to replay from) is not blind to what the agent
// was just doing.
export const SSE_SYNC_TERMINAL_BACKLOG = 50;

// Client-side timeout of the blocking PermissionRequest hook, as installed by
// setup-hooks.sh (its PERMISSION_HOOK_TIMEOUT_S, in seconds — a test asserts
// the two stay equal). Claude Code aborts the hook request after this window.
// Overridable via CLAUDE_WATCH_HOOK_PERMISSION_TIMEOUT_MS (test-only).
export const HOOK_PERMISSION_TIMEOUT_MS = testOverridable(
  "CLAUDE_WATCH_HOOK_PERMISSION_TIMEOUT_MS",
  600_000, // 10 minutes — keep in sync with setup-hooks.sh
);

// Bridge-side auto-deny for unanswered permissions. This MUST fire before the
// hook's client-side timeout above: when both were exactly 600 s, expiry
// raced nondeterministically — sometimes the hook aborted first and the
// bridge's deny went into a dead socket. The margin keeps the bridge
// deterministically first while degrading gracefully when tests shorten the
// hook window below the production margin.
// Overridable via CLAUDE_WATCH_PERMISSION_TIMEOUT_MS (test-only).
export const PERMISSION_TIMEOUT_MS = testOverridable(
  "CLAUDE_WATCH_PERMISSION_TIMEOUT_MS",
  Math.max(HOOK_PERMISSION_TIMEOUT_MS - 30_000, Math.ceil(HOOK_PERMISSION_TIMEOUT_MS / 2)),
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
export const SESSION_PRUNE_GRACE_MS = testOverridable(
  "CLAUDE_WATCH_SESSION_PRUNE_GRACE_MS",
  5 * 60 * 1000,
);
export const SESSION_PRUNE_INTERVAL_MS = testOverridable(
  "CLAUDE_WATCH_SESSION_PRUNE_INTERVAL_MS",
  60_000,
);
// Hard cap on hook-created (external) session slots. /hooks/* is
// unauthenticated, so without a bound every unique session_id (or cwd) in a
// hook payload would mint a permanent "running" slot — unbounded memory, SSE
// broadcasts, and /status snapshot growth. Beyond the cap the oldest
// hook-created slot is evicted (ended ones first).
export const MAX_EXTERNAL_SESSIONS = 32;

// Auto-spawned command injection waits for the new PTY's first output (the
// ready signal) before writing the command, bounded by this timeout. A blind
// timer used to fire the write at 500 ms whether or not the agent was up,
// silently dropping the command when it wasn't. Overridable via
// CLAUDE_WATCH_SPAWN_INJECT_TIMEOUT_MS (test-only).
export const SPAWN_INJECT_TIMEOUT_MS = testOverridable(
  "CLAUDE_WATCH_SPAWN_INJECT_TIMEOUT_MS",
  15_000,
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
