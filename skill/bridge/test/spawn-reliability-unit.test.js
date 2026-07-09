// Spawn/injection reliability, in-process: the stdin-write-vs-child-death
// race and the first-output readiness gate are exercised directly against
// sessions.js with real child processes — the exact interleavings are
// impractical to force over a real socket. Black-box coverage lives in
// spawn-reliability.test.js.
//
// These tests run in their own process (node --test isolates test files), so
// an uncaught stream error — the pre-fix EPIPE crash — fails the file.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { spawn as childSpawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Never let a bridge module near the real ~/.claude-watch, even lazily.
const credsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-unit-creds-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = credsDir;
after(() => {
  try { fs.rmSync(credsDir, { recursive: true, force: true }); } catch { /* ignore */ }
});

function makeSlot(id, proc) {
  return {
    id,
    agent: "claude",
    cwd: "/tmp/spawn-unit",
    folderName: "spawn-unit",
    ptyProcess: proc,
    state: "running",
    createdAt: Date.now(),
  };
}

function spawnChild(cmd) {
  return childSpawn("/bin/sh", ["-c", cmd], { stdio: ["pipe", "pipe", "pipe"] });
}

test("stdin write racing child death does not crash the bridge", async (t) => {
  const { bindPtyProcess, writeToSessionStdin } = await import("../sessions.js");

  const proc = spawnChild("exit 0");
  const slot = makeSlot("unit-race-death", proc);
  bindPtyProcess(slot, proc);

  // Wait for the child to die ('exit' fires before 'close', so the stream
  // teardown is still in flight — exactly the production race window).
  await new Promise((resolve) => proc.on("exit", resolve));

  // A raw write slipping through at this moment surfaces as an asynchronous
  // EPIPE 'error' event on stdin; without the listener bindPtyProcess
  // attaches, that is an uncaught exception that kills the process.
  try { proc.stdin.write("racing write\n"); } catch { /* sync throw is fine too */ }
  await new Promise((resolve) => setTimeout(resolve, 300));

  // The guarded write must refuse the dead PTY rather than pretend success.
  assert.equal(writeToSessionStdin(slot, "late write\n"), false);
});

test("an async stdin 'error' event is absorbed by the session's error listener", async (t) => {
  const { bindPtyProcess, writeToSessionStdin } = await import("../sessions.js");

  const proc = spawnChild("exec cat");
  const slot = makeSlot("unit-stdin-error", proc);
  bindPtyProcess(slot, proc);
  t.after(() => { try { proc.kill("SIGKILL"); } catch { /* ignore */ } });

  // Deterministically emit the stream error a racing EPIPE would deliver.
  proc.stdin.destroy(new Error("simulated EPIPE"));
  await new Promise((resolve) => setTimeout(resolve, 200));

  // Still alive, and subsequent writes report failure instead of throwing.
  assert.equal(writeToSessionStdin(slot, "after destroy\n"), false);
});

test("writeToSessionStdin writes to a healthy PTY and reports missing ones", async (t) => {
  const { bindPtyProcess, writeToSessionStdin } = await import("../sessions.js");

  assert.equal(writeToSessionStdin(null, "x"), false, "no slot");
  assert.equal(writeToSessionStdin(makeSlot("no-pty", null), "x"), false, "PTY-less slot");

  const proc = spawnChild("exec cat");
  const slot = makeSlot("unit-healthy", proc);
  let echoed = "";
  proc.stdout.on("data", (d) => { echoed += d.toString(); });
  bindPtyProcess(slot, proc);
  t.after(() => { try { proc.kill("SIGKILL"); } catch { /* ignore */ } });

  assert.equal(writeToSessionStdin(slot, "ping\n"), true);
  const deadline = Date.now() + 5000;
  while (!echoed.includes("ping") && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  assert.match(echoed, /ping/, "write must actually reach the child");
});

test("waitForFirstPtyOutput resolves true on first output and stays true", async (t) => {
  const { bindPtyProcess, waitForFirstPtyOutput } = await import("../sessions.js");

  const proc = spawnChild("sleep 0.1; echo ready-now; sleep 10");
  const slot = makeSlot("unit-ready", proc);
  bindPtyProcess(slot, proc);
  t.after(() => { try { proc.kill("SIGKILL"); } catch { /* ignore */ } });

  assert.equal(await waitForFirstPtyOutput(slot, 5000), true);
  assert.equal(await waitForFirstPtyOutput(slot, 5000), true, "readiness is sticky");
});

test("waitForFirstPtyOutput resolves false when the PTY dies without output", async () => {
  const { bindPtyProcess, waitForFirstPtyOutput } = await import("../sessions.js");

  const proc = spawnChild("exit 1");
  const slot = makeSlot("unit-dies-silent", proc);
  bindPtyProcess(slot, proc);

  assert.equal(await waitForFirstPtyOutput(slot, 5000), false);
});

test("waitForFirstPtyOutput resolves false when the bounded wait expires", async (t) => {
  const { bindPtyProcess, waitForFirstPtyOutput } = await import("../sessions.js");

  const proc = spawnChild("sleep 10");
  const slot = makeSlot("unit-timeout", proc);
  bindPtyProcess(slot, proc);
  t.after(() => { try { proc.kill("SIGKILL"); } catch { /* ignore */ } });

  assert.equal(await waitForFirstPtyOutput(slot, 100), false);
});
