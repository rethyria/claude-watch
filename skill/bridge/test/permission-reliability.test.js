// Permission delivery reliability, black-box (issues #9/#63): the connect-time
// snapshot (sessions + terminal backlog + ALL pending permissions) that
// survives ring-buffer eviction, and the authoritative permission-sync frame
// that retracts prompts which died while a client was away. Prompts are
// raised the way the product raises them — over the ACP loopback channel, as
// the Zed-launched fork does.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { startBridge, request, connectSse } from "./helpers.js";

async function pairAndToken(bridge) {
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  return pair.body.token;
}

// Open the fork's inbox and register an ACP session on it.
async function forkWithSession(t, bridge, connection, sessionId, cwd) {
  const inbox = connectSse(bridge.port, undefined, { path: `/acp/inbox?connection=${connection}` });
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  const reg = await request(bridge.port, "POST", "/acp/register", {
    body: { connection, sessionId, sdkSessionId: sessionId, cwd },
  });
  assert.equal(reg.status, 200);
  return inbox;
}

// Raise a permission request exactly as the adapter's forwardPermissionRequest
// does (a teed RequestPermissionRequest on /acp/update).
async function raisePermission(bridge, connection, sessionId, toolCallId, title, rawInput) {
  const res = await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection,
      sessionId,
      kind: "permission",
      payload: {
        sessionId,
        toolCall: { toolCallId, title, rawInput },
        options: [
          { optionId: "allow_always", name: "Always Allow", kind: "allow_always" },
          { optionId: "allow", name: "Allow", kind: "allow_once" },
          { optionId: "reject", name: "Reject", kind: "reject_once" },
        ],
      },
    },
  });
  assert.equal(res.status, 200);
}

test("client connecting after ring-buffer eviction still receives pending permissions", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_SSE_BUFFER_SIZE: "8" },
  });
  const { port } = bridge;
  const token = await pairAndToken(bridge);

  // A watch is connected while the prompt fires...
  const sseA = connectSse(port, token);
  assert.equal(await sseA.statusCode(), 200);

  const inbox = await forkWithSession(t, bridge, "conn-evict", "acp-evict", "/tmp/evict-project");
  await raisePermission(bridge, "conn-evict", "acp-evict", "tc-evict", "Write", { file_path: "/tmp/x" });
  const promptEvent = await sseA.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Write",
  );
  const permissionId = promptEvent.parsed.permissionId;
  assert.ok(permissionId);

  // ...then disconnects without answering.
  sseA.close();

  // Ordinary session traffic (the fork's teed turns) rolls the 8-slot ring
  // buffer over, evicting the buffered permission-request.
  for (let i = 0; i < 8; i++) {
    const turnStart = await request(port, "POST", "/acp/update", {
      body: { connection: "conn-evict", sessionId: "acp-evict", kind: "turn", payload: { phase: "start" } },
    });
    assert.equal(turnStart.status, 200);
    const turnEnd = await request(port, "POST", "/acp/update", {
      body: { connection: "conn-evict", sessionId: "acp-evict", kind: "turn", payload: { phase: "end" } },
    });
    assert.equal(turnEnd.status, 200);
  }

  // A fresh client (no Last-Event-ID — replay can't help) must still receive
  // the pending prompt via the connect-time snapshot.
  const sseB = connectSse(port, token);
  t.after(() => sseB.close());
  assert.equal(await sseB.statusCode(), 200);
  const resent = await sseB.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.permissionId === permissionId,
  );
  assert.equal(resent.parsed.tool_name, "Write");

  // And answering it still reaches the fork as a decision frame.
  const decision = await request(port, "POST", "/command", {
    token,
    body: { permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(decision.status, 200);
  const frame = await inbox.waitFor(
    (e) => e.event === "permission-decision" && e.parsed?.toolCallId === "tc-evict",
  );
  assert.equal(frame.parsed.optionId, "allow");
});

test("connect-time snapshot includes sessions, terminal backlog, and pending permissions", { timeout: 60_000 }, async (t) => {
  // Terminal backlog is pty-output/tool-output only, so a codex stub PTY
  // supplies it; the pending prompt rides the ACP lane.
  const binDir = fs.mkdtempSync(path.join(os.tmpdir(), "perm-rel-bin-"));
  t.after(() => { try { fs.rmSync(binDir, { recursive: true, force: true }); } catch { /* ignore */ } });
  const bin = path.join(binDir, "codex");
  fs.writeFileSync(bin, "#!/bin/sh\necho snapshot-marker-output\nexec cat\n", { mode: 0o755 });
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_CODEX_BIN: bin } });
  const { port } = bridge;
  const token = await pairAndToken(bridge);

  // First client online while activity happens.
  const sseA = connectSse(port, token);
  t.after(() => sseA.close());
  assert.equal(await sseA.statusCode(), 200);

  // Terminal output from a bridge-owned PTY...
  const spawned = await request(port, "POST", "/v1/command", {
    token,
    body: { spawn: "codex", cwd: os.homedir() },
  });
  assert.equal(spawned.status, 200, JSON.stringify(spawned.body));
  await sseA.waitFor(
    (e) => e.event === "pty-output" && e.parsed?.text?.includes("snapshot-marker-output"),
  );

  // ...and a pending ACP permission prompt.
  await forkWithSession(t, bridge, "conn-snap", "acp-snap", "/tmp/snapshot-project");
  await raisePermission(bridge, "conn-snap", "acp-snap", "tc-snap", "Bash", { command: "npm test" });
  const promptEvent = await sseA.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
  );
  const permissionId = promptEvent.parsed.permissionId;

  // A brand-new client (fresh pair: no Last-Event-ID) connects afterwards and
  // must be told everything: the running sessions, the recent terminal
  // backlog, and the prompt still awaiting an answer.
  const sseB = connectSse(port, token);
  t.after(() => sseB.close());
  assert.equal(await sseB.statusCode(), 200);

  const sessionEvent = await sseB.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === "acp-snap",
  );
  assert.equal(sessionEvent.parsed.state, "running");

  const backlogEvent = await sseB.waitFor(
    (e) => e.event === "pty-output" && e.parsed?.text?.includes("snapshot-marker-output"),
  );
  assert.ok(backlogEvent.parsed.sessionId, "backlog terminal event keeps its session attribution");

  const pendingEvent = await sseB.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.permissionId === permissionId,
  );
  assert.equal(pendingEvent.parsed.tool_input.command, "npm test");
});

test("connect-time sync retracts a prompt that died while the client was away", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;
  const token = await pairAndToken(bridge);

  const sseA = connectSse(port, token);
  assert.equal(await sseA.statusCode(), 200);

  await forkWithSession(t, bridge, "conn-retract", "acp-retract", "/tmp/retract-project");
  await raisePermission(bridge, "conn-retract", "acp-retract", "tc-retract", "Bash", { command: "true" });
  const promptEvent = await sseA.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
  );
  const permissionId = promptEvent.parsed.permissionId;

  // The client goes away; the prompt then dies — the user answered it in Zed,
  // so the fork retracts it — and its permission-cleared is unobservable to
  // the absent client. (This used to be driven by the bridge's expiry timer,
  // which ACP cards no longer carry: a card now lives exactly as long as the
  // request it mirrors.)
  sseA.close();
  const resolved = await request(port, "POST", "/acp/update", {
    body: {
      connection: "conn-retract",
      sessionId: "acp-retract",
      kind: "permission-resolved",
      payload: { sessionId: "acp-retract", toolCallId: "tc-retract" },
    },
  });
  assert.equal(resolved.status, 200);

  // On reconnect the authoritative permission-sync must NOT list the dead id
  // — that absence is what tells the client to drop the card it still holds.
  const sseB = connectSse(port, token);
  t.after(() => sseB.close());
  assert.equal(await sseB.statusCode(), 200);
  const sync = await sseB.waitFor((e) => e.event === "permission-sync");
  assert.ok(Array.isArray(sync.parsed.permissionIds));
  assert.ok(
    !sync.parsed.permissionIds.includes(permissionId),
    `the dead prompt must be absent from the sync; got ${JSON.stringify(sync.parsed)}`,
  );
});

test("connect-time sync lists a still-live prompt so clients keep it", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;
  const token = await pairAndToken(bridge);

  const sseA = connectSse(port, token);
  t.after(() => sseA.close());
  assert.equal(await sseA.statusCode(), 200);

  await forkWithSession(t, bridge, "conn-live", "acp-live", "/tmp/live-project");
  await raisePermission(bridge, "conn-live", "acp-live", "tc-live", "Edit", { file_path: "a.txt" });
  const promptEvent = await sseA.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Edit",
  );
  const permissionId = promptEvent.parsed.permissionId;

  const sseB = connectSse(port, token);
  t.after(() => sseB.close());
  assert.equal(await sseB.statusCode(), 200);
  const sync = await sseB.waitFor((e) => e.event === "permission-sync");
  assert.ok(
    sync.parsed.permissionIds.includes(permissionId),
    `a live prompt must be listed by the sync; got ${JSON.stringify(sync.parsed)}`,
  );
  // ...and its payload follows as a re-sent permission-request.
  await sseB.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.permissionId === permissionId,
  );
});
