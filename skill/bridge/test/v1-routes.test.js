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

  // Both surfaces answer /status with the same bridge identity
  const v1Status = await request(port, "GET", "/v1/status");
  assert.equal(v1Status.status, 200);
  const legacyStatus = await request(port, "GET", "/status");
  assert.equal(legacyStatus.status, 200);
  assert.equal(legacyStatus.body.bridgeId, v1Status.body.bridgeId);

  // Unknown paths under /v1 are 404, same as legacy
  const missing = await request(port, "GET", "/v1/does-not-exist");
  assert.equal(missing.status, 404);

  // Legacy pairing still works after the /v1 skeleton landed
  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  assert.ok(pair.body.token, "legacy pair response carries a token");
});
