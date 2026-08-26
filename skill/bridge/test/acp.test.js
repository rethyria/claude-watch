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

test("dictatable is live-delivery only: PTY yes+killable, scanner no, ACP yes+hide (S4 #78)", { timeout: 60_000 }, async (t) => {
  // A stub codex so a spawn produces a real, bridge-owned PTY session, plus a
  // scanner rollout fixture for the PTY-less external class.
  const binDir = fs.mkdtempSync(path.join(os.tmpdir(), "acp-fakebin-"));
  t.after(() => { try { fs.rmSync(binDir, { recursive: true, force: true }); } catch { /* ignore */ } });
  const bin = path.join(binDir, "codex");
  fs.writeFileSync(bin, "#!/bin/sh\necho READY\nexec cat\n", { mode: 0o755 });
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "acp-scanner-home-"));
  t.after(() => { try { fs.rmSync(home, { recursive: true, force: true }); } catch { /* ignore */ } });
  const rolloutDir = path.join(home, ".codex", "sessions", "2026", "08", "07");
  fs.mkdirSync(rolloutDir, { recursive: true });

  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_CODEX_BIN: bin, HOME: home } });
  const token = await pair(bridge);

  // (1) A bridge-owned PTY session: dictatable (stdin), NOT external (real
  // kill). Spawned as CODEX: every claude path is ACP-only now — the explicit
  // spawn action since the Zed pivot, and the dictate-with-no-session site
  // since #91 — so codex is the last agent that can produce a bridge PTY.
  const spawned = await request(bridge.port, "POST", "/command", { token, body: { command: "hello\n", agent: "codex", cwd: os.homedir() } });
  assert.equal(spawned.status, 200);
  assert.equal(spawned.body.spawned, true, "the command auto-spawned a PTY session");
  const ptyEntry = await statusEntry(bridge, token, spawned.body.sessionId);
  assert.ok(ptyEntry, "spawned PTY session present");
  assert.equal(ptyEntry.dictatable, true, "a bridge-owned PTY session is dictatable");
  assert.notEqual(ptyEntry.external, true, "a PTY session is NOT external (real kill)");

  // (2) A PTY-less Codex-scanner session: NOT dictatable (the bridge owns no
  // input channel into it — only the retired headless fork could pretend to).
  // Written AFTER the spawn above, so the no-session-id fallback there could
  // not resolve to this PTY-less slot instead of auto-spawning.
  fs.appendFileSync(
    path.join(rolloutDir, "rollout-disc.jsonl"),
    `${JSON.stringify({ type: "session_meta", payload: { id: "cdx-disc", cwd: home, timestamp: new Date().toISOString() } })}\n`,
  );
  const scannerDeadline = Date.now() + 30_000;
  let scannerEntry = null;
  while (Date.now() < scannerDeadline && !scannerEntry) {
    scannerEntry = await statusEntry(bridge, token, "cdx-disc");
    if (!scannerEntry) await new Promise((r) => setTimeout(r, 200));
  }
  assert.ok(scannerEntry, "scanner session present");
  assert.notEqual(scannerEntry.dictatable, true, "a PTY-less scanner session is NOT dictatable");

  // (3) An ACP session: dictatable (inject) AND external — the bridge owns no
  // process of its own here; a kill goes out as a close frame (#88).
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
// the session/prompt RPC's `stopReason`), so the fork forwards it explicitly
// as kind:"turn" — the SOLE writer of an ACP slot's `slot.idle` since the
// hook channel's retirement (#87). NOTE: `state` deliberately stays "running"
// across a finished turn (issue #60); `idle` is the turn-level truth.
test("an ACP slot is flagged idle at turn end (#83)", { timeout: 60_000 }, async (t) => {
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

// A rename in Zed's UI never crosses ACP (#112: Zed keeps it in its own
// thread store), so the wrist-visible rename is the in-thread /rename
// command: the adapter's turn-end poll sees the changed transcript title and
// pushes a fresh session_info_update. A CHANGED title must behave exactly
// like the first one — update the slot and re-announce — or the wrist keeps
// the stale label until a bridge restart.
test("a mid-session title change re-announces the slot (#112)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "rename");

  const inbox = connectInbox(bridge, "conn-rename");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-rename", sessionId: "acp-rename", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-rename");

  const pushTitle = (title) =>
    request(bridge.port, "POST", "/acp/update", {
      body: {
        connection: "conn-rename",
        sessionId: "acp-rename",
        kind: "session_update",
        payload: {
          sessionId: "acp-rename",
          update: { sessionUpdate: "session_info_update", title },
        },
      },
    });

  await pushTitle("Curve navigation dots");
  await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.title === "Curve navigation dots",
  );

  // The user runs /rename; the next turn-end poll pushes the new title.
  await pushTitle("Watch bug hunt");
  const renamed = await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.title === "Watch bug hunt",
  );
  assert.equal(renamed.parsed.sessionId, "acp-rename");
  assert.equal((await statusEntry(bridge, token, "acp-rename")).title, "Watch bug hunt");
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

// A fork death is the one exit where the adapter can never send
// permission-resolved (Zed quit, SIGKILL — the inbox just drops), so the
// session's end is the only signal left to void the card by. Without it the
// card sat pending the full expiry window, replayed to every reconnecting
// watch from the connect-time snapshot, and answering it 200-ok'd a decision
// frame into a connection that no longer existed.
test("a session's death expires its pending permission card (#119)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "permdead");

  const inbox = connectInbox(bridge, "conn-permdead");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-permdead", sessionId: "acp-permdead", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-permdead");

  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-permdead", sessionId: "acp-permdead", kind: "permission",
      payload: {
        sessionId: "acp-permdead",
        toolCall: { toolCallId: "tc-dead", title: "Bash", rawInput: { command: "ls" } },
        options: [
          { optionId: "allow", name: "Allow", kind: "allow_once" },
          { optionId: "reject", name: "Reject", kind: "reject_once" },
        ],
      },
    },
  });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");

  // Zed quits: the fork's inbox drops with no graceful deregister.
  inbox.close();
  await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === "acp-permdead" && e.parsed?.state === "ended",
  );

  const cleared = await sse.waitFor((e) => e.event === "permission-cleared");
  assert.equal(cleared.parsed.permissionId, prompt.parsed.permissionId);

  // A watch connecting after the death gets the authoritative snapshot with
  // the card already gone — not a replayed prompt for a dead session.
  const late = connectSse(bridge.port, token);
  t.after(() => late.close());
  assert.equal(await late.statusCode(), 200);
  const sync = await late.waitFor((e) => e.event === "permission-sync");
  assert.equal(
    sync.parsed.permissionIds.includes(prompt.parsed.permissionId), false,
    "the dead session's card must not replay to reconnecting watches",
  );

  // And answering the voided card is refused, never 200-ok'd into the void.
  const answer = await request(bridge.port, "POST", "/command", {
    token,
    body: { permissionId: prompt.parsed.permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(answer.status, 404, "an answer to a dead session's card must not report success");
});

// --- AskUserQuestion input-requests (#111) ----------------------------------
// AskUserQuestion rides Zed's form elicitation, which is a client-bound
// REQUEST the adapter's client tee never mirrors — so the adapter raises an
// explicit `input-request` frame and the bridge reshapes it into the HOOK-ERA
// question-card wire shape the watch already renders: a `permission-request`
// with tool_name "AskUserQuestion", NO top-level options, and the questions in
// tool_input.questions. The wrist's answers ride back as a positional
// `input-decision` frame; `input-resolved` and session end retract the card.

const ASK_QUESTIONS = [
  {
    question: "Favorite color?",
    header: "Color",
    options: [{ label: "Blue" }, { label: "Green", description: "calming" }],
    multiSelect: false,
  },
  {
    question: "Tabs or spaces?",
    header: "Style",
    options: [{ label: "Tabs" }, { label: "Spaces" }],
    multiSelect: false,
  },
];

async function raiseInputRequest(bridge, { connection, sessionId, toolCallId }) {
  const res = await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection, sessionId, kind: "input-request",
      payload: { sessionId, toolCallId, questions: ASK_QUESTIONS },
    },
  });
  assert.equal(res.status, 200);
}

test("an ACP input-request raises the hook-era question card, and the snapshot replays it (#111)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "ask");

  const inbox = connectInbox(bridge, "conn-ask");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-ask", sessionId: "acp-ask", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-ask");

  await raiseInputRequest(bridge, { connection: "conn-ask", sessionId: "acp-ask", toolCallId: "tc-q1" });

  const ev = await sse.waitFor((e) => e.event === "permission-request" && e.parsed?.sessionId === "acp-ask");
  assert.ok(ev.parsed.permissionId, "the card must carry a permissionId the watch can answer with");
  assert.equal(ev.parsed.tool_name, "AskUserQuestion");
  // The question list rides verbatim — headers, descriptions, multiSelect —
  // and there is NO top-level options menu: question prompts are content, not
  // permission gates (PROTOCOL.md), which is what routes the wear side to its
  // existing question card with zero client changes.
  assert.deepEqual(ev.parsed.tool_input, { questions: ASK_QUESTIONS });
  assert.equal("options" in ev.parsed, false);

  // A client connecting late (or reconnecting) gets the pending card from the
  // connect-time snapshot, exactly like a pending hook permission.
  const late = connectSse(bridge.port, token);
  t.after(() => late.close());
  assert.equal(await late.statusCode(), 200);
  const replay = await late.waitFor((e) => e.event === "permission-request" && e.parsed?.tool_name === "AskUserQuestion");
  assert.equal(replay.parsed.permissionId, ev.parsed.permissionId);
});

test("answering the question card on the watch sends positional answers down the inbox (#111)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "askans");

  const inbox = connectInbox(bridge, "conn-askans");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-askans", sessionId: "acp-askans", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-askans");

  await raiseInputRequest(bridge, { connection: "conn-askans", sessionId: "acp-askans", toolCallId: "tc-q2" });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");

  // The wear client's /v1 form: behavior allow + one positional answer per
  // question. The second answer is dictated free text — the wrist's own
  // "Other" lane — and must ride back as the literal string.
  const answer = await request(bridge.port, "POST", "/command", {
    token,
    body: {
      permissionId: prompt.parsed.permissionId,
      decision: { behavior: "allow", answers: ["Green", "whatever the linter says"] },
    },
  });
  assert.equal(answer.status, 200);

  const frame = await inbox.waitFor((e) => e.event === "input-decision");
  assert.equal(frame.parsed.sessionId, "acp-askans");
  assert.equal(frame.parsed.toolCallId, "tc-q2");
  assert.deepEqual(frame.parsed.answers, ["Green", "whatever the linter says"]);
});

test("an elicitation resolved in Zed retracts the question card (#111)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "askzed");

  const inbox = connectInbox(bridge, "conn-askzed");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-askzed", sessionId: "acp-askzed", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-askzed");

  await raiseInputRequest(bridge, { connection: "conn-askzed", sessionId: "acp-askzed", toolCallId: "tc-q3" });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");

  // The adapter reports the elicitation settled on the Zed side (answered
  // there, or the turn was cancelled).
  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-askzed", sessionId: "acp-askzed", kind: "input-resolved",
      payload: { sessionId: "acp-askzed", toolCallId: "tc-q3" },
    },
  });

  const cleared = await sse.waitFor((e) => e.event === "permission-cleared");
  assert.equal(cleared.parsed.permissionId, prompt.parsed.permissionId);
});

// The reported bug, both halves. A question asked in Zed reached the wrist
// only if a watch happened to be streaming at that instant, and even then the
// card was retracted after the bridge's expiry window while Zed's form was
// still open — so the session went back to showing its last activity (green,
// RUNNING) with no card to answer and the agent still blocked.
test("a question raised with no watch streaming survives the expiry window and replays on connect (#111)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "1000" },
  });
  const token = await pair(bridge);
  const cwd = realCwd(t, "askgap");

  const inbox = connectInbox(bridge, "conn-askgap");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-askgap", sessionId: "acp-askgap", cwd });

  // NO watch is streaming — the wrist's connection drops routinely (screen
  // off, off-LAN, doze) and the question lands in one of those gaps.
  await raiseInputRequest(bridge, { connection: "conn-askgap", sessionId: "acp-askgap", toolCallId: "tc-gap" });

  // Well past the configured window: an expiry here would retract a card
  // whose elicitation is still open, and nothing ever re-raises it.
  await new Promise((resolve) => setTimeout(resolve, 2_500));

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  const card = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "AskUserQuestion",
  );
  assert.deepEqual(card.parsed.tool_input, { questions: ASK_QUESTIONS });

  // And it is still answerable — the whole point of keeping it.
  const answer = await request(bridge.port, "POST", "/command", {
    token,
    body: {
      permissionId: card.parsed.permissionId,
      decision: { behavior: "allow", answers: ["Green", "Tabs"] },
    },
  });
  assert.equal(answer.status, 200);
  const decision = await inbox.waitFor((e) => e.event === "input-decision");
  assert.deepEqual(decision.parsed.answers, ["Green", "Tabs"]);
});

test("a session's end expires its pending question card (#111)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "askend");

  const inbox = connectInbox(bridge, "conn-askend");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-askend", sessionId: "acp-askend", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-askend");

  await raiseInputRequest(bridge, { connection: "conn-askend", sessionId: "acp-askend", toolCallId: "tc-q4" });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");

  // The fork closed the session (Zed quit, thread closed): nobody is left to
  // consume an answer, and there is no expiry timer to eventually sweep the
  // card up — this cleanup is the ONLY thing that retires it.
  const dereg = await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "conn-askend", sessionId: "acp-askend", reason: "acp-closed" },
  });
  assert.equal(dereg.status, 200);

  const cleared = await sse.waitFor((e) => e.event === "permission-cleared");
  assert.equal(cleared.parsed.permissionId, prompt.parsed.permissionId);
});

// --- Rich ACP option lists (#110) -------------------------------------------
// The canonical menu is behavior-keyed, so ExitPlanMode's several allow_always
// mode switches cannot all keep a button. The guard drops an ambiguous
// behavior's button instead of electing one silently, and the agent's real
// list rides alongside as `agentOptions` so a capable client renders it and
// answers with the exact optionId.

// The adapter's ExitPlanMode shape verbatim (acp-agent.ts): three allow_always
// mode switches, one allow_once, one reject_once.
const EXIT_PLAN_OPTIONS = [
  { optionId: "bypassPermissions", name: "Yes, and bypass permissions", kind: "allow_always" },
  { optionId: "auto", name: 'Yes, and use "auto" mode', kind: "allow_always" },
  { optionId: "acceptEdits", name: "Yes, and auto-accept edits", kind: "allow_always" },
  { optionId: "default", name: "Yes, and manually approve edits", kind: "allow_once" },
  { optionId: "plan", name: "No, keep planning", kind: "reject_once" },
];

async function raisePlanPermission(bridge, { connection, sessionId, toolCallId }) {
  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection, sessionId, kind: "permission",
      payload: {
        sessionId,
        toolCall: { toolCallId, title: "ExitPlanMode", rawInput: { plan: "the plan" } },
        options: EXIT_PLAN_OPTIONS,
      },
    },
  });
}

test("a rich option list rides the prompt verbatim and the tapped optionId comes back exact (#110)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "permrich");

  const inbox = connectInbox(bridge, "conn-permrich");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-permrich", sessionId: "acp-permrich", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-permrich");

  await raisePlanPermission(bridge, {
    connection: "conn-permrich", sessionId: "acp-permrich", toolCallId: "tc-plan",
  });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");

  // The agent's list, VERBATIM: same options, same order, label + id + kind.
  assert.deepEqual(
    prompt.parsed.agentOptions,
    EXIT_PLAN_OPTIONS.map(({ optionId, name, kind }) => ({ optionId, label: name, kind })),
  );

  // Answering with the tapped optionId names that exact option to the agent —
  // never one elected from its behavior.
  const answer = await request(bridge.port, "POST", "/command", {
    token,
    body: {
      permissionId: prompt.parsed.permissionId,
      decision: { behavior: "allow-always", optionId: "acceptEdits" },
    },
  });
  assert.equal(answer.status, 200);
  const frame = await inbox.waitFor((e) => e.event === "permission-decision");
  assert.equal(frame.parsed.toolCallId, "tc-plan");
  assert.equal(frame.parsed.optionId, "acceptEdits");
  assert.equal(frame.parsed.behavior, "allow-always");
});

test("an ambiguous behavior loses its canonical button; unambiguous ones survive exact (#110)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "permguard");

  const inbox = connectInbox(bridge, "conn-permguard");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-permguard", sessionId: "acp-permguard", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-permguard");

  await raisePlanPermission(bridge, {
    connection: "conn-permguard", sessionId: "acp-permguard", toolCallId: "tc-guard",
  });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");

  // Three options map to allow-always: that button is GONE (absence beats
  // roulette), while the sole allow and deny keep their buttons.
  assert.deepEqual(prompt.parsed.options.map((o) => o.behavior), ["allow", "deny"]);

  // A canonical-button answer (an app without agentOptions support) still
  // resolves through the surviving unambiguous behaviors, exactly.
  const answer = await request(bridge.port, "POST", "/command", {
    token,
    body: { permissionId: prompt.parsed.permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(answer.status, 200);
  const frame = await inbox.waitFor((e) => e.event === "permission-decision");
  assert.equal(frame.parsed.optionId, "default", "allow must resolve to the sole allow_once option");
  assert.equal(frame.parsed.behavior, "allow");
});

// The behavior fallback must key on the behavior the user CHOSE — the
// hook-era rewrite of allow-always into allow once leaked into this echo and
// sent a wrist "Always Allow" to the agent as its allow_once option: an
// allow-once masquerading as a standing grant.
test("a behavior-only allow-always answer names the agent's allow_always option (#110)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "permalways");

  const inbox = connectInbox(bridge, "conn-permalways");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-permalways", sessionId: "acp-permalways", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-permalways");

  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-permalways", sessionId: "acp-permalways", kind: "permission",
      payload: {
        sessionId: "acp-permalways",
        toolCall: { toolCallId: "tc-aa", title: "Bash", rawInput: { command: "ls" } },
        options: [
          { optionId: "once", name: "Allow", kind: "allow_once" },
          { optionId: "always", name: "Always allow", kind: "allow_always" },
          { optionId: "no", name: "Reject", kind: "reject_once" },
        ],
      },
    },
  });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");
  // One option per behavior: nothing is ambiguous, nothing rides as
  // agentOptions — the canonical wire shape is unchanged for simple prompts.
  assert.deepEqual(prompt.parsed.options.map((o) => o.behavior), ["allow", "allow-always", "deny"]);
  assert.equal("agentOptions" in prompt.parsed, false);

  const answer = await request(bridge.port, "POST", "/command", {
    token,
    body: { permissionId: prompt.parsed.permissionId, decision: { behavior: "allow-always" } },
  });
  assert.equal(answer.status, 200);
  const frame = await inbox.waitFor((e) => e.event === "permission-decision");
  assert.equal(frame.parsed.optionId, "always");
  assert.equal(frame.parsed.behavior, "allow-always");
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

// The wrist's idle flag is a ONE-WAY latch (#60): absence never wakes a
// session, because every reconnect snapshot omits it. So a turn START has to
// say `idle: false` out loud, or the watch stays idle for the whole turn —
// there is no other mid-turn signal now that prose is coalesced to turn end.
test("turn start announces an explicit idle:false, turn end idle:true (#79)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "wake");

  const inbox = connectInbox(bridge, "conn-wake");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-wake", sessionId: "acp-wake", cwd });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-wake");

  const turn = (phase) =>
    request(bridge.port, "POST", "/acp/update", {
      body: { connection: "conn-wake", sessionId: "acp-wake", kind: "turn", payload: { phase } },
    });

  await turn("end");
  const ended = await sse.waitFor((e) => e.event === "session" && e.parsed?.idle === true);
  assert.equal(ended.parsed.idle, true);

  await turn("start");
  const started = await sse.waitFor((e) => e.event === "session" && e.parsed?.idle === false);
  assert.equal(started.parsed.idle, false, "a turn start must say idle:false out loud");

  // A connect-time snapshot must NOT carry it: absence is what keeps a routine
  // reconnect from restarting every session's elapsed clock.
  const snap = connectSse(bridge.port, token);
  t.after(() => snap.close());
  assert.equal(await snap.statusCode(), 200);
  const resent = await snap.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-wake");
  assert.equal(resent.parsed.idle, undefined, "the reconnect snapshot must stay silent about idle");
});

// --- Wrist subheading meta: model · mode · contextPct (#97, Halo v2 S8) ------
// The v2 pager shows `model · mode · use%` under each session title. The
// register body seeds all three (context arrives as TOKENS, the bridge owns
// the percent); the teed updates the bridge used to ignore keep them current.

test("register seeds model/mode/contextPct, and SSE + REST agree — 0% included (#97)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "meta");

  const inbox = connectInbox(bridge, "conn-meta");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);

  const res = await request(bridge.port, "POST", "/acp/register", {
    body: {
      connection: "conn-meta", sessionId: "acp-meta", sdkSessionId: "acp-meta", cwd,
      model: "Opus", mode: "default", contextUsed: 0, contextSize: 200000,
    },
  });
  assert.equal(res.status, 200);

  // The connect-time snapshot carries the seeded meta — including the 0% a
  // fresh session honestly reports (presence, not truthiness).
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  const ev = await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-meta");
  assert.equal(ev.parsed.model, "Opus");
  assert.equal(ev.parsed.mode, "default");
  assert.equal(ev.parsed.contextPct, 0, "a real 0% must survive the omit-when-false doctrine");

  const entry = await statusEntry(bridge, token, "acp-meta");
  assert.equal(entry.model, "Opus");
  assert.equal(entry.mode, "default");
  assert.equal(entry.contextPct, 0);

  // A register with no meta carries none of the fields — there is no signal
  // to derive them from, and a fabricated value would be a lie.
  const bareCwd = realCwd(t, "metabare");
  await request(bridge.port, "POST", "/acp/register", {
    body: { connection: "conn-meta", sessionId: "acp-meta-bare", sdkSessionId: "acp-meta-bare", cwd: bareCwd },
  });
  const bareEntry = await statusEntry(bridge, token, "acp-meta-bare");
  assert.ok(bareEntry, "meta-less session present");
  assert.equal(bareEntry.model, undefined);
  assert.equal(bareEntry.mode, undefined);
  assert.equal(bareEntry.contextPct, undefined);
});

test("teed usage_update announces contextPct only when the integer changes (#97)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "pct");

  const inbox = connectInbox(bridge, "conn-pct");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await request(bridge.port, "POST", "/acp/register", {
    body: {
      connection: "conn-pct", sessionId: "acp-pct", sdkSessionId: "acp-pct", cwd,
      model: "Opus", mode: "default", contextUsed: 0, contextSize: 200000,
    },
  });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-pct");

  const usage = (used, size) =>
    request(bridge.port, "POST", "/acp/update", {
      body: {
        connection: "conn-pct", sessionId: "acp-pct", kind: "session_update",
        payload: { sessionId: "acp-pct", update: { sessionUpdate: "usage_update", used, size } },
      },
    });

  await usage(800, 200000);    // 0.4% → still 0: below the integer step, no event
  await usage(100000, 200000); // 50% → announce
  await usage(100600, 200000); // 50.3% → still 50: the mid-stream duplicate case
  await usage(120000, 200000); // 60% → announce

  await sse.waitFor((e) => e.event === "session" && e.parsed?.contextPct === 60);
  const pcts = sse.events
    .filter((e) => e.event === "session" && e.parsed?.sessionId === "acp-pct")
    .map((e) => e.parsed.contextPct);
  assert.deepEqual(pcts, [0, 50, 60], "one event per INTEGER move — sub-percent motion is silent");
});

test("teed current_mode_update announces the mode on change, not on repeat (#97)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "mode");

  const inbox = connectInbox(bridge, "conn-mode");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await request(bridge.port, "POST", "/acp/register", {
    body: {
      connection: "conn-mode", sessionId: "acp-mode", sdkSessionId: "acp-mode", cwd,
      model: "Opus", mode: "default", contextUsed: 0, contextSize: 200000,
    },
  });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-mode");

  const modeUpdate = (currentModeId) =>
    request(bridge.port, "POST", "/acp/update", {
      body: {
        connection: "conn-mode", sessionId: "acp-mode", kind: "session_update",
        payload: { sessionId: "acp-mode", update: { sessionUpdate: "current_mode_update", currentModeId } },
      },
    });

  await modeUpdate("default"); // the register already said so — no event
  await modeUpdate("plan");
  await modeUpdate("plan");    // repeat — no event
  await modeUpdate("acceptEdits");

  await sse.waitFor((e) => e.event === "session" && e.parsed?.mode === "acceptEdits");
  const modes = sse.events
    .filter((e) => e.event === "session" && e.parsed?.sessionId === "acp-mode")
    .map((e) => e.parsed.mode);
  assert.deepEqual(modes, ["default", "plan", "acceptEdits"], "announce on change only");
  assert.equal((await statusEntry(bridge, token, "acp-mode")).mode, "acceptEdits");
});

test("teed config_option_update announces the model DISPLAY name, raw id verbatim off-picker (#97)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "model");

  const inbox = connectInbox(bridge, "conn-model");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await request(bridge.port, "POST", "/acp/register", {
    body: {
      connection: "conn-model", sessionId: "acp-model", sdkSessionId: "acp-model", cwd,
      model: "Opus", mode: "default", contextUsed: 0, contextSize: 200000,
    },
  });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-model");

  const configUpdate = (currentValue) =>
    request(bridge.port, "POST", "/acp/update", {
      body: {
        connection: "conn-model", sessionId: "acp-model", kind: "session_update",
        payload: {
          sessionId: "acp-model",
          update: {
            sessionUpdate: "config_option_update",
            configOptions: [
              { id: "mode", category: "mode", currentValue: "default", options: [] },
              {
                id: "model", category: "model", currentValue,
                options: [
                  { value: "default", name: "Default (recommended)" },
                  { value: "opus[1m]", name: "Opus" },
                  { value: "sonnet", name: "Sonnet" },
                ],
              },
            ],
          },
        },
      },
    });

  // The never-touched-the-picker session: its currentValue is the `default`
  // alias, whose row name says nothing about the model. The register seeded
  // the adapter-RESOLVED name, and the wire carries no resolvedModel to
  // re-derive it from — so the alias must announce nothing, not clobber the
  // seed with "Default (recommended)".
  await configUpdate("default");
  await configUpdate("opus[1m]"); // display name "Opus" — unchanged, no event
  await configUpdate("sonnet");
  await configUpdate("sonnet");   // an effort/agent rebuild re-sends the list — no event
  // A refusal fallback outside the picker: no option row, so the raw id is
  // the only honest label.
  await configUpdate("claude-weird-9");

  await sse.waitFor((e) => e.event === "session" && e.parsed?.model === "claude-weird-9");
  const models = sse.events
    .filter((e) => e.event === "session" && e.parsed?.sessionId === "acp-model")
    .map((e) => e.parsed.model);
  assert.deepEqual(models, ["Opus", "Sonnet", "claude-weird-9"], "display name on change only");
  assert.ok(!models.includes("Default (recommended)"), "the alias row's own name never announces");
});

// Zed's native mode selector is session/set_mode, and the adapter's only teed
// footprint for it is the config_option_update its updateConfigOption emits —
// no current_mode_update. The bridge must read the mode option out of that
// frame, or a Zed mode flip sits stale on the wrist until a bridge restart.
// The same frame re-sends the model option with the `default` alias as its
// currentValue (the never-touched-the-picker session), so this also pins the
// seeded display name surviving every alias-bearing rebuild.
test("a Zed set_mode flip — teed as config_option_update alone — announces the mode (#97)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "setmode");

  const inbox = connectInbox(bridge, "conn-setmode");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await request(bridge.port, "POST", "/acp/register", {
    body: {
      connection: "conn-setmode", sessionId: "acp-setmode", sdkSessionId: "acp-setmode", cwd,
      model: "Opus", mode: "default", contextUsed: 0, contextSize: 200000,
    },
  });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-setmode");

  const setMode = (modeId) =>
    request(bridge.port, "POST", "/acp/update", {
      body: {
        connection: "conn-setmode", sessionId: "acp-setmode", kind: "session_update",
        payload: {
          sessionId: "acp-setmode",
          update: {
            sessionUpdate: "config_option_update",
            configOptions: [
              {
                id: "mode", category: "mode", currentValue: modeId,
                options: [
                  { value: "default", name: "Always Ask" },
                  { value: "plan", name: "Plan Mode" },
                  { value: "acceptEdits", name: "Accept Edits" },
                ],
              },
              {
                id: "model", category: "model", currentValue: "default",
                options: [
                  { value: "default", name: "Default (recommended)" },
                  { value: "opus[1m]", name: "Opus" },
                ],
              },
            ],
          },
        },
      },
    });

  await setMode("plan");
  const flip = await sse.waitFor((e) => e.event === "session" && e.parsed?.mode === "plan");
  assert.equal(flip.parsed.model, "Opus", "the seeded resolved name rides the mode flip untouched");
  await setMode("plan"); // an effort/fast rebuild re-sends the list — no event
  await setMode("acceptEdits");

  await sse.waitFor((e) => e.event === "session" && e.parsed?.mode === "acceptEdits");
  const frames = sse.events.filter((e) => e.event === "session" && e.parsed?.sessionId === "acp-setmode");
  assert.deepEqual(frames.map((e) => e.parsed.mode), ["default", "plan", "acceptEdits"], "announce on change only");
  assert.deepEqual([...new Set(frames.map((e) => e.parsed.model))], ["Opus"],
    "no frame ever rewrites the model as the alias row's own name");

  const entry = await statusEntry(bridge, token, "acp-setmode");
  assert.equal(entry.mode, "acceptEdits");
  assert.equal(entry.model, "Opus");
});

// The replay's context pair is a registration-time reading (the fork refreshes
// only model/mode in its replay copy), so a re-register must behave like the
// `active` doctrine: refresh what is current, never rewind what the teed
// updates advanced.
test("a re-register refreshes model/mode but never rewinds contextPct (#97)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = realCwd(t, "rewind");

  const inbox = connectInbox(bridge, "conn-rewind");
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  const register = (fields) =>
    request(bridge.port, "POST", "/acp/register", {
      body: { connection: "conn-rewind", sessionId: "acp-rewind", sdkSessionId: "acp-rewind", cwd, ...fields },
    });
  await register({ model: "Opus", mode: "default", contextUsed: 0, contextSize: 200000 });

  // The turn advances the meter to 50%.
  await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection: "conn-rewind", sessionId: "acp-rewind", kind: "session_update",
      payload: { sessionId: "acp-rewind", update: { sessionUpdate: "usage_update", used: 100000, size: 200000 } },
    },
  });
  assert.equal((await statusEntry(bridge, token, "acp-rewind")).contextPct, 50);

  // A duplicate register (reconnect replay) carries the stale creation-time
  // tokens but the CURRENT mode the fork's noteSessionMeta kept fresh.
  await register({ model: "Opus", mode: "plan", contextUsed: 0, contextSize: 200000 });

  const entry = await statusEntry(bridge, token, "acp-rewind");
  assert.equal(entry.mode, "plan", "model/mode refresh on a re-register");
  assert.equal(entry.contextPct, 50, "a stale register must not rewind the meter");
});
