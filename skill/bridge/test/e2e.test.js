// End-to-end executable spec: the full watch-approval loop against the real
// bridge process, driven exactly like Claude Code hooks + a watch client.
import { test } from "node:test";
import assert from "node:assert/strict";
import { startBridge, request, connectSse } from "./helpers.js";

test("pair → blocking permission hook → SSE → decision → hook unblocks", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  // Wrong pairing code is rejected (derived from the real one so it can never
  // accidentally match)
  const wrongCode = pairingCode === "000000" ? "000001" : "000000";
  const bad = await request(port, "POST", "/pair", { body: { code: wrongCode } });
  assert.equal(bad.status, 401);

  // Correct code pairs and returns a bearer token + snapshot
  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  assert.ok(pair.body.token, "pair response carries a token");
  assert.ok(pair.body.bridgeId, "pair response carries bridgeId");
  assert.ok(Array.isArray(pair.body.sessions), "pair response carries sessions snapshot");
  const token = pair.body.token;

  // Auth is enforced on the privileged surface
  const sseUnauthed = connectSse(port, null);
  t.after(() => sseUnauthed.close());
  assert.equal(await sseUnauthed.statusCode(), 401);
  const cmdUnauthed = await request(port, "POST", "/command", {
    token: "wrong-token",
    body: { permissionId: "x", decision: { behavior: "allow" } },
  });
  assert.equal(cmdUnauthed.status, 401);

  // Watch client connects to the event stream
  const sse = connectSse(port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  // Claude Code hits a permission prompt: the hook POSTs and BLOCKS
  const hookBody = {
    tool_name: "Bash",
    cwd: "/tmp/e2e-project",
    tool_input: { command: "rm -rf ./build" },
    permission_suggestions: [{ type: "rule", value: "Bash(rm:*)" }],
  };
  const hookResponse = request(port, "POST", "/hooks/permission", { body: hookBody });

  // The prompt reaches the watch over SSE
  const promptEvent = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
  );
  const permissionId = promptEvent.parsed.permissionId;
  assert.ok(permissionId, "permission-request carries a permissionId");
  assert.equal(promptEvent.parsed.tool_input.command, "rm -rf ./build");

  // The hook is still blocked while the watch decides
  const race = await Promise.race([
    hookResponse.then(() => "resolved"),
    new Promise((r) => setTimeout(() => r("blocked"), 500).unref()),
  ]);
  assert.equal(race, "blocked", "hook must block until a decision arrives");

  // The watch approves
  const decision = await request(port, "POST", "/command", {
    token,
    body: { permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(decision.status, 200);
  assert.equal(decision.body.ok, true);

  // The blocked hook unblocks with exactly that decision
  const hook = await hookResponse;
  assert.equal(hook.status, 200);
  assert.equal(hook.body.hookSpecificOutput.hookEventName, "PermissionRequest");
  assert.equal(hook.body.hookSpecificOutput.decision.behavior, "allow");
});

test("deny decision reaches the hook with its message", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  const sse = connectSse(port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  const hookResponse = request(port, "POST", "/hooks/permission", {
    body: { tool_name: "Write", cwd: "/tmp/e2e-project", tool_input: { file_path: "/etc/passwd" } },
  });

  const promptEvent = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Write",
  );

  const decision = await request(port, "POST", "/command", {
    token,
    body: {
      permissionId: promptEvent.parsed.permissionId,
      decision: { behavior: "deny", message: "Denied from the watch" },
    },
  });
  assert.equal(decision.status, 200);

  const hook = await hookResponse;
  assert.equal(hook.status, 200);
  assert.equal(hook.body.hookSpecificOutput.decision.behavior, "deny");
  assert.equal(hook.body.hookSpecificOutput.decision.message, "Denied from the watch");
});

test("tool-output hook events reach the watch stream", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  const token = pair.body.token;

  const sse = connectSse(port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  const posted = await request(port, "POST", "/hooks/tool-output", {
    body: { tool_name: "Read", cwd: "/tmp/e2e-project", tool_output: "file contents here" },
  });
  assert.equal(posted.status, 200);

  const event = await sse.waitFor(
    (e) => e.event === "tool-output" && e.parsed?.tool_name === "Read",
  );
  assert.equal(event.parsed.tool_output, "file contents here");
  assert.ok(event.parsed.sessionId, "tool-output is attributed to a session");
});
