// Permission delivery reliability, black-box (issue #9): fast no-decision
// when nobody is connected, close-cleanup of aborted hook requests, the
// bridge-side auto-deny landing before the hook's client-side timeout, and
// the connect-time snapshot (sessions + terminal backlog + ALL pending
// permissions) that survives ring-buffer eviction.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { startBridge, request, connectSse } from "./helpers.js";

// Raw fetch for the blocking permission hook so tests can abort it (simulating
// Claude Code answering in the terminal / Esc / its own hook timeout).
function postPermissionHook(port, body, signal) {
  return fetch(`http://127.0.0.1:${port}/hooks/permission`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal,
  });
}

test("zero connected clients: hook gets an immediate no-decision response", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  // No SSE client is connected. The hook must NOT block for the auto-deny
  // window (production default is minutes) — it returns right away with no
  // decision, so the terminal dialog appears normally.
  const startedAt = Date.now();
  const hook = await request(port, "POST", "/hooks/permission", {
    body: { tool_name: "Bash", cwd: "/tmp/no-clients-project", tool_input: { command: "ls" } },
  });
  const elapsed = Date.now() - startedAt;

  assert.equal(hook.status, 200);
  assert.equal(hook.body.hookSpecificOutput?.decision, undefined, "response must carry no decision");
  assert.deepEqual(hook.body, {}, "no-decision response is an empty object");
  assert.ok(elapsed < 10_000, `no-decision must be immediate, took ${elapsed}ms`);
  await bridge.waitForOutput(/no connected clients, returning no-decision/);

  // Nothing was registered: no zombie pending permission survives.
  const status = await request(port, "GET", "/status", { token });
  assert.equal(status.body.pendingPermissions, 0);

  // A client connecting afterwards sees no ghost permission-request either
  // (the hook request is long gone — an answer would go nowhere).
  const sse = connectSse(port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await new Promise((r) => setTimeout(r, 500).unref());
  assert.ok(
    !sse.events.some((e) => e.event === "permission-request"),
    "no permission-request must reach a late-connecting client",
  );
});

test("aborted hook request: pending entry removed, permission-cleared emitted, late answer 404s", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  const sse = connectSse(port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  // Claude Code blocks on the permission hook...
  const controller = new AbortController();
  const hookResult = postPermissionHook(port, {
    tool_name: "Bash",
    cwd: "/tmp/abort-project",
    tool_input: { command: "rm -rf ./build" },
    permission_suggestions: [{ type: "rule", value: "Bash(rm:*)" }],
  }, controller.signal).then(
    () => "resolved",
    () => "aborted",
  );

  const promptEvent = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
  );
  const permissionId = promptEvent.parsed.permissionId;
  assert.ok(permissionId);

  // ...then gives up (terminal answer, Esc, or its own timeout) and aborts.
  controller.abort();
  assert.equal(await hookResult, "aborted");

  // The watch is told to dismiss the now-dead prompt.
  const cleared = await sse.waitFor(
    (e) => e.event === "permission-cleared" && e.parsed?.permissionId === permissionId,
  );
  assert.equal(cleared.parsed.reason, "hook-aborted");
  await bridge.waitForOutput(/aborted by Claude Code, clearing pending prompt/);

  // The pending entry is gone: a late answer finds nothing instead of feeding
  // a dead socket.
  const late = await request(port, "POST", "/command", {
    token,
    body: { permissionId, decision: { behavior: "allow" }, allowAll: true },
  });
  assert.equal(late.status, 404);

  const status = await request(port, "GET", "/status", { token });
  assert.equal(status.body.pendingPermissions, 0, "aborted permission must not stay pending");
});

test("bridge no-decision lands before the hook-side timeout window", { timeout: 60_000 }, async (t) => {
  // Shrink only the HOOK-side window; the bridge must derive its own expiry
  // to fire deterministically before it (previously both were exactly 600s
  // and expiry raced).
  const hookWindowMs = 4000;
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_HOOK_PERMISSION_TIMEOUT_MS: String(hookWindowMs) },
  });
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  const sse = connectSse(port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  // The hook client gives up (aborts) after its window, exactly like Claude
  // Code's hook timeout. The watch never answers.
  const startedAt = Date.now();
  const hookRes = await postPermissionHook(port, {
    tool_name: "Bash",
    cwd: "/tmp/deconflict-project",
    tool_input: { command: "make deploy" },
  }, AbortSignal.timeout(hookWindowMs));
  const elapsed = Date.now() - startedAt;

  // The bridge answered BEFORE the hook-side window elapsed — the fetch above
  // would have thrown TimeoutError otherwise. The answer is NO DECISION, not
  // a deny: the bridge never fabricates a refusal the user did not choose
  // (issue #63), so the agent's own prompt keeps the answer.
  assert.equal(hookRes.status, 200);
  const hookBody = await hookRes.json();
  assert.deepEqual(hookBody, {}, "expiry must return no decision, never a deny");
  assert.ok(
    elapsed < hookWindowMs,
    `expiry must land inside the hook window (${hookWindowMs}ms), took ${elapsed}ms`,
  );
  await bridge.waitForOutput(/expired after .*s unanswered/);
});

test("client connecting after ring-buffer eviction still receives pending permissions", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_SSE_BUFFER_SIZE: "8" },
  });
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  // A watch is connected while the prompt fires...
  const sseA = connectSse(port, token);
  assert.equal(await sseA.statusCode(), 200);

  const hookResponse = request(port, "POST", "/hooks/permission", {
    body: { tool_name: "Write", cwd: "/tmp/evict-project", tool_input: { file_path: "/tmp/x" } },
  });
  const promptEvent = await sseA.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Write",
  );
  const permissionId = promptEvent.parsed.permissionId;
  assert.ok(permissionId);

  // ...then disconnects without answering.
  sseA.close();

  // Ordinary terminal traffic rolls the 8-slot ring buffer over, evicting the
  // buffered permission-request.
  for (let i = 0; i < 12; i++) {
    const posted = await request(port, "POST", "/hooks/tool-output", {
      body: { tool_name: "Read", cwd: "/tmp/evict-project", tool_output: `filler ${i}` },
    });
    assert.equal(posted.status, 200);
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

  // And answering it still unblocks the original hook.
  const decision = await request(port, "POST", "/command", {
    token,
    body: { permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(decision.status, 200);
  const hook = await hookResponse;
  assert.equal(hook.status, 200);
  assert.equal(hook.body.hookSpecificOutput.decision.behavior, "allow");
});

test("connect-time snapshot includes sessions, terminal backlog, and pending permissions", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  // First client online while activity happens.
  const sseA = connectSse(port, token);
  t.after(() => sseA.close());
  assert.equal(await sseA.statusCode(), 200);

  // Terminal output + a blocking permission prompt.
  const posted = await request(port, "POST", "/hooks/tool-output", {
    body: { tool_name: "Read", cwd: "/tmp/snapshot-project", tool_output: "snapshot-marker-output" },
  });
  assert.equal(posted.status, 200);
  const hookResponse = request(port, "POST", "/hooks/permission", {
    body: { tool_name: "Bash", cwd: "/tmp/snapshot-project", tool_input: { command: "npm test" } },
  });
  const promptEvent = await sseA.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
  );
  const permissionId = promptEvent.parsed.permissionId;

  // A brand-new client (fresh pair: no Last-Event-ID) connects afterwards and
  // must be told everything: the running session, the recent terminal
  // backlog, and the prompt still awaiting an answer.
  const sseB = connectSse(port, token);
  t.after(() => sseB.close());
  assert.equal(await sseB.statusCode(), 200);

  const sessionEvent = await sseB.waitFor(
    (e) => e.event === "session" && e.parsed?.folderName === "snapshot-project",
  );
  assert.equal(sessionEvent.parsed.state, "running");

  const backlogEvent = await sseB.waitFor(
    (e) => e.event === "tool-output" && e.parsed?.tool_output === "snapshot-marker-output",
  );
  assert.ok(backlogEvent.parsed.sessionId, "backlog terminal event keeps its session attribution");

  const pendingEvent = await sseB.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.permissionId === permissionId,
  );
  assert.equal(pendingEvent.parsed.tool_input.command, "npm test");

  // Unblock the hook so teardown is clean.
  const decision = await request(port, "POST", "/command", {
    token,
    body: { permissionId, decision: { behavior: "deny", message: "test done" } },
  });
  assert.equal(decision.status, 200);
  const hook = await hookResponse;
  assert.equal(hook.body.hookSpecificOutput.decision.behavior, "deny");
});

test("installer hook timeout and bridge auto-deny share one constant, bridge fires first", async () => {
  const script = fs.readFileSync(new URL("../../setup-hooks.sh", import.meta.url), "utf-8");
  const match = script.match(/^PERMISSION_HOOK_TIMEOUT_S=(\d+)$/m);
  assert.ok(match, "setup-hooks.sh must define PERMISSION_HOOK_TIMEOUT_S");
  const installerTimeoutMs = parseInt(match[1], 10) * 1000;
  assert.ok(
    script.includes("'timeout': PERMISSION_TIMEOUT_S"),
    "the PermissionRequest hook entry must use the shared constant",
  );

  // This test file's process has no CLAUDE_WATCH_* overrides, so config.js
  // yields the production values here.
  const { HOOK_PERMISSION_TIMEOUT_MS, PERMISSION_TIMEOUT_MS } = await import("../config.js");
  assert.equal(
    HOOK_PERMISSION_TIMEOUT_MS,
    installerTimeoutMs,
    "bridge's view of the hook-side timeout must match the installer",
  );
  assert.ok(
    PERMISSION_TIMEOUT_MS < HOOK_PERMISSION_TIMEOUT_MS,
    `bridge auto-deny (${PERMISSION_TIMEOUT_MS}ms) must fire before the hook-side timeout (${HOOK_PERMISSION_TIMEOUT_MS}ms)`,
  );
});
