// The codex monitor must DEGRADE, never die (issue #126).
//
// startCodexMonitor() ran its boot scan bare (only the interval body was
// guarded), so an fs race during startup — a rollout deleted/rotated between
// the directory listing and the open, or simply an unreadable file — threw
// out of async startServer() into the .catch that exits the bridge with
// code 1. Zed starting while codex was actively writing killed the bridge at
// boot, the watch showed offline, and nothing respawned it until the next
// Zed restart. These tests use an unreadable (chmod 000) file for the same
// openSync failure the race produces, deterministically.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { startBridge, request, tempDir } from "./helpers.js";

function makeCodexTree(t) {
  const root = tempDir(t, "claude-watch-codex-resilience-");
  const sessionsRoot = path.join(root, "sessions");
  const logFile = path.join(root, "log", "codex-tui.log");
  fs.mkdirSync(sessionsRoot, { recursive: true });
  fs.mkdirSync(path.dirname(logFile), { recursive: true });
  return { sessionsRoot, logFile };
}

function codexEnv({ sessionsRoot, logFile }) {
  return {
    CLAUDE_WATCH_CODEX_SESSION_ROOT: sessionsRoot,
    CLAUDE_WATCH_CODEX_LOG_FILE: logFile,
    CLAUDE_WATCH_CODEX_SCAN_INTERVAL_MS: "200",
  };
}

// An unreadable rollout: listed by the scan (stat needs no read permission),
// thrown on by the open — the persistent stand-in for the boot-time race.
function writeUnreadableRollout(sessionsRoot, name = "bad.jsonl") {
  const file = path.join(sessionsRoot, name);
  fs.writeFileSync(file, "unreadable\n");
  fs.chmodSync(file, 0o000);
  return file;
}

test("boot survives an unopenable rollout file: degraded scan, never exit 1 (#126)", { timeout: 60_000 }, async (t) => {
  const tree = makeCodexTree(t);
  fs.writeFileSync(tree.logFile, "");
  writeUnreadableRollout(tree.sessionsRoot);

  // Before the fix this rejects "bridge exited early (code 1)" — the boot
  // scan's throw walked out of startServer into the process.exit(1) catch.
  const bridge = await startBridge(t, { env: codexEnv(tree) });
  const ping = await request(bridge.port, "GET", "/ping");
  assert.equal(ping.status, 200, "the bridge serves despite the broken rollout");
  await bridge.waitForOutput(/scan failed/);
});

test("boot survives an unopenable codex log: degraded mirroring, never exit 1 (#126)", { timeout: 60_000 }, async (t) => {
  const tree = makeCodexTree(t);
  fs.writeFileSync(tree.logFile, "some log content the bootstrap will try to read\n");
  fs.chmodSync(tree.logFile, 0o000);

  const bridge = await startBridge(t, { env: codexEnv(tree) });
  const ping = await request(bridge.port, "GET", "/ping");
  assert.equal(ping.status, 200, "the bridge serves despite the unreadable log");
  await bridge.waitForOutput(/Codex log scan failed/);
});
