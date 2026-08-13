// Port file as single source of truth: the bridge walks 7860-7869 when the
// default port is taken (Gradio's default, notably), so the forked
// claude-agent-acp Zed launches must resolve the ACTUAL bound port from the
// port file the bridge writes at startup (readBridgePort in the adapter's
// bridge-channel.ts) — never assume 7860.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { startBridge, tempDir } from "./helpers.js";

test("bridge writes its bound port on startup, refreshes a stale file, removes it on exit", { timeout: 60_000 }, async (t) => {
  const credsDir = tempDir(t, "claude-watch-portfile-");
  const portFile = path.join(credsDir, "port");
  // Stale leftover from a crash / a previous run on a different port: startup
  // must refresh it to the actual bound port.
  fs.writeFileSync(portFile, "1234\n");

  const bridge = await startBridge(t, { credentialsDir: credsDir });
  const written = parseInt(fs.readFileSync(portFile, "utf-8").trim(), 10);
  assert.equal(written, bridge.port, "port file must record the ACTUAL bound port");

  await bridge.stop();
  assert.equal(fs.existsSync(portFile), false, "port file must be removed on graceful shutdown");
});

test("bridge does not delete a port file a sibling bridge has since taken over", { timeout: 60_000 }, async (t) => {
  const credsDir = tempDir(t, "claude-watch-portfile-");
  const portFile = path.join(credsDir, "port");

  const bridge = await startBridge(t, { credentialsDir: credsDir });
  // A sibling bridge started later and overwrote the file with its own port.
  fs.writeFileSync(portFile, "9999\n");

  await bridge.stop();
  assert.equal(fs.existsSync(portFile), true, "exit must not wipe a sibling's port entry");
  assert.equal(fs.readFileSync(portFile, "utf-8").trim(), "9999");
});
