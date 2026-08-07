// Dictating with no session to dictate into (issue #91): the last claude PTY
// auto-spawn is retired, so this site now composes the SAME two machines the
// explicit spawn action uses — a `spawn` frame down the fork's inbox, then an
// ordinary `inject` into the session it answers with. Black-box, with a test
// standing in for the Zed-launched adapter.
//
// The point is that the wrist never gets a session Zed cannot see: with no
// fork connected the answer is an honest 409 and NOTHING is created — no PTY,
// no slot, no half-spawned draft.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { startBridge, request, tempDir, connectSse } from "./helpers.js";

/** A stub `claude` binary: if the retired PTY auto-spawn ever came back, this
 *  is what it would run — so every test here can prove it did not. */
function stubClaude(t) {
  const dir = tempDir(t, "acp-dictate-bin-");
  const bin = path.join(dir, "claude");
  fs.writeFileSync(bin, "#!/bin/sh\necho STUB-CLAUDE-READY\nexec cat\n", { mode: 0o755 });
  return bin;
}

async function pairedBridge(t, extraEnv = {}) {
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_CLAUDE_BIN: stubClaude(t), ...extraEnv },
  });
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  return { bridge, token: pair.body.token };
}

function connectInbox(t, bridge, connectionId) {
  const inbox = connectSse(bridge.port, undefined, { path: `/acp/inbox?connection=${connectionId}` });
  t.after(() => inbox.close());
  return inbox;
}

async function sessionSnapshot(bridge, token) {
  const status = await request(bridge.port, "GET", "/v1/status", { token });
  assert.equal(status.status, 200);
  return status.body.sessions;
}

test("dictation with no session spawns in Zed and injects into what the fork answers with", { timeout: 60_000 }, async (t) => {
  const { bridge, token } = await pairedBridge(t);
  const cwd = tempDir(t, "acp-dictate-happy-");

  const inbox = connectInbox(t, bridge, "fork-dictate");
  assert.equal(await inbox.statusCode(), 200);

  const respPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { command: "start on the parser bug\n", cwd },
  });

  // Leg one: the same spawn frame the explicit spawn action sends.
  const spawnFrame = await inbox.waitFor((e) => e.event === "spawn");
  assert.equal(spawnFrame.parsed.cwd, cwd);
  assert.equal(spawnFrame.parsed.agent, "claude");
  await request(bridge.port, "POST", "/acp/register", {
    body: {
      connection: "fork-dictate", sessionId: "dictated-1", sdkSessionId: "dictated-1", cwd, detached: true,
    },
  });
  await request(bridge.port, "POST", "/acp/spawn-result", {
    body: { connection: "fork-dictate", requestId: spawnFrame.parsed.requestId, ok: true, sessionId: "dictated-1", cwd },
  });

  // Leg two: the prompt itself, as an ordinary inject — trimmed exactly like a
  // dictation into an existing ACP session.
  const injected = await inbox.waitFor((e) => e.event === "inject");
  assert.equal(injected.parsed.sessionId, "dictated-1");
  assert.equal(injected.parsed.text, "start on the parser bug");
  assert.equal(injected.parsed.source, "watch");

  const resp = await respPromise;
  assert.equal(resp.status, 200);
  assert.equal(resp.body.ok, true);
  assert.equal(resp.body.sessionId, "dictated-1");
  assert.equal(resp.body.kind, "acp", "the new session is Zed's, not a bridge PTY");
  assert.equal(resp.body.spawned, true);
  assert.equal(resp.body.prompt, true);
  assert.equal(resp.body.spawnRequestId, spawnFrame.parsed.requestId, "attributable, exactly like the spawn action");

  // Exactly one session, and it is the ACP one: no PTY was minted alongside.
  const snapshot = await sessionSnapshot(bridge, token);
  assert.equal(snapshot.length, 1);
  assert.equal(snapshot[0].kind, "acp");
  assert.equal(snapshot[0].cwd, cwd);
});

test("no fork connected → the honest 409, and NOTHING is created (no PTY fallback)", { timeout: 60_000 }, async (t) => {
  // The stub claude is available and would happily run: the refusal must be a
  // decision, not a missing binary.
  const { bridge, token } = await pairedBridge(t);

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { command: "anyone there?\n", cwd: os.homedir() },
  });
  assert.equal(resp.status, 409);
  assert.match(resp.body.error, /Zed/i, "the error names the actual fix (open Zed)");

  assert.deepEqual(await sessionSnapshot(bridge, token), [], "no session slot of any species was created");
});

test("a fork failure surfaces the fork's own error and the requestId (late arrivals stay attributable)", { timeout: 60_000 }, async (t) => {
  const { bridge, token } = await pairedBridge(t);
  const cwd = tempDir(t, "acp-dictate-fail-");

  const inbox = connectInbox(t, bridge, "fork-dictate-fail");
  assert.equal(await inbox.statusCode(), 200);

  const respPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { command: "this one dies\n", cwd },
  });
  const frame = await inbox.waitFor((e) => e.event === "spawn");
  await request(bridge.port, "POST", "/acp/spawn-result", {
    body: { connection: "fork-dictate-fail", requestId: frame.parsed.requestId, ok: false, error: "cwd exploded" },
  });

  const resp = await respPromise;
  assert.equal(resp.status, 409);
  assert.match(resp.body.error, /cwd exploded/);
  assert.equal(resp.body.spawnRequestId, frame.parsed.requestId);
  assert.deepEqual(await sessionSnapshot(bridge, token), [], "a failed spawn leaves no slot behind");
});

test("an unreachable session after a successful spawn is NAMED, not lost", { timeout: 60_000 }, async (t) => {
  // The window between the fork's ack and the injection: the session exists,
  // but its owning inbox is gone by the time the prompt is written. Modelled
  // by an ack that names a fork with no live inbox — the routing state the
  // bridge would be in either way. The watch must hear the session's ID: a
  // bare "failed" would strand a live session it could still dictate at once
  // Zed is back.
  const { bridge, token } = await pairedBridge(t);
  const cwd = tempDir(t, "acp-dictate-unreachable-");

  const inbox = connectInbox(t, bridge, "fork-dictate-death");
  assert.equal(await inbox.statusCode(), 200);

  const respPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { command: "into the void\n", cwd },
  });
  const frame = await inbox.waitFor((e) => e.event === "spawn");
  await request(bridge.port, "POST", "/acp/spawn-result", {
    body: { connection: "fork-no-inbox", requestId: frame.parsed.requestId, ok: true, sessionId: "dictated-2", cwd },
  });

  const resp = await respPromise;
  assert.equal(resp.status, 502, "an undelivered dictation must not report success");
  assert.equal(resp.body.sessionId, "dictated-2", "the refusal names the session that WAS created");
  assert.equal(resp.body.spawned, true);

  // And the session is a real, visible slot — the client can retry into it.
  const slot = (await sessionSnapshot(bridge, token)).find((s) => s.id === "dictated-2");
  assert.ok(slot, "the spawned session is on the wire, not swallowed by the failure");
});

test("an empty dictation is refused before anything is spawned", { timeout: 60_000 }, async (t) => {
  const { bridge, token } = await pairedBridge(t);

  const inbox = connectInbox(t, bridge, "fork-dictate-empty");
  assert.equal(await inbox.statusCode(), 200);

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { command: "   \n", cwd: os.homedir() },
  });
  assert.equal(resp.status, 400);

  const frame = await inbox.waitFor((e) => e.event === "spawn", 800).catch(() => null);
  assert.equal(frame, null, "no session is created for a prompt with nothing in it");
});

test("codex keeps the PTY auto-spawn — the retirement is claude-only", { timeout: 60_000 }, async (t) => {
  const codexDir = tempDir(t, "acp-dictate-codex-bin-");
  const codexBin = path.join(codexDir, "codex");
  fs.writeFileSync(codexBin, "#!/bin/sh\necho CODEX-READY\nexec cat\n", { mode: 0o755 });
  const { bridge, token } = await pairedBridge(t, { CLAUDE_WATCH_CODEX_BIN: codexBin });

  // A fork IS connected: codex must not be routed at it.
  const inbox = connectInbox(t, bridge, "fork-dictate-codex");
  assert.equal(await inbox.statusCode(), 200);

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { command: "hello codex\n", agent: "codex", cwd: os.homedir() },
  });
  assert.equal(resp.status, 200);
  assert.equal(resp.body.spawned, true);
  assert.equal(resp.body.agent, "codex");
  assert.notEqual(resp.body.kind, "acp");

  const frame = await inbox.waitFor((e) => e.event === "spawn", 800).catch(() => null);
  assert.equal(frame, null, "a codex dictation asks Zed's adapter for nothing");
});
