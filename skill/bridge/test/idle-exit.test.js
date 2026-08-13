// The bridge's lifetime is coupled to Zed (#92): the Zed-launched adapter
// spawns the bridge (bridge-channel.ts's ensureBridgeRunning — pinned from the
// adapter's own suite), and this file pins the reaper half. With ZERO fork
// inboxes for the grace window the bridge exits cleanly on its own; a held
// inbox parks the clock; a watch SSE client deliberately does NOT — Zed closed
// is an honest offline on the wrist, never a bridge kept alive against a dead
// editor. helpers.js pins the reaper OFF for every other test
// (CLAUDE_WATCH_NO_IDLE_EXIT=1), so each test here re-enables it explicitly
// with a milliseconds-scale grace.
import { test } from "node:test";
import assert from "node:assert";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { startBridge, request, connectSse } from "./helpers.js";

// Anything but "1" leaves the reaper armed — helpers.js pins "1", this unpins.
const reaperEnv = (graceMs) => ({
  CLAUDE_WATCH_NO_IDLE_EXIT: "0",
  CLAUDE_WATCH_IDLE_EXIT_MS: String(graceMs),
});

function waitForExit(proc, timeoutMs = 15_000) {
  return new Promise((resolve, reject) => {
    if (proc.exitCode !== null) return resolve(proc.exitCode);
    const timer = setTimeout(
      () => reject(new Error(`bridge still running after ${timeoutMs}ms`)),
      timeoutMs,
    );
    timer.unref();
    proc.on("exit", (code) => {
      clearTimeout(timer);
      resolve(code);
    });
  });
}

// A fake fork inbox: exactly the long-lived GET the adapter holds. Resolves
// once the SSE response headers arrive (the bridge has registered the inbox).
function openInbox(port, connection) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: "127.0.0.1", port, path: `/acp/inbox?connection=${connection}`, method: "GET" },
      (res) => resolve({ res, close: () => req.destroy() }),
    );
    req.on("error", reject);
    req.end();
  });
}

test("forkless for the grace window: the bridge exits cleanly and says why", async (t) => {
  const bridge = await startBridge(t, { env: reaperEnv(800) });

  const code = await waitForExit(bridge.proc);
  assert.equal(code, 0, `idle self-reap must be a clean exit\n${bridge.output()}`);
  assert.match(bridge.output(), /idle self-reap, #92/, "the exit must log its reason");
  // Graceful, not just dead: the shutdown path retired the port file, so the
  // next adapter probe walks instead of trusting a stale entry.
  assert.ok(
    !fs.existsSync(path.join(bridge.credentialsDir, "port")),
    "a self-reaped bridge must not leave its port file behind",
  );
});

test("a held fork inbox parks the reaper; dropping it restarts the clock", async (t) => {
  const bridge = await startBridge(t, { env: reaperEnv(800) });
  const inbox = await openInbox(bridge.port, "idle-exit-fork");
  await bridge.waitForOutput(/ACP fork inbox connected/);

  // Well past the grace window with the inbox held: still serving.
  await new Promise((r) => setTimeout(r, 2000));
  assert.equal(bridge.proc.exitCode, null, `bridge must outlive the grace while a fork is connected\n${bridge.output()}`);
  const ping = await request(bridge.port, "GET", "/ping");
  assert.equal(ping.status, 200);

  // The fork goes away (Zed quit): the clock starts NOW, not at boot.
  inbox.close();
  const code = await waitForExit(bridge.proc);
  assert.equal(code, 0, `bridge must reap after the fork drops\n${bridge.output()}`);
  assert.match(bridge.output(), /idle self-reap, #92/);
});

test("a connected watch SSE client does NOT hold the bridge past the grace", async (t) => {
  // Grace wide enough to pair and connect the SSE stream first, then reap.
  const bridge = await startBridge(t, { env: reaperEnv(2500) });
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  const sse = connectSse(bridge.port, pair.body.token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  // Zed closed means honest offline on the wrist (the doctrine): the stream
  // being open must not stop the reap.
  const code = await waitForExit(bridge.proc);
  assert.equal(code, 0, `a watch SSE client must not pin a Zed-less bridge alive\n${bridge.output()}`);
  assert.match(bridge.output(), /idle self-reap, #92/);
});

test("CLAUDE_WATCH_NO_IDLE_EXIT=1 keeps a forkless bridge alive (manual debugging)", async (t) => {
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_NO_IDLE_EXIT: "1", CLAUDE_WATCH_IDLE_EXIT_MS: "300" },
  });
  assert.match(bridge.output(), /Idle self-reap disabled/, "the opt-out must announce itself");

  await new Promise((r) => setTimeout(r, 1500));
  assert.equal(bridge.proc.exitCode, null, `opted-out bridge must survive with no fork\n${bridge.output()}`);
  const ping = await request(bridge.port, "GET", "/ping");
  assert.equal(ping.status, 200);
});
