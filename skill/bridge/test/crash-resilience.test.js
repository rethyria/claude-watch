// Crash resilience (issue #4): malformed pre-auth input and stray async
// faults must never kill the bridge — a dead bridge tears down every PTY
// session and strands every in-flight permission hook.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { startBridge, request, connectSse, rawRequest, tempDir } from "./helpers.js";

const BRIDGE_DIR = fileURLToPath(new URL("..", import.meta.url));

// Accumulated CPU time of a pid, in whole seconds. `ps -o time=` is the
// portable spelling (macOS ps has no `times`); it prints [[DD-]HH:]MM:SS.
function cpuSeconds(pid) {
  const raw = execFileSync("ps", ["-o", "time=", "-p", String(pid)], { encoding: "utf-8" }).trim();
  const [days, clock] = raw.includes("-") ? raw.split("-") : ["0", raw];
  return clock.split(":").reduce((acc, n) => acc * 60 + Number(n), 0) + Number(days) * 86_400;
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

test("a fault after the parent process dies must not spin the bridge", { timeout: 60_000 }, async (t) => {
  // The guards log every fault they catch. If the log write itself fails, that
  // failure resurfaces as another uncaughtException, gets logged, fails again —
  // an endless cycle that pegs one core. Every turn goes through the event loop,
  // so the bridge keeps answering /ping and looks perfectly healthy: liveness
  // proves nothing here, CPU does. This is how stranded test bridges once
  // accumulated ~28 CPU-hours on a dev machine.
  //
  // Reproducing it needs the bridge's PARENT to be GONE, not merely its pipe
  // closed: destroying the read end from a live parent leaves console.log a
  // harmless no-op, while a dead parent turns each write into an asynchronous
  // EPIPE that resurfaces as an uncaughtException. So spawn the bridge from a
  // shim that exits immediately, and let the fault land after it is gone.
  const credsDir = tempDir(t, "claude-watch-spin-");
  const shimSource = `
    const { spawn } = require("node:child_process");
    const child = spawn(process.execPath, ["server.js"], {
      cwd: ${JSON.stringify(BRIDGE_DIR)},
      env: { ...process.env },
      stdio: ["ignore", "pipe", "pipe"],
    });
    process.stdout.write(String(child.pid));
    process.exit(0);
  `;
  const shim = spawnSync(process.execPath, ["-e", shimSource], {
    encoding: "utf-8",
    env: {
      ...process.env,
      CLAUDE_WATCH_CREDENTIALS_DIR: credsDir,
      CLAUDE_WATCH_DISABLE_MDNS: "1",
      CLAUDE_WATCH_PORT_RANGE_END: "7929",
      CLAUDE_WATCH_TEST_FAULT: "uncaughtException",
      CLAUDE_WATCH_TEST_FAULT_DELAY_MS: "3000",
      // The orphan watchdog (helpers.js) would shut this bridge down before the
      // fault even fires. This test is about the spin itself, so opt out.
      CLAUDE_WATCH_EXIT_WHEN_ORPHANED: "0",
    },
  });
  const pid = Number(shim.stdout.trim());
  assert.ok(pid > 0, `shim must report the bridge pid, got ${JSON.stringify(shim.stdout)}`);
  t.after(() => { try { process.kill(pid, "SIGKILL"); } catch { /* already gone */ } });

  const alive = () => { try { process.kill(pid, 0); return true; } catch { return false; } };

  // Let it boot and let the injected fault fire into the dead log path.
  await new Promise((resolve) => setTimeout(resolve, 4500));
  assert.ok(alive(), "bridge must survive the fault with no reader on its pipes");

  const before = cpuSeconds(pid);
  await new Promise((resolve) => setTimeout(resolve, 4000));
  const spent = cpuSeconds(pid) - before;
  assert.ok(
    spent <= 1,
    `orphaned bridge burned ${spent}s of CPU over 4s idle — the log→EPIPE→log cycle is back`,
  );

  // Still a working bridge, not just a quiet one: it published its port and
  // serves on it. (A spinning bridge answers this too — hence the CPU check.)
  const port = Number(fs.readFileSync(path.join(credsDir, "port"), "utf-8").trim());
  const ping = await request(port, "GET", "/ping");
  assert.equal(ping.status, 200, "orphaned bridge must still serve requests");
});
