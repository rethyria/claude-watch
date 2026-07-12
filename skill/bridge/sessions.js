// Multi-session PTY management: the sessions map, spawning/attaching/killing
// PTY-backed agent sessions, lookup helpers, and hook-to-session resolution.
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { spawnPtyProcess } from "./pty.js";
import { log } from "./util.js";
import {
  CLAUDE_BIN,
  CODEX_BIN,
  CLI_CWD,
  SESSION_PRUNE_GRACE_MS,
  SESSION_PRUNE_INTERVAL_MS,
  MAX_EXTERNAL_SESSIONS,
} from "./config.js";
import { pushSseEvent, registerSseSyncProvider } from "./transport-sse.js";

// Multi-session: each entry is a session slot
// { id, agent, cwd, folderName, ptyProcess, state, createdAt, endedAt?, hookSessionId?, hookCreated?,
//   title?, titleIsAi?, transcriptPath?, titleCache?,
//   projectRootVerified?, projectRootAttempt? } — title is lazily derived
//   from the Claude Code transcript (see the session-titles section below);
//   projectRootVerified/projectRootAttempt cache the project-root attribution
//   (see the project-root-attribution section below).
/** @type {Map<string, {id: string, agent: string, cwd: string, folderName: string, ptyProcess: import("child_process").ChildProcess | null, state: string, createdAt: number, endedAt?: number, hookSessionId?: string, hookCreated?: boolean, title?: string, titleIsAi?: boolean, transcriptPath?: string, titleCache?: {path: string, mtimeMs: number, size: number, title: string | null}, projectRootVerified?: boolean, projectRootAttempt?: string}>} */
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

// --- Session titles (derived from the Claude Code transcript) ---------------
// Claude Code keeps a session title in the transcript JSONL that every hook
// payload points at via `transcript_path`: dedicated records of the shape
//   {"type":"ai-title","aiTitle":"<title>","sessionId":"…"}
// re-emitted whenever the title evolves — the LAST one is current. Before an
// ai-title exists, the first real user prompt (truncated) is the best label.
//
// Hooks are a hot path, so derivation is lazy and cached per slot keyed on
// (path, mtime, size): the transcript is only re-read when it changed, and
// only at opportunistic moments (session creation / first hook binding,
// Stop, SessionEnd) — never on every hook event. Any failure (missing file,
// unreadable, malformed lines) silently yields no title.

const TRANSCRIPT_TITLE_MAX_CHARS = 60;
// Big transcripts are scanned as a head chunk (first user prompt lives at the
// top) plus a tail chunk (the latest ai-title re-emission) instead of a full
// read.
const TRANSCRIPT_SCAN_BYTES = 256 * 1024;

function transcriptPathOf(body) {
  return typeof body?.transcript_path === "string" && body.transcript_path
    ? body.transcript_path
    : null;
}

// --- Project-root attribution (issue #51) ------------------------------------
// A slot used to bind cwd/folderName from the FIRST hook event it saw; a hook
// firing while the session's shell sat in a subdirectory mislabeled the whole
// session (e.g. a gradle run in …/claude-watch/wear/ labeled the session
// "wear"). Hook payloads carry `transcript_path`, whose parent directory name
// is Claude Code's sanitized project root — derive the real root from it.
//
// Sanitization char class, verified two ways on this machine:
//   * the bundled Claude Code CLI computes the projects dir name as
//     `cwd.replace(/[^a-zA-Z0-9]/g, "-")` (paths over 200 chars are further
//     truncated and hash-suffixed — those never match here and simply fall
//     back to the observed cwd);
//   * real dirs under ~/.claude/projects/ agree, e.g.
//     /home/deck/Development/claude-watch → -home-deck-Development-claude-watch
//     (both "/" and the "-" already in the name map to "-").
// The mapping is lossy ("-", "/", ".", "_", … all collapse to "-"), so it
// cannot be inverted. Instead each ancestor of the observed cwd — the cwd
// itself first — is sanitized and compared against the transcript's parent
// dir name; the first (deepest) match is the verified project root.
const PROJECT_DIR_SANITIZE_RE = /[^a-zA-Z0-9]/g;

function sanitizeProjectPath(p) {
  return p.replace(PROJECT_DIR_SANITIZE_RE, "-");
}

function findVerifiedProjectRoot(cwd, transcriptPath) {
  if (typeof cwd !== "string" || !path.isAbsolute(cwd)) return null;
  const expected = path.basename(path.dirname(transcriptPath));
  if (!expected) return null;
  let dir = path.resolve(cwd);
  for (;;) {
    if (sanitizeProjectPath(dir) === expected) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return null; // reached the filesystem root: no match
    dir = parent;
  }
}

// Proper-ancestor check on path strings (hook cwds are clean absolute paths).
function isAncestorPath(ancestor, descendant) {
  if (!ancestor || !descendant || ancestor === descendant) return false;
  const prefix = ancestor.endsWith(path.sep) ? ancestor : ancestor + path.sep;
  return descendant.startsWith(prefix);
}

// Re-label a slot with its (better) project root and tell connected clients
// via the same idempotent re-sent running `session` event that title changes
// use, so they re-group the session under the right project.
function rebindProjectRoot(slot, root) {
  if (slot.cwd === root) return;
  slot.cwd = root;
  slot.folderName = path.basename(root) || root;
  if (slot.state !== "running") return;
  pushSseEvent(
    "session",
    sessionEventPayload(slot, { state: "running", agent: slot.agent, cwd: slot.cwd, folderName: slot.folderName }),
    slot.id,
  );
}

// Attribution upkeep for an already-bound slot, run on the hook hot path so
// it must stay cheap: string compares only. The ancestor walk runs at most
// once per distinct (cwd, transcript path) pair — the attempt is cached on
// the slot — and never again once a root was verified.
//
// Only hook-created slots keyed by session_id are eligible: PTY-backed slots
// keep their user-chosen spawn cwd (attachPtyToSession respawns there), and
// legacy no-session_id slots are routed by EXACT cwd match, which a rebind
// would break (Codex sessions therefore stay untouched).
function updateProjectAttribution(slot, cwd, transcriptPath) {
  if (!slot.hookCreated) return;
  if (transcriptPath) {
    if (slot.projectRootVerified) return; // deterministic — never re-walk
    const attemptKey = `${cwd ?? ""}\n${transcriptPath}`;
    if (slot.projectRootAttempt !== attemptKey) {
      slot.projectRootAttempt = attemptKey;
      const root = findVerifiedProjectRoot(cwd || slot.cwd, transcriptPath);
      if (root) {
        slot.projectRootVerified = true;
        rebindProjectRoot(slot, root);
        return;
      }
    }
    // No ancestor matched: fall through to the ancestor-cwd heuristic.
  } else if (slot.projectRootVerified) {
    return;
  }
  // A session can start deep but never migrates to an unrelated root: a later
  // event whose cwd is an ANCESTOR of the bound cwd rebinds upward.
  if (cwd && isAncestorPath(cwd, slot.cwd)) rebindProjectRoot(slot, cwd);
}

// First real prompt text of a `user` transcript record, or null. Skips meta
// records and synthetic bodies (slash-command markup like <command-name>,
// tool_result content) — those are not what the user asked for.
function extractUserPromptText(record) {
  if (record.isMeta) return null;
  const content = record.message?.content;
  let text = null;
  if (typeof content === "string") {
    text = content;
  } else if (Array.isArray(content)) {
    const textPart = content.find((part) => part?.type === "text" && typeof part.text === "string");
    text = textPart ? textPart.text : null;
  }
  if (typeof text !== "string") return null;
  text = text.replace(/\s+/g, " ").trim();
  if (!text || text.startsWith("<")) return null;
  return text;
}

function truncateTitle(text) {
  if (text.length <= TRANSCRIPT_TITLE_MAX_CHARS) return text;
  return `${text.slice(0, TRANSCRIPT_TITLE_MAX_CHARS - 1).trimEnd()}…`;
}

// Derive the current title from a transcript: last ai-title record wins,
// falling back to the first user prompt. Returns
//   { title, fromAi, aiIsNewest } — title null when neither exists; fromAi
// says the title came from an ai-title record; aiIsNewest says that ai-title
// is provably the transcript's newest one (a full scan sees every record; a
// partial head+tail scan only proves it for the tail chunk — an ai-title
// seen only in the head may be outranked by a newer one in the skipped
// middle). Throws only I/O errors (the caller absorbs them); malformed
// lines are skipped silently.
function deriveTranscriptTitle(transcriptPath, size) {
  const chunks = [];
  const fd = fs.openSync(transcriptPath, "r");
  try {
    if (size <= 2 * TRANSCRIPT_SCAN_BYTES) {
      // Bounded read (never readFileSync): `size` came from a stat of a
      // regular file, so even a file that grows mid-read costs at most this
      // buffer.
      const buf = Buffer.alloc(size);
      const read = fs.readSync(fd, buf, 0, size, 0);
      chunks.push({ text: buf.toString("utf-8", 0, read), partialFirstLine: false, isTail: true });
    } else {
      const head = Buffer.alloc(TRANSCRIPT_SCAN_BYTES);
      const headRead = fs.readSync(fd, head, 0, TRANSCRIPT_SCAN_BYTES, 0);
      const tail = Buffer.alloc(TRANSCRIPT_SCAN_BYTES);
      const tailRead = fs.readSync(fd, tail, 0, TRANSCRIPT_SCAN_BYTES, size - TRANSCRIPT_SCAN_BYTES);
      chunks.push({ text: head.toString("utf-8", 0, headRead), partialFirstLine: false, isTail: false });
      // The tail chunk almost certainly starts mid-line; drop the fragment.
      chunks.push({ text: tail.toString("utf-8", 0, tailRead), partialFirstLine: true, isTail: true });
    }
  } finally {
    fs.closeSync(fd);
  }

  let aiTitle = null;
  let aiTitleInTail = false;
  let firstUserPrompt = null;
  for (const chunk of chunks) {
    const lines = chunk.text.split("\n");
    for (let i = chunk.partialFirstLine ? 1 : 0; i < lines.length; i++) {
      const line = lines[i].trim();
      if (!line) continue;
      let record;
      try { record = JSON.parse(line); } catch { continue; }
      if (!record || typeof record !== "object") continue;
      if (record.type === "ai-title" && typeof record.aiTitle === "string" && record.aiTitle.trim()) {
        aiTitle = record.aiTitle.trim(); // last one wins
        aiTitleInTail = chunk.isTail;
      } else if (!firstUserPrompt && record.type === "user") {
        firstUserPrompt = extractUserPromptText(record);
      }
    }
  }

  const title = aiTitle || firstUserPrompt;
  return {
    title: title ? truncateTitle(title) : null,
    fromAi: Boolean(aiTitle),
    aiIsNewest: Boolean(aiTitle) && aiTitleInTail,
  };
}

// Refresh a slot's title from its transcript, stat-gated by the (path, mtime,
// size) cache so an unchanged transcript costs one stat and no read. Returns
// true when the slot's title actually changed. Never throws: an unreadable or
// malformed transcript leaves the slot as it was (no title, no crash).
export function refreshSessionTitle(slot, transcriptPath = slot?.transcriptPath) {
  if (!slot || typeof transcriptPath !== "string" || !transcriptPath) return false;
  try {
    const stat = fs.statSync(transcriptPath);
    // Regular files only: opening/reading a FIFO or device node here would
    // block forever or read unboundedly (their stat size is 0, so the size
    // gate cannot help), stalling the whole single-threaded bridge from
    // inside a hook handler.
    if (!stat.isFile()) return false;
    const cache = slot.titleCache;
    if (cache && cache.path === transcriptPath && cache.mtimeMs === stat.mtimeMs && cache.size === stat.size) {
      return false;
    }
    const derived = deriveTranscriptTitle(transcriptPath, stat.size);
    slot.titleCache = { path: transcriptPath, mtimeMs: stat.mtimeMs, size: stat.size, title: derived.title };
    // A transcript that (no longer) yields a title never clears a previously
    // known one — a stale title beats flapping back to no label.
    if (!derived.title) return false;
    // Once the title came from an ai-title, only a provably-newer ai-title
    // may replace it: a partial scan whose only ai-title sits in the
    // immutable head chunk (or a first-prompt fallback) can be OLDER than
    // what the slot already carries — the newest ai-title may hide in the
    // skipped middle. Reverting would broadcast a stale title.
    if (slot.titleIsAi && !derived.aiIsNewest) return false;
    if (derived.title !== slot.title) {
      slot.title = derived.title;
      slot.titleIsAi = derived.fromAi;
      return true;
    }
    // Same text, but now known to be ai-derived: remember that so a later
    // prompt fallback or head-only ai-title cannot displace it.
    if (derived.fromAi) slot.titleIsAi = true;
    return false;
  } catch {
    return false;
  }
}

// The additive `title` field rides every session payload once known; absent
// until derivable (clients must tolerate either, per PROTOCOL.md).
function sessionEventPayload(slot, fields) {
  return slot.title ? { ...fields, title: slot.title } : fields;
}

// Refresh + broadcast: when an opportunistic refresh changes a running slot's
// title, clients learn it through an idempotent `session` running event (the
// same shape the connect-time sync re-sends).
function announceTitleRefresh(slot, transcriptPath) {
  if (!refreshSessionTitle(slot, transcriptPath)) return;
  if (slot.state !== "running") return;
  pushSseEvent(
    "session",
    sessionEventPayload(slot, { state: "running", agent: slot.agent, cwd: slot.cwd, folderName: slot.folderName }),
    slot.id,
  );
}

// Opportunistic refresh from a hook body (Stop is the natural moment: the
// turn just finished, so the transcript — and possibly its ai-title — just
// changed). Remembers the transcript path on the slot so later refreshes work
// even from payloads that omit it.
export function refreshHookSessionTitle(sessionId, body) {
  const slot = sessions.get(sessionId);
  if (!slot) return;
  const transcriptPath = transcriptPathOf(body);
  if (transcriptPath) slot.transcriptPath = transcriptPath;
  announceTitleRefresh(slot, slot.transcriptPath);
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

// --- First-output readiness -------------------------------------------------
// A freshly spawned agent PTY produces no output until the agent has actually
// started; injecting a command before then (or after the PTY died) silently
// drops it. bindPtyProcess marks the slot on its first stdout/stderr byte;
// waitForFirstPtyOutput resolves true then, or false when the PTY ends first
// or the bounded wait expires.

function flushReadyWaiters(slot, ready) {
  if (ready) slot.firstOutputSeen = true;
  const waiters = slot.readyWaiters;
  if (!waiters || waiters.length === 0) return;
  slot.readyWaiters = [];
  for (const waiter of waiters) waiter(ready);
}

export function waitForFirstPtyOutput(slot, timeoutMs) {
  if (!slot) return Promise.resolve(false);
  if (slot.firstOutputSeen) return Promise.resolve(true);
  if (!slot.ptyProcess) return Promise.resolve(false);
  return new Promise((resolve) => {
    const waiters = slot.readyWaiters ?? (slot.readyWaiters = []);
    const timer = setTimeout(() => {
      const idx = waiters.indexOf(waiter);
      if (idx !== -1) waiters.splice(idx, 1);
      resolve(false);
    }, timeoutMs);
    timer.unref();
    const waiter = (ready) => {
      clearTimeout(timer);
      resolve(ready);
    };
    waiters.push(waiter);
  });
}

// Guarded stdin write: returns false instead of throwing (or blind-firing)
// when the PTY is gone, its stdin is unusable, or the write itself throws.
// The async-failure case — a write racing child death that surfaces as a
// later EPIPE 'error' event — is absorbed by the stdin error listener that
// bindPtyProcess attaches.
export function writeToSessionStdin(slot, data) {
  const proc = slot?.ptyProcess;
  if (!proc || !proc.stdin || proc.stdin.destroyed || !proc.stdin.writable || proc.exitCode !== null) {
    return false;
  }
  try {
    proc.stdin.write(data);
    return true;
  } catch (err) {
    log("error", `Session ${slot.id} stdin write failed: ${err.message}`);
    return false;
  }
}

export function bindPtyProcess(slot, proc) {
  const sessionId = slot.id;
  slot.ptyProcess = proc;
  slot.firstOutputSeen = slot.firstOutputSeen || false;

  // Without an 'error' listener, a stdin write racing child death raises the
  // resulting EPIPE as an uncaught exception and can take the bridge down.
  proc.stdin?.on("error", (err) => {
    log("warn", `Session ${sessionId} stdin write error: ${err.code || err.message}`);
  });

  proc.stdout.on("data", (data) => {
    if (!slot.firstOutputSeen) flushReadyWaiters(slot, true);
    pushSseEvent("pty-output", { text: data.toString() }, sessionId);
  });

  proc.stderr.on("data", (data) => {
    if (!slot.firstOutputSeen) flushReadyWaiters(slot, true);
    pushSseEvent("pty-output", { text: data.toString() }, sessionId);
  });

  proc.on("close", (exitCode, signal) => {
    log("info", `Session ${sessionId} (${slot.agent}) PTY exited: code=${exitCode} signal=${signal}`);
    slot.state = "ended";
    slot.endedAt = Date.now();
    slot.ptyProcess = null;
    flushReadyWaiters(slot, false);
    runSessionCleanupHooks(sessionId, "pty-closed");
    pushSseEvent("session", sessionEventPayload(slot, { state: "ended", exitCode, signal, agent: slot.agent, folderName: slot.folderName }), sessionId);
  });

  proc.on("error", (err) => {
    log("error", `Session ${sessionId} PTY spawn error: ${err.message}`);
    slot.state = "ended";
    slot.endedAt = Date.now();
    slot.ptyProcess = null;
    flushReadyWaiters(slot, false);
    runSessionCleanupHooks(sessionId, "pty-error");
    pushSseEvent("session", sessionEventPayload(slot, { state: "ended", error: err.message, agent: slot.agent, folderName: slot.folderName }), sessionId);
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
  pushSseEvent("session", sessionEventPayload(slot, { state: "ended", agent: slot.agent, folderName: slot.folderName, killed: true }), sessionId);
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
    // Additive optional field: only present once derived from the transcript.
    ...(s.title ? { title: s.title } : {}),
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

// /hooks/* is unauthenticated, so hook-created slots must be bounded: before
// minting a new one, evict the oldest hook-created slot once the cap is hit.
// Ended slots (waiting out the prune grace period) go first — they are
// already dead; only then a running one. PTY-backed and codex-scanner slots
// are never evicted here: they come from the user or the local filesystem,
// not from the network.
function evictExternalSessionIfAtCap() {
  let count = 0;
  let oldestEnded = null;
  let oldestRunning = null;
  for (const [, slot] of sessions) {
    if (!slot.hookCreated) continue;
    count++;
    if (slot.state === "ended") {
      if (!oldestEnded || (slot.endedAt ?? slot.createdAt) < (oldestEnded.endedAt ?? oldestEnded.createdAt)) {
        oldestEnded = slot;
      }
    } else if (!oldestRunning || slot.createdAt < oldestRunning.createdAt) {
      oldestRunning = slot;
    }
  }
  if (count < MAX_EXTERNAL_SESSIONS) return;

  const victim = oldestEnded || oldestRunning;
  if (!victim) return;
  sessions.delete(victim.id);
  if (victim.hookSessionId) hookSessionIndex.delete(victim.hookSessionId);
  if (victim.state !== "ended") {
    runSessionCleanupHooks(victim.id, "evicted");
    pushSseEvent("session", sessionEventPayload(victim, { state: "ended", agent: victim.agent, folderName: victim.folderName, reason: "evicted" }), victim.id);
  }
  log("warn", `External session cap (${MAX_EXTERNAL_SESSIONS}) reached — evicted ${victim.state} session ${victim.id} (${victim.folderName})`);
}

// Auto-create a slot for an agent instance the bridge does not own (started
// outside the bridge, or a bridge-owned PTY whose cwd no longer matches).
function createExternalSession({ source, cwd, hookSessionId, transcriptPath }) {
  evictExternalSessionIfAtCap();

  const agent = source === "codex" ? "codex" : "claude";
  let resolvedCwd = cwd || CLI_CWD || process.env.HOME || process.cwd();
  const sessionId = crypto.randomUUID();

  // Bind to the transcript-verified project root, not the (possibly deep)
  // observed cwd. Legacy no-session_id slots are exempt: they are routed by
  // exact cwd match, which rebinding would break.
  let projectRootVerified = false;
  let projectRootAttempt;
  if (hookSessionId && transcriptPath) {
    projectRootAttempt = `${resolvedCwd}\n${transcriptPath}`;
    const root = findVerifiedProjectRoot(resolvedCwd, transcriptPath);
    if (root) {
      resolvedCwd = root;
      projectRootVerified = true;
    }
  }
  const folderName = path.basename(resolvedCwd) || resolvedCwd;

  const slot = {
    id: sessionId,
    agent,
    cwd: resolvedCwd,
    folderName,
    ptyProcess: null, // External process — no PTY owned by bridge
    state: "running",
    createdAt: Date.now(),
    hookCreated: true,
    projectRootVerified,
    projectRootAttempt,
  };
  if (hookSessionId) bindHookSession(slot, hookSessionId);
  if (transcriptPath) {
    slot.transcriptPath = transcriptPath;
    // Session creation is one of the opportunistic refresh points: derive the
    // title now so the initial "running" event already carries it.
    refreshSessionTitle(slot, transcriptPath);
  }
  sessions.set(sessionId, slot);

  log("info", `Auto-created session ${sessionId} for external ${agent} (${folderName})`);
  pushSseEvent("session", sessionEventPayload(slot, { state: "running", agent, cwd: resolvedCwd, folderName }), sessionId);

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
  const transcriptPath = transcriptPathOf(body);

  if (hookSessionId) {
    // A known session_id always routes to the slot it was bound to.
    const boundId = hookSessionIndex.get(hookSessionId);
    if (boundId) {
      const bound = sessions.get(boundId);
      if (bound) {
        // Remember (never read) the transcript path on the hot path; the
        // stat+read happens only at the opportunistic refresh points.
        if (transcriptPath) bound.transcriptPath = transcriptPath;
        // Attribution upkeep (issue #51): a later event may verify the
        // project root against the transcript, or reveal an ancestor cwd —
        // cheap string compares only, the ancestor walk is cached per slot.
        updateProjectAttribution(bound, cwd, transcriptPath);
        return bound.id;
      }
      hookSessionIndex.delete(hookSessionId); // slot was pruned
    }

    // First event for this session_id: bind it to a running PTY-backed slot
    // in the same cwd that no other session_id has claimed yet.
    if (cwd) {
      for (const [, slot] of sessions) {
        if (slot.state === "running" && slot.ptyProcess && slot.cwd === cwd && !slot.hookSessionId) {
          bindHookSession(slot, hookSessionId);
          // A fresh binding is session creation from the slot's point of
          // view: derive the title now and broadcast it if it changed.
          if (transcriptPath) {
            slot.transcriptPath = transcriptPath;
            announceTitleRefresh(slot, transcriptPath);
          }
          return slot.id;
        }
      }
    }

    // No claimable slot — this is an external instance; give it its own slot.
    return createExternalSession({ source, cwd, hookSessionId, transcriptPath });
  }

  // Legacy payload without session_id: exact-cwd match only. Resolve the cwd
  // fallback chain first so repeated cwd-less events (e.g. the codex-watch
  // wrapper's turn.completed posts) reuse one slot instead of minting one per
  // event.
  const resolvedCwd = cwd || CLI_CWD || process.env.HOME || process.cwd();
  const match = findSessionByCwd(resolvedCwd);
  if (match) {
    if (transcriptPath) match.transcriptPath = transcriptPath;
    return match.id;
  }

  return createExternalSession({ source, cwd: resolvedCwd, hookSessionId: null, transcriptPath });
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
  // Last chance to read the final transcript state (SessionEnd is an
  // opportunistic refresh point) so the "ended" event carries the final title.
  const transcriptPath = transcriptPathOf(body);
  if (transcriptPath) slot.transcriptPath = transcriptPath;
  refreshSessionTitle(slot, slot.transcriptPath);
  if (slot.ptyProcess) {
    // Bridge-owned: the PTY close handler ends the slot, not this hook. But
    // the Claude Code instance behind this session_id is gone — /clear and
    // /login fire SessionEnd and start a successor with a fresh session_id in
    // the SAME still-running PTY. Release the binding so the successor's
    // first cwd-matched event re-claims this slot instead of minting a
    // phantom external session that all later events would misroute to.
    if (slot.hookSessionId) {
      hookSessionIndex.delete(slot.hookSessionId);
      slot.hookSessionId = undefined;
    }
    return slot.id;
  }

  slot.state = "ended";
  slot.endedAt = Date.now();
  runSessionCleanupHooks(slot.id, "session-end-hook");
  pushSseEvent("session", sessionEventPayload(slot, { state: "ended", agent: slot.agent, folderName: slot.folderName, reason: "session-end" }), slot.id);
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
        data: JSON.stringify(sessionEventPayload(slot, {
          state: "running",
          agent: slot.agent,
          cwd: slot.cwd,
          folderName: slot.folderName,
          sessionId: sid,
        })),
      };
    }
  }
});
