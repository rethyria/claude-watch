// ACP loopback channel (issue #77): the forked claude-agent-acp launched by Zed
// registers its sessions with the bridge, the bridge represents them as
// dictatable external slots, watch dictation is injected down the fork's inbox
// SSE, and lifecycle (deregister / fork-disconnect) ends the slot with no
// zombie left behind. Black-box against the real bridge, exactly as the fork
// and a watch client drive it: /acp/* over loopback, /command + /events authed.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import http from "node:http";
import { startBridge, request, connectSse } from "./helpers.js";

async function pair(bridge) {
  const res = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(res.status, 200);
  return res.body.token;
}

function realCwd(t, name) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `acp-${name}-`));
  t.after(() => { try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* ignore */ } });
  return dir;
}

// Open the fork's inbox SSE (loopback, no auth) and return a handle that also
// exposes the raw request so tests can close it to simulate a fork death.
function connectInbox(bridge, connectionId) {
  const sse = connectSse(bridge.port, undefined, { path: `/acp/inbox?connection=${connectionId}` });
  return sse;
}

async function registerAcp(bridge, { connection, sessionId, cwd }) {
  const res = await request(bridge.port, "POST", "/acp/register", {
    body: { connection, sessionId, sdkSessionId: sessionId, cwd },
  });
  assert.equal(res.status, 200);
  return res;
}

async function statusEntry(bridge, token, sessionId) {
  const status = await request(bridge.port, "GET", "/status", { token });
  assert.equal(status.status, 200);
  return status.body.sessions.find((s) => s.id === sessionId);
}

test("register surfaces a dictatable, external ACP slot (SSE + REST agree)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "reg");

  const inbox = connectInbox(bridge, "conn-reg");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);

  await registerAcp(bridge, { connection: "conn-reg", sessionId: "acp-reg", cwd });

  // A watch pairing now sees it in the connect-time snapshot as a running,
  // external (Hide-not-Kill), dictatable, kind:"acp" session.
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  const ev = await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-reg");
  assert.equal(ev.parsed.state, "running");
  assert.equal(ev.parsed.external, true, `ACP slot must report external; got ${JSON.stringify(ev.parsed)}`);
  assert.equal(ev.parsed.kind, "acp");
  assert.equal(ev.parsed.dictatable, true);

  const entry = await statusEntry(bridge, token, "acp-reg");
  assert.ok(entry, "ACP session present in /status");
  assert.equal(entry.external, true);
  assert.equal(entry.kind, "acp");
  assert.equal(entry.dictatable, true);
});

test("dictation to an ACP session is injected down the fork's inbox (not a headless fork)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "inject");

  const inbox = connectInbox(bridge, "conn-inj");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-inj", sessionId: "acp-inj", cwd });

  const resp = await request(bridge.port, "POST", "/command", {
    token,
    body: { sessionId: "acp-inj", command: "add tests to the parser\n" },
  });
  assert.equal(resp.status, 200);
  assert.equal(resp.body.prompt, true, "the ACP session took the inject branch");

  // The inbox receives the inject frame the fork's injectUserPrompt consumes.
  const frame = await inbox.waitFor((e) => e.event === "inject");
  assert.equal(frame.parsed.sessionId, "acp-inj");
  assert.equal(frame.parsed.text, "add tests to the parser");
  assert.equal(frame.parsed.source, "watch");
});

test("dictation to an ACP session whose fork is not connected is refused honestly (502, no draft loss)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "noinbox");

  // Register WITHOUT ever opening the inbox: the fork announced the session but
  // its downlink is not up.
  await registerAcp(bridge, { connection: "conn-gone", sessionId: "acp-gone", cwd });

  const resp = await request(bridge.port, "POST", "/command", {
    token,
    body: { sessionId: "acp-gone", command: "hello\n" },
  });
  assert.equal(resp.status, 502, "unreachable ACP fork must not fake a delivery");
  assert.match(resp.body.error, /not reachable|not connected/i);
});

test("hook-twin correlation: one ACP session yields exactly one bridge slot", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "dedup");

  const inbox = connectInbox(bridge, "conn-dedup");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-dedup", sessionId: "acp-dedup", cwd });

  // The fork's SDK session fires the settings.json hooks with the SAME
  // session_id (== the ACP session id). Without correlation this would mint a
  // SECOND external slot. It must resolve to the one ACP slot instead.
  const toolOut = await request(bridge.port, "POST", "/hooks/tool-output", {
    body: { session_id: "acp-dedup", cwd, tool_name: "Read", tool_output: "hi" },
  });
  assert.equal(toolOut.status, 200);

  const status = await request(bridge.port, "GET", "/status", { token });
  const forThisCwd = status.body.sessions.filter((s) => s.cwd === cwd);
  assert.equal(forThisCwd.length, 1, `exactly one slot for the ACP session; got ${JSON.stringify(forThisCwd)}`);
  assert.equal(forThisCwd[0].id, "acp-dedup");
  assert.equal(forThisCwd[0].kind, "acp");

  // And the hook drives its turn state: a Stop idles the SAME slot.
  await request(bridge.port, "POST", "/hooks/stop", { body: { session_id: "acp-dedup", cwd } });
  const entry = await statusEntry(bridge, token, "acp-dedup");
  assert.equal(entry.idle, true, "the ACP slot's idle is driven by its correlated hooks");
});

test("explicit deregister ends the ACP slot", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "dereg");

  const inbox = connectInbox(bridge, "conn-dereg");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-dereg", sessionId: "acp-dereg", cwd });
  assert.ok(await statusEntry(bridge, token, "acp-dereg"), "registered");

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-dereg" && e.parsed?.state === "running");

  const res = await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "conn-dereg", sessionId: "acp-dereg", reason: "query-closed" },
  });
  assert.equal(res.status, 200);

  const ended = await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-dereg" && e.parsed?.state === "ended");
  assert.equal(ended.parsed.state, "ended");
});

test("a fork whose inbox drops (Zed quit / crash) strands no zombie slot", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "zombie");

  const inbox = connectInbox(bridge, "conn-zombie");
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-zombie", sessionId: "acp-zombie", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-zombie" && e.parsed?.state === "running");

  // The fork dies: the inbox socket closes with no graceful deregister. The
  // bridge must end the slot on that drop, not leave it running forever.
  inbox.close();

  const ended = await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === "acp-zombie" && e.parsed?.state === "ended",
  );
  assert.match(ended.parsed.reason, /disconnect/i);
});

test("dictatable is live-delivery only: PTY yes+killable, hook no, ACP yes+hide (S4 #78)", { timeout: 60_000 }, async (t) => {
  // A stub claude so a spawn produces a real, bridge-owned PTY session.
  const binDir = fs.mkdtempSync(path.join(os.tmpdir(), "acp-fakebin-"));
  t.after(() => { try { fs.rmSync(binDir, { recursive: true, force: true }); } catch { /* ignore */ } });
  const bin = path.join(binDir, "claude");
  fs.writeFileSync(bin, "#!/bin/sh\necho READY\nexec cat\n", { mode: 0o755 });

  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_CLAUDE_BIN: bin } });
  const token = await pair(bridge);

  // (1) A bridge-owned PTY session: dictatable (stdin), NOT external (real kill).
  const spawned = await request(bridge.port, "POST", "/command", { token, body: { spawn: "claude", cwd: os.homedir() } });
  assert.equal(spawned.status, 200);
  const ptyEntry = await statusEntry(bridge, token, spawned.body.sessionId);
  assert.ok(ptyEntry, "spawned PTY session present");
  assert.equal(ptyEntry.dictatable, true, "a bridge-owned PTY session is dictatable");
  assert.notEqual(ptyEntry.external, true, "a PTY session is NOT external (real kill)");

  // (2) A PTY-less external hook session: NOT dictatable (only the retired
  // headless fork could reach it), external (Hide).
  const hookCwd = realCwd(t, "hook");
  await request(bridge.port, "POST", "/hooks/tool-output", {
    body: { session_id: "hook-1", cwd: hookCwd, tool_name: "Read", tool_output: "hi" },
  });
  const hookEntry = (await request(bridge.port, "GET", "/status", { token })).body.sessions.find((s) => s.cwd === hookCwd);
  assert.ok(hookEntry, "hook session present");
  assert.notEqual(hookEntry.dictatable, true, "a PTY-less hook session is NOT dictatable");
  assert.equal(hookEntry.external, true, "a hook session is external (Hide)");

  // (3) An ACP session: dictatable (inject) AND external (Hide, not a fake Kill).
  const inbox = connectInbox(bridge, "conn-disc");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-disc", sessionId: "acp-disc", cwd: realCwd(t, "disc") });
  const acpEntry = await statusEntry(bridge, token, "acp-disc");
  assert.equal(acpEntry.dictatable, true, "an ACP session is dictatable");
  assert.equal(acpEntry.external, true, "an ACP session is external (Hide)");
  assert.equal(acpEntry.kind, "acp");
});

test("ACP endpoints validate their inputs (routes are wired, not 404)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  // A wired-but-invalid request 400s; a 404 would mean the route table missed
  // it entirely. (The loopback gate itself keys on the socket remote address,
  // which is 127.0.0.1 in-test, so it can't be exercised black-box here.)
  const bad = await request(bridge.port, "POST", "/acp/register", { body: { connection: "c" } });
  assert.equal(bad.status, 400, "register requires sessionId");
  const badInbox = await request(bridge.port, "GET", "/acp/inbox");
  assert.equal(badInbox.status, 400, "inbox requires a connection id");
});

// --- Turn-level idle for ACP slots (#79 re-scope / #83) ----------------------
// The ACP `sessionUpdate` union carries no turn-boundary variant (turn end is
// the session/prompt RPC's `stopReason`), so the fork forwards it explicitly as
// kind:"turn". Without it an ACP slot is never flagged idle or working — the
// only writers of `slot.idle` are the hook channel and the headless path, and
// the user has removed the hooks block. NOTE: `state` deliberately stays
// "running" across a finished turn (issue #60); `idle` is the turn-level truth.
test("an ACP slot is flagged idle at turn end, with no hook traffic (#83)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "turn");

  const inbox = connectInbox(bridge, "conn-turn");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);

  await registerAcp(bridge, { connection: "conn-turn", sessionId: "acp-turn", cwd });

  // A freshly registered slot is working, not idle.
  const fresh = await statusEntry(bridge, token, "acp-turn");
  assert.equal(fresh.idle, undefined, "a just-registered ACP slot must not be idle");

  // The fork reports the turn settled.
  const res = await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-turn",
      sessionId: "acp-turn",
      kind: "turn",
      payload: { phase: "end", stopReason: "end_turn" },
    },
  });
  assert.equal(res.status, 200);

  // `state` keeps its #60 semantics; `idle` carries the turn-level truth.
  const settled = await statusEntry(bridge, token, "acp-turn");
  assert.equal(settled.state, "running", "state must NOT be repurposed (issue #60)");
  assert.equal(settled.idle, true, "turn end must flag the ACP slot idle");
});

test("a new turn clears the ACP slot's idle flag (#83)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "turn2");

  const inbox = connectInbox(bridge, "conn-turn2");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);

  await registerAcp(bridge, { connection: "conn-turn2", sessionId: "acp-turn2", cwd });

  const turn = (phase, stopReason) =>
    request(bridge.port, "POST", "/acp/update", {
      body: { connection: "conn-turn2", sessionId: "acp-turn2", kind: "turn", payload: { phase, stopReason } },
    });

  await turn("end", "end_turn");
  assert.equal((await statusEntry(bridge, token, "acp-turn2")).idle, true);

  // Dictation (or the user typing in Zed) starts a fresh turn: the slot is
  // working again, so the stale idle flag must not ride the next snapshot.
  await turn("start");
  assert.equal(
    (await statusEntry(bridge, token, "acp-turn2")).idle,
    undefined,
    "a new turn must clear idle",
  );
});

// #84: `dictatable` is DERIVED from kind alone, so it survived the slot ending.
// Delivery was always honest (injectToAcpSession 502s once the connection
// binding is gone) — the defect is that the watch would OFFER Dictate on a dead
// session and then eat the 502.
test("an ended ACP slot stops advertising dictatable (#84)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "ended");

  const inbox = connectInbox(bridge, "conn-ended");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);

  await registerAcp(bridge, { connection: "conn-ended", sessionId: "acp-ended", cwd });
  assert.equal((await statusEntry(bridge, token, "acp-ended")).dictatable, true);

  const res = await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "conn-ended", sessionId: "acp-ended", reason: "query-closed" },
  });
  assert.equal(res.status, 200);

  const ended = await statusEntry(bridge, token, "acp-ended");
  assert.equal(ended.state, "ended");
  assert.equal(ended.dictatable, undefined, "an ended slot must not offer Dictate");
});

// #79: assistant prose is the thing hooks never carried. It arrives as the ACP
// `agent_message_chunk` update (assistant-only — the adapter emits no
// user_message_chunk), and is fanned out as a NEW additive `message` event so
// older clients, which ignore unknown events, are unaffected.
test("ACP assistant prose is fanned out as an additive `message` SSE event (#79)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "prose");

  const inbox = connectInbox(bridge, "conn-prose");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);

  await registerAcp(bridge, { connection: "conn-prose", sessionId: "acp-prose", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  const res = await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-prose",
      sessionId: "acp-prose",
      kind: "session_update",
      payload: {
        sessionId: "acp-prose",
        update: {
          sessionUpdate: "agent_message_chunk",
          content: { type: "text", text: "on it" },
        },
      },
    },
  });
  assert.equal(res.status, 200);

  const ev = await sse.waitFor((e) => e.event === "message" && e.parsed?.sessionId === "acp-prose");
  assert.equal(ev.parsed.text, "on it");
  assert.equal(ev.parsed.role, "assistant");
});

// A connected watch learns "turn ended" from a pushed event, not from a flag.
// markSessionIdle only sets `idle`, which by design rides the NEXT session
// event; hook sessions got that push from the Stop hook. ACP had no equivalent,
// so a live watch sat on green forever.
test("ACP turn end pushes a session event carrying idle, so a live watch updates", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "idlepush");

  const inbox = connectInbox(bridge, "conn-idlepush");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-idlepush", sessionId: "acp-idlepush", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-idlepush");

  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-idlepush",
      sessionId: "acp-idlepush",
      kind: "turn",
      payload: { phase: "end", stopReason: "end_turn" },
    },
  });

  const ev = await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === "acp-idlepush" && e.parsed?.idle === true,
  );
  assert.equal(ev.parsed.state, "running", "state keeps its #60 semantics");
  assert.equal(ev.parsed.idle, true);
});

// The adapter already pushes the SDK's auto-generated thread title as a
// `session_info_update` at each turn end — the bridge was discarding it, so the
// watch fell back to showing the raw session uuid.
test("ACP session_info_update sets the slot title and announces it (#79)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "title");

  const inbox = connectInbox(bridge, "conn-title");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-title", sessionId: "acp-title", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-title");

  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-title",
      sessionId: "acp-title",
      kind: "session_update",
      payload: {
        sessionId: "acp-title",
        update: { sessionUpdate: "session_info_update", title: "Fix the flaky auth tests" },
      },
    },
  });

  const ev = await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === "acp-title" && e.parsed?.title,
  );
  assert.equal(ev.parsed.title, "Fix the flaky auth tests");
  assert.equal((await statusEntry(bridge, token, "acp-title")).title, "Fix the flaky auth tests");
});
