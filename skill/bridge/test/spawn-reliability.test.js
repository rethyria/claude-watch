// Session spawn / command-injection reliability, black-box: auto-spawn must
// inject the dictated command only after the PTY's first output (the ready
// signal), surface injection failure to the client instead of ok:true, and
// the no-session-id fallback must run PTY-less external sessions through the
// headless CLI branch instead of dereferencing a null ptyProcess.
//
// Each test points the bridge at a stub `claude` binary via the test-only
// CLAUDE_WATCH_CLAUDE_BIN override so agent behavior is deterministic.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { startBridge, request, connectSse } from "./helpers.js";

function makeFakeClaude(t, script) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-fake-bin-"));
  t.after(() => {
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* ignore */ }
  });
  const bin = path.join(dir, "claude");
  fs.writeFileSync(bin, script, { mode: 0o755 });
  return bin;
}

async function pairAndConnect(t, bridge) {
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  const sse = connectSse(bridge.port, pair.body.token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  return { token: pair.body.token, sse };
}

test("auto-spawn injects the command only after the first pty-output", { timeout: 60_000 }, async (t) => {
  // The stub agent stays silent for 700 ms before printing its ready marker.
  // A blind timed write would hit the PTY during the silence and the PTY echo
  // would surface the command BEFORE the marker; the ready-gated write can
  // only ever surface it after.
  const bin = makeFakeClaude(t, "#!/bin/sh\nsleep 0.7\necho SPAWN-READY-MARKER\nexec cat\n");
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_CLAUDE_BIN: bin } });
  const { token, sse } = await pairAndConnect(t, bridge);

  const resp = await request(bridge.port, "POST", "/command", {
    token,
    body: { command: "inject-after-ready\n" },
  });
  assert.equal(resp.status, 200);
  assert.equal(resp.body.ok, true);
  assert.equal(resp.body.spawned, true);
  assert.ok(resp.body.sessionId, "response names the auto-spawned session");

  await sse.waitFor((e) => e.event === "pty-output" && e.parsed?.text?.includes("inject-after-ready"));
  const ptyEvents = sse.events.filter((e) => e.event === "pty-output");
  const readyIdx = ptyEvents.findIndex((e) => e.parsed?.text?.includes("SPAWN-READY-MARKER"));
  const commandIdx = ptyEvents.findIndex((e) => e.parsed?.text?.includes("inject-after-ready"));
  assert.notEqual(readyIdx, -1, "ready marker must appear in the PTY stream");
  assert.ok(
    commandIdx > readyIdx,
    `command must be injected after the first pty-output (ready at #${readyIdx}, command at #${commandIdx})`,
  );
});

test("auto-spawn that never becomes ready surfaces an error, not ok:true", { timeout: 60_000 }, async (t) => {
  // The stub agent produces no output at all; the bounded ready wait must
  // expire and the client must learn the command was NOT injected.
  const bin = makeFakeClaude(t, "#!/bin/sh\nexec sleep 30\n");
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_CLAUDE_BIN: bin, CLAUDE_WATCH_SPAWN_INJECT_TIMEOUT_MS: "500" },
  });
  const { token } = await pairAndConnect(t, bridge);

  const resp = await request(bridge.port, "POST", "/command", {
    token,
    body: { command: "this must not vanish silently\n" },
  });
  assert.equal(resp.status, 500, "injection failure must not report success");
  assert.notEqual(resp.body.ok, true);
  assert.match(resp.body.error, /no output/i);
});

test("auto-spawn whose PTY dies immediately surfaces an error and leaves the bridge alive", { timeout: 60_000 }, async (t) => {
  const bin = makeFakeClaude(t, "#!/bin/sh\nexit 1\n");
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_CLAUDE_BIN: bin, CLAUDE_WATCH_SPAWN_INJECT_TIMEOUT_MS: "2000" },
  });
  const { token } = await pairAndConnect(t, bridge);

  const resp = await request(bridge.port, "POST", "/command", {
    token,
    body: { command: "into a dead pty\n" },
  });
  assert.equal(resp.status, 500, "dead PTY must not report success");
  assert.notEqual(resp.body.ok, true);

  // The write racing the child's death must not have crashed the bridge.
  const status = await request(bridge.port, "GET", "/status", { token });
  assert.equal(status.status, 200, "bridge must still be responsive");
  assert.ok(!/uncaughtException/.test(bridge.output()), "no uncaught exception may be logged");
});

test("command without session id against an external PTY-less session runs the headless branch", { timeout: 60_000 }, async (t) => {
  const bin = makeFakeClaude(t, '#!/bin/sh\necho "HEADLESS-RAN $@"\n');
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_CLAUDE_BIN: bin } });
  const { token, sse } = await pairAndConnect(t, bridge);

  // A hook from an external Claude instance auto-creates a session the bridge
  // owns no PTY for. The cwd must exist: the headless branch spawns the agent
  // CLI inside it.
  const projectDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-headless-project-"));
  t.after(() => {
    try { fs.rmSync(projectDir, { recursive: true, force: true }); } catch { /* ignore */ }
  });
  const hook = await request(bridge.port, "POST", "/hooks/tool-output", {
    body: { tool_name: "Read", cwd: projectDir, tool_output: "file contents" },
  });
  assert.equal(hook.status, 200);
  const toolEvent = await sse.waitFor((e) => e.event === "tool-output" && e.parsed?.tool_name === "Read");
  const externalSessionId = toolEvent.parsed.sessionId;
  assert.ok(externalSessionId, "hook must be attributed to a session");

  // No session id: the fallback selects that PTY-less session. This used to
  // 500 with a null dereference on ptyProcess.stdin; it must instead run the
  // prompt via the headless CLI branch.
  const resp = await request(bridge.port, "POST", "/command", {
    token,
    body: { command: "summarize the repo\n" },
  });
  assert.equal(resp.status, 200);
  assert.equal(resp.body.ok, true);
  assert.equal(resp.body.prompt, true, "must take the headless prompt branch");
  assert.equal(resp.body.sessionId, externalSessionId, "output is attributed to the external session");

  const output = await sse.waitFor(
    (e) => e.event === "pty-output" && e.parsed?.text?.includes("HEADLESS-RAN"),
  );
  assert.equal(output.parsed.sessionId, externalSessionId);
  assert.match(output.parsed.text, /-p summarize the repo --continue/);
});
