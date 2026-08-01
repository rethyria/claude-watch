// Issue #55: the workflow-activity indicator. The Workflow tool returns
// immediately (it runs in the background), so the launch signal is the one
// wire that names the tool — the PostToolUse hook for hook sessions, the teed
// ACP tool_call for ACP sessions (issue #105, the ACP-feed section below);
// completion is discovered by a slow poll over the session's workflow
// journals:
//   <transcript minus .jsonl>/subagents/workflows/wf_*/journal.jsonl
// running = `started` records without a matching `result` (matched on `key`),
// done = matched ones in LIVE (non-stale) journals. The completion state is
// the EXPLICIT {running: 0, done: N} broadcast — absence never clears — after
// which the slot WATCHES its tree (issue #108): the poll keeps re-statting it
// and re-arms on resumed writes or a fresh Workflow hook, and watching ends
// with the slot's life, never with silence.
// The launch signal races the runner's first journal write, so a zero
// only counts as completion once the workflow was actually OBSERVED since
// arming (running agents seen, or a journal written after the signal).
// Stale journals (a killed workflow never writes its results) count as
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
// The ACP feed (issue #105) has no transcript_path on any wire — the bridge
// derives it from the CLI's projects-dir convention, so point that root at
// the fixture tree before any bridge module loads.
const projectsRoot = path.join(fixturesRoot, "projects");
process.env.CLAUDE_WATCH_CLAUDE_PROJECTS_ROOT = projectsRoot;
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

// ACP variant of makeWorkflowTree: the tree is built at the location the
// bridge must DERIVE from cwd + SDK session id, since ACP registration carries
// no transcript path (issue #105):
//   <projectsRoot>/<munged cwd>/<sid>.jsonl
//   <projectsRoot>/<munged cwd>/<sid>/subagents/workflows/wf_a/journal.jsonl
// The CLI's cwd munge is deliberately re-implemented here, so a drift in the
// bridge's convention breaks the test instead of agreeing with itself.
// `records: null` builds only the transcript (a session that never ran a
// workflow — no workflows dir at all).
function makeAcpWorkflowTree(name, sid, records) {
  const cwd = path.join(fixturesRoot, "acp-cwd", name);
  fs.mkdirSync(cwd, { recursive: true });
  const projectDir = path.join(projectsRoot, cwd.replace(/[^a-zA-Z0-9]/g, "-"));
  fs.mkdirSync(projectDir, { recursive: true });
  const transcriptPath = path.join(projectDir, `${sid}.jsonl`);
  fs.writeFileSync(transcriptPath, "");
  const journalPath = path.join(projectDir, sid, "subagents", "workflows", "wf_a", "journal.jsonl");
  if (records) {
    fs.mkdirSync(path.dirname(journalPath), { recursive: true });
    fs.writeFileSync(journalPath, records.map((r) => JSON.stringify(r)).join("\n") + "\n");
  }
  return { cwd, transcriptPath, journalPath };
}

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

test("a fresh running=0 is the inter-phase gap (held); the explicit zero lands only once the tree goes stale, then the slot watches", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("complete", "cc-wf-complete",
    [started("k1"), started("k2"), result("k1")]);
  const id = resolveHookSession({ session_id: "cc-wf-complete", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    assert.deepEqual(sessions.get(id).agents, { running: 1, done: 1 });

    // The last agent of the phase finishes: the journal reads running=0, but it
    // was JUST written — that is the between-phases gap, not completion. The
    // indicator must HOLD (no premature zero broadcast) and stay armed.
    fs.appendFileSync(journalPath, JSON.stringify(result("k2")) + "\n");
    let before = sseBuffer.length;
    pollWorkflowActivity(Date.now());
    assert.deepEqual(sessions.get(id).agents, { running: 1, done: 1 }, "held — a fresh running=0 is not completion");
    assert.equal(sessions.get(id).workflowActive, true, "stays armed for a possible next phase");
    assert.equal(lastSessionEvent(sseBuffer.slice(before), id), null, "no premature {running: 0} broadcast");

    // The workflow truly ended: nothing more is written and the tree goes
    // stale. NOW the explicit zero lands and the poll goes quiet.
    const old = new Date(Date.now() - 2 * STALE_MS);
    fs.utimesSync(journalPath, old, old);
    before = sseBuffer.length;
    pollWorkflowActivity(Date.now());
    // The completion payload still carries the TRUE done count (2), even though
    // the now-stale journal aggregates to done:0 — the peak done is preserved.
    assert.deepEqual(sessions.get(id).agents, { running: 0, done: 2 }, "stale tree → explicit zero, true done preserved");
    assert.equal(sessions.get(id).workflowActive, false, "the poll went quiet");
    const cleared = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(cleared, "the completion state was broadcast");
    assert.deepEqual(cleared.agents, { running: 0, done: 2 });

    // Completion is no longer the end of observation (issue #108): the slot
    // keeps WATCHING its tree, so new journal activity re-arms the scan on the
    // next poll without waiting for a fresh Workflow hook.
    fs.appendFileSync(journalPath, JSON.stringify(started("k9")) + "\n");
    pollWorkflowActivity(Date.now());
    assert.equal(sessions.get(id).agents.running, 1, "the watching slot re-armed on fresh journal activity");

    // A fresh launch signal on the re-armed slot still sees the new agent.
    markWorkflowActivity(id);
    assert.equal(sessions.get(id).agents.running, 1, "re-arm: the launch signal sees the new agent");
  } finally {
    sessions.delete(id);
  }
});

test("multi-phase: a poll landing in the inter-phase running=0 gap holds blue, and phase 2's agents surface (issue #70 flaw 1)", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // Phase 1: two agents running.
  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("multiphase", "cc-wf-multi",
    [started("p1a"), started("p1b")]);
  const id = resolveHookSession({ session_id: "cc-wf-multi", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    assert.deepEqual(sessions.get(id).agents, { running: 2, done: 0 }, "phase 1 running");

    // Phase 1 finishes; phase 2 not spawned yet → the journal reads running=0,
    // freshly written. A poll lands right in this gap. Before #70 this disarmed
    // the scan and dropped blue to green for the rest of the workflow.
    fs.appendFileSync(journalPath, [result("p1a"), result("p1b")].map((r) => JSON.stringify(r)).join("\n") + "\n");
    pollWorkflowActivity(Date.now());
    assert.equal(sessions.get(id).workflowActive, true, "still armed through the inter-phase gap");
    assert.equal(sessions.get(id).agents.running, 2, "blue held (last state kept) — not cleared to green");

    // Phase 2 spawns its agents: the scan must still be running and surface them.
    fs.appendFileSync(journalPath, [started("p2a"), started("p2b"), started("p2c")].map((r) => JSON.stringify(r)).join("\n") + "\n");
    const before = sseBuffer.length;
    pollWorkflowActivity(Date.now());
    assert.deepEqual(sessions.get(id).agents, { running: 3, done: 2 }, "phase 2 agents visible");
    const event = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(event, "phase 2 running state broadcast");
    assert.equal(event.agents.running, 3);
  } finally {
    sessions.delete(id);
  }
});

test("a phase with one long-running agent stays live via its agent-*.jsonl transcript after journal.jsonl goes stale (issue #70 flaw 2)", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");

  // One agent started, none finished — the journal gains no further line until
  // that agent completes.
  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("longagent", "cc-wf-long",
    [started("only")]);
  const wfDir = path.dirname(journalPath);
  const id = resolveHookSession({ session_id: "cc-wf-long", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    assert.deepEqual(sessions.get(id).agents, { running: 1, done: 0 }, "the single agent is running");

    // journal.jsonl goes stale (no start/result line for longer than the
    // window) while the agent is very much alive, writing its transcript.
    const old = new Date(Date.now() - 2 * STALE_MS);
    fs.utimesSync(journalPath, old, old);
    const agentTranscript = path.join(wfDir, "agent-only.jsonl");
    fs.writeFileSync(agentTranscript, JSON.stringify({ type: "assistant" }) + "\n"); // just written — the liveness signal
    pollWorkflowActivity(Date.now());
    assert.deepEqual(sessions.get(id).agents, { running: 1, done: 0 }, "still blue — the fresh transcript kept it live");
    assert.equal(sessions.get(id).workflowActive, true, "still armed");

    // The agent finally dies: its transcript stops too, so the whole dir goes
    // stale and the indicator clears.
    fs.utimesSync(agentTranscript, old, old);
    pollWorkflowActivity(Date.now());
    assert.equal(sessions.get(id).agents.running, 0, "everything stale → cleared");
    assert.equal(sessions.get(id).workflowActive, false, "poll went quiet");
  } finally {
    sessions.delete(id);
  }
});

test("a launch signal that sees only a stale journal stays armed — the new workflow's journal still surfaces {running: N}", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // An earlier workflow's long-dead journal is already on disk, so the
  // workflows dir EXISTS when the launch signal for the next workflow fires
  // — but the runner has not written the new journal yet.
  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("stale", "cc-wf-stale",
    [started("k1")]);
  const old = new Date(Date.now() - 2 * STALE_MS);
  fs.utimesSync(journalPath, old, old);
  const id = resolveHookSession({ session_id: "cc-wf-stale", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    const slot = sessions.get(id);
    // Registration reconciled the stale leftover to the explicit zero (issue
    // #68); the launch signal then adds no spurious completion of its own.
    assert.deepEqual(slot.agents, { running: 0, done: 0 }, "stale leftover reconciled to zero at registration, not a launch-race completion");
    assert.equal(slot.workflowActive, true, "stays armed for the new workflow's journal to appear");

    // The new workflow's journal materializes a beat later: the poll must
    // surface its running agents instead of having gone quiet.
    const wfDirB = path.join(path.dirname(path.dirname(journalPath)), "wf_b");
    fs.mkdirSync(wfDirB, { recursive: true });
    fs.writeFileSync(path.join(wfDirB, "journal.jsonl"),
      [started("b1"), started("b2"), started("b3")].map((r) => JSON.stringify(r)).join("\n") + "\n");
    const before = sseBuffer.length;
    pollWorkflowActivity(Date.now());
    assert.deepEqual(sessions.get(id).agents, { running: 3, done: 0 });
    const event = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(event, "the running state was broadcast");
    assert.deepEqual(event.agents, { running: 3, done: 0 });
  } finally {
    sessions.delete(id);
  }
});

test("only stale journals and nothing ever materializing: give up quietly after the stale window", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");

  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("stale-giveup", "cc-wf-stale-giveup",
    [started("k1")]);
  const old = new Date(Date.now() - 10 * STALE_MS);
  fs.utimesSync(journalPath, old, old);
  const id = resolveHookSession({ session_id: "cc-wf-stale-giveup", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    const slot = sessions.get(id);
    assert.equal(slot.workflowActive, true, "stays armed at first");
    pollWorkflowActivity(Date.now() + STALE_MS + 1_000);
    assert.equal(slot.workflowActive, false, "gave up after the stale window");
    // Registration reconciled the stale journal to the explicit zero (issue
    // #68); the give-up path never adds a phantom running count on top.
    assert.deepEqual(slot.agents, { running: 0, done: 0 }, "stale journal at registration reconciled to zero, no phantom running count");
  } finally {
    sessions.delete(id);
  }
});

test("an OBSERVED workflow whose journal goes stale gets the explicit zero — the indicator cannot stick", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");

  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("stuck", "cc-wf-stuck",
    [started("k1")]); // started, never finished — a killed workflow's shape
  const id = resolveHookSession({ session_id: "cc-wf-stuck", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    assert.deepEqual(sessions.get(id).agents, { running: 1, done: 0 }, "the workflow was observed running");

    // The workflow is killed: its journal never gets result lines and goes
    // stale. Because running agents WERE observed since arming, the stale
    // scan's zero is a real completion state, not a launch race.
    const old = new Date(Date.now() - 2 * STALE_MS);
    fs.utimesSync(journalPath, old, old);
    pollWorkflowActivity(Date.now());
    const slot = sessions.get(id);
    assert.deepEqual(slot.agents, { running: 0, done: 0 }, "a stale journal never yields running agents");
    assert.equal(slot.workflowActive, false, "the poll went quiet");
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

test("an oversized live journal is indeterminate: last-known counts carry forward, no false completion", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("oversized", "cc-wf-big",
    [started("k1"), started("k2")]);
  const id = resolveHookSession({ session_id: "cc-wf-big", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    assert.deepEqual(sessions.get(id).agents, { running: 2, done: 0 });

    // The journal outgrows the 1 MB read cap while its agents still run: the
    // scan must NOT mistake "could not read" for "observed completion".
    fs.appendFileSync(journalPath, "x".repeat(1024 * 1024 + 1) + "\n");
    const before = sseBuffer.length;
    pollWorkflowActivity(Date.now());
    const slot = sessions.get(id);
    assert.deepEqual(slot.agents, { running: 2, done: 0 }, "last-known counts carry forward");
    assert.equal(slot.workflowActive, true, "indeterminate is not completion — stays armed");
    assert.equal(lastSessionEvent(sseBuffer.slice(before), id), null, "no false {running: 0} broadcast");

    // Once the oversized journal goes stale, the ordinary staleness gate
    // retires it and the explicit zero lands.
    const old = new Date(Date.now() - 2 * STALE_MS);
    fs.utimesSync(journalPath, old, old);
    pollWorkflowActivity(Date.now());
    assert.equal(sessions.get(id).agents.running, 0, "stale retires it and completion proceeds");
    assert.equal(sessions.get(id).workflowActive, false);
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

// Issue #68: a bridge restart loses the in-memory workflow arming. The surviving
// Claude session re-registers (its hookSessionId binding gone) via
// resolveHookSession -> createExternalSession, where reconcileWorkflowActivity
// must re-derive the indicator from the on-disk journal — no fresh Workflow hook,
// no markWorkflowActivity.
test("restart mid-workflow: re-registration re-arms the scanner from a live journal and re-seeds the count (issue #68)", async () => {
  const { sessions, resolveHookSession, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // A live workflow journal already on disk, as after a mid-run restart.
  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("restart-live", "cc-wf-restart-live",
    [started("k1"), started("k2"), started("k3"), result("k1")]); // running=2, done=1, fresh
  const before = sseBuffer.length;
  // The first post-restart hook re-registers the session. NO Workflow hook.
  const id = resolveHookSession({ session_id: "cc-wf-restart-live", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    assert.deepEqual(sessions.get(id).agents, { running: 2, done: 1 }, "re-seeded the running count on registration");
    assert.equal(sessions.get(id).workflowActive, true, "re-armed the scanner without a Workflow hook");
    const event = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(event, "the registration running event carried the reconciled agents");
    assert.deepEqual(event.agents, { running: 2, done: 1 });

    // The scanner now tracks to completion like any armed workflow: the last two
    // finish (fresh zero, held per #70), then the tree goes stale and clears.
    fs.appendFileSync(journalPath, [result("k2"), result("k3")].map((r) => JSON.stringify(r)).join("\n") + "\n");
    pollWorkflowActivity(Date.now());
    assert.equal(sessions.get(id).agents.running, 2, "held through the fresh inter-phase zero");
    const old = new Date(Date.now() - 2 * STALE_MS);
    fs.utimesSync(journalPath, old, old);
    pollWorkflowActivity(Date.now());
    assert.equal(sessions.get(id).agents.running, 0, "tracked to completion and cleared to green");
    assert.equal(sessions.get(id).workflowActive, false, "poll went quiet");
  } finally {
    sessions.delete(id);
  }
});

test("restart after the workflow finished during the downtime: registration broadcasts the explicit zero to clear a stale blue (issue #68)", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // A workflow that was in flight but whose journal is now stale (it finished or
  // died while the bridge was down).
  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("restart-stale", "cc-wf-restart-stale",
    [started("k1")]);
  const old = new Date(Date.now() - 2 * STALE_MS);
  fs.utimesSync(journalPath, old, old);
  const before = sseBuffer.length;
  const id = resolveHookSession({ session_id: "cc-wf-restart-stale", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    // The client may be latched on a stale running>0; the bridge must broadcast
    // the explicit zero so preserve-on-absence is overwritten. Nothing is live,
    // so the scanner is not armed.
    assert.deepEqual(sessions.get(id).agents, { running: 0, done: 0 }, "reconciled to the explicit zero");
    assert.notEqual(sessions.get(id).workflowActive, true, "nothing live to track — not armed");
    const event = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(event, "the registration event carried the clearing zero");
    assert.deepEqual(event.agents, { running: 0, done: 0 });
  } finally {
    sessions.delete(id);
  }
});

test("registration of a session that never ran a workflow adds no agents field (reconcile is silent)", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // A transcript with no workflows dir at all.
  const dir = path.join(fixturesRoot, "restart-none");
  fs.mkdirSync(dir, { recursive: true });
  const transcriptPath = path.join(dir, "cc-wf-restart-none.jsonl");
  fs.writeFileSync(transcriptPath, "");
  const before = sseBuffer.length;
  const id = resolveHookSession({ session_id: "cc-wf-restart-none", cwd: dir, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    assert.equal(sessions.get(id).agents, undefined, "no workflow tree → no agents field");
    const event = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(event, "a running event was still broadcast");
    assert.ok(!Object.hasOwn(event, "agents"), "no agents field on the wire");
  } finally {
    sessions.delete(id);
  }
});

test("restart during an inter-phase gap (live tree reading running=0) does NOT clear blue — it arms and lets the poll resolve (issue #68 x #70)", async () => {
  const { sessions, resolveHookSession, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // Phase 1 finished, phase 2 not yet spawned: the journal reads running=0 but
  // was just written (fresh) — indistinguishable from real completion. Clearing
  // here would drop a still-live workflow's blue to green (the #70 bug).
  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("restart-gap", "cc-wf-restart-gap",
    [started("p1a"), started("p1b"), result("p1a"), result("p1b")]); // running=0, done=2, fresh
  const before = sseBuffer.length;
  const id = resolveHookSession({ session_id: "cc-wf-restart-gap", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    assert.equal(sessions.get(id).agents, undefined, "no false clear on a fresh inter-phase zero — agents left absent");
    assert.equal(sessions.get(id).workflowActive, true, "armed to track the live workflow");
    const regEvent = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(!regEvent || !Object.hasOwn(regEvent, "agents"), "no agents field broadcast at registration");

    // Phase 2 spawns: the armed poll surfaces blue (the client's preserve-on-
    // absence held its blue in the meantime).
    fs.appendFileSync(journalPath, [started("p2a"), started("p2b")].map((r) => JSON.stringify(r)).join("\n") + "\n");
    pollWorkflowActivity(Date.now());
    assert.deepEqual(sessions.get(id).agents, { running: 2, done: 2 }, "phase 2 surfaced by the armed poll");
  } finally {
    sessions.delete(id);
  }
});

test("restart onto an oversized (unreadable) live journal does NOT false-clear the indicator (issue #68 x unreadableLive)", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // A live workflow (running=2) whose journal has outgrown the read cap: the
  // scan cannot read it and, on a fresh post-restart slot with an empty cache,
  // reports running=0 with unreadableLive=true and a fresh mtime. Reconcile must
  // NOT mistake "could not read" for "completed" and clear to green.
  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("restart-big", "cc-wf-restart-big",
    [started("k1"), started("k2")]);
  fs.appendFileSync(journalPath, "x".repeat(1024 * 1024 + 1) + "\n"); // outgrows the cap, fresh mtime
  const before = sseBuffer.length;
  const id = resolveHookSession({ session_id: "cc-wf-restart-big", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    assert.equal(sessions.get(id).agents, undefined, "no false clear on an unreadable live journal — agents left absent");
    assert.equal(sessions.get(id).workflowActive, true, "armed; the poll resolves it once readable or stale");
    const event = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(!event || !Object.hasOwn(event, "agents"), "no agents field broadcast at registration");
  } finally {
    sessions.delete(id);
  }
});

test("restart onto a just-finished workflow with a still-fresh journal holds, then clears once the tree goes stale (issue #68)", async () => {
  const { sessions, resolveHookSession, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // The workflow finished during the downtime, but its journal is still fresh at
  // re-registration (running=0). Reconcile cannot tell this from a between-phases
  // gap, so it holds (no immediate clear) and — because it arms as observed —
  // lets the poll broadcast the completion zero once the tree goes quiet, exactly
  // like a normal completion (#70). Without arming-as-observed the poll would
  // give up silently and strand the client's stale blue.
  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("restart-fresh-done", "cc-wf-restart-fresh-done",
    [started("k1"), result("k1")]); // running=0, done=1, fresh
  const id = resolveHookSession({ session_id: "cc-wf-restart-fresh-done", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    assert.equal(sessions.get(id).agents, undefined, "held — a fresh running=0 is not an immediate clear");
    assert.equal(sessions.get(id).workflowActive, true, "armed, observed by construction");

    // It stays quiet: the journal goes stale → the poll broadcasts the explicit
    // zero (with the true done preserved) to clear the client.
    const old = new Date(Date.now() - 2 * STALE_MS);
    fs.utimesSync(journalPath, old, old);
    const before = sseBuffer.length;
    pollWorkflowActivity(Date.now());
    assert.deepEqual(sessions.get(id).agents, { running: 0, done: 1 }, "cleared to the explicit zero once stale, done preserved");
    assert.equal(sessions.get(id).workflowActive, false, "poll went quiet");
    const cleared = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(cleared, "the completion zero was broadcast to clear the client");
    assert.equal(cleared.agents.running, 0);
  } finally {
    sessions.delete(id);
  }
});

// --- The ACP feed (issue #105) -----------------------------------------------
// ACP sessions fire no hooks (the Zed-only pivot retired them), so the
// hook-fed indicator above never armed and never had a transcript to scan.
// The ACP feed is additive: registration derives the transcript path from
// cwd + SDK session id (the CLI projects-dir convention) and reconciles like
// the hook path's #68 registration; the launch signal is the teed tool_call
// whose _meta names the Workflow tool (handleAcpUpdate → markWorkflowActivity).
// Scanner/staleness semantics are shared and covered above — these tests cover
// the ACP-specific feed points.

test("ACP registration derives the transcript path and reconciles a live journal — no hook ever fires (#105)", async () => {
  const { sessions, registerAcpSession, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // A live workflow already on disk, as after a bridge restart mid-workflow:
  // the fork's replay re-registers the session, and that registration is the
  // only signal the bridge gets — no Workflow hook, no fresh tool_call.
  const { cwd, transcriptPath, journalPath } = makeAcpWorkflowTree("restart-live", "acp-wf-restart-live",
    [started("k1"), started("k2"), started("k3"), result("k1")]); // running=2, done=1, fresh
  const before = sseBuffer.length;
  registerAcpSession({ sessionId: "acp-wf-restart-live", sdkSessionId: "acp-wf-restart-live", cwd });
  const slot = sessions.get("acp-wf-restart-live");
  try {
    assert.equal(slot.transcriptPath, transcriptPath, "derived from cwd + SDK session id — the CLI convention");
    assert.deepEqual(slot.agents, { running: 2, done: 1 }, "re-seeded the running count at registration");
    assert.equal(slot.workflowActive, true, "re-armed the scanner without any launch signal");
    const event = lastSessionEvent(sseBuffer.slice(before), "acp-wf-restart-live");
    assert.ok(event, "the registration running event carried the reconciled agents");
    assert.deepEqual(event.agents, { running: 2, done: 1 });

    // From here the shared machinery tracks to completion exactly like an
    // armed hook workflow: fresh zero held (#70), stale tree clears.
    fs.appendFileSync(journalPath, [result("k2"), result("k3")].map((r) => JSON.stringify(r)).join("\n") + "\n");
    pollWorkflowActivity(Date.now());
    assert.equal(slot.agents.running, 2, "held through the fresh inter-phase zero");
    const old = new Date(Date.now() - 2 * STALE_MS);
    fs.utimesSync(journalPath, old, old);
    pollWorkflowActivity(Date.now());
    assert.deepEqual(slot.agents, { running: 0, done: 3 }, "tracked to completion, peak done preserved");
    assert.equal(slot.workflowActive, false, "poll went quiet");
  } finally {
    sessions.delete("acp-wf-restart-live");
  }
});

test("ACP re-register after the workflow finished during bridge downtime broadcasts the explicit zero (#68 x #105)", async () => {
  const { sessions, registerAcpSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { cwd, journalPath } = makeAcpWorkflowTree("restart-stale", "acp-wf-restart-stale", [started("k1")]);
  const old = new Date(Date.now() - 2 * STALE_MS);
  fs.utimesSync(journalPath, old, old);
  const before = sseBuffer.length;
  registerAcpSession({ sessionId: "acp-wf-restart-stale", sdkSessionId: "acp-wf-restart-stale", cwd });
  const slot = sessions.get("acp-wf-restart-stale");
  try {
    // A watch latched on a pre-restart blue must be overwritten explicitly —
    // preserve-on-absence means only a broadcast zero can clear it.
    assert.deepEqual(slot.agents, { running: 0, done: 0 }, "reconciled to the explicit zero");
    assert.notEqual(slot.workflowActive, true, "nothing live to track — not armed");
    const event = lastSessionEvent(sseBuffer.slice(before), "acp-wf-restart-stale");
    assert.ok(event, "the registration event carried the clearing zero");
    assert.deepEqual(event.agents, { running: 0, done: 0 });
  } finally {
    sessions.delete("acp-wf-restart-stale");
  }
});

test("an ACP session that never ran a workflow still gets its derived path, but no agents field (#105)", async () => {
  const { sessions, registerAcpSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { cwd, transcriptPath } = makeAcpWorkflowTree("no-wf", "acp-wf-none", null);
  const before = sseBuffer.length;
  registerAcpSession({ sessionId: "acp-wf-none", sdkSessionId: "acp-wf-none", cwd });
  const slot = sessions.get("acp-wf-none");
  try {
    // The path is seeded unconditionally — a later Workflow tool_call must be
    // able to arm a scan for a journal that does not exist yet.
    assert.equal(slot.transcriptPath, transcriptPath, "path derived even with no workflow on disk");
    assert.equal(slot.agents, undefined, "no workflow tree → no agents field, not a phantom zero");
    const event = lastSessionEvent(sseBuffer.slice(before), "acp-wf-none");
    assert.ok(event, "a running event was still broadcast");
    assert.ok(!Object.hasOwn(event, "agents"), "no agents field on the wire");
  } finally {
    sessions.delete("acp-wf-none");
  }
});

test("a fork drop mid-workflow (Zed relaunch) re-arms on re-register — no stale blue, no stranded grey (#105)", async () => {
  const { sessions, registerAcpSession, endAcpSession, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { cwd } = makeAcpWorkflowTree("fork-drop", "acp-wf-fork-drop", [started("k1"), started("k2")]);
  registerAcpSession({ sessionId: "acp-wf-fork-drop", sdkSessionId: "acp-wf-fork-drop", cwd });
  const slot = sessions.get("acp-wf-fork-drop");
  try {
    assert.deepEqual(slot.agents, { running: 2, done: 0 }, "armed and seeded at first register");

    // The fork's inbox drops (Zed quit): the slot ends, and the next poll tick
    // goes quiet for it — the bridge stays up, so no restart reconcile runs.
    endAcpSession("acp-wf-fork-drop", "acp-fork-disconnected");
    pollWorkflowActivity(Date.now());
    assert.equal(slot.workflowActive, false, "ended slot disarmed the poll");

    // Zed relaunches and the session resumes: the SAME slot re-registers (it
    // is still within the prune grace). The workflow never stopped running on
    // disk — the re-register must re-arm and re-seed, or the wrist strands.
    const before = sseBuffer.length;
    registerAcpSession({ sessionId: "acp-wf-fork-drop", sdkSessionId: "acp-wf-fork-drop", cwd });
    assert.equal(slot.workflowActive, true, "re-armed on the surviving slot's re-register");
    assert.deepEqual(slot.agents, { running: 2, done: 0 });
    const event = lastSessionEvent(sseBuffer.slice(before), "acp-wf-fork-drop");
    assert.ok(event, "the re-register running event carried the agents");
    assert.deepEqual(event.agents, { running: 2, done: 0 });
  } finally {
    sessions.delete("acp-wf-fork-drop");
  }
});

// Black-box wiring for the launch signal: a real bridge, a real /acp/register,
// and the teed tool_call exactly as the fork's client tee posts it — proves
// handleAcpUpdate routes a Workflow tool_call into markWorkflowActivity (and
// nothing else does) and the indicator reaches the wire.
test("a teed Workflow tool_call arms the scan for an ACP session — other tools do not (#105)", async (t) => {
  const sid = "acp-wf-wire";
  const bridge = await startBridge(t, {
    env: {
      CLAUDE_WATCH_WORKFLOW_POLL_MS: "3600000",
      CLAUDE_WATCH_WORKFLOW_STALE_MS: String(STALE_MS),
      CLAUDE_WATCH_CLAUDE_PROJECTS_ROOT: projectsRoot,
    },
  });
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  const sse = connectSse(bridge.port, pair.body.token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  // Registration precedes the workflow (the real order: the session exists
  // before any turn runs a tool), so register-time reconcile sees nothing.
  const { cwd, journalPath } = makeAcpWorkflowTree("wire-pre", sid, null);
  const reg = await request(bridge.port, "POST", "/acp/register", {
    body: { connection: "conn-wf-wire", sessionId: sid, sdkSessionId: sid, cwd },
  });
  assert.equal(reg.status, 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === sid);

  // The workflow journal materializes at the derived location.
  fs.mkdirSync(path.dirname(journalPath), { recursive: true });
  fs.writeFileSync(journalPath,
    [started("k1"), started("k2"), result("k1")].map((r) => JSON.stringify(r)).join("\n") + "\n");

  const teeToolCall = (toolName, toolCallId) =>
    request(bridge.port, "POST", "/acp/update", {
      body: {
        connection: "conn-wf-wire", sessionId: sid, kind: "session_update",
        payload: {
          sessionId: sid,
          update: {
            sessionUpdate: "tool_call", toolCallId, status: "pending", title: toolName,
            _meta: { claudeCode: { toolName } },
          },
        },
      },
    });

  // A non-Workflow tool_call must not arm. Proven via the NEXT broadcast (a
  // title update): had the Bash call armed, its immediate scan would have set
  // slot.agents and every later session event would carry it.
  assert.equal((await teeToolCall("Bash", "t1")).status, 200);
  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-wf-wire", sessionId: sid, kind: "session_update",
      payload: { sessionId: sid, update: { sessionUpdate: "session_info_update", title: "wf probe" } },
    },
  });
  const titled = await sse.waitFor((e) => e.event === "session" && e.parsed?.title === "wf probe");
  assert.ok(!Object.hasOwn(titled.parsed, "agents"), "a Bash tool_call must not arm the workflow scan");

  // The Workflow tool_call arms; the immediate scan sees the journal.
  assert.equal((await teeToolCall("Workflow", "t2")).status, 200);
  const armed = await sse.waitFor((e) => e.event === "session" && e.parsed?.agents?.running === 1);
  assert.deepEqual(armed.parsed.agents, { running: 1, done: 1 });
});

// End to end on the wire, including the child bridge's REAL poll timer: blue
// while agents run, the explicit completion zero once the tree goes stale.
test("ACP workflow on the wire: agents while running, the explicit zero after the staleness window (#105)", async (t) => {
  const sid = "acp-wf-e2e";
  const bridge = await startBridge(t, {
    env: {
      CLAUDE_WATCH_WORKFLOW_POLL_MS: "200",
      CLAUDE_WATCH_WORKFLOW_STALE_MS: String(STALE_MS),
      CLAUDE_WATCH_CLAUDE_PROJECTS_ROOT: projectsRoot,
    },
  });
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  const sse = connectSse(bridge.port, pair.body.token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  const { cwd, journalPath } = makeAcpWorkflowTree("wire-e2e", sid, null);
  const reg = await request(bridge.port, "POST", "/acp/register", {
    body: { connection: "conn-wf-e2e", sessionId: sid, sdkSessionId: sid, cwd },
  });
  assert.equal(reg.status, 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === sid);

  fs.mkdirSync(path.dirname(journalPath), { recursive: true });
  fs.writeFileSync(journalPath,
    [started("k1"), started("k2"), result("k1")].map((r) => JSON.stringify(r)).join("\n") + "\n");
  const armed = await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-wf-e2e", sessionId: sid, kind: "session_update",
      payload: {
        sessionId: sid,
        update: {
          sessionUpdate: "tool_call", toolCallId: "wf1", status: "pending", title: "Workflow",
          _meta: { claudeCode: { toolName: "Workflow" } },
        },
      },
    },
  });
  assert.equal(armed.status, 200);
  const blue = await sse.waitFor((e) => e.event === "session" && e.parsed?.agents?.running === 1);
  assert.deepEqual(blue.parsed.agents, { running: 1, done: 1 }, "DELEGATED state on the wire while agents run");

  // The workflow goes quiet: backdate the journal past the stale window and
  // let the child's own poll discover completion.
  const old = new Date(Date.now() - 2 * STALE_MS);
  fs.utimesSync(journalPath, old, old);
  const cleared = await sse.waitFor((e) => e.event === "session" && e.parsed?.agents?.running === 0);
  assert.deepEqual(cleared.parsed.agents, { running: 0, done: 1 }, "explicit completion zero, peak done preserved");
});

// --- Watching after the stale-clear (issue #108) -----------------------------
// The staleness window decides only WHEN the honest zero is broadcast — it is
// not a verdict of death. A machine-sleep resume, or a single tool call silent
// for longer than the window (transcripts gain nothing until a tool result
// returns), makes a "completed" tree write again with the one-shot launch
// signal long past. So the stale-clear moves the slot to WATCHING: the same
// poll re-stats the tree and re-arms + re-publishes exactly like the restart
// reconcile the moment the newest write is back inside the window. Watching
// ends with the slot's life, never with silence; sessions with no tree stay
// off the poll entirely (the idle-cost gate).

test("the stale-clear is not final: a resumed tree re-arms the WATCHING slot and publishes running>0 — no restart, no launch signal (#108)", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("resume", "cc-wf-resume",
    [started("k1"), started("k2"), result("k1")]); // running=1, done=1
  const id = resolveHookSession({ session_id: "cc-wf-resume", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    const slot = sessions.get(id);
    assert.deepEqual(slot.agents, { running: 1, done: 1 }, "armed and counting");

    // A longer-than-window silent stretch (machine sleep, or a tool call that
    // returns nothing for the whole window): the tree reads dead and the
    // honest zero is broadcast — the pre-#108 permanent disarm point.
    const old = new Date(Date.now() - 2 * STALE_MS);
    fs.utimesSync(journalPath, old, old);
    pollWorkflowActivity(Date.now());
    assert.deepEqual(slot.agents, { running: 0, done: 1 }, "honest zero at the window");
    assert.equal(slot.workflowActive, false, "armed poll went quiet");
    assert.equal(slot.workflowWatching, true, "…but the slot is WATCHING, not abandoned");

    // The workflow was alive all along — the silent tool call returns and the
    // journal gains a line. No bridge restart, no Workflow launch signal.
    fs.appendFileSync(journalPath, JSON.stringify(started("k3")) + "\n"); // running=2, done=1, fresh
    const before = sseBuffer.length;
    pollWorkflowActivity(Date.now());
    assert.equal(slot.workflowActive, true, "the watch re-armed the scanner");
    assert.equal(slot.workflowWatching, false, "watching handed back to the armed poll");
    assert.deepEqual(slot.agents, { running: 2, done: 1 }, "running>0 re-seeded from the resumed tree");
    const event = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(event, "the re-arm was published");
    assert.deepEqual(event.agents, { running: 2, done: 1 });

    // The re-armed scanner still tracks to a REAL completion afterwards: the
    // fresh zero holds (#70 intact after a watch re-arm), the stale tree
    // clears with the peak done preserved.
    fs.appendFileSync(journalPath, [result("k2"), result("k3")].map((r) => JSON.stringify(r)).join("\n") + "\n");
    pollWorkflowActivity(Date.now());
    assert.equal(slot.agents.running, 2, "fresh zero held (#70 semantics survive the re-arm)");
    fs.utimesSync(journalPath, old, old);
    pollWorkflowActivity(Date.now());
    assert.deepEqual(slot.agents, { running: 0, done: 3 }, "true completion: explicit zero, peak done preserved");
  } finally {
    sessions.delete(id);
  }
});

test("watching survives multiple silent stretches — every false stale re-arms on the next resume (#108)", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("resume-twice", "cc-wf-resume-twice",
    [started("a1")]); // running=1
  const id = resolveHookSession({ session_id: "cc-wf-resume-twice", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  const old = new Date(Date.now() - 2 * STALE_MS);
  try {
    markWorkflowActivity(id);
    const slot = sessions.get(id);
    assert.deepEqual(slot.agents, { running: 1, done: 0 });

    // Silent stretch 1 → honest zero, watching.
    fs.utimesSync(journalPath, old, old);
    pollWorkflowActivity(Date.now());
    assert.deepEqual(slot.agents, { running: 0, done: 0 });
    assert.equal(slot.workflowWatching, true, "watching after the first false stale");

    // Resume 1 → re-armed and published.
    fs.appendFileSync(journalPath, JSON.stringify(started("a2")) + "\n"); // running=2, fresh
    pollWorkflowActivity(Date.now());
    assert.deepEqual(slot.agents, { running: 2, done: 0 }, "first resume re-armed");
    assert.equal(slot.workflowActive, true);

    // Silent stretch 2 → the zero again, watching again — the first re-arm did
    // not use up the slot's only resurrection.
    fs.utimesSync(journalPath, old, old);
    pollWorkflowActivity(Date.now());
    assert.deepEqual(slot.agents, { running: 0, done: 0 });
    assert.equal(slot.workflowWatching, true, "watching again after the second false stale");

    // Resume 2 → re-armed and published again.
    fs.appendFileSync(journalPath, JSON.stringify(result("a1")) + "\n"); // running=1, done=1, fresh
    const before = sseBuffer.length;
    pollWorkflowActivity(Date.now());
    assert.deepEqual(slot.agents, { running: 1, done: 1 }, "second resume re-armed");
    const event = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(event, "the second re-arm was published too");
    assert.deepEqual(event.agents, { running: 1, done: 1 });
  } finally {
    sessions.delete(id);
  }
});

test("watching ends with the slot's life: an ended session's resumed tree re-arms nothing (#108)", async () => {
  const { sessions, registerAcpSession, endAcpSession, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { cwd, journalPath } = makeAcpWorkflowTree("watch-end", "acp-wf-watch-end", [started("k1")]);
  registerAcpSession({ sessionId: "acp-wf-watch-end", sdkSessionId: "acp-wf-watch-end", cwd });
  const slot = sessions.get("acp-wf-watch-end");
  try {
    assert.deepEqual(slot.agents, { running: 1, done: 0 }, "armed at registration");

    // False stale → the zero, watching.
    const old = new Date(Date.now() - 2 * STALE_MS);
    fs.utimesSync(journalPath, old, old);
    pollWorkflowActivity(Date.now());
    assert.equal(slot.workflowWatching, true, "stale-clear left the slot watching");

    // The session ends (Zed quit): the next tick sheds the watch with it.
    endAcpSession("acp-wf-watch-end", "acp-closed");
    pollWorkflowActivity(Date.now());
    assert.equal(slot.workflowWatching, false, "watching ended with the slot");
    assert.equal(slot.workflowActive, false);

    // The tree resuming AFTER the session died must resurrect nothing.
    fs.appendFileSync(journalPath, JSON.stringify(started("k2")) + "\n");
    const before = sseBuffer.length;
    pollWorkflowActivity(Date.now());
    assert.equal(slot.workflowActive, false, "no re-arm for a dead session");
    assert.equal(slot.workflowWatching, false);
    assert.equal(lastSessionEvent(sseBuffer.slice(before), "acp-wf-watch-end"), null, "nothing broadcast");
  } finally {
    sessions.delete("acp-wf-watch-end");
  }
});

test("a genuinely dead tree stays at the explicit zero forever — watching never fabricates a resurrection (#108)", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("dead-forever", "cc-wf-dead-forever",
    [started("k1")]); // started, never finished — a killed workflow's shape
  const id = resolveHookSession({ session_id: "cc-wf-dead-forever", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    const slot = sessions.get(id);
    assert.deepEqual(slot.agents, { running: 1, done: 0 }, "observed running");

    const old = new Date(Date.now() - 2 * STALE_MS);
    fs.utimesSync(journalPath, old, old);
    pollWorkflowActivity(Date.now());
    assert.deepEqual(slot.agents, { running: 0, done: 0 }, "honest zero at the window");

    // Nothing ever writes again: tick after tick the watch pays its bounded
    // stat sweep (one readdir + a stat per dir; a stale dir is skipped before
    // any journal read, so the dead journal is never re-parsed) and changes
    // nothing — no re-arm, no broadcast churn, no give-up either.
    const before = sseBuffer.length;
    for (let tick = 1; tick <= 5; tick++) {
      pollWorkflowActivity(Date.now() + tick * STALE_MS);
      assert.equal(slot.workflowActive, false, `tick ${tick}: never re-armed`);
      assert.equal(slot.workflowWatching, true, `tick ${tick}: still watching — silence never ends the watch`);
    }
    assert.deepEqual(slot.agents, { running: 0, done: 0 }, "the zero stands");
    assert.equal(lastSessionEvent(sseBuffer.slice(before), id), null, "no re-broadcast churn");
  } finally {
    sessions.delete(id);
  }
});

test("a launch signal with no tree gives up WITHOUT watching — no-tree sessions cost the poll nothing (#108)", async () => {
  const { sessions, resolveHookSession, markWorkflowActivity, pollWorkflowActivity } = await import("../sessions.js");

  // A transcript whose journal tree never materializes (same shape as the
  // give-up test above): there is nothing to stat, so watching it would put a
  // per-tick readdir on a session that never ran a workflow.
  const dir = path.join(fixturesRoot, "never-watch");
  fs.mkdirSync(dir, { recursive: true });
  const transcriptPath = path.join(dir, "cc-wf-never-watch.jsonl");
  fs.writeFileSync(transcriptPath, "");
  const id = resolveHookSession({ session_id: "cc-wf-never-watch", cwd: dir, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    markWorkflowActivity(id);
    const slot = sessions.get(id);
    pollWorkflowActivity(Date.now() + STALE_MS + 1_000);
    assert.equal(slot.workflowActive, false, "gave up after the stale window");
    assert.notEqual(slot.workflowWatching, true, "no tree → nothing to watch — the idle fast path applies");
  } finally {
    sessions.delete(id);
  }
});

test("restart onto a stale tree that later resumes: registration's zero is watched, not final (#68 x #108)", async () => {
  const { sessions, resolveHookSession, pollWorkflowActivity } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // The machine slept through a bridge restart: at re-registration the tree is
  // stale, so reconcile broadcasts the clearing zero — but the workflow
  // survived the sleep and resumes writing afterwards.
  const { cwd, transcriptPath, journalPath } = makeWorkflowTree("restart-resume", "cc-wf-restart-resume",
    [started("k1")]);
  const old = new Date(Date.now() - 2 * STALE_MS);
  fs.utimesSync(journalPath, old, old);
  // Settle the module gate first (no tracked slots remain → false), so this
  // test proves reconcile's stale branch RAISES it for the watch — without
  // that the poll would never visit the watching slot.
  pollWorkflowActivity(Date.now());
  const id = resolveHookSession({ session_id: "cc-wf-restart-resume", cwd, transcript_path: transcriptPath, tool_name: "Bash" });
  try {
    const slot = sessions.get(id);
    assert.deepEqual(slot.agents, { running: 0, done: 0 }, "reconciled to the explicit zero (#68 unchanged)");
    assert.notEqual(slot.workflowActive, true, "nothing live to track — not armed");
    assert.equal(slot.workflowWatching, true, "…but the tree is watched");

    // The workflow resumes: the poll's watch re-arms and publishes.
    fs.appendFileSync(journalPath, [result("k1"), started("k2")].map((r) => JSON.stringify(r)).join("\n") + "\n"); // running=1, done=1, fresh
    const before = sseBuffer.length;
    pollWorkflowActivity(Date.now());
    assert.equal(slot.workflowActive, true, "the watch re-armed the scanner");
    assert.deepEqual(slot.agents, { running: 1, done: 1 });
    const event = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(event, "the resume was published");
    assert.deepEqual(event.agents, { running: 1, done: 1 });
  } finally {
    sessions.delete(id);
  }
});
