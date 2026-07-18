// Issue #55: the workflow-activity indicator. The Workflow tool returns
// immediately (it runs in the background), so the PostToolUse hook is the
// ONLY launch signal; completion is discovered by a slow poll over the
// session's workflow journals:
//   <transcript minus .jsonl>/subagents/workflows/wf_*/journal.jsonl
// running = `started` records without a matching `result` (matched on `key`),
// done = matched ones in LIVE (non-stale) journals. The completion state is
// the EXPLICIT {running: 0, done: N} broadcast — absence never clears — after
// which the poll goes quiet for the slot until a fresh Workflow hook re-arms
// it. Stale journals (a killed workflow never writes its results) count as
// dead so the indicator cannot stick.
//
// Env overrides must be set before any bridge module loads (config.js reads
// them once at evaluation), hence the dynamic imports inside the tests. The
// module-level poll interval is pushed out to an hour so tests drive
// pollWorkflowActivity(now) deterministically instead of racing a timer.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { startBridge, request, connectSse } from "./helpers.js";

const credsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-wf-creds-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = credsDir;
process.env.CLAUDE_WATCH_WORKFLOW_POLL_MS = "3600000";
const STALE_MS = 60_000;
process.env.CLAUDE_WATCH_WORKFLOW_STALE_MS = String(STALE_MS);

const fixturesRoot = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-wf-fixtures-"));
after(() => {
  for (const dir of [credsDir, fixturesRoot]) {
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* ignore */ }
  }
});

function lastSessionEvent(sseBuffer, sessionId) {
  for (let i = sseBuffer.length - 1; i >= 0; i--) {
    const entry = sseBuffer[i];
    if (entry.event !== "session") continue;
    const parsed = JSON.parse(entry.data);
    if (parsed.sessionId === sessionId) return parsed;
  }
  return null;
}

// Build a session's transcript + workflow journal tree:
//   <fixturesRoot>/<name>/<sid>.jsonl                       (the transcript)
//   <fixturesRoot>/<name>/<sid>/subagents/workflows/wf_a/journal.jsonl
// Returns { cwd, transcriptPath, journalPath }.
function makeWorkflowTree(name, sid, records) {
  const dir = path.join(fixturesRoot, name);
  const wfDir = path.join(dir, sid, "subagents", "workflows", "wf_a");
  fs.mkdirSync(wfDir, { recursive: true });
  const transcriptPath = path.join(dir, `${sid}.jsonl`);
  fs.writeFileSync(transcriptPath, "");
  const journalPath = path.join(wfDir, "journal.jsonl");
  fs.writeFileSync(journalPath, records.map((r) => JSON.stringify(r)).join("\n") + "\n");
  return { cwd: dir, transcriptPath, journalPath };
}

const started = (key) => ({ type: "started", key, agentId: `agent-${key}` });
const result = (key) => ({ type: "result", key, value: "ok" });

test("the launch signal scans immediately: 3 started / 1 result → {running: 2, done: 1} on payload + snapshot", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, getSessionsSnapshot } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { cwd, transcriptPath } = makeWorkflowTree("counts", "cc-wf-counts",
    [started("k1"), started("k2"), started("k3"), result("k1")]);
  const id = resolveHookSession({ session_id: "cc-wf-counts", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    assert.deepEqual(sessions.get(id).agents, { running: 2, done: 1 });
    const event = lastSessionEvent(sseBuffer, id);
    assert.deepEqual(event.agents, { running: 2, done: 1 });
    assert.deepEqual(getSessionsSnapshot().find((s) => s.id === id).agents, { running: 2, done: 1 });
  } finally {
    sessions.delete(id);
  }
});

test("completion is the explicit {running: 0} broadcast, after which the poll is quiet until re-armed", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("complete", "cc-wf-complete",
    [started("k1"), started("k2"), result("k1")]);
  const id = resolveHookSession({ session_id: "cc-wf-complete", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    assert.deepEqual(sessions.get(id).agents, { running: 1, done: 1 });

    // The last agent finishes: the poll must broadcast the EXPLICIT zero.
    fs.appendFileSync(journalPath, JSON.stringify(result("k2")) + "\n");
    const before = sseBuffer.length;
    pollWorkflowActivity(Date.now());
    assert.deepEqual(sessions.get(id).agents, { running: 0, done: 2 });
    const cleared = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(cleared, "the completion state was broadcast");
    assert.deepEqual(cleared.agents, { running: 0, done: 2 });

    // Quiet after completion: new journal activity is NOT observed by the
    // poll (only a fresh Workflow hook re-arms the scan).
    fs.appendFileSync(journalPath, JSON.stringify(started("k9")) + "\n");
    pollWorkflowActivity(Date.now());
    assert.deepEqual(sessions.get(id).agents, { running: 0, done: 2 }, "the poll went quiet for the slot");

    // Re-arm: the launch signal sees the new agent.
    markWorkflowActivity(id);
    assert.deepEqual(sessions.get(id).agents, { running: 1, done: 2 });
  } finally {
    sessions.delete(id);
  }
});

test("a stale journal (killed workflow) counts as dead — the indicator cannot stick", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity } = await import("../sessions.js");

  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("stale", "cc-wf-stale",
    [started("k1")]); // started, never finished — a killed workflow's shape
  const old = new Date(Date.now() - 2 * STALE_MS);
  fs.utimesSync(journalPath, old, old);
  const id = resolveHookSession({ session_id: "cc-wf-stale", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    const slot = sessions.get(id);
    assert.deepEqual(slot.agents, { running: 0, done: 0 }, "a stale journal never yields running agents");
    assert.equal(slot.workflowActive, false, "the poll went quiet immediately");
  } finally {
    sessions.delete(id);
  }
});

test("a launch signal with no observable journal tree stays armed, then gives up after the stale window", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // A transcript path whose journal tree never materializes.
  const dir = path.join(fixturesRoot, "never");
  fs.mkdirSync(dir, { recursive: true });
  const transcriptPath = path.join(dir, "cc-wf-never.jsonl");
  fs.writeFileSync(transcriptPath, "");
  const id = resolveHookSession({ session_id: "cc-wf-never", cwd: dir, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    const slot = sessions.get(id);
    assert.equal(slot.agents, undefined, "nothing observable → no agents field, not a phantom zero");
    assert.equal(slot.workflowActive, true, "stays armed for the journals to appear");

    // Past the stale window with still nothing: give up quietly.
    pollWorkflowActivity(Date.now() + STALE_MS + 1_000);
    assert.equal(slot.workflowActive, false, "gave up after the stale window");
    assert.equal(slot.agents, undefined, "never broadcast a phantom agents state");
    const event = lastSessionEvent(sseBuffer, id);
    assert.ok(!Object.hasOwn(event, "agents"), "payloads never grew an agents field");
  } finally {
    sessions.delete(id);
  }
});

// Black-box wiring: a real bridge process, a real SSE client, and the actual
// /hooks/tool-output surface — proves hooks.js routes a Workflow PostToolUse
// into markWorkflowActivity and the indicator reaches the wire. (The
// in-process tests above cover the scan/poll semantics; this covers the glue.)
test("a Workflow tool-output hook arms the scan and the session event carries agents on the wire", async (t) => {
  const { cwd, transcriptPath } = makeWorkflowTree("wire", "cc-wf-wire",
    [started("k1"), started("k2"), result("k1")]);
  const bridge = await startBridge(t, {
    env: {
      // The parent process env pushed the poll out to an hour (for the
      // in-process tests); the child bridge inherits process.env, so pin its
      // own values explicitly. The launch signal scans immediately, so the
      // poll interval is irrelevant here — only the stale window matters.
      CLAUDE_WATCH_WORKFLOW_POLL_MS: "3600000",
      CLAUDE_WATCH_WORKFLOW_STALE_MS: String(STALE_MS),
    },
  });
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  const sse = connectSse(bridge.port, pair.body.token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  const posted = await request(bridge.port, "POST", "/hooks/tool-output", {
    body: { session_id: "cc-wf-wire", cwd, transcript_path: transcriptPath, tool_name: "Workflow", tool_output: "launched" },
  });
  assert.equal(posted.status, 200);

  const event = await sse.waitFor((e) => e.event === "session" && e.parsed?.agents?.running === 1);
  assert.deepEqual(event.parsed.agents, { running: 1, done: 1 });
});
