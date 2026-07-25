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

// #79: assistant prose is the thing hooks never carried. It arrives as ACP
// `agent_message_chunk` updates (assistant-only — the adapter emits no
// user_message_chunk), which stream in many small deltas. Pushing one SSE frame
// per delta is ~100 radio wakeups a turn for content the wrist does not want, so
// prose is COALESCED and flushed once per turn. See the flush rules below.
test("ACP prose is coalesced and flushed once at turn end, not streamed (#79)", { timeout: 60_000 }, async (t) => {
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
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-prose");

  const chunk = (text) =>
    request(bridge.port, "POST", "/acp/update", {
      body: {
        connection: "conn-prose", sessionId: "acp-prose", kind: "session_update",
        payload: {
          sessionId: "acp-prose",
          update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text } },
        },
      },
    });

  await chunk("on ");
  await chunk("it");
  assert.equal(
    sse.events.filter((e) => e.event === "message").length,
    0,
    "mid-turn deltas must not each push a frame",
  );

  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-prose", sessionId: "acp-prose", kind: "turn",
      payload: { phase: "end", stopReason: "end_turn" },
    },
  });

  const ev = await sse.waitFor((e) => e.event === "message" && e.parsed?.sessionId === "acp-prose");
  assert.equal(ev.parsed.text, "on it", "the turn's prose arrives as ONE coalesced message");
  assert.equal(ev.parsed.role, "assistant");
});

// "The last message" means the final prose block, not a transcript of the whole
// turn: narration before a tool call is superseded by whatever is said after it.
test("a tool call resets the prose buffer so only the final block is sent (#79)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "prose2");

  const inbox = connectInbox(bridge, "conn-prose2");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-prose2", sessionId: "acp-prose2", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-prose2");

  const send = (update) =>
    request(bridge.port, "POST", "/acp/update", {
      body: {
        connection: "conn-prose2", sessionId: "acp-prose2", kind: "session_update",
        payload: { sessionId: "acp-prose2", update },
      },
    });

  await send({ sessionUpdate: "agent_message_chunk", content: { type: "text", text: "let me check" } });
  await send({ sessionUpdate: "tool_call", toolCallId: "t1", title: "Bash" });
  await send({ sessionUpdate: "agent_message_chunk", content: { type: "text", text: "all 211 pass" } });

  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-prose2", sessionId: "acp-prose2", kind: "turn",
      payload: { phase: "end", stopReason: "end_turn" },
    },
  });

  const ev = await sse.waitFor((e) => e.event === "message" && e.parsed?.sessionId === "acp-prose2");
  assert.equal(ev.parsed.text, "all 211 pass", "pre-tool narration is superseded");
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

// Re-registration happens on every Zed restart / session resume and on every
// fork reconnect. It is a re-ANNOUNCEMENT, not new work: an idle session that
// gets re-announced has not started a turn, so forcing it back to "working"
// left the wrist showing green for a session where nothing was happening.
test("re-registering an idle ACP session does not resurrect it as working (#79)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "reannounce");

  const inbox = connectInbox(bridge, "conn-reann");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-reann", sessionId: "acp-reann", cwd });

  // A turn runs and ends: the slot is idle.
  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-reann", sessionId: "acp-reann", kind: "turn",
      payload: { phase: "end", stopReason: "end_turn" },
    },
  });
  assert.equal((await statusEntry(bridge, token, "acp-reann")).idle, true);

  // Zed restarts: the fork reconnects and re-announces the same session.
  await registerAcp(bridge, { connection: "conn-reann2", sessionId: "acp-reann", cwd });

  assert.equal(
    (await statusEntry(bridge, token, "acp-reann")).idle,
    true,
    "a re-announced idle session must stay idle — nothing started",
  );
});

// A bridge restart rebuilds the session table from the fork's re-announce, so
// the slot is brand new and has no idle flag to preserve. The fork reports
// whether a turn is in flight; without that the bridge had to guess, and
// guessing "working" showed green for a thread sitting idle.
test("a re-announced session with no turn in flight is idle, not working (#79)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "revive");

  const inbox = connectInbox(bridge, "conn-revive");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);

  // Fresh bridge, fork re-announces an idle session.
  const res = await request(bridge.port, "POST", "/acp/register", {
    body: { connection: "conn-revive", sessionId: "acp-revive", sdkSessionId: "acp-revive", cwd, active: false },
  });
  assert.equal(res.status, 200);
  assert.equal(
    (await statusEntry(bridge, token, "acp-revive")).idle,
    true,
    "no turn in flight means idle",
  );

  // And one announced mid-turn is working.
  await request(bridge.port, "POST", "/acp/register", {
    body: { connection: "conn-revive", sessionId: "acp-busy", sdkSessionId: "acp-busy", cwd, active: true },
  });
  assert.equal((await statusEntry(bridge, token, "acp-busy")).idle, undefined);
});

// --- ACP permissions on the wrist (#80) -------------------------------------
// The fork mirrors its `requestPermission` RPC here. Zed shows its own prompt
// regardless; this raises the SAME decision on the watch so it can be answered
// from the wrist, and whichever surface answers first wins.
test("an ACP permission request raises a watch prompt (#80)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "perm");

  const inbox = connectInbox(bridge, "conn-perm");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-perm", sessionId: "acp-perm", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-perm");

  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-perm",
      sessionId: "acp-perm",
      kind: "permission",
      payload: {
        sessionId: "acp-perm",
        toolCall: { toolCallId: "tc-1", title: "Bash", rawInput: { command: "rm -rf build" } },
        options: [
          { optionId: "allow", name: "Allow", kind: "allow_once" },
          { optionId: "reject", name: "Reject", kind: "reject_once" },
        ],
      },
    },
  });

  const ev = await sse.waitFor((e) => e.event === "permission-request" && e.parsed?.sessionId === "acp-perm");
  assert.ok(ev.parsed.permissionId, "the prompt must carry a permissionId the watch can answer with");
  assert.equal(ev.parsed.tool_name, "Bash");
  assert.deepEqual(ev.parsed.tool_input, { command: "rm -rf build" });
  // Canonical machine-readable semantics, never inferred from label wording.
  assert.deepEqual(
    ev.parsed.options.map((o) => o.behavior),
    ["allow", "deny"],
  );
});

// The wrist answer has to reach the agent, and the only downlink to the fork is
// the inbox SSE. The decision names the ACP optionId the AGENT offered, not one
// synthesised from the behavior — the agent owns its own option vocabulary.
test("answering an ACP prompt on the watch sends the decision down the fork's inbox (#80)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "permans");

  const inbox = connectInbox(bridge, "conn-permans");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-permans", sessionId: "acp-permans", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-permans");

  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-permans",
      sessionId: "acp-permans",
      kind: "permission",
      payload: {
        sessionId: "acp-permans",
        toolCall: { toolCallId: "tc-9", title: "Bash", rawInput: { command: "ls" } },
        options: [
          { optionId: "zed-allow", name: "Allow", kind: "allow_once" },
          { optionId: "zed-reject", name: "Reject", kind: "reject_once" },
        ],
      },
    },
  });

  const prompt = await sse.waitFor((e) => e.event === "permission-request");

  const answer = await request(bridge.port, "POST", "/command", {
    token,
    body: { permissionId: prompt.parsed.permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(answer.status, 200);

  const frame = await inbox.waitFor((e) => e.event === "permission-decision");
  assert.equal(frame.parsed.toolCallId, "tc-9");
  assert.equal(frame.parsed.optionId, "zed-allow", "must name the agent's own optionId");
  assert.equal(frame.parsed.behavior, "allow");
});

// If the user answers in Zed, the wrist card must go away. Otherwise it sits
// there as a zombie whose eventual answer applies to a decided request.
test("answering in Zed retracts the wrist prompt (#80)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "permzed");

  const inbox = connectInbox(bridge, "conn-permzed");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-permzed", sessionId: "acp-permzed", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-permzed");

  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-permzed", sessionId: "acp-permzed", kind: "permission",
      payload: {
        sessionId: "acp-permzed",
        toolCall: { toolCallId: "tc-z", title: "Bash", rawInput: { command: "ls" } },
        options: [{ optionId: "a", name: "Allow", kind: "allow_once" }],
      },
    },
  });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");

  // The fork reports the request was settled elsewhere.
  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-permzed", sessionId: "acp-permzed", kind: "permission-resolved",
      payload: { sessionId: "acp-permzed", toolCallId: "tc-z" },
    },
  });

  const cleared = await sse.waitFor((e) => e.event === "permission-cleared");
  assert.equal(cleared.parsed.permissionId, prompt.parsed.permissionId);
});

// A bridge restart rebuilds the slot from the fork's re-announce. Without the
// title on that payload the watch showed the raw uuid until the NEXT turn end,
// because the adapter only pushes session_info_update when the title CHANGES.
test("re-announce carries the session title, so a bridge restart doesn't show a uuid (#79)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "retitle");

  const inbox = connectInbox(bridge, "conn-retitle");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);

  const res = await request(bridge.port, "POST", "/acp/register", {
    body: {
      connection: "conn-retitle", sessionId: "acp-retitle", sdkSessionId: "acp-retitle",
      cwd, active: false, title: "Fix the flaky auth tests",
    },
  });
  assert.equal(res.status, 200);
  assert.equal((await statusEntry(bridge, token, "acp-retitle")).title, "Fix the flaky auth tests");
});

// The initial register races a turn that starts immediately after it. If a
// stale active:false lands after the turn-start boundary it flips a working
// session back to idle — which is what showed an idle watch mid-turn.
test("a re-register never clobbers turn state the bridge already tracked (#79)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "clobber");

  const inbox = connectInbox(bridge, "conn-clobber");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-clobber", sessionId: "acp-clobber", cwd });

  // A turn is running.
  await request(bridge.port, "POST", "/acp/update", {
    body: { connection: "conn-clobber", sessionId: "acp-clobber", kind: "turn", payload: { phase: "start" } },
  });
  assert.equal((await statusEntry(bridge, token, "acp-clobber")).idle, undefined);

  // A late register, minted before the turn began, must not idle it.
  await request(bridge.port, "POST", "/acp/register", {
    body: {
      connection: "conn-clobber", sessionId: "acp-clobber", sdkSessionId: "acp-clobber",
      cwd, active: false,
    },
  });
  assert.equal(
    (await statusEntry(bridge, token, "acp-clobber")).idle,
    undefined,
    "a stale register must not flip a working session to idle",
  );
});
