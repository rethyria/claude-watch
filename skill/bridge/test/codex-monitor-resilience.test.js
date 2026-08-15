// The codex monitor must DEGRADE, never die (issue #126) — and one broken
// rollout file must not starve the rest of the scan (#127).
//
// #126: startCodexMonitor() ran its boot scan bare (only the interval body
// was guarded), so an fs race during startup — a rollout deleted/rotated
// between the directory listing and the open, or simply an unreadable file —
// threw out of async startServer() into the .catch that exits the bridge
// with code 1. Zed starting while codex was actively writing killed the
// bridge at boot, the watch showed offline, and nothing respawned it until
// the next Zed restart. These tests use an unreadable (chmod 000) file for
// the same openSync failure the race produces, deterministically.
//
// #127 (codex-scan-starves-log-2): the tick ran both scans in ONE try/catch
// with no per-file guard, so a persistently-unopenable rollout threw every
// tick — permanently skipping every file after it AND the log scan, the only
// path that surfaces exec approvals and detects Codex shutdowns.
import { test } from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
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
  const bad = writeUnreadableRollout(tree.sessionsRoot);

  // Before the fix this rejects "bridge exited early (code 1)" — the boot
  // scan's throw walked out of startServer into the process.exit(1) catch.
  const bridge = await startBridge(t, { env: codexEnv(tree) });
  const ping = await request(bridge.port, "GET", "/ping");
  assert.equal(ping.status, 200, "the bridge serves despite the broken rollout");
  await bridge.waitForOutput(/Codex session file scan failed for .*bad\.jsonl/);

  // Logged once, not once per 200 ms tick: give it a few ticks and count.
  await new Promise((r) => setTimeout(r, 1000).unref());
  const warns = bridge.output().match(/Codex session file scan failed/g);
  assert.equal(warns.length, 1, "a persistently-broken file warns once, not per tick");
  void bad;
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

test("one throwing rollout starves neither the other files nor the log scan (#127)", { timeout: 60_000 }, async (t) => {
  const tree = makeCodexTree(t);
  fs.writeFileSync(tree.logFile, "");
  const bridge = await startBridge(t, { env: codexEnv(tree) });
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  // The bad file lands first and must already be poisoning ticks before the
  // good one appears...
  writeUnreadableRollout(tree.sessionsRoot);
  await bridge.waitForOutput(/scan failed/);

  // ...and it stays NEWER by mtime, so every tick visits it before the good
  // file — the exact ordering that used to abort the whole scan.
  const threadId = crypto.randomUUID();
  const timestamp = new Date().toISOString();
  const good = path.join(tree.sessionsRoot, `rollout-${threadId}.jsonl`);
  const goodDir = tempDir(t, "claude-watch-codex-good-cwd-");
  fs.writeFileSync(good, `${JSON.stringify({ type: "session_meta", timestamp, payload: { id: threadId, timestamp, cwd: goodDir } })}\n`);
  const older = new Date(Date.now() - 60_000);
  fs.utimesSync(good, older, older);

  const registered = async () => {
    const deadline = Date.now() + 10_000;
    for (;;) {
      const status = await request(bridge.port, "GET", "/v1/status", { token });
      const slot = status.body.sessions.find((s) => s.id === threadId);
      if (slot) return slot;
      if (Date.now() > deadline) throw new Error(`session ${threadId} never registered; sessions: ${JSON.stringify(status.body.sessions)}`);
      await new Promise((r) => setTimeout(r, 100).unref());
    }
  };
  const slot = await registered();
  assert.equal(slot.state, "running", "the good rollout registers despite the poisoned sibling");

  // The log scan — behind the file scan in the tick — must be alive too: a
  // shutdown line for the good session has to end it.
  fs.appendFileSync(tree.logFile, `2026-08-15T12:00:00.000Z INFO codex_core::codex: Shutting down Codex instance thread_id=${threadId}\n`);
  const deadline = Date.now() + 10_000;
  for (;;) {
    const status = await request(bridge.port, "GET", "/v1/status", { token });
    const after = status.body.sessions.find((s) => s.id === threadId);
    if (after?.state === "ended") break;
    if (Date.now() > deadline) throw new Error(`log scan starved: session ${threadId} never ended; got ${JSON.stringify(after)}`);
    await new Promise((r) => setTimeout(r, 100).unref());
  }
});
