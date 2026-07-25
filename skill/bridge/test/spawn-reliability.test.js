// Session spawn / command-injection reliability, black-box: auto-spawn must
// inject the dictated command only after the PTY's first output (the ready
// signal), surface injection failure to the client instead of ok:true, and a
// command that resolves to a PTY-less external session is REFUSED (409), never
// run as a detached headless `claude -p --continue` fork of the live session
// (issue #69).
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

test("failed auto-spawn is not sticky: a retry never silently targets the zombie session", { timeout: 60_000 }, async (t) => {
  // First spawn hangs forever. The failed session must be killed, not left
  // registered as running with a live PTY — otherwise the no-session-id
  // fallback selects it on retry, blind-writes past the ready gate, and
  // returns ok:true while the command is silently swallowed.
  const bin = makeFakeClaude(t, "#!/bin/sh\nexec sleep 30\n");
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_CLAUDE_BIN: bin, CLAUDE_WATCH_SPAWN_INJECT_TIMEOUT_MS: "500" },
  });
  const { token } = await pairAndConnect(t, bridge);

  const first = await request(bridge.port, "POST", "/command", {
    token,
    body: { command: "first attempt\n" },
  });
  assert.equal(first.status, 500);
  const zombieId = first.body.sessionId;
  assert.ok(zombieId, "failure response names the failed session");

  // The failed session must no longer be running.
  const status = await request(bridge.port, "GET", "/status", { token });
  const zombieSnapshot = status.body.sessions.find((s) => s.id === zombieId);
  assert.ok(zombieSnapshot, "failed session is still visible in the snapshot");
  assert.equal(zombieSnapshot.state, "ended", "failed session must not stay 'running'");

  // The retry must NOT return ok:true against the zombie; it spawns fresh
  // (which also fails here, with the same hanging stub) and reports that.
  const retry = await request(bridge.port, "POST", "/command", {
    token,
    body: { command: "retry attempt\n" },
  });
  assert.notEqual(retry.body.ok, true, "retry must not silently swallow the command");
  assert.equal(retry.status, 500);
  assert.notEqual(retry.body.sessionId, zombieId, "retry must spawn fresh, not target the zombie");
});

test("auto-spawn recovers after a failed injection once the agent behaves", { timeout: 60_000 }, async (t) => {
  // The stub hangs on its first invocation and works from the second on. The
  // first command fails; the retry must get a fresh, working session and the
  // command must actually surface in its PTY stream.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-flaky-agent-"));
  t.after(() => {
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* ignore */ }
  });
  const marker = path.join(dir, "already-ran");
  const bin = makeFakeClaude(
    t,
    `#!/bin/sh\nif [ ! -f "${marker}" ]; then touch "${marker}"; exec sleep 30; fi\necho RECOVERED-READY\nexec cat\n`,
  );
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_CLAUDE_BIN: bin, CLAUDE_WATCH_SPAWN_INJECT_TIMEOUT_MS: "500" },
  });
  const { token, sse } = await pairAndConnect(t, bridge);

  const first = await request(bridge.port, "POST", "/command", {
    token,
    body: { command: "lost to the hung agent\n" },
  });
  assert.equal(first.status, 500);

  const retry = await request(bridge.port, "POST", "/command", {
    token,
    body: { command: "second time lucky\n" },
  });
  assert.equal(retry.status, 200);
  assert.equal(retry.body.ok, true);
  assert.equal(retry.body.spawned, true, "retry must spawn a fresh session");
  assert.notEqual(retry.body.sessionId, first.body.sessionId);

  const output = await sse.waitFor(
    (e) => e.event === "pty-output" && e.parsed?.text?.includes("second time lucky"),
  );
  assert.equal(output.parsed.sessionId, retry.body.sessionId);
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

test("a text command that resolves to an external PTY-less session is refused, never forked headlessly (issue #69)", { timeout: 60_000 }, async (t) => {
  // If the bridge DID fork, this stub would run and echo a marker; the test
  // proves it never does.
  const bin = makeFakeClaude(t, '#!/bin/sh\necho "HEADLESS-RAN $@"\n');
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_CLAUDE_BIN: bin } });
  const { token, sse } = await pairAndConnect(t, bridge);

  // A hook from an external Claude instance auto-creates a session the bridge
  // owns no PTY for.
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

  // No session id: the fallback selects that PTY-less external session. It must
  // be REFUSED (409), never turned into a `claude -p --continue` fork of the
  // live interactive session (issue #69).
  const resp = await request(bridge.port, "POST", "/command", {
    token,
    body: { command: "summarize the repo\n" },
  });
  assert.equal(resp.status, 409);
  assert.notEqual(resp.body.ok, true);
  assert.equal(resp.body.external, true);
  assert.equal(resp.body.sessionId, externalSessionId, "the refusal names the external session it resolved to");
  assert.match(resp.body.error, /external session/i);

  // Belt-and-suspenders: give the (refused) request a moment and assert no
  // headless child ever emitted the stub marker.
  await new Promise((r) => setTimeout(r, 300));
  const forked = sse.events.some(
    (e) => e.event === "pty-output" && e.parsed?.text?.includes("HEADLESS-RAN"),
  );
  assert.equal(forked, false, "no detached headless run was spawned");
});

test("a command to an ENDED bridge-owned session is refused as ended, not mislabeled external (issue #69)", { timeout: 60_000 }, async (t) => {
  // A bridge-owned PTY session that has ended lingers in the map (pre-prune)
  // with ptyProcess=null — the same "no PTY" shape as an external session. Its
  // refusal must say it ENDED, never falsely claim it is an external session
  // the bridge does not own (the bridge spawned it).
  const bin = makeFakeClaude(t, "#!/bin/sh\necho SPAWN-READY\nexec cat\n");
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_CLAUDE_BIN: bin } });
  const { token, sse } = await pairAndConnect(t, bridge);

  // Auto-spawn a bridge-owned (NOT external) session and capture its id.
  const spawn = await request(bridge.port, "POST", "/command", {
    token, body: { command: "hello\n" },
  });
  assert.equal(spawn.status, 200);
  const sessionId = spawn.body.sessionId;
  assert.ok(sessionId, "auto-spawn names the session");

  // Kill it and wait for the ended broadcast (state ended, ptyProcess null).
  const kill = await request(bridge.port, "POST", "/command", {
    token, body: { kill: true, sessionId },
  });
  assert.equal(kill.status, 200);
  await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === sessionId && e.parsed?.state === "ended",
  );

  // Dictate at the ended slot before it prunes: an honest "ended" refusal, and
  // crucially NOT an external:true mislabel.
  const resp = await request(bridge.port, "POST", "/command", {
    token, body: { sessionId, command: "too late\n" },
  });
  assert.equal(resp.status, 409);
  assert.notEqual(resp.body.ok, true);
  assert.notEqual(resp.body.external, true, "a bridge-spawned session must not be mislabeled external");
  assert.match(resp.body.error, /ended/i);
});
