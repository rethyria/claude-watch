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
