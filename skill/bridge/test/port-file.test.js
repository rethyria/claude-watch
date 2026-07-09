// Port file as single source of truth: the bridge walks 7860-7869 when the
// default port is taken (Gradio's default, notably), so hook URLs and the
// codex-watch wrapper must resolve the ACTUAL bound port from the port file
// the bridge writes at startup — never assume 7860.
//
// The decoy tests move the bridge into a private port range via the
// CLAUDE_WATCH_PORT_RANGE_* test-only overrides so occupying the range-start
// port can't collide with parallel test files binding real 786x ports.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { startBridge, request, connectSse } from "./helpers.js";

const SETUP_HOOKS = fileURLToPath(new URL("../../setup-hooks.sh", import.meta.url));

function tempDir(t, prefix) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  t.after(() => { try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* ignore */ } });
  return dir;
}

// Run a shell script (the installer or the generated codex-watch wrapper) as
// a child process with a controlled HOME/PATH, collecting combined output.
function runScript(scriptPath, args, envOverride) {
  return new Promise((resolve) => {
    const proc = spawn("bash", [scriptPath, ...args], {
      env: { ...process.env, ...envOverride },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    proc.stdout.on("data", (d) => { output += d.toString(); });
    proc.stderr.on("data", (d) => { output += d.toString(); });
    proc.on("exit", (code) => resolve({ code, output }));
  });
}

// Occupy a port with a bare TCP listener (stands in for Gradio on 7860).
async function startDecoy(t, port) {
  const decoy = net.createServer();
  await new Promise((resolve, reject) => {
    decoy.once("error", reject);
    decoy.listen(port, "0.0.0.0", resolve);
  });
  t.after(() => new Promise((resolve) => decoy.close(resolve)));
  return decoy;
}

// Collect every hook URL the installer wrote into settings.json.
function installedHookUrls(home) {
  const settings = JSON.parse(
    fs.readFileSync(path.join(home, ".claude", "settings.json"), "utf-8"),
  );
  const urls = [];
  for (const entries of Object.values(settings.hooks ?? {})) {
    for (const entry of entries) {
      for (const hook of entry.hooks ?? []) {
        if (hook.url) urls.push(hook.url);
      }
    }
  }
  return urls;
}

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

test("installer-written hook URLs use the port-file value; explicit argument still wins", { timeout: 60_000 }, async (t) => {
  const home = tempDir(t, "claude-watch-home-");
  fs.mkdirSync(path.join(home, ".claude-watch"), { recursive: true });
  fs.writeFileSync(path.join(home, ".claude-watch", "port"), "17863\n");

  // No port argument: the installer must pick up the port-file value.
  const install = await runScript(SETUP_HOOKS, [], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(install.code, 0, install.output);
  let urls = installedHookUrls(home);
  assert.ok(urls.length > 0, "installer must write hook URLs");
  for (const url of urls) {
    assert.ok(
      url.startsWith("http://127.0.0.1:17863/hooks/"),
      `hook URL must use the port-file port, got: ${url}`,
    );
  }

  // Explicit argument takes precedence over the port file.
  const reinstall = await runScript(SETUP_HOOKS, ["7777"], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(reinstall.code, 0, reinstall.output);
  urls = installedHookUrls(home);
  assert.ok(urls.length > 0);
  for (const url of urls) {
    assert.ok(
      url.startsWith("http://127.0.0.1:7777/hooks/"),
      `explicit port argument must win, got: ${url}`,
    );
  }
});

test("codex-watch wrapper resolves the actual bound port at launch, not a hardcoded default", { timeout: 60_000 }, async (t) => {
  const home = tempDir(t, "claude-watch-home-");
  const credsDir = path.join(home, ".claude-watch");
  fs.mkdirSync(credsDir, { recursive: true });

  // Decoy holds the range-start port, so the bridge lands one port up — a
  // wrapper with a baked-in default would talk to the wrong port entirely.
  await startDecoy(t, 27860);
  const bridge = await startBridge(t, {
    credentialsDir: credsDir,
    env: {
      CLAUDE_WATCH_PORT_RANGE_START: "27860",
      CLAUDE_WATCH_PORT_RANGE_END: "27869",
    },
  });
  assert.notEqual(bridge.port, 27860, "decoy must have forced the bridge off the range start");

  // Fake `codex` on PATH: makes the installer generate the wrapper, and gives
  // the wrapper an agent to run that emits one turn.completed event.
  const binDir = path.join(home, "fakebin");
  fs.mkdirSync(binDir);
  fs.writeFileSync(
    path.join(binDir, "codex"),
    "#!/bin/bash\necho '{\"type\":\"turn.completed\"}'\n",
    { mode: 0o755 },
  );
  const pathWithFakeCodex = `${binDir}:${process.env.PATH}`;

  const install = await runScript(SETUP_HOOKS, [], {
    HOME: home,
    PATH: pathWithFakeCodex,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(install.code, 0, install.output);
  const wrapper = path.join(home, ".local", "bin", "codex-watch");
  assert.ok(fs.existsSync(wrapper), "installer must generate the codex-watch wrapper");

  // Watch client connects so the wrapper's hook POST is observable over SSE.
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  const sse = connectSse(bridge.port, pair.body.token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  // Launch the wrapper with no CLAUDE_WATCH_PORT: it must resolve the bound
  // port from the port file and reach THIS bridge, not 7860.
  const run = await runScript(wrapper, ["exec", "hello"], {
    HOME: home,
    PATH: pathWithFakeCodex,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
    CLAUDE_WATCH_PORT: "",
  });
  assert.equal(run.code, 0, run.output);

  const stop = await sse.waitFor((e) => e.event === "stop" && e.parsed?.source === "codex");
  assert.ok(stop, "wrapper's turn.completed must arrive at the port-file bridge as a stop event");
});

test("with the range-start port occupied by a decoy, installer hook → SSE round-trip still works", { timeout: 60_000 }, async (t) => {
  const home = tempDir(t, "claude-watch-home-");
  const credsDir = path.join(home, ".claude-watch");
  fs.mkdirSync(credsDir, { recursive: true });

  // Decoy plays Gradio on the default port; the bridge walks up.
  await startDecoy(t, 17860);
  const bridge = await startBridge(t, {
    credentialsDir: credsDir,
    env: {
      CLAUDE_WATCH_PORT_RANGE_START: "17860",
      CLAUDE_WATCH_PORT_RANGE_END: "17869",
    },
  });
  assert.notEqual(bridge.port, 17860, "decoy must have forced the bridge off the range start");

  // Install hooks with no explicit port: URLs must point at the walked port.
  const install = await runScript(SETUP_HOOKS, [], {
    HOME: home,
    CLAUDE_WATCH_CREDENTIALS_DIR: "",
  });
  assert.equal(install.code, 0, install.output);
  const permissionUrl = installedHookUrls(home).find((u) => u.endsWith("/hooks/permission"));
  assert.ok(permissionUrl, "installer must write the permission hook URL");
  const urlPort = parseInt(new URL(permissionUrl).port, 10);
  assert.equal(urlPort, bridge.port, "hook URL must target the bridge's actual bound port");

  // Full watch loop against the URL exactly as installed: blocking permission
  // hook → SSE prompt → decision → hook unblocks.
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  const hookResponse = request(urlPort, "POST", "/hooks/permission", {
    body: { tool_name: "Bash", cwd: "/tmp/port-file-e2e", tool_input: { command: "ls" } },
  });

  const promptEvent = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
  );
  const decision = await request(bridge.port, "POST", "/command", {
    token,
    body: { permissionId: promptEvent.parsed.permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(decision.status, 200);

  const hook = await hookResponse;
  assert.equal(hook.status, 200);
  assert.equal(hook.body.hookSpecificOutput.decision.behavior, "allow");
});
