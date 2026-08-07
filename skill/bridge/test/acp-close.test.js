// The wrist kill of an ACP session (issue #88, close half): the bridge relays
// `kill` down the fork's inbox as a `close` frame, the fork's own teardown
// deregisters the session, and only THAT ending answers the watch. Black-box,
// with a test standing in for the fork exactly as the real adapter behaves.
//
// The whole point is #53's doctrine: a kill the bridge cannot perform must
// never LOOK performed. So every refusal here is checked twice — the status
// the watch sees, and the slot still standing on the far side of it.
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

async function registerAcp(bridge, { connection, sessionId, cwd, detached }) {
  const res = await request(bridge.port, "POST", "/acp/register", {
    body: { connection, sessionId, sdkSessionId: sessionId, cwd, detached },
  });
  assert.equal(res.status, 200);
}

async function slotOf(bridge, token, sessionId) {
  const status = await request(bridge.port, "GET", "/status", { token });
  assert.equal(status.status, 200);
  return status.body.sessions.find((s) => s.id === sessionId);
}

test("wrist kill of an ACP session rides a close frame; the fork's teardown is the ack", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-close-happy-");

  const inbox = connectInbox(t, bridge, "fork-close");
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "fork-close", sessionId: "kill-1", cwd, detached: true });

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "kill-1" && e.parsed?.state === "running");

  const respPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { kill: true, sessionId: "kill-1" },
  });

  // The frame the fork consumes — the same shape as inject/spawn, addressed
  // by session and carrying why.
  const frame = await inbox.waitFor((e) => e.event === "close");
  assert.equal(frame.parsed.sessionId, "kill-1");
  assert.equal(frame.parsed.reason, "watch-kill");

  // Nothing has ended yet: the adapter is still tearing down, and the bridge
  // must not have invented the ending it asked for.
  const midFlight = await slotOf(bridge, token, "kill-1");
  assert.equal(midFlight.state, "running", "the slot stays running until the fork really ends it");

  // The real adapter's teardown ends with the deregister its closeQueryStream
  // POSTs — that, and only that, settles the wrist's kill.
  await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "fork-close", sessionId: "kill-1", reason: "query-closed" },
  });

  const resp = await respPromise;
  assert.equal(resp.status, 200);
  assert.equal(resp.body.ok, true);
  assert.equal(resp.body.kind, "acp");

  const ended = await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === "kill-1" && e.parsed?.state === "ended",
  );
  assert.equal(ended.parsed.reason, "query-closed", "the ending is the fork's own, not a bridge fabrication");
});

test("a fork that never answers the frame → 504, and the session stays running", { timeout: 60_000 }, async (t) => {
  // The stale-adapter case: a build too old to know the frame hard-ignores it.
  // The wrist must hear that nothing happened rather than watch a live session
  // vanish from its list (#53's zombie, rebuilt).
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_ACP_CLOSE_TIMEOUT_MS: "400" } });
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-close-deaf-");

  const inbox = connectInbox(t, bridge, "fork-deaf");
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "fork-deaf", sessionId: "kill-deaf", cwd });

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { kill: true, sessionId: "kill-deaf" },
  });
  assert.equal(resp.status, 504);
  assert.match(resp.body.error, /rebuild/i, "the error names the actual fix");

  const slot = await slotOf(bridge, token, "kill-deaf");
  assert.equal(slot.state, "running", "an unacknowledged kill leaves the session exactly as it was");
  assert.equal(slot.dictatable, true, "and still reachable — nothing was torn down");
});

test("an ACP session whose fork is not connected refuses the kill honestly (502, nothing stopped)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-close-noinbox-");

  // Registered, but its downlink was never opened: there is no fork to ask.
  await registerAcp(bridge, { connection: "fork-absent", sessionId: "kill-absent", cwd });

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { kill: true, sessionId: "kill-absent" },
  });
  assert.equal(resp.status, 502);
  assert.match(resp.body.error, /not reachable|not connected/i);
  assert.match(resp.body.error, /nothing was stopped/i, "the refusal says outright that the agent still runs");

  const slot = await slotOf(bridge, token, "kill-absent");
  assert.equal(slot.state, "running", "a refused kill must not mark the slot ended");
});

test("the fork dying mid-close settles the kill as the real ending it is", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_ACP_CLOSE_TIMEOUT_MS: "8000" } });
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-close-forkdeath-");

  const inbox = connectInbox(t, bridge, "fork-dying");
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "fork-dying", sessionId: "kill-dying", cwd });

  const started = Date.now();
  const respPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { kill: true, sessionId: "kill-dying" },
  });
  await inbox.waitFor((e) => e.event === "close");
  inbox.close(); // Zed quit while tearing down — the session died with it

  const resp = await respPromise;
  assert.equal(resp.status, 200, "the session ended, which is what the wrist asked for");
  assert.ok(Date.now() - started < 5000, "settled by the ending, not by the timeout");

  const slot = await slotOf(bridge, token, "kill-dying");
  assert.equal(slot.state, "ended");
});

test("two taps on one session share a single close — the second never orphans the first", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-close-double-");

  const inbox = connectInbox(t, bridge, "fork-double");
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "fork-double", sessionId: "kill-double", cwd });

  const first = request(bridge.port, "POST", "/v1/command", { token, body: { kill: true, sessionId: "kill-double" } });
  await inbox.waitFor((e) => e.event === "close");
  const second = request(bridge.port, "POST", "/v1/command", { token, body: { kill: true, sessionId: "kill-double" } });

  await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "fork-double", sessionId: "kill-double", reason: "query-closed" },
  });

  assert.equal((await first).status, 200);
  assert.equal((await second).status, 200, "both waiters settle on the one ending");
});

test("a killed watch-spawned session is no longer claimable at the desk", { timeout: 60_000 }, async (t) => {
  // The pickup registry deliberately outlives a fork death (Zed restarting is
  // not the user giving up on the session), but a KILL is: a New Thread that
  // adopted it would resume the very conversation the wrist just ended —
  // auto-revive by accident, and that policy is explicitly unsettled.
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-close-pickup-");

  const inbox = connectInbox(t, bridge, "fork-pickup");
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "fork-pickup", sessionId: "kill-pickup", cwd, detached: true });

  const respPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { kill: true, sessionId: "kill-pickup" },
  });
  await inbox.waitFor((e) => e.event === "close");
  await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "fork-pickup", sessionId: "kill-pickup", reason: "query-closed" },
  });
  assert.equal((await respPromise).status, 200);

  const claim = await request(bridge.port, "POST", "/acp/claim", { body: { connection: "fork-pickup", cwd } });
  assert.equal(claim.body.sessionId, null, "a killed session must not be handed to a New Thread");
});

test("a fork death still leaves the pickup claimable (only a KILL retires it)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const cwd = tempDir(t, "acp-close-pickup-survives-");

  const inbox = connectInbox(t, bridge, "fork-quits");
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "fork-quits", sessionId: "survives", cwd, detached: true });

  // Zed quits with no kill in flight: the session ends, the pickup remains.
  inbox.close();

  const inbox2 = connectInbox(t, bridge, "fork-next");
  assert.equal(await inbox2.statusCode(), 200);
  const claim = await request(bridge.port, "POST", "/acp/claim", { body: { connection: "fork-next", cwd } });
  assert.equal(claim.body.sessionId, "survives", "a fork death is not the user abandoning the session");
});

test("a bridge-owned PTY session keeps its direct kill (no frame, no fork needed)", { timeout: 60_000 }, async (t) => {
  const binDir = tempDir(t, "acp-close-pty-bin-");
  const bin = path.join(binDir, "codex");
  fs.writeFileSync(bin, "#!/bin/sh\necho CODEX-READY\nexec cat\n", { mode: 0o755 });
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_CODEX_BIN: bin } });
  const token = await pair(bridge);

  // A fork IS connected — the PTY path must not be routed at it.
  const inbox = connectInbox(t, bridge, "fork-idle");
  assert.equal(await inbox.statusCode(), 200);

  const spawned = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "codex", cwd: os.homedir() },
  });
  assert.equal(spawned.status, 200);

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { kill: true, sessionId: spawned.body.sessionId },
  });
  assert.equal(resp.status, 200);
  const slot = await slotOf(bridge, token, spawned.body.sessionId);
  assert.equal(slot.state, "ended", "the bridge owns this process: the kill is immediate and real");

  const frame = await inbox.waitFor((e) => e.event === "close", 800).catch(() => null);
  assert.equal(frame, null, "a PTY kill sends the fork nothing");
});

test("kill of an unknown session is still a 404", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { kill: true, sessionId: "no-such-session" },
  });
  assert.equal(resp.status, 404);
});
