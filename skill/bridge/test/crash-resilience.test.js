// Crash resilience (issue #4): malformed pre-auth input and stray async
// faults must never kill the bridge — a dead bridge tears down every PTY
// session and strands every in-flight permission hook.
import { test } from "node:test";
import assert from "node:assert/strict";
import net from "node:net";
import { startBridge, request, connectSse } from "./helpers.js";

// Send raw bytes over a plain TCP socket: fetch/http.request refuse to emit a
// malformed Host header, which is exactly what this attack needs.
function rawRequest(port, payload, timeoutMs = 10_000) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, "127.0.0.1", () => socket.write(payload));
    let data = "";
    socket.setTimeout(timeoutMs, () => { socket.destroy(); resolve(data); });
    socket.on("data", (chunk) => { data += chunk.toString(); });
    socket.on("close", () => resolve(data));
    socket.on("error", reject);
  });
}

test("malformed Host header gets 400; bridge, sessions, and pending permissions survive", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  // Establish real state that must survive the attack: a paired device with
  // an SSE stream and an in-flight (blocked) permission hook.
  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  const sse = connectSse(port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  const hookResponse = request(port, "POST", "/hooks/permission", {
    body: { tool_name: "Bash", cwd: "/tmp/crash-test", tool_input: { command: "ls" } },
  });
  const promptEvent = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
  );

  // The attack: a raw pre-auth request with an invalid Host header. Before
  // the fix this was an unhandled rejection that killed the whole process.
  const raw = await rawRequest(
    port,
    "GET /status HTTP/1.1\r\nHost: bad host\r\nConnection: close\r\n\r\n",
  );
  assert.match(raw, /^HTTP\/1\.1 400 /, "malformed Host must get a 400 response");
  assert.equal(bridge.proc.exitCode, null, "bridge process must survive");

  // A subsequent well-formed request is served normally.
  const status = await request(port, "GET", "/status", { token });
  assert.equal(status.status, 200);
  assert.ok(status.body.bridgeId, "well-formed request served after the attack");

  // The pending permission is still resolvable end to end.
  const decision = await request(port, "POST", "/command", {
    token,
    body: { permissionId: promptEvent.parsed.permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(decision.status, 200);
  const hook = await hookResponse;
  assert.equal(hook.status, 200);
  assert.equal(hook.body.hookSpecificOutput.decision.behavior, "allow");
});

test("unhandledRejection guard logs and keeps the bridge alive", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_TEST_FAULT: "unhandledRejection" },
  });
  await bridge.waitForOutput(/Unhandled promise rejection \(bridge kept alive\)/);
  assert.equal(bridge.proc.exitCode, null, "bridge must survive a stray rejection");
  const ping = await request(bridge.port, "GET", "/ping");
  assert.equal(ping.status, 200);
});

test("uncaughtException guard logs and keeps the bridge alive", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_TEST_FAULT: "uncaughtException" },
  });
  await bridge.waitForOutput(/Uncaught exception \(bridge kept alive\)/);
  assert.equal(bridge.proc.exitCode, null, "bridge must survive a stray exception");
  const ping = await request(bridge.port, "GET", "/ping");
  assert.equal(ping.status, 200);
});
