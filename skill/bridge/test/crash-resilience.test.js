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

test("a dead log sink degrades to the bridge.log fallback and /v1/status says so", { timeout: 60_000 }, async (t) => {
  // The fix above traded the log→EPIPE→log spin for a bridge that survives a
  // dead sink SILENTLY — every line dropped, nothing announcing it, on a
  // project whose diagnostic doctrine is to read the log (issue #93). So a
  // failed primary write must (a) reroute lines to bridge.log next to the
  // credentials and (b) raise loggingDegraded in /v1/status.
  //
  // Phase 1 — a healthy bridge: pins the flag's resting state, and mints the
  // token the orphaned bridge below is interrogated with (its pairing banner
  // has no reader; credentials persist in credsDir across the restart).
  const credsDir = tempDir(t, "claude-watch-logsink-");
  const first = await startBridge(t, { credentialsDir: credsDir });
  const pair = await request(first.port, "POST", "/pair", { body: { code: first.pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;
  const healthy = await request(first.port, "GET", "/v1/status", { token });
  assert.equal(healthy.body.loggingDegraded, false, "a bridge whose sink works must not claim degradation");
  await first.stop();

  // Phase 2 — the dead-sink state, via the same reproduction trap the spin
  // test documents: the parent PROCESS must be gone, not merely the pipe
  // closed, so the bridge is spawned from a shim that exits immediately.
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
      // A known line to hunt for in the fallback: the guard logs the injected
      // fault well after boot, i.e. squarely into the dead sink.
      CLAUDE_WATCH_TEST_FAULT: "uncaughtException",
      CLAUDE_WATCH_TEST_FAULT_DELAY_MS: "1500",
      // The orphan watchdog would shut this bridge down mid-interrogation.
      CLAUDE_WATCH_EXIT_WHEN_ORPHANED: "0",
    },
  });
  const pid = Number(shim.stdout.trim());
  assert.ok(pid > 0, `shim must report the bridge pid, got ${JSON.stringify(shim.stdout)}`);
  t.after(() => { try { process.kill(pid, "SIGKILL"); } catch { /* already gone */ } });

  // The orphan republishes its port (the graceful stop above removed the
  // first bridge's file, so this never reads a stale one).
  const portFile = path.join(credsDir, "port");
  let port = null;
  const portDeadline = Date.now() + 15_000;
  while (Date.now() < portDeadline && !port) {
    try { port = Number(fs.readFileSync(portFile, "utf-8").trim()) || null; } catch { /* not yet */ }
    if (!port) await new Promise((r) => setTimeout(r, 100));
  }
  assert.ok(port, "orphaned bridge must publish its port");

  // (a) The lines: the fallback file opens by naming the failure, and the
  // guard's fault line — logged ~1.5 s into the dead-sink state — lands in it
  // rather than vanishing.
  const fallbackLog = path.join(credsDir, "bridge.log");
  let logged = "";
  const lineDeadline = Date.now() + 15_000;
  while (Date.now() < lineDeadline && !/Uncaught exception \(bridge kept alive\)/.test(logged)) {
    try { logged = fs.readFileSync(fallbackLog, "utf-8"); } catch { /* not yet */ }
    await new Promise((r) => setTimeout(r, 150));
  }
  assert.match(logged, /Primary log sink failed/, "the fallback must announce why it took over");
  assert.match(
    logged,
    /Uncaught exception \(bridge kept alive\).*injected test fault/,
    "a line logged after the sink died must land in the fallback file, not vanish",
  );

  // (b) The flag: visible from the admin surface instead of inferred from
  // silence.
  const status = await request(port, "GET", "/v1/status", { token });
  assert.equal(status.status, 200, "orphaned bridge must still serve /v1/status");
  assert.equal(status.body.loggingDegraded, true, "a dead log sink must be announced in /v1/status");
});
