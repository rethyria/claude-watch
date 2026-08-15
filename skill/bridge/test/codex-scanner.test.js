// Codex rollout scanning, black-box against REAL rollout files (issue #122).
// The scanner tails ~/.codex/sessions JSONL rollouts; these tests point it at
// a fixture tree (CLAUDE_WATCH_CODEX_SESSION_ROOT) and write the same line
// shapes codex writes — the first coverage this lane has ever had that isn't
// a stub binary writing nothing.
//
// The #122 bug: a watch spawn minted a bridge uuid for the codex PTY slot,
// while the codex process inside it wrote its rollout under codex's OWN
// thread id — which the scanner registered as a SECOND slot within a tick or
// two. Two rows on the wrist for one conversation, and every turn signal
// (task_started/task_complete lives only in the rollout) addressed to the
// twin, so the PTY row's elapsed clock ran forever. The fix links the rollout
// onto the spawned slot: one row, whose idle truth works, and whose kill
// leaves nothing behind.
import { test } from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { startBridge, request, tempDir, connectSse } from "./helpers.js";

// A codex stand-in that announces itself once and then stays QUIET: PTY bytes
// mark the slot working, so a chatty stub would fight the task_complete this
// suite is asserting on — exactly like the real TUI, which is silent while it
// waits for input.
function makeFakeCodex(t) {
  const dir = tempDir(t, "claude-watch-codex-bin-");
  const bin = path.join(dir, "codex");
  fs.writeFileSync(bin, "#!/bin/sh\necho CODEX-STUB-READY\nexec sleep 600\n", { mode: 0o755 });
  return bin;
}

// Fixture tree + a bridge scanning it on a fast tick. The log file exists
// (empty) from boot so the log scan is live for the shutdown-line test.
async function codexBridge(t) {
  const root = tempDir(t, "claude-watch-codex-root-");
  const sessionsRoot = path.join(root, "sessions");
  const logFile = path.join(root, "log", "codex-tui.log");
  fs.mkdirSync(sessionsRoot, { recursive: true });
  fs.mkdirSync(path.dirname(logFile), { recursive: true });
  fs.writeFileSync(logFile, "");
  const bridge = await startBridge(t, {
    env: {
      CLAUDE_WATCH_CODEX_BIN: makeFakeCodex(t),
      CLAUDE_WATCH_CODEX_SESSION_ROOT: sessionsRoot,
      CLAUDE_WATCH_CODEX_LOG_FILE: logFile,
      CLAUDE_WATCH_CODEX_SCAN_INTERVAL_MS: "200",
    },
  });
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  return { bridge, token: pair.body.token, sessionsRoot, logFile };
}

// One rollout file in codex's on-disk shape: the session_meta header line
// first, event lines appended behind it.
function writeRolloutMeta(sessionsRoot, threadId, cwd, timestamp = new Date().toISOString()) {
  const day = path.join(sessionsRoot, "2026", "08", "15");
  fs.mkdirSync(day, { recursive: true });
  const file = path.join(day, `rollout-${timestamp.replaceAll(":", "-")}-${threadId}.jsonl`);
  fs.writeFileSync(file, `${JSON.stringify({ type: "session_meta", timestamp, payload: { id: threadId, timestamp, cwd } })}\n`);
  return file;
}

function appendRollout(file, record) {
  fs.appendFileSync(file, `${JSON.stringify(record)}\n`);
}

const taskStarted = { type: "event_msg", payload: { type: "task_started" } };
const taskComplete = { type: "event_msg", payload: { type: "task_complete" } };

async function sessionSnapshot(bridge, token) {
  const status = await request(bridge.port, "GET", "/v1/status", { token });
  assert.equal(status.status, 200);
  return status.body.sessions;
}

async function waitForSnapshot(bridge, token, predicate, what, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const snapshot = await sessionSnapshot(bridge, token);
    const found = predicate(snapshot);
    if (found) return found;
    if (Date.now() > deadline) {
      throw new Error(`${what} not observed within ${timeoutMs}ms; snapshot: ${JSON.stringify(snapshot)}`);
    }
    await new Promise((r) => setTimeout(r, 100).unref());
  }
}

test("a watch-spawned codex session and its rollout are ONE slot, whose idle truth works (#122)", { timeout: 60_000 }, async (t) => {
  const { bridge, token, sessionsRoot } = await codexBridge(t);
  const projectDir = tempDir(t, "claude-watch-codex-project-");
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  const spawn = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "codex", cwd: projectDir },
  });
  assert.equal(spawn.status, 200);
  const spawnedId = spawn.body.sessionId;

  // The codex process inside the PTY writes its rollout under its OWN thread
  // id, in the spawn's cwd, moments after starting.
  const threadId = crypto.randomUUID();
  const rollout = writeRolloutMeta(sessionsRoot, threadId, projectDir);

  // The scanner must adopt the rollout into the spawned slot, not mint a twin.
  await bridge.waitForOutput(new RegExp(`Linked Codex rollout ${threadId} to watch-spawned session ${spawnedId}`));
  const linked = await sessionSnapshot(bridge, token);
  assert.ok(!linked.some((s) => s.id === threadId), "the rollout must not register a second session for the same conversation");
  assert.equal(linked.filter((s) => s.agent === "codex").length, 1, "one spawn is one row");
  const slot = linked.find((s) => s.id === spawnedId);
  assert.ok(slot, "the spawned slot is the surviving row");
  assert.equal(slot.dictatable, true, "the linked slot keeps its PTY input channel");

  // The rollout's turn signals now drive the SPAWNED slot: task_complete is
  // the only turn-end a codex session ever emits, and before the link it was
  // addressed to the twin — the PTY row could never idle.
  appendRollout(rollout, taskStarted);
  appendRollout(rollout, taskComplete);
  await sse.waitFor((e) => e.event === "task-complete" && e.parsed?.sessionId === spawnedId);
  await waitForSnapshot(
    bridge, token,
    (snapshot) => snapshot.find((s) => s.id === spawnedId && s.idle === true),
    "the spawned slot going idle on task_complete",
  );
});

test("an external rollout registers under its own thread id and mirrors its events", { timeout: 60_000 }, async (t) => {
  const { bridge, token, sessionsRoot, logFile } = await codexBridge(t);
  const externalDir = tempDir(t, "claude-watch-codex-external-");
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  // No PTY slot exists for this directory: the rollout is a codex the user
  // started elsewhere, and must appear as an external scanner session.
  const threadId = crypto.randomUUID();
  const rollout = writeRolloutMeta(sessionsRoot, threadId, externalDir);
  const slot = await waitForSnapshot(
    bridge, token,
    (snapshot) => snapshot.find((s) => s.id === threadId),
    "the external rollout's session",
  );
  assert.equal(slot.agent, "codex");
  assert.equal(slot.cwd, externalDir);

  // Its event stream fans out addressed to the thread id...
  appendRollout(rollout, taskStarted);
  appendRollout(rollout, { type: "event_msg", payload: { type: "agent_message", message: "external says hi" } });
  const message = await sse.waitFor((e) => e.event === "tool-output" && e.parsed?.sessionId === threadId);
  assert.equal(message.parsed.tool_name, "CodexMessage");
  assert.equal(message.parsed.tool_output, "external says hi");

  // ...and the TUI log's shutdown line ends it, observably (the ended state
  // stays visible through the prune grace — the #127 endedAt stamp; the
  // window itself is pinned in codex-state-unit.test.js).
  fs.appendFileSync(logFile, `2026-08-15T12:00:00.000Z INFO codex_core::codex: Shutting down Codex instance thread_id=${threadId}\n`);
  const ended = await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === threadId && e.parsed?.state === "ended");
  assert.equal(ended.parsed.reason, "codex-shutdown");
  await waitForSnapshot(
    bridge, token,
    (snapshot) => snapshot.find((s) => s.id === threadId && s.state === "ended"),
    "the ended session still observable in the snapshot",
  );
});

test("killing the linked slot kills the conversation — trailing rollout writes revive nothing (#122)", { timeout: 60_000 }, async (t) => {
  const { bridge, token, sessionsRoot } = await codexBridge(t);
  const projectDir = tempDir(t, "claude-watch-codex-kill-");
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  const spawn = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "codex", cwd: projectDir },
  });
  assert.equal(spawn.status, 200);
  const spawnedId = spawn.body.sessionId;
  const threadId = crypto.randomUUID();
  const rollout = writeRolloutMeta(sessionsRoot, threadId, projectDir);
  await bridge.waitForOutput(new RegExp(`Linked Codex rollout ${threadId}`));

  const kill = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { kill: true, sessionId: spawnedId },
  });
  assert.equal(kill.status, 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === spawnedId && e.parsed?.state === "ended");

  // The dying process's rollout flush lands on the scanner a tick later.
  // Before the link existed this is where the ghost lived: the twin row
  // stayed (or sprang back) running until a shutdown log line or 12 h of
  // ageing. An authoritatively-dead slot must swallow its trailing writes.
  appendRollout(rollout, taskStarted);
  appendRollout(rollout, { type: "event_msg", payload: { type: "agent_message", message: "death rattle" } });
  await new Promise((r) => setTimeout(r, 1200).unref());
  const snapshot = await sessionSnapshot(bridge, token);
  assert.ok(!snapshot.some((s) => s.id === threadId), "no twin row appears for the killed conversation");
  const slot = snapshot.find((s) => s.id === spawnedId);
  assert.ok(!slot || slot.state === "ended", `the killed slot stays dead; got ${JSON.stringify(slot)}`);
});
