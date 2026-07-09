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
// { id, agent, cwd, folderName, ptyProcess, state, createdAt, endedAt?, hookSessionId? }
/** @type {Map<string, {id: string, agent: string, cwd: string, folderName: string, ptyProcess: import("child_process").ChildProcess | null, state: string, createdAt: number, endedAt?: number, hookSessionId?: string}>} */
export const sessions = new Map();

// Claude Code hook payloads carry the emitting instance's own session_id.
// Attribution is keyed on it: a slot remembers which hook session it
// represents (slot.hookSessionId) and this index maps session_id → slot id
// so every subsequent event for that instance routes to the same slot.
/** @type {Map<string, string>} */
const hookSessionIndex = new Map();

function bindHookSession(slot, hookSessionId) {
  slot.hookSessionId = hookSessionId;
  hookSessionIndex.set(hookSessionId, slot.id);
}

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
      if (slot.hookSessionId) hookSessionIndex.delete(slot.hookSessionId);
      log("info", `Pruned ended session ${id} (${slot.agent}, ${slot.folderName}) after grace period`);
    }
  }
}

// unref() so importing this module (e.g. from an in-process unit test) never
// keeps the process alive on its own.
setInterval(() => pruneEndedSessions(), SESSION_PRUNE_INTERVAL_MS).unref();

// Auto-create a slot for an agent instance the bridge does not own (started
// outside the bridge, or a bridge-owned PTY whose cwd no longer matches).
function createExternalSession({ source, cwd, hookSessionId }) {
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
  if (hookSessionId) bindHookSession(slot, hookSessionId);
  sessions.set(sessionId, slot);

  log("info", `Auto-created session ${sessionId} for external ${agent} (${folderName})`);
  pushSseEvent("session", { state: "running", agent, cwd: resolvedCwd, folderName }, sessionId);

  return sessionId;
}

function hookSessionIdOf(body) {
  return typeof body.session_id === "string" && body.session_id ? body.session_id : null;
}

// Hooks come from Claude Code instances. Attribution keys on the payload's
// session_id (present in every Claude Code hook event); cwd is a tie-breaker
// used only to bind a fresh session_id to a bridge-spawned PTY slot, and the
// sole heuristic for legacy payloads that carry no session_id. A cwd that
// matches no session NEVER falls back to "most recent active" — that used to
// let project B's permission prompts surface under project A.
export function resolveHookSession(body) {
  const cwd = body.session_cwd || body.cwd || null;
  const source = body.source || "claude";
  const hookSessionId = hookSessionIdOf(body);

  if (hookSessionId) {
    // A known session_id always routes to the slot it was bound to.
    const boundId = hookSessionIndex.get(hookSessionId);
    if (boundId) {
      const bound = sessions.get(boundId);
      if (bound) return bound.id;
      hookSessionIndex.delete(hookSessionId); // slot was pruned
    }

    // First event for this session_id: bind it to a running PTY-backed slot
    // in the same cwd that no other session_id has claimed yet.
    if (cwd) {
      for (const [, slot] of sessions) {
        if (slot.state === "running" && slot.ptyProcess && slot.cwd === cwd && !slot.hookSessionId) {
          bindHookSession(slot, hookSessionId);
          return slot.id;
        }
      }
    }

    // No claimable slot — this is an external instance; give it its own slot.
    return createExternalSession({ source, cwd, hookSessionId });
  }

  // Legacy payload without session_id: exact-cwd match only. Resolve the cwd
  // fallback chain first so repeated cwd-less events (e.g. the codex-watch
  // wrapper's turn.completed posts) reuse one slot instead of minting one per
  // event.
  const resolvedCwd = cwd || CLI_CWD || process.env.HOME || process.cwd();
  const match = findSessionByCwd(resolvedCwd);
  if (match) return match.id;

  return createExternalSession({ source, cwd: resolvedCwd, hookSessionId: null });
}

// SessionEnd hook: the Claude Code instance behind this session_id exited.
// Lookup only — a SessionEnd for an unknown session must never create one.
// Only external (non-PTY) slots are ended here: bridge-owned slots end when
// their PTY closes, and Stop (which fires per turn) is deliberately NOT
// mapped to this. Returns the affected slot id, or null if nothing matched.
export function endHookSession(body) {
  const hookSessionId = hookSessionIdOf(body);
  let slot = null;
  if (hookSessionId) {
    const boundId = hookSessionIndex.get(hookSessionId);
    if (boundId) slot = sessions.get(boundId) || null;
  } else {
    slot = findSessionByCwd(body.session_cwd || body.cwd || null);
  }
  if (!slot || slot.state === "ended") return slot?.id ?? null;
  if (slot.ptyProcess) return slot.id; // bridge-owned: PTY close ends it

  slot.state = "ended";
  slot.endedAt = Date.now();
  runSessionCleanupHooks(slot.id, "session-end-hook");
  pushSseEvent("session", { state: "ended", agent: slot.agent, folderName: slot.folderName, reason: "session-end" }, slot.id);
  log("info", `External session ${slot.id} ended (SessionEnd hook)`);
  return slot.id;
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
