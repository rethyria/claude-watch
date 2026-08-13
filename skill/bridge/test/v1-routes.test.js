// /v1 surface: every endpoint is also reachable under a /v1 prefix with
// behavior identical to the legacy unprefixed routes, which stay frozen for
// existing clients (see ARCHITECTURE.md).
import { test } from "node:test";
import assert from "node:assert/strict";
import { startBridge, request, connectSse } from "./helpers.js";

// The fork side of a permission round-trip: hold an inbox, register a
// session, and tee a permission request — the adapter's own wire moves.
async function raiseAcpPermission(t, bridge, { connection, sessionId, cwd, toolCallId, title, rawInput }) {
  const inbox = connectSse(bridge.port, undefined, { path: `/acp/inbox?connection=${connection}` });
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  assert.equal((await request(bridge.port, "POST", "/acp/register", {
    body: { connection, sessionId, sdkSessionId: sessionId, cwd },
  })).status, 200);
  assert.equal((await request(bridge.port, "POST", "/acp/update", {
    body: {
      connection,
      sessionId,
      kind: "permission",
      payload: {
        sessionId,
        toolCall: { toolCallId, title, rawInput },
        options: [
          { optionId: "allow", name: "Allow", kind: "allow_once" },
          { optionId: "reject", name: "Reject", kind: "reject_once" },
        ],
      },
    },
  })).status, 200);
  return inbox;
}

test("v1 surface: pair → SSE → permission → decision round-trip", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  // Pairing over /v1 requires the client's protocol version (see PROTOCOL.md)
  const pair = await request(port, "POST", "/v1/pair", { body: { code: pairingCode, proto: 3 } });
  assert.equal(pair.status, 200);
  assert.ok(pair.body.token, "v1 pair response carries a token");
  assert.ok(pair.body.bridgeId, "v1 pair response carries bridgeId");
  const token = pair.body.token;

  // Auth is enforced on the /v1 event stream too
  const sseUnauthed = connectSse(port, null, { path: "/v1/events" });
  t.after(() => sseUnauthed.close());
  assert.equal(await sseUnauthed.statusCode(), 401);

  // Watch client connects to the event stream via /v1
  const sse = connectSse(port, token, { path: "/v1/events" });
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  // The fork raises a permission over its loopback uplink; the pending prompt
  // stays live until a /v1 command decision comes back down the inbox.
  const inbox = await raiseAcpPermission(t, bridge, {
    connection: "conn-v1",
    sessionId: "acp-v1",
    cwd: "/tmp/e2e-v1-project",
    toolCallId: "tc-v1",
    title: "Bash",
    rawInput: { command: "ls -la" },
  });

  const promptEvent = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
  );
  const permissionId = promptEvent.parsed.permissionId;
  assert.ok(permissionId, "v1 permission-request carries a permissionId");
  assert.equal(promptEvent.parsed.tool_input.command, "ls -la");

  const decision = await request(port, "POST", "/v1/command", {
    token,
    body: { permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(decision.status, 200);
  assert.equal(decision.body.ok, true);

  const frame = await inbox.waitFor(
    (e) => e.event === "permission-decision" && e.parsed?.toolCallId === "tc-v1",
  );
  assert.equal(frame.parsed.optionId, "allow");
  assert.equal(frame.parsed.behavior, "allow");
});

test("legacy unprefixed paths keep working alongside /v1", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  // Legacy pairing still works after the /v1 skeleton landed
  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  assert.ok(pair.body.token, "legacy pair response carries a token");
  const token = pair.body.token;

  // Both surfaces answer /status (now authenticated) with the same identity
  const v1Status = await request(port, "GET", "/v1/status", { token });
  assert.equal(v1Status.status, 200);
  const legacyStatus = await request(port, "GET", "/status", { token });
  assert.equal(legacyStatus.status, 200);
  assert.equal(legacyStatus.body.bridgeId, v1Status.body.bridgeId);

  // Both surfaces answer the unauthenticated /ping discovery probe
  const v1Ping = await request(port, "GET", "/v1/ping");
  assert.equal(v1Ping.status, 200);
  const legacyPing = await request(port, "GET", "/ping");
  assert.equal(legacyPing.status, 200);
  assert.equal(legacyPing.body.bridgeId, v1Ping.body.bridgeId);

  // Unknown paths under /v1 are 404, same as legacy
  const missing = await request(port, "GET", "/v1/does-not-exist");
  assert.equal(missing.status, 404);
});

// The production topology: the Zed-launched fork posts to the unprefixed
// loopback paths while a /v1 watch client answers. Permission state and
// tokens are shared across the two surfaces.
test("cross-surface: /v1-paired client answers a legacy-path prompt; tokens interchangeable", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/v1/pair", { body: { code: pairingCode, proto: 3 } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  // A /v1-issued token is valid on the legacy event stream
  const legacySse = connectSse(port, token);
  t.after(() => legacySse.close());
  assert.equal(await legacySse.statusCode(), 200);

  // A /v1 watch client is also connected
  const v1Sse = connectSse(port, token, { path: "/v1/events" });
  t.after(() => v1Sse.close());
  assert.equal(await v1Sse.statusCode(), 200);

  // The fork posts to the LEGACY (unprefixed) paths
  const inbox = await raiseAcpPermission(t, bridge, {
    connection: "conn-cross",
    sessionId: "acp-cross",
    cwd: "/tmp/e2e-cross",
    toolCallId: "tc-cross",
    title: "Edit",
    rawInput: { file_path: "a.txt" },
  });

  // The prompt reaches the /v1 stream, and the /v1 decision resolves it
  const promptEvent = await v1Sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Edit",
  );
  const decision = await request(port, "POST", "/v1/command", {
    token,
    body: { permissionId: promptEvent.parsed.permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(decision.status, 200);

  const frame = await inbox.waitFor(
    (e) => e.event === "permission-decision" && e.parsed?.toolCallId === "tc-cross",
  );
  assert.equal(frame.parsed.optionId, "allow");
});
