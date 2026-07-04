// Multi-session PTY management: the sessions map, spawning/attaching/killing
// PTY-backed agent sessions, lookup helpers, and hook-to-session resolution.
import crypto from "node:crypto";
import path from "node:path";
import { spawnPtyProcess } from "./pty.js";
import { log } from "./util.js";
import {
  CLAUDE_BIN,
  CODEX_BIN,
  CLI_CWD,
  SESSION_PRUNE_GRACE_MS,
  SESSION_PRUNE_INTERVAL_MS,
} from "./config.js";
import { pushSseEvent, registerSseSyncProvider } from "./transport-sse.js";

// Multi-session: each entry is a session slot
// { id, agent, cwd, folderName, ptyProcess, state, createdAt, endedAt? }
/** @type {Map<string, {id: string, agent: string, cwd: string, folderName: string, ptyProcess: import("child_process").ChildProcess | null, state: string, createdAt: number, endedAt?: number}>} */
export const sessions = new Map();

// Invoked when a PTY-backed session ends so codex.js can clear its synthetic
// permission state without a circular import (codex.js imports sessions.js,
// so the dependency must not also point the other way).
const sessionCleanupHooks = [];

export function registerSessionCleanupHook(fn) {
  sessionCleanupHooks.push(fn);
}

function runSessionCleanupHooks(sessionId, reason) {
  for (const hook of sessionCleanupHooks) hook(sessionId, reason);
}

export function spawnInteractiveProcess(agent, cwd, args = []) {
  const bin = agent === "codex" ? CODEX_BIN : CLAUDE_BIN;
  if (!bin) {
    return null;
  }
  const cols = parseInt(process.env.COLUMNS, 10) || 120;
  const rows = parseInt(process.env.LINES, 10) || 40;

  return spawnPtyProcess(bin, args, {
    cwd,
    cols,
    rows,
    env: {
      ...process.env,
      TERM: "xterm-256color",
      COLUMNS: String(cols),
      LINES: String(rows),
    },
  });
}

export function bindPtyProcess(slot, proc) {
  const sessionId = slot.id;
  slot.ptyProcess = proc;

  proc.stdout.on("data", (data) => {
    pushSseEvent("pty-output", { text: data.toString() }, sessionId);
  });

  proc.stderr.on("data", (data) => {
    pushSseEvent("pty-output", { text: data.toString() }, sessionId);
  });

  proc.on("close", (exitCode, signal) => {
    log("info", `Session ${sessionId} (${slot.agent}) PTY exited: code=${exitCode} signal=${signal}`);
    slot.state = "ended";
    slot.endedAt = Date.now();
    slot.ptyProcess = null;
    runSessionCleanupHooks(sessionId, "pty-closed");
    pushSseEvent("session", { state: "ended", exitCode, signal, agent: slot.agent, folderName: slot.folderName }, sessionId);
  });

  proc.on("error", (err) => {
    log("error", `Session ${sessionId} PTY spawn error: ${err.message}`);
    slot.state = "ended";
    slot.endedAt = Date.now();
    slot.ptyProcess = null;
    runSessionCleanupHooks(sessionId, "pty-error");
    pushSseEvent("session", { state: "ended", error: err.message, agent: slot.agent, folderName: slot.folderName }, sessionId);
  });
}

export function spawnSession(agent, cwd) {
  const sessionId = crypto.randomUUID();
  const folderName = path.basename(cwd) || cwd;

  log("info", `Spawning ${agent} session ${sessionId} in PTY (cwd: ${cwd})`);

  const proc = spawnInteractiveProcess(agent, cwd);
  if (!proc) {
    const msg = `Cannot spawn ${agent}: binary not found`;
    log("error", msg);
    pushSseEvent("error", { error: msg });
    return null;
  }

  log("info", `Using binary: ${agent === "codex" ? CODEX_BIN : CLAUDE_BIN}`);

  const slot = {
    id: sessionId,
    agent,
    cwd,
    folderName,
    ptyProcess: proc,
    state: "running",
    createdAt: Date.now(),
  };
  sessions.set(sessionId, slot);
  bindPtyProcess(slot, proc);

  pushSseEvent("session", { state: "running", agent, cwd, folderName }, sessionId);

  log("info", `${agent} session ${sessionId} started (${folderName}), pid: ${proc.pid}`);
  return sessionId;
}

export function attachPtyToSession(slot) {
  if (slot.ptyProcess) return slot.ptyProcess;

  const args = slot.agent === "codex"
    ? ["resume", slot.id, "--no-alt-screen"]
    : [];

  const proc = spawnInteractiveProcess(slot.agent, slot.cwd, args);
  if (!proc) return null;

  bindPtyProcess(slot, proc);
  log("info", `Attached PTY to session ${slot.id} (${slot.agent}), pid: ${proc.pid}`);
  return proc;
}

export function killSession(sessionId) {
  const slot = sessions.get(sessionId);
  if (!slot) return false;
  if (slot.ptyProcess) {
    try { slot.ptyProcess.kill(); } catch { /* ignore */ }
  }
  slot.state = "ended";
  slot.endedAt = Date.now();
  slot.ptyProcess = null;
  pushSseEvent("session", { state: "ended", agent: slot.agent, folderName: slot.folderName, killed: true }, sessionId);
  log("info", `Session ${sessionId} killed`);
  return true;
}

export function findSessionByCwd(cwd) {
  if (!cwd) return null;
  for (const [, slot] of sessions) {
    if (slot.cwd === cwd && slot.state === "running") return slot;
  }
  return null;
}

export function findMostRecentActiveSession() {
  let best = null;
  for (const [, slot] of sessions) {
    if (slot.state === "running" && slot.ptyProcess) {
      if (!best || slot.createdAt > best.createdAt) {
        best = slot;
      }
    }
  }
  return best;
}

export function findMostRecentRunningSession() {
  let best = null;
  for (const [, slot] of sessions) {
    if (slot.state === "running") {
      if (!best || slot.createdAt > best.createdAt) {
        best = slot;
      }
    }
  }
  return best;
}

export function getSessionsSnapshot() {
  return Array.from(sessions.values()).map((s) => ({
    id: s.id,
    agent: s.agent,
    cwd: s.cwd,
    folderName: s.folderName,
    state: s.state,
    createdAt: s.createdAt,
  }));
}

// Ended sessions stay visible in snapshots for SESSION_PRUNE_GRACE_MS so
// clients observe the "ended" state, then get deleted — otherwise the map
// (and every /status and /pair snapshot) grows forever. `now` is injectable
// so tests can exercise the cutoff without waiting out the grace period.
// Sessions ended before this code existed carry no endedAt; fall back to
// createdAt so they still age out.
export function pruneEndedSessions(now = Date.now()) {
  for (const [id, slot] of sessions) {
    if (slot.state !== "ended") continue;
    const endedAt = slot.endedAt ?? slot.createdAt;
    if (now - endedAt >= SESSION_PRUNE_GRACE_MS) {
      sessions.delete(id);
      log("info", `Pruned ended session ${id} (${slot.agent}, ${slot.folderName}) after grace period`);
    }
  }
}

// unref() so importing this module (e.g. from an in-process unit test) never
// keeps the process alive on its own.
setInterval(() => pruneEndedSessions(), SESSION_PRUNE_INTERVAL_MS).unref();

// Hooks come from Claude Code instances. We match by cwd to find the session.
export function resolveHookSession(body) {
  const cwd = body.session_cwd || body.cwd || null;
  const source = body.source || "claude";

  // Try exact cwd match first
  const match = findSessionByCwd(cwd);
  if (match) return match.id;

  // Fallback: if exactly one running session, use it
  const active = findMostRecentActiveSession();
  if (active) return active.id;

  // No session exists — auto-create one for this external Claude/Codex instance
  const agent = source === "codex" ? "codex" : "claude";
  const resolvedCwd = cwd || CLI_CWD || process.env.HOME || process.cwd();
  const folderName = path.basename(resolvedCwd) || resolvedCwd;
  const sessionId = crypto.randomUUID();

  const slot = {
    id: sessionId,
    agent,
    cwd: resolvedCwd,
    folderName,
    ptyProcess: null, // External process — no PTY owned by bridge
    state: "running",
    createdAt: Date.now(),
  };
  sessions.set(sessionId, slot);

  log("info", `Auto-created session ${sessionId} for external ${agent} (${folderName})`);
  pushSseEvent("session", { state: "running", agent, cwd: resolvedCwd, folderName }, sessionId);

  return sessionId;
}

// Send current sessions state so late-connecting SSE clients see existing
// sessions (runs on every GET /events connect).
registerSseSyncProvider(function* runningSessionsSync() {
  for (const [sid, slot] of sessions) {
    if (slot.state === "running") {
      yield {
        event: "session",
        data: JSON.stringify({
          state: "running",
          agent: slot.agent,
          cwd: slot.cwd,
          folderName: slot.folderName,
          sessionId: sid,
        }),
      };
    }
  }
});
