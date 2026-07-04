// Black-box test helpers: run the real bridge as a child process and talk to
// it over real HTTP/SSE, exactly like a watch client and Claude Code hooks do.
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const BRIDGE_DIR = fileURLToPath(new URL("..", import.meta.url));

// Options:
//   credentialsDir — directory for the bridge's persisted credentials.
//     Omitted: a fresh per-test temp dir (removed on cleanup), so every
//       bridge starts unpaired and never touches the real ~/.claude-watch.
//     A path: reuse that dir (restart-persistence tests); caller owns cleanup.
//     false: opt out — inherit the parent env untouched.
//   args — extra CLI arguments (e.g. ["--allow-pairing"]).
//   env — extra environment variables for the bridge process (e.g. the
//     CLAUDE_WATCH_* test-only overrides from config.js).
export async function startBridge(t, { credentialsDir, args = [], env: extraEnv } = {}) {
  const env = { ...process.env, ...extraEnv };
  let ownedTempDir = null;
  if (credentialsDir === undefined) {
    ownedTempDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-test-creds-"));
    env.CLAUDE_WATCH_CREDENTIALS_DIR = ownedTempDir;
  } else if (credentialsDir !== false) {
    env.CLAUDE_WATCH_CREDENTIALS_DIR = credentialsDir;
  }

  const proc = spawn(process.execPath, ["server.js", ...args], {
    cwd: BRIDGE_DIR,
    env,
    stdio: ["ignore", "pipe", "pipe"],
  });

  const waitForExit = async () => {
    if (proc.exitCode !== null || proc.signalCode !== null) return;
    proc.kill("SIGTERM");
    await new Promise((resolve) => {
      proc.on("exit", resolve);
      setTimeout(() => { proc.kill("SIGKILL"); resolve(); }, 3000).unref();
    });
  };

  // Register cleanup BEFORE anything can throw: a leaked child process keeps
  // the test-file process alive and hangs the runner indefinitely.
  t.after(async () => {
    await waitForExit();
    if (ownedTempDir) {
      try { fs.rmSync(ownedTempDir, { recursive: true, force: true }); } catch { /* ignore */ }
    }
  });

  let out = "";
  const bridge = {
    proc,
    port: null,
    pairingCode: null,
    pairingLocked: false,
    credentialsDir: ownedTempDir ?? (credentialsDir === false ? null : credentialsDir),
    output: () => out,
    // Stop the bridge mid-test (restart-persistence tests) and wait for exit.
    stop: waitForExit,
    // Poll the accumulated child output until `regex` matches; returns the
    // match. Used e.g. to scrape the fresh pairing code after SIGUSR1.
    async waitForOutput(regex, timeoutMs = 10_000) {
      const deadline = Date.now() + timeoutMs;
      for (;;) {
        const match = out.match(regex);
        if (match) return match;
        if (Date.now() > deadline) {
          throw new Error(`output not matching ${regex} within ${timeoutMs}ms\n${out}`);
        }
        await new Promise((r) => setTimeout(r, 25).unref());
      }
    },
  };

  await new Promise((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error(`bridge start timeout\n${out}`)),
      15_000,
    );
    timer.unref();
    const scan = (d) => {
      out += d.toString();
      const code = out.match(/Pairing Code:\s+(\d{6})/);
      const locked = out.match(/Pairing:\s+locked/);
      const port = out.match(/Port:\s+(\d+)/);
      if ((code || locked) && port) {
        bridge.pairingCode = code ? code[1] : null;
        bridge.pairingLocked = !code;
        bridge.port = parseInt(port[1], 10);
        clearTimeout(timer);
        resolve();
      }
    };
    proc.stdout.on("data", scan);
    proc.stderr.on("data", scan);
    proc.on("exit", (c) => {
      clearTimeout(timer);
      reject(new Error(`bridge exited early (code ${c})\n${out}`));
    });
  });

  return bridge;
}

export async function request(port, method, path, { token, body } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers["Authorization"] = `Bearer ${token}`;
  const res = await fetch(`http://127.0.0.1:${port}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(45_000),
  });
  let json = null;
  try { json = await res.json(); } catch { /* non-JSON response */ }
  return { status: res.status, body: json };
}

// Minimal SSE client: collects parsed events, lets tests await a matching one.
export function connectSse(port, token, { lastEventId, path = "/events" } = {}) {
  const events = [];
  const waiters = [];
  let connected;
  const connectedPromise = new Promise((r) => { connected = r; });

  const headers = { Accept: "text/event-stream" };
  if (token) headers["Authorization"] = `Bearer ${token}`;
  if (lastEventId !== undefined) headers["Last-Event-ID"] = String(lastEventId);

  const req = http.request(
    { host: "127.0.0.1", port, path, method: "GET", headers },
    (res) => {
      connected(res.statusCode);
      let buffer = "";
      res.on("data", (chunk) => {
        buffer += chunk.toString();
        let idx;
        while ((idx = buffer.indexOf("\n\n")) !== -1) {
          const block = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          const event = { id: null, event: "message", data: "" };
          for (const line of block.split("\n")) {
            if (line.startsWith("id: ")) event.id = parseInt(line.slice(4), 10);
            else if (line.startsWith("event: ")) event.event = line.slice(7);
            else if (line.startsWith("data: ")) event.data += line.slice(6);
          }
          if (block.startsWith(":")) continue; // heartbeat comment
          try { event.parsed = JSON.parse(event.data); } catch { event.parsed = null; }
          events.push(event);
          for (const w of [...waiters]) w();
        }
      });
    },
  );
  req.end();

  return {
    events,
    statusCode: () => connectedPromise,
    async waitFor(predicate, timeoutMs = 10_000) {
      const deadline = Date.now() + timeoutMs;
      for (;;) {
        const found = events.find(predicate);
        if (found) return found;
        if (Date.now() > deadline) {
          throw new Error(
            `SSE event not seen within ${timeoutMs}ms; saw: ${events.map((e) => e.event).join(", ") || "none"}`,
          );
        }
        await new Promise((resolve) => {
          waiters.push(resolve);
          setTimeout(() => {
            waiters.splice(waiters.indexOf(resolve), 1);
            resolve();
          }, 100).unref();
        });
      }
    },
    close() {
      req.destroy();
    },
  };
}
