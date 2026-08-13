// claude-watch: the adapter is the bridge's launcher (#92). Pins the three
// load-bearing behaviors of ensureBridgeRunning:
//
//   1. connect-when-present — a bridge answering /ping (via the port file, or
//      the range walk when the file is missing/stale) means NO spawn;
//   2. spawn-when-absent — nothing answering means a REAL detached bridge,
//      logging to the #93 bridge.log beside the credentials, and never with
//      --allow-pairing (a seeded credential store must come up LOCKED);
//   3. single-spawn under racing starts — two adapters ensuring at once may
//      both spawn, and the bridge's port bind is the mutex that collapses the
//      race to exactly one surviving bridge (the loser exits itself; both
//      adapters can then find the winner).
//
// 2 and 3 run the actual skill/bridge/server.js (the sibling package must be
// installed — the required test workflow runs its npm ci first), pinned into
// the e2e's isolated test-only port range (7970-7999) with mDNS off, so no
// spawned bridge can ever collide with a developer's live bridge or attract a
// real adapter. tests/setup.ts pins CLAUDE_WATCH_NO_BRIDGE_SPAWN=1 for the
// rest of the suite; every test here unpins it explicitly.
import { describe, it, expect, vi } from "vitest";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { ensureBridgeRunning, findRunningBridgePort } from "../bridge-channel.js";

const quietLogger = { log() {}, error() {} };

const ENV_KEYS = [
  "CLAUDE_WATCH_NO_BRIDGE_SPAWN",
  "CLAUDE_WATCH_CREDENTIALS_DIR",
  "CLAUDE_WATCH_PORT_RANGE_START",
  "CLAUDE_WATCH_PORT_RANGE_END",
  "CLAUDE_WATCH_DISABLE_MDNS",
  "CLAUDE_WATCH_EXIT_WHEN_ORPHANED",
] as const;

/** Apply env overrides; returns the restore function for the finally block. */
function setEnv(overrides: Partial<Record<(typeof ENV_KEYS)[number], string>>): () => void {
  const saved = new Map<string, string | undefined>(ENV_KEYS.map((k) => [k, process.env[k]]));
  for (const key of ENV_KEYS) delete process.env[key];
  for (const [key, value] of Object.entries(overrides)) process.env[key] = value;
  return () => {
    for (const [key, value] of saved) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  };
}

async function waitFor(predicate: () => boolean, timeoutMs: number, label: string): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${label}`);
    await new Promise((r) => setTimeout(r, 100));
  }
}

/** A stand-in bridge: answers GET /ping with a bridge-shaped body. */
function startFakePingBridge(port?: number): Promise<{ port: number; close: () => void }> {
  const server = http.createServer((req, res) => {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(
      req.method === "GET" && req.url?.startsWith("/ping")
        ? JSON.stringify({ proto: 3, bridgeId: "fake-bridge-id", machineName: "test" })
        : "{}",
    );
  });
  return new Promise((resolve, reject) => {
    server.on("error", reject);
    server.listen(port ?? 0, "127.0.0.1", () => {
      const bound = (server.address() as import("node:net").AddressInfo).port;
      resolve({ port: bound, close: () => server.close() });
    });
  });
}

async function pingsAsBridge(port: number): Promise<boolean> {
  try {
    const resp = await fetch(`http://127.0.0.1:${port}/ping`, { signal: AbortSignal.timeout(1000) });
    if (!resp.ok) return false;
    const body = (await resp.json()) as { bridgeId?: unknown };
    return typeof body.bridgeId === "string";
  } catch {
    return false;
  }
}

const alive = (pid: number): boolean => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

const killQuietly = (pid?: number) => {
  if (!pid) return;
  try {
    process.kill(pid, "SIGTERM");
  } catch {
    /* already gone */
  }
};

describe("ensureBridgeRunning (claude-watch, #92 launcher half)", () => {
  it("connects instead of spawning when the port file's port answers as a bridge", async () => {
    const fake = await startFakePingBridge();
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bridge-spawn-"));
    fs.writeFileSync(path.join(dir, "port"), `${fake.port}\n`);
    const spawnSpy = vi.fn(() => null);
    const restore = setEnv({
      CLAUDE_WATCH_NO_BRIDGE_SPAWN: "0",
      CLAUDE_WATCH_CREDENTIALS_DIR: dir,
      // A range with no bridge in it: only the port file can say "found".
      CLAUDE_WATCH_PORT_RANGE_START: "7970",
      CLAUDE_WATCH_PORT_RANGE_END: "7970",
    });
    try {
      const result = await ensureBridgeRunning(quietLogger, spawnSpy);
      expect(result).toEqual({ outcome: "found", port: fake.port });
      expect(spawnSpy).not.toHaveBeenCalled();
    } finally {
      restore();
      fake.close();
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it("finds a bridge by walking the range when the port file is missing", async () => {
    // The fake sits at a fixed in-range port; no port file exists at all.
    const fake = await startFakePingBridge(7971);
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bridge-spawn-"));
    const spawnSpy = vi.fn(() => null);
    const restore = setEnv({
      CLAUDE_WATCH_NO_BRIDGE_SPAWN: "0",
      CLAUDE_WATCH_CREDENTIALS_DIR: dir,
      CLAUDE_WATCH_PORT_RANGE_START: "7971",
      CLAUDE_WATCH_PORT_RANGE_END: "7972",
    });
    try {
      const result = await ensureBridgeRunning(quietLogger, spawnSpy);
      expect(result).toEqual({ outcome: "found", port: 7971 });
      expect(spawnSpy).not.toHaveBeenCalled();
    } finally {
      restore();
      fake.close();
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it("spawns a detached bridge when nothing answers — logging to bridge.log, pairing NOT opened", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bridge-spawn-"));
    // A seeded credential store makes the no---allow-pairing contract
    // OBSERVABLE: with stored devices the bridge boots LOCKED unless the flag
    // was passed, so the lock line in its log is the proof it wasn't.
    fs.writeFileSync(
      path.join(dir, "credentials.json"),
      JSON.stringify({
        version: 1,
        tokens: [{ hash: "a".repeat(64), createdAt: "2026-01-01T00:00:00.000Z", surface: "legacy" }],
      }),
    );
    const restore = setEnv({
      CLAUDE_WATCH_NO_BRIDGE_SPAWN: "0",
      CLAUDE_WATCH_CREDENTIALS_DIR: dir,
      CLAUDE_WATCH_PORT_RANGE_START: "7975",
      CLAUDE_WATCH_PORT_RANGE_END: "7976",
      CLAUDE_WATCH_DISABLE_MDNS: "1",
      CLAUDE_WATCH_EXIT_WHEN_ORPHANED: "1",
    });
    let pid: number | undefined;
    try {
      const result = await ensureBridgeRunning(quietLogger);
      expect(result.outcome).toBe("spawned");
      pid = result.pid;
      expect(pid).toBeGreaterThan(0);

      // The spawned bridge comes up, publishes its port, and answers /ping —
      // i.e. the next adapter (or this one's inbox loop) can find it.
      await waitFor(() => fs.existsSync(path.join(dir, "port")), 15_000, "the port file");
      const found = await findRunningBridgePort();
      expect(found).not.toBeNull();
      expect(found).toBeGreaterThanOrEqual(7975);
      expect(found).toBeLessThanOrEqual(7976);

      // Its stdout/stderr landed in the #93 bridge.log beside the credentials,
      // and the seeded store came up locked (no --allow-pairing).
      const logPath = path.join(dir, "bridge.log");
      await waitFor(
        () => fs.existsSync(logPath) && /Pairing locked/.test(fs.readFileSync(logPath, "utf8")),
        15_000,
        "the locked-pairing line in bridge.log",
      );
    } finally {
      killQuietly(pid);
      restore();
      fs.rmSync(dir, { recursive: true, force: true });
    }
  }, 30_000);

  it("racing adapters converge on exactly ONE bridge (the port-bind mutex)", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bridge-spawn-"));
    const restore = setEnv({
      CLAUDE_WATCH_NO_BRIDGE_SPAWN: "0",
      CLAUDE_WATCH_CREDENTIALS_DIR: dir,
      CLAUDE_WATCH_PORT_RANGE_START: "7977",
      CLAUDE_WATCH_PORT_RANGE_END: "7979",
      CLAUDE_WATCH_DISABLE_MDNS: "1",
      CLAUDE_WATCH_EXIT_WHEN_ORPHANED: "1",
    });
    const pids: number[] = [];
    try {
      // Two Zed windows starting at once: both probe an empty range, so both
      // are entitled to spawn — the mutex is downstream, at the port bind.
      const results = await Promise.all([
        ensureBridgeRunning(quietLogger),
        ensureBridgeRunning(quietLogger),
      ]);
      for (const r of results) {
        expect(["spawned", "found"]).toContain(r.outcome);
        if (r.outcome === "spawned" && r.pid) pids.push(r.pid);
      }
      expect(pids.length).toBeGreaterThanOrEqual(1);

      // One spawn wins the bind; any other exits itself (--exit-if-sibling).
      await waitFor(() => fs.existsSync(path.join(dir, "port")), 15_000, "the port file");
      await waitFor(
        () => pids.filter(alive).length === 1,
        15_000,
        "the losing spawn to exit on its own",
      );

      // Exactly one bridge serves the whole range — and both adapters' own
      // probe now lands on it ("losers just connect").
      const answering: number[] = [];
      for (const port of [7977, 7978, 7979]) {
        if (await pingsAsBridge(port)) answering.push(port);
      }
      expect(answering).toHaveLength(1);
      expect(await findRunningBridgePort()).toBe(answering[0]);
    } finally {
      for (const pid of pids) killQuietly(pid);
      restore();
      fs.rmSync(dir, { recursive: true, force: true });
    }
  }, 30_000);
});
