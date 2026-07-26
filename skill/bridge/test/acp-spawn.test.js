// Watch spawn, born in Zed (the ACP-native spawn feature), black-box: the
// watch's `spawn: "claude"` rides a new `spawn` frame down the fork's inbox,
// the fork answers with /acp/register (detached) + /acp/spawn-result, and the
// watch's POST resolves with the fork's session. No fork = an honest 409 —
// there is deliberately NO PTY fallback for claude (Zed-only product). Codex
// keeps the PTY path. Also covers the correlation races (result before/after
// register, timeout with late convergence, inbox death mid-request), the
// desk-pickup claim registry, and the detached wrist-only permission policy.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { startBridge, request, tempDir, connectSse } from "./helpers.js";

async function pair(bridge) {
  const res = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(res.status, 200);
  return res.body.token;
}

function connectInbox(t, bridge, connectionId) {
  const inbox = connectSse(bridge.port, undefined, { path: `/acp/inbox?connection=${connectionId}` });
  t.after(() => inbox.close());
  return inbox;
}

async function registerAcp(bridge, { connection, sessionId, cwd, detached, active }) {
  const res = await request(bridge.port, "POST", "/acp/register", {
    body: { connection, sessionId, sdkSessionId: sessionId, cwd, detached, active },
  });
  assert.equal(res.status, 200);
}

async function spawnResult(bridge, body) {
  return request(bridge.port, "POST", "/acp/spawn-result", { body });
}

test("watch spawn rides the inbox and resolves with the fork's session (frame → register → result)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-spawn-happy-");

  const inbox = connectInbox(t, bridge, "fork-happy");
  assert.equal(await inbox.statusCode(), 200);

  const respPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude", cwd },
  });

  // The frame the fork sees: requestId + the resolved cwd + the agent.
  const frame = await inbox.waitFor((e) => e.event === "spawn");
  assert.ok(frame.parsed.requestId, "spawn frame carries a requestId");
  assert.equal(frame.parsed.cwd, cwd);
  assert.equal(frame.parsed.agent, "claude");

  // The real fork's order: register (detached) inside createSession, then ack.
  await registerAcp(bridge, { connection: "fork-happy", sessionId: "born-1", cwd, detached: true });
  await spawnResult(bridge, {
    connection: "fork-happy", requestId: frame.parsed.requestId, ok: true, sessionId: "born-1", cwd,
  });

  const resp = await respPromise;
  assert.equal(resp.status, 200);
  assert.equal(resp.body.ok, true);
  assert.equal(resp.body.sessionId, "born-1");
  assert.equal(resp.body.kind, "acp");
  assert.equal(resp.body.spawnRequestId, frame.parsed.requestId, "the response is attributable");

  // The slot is a normal ACP slot: dictatable, external (Hide, not Kill).
  const status = await request(bridge.port, "GET", "/status", { token });
  const slot = status.body.sessions.find((s) => s.id === "born-1");
  assert.equal(slot.kind, "acp");
  assert.equal(slot.dictatable, true);
  assert.equal(slot.external, true);
});

test("spawn-result BEFORE the register still yields a live, dictatable slot (early-register race)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-spawn-early-");

  const inbox = connectInbox(t, bridge, "fork-early");
  assert.equal(await inbox.statusCode(), 200);

  const respPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude", cwd },
  });
  const frame = await inbox.waitFor((e) => e.event === "spawn");

  // The ack overtakes the register (two sockets, no ordering guarantee).
  await spawnResult(bridge, {
    connection: "fork-early", requestId: frame.parsed.requestId, ok: true, sessionId: "born-2", cwd,
  });

  const resp = await respPromise;
  assert.equal(resp.status, 200);
  assert.equal(resp.body.sessionId, "born-2");

  // The watch can dictate IMMEDIATELY — the early-registered slot exists and
  // routes to the fork's inbox before the register ever lands.
  const inject = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { sessionId: "born-2", command: "hello from the wrist" },
  });
  assert.equal(inject.status, 200, `dictation must not 502 in the race window: ${JSON.stringify(inject.body)}`);
  const injected = await inbox.waitFor((e) => e.event === "inject");
  assert.equal(injected.parsed.sessionId, "born-2");
});

test("no fork connected → honest 409, no session, no PTY fallback", { timeout: 60_000 }, async (t) => {
  // Even with a stub claude available, the bridge must NOT fall back to a PTY.
  const binDir = tempDir(t, "acp-spawn-nofork-bin-");
  const bin = path.join(binDir, "claude");
  fs.writeFileSync(bin, "#!/bin/sh\necho READY\nexec cat\n", { mode: 0o755 });
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_CLAUDE_BIN: bin } });
  const token = await pair(bridge);

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude", cwd: os.homedir() },
  });
  assert.equal(resp.status, 409);
  assert.match(resp.body.error, /Zed/i, "the error names the actual fix (open Zed)");

  const status = await request(bridge.port, "GET", "/status", { token });
  assert.deepEqual(status.body.sessions, [], "no session slot of any species was created");
});

test("codex spawn ignores the ACP path entirely and still gets a PTY", { timeout: 60_000 }, async (t) => {
  const binDir = tempDir(t, "acp-spawn-codex-bin-");
  const bin = path.join(binDir, "codex");
  fs.writeFileSync(bin, "#!/bin/sh\necho CODEX-READY\nexec cat\n", { mode: 0o755 });
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_CODEX_BIN: bin } });
  const token = await pair(bridge);

  // A live inbox exists — codex must not be routed at it.
  const inbox = connectInbox(t, bridge, "fork-codex");
  assert.equal(await inbox.statusCode(), 200);

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "codex", cwd: os.homedir() },
  });
  assert.equal(resp.status, 200);
  assert.equal(resp.body.agent, "codex");
  assert.notEqual(resp.body.kind, "acp");

  const status = await request(bridge.port, "GET", "/status", { token });
  const slot = status.body.sessions.find((s) => s.id === resp.body.sessionId);
  assert.ok(slot, "codex session exists");
  assert.notEqual(slot.kind, "acp", "codex is a bridge-owned PTY, not an ACP slot");
});

test("timeout → 409 with the requestId; a LATE success still converges into a visible, attributable session", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_ACP_SPAWN_TIMEOUT_MS: "300" } });
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-spawn-late-");

  const inbox = connectInbox(t, bridge, "fork-late");
  assert.equal(await inbox.statusCode(), 200);

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude", cwd },
  });
  assert.equal(resp.status, 409, "the bridge gave up at the deadline");
  assert.ok(resp.body.spawnRequestId, "the timeout error carries the requestId for later attribution");
  const frame = await inbox.waitFor((e) => e.event === "spawn");
  assert.equal(frame.parsed.requestId, resp.body.spawnRequestId);

  // The watch is watching the stream when the slow fork finally delivers.
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  await registerAcp(bridge, { connection: "fork-late", sessionId: "born-late", cwd, detached: true });
  const late = await spawnResult(bridge, {
    connection: "fork-late", requestId: frame.parsed.requestId, ok: true, sessionId: "born-late", cwd,
  });
  assert.equal(late.status, 200);
  assert.equal(late.body.stale, true, "a late result is accepted, not errored at the fork");

  // Self-healing: the session appears over SSE, carrying the requestId the
  // watch was told had failed — "arrived late", not a mystery session.
  const ev = await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === "born-late" && e.parsed?.spawnRequestId,
  );
  assert.equal(ev.parsed.spawnRequestId, frame.parsed.requestId);
});

test("a fork failure (ok:false) surfaces the fork's own error", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-spawn-fail-");

  const inbox = connectInbox(t, bridge, "fork-fail");
  assert.equal(await inbox.statusCode(), 200);

  const respPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude", cwd },
  });
  const frame = await inbox.waitFor((e) => e.event === "spawn");
  await spawnResult(bridge, {
    connection: "fork-fail", requestId: frame.parsed.requestId, ok: false, error: "cwd exploded",
  });

  const resp = await respPromise;
  assert.equal(resp.status, 409);
  assert.match(resp.body.error, /cwd exploded/);
});

test("inbox death mid-request fails the spawn fast, not by timeout", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_ACP_SPAWN_TIMEOUT_MS: "8000" } });
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-spawn-dead-");

  const inbox = connectInbox(t, bridge, "fork-dead");
  assert.equal(await inbox.statusCode(), 200);

  const started = Date.now();
  const respPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude", cwd },
  });
  await inbox.waitFor((e) => e.event === "spawn");
  inbox.close(); // the fork dies (Zed quit)

  const resp = await respPromise;
  assert.equal(resp.status, 409);
  assert.ok(Date.now() - started < 5000, "settled by the inbox close, not the 8s timer");
});

test("spawn routes to the fork already hosting a running session in the SAME directory", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwdA = tempDir(t, "acp-spawn-route-a-");
  const cwdB = tempDir(t, "acp-spawn-route-b-");

  // Window A's fork connects FIRST (so newest-wins would pick B — the cwd
  // match must beat recency), and it hosts a running session in cwdA.
  const inboxA = connectInbox(t, bridge, "fork-window-a");
  assert.equal(await inboxA.statusCode(), 200);
  await registerAcp(bridge, { connection: "fork-window-a", sessionId: "zed-a", cwd: cwdA });
  const inboxB = connectInbox(t, bridge, "fork-window-b");
  assert.equal(await inboxB.statusCode(), 200);
  await registerAcp(bridge, { connection: "fork-window-b", sessionId: "zed-b", cwd: cwdB });

  void request(bridge.port, "POST", "/v1/command", { token, body: { spawn: "claude", cwd: cwdA } });

  const frame = await inboxA.waitFor((e) => e.event === "spawn");
  assert.equal(frame.parsed.cwd, cwdA, "the cwd-matching window's fork got the spawn");
});

test("desk pickup: /acp/claim atomically takes the newest pending for the cwd, once", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const cwd = tempDir(t, "acp-claim-");
  const other = tempDir(t, "acp-claim-other-");

  const inbox = connectInbox(t, bridge, "fork-claim");
  assert.equal(await inbox.statusCode(), 200);

  // Two pending pickups in cwd (older, then newer), one elsewhere.
  await registerAcp(bridge, { connection: "fork-claim", sessionId: "pick-old", cwd, detached: true });
  await new Promise((r) => setTimeout(r, 10)); // distinct createdAt
  await registerAcp(bridge, { connection: "fork-claim", sessionId: "pick-new", cwd, detached: true });
  await registerAcp(bridge, { connection: "fork-claim", sessionId: "pick-elsewhere", cwd: other, detached: true });

  const first = await request(bridge.port, "POST", "/acp/claim", { body: { connection: "fork-claim", cwd } });
  assert.equal(first.status, 200);
  assert.equal(first.body.sessionId, "pick-new", "newest pending for the cwd wins");

  const second = await request(bridge.port, "POST", "/acp/claim", { body: { connection: "fork-claim", cwd } });
  assert.equal(second.body.sessionId, "pick-old", "each claim takes exactly one");

  const third = await request(bridge.port, "POST", "/acp/claim", { body: { connection: "fork-claim", cwd } });
  assert.equal(third.body.sessionId, null, "the registry is empty for this cwd");

  const elsewhere = await request(bridge.port, "POST", "/acp/claim", { body: { connection: "fork-claim", cwd: other } });
  assert.equal(elsewhere.body.sessionId, "pick-elsewhere", "other directories are untouched");
});

test("an attach re-register (no detached flag) retires the pickup — Import Threads can't double-adopt", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const cwd = tempDir(t, "acp-claim-retire-");

  const inbox = connectInbox(t, bridge, "fork-retire");
  assert.equal(await inbox.statusCode(), 200);

  await registerAcp(bridge, { connection: "fork-retire", sessionId: "adopted-1", cwd, detached: true });
  // The user opened it via session/load (Import Threads path): the fork
  // re-registers WITHOUT the flag.
  await registerAcp(bridge, { connection: "fork-retire", sessionId: "adopted-1", cwd });

  const claim = await request(bridge.port, "POST", "/acp/claim", { body: { connection: "fork-retire", cwd } });
  assert.equal(claim.body.sessionId, null, "an adopted session is no longer claimable");
});

test("the pickup survives the fork's death (Zed quit) so a fresh fork can resume it", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-claim-survive-");

  const inbox = connectInbox(t, bridge, "fork-gen1");
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "fork-gen1", sessionId: "survivor", cwd, detached: true });

  // The watch is connected BEFORE the fork dies, so it observes the ending.
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === "survivor" && e.parsed?.state === "running",
  );

  // Zed quits: the slot ends…
  inbox.close();
  await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === "survivor" && e.parsed?.state === "ended",
  );

  // …but a NEW fork generation (Zed reopened) can still claim the pickup and
  // resume the session from disk under the same id.
  const inbox2 = connectInbox(t, bridge, "fork-gen2");
  assert.equal(await inbox2.statusCode(), 200);
  const claim = await request(bridge.port, "POST", "/acp/claim", { body: { connection: "fork-gen2", cwd } });
  assert.equal(claim.body.sessionId, "survivor");

  // The resume's register revives the same slot.
  await registerAcp(bridge, { connection: "fork-gen2", sessionId: "survivor", cwd });
  const revived = await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === "survivor" && e.parsed?.state === "running",
  );
  assert.ok(revived, "the slot came back under the same id");
});

test("a DETACHED session's permission is registered with zero SSE clients and replayed on connect", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-perm-detached-");

  const inbox = connectInbox(t, bridge, "fork-perm");
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "fork-perm", sessionId: "det-perm", cwd, detached: true });

  // NO watch is connected when the permission fires — the wrist is the only
  // surface for a detached session, so the card must be parked for replay.
  const raise = await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "fork-perm",
      sessionId: "det-perm",
      kind: "permission",
      payload: {
        toolCall: { toolCallId: "tc-det", title: "Bash", rawInput: { command: "ls" } },
        options: [
          { optionId: "opt-allow", kind: "allow_once", name: "Allow" },
          { optionId: "opt-deny", kind: "reject_once", name: "Deny" },
        ],
      },
    },
  });
  assert.equal(raise.status, 200);

  // The watch connects late: pendingPermissionsSync replays the card.
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  const card = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.sessionId === "det-perm",
  );
  assert.ok(card.parsed.permissionId, "the parked card replays with its id");

  // Answering it sends the fork the AGENT'S option id down the inbox.
  const answer = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { permissionId: card.parsed.permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(answer.status, 200);
  const decision = await inbox.waitFor((e) => e.event === "permission-decision");
  assert.equal(decision.parsed.optionId, "opt-allow");
  assert.equal(decision.parsed.sessionId, "det-perm");
});

test("an ATTACHED session's permission with zero SSE clients still stays Zed-only (no parked card)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-perm-attached-");

  const inbox = connectInbox(t, bridge, "fork-perm-att");
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "fork-perm-att", sessionId: "att-perm", cwd });

  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "fork-perm-att",
      sessionId: "att-perm",
      kind: "permission",
      payload: {
        toolCall: { toolCallId: "tc-att", title: "Bash", rawInput: {} },
        options: [{ optionId: "opt-a", kind: "allow_once", name: "Allow" }],
      },
    },
  });

  // A late-connecting watch sees NO card: Zed's own dialog owns the decision,
  // and a parked card nobody could see would just expire into noise.
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  const replayed = await sse
    .waitFor((e) => e.event === "permission-request" && e.parsed?.sessionId === "att-perm", 1500)
    .catch(() => null);
  assert.equal(replayed, null, "no parked card for an attached session raised with zero clients");
});
