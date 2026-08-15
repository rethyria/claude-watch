// endExternalSession must stamp endedAt (#127, bridge-state-2), in-process.
//
// Every other ender stamps it; this one only flipped state. pruneEndedSessions
// falls back to createdAt for unstamped slots — and a Codex scanner slot's
// createdAt is the rollout FILE's birth, routinely hours old — so any codex
// session older than the grace window was pruned on the first tick after
// ending instead of staying observable through it. The clock is injected so
// the test exercises the PRODUCTION grace window.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Isolate every homedir-derived path BEFORE any bridge module loads: never
// let the monitor near the real ~/.codex, nor credentials near ~/.claude-watch.
const fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-codex-state-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = path.join(fixtureRoot, "creds");
process.env.CLAUDE_WATCH_CODEX_SESSION_ROOT = path.join(fixtureRoot, "sessions");
process.env.CLAUDE_WATCH_CODEX_LOG_FILE = path.join(fixtureRoot, "log", "codex-tui.log");
after(() => {
  try { fs.rmSync(fixtureRoot, { recursive: true, force: true }); } catch { /* ignore */ }
});

const MINUTE = 60 * 1000;

test("a codex session ended by the log scan waits out the full prune grace (#127)", async () => {
  const { sessions, markSessionObserved, pruneEndedSessions } = await import("../sessions.js");
  const { SESSION_PRUNE_GRACE_MS } = await import("../config.js");
  const { startCodexMonitor, stopCodexMonitor } = await import("../codex.js");
  after(() => stopCodexMonitor());

  // A scanner-shaped slot whose createdAt — the rollout file's timestamp —
  // already predates the grace window, which is the normal case for any
  // conversation older than a few minutes.
  const threadId = crypto.randomUUID();
  const slot = {
    id: threadId,
    agent: "codex",
    cwd: path.join(fixtureRoot, "project"),
    folderName: "project",
    ptyProcess: null,
    state: "running",
    createdAt: Date.now() - SESSION_PRUNE_GRACE_MS - 5 * MINUTE,
    idle: false,
  };
  markSessionObserved(slot);
  sessions.set(threadId, slot);

  // The boot scan's log bootstrap consumes the shutdown line synchronously.
  fs.mkdirSync(path.dirname(process.env.CLAUDE_WATCH_CODEX_LOG_FILE), { recursive: true });
  fs.writeFileSync(
    process.env.CLAUDE_WATCH_CODEX_LOG_FILE,
    `2026-08-15T12:00:00.000Z INFO codex_core::codex: Shutting down Codex instance thread_id=${threadId}\n`,
  );
  startCodexMonitor();

  assert.equal(slot.state, "ended", "the shutdown line ends the session");
  assert.equal(typeof slot.endedAt, "number", "the ending is stamped, like every other ender's");

  // Freshly ended: the next prune tick (≤60 s later in production) must keep
  // it observable — before the stamp it fell back to createdAt and vanished
  // 1 ms after ending.
  pruneEndedSessions(Date.now() + MINUTE);
  assert.ok(sessions.has(threadId), "an ended session survives the prune tick inside its grace window");

  // And the grace window still ends: the stamp ages it out normally.
  pruneEndedSessions(slot.endedAt + SESSION_PRUNE_GRACE_MS + MINUTE);
  assert.ok(!sessions.has(threadId), "the grace window still expires from the ending, not from birth");
});
