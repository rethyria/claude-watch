// /v1 surface: every endpoint is also reachable under a /v1 prefix with
// behavior identical to the legacy unprefixed routes, which stay frozen for
// existing clients (see ARCHITECTURE.md).
import { test } from "node:test";
import assert from "node:assert/strict";
import { startBridge, request, connectSse } from "./helpers.js";

test("v1 surface: pair → SSE → permission hook → decision round-trip", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  // Pairing over /v1 behaves exactly like /pair
  const pair = await request(port, "POST", "/v1/pair", { body: { code: pairingCode } });
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

  // A permission hook posted to /v1 blocks until a /v1 command decision
  const hookResponse = request(port, "POST", "/v1/hooks/permission", {
    body: { tool_name: "Bash", cwd: "/tmp/e2e-v1-project", tool_input: { command: "ls -la" } },
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

  const hook = await hookResponse;
  assert.equal(hook.status, 200);
  assert.equal(hook.body.hookSpecificOutput.hookEventName, "PermissionRequest");
  assert.equal(hook.body.hookSpecificOutput.decision.behavior, "allow");
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

// The realistic production topology: hook scripts installed by setup-hooks.sh
// POST to the legacy unprefixed paths while a /v1 watch client answers.
// Permission state and tokens are shared across the two surfaces.
test("cross-surface: /v1-paired client answers a legacy hook; tokens interchangeable", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/v1/pair", { body: { code: pairingCode } });
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

  // Claude Code's installed hook posts to the LEGACY path and blocks
  const hookResponse = request(port, "POST", "/hooks/permission", {
    body: { tool_name: "Edit", cwd: "/tmp/e2e-cross", tool_input: { file_path: "a.txt" } },
  });

  // The prompt reaches the /v1 stream, and the /v1 decision resolves the legacy hook
  const promptEvent = await v1Sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Edit",
  );
  const decision = await request(port, "POST", "/v1/command", {
    token,
    body: { permissionId: promptEvent.parsed.permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(decision.status, 200);

  const hook = await hookResponse;
  assert.equal(hook.status, 200);
  assert.equal(hook.body.hookSpecificOutput.decision.behavior, "allow");
});
