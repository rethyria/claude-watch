// Black-box test helpers: run the real bridge as a child process and talk to
// it over real HTTP/SSE, exactly like a watch client and Claude Code hooks do.
import { spawn } from "node:child_process";
import http from "node:http";
import { fileURLToPath } from "node:url";

const BRIDGE_DIR = fileURLToPath(new URL("..", import.meta.url));

export async function startBridge(t) {
  const proc = spawn(process.execPath, ["server.js"], {
    cwd: BRIDGE_DIR,
    env: process.env,
    stdio: ["ignore", "pipe", "pipe"],
  });

  // Register cleanup BEFORE anything can throw: a leaked child process keeps
  // the test-file process alive and hangs the runner indefinitely.
  t.after(async () => {
    proc.kill("SIGTERM");
    await new Promise((resolve) => {
      proc.on("exit", resolve);
      setTimeout(() => { proc.kill("SIGKILL"); resolve(); }, 3000).unref();
    });
  });

  let out = "";
  const bridge = { proc, port: null, pairingCode: null, output: () => out };

  await new Promise((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error(`bridge start timeout\n${out}`)),
      15_000,
    );
    timer.unref();
    const scan = (d) => {
      out += d.toString();
      const code = out.match(/Pairing Code:\s+(\d{6})/);
      const port = out.match(/Port:\s+(\d+)/);
      if (code && port) {
        bridge.pairingCode = code[1];
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
