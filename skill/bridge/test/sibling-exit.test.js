// The spawn race's mutex (#92): several Zed windows starting at once each
// probe for a bridge, each find nothing, and each spawn one — and the port
// bind decides which spawned bridge serves. --exit-if-sibling is how the
// adapter's spawn tells the bridge to LOSE that race gracefully: EADDRINUSE
// from a port that answers /ping like a bridge is a sibling that won, so exit
// 0 instead of walking on as a duplicate; any other occupant (7860 is Gradio's
// default) keeps the ordinary walk. A manual start never passes the flag and
// keeps today's sibling-tolerant behavior — that path is untouched, so only
// the flagged paths are pinned here.
import { test } from "node:test";
import assert from "node:assert";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { startBridge, request, tempDir } from "./helpers.js";

const BRIDGE_DIR = fileURLToPath(new URL("..", import.meta.url));

// Raw spawn (not startBridge): these bridges are EXPECTED to exit or to bind
// off the range start, and startBridge treats an early exit as a failure.
function spawnFlaggedBridge(t, { credsDir, rangeStart, rangeEnd }) {
  const proc = spawn(process.execPath, ["server.js", "--exit-if-sibling"], {
    cwd: BRIDGE_DIR,
    env: {
      ...process.env,
      CLAUDE_WATCH_CREDENTIALS_DIR: credsDir,
      CLAUDE_WATCH_DISABLE_MDNS: "1",
      CLAUDE_WATCH_EXIT_WHEN_ORPHANED: "1",
      CLAUDE_WATCH_NO_IDLE_EXIT: "1",
      CLAUDE_WATCH_PORT_RANGE_START: String(rangeStart),
      CLAUDE_WATCH_PORT_RANGE_END: String(rangeEnd),
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let out = "";
  proc.stdout.on("data", (d) => { out += d.toString(); });
  proc.stderr.on("data", (d) => { out += d.toString(); });
  t.after(() => { try { proc.kill("SIGKILL"); } catch { /* already gone */ } });
  return {
    proc,
    output: () => out,
    waitForExit: (timeoutMs = 15_000) =>
      new Promise((resolve, reject) => {
        if (proc.exitCode !== null) return resolve(proc.exitCode);
        const timer = setTimeout(
          () => reject(new Error(`bridge still running after ${timeoutMs}ms\n${out}`)),
          timeoutMs,
        );
        timer.unref();
        proc.on("exit", (code) => { clearTimeout(timer); resolve(code); });
      }),
    waitForOutput: async (regex, timeoutMs = 15_000) => {
      const deadline = Date.now() + timeoutMs;
      while (!regex.test(out)) {
        if (Date.now() > deadline) throw new Error(`output not matching ${regex} within ${timeoutMs}ms\n${out}`);
        await new Promise((r) => setTimeout(r, 25).unref());
      }
      return out.match(regex);
    },
  };
}

test("--exit-if-sibling: losing the bind to a live bridge is a clean exit, not a walk", async (t) => {
  const winner = await startBridge(t);
  const credsDir = tempDir(t, "claude-watch-sibling-");
  // The loser's whole range IS the winner's port, so the only escape routes
  // are the sibling exit (correct) or walking off the range end (the bug).
  const loser = spawnFlaggedBridge(t, {
    credsDir,
    rangeStart: winner.port,
    rangeEnd: winner.port,
  });

  const code = await loser.waitForExit();
  assert.equal(code, 0, `losing the race must be a clean exit\n${loser.output()}`);
  assert.match(loser.output(), /a sibling won the bind race/);
  // The loser never bound, so it must not have clobbered anyone's port file.
  assert.ok(!fs.existsSync(path.join(credsDir, "port")), "the loser must not write a port file");
  // The winner is untouched and still serving.
  const ping = await request(winner.port, "GET", "/ping");
  assert.equal(ping.status, 200);
});

test("--exit-if-sibling: a non-bridge occupant is walked past, exactly like a manual start", async (t) => {
  // A Gradio-shaped squatter: answers HTTP but not with a bridgeId, so the
  // sibling probe must reject it and the walk must continue. Pinned inside the
  // e2e's isolated test range (7970-7999) like every throwaway listener.
  const decoyPort = 7980;
  const decoy = http.createServer((req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end("{}");
  });
  await new Promise((resolve, reject) => {
    decoy.on("error", reject);
    decoy.listen(decoyPort, "127.0.0.1", resolve);
  });
  t.after(() => decoy.close());

  const credsDir = tempDir(t, "claude-watch-decoy-");
  const bridge = spawnFlaggedBridge(t, {
    credsDir,
    rangeStart: decoyPort,
    rangeEnd: decoyPort + 3,
  });

  const portMatch = await bridge.waitForOutput(/Port:\s+(\d+)/);
  const boundPort = parseInt(portMatch[1], 10);
  assert.ok(boundPort > decoyPort, `bridge must walk past the decoy, bound ${boundPort}`);
  assert.equal(bridge.proc.exitCode, null, `bridge must not mistake the decoy for a sibling\n${bridge.output()}`);
  const ping = await request(boundPort, "GET", "/ping");
  assert.equal(ping.status, 200);
  assert.equal(typeof ping.body.bridgeId, "string");
});
