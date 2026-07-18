// Spawn target directory semantics (issue #56), black-box: the spawn action's
// optional `cwd` must land the session in the requested directory, the
// literal "~" must resolve to the bridge user's home (the "no project"
// sentinel — the watch cannot know that path), an invalid target must 400
// WITHOUT minting a session slot (before validation it spawned a PTY that
// died into an instantly-ended session), and an omitted cwd must keep the
// historical fallback chain (bridge CLI positional arg first).
//
// Each test points the bridge at a stub `claude` binary via the test-only
// CLAUDE_WATCH_CLAUDE_BIN override so no real agent ever launches.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { startBridge, request, tempDir } from "./helpers.js";

// A stub agent that reports readiness immediately and then idles: spawn
// succeeds deterministically and the auto-spawn injection path's ready gate
// opens without delay.
function makeFakeClaude(t) {
  const dir = tempDir(t, "claude-watch-spawn-cwd-bin-");
  const bin = path.join(dir, "claude");
  fs.writeFileSync(bin, "#!/bin/sh\necho SPAWN-CWD-READY\nexec cat\n", { mode: 0o755 });
  return bin;
}

async function pairedBridge(t, { args } = {}) {
  const bridge = await startBridge(t, {
    args,
    env: { CLAUDE_WATCH_CLAUDE_BIN: makeFakeClaude(t) },
  });
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  return { bridge, token: pair.body.token };
}

async function sessionSnapshot(bridge, token) {
  const status = await request(bridge.port, "GET", "/v1/status", { token });
  assert.equal(status.status, 200);
  return status.body.sessions;
}

test("spawn with a valid cwd lands the session in that directory", { timeout: 60_000 }, async (t) => {
  const { bridge, token } = await pairedBridge(t);
  const projectDir = tempDir(t, "claude-watch-spawn-cwd-project-");

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude", cwd: projectDir },
  });
  assert.equal(resp.status, 200);
  assert.equal(resp.body.ok, true);
  assert.ok(resp.body.sessionId, "spawn response names the new session");

  const slot = (await sessionSnapshot(bridge, token)).find((s) => s.id === resp.body.sessionId);
  assert.ok(slot, "the spawned session appears in the snapshot");
  assert.equal(slot.cwd, projectDir, "the session's cwd is the requested directory");
});

test('the "~" sentinel spawns the session in the bridge user\'s home', { timeout: 60_000 }, async (t) => {
  const { bridge, token } = await pairedBridge(t);

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude", cwd: "~" },
  });
  assert.equal(resp.status, 200);
  assert.equal(resp.body.ok, true);

  const slot = (await sessionSnapshot(bridge, token)).find((s) => s.id === resp.body.sessionId);
  assert.ok(slot, "the spawned session appears in the snapshot");
  // The bridge child inherits this process's HOME, so os.homedir() here is
  // the bridge user's home there.
  assert.equal(slot.cwd, os.homedir(), '"~" resolves to the bridge user\'s home directory');
});

test("an invalid spawn cwd 400s and mints no session slot", { timeout: 60_000 }, async (t) => {
  const { bridge, token } = await pairedBridge(t);
  const missing = path.join(os.tmpdir(), "claude-watch-spawn-cwd-does-not-exist");

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude", cwd: missing },
  });
  assert.equal(resp.status, 400, "a non-existent directory must be refused, not spawned into");
  assert.match(resp.body.error, /spawn cwd is not a directory/);
  assert.notEqual(resp.body.ok, true);

  // A relative path never resolves against the bridge's own cwd.
  const relative = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude", cwd: "some/relative/dir" },
  });
  assert.equal(relative.status, 400);
  assert.match(relative.body.error, /spawn cwd is not a directory/);

  // A file (exists, but is not a directory) is refused too.
  const fileDir = tempDir(t, "claude-watch-spawn-cwd-file-");
  const filePath = path.join(fileDir, "a-file");
  fs.writeFileSync(filePath, "not a directory\n");
  const asFile = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude", cwd: filePath },
  });
  assert.equal(asFile.status, 400);
  assert.match(asFile.body.error, /spawn cwd is not a directory/);

  assert.deepEqual(await sessionSnapshot(bridge, token), [],
    "no session slot — not even an instantly-ended one — was created");
});

test("the auto-spawn command site validates cwd identically", { timeout: 60_000 }, async (t) => {
  const { bridge, token } = await pairedBridge(t);
  const missing = path.join(os.tmpdir(), "claude-watch-spawn-cwd-auto-missing");

  // Invalid target: 400, no slot.
  const bad = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { command: "hello\n", cwd: missing },
  });
  assert.equal(bad.status, 400);
  assert.match(bad.body.error, /spawn cwd is not a directory/);
  assert.deepEqual(await sessionSnapshot(bridge, token), [], "no session slot was created");

  // Valid target: the auto-spawned session lands there.
  const projectDir = tempDir(t, "claude-watch-spawn-cwd-auto-project-");
  const good = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { command: "hello\n", cwd: projectDir },
  });
  assert.equal(good.status, 200);
  assert.equal(good.body.ok, true);
  assert.equal(good.body.spawned, true);
  const slot = (await sessionSnapshot(bridge, token)).find((s) => s.id === good.body.sessionId);
  assert.equal(slot.cwd, projectDir);
});

test("an omitted cwd keeps the historical fallback chain (bridge CLI arg first)", { timeout: 60_000 }, async (t) => {
  // The bridge's optional positional argument is the head of the fallback
  // chain; a spawn without cwd must land there, exactly as before #56.
  const cliCwd = tempDir(t, "claude-watch-spawn-cwd-cli-");
  const { bridge, token } = await pairedBridge(t, { args: [cliCwd] });

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "claude" },
  });
  assert.equal(resp.status, 200);
  assert.equal(resp.body.ok, true);

  const slot = (await sessionSnapshot(bridge, token)).find((s) => s.id === resp.body.sessionId);
  assert.equal(slot.cwd, cliCwd, "omitted cwd falls back to the bridge's CLI positional arg");
});
