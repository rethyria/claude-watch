// Multi-session management: the sessions map, spawning/attaching/killing
// PTY-backed agent sessions, ACP (Zed-hosted) slot lifecycle, and lookup
// helpers.
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
  SESSION_UNHOSTED_GRACE_MS,
  SESSION_SILENT_GRACE_MS,
  WORKFLOW_POLL_MS,
  WORKFLOW_STALE_MS,
  CLAUDE_PROJECTS_ROOT,
} from "./config.js";
import { pushSseEvent, registerSseSyncProvider } from "./transport-sse.js";

// Multi-session: each entry is a session slot
// { id, agent, cwd, folderName, ptyProcess, state, createdAt, endedAt?,
//   kind?, title?, titleIsAi?, transcriptPath?, endedAuthoritatively? } —
//   title is pushed by the ACP adapter (register seed + session_info_update);
//   transcriptPath feeds the workflow-activity scanner and the zombie
//   ageing's transcript-mtime evidence (for ACP slots it is DERIVED from the
//   CLI projects-dir convention — see deriveAcpTranscriptPath);
//   endedAuthoritatively marks an ending as final (a PTY exit, an ACP
//   deregister) so a later observation can never revive it (issue #53);
//   branch/worktree/repoRoot + gitMetaCache carry the git metadata derived at
//   the slot's root (issue #54, see the git-metadata section below);
//   agents/workflowActive/workflowWatching/workflowActivatedAt/workflowSawRunning/
//   workflowDone/workflowJournalCache track subagent workflow activity
//   (issue #55, see the workflow-activity section);
//   idle carries the TURN-level truth that state cannot (issue #60 — see
//   the turn-end-idle section below): state stays "running" across a finished
//   turn, so idle is what tells a connect-time snapshot apart from a session
//   that is actually producing work;
//   observedAt is the last moment the bridge OBSERVED the session existing
//   (an ACP register or turn boundary, a Codex scan) — the clock the zombie
//   ageing measures silence from (issue #65, see the section below).
/** @type {Map<string, {id: string, agent: string, cwd: string, folderName: string, ptyProcess: import("child_process").ChildProcess | null, state: string, createdAt: number, endedAt?: number, kind?: "acp", idle?: boolean, observedAt?: number, title?: string, titleIsAi?: boolean, transcriptPath?: string, endedAuthoritatively?: boolean, branch?: string, worktree?: boolean, repoRoot?: string, gitMetaCache?: {headPath: string, mtimeMs: number, size: number}, agents?: {running: number, done: number}, workflowActive?: boolean, workflowWatching?: boolean, workflowActivatedAt?: number, workflowSawRunning?: boolean, workflowDone?: number, workflowJournalCache?: Map<string, {mtimeMs: number, size: number, running: number, done: number}>}>} */
export const sessions = new Map();

// --- Git metadata (issue #54) -----------------------------------------------
// Additive branch/worktree/repoRoot fields on session payloads, derived at the
// slot's bound root via FILE READS ONLY (never a spawned git process — the
// refresh points sit on event paths and the bridge must stay subprocess-free
// there):
//   * <root>/.git is a DIRECTORY  → main checkout; HEAD is <root>/.git/HEAD.
//   * <root>/.git is a FILE       → `gitdir: <path>` pointer. When <path>
//     matches `.../.git/worktrees/<name>` exactly, the root is a linked
//     worktree of the main repo three levels up; any other pointer target
//     (a submodule's .git/modules/<n>, a relocated gitdir) is treated as a
//     plain checkout — branch at most, never a guessed worktree/repoRoot.
//   * anything else               → not a git checkout; previously-known
//     values are PRESERVED, not cleared (stale beats flapping, same doctrine
//     as `title` — clients treat an absent field as "keep what you knew").
// HEAD reads are stat-gated on (headPath, mtime, size): an unchanged HEAD
// costs one stat and no read, and a branch switch rewrites HEAD so the cache
// invalidates naturally.

// A real .git pointer file is one short line; HEAD is a ref line or a 40-hex
// sha. Anything bigger is not what we think it is and gets skipped outright.
const GIT_FILE_SCAN_BYTES = 8 * 1024;
const GIT_HEAD_SCAN_BYTES = 1024;

// Bounded read of an already-stat'ed regular file (callers hold the stat and
// have applied the isFile()/size gates: opening/reading a FIFO or device node
// here would block forever or read unboundedly — their stat size is 0, so a
// size gate cannot help — stalling the whole single-threaded bridge). Throws
// I/O errors; callers absorb them.
function readFileBounded(filePath, size) {
  const fd = fs.openSync(filePath, "r");
  try {
    const buf = Buffer.alloc(size);
    const read = fs.readSync(fd, buf, 0, size, 0);
    return buf.toString("utf-8", 0, read);
  } finally {
    fs.closeSync(fd);
  }
}

// Resolve where the root's HEAD file lives and what kind of checkout the root
// is. Returns { headPath, worktree, repoRoot } — repoRoot only for a verified
// linked-worktree structure — or null when the root is not a git checkout (or
// its .git pointer is unreadable/malformed). Throws only I/O errors from the
// stat/read; the caller absorbs them.
function resolveGitLayout(root) {
  const dotGit = path.join(root, ".git");
  let stat;
  try { stat = fs.statSync(dotGit); } catch { return null; }
  if (stat.isDirectory()) {
    return { headPath: path.join(dotGit, "HEAD"), worktree: false, repoRoot: null };
  }
  // Not a regular file (FIFO, device, socket): never open it (see the
  // isFile() discipline above) — treat as not-a-checkout.
  if (!stat.isFile() || stat.size > GIT_FILE_SCAN_BYTES) return null;
  const text = readFileBounded(dotGit, stat.size);
  if (!text.startsWith("gitdir:")) return null;
  let gitdir = text.slice("gitdir:".length).split("\n", 1)[0].trim();
  if (!gitdir) return null;
  // A relative gitdir is relative to the directory holding the .git file.
  if (!path.isAbsolute(gitdir)) gitdir = path.resolve(root, gitdir);
  // Linked worktree ONLY when the pointer target matches .../.git/worktrees/<name>
  // exactly: both the "worktrees" segment and its ".git" parent are verified,
  // so a lookalike path can never yield a wrong repoRoot (never guess).
  const worktreesDir = path.dirname(gitdir);
  const mainGitDir = path.dirname(worktreesDir);
  if (path.basename(worktreesDir) === "worktrees" && path.basename(mainGitDir) === ".git") {
    return { headPath: path.join(gitdir, "HEAD"), worktree: true, repoRoot: path.dirname(mainGitDir) };
  }
  return { headPath: path.join(gitdir, "HEAD"), worktree: false, repoRoot: null };
}

// Parse HEAD content into a branch label: `ref: refs/heads/<branch>` → the
// branch (slashes included: "feature/x"), a bare 40-hex sha (detached HEAD) →
// its 7-char short form. Anything else → null (caller leaves the slot as-is).
function parseGitHead(text) {
  const line = text.split("\n", 1)[0].trim();
  if (line.startsWith("ref: refs/heads/")) {
    return line.slice("ref: refs/heads/".length).trim() || null;
  }
  if (/^[0-9a-f]{40}$/.test(line)) return line.slice(0, 7);
  return null;
}

// Refresh a slot's git metadata from its bound root, stat-gated by
// slot.gitMetaCache. Returns true when branch/worktree/repoRoot actually
// changed. Never throws; any failure (no checkout, unreadable HEAD) preserves
// previously-known values — see the section comment for the doctrine.
export function refreshGitMetadata(slot) {
  if (!slot || typeof slot.cwd !== "string" || !slot.cwd) return false;
  try {
    const layout = resolveGitLayout(slot.cwd);
    if (!layout) return false;
    const stat = fs.statSync(layout.headPath);
    if (!stat.isFile()) return false;
    const cache = slot.gitMetaCache;
    if (cache && cache.headPath === layout.headPath && cache.mtimeMs === stat.mtimeMs && cache.size === stat.size) {
      return false; // unchanged HEAD: one stat, no read
    }
    if (stat.size > GIT_HEAD_SCAN_BYTES) return false;
    const branch = parseGitHead(readFileBounded(layout.headPath, stat.size));
    // Cache even a parse failure so a persistently-weird HEAD costs one stat
    // per refresh, not one read.
    slot.gitMetaCache = { headPath: layout.headPath, mtimeMs: stat.mtimeMs, size: stat.size };
    if (!branch) return false; // unparseable HEAD content: leave unchanged
    // A definitive derivation replaces all three fields together (a rebind
    // from a worktree to a main checkout must drop the worktree claim);
    // worktree/repoRoot are stored as undefined — not false/null — so the
    // payload/snapshot spreads omit them entirely per the wire contract.
    const worktree = layout.worktree ? true : undefined;
    const repoRoot = layout.repoRoot ?? undefined;
    if (slot.branch === branch && slot.worktree === worktree && slot.repoRoot === repoRoot) return false;
    slot.branch = branch;
    slot.worktree = worktree;
    slot.repoRoot = repoRoot;
    return true;
  } catch {
    return false;
  }
}

// --- Turn-end idle (issue #60) ----------------------------------------------
// The slot model has exactly two lifecycle states, `running` and `ended`, and
// that is deliberate: a turn ends at the end of every TURN, and mapping that
// to "ended" would kill a live session on the watch after its first reply.
// A turn boundary only moves the flag below and leaves slot.state alone.
//
// What that left uncovered showed up on hardware: a session whose last signal
// was a turn end three hours earlier rendered GREEN on a freshly-paired watch.
// The connect-time sync re-sent the still-live slot as `running`; the client
// creates a session it has never seen before as WORKING; and the event that
// had idled it had long since aged out of the SSE replay ring, so nothing
// could ever correct it. Green means "it is working" — the exact opposite of
// the truth, on the one screen whose entire job is at-a-glance honesty.
//
// So the slot carries the turn-level truth alongside the lifecycle one:
// `idle` is TRUE once the last lifecycle signal was a turn END (an ACP
// `kind: "turn"` end, a Codex task_complete) and FALSE while the session is
// producing work (Codex tool output, PTY output, an ACP turn start). That
// signal set is deliberately the SAME one the watch reducer folds into
// markIdle/markWorking, so the flag is exactly "what a client watching live
// would have computed" — pre-computed for the clients that were NOT watching.
//
// On the WIRE, absence still means working (the omit-when-false doctrine
// below), but on the SLOT it does not: the connect-time sync reports the flag
// as a tri-state and an unset one there means "no turn signal has EVER been
// observed", which clients render idle. So every path that observes work must
// say `false` OUT LOUD rather than leave the field unset — PTY bytes
// (bindPtyProcess), a Codex file write (codex.js), an ACP turn start — and a
// slot with no field is reserved for the sessions the bridge genuinely cannot
// vouch for (an ACP register that reported no `active` and has yet to run a
// turn). Leaving a working session unflagged reads as that, and paints it grey.
//
// Setting it never broadcasts. Live clients already learn a turn end from the
// `task-complete` event (Codex) or the idempotent session push acp.js makes at
// its turn boundary, and new work from the output events, so an extra
// `session` push per turn here would be pure noise (and per turn is a LOT of
// pushes). The flag's whole job is to ride the NEXT session event — above all
// the connect-time snapshot, which is where the bug lived.

/** Mark a slot idle: its last lifecycle signal was a turn end. */
export function markSessionIdle(sessionId) {
  const slot = sessions.get(sessionId);
  if (!slot) return;
  slot.idle = true;
  // A turn boundary is the bridge OBSERVING the session, which is what the
  // zombie ageing measures silence from (issue #65). A session that keeps
  // ending turns is alive, however little else it says.
  markSessionObserved(slot);
}

/** Mark a slot working again: it just produced output. */
export function markSessionWorking(sessionId) {
  const slot = sessions.get(sessionId);
  if (!slot) return;
  slot.idle = false;
  markSessionObserved(slot);
}

// The additive `title` field rides every session payload once known; absent
// until derivable (clients must tolerate either, per PROTOCOL.md). The same
// absent-means-preserve doctrine covers the additive git-metadata fields
// (branch/worktree/repoRoot, issue #54) and the workflow-activity `agents`
// object (issue #55 — completion is the EXPLICIT {running: 0, done: N},
// because omission can never clear). An ACP slot is ALSO tagged
// `external: true` here, uniformly on EVERY session event (running/ended +
// the connect-time sync), so clients know the bridge does not own its
// process. The flag is OMITTED for bridge-owned PTY slots — older clients
// tolerate that, and clients treat absent as external=false (killable). The
// turn-end `idle` flag (issue #60) follows the exact same
// present-only-when-true rule, and for the same reason must ride EVERY
// session event uniformly: it is the connect-time snapshot — an event nobody
// thinks of as "a state change" — that carries the only honest answer for a
// session idled before the client existed. Kept in lockstep with
// getSessionsSnapshot.
//
// Exported so session events pushed from outside this module (acp.js's
// announceAcpSlot) go through it too. PROTOCOL.md claims these fields ride
// EVERY session event; a hand-built payload elsewhere makes that claim true
// only by luck, and the luck runs out the moment a slot has a title or an
// `external` tag the hand-built push forgets.
export function sessionEventPayload(slot, fields) {
  const payload = { ...fields };
  if (slot.title) payload.title = slot.title;
  if (slot.branch) payload.branch = slot.branch;
  if (slot.worktree) payload.worktree = true;
  if (slot.repoRoot) payload.repoRoot = slot.repoRoot;
  if (slot.agents) payload.agents = slot.agents;
  // `external` marks a slot the bridge does NOT own a PTY for. An ACP session
  // is Zed's process, not ours — but it is really killable end to end (#88:
  // the kill rides a close frame to the fork), so clients pair the tag with
  // `kind` to pick the affordance (S3 #77).
  if (slot.kind === "acp") payload.external = true;
  // Additive session-type discriminator + dictatable flag (S3 #77 / S4 #78).
  // `kind` is currently carried only for ACP; `dictatable` is DERIVED, not
  // stored, so it is always honest: the bridge can deliver dictation into a
  // session it can reach LIVE — its own PTY (stdin) or an ACP session (inject
  // over the loopback channel) — and nothing else (a Codex-scanner slot with
  // no attached PTY has no reachable input channel: /command answers 409 for
  // it, since the detached headless fork that used to serve it was retired in
  // #81). A PTY that dies drops ptyProcess to null and the flag vanishes on
  // its own. Clients gate the Dictate affordance on `dictatable`, NOT on
  // "external" (an ACP session is both external AND dictatable).
  if (slot.kind) payload.kind = slot.kind;
  // Liveness is part of "can the bridge deliver into this", so an ended slot
  // must drop the flag (#84): delivery already refuses honestly once the
  // connection binding is gone, but a client gating on `dictatable` alone would
  // still OFFER Dictate on a dead session and then eat the 502.
  if (slot.state !== "ended" && (slot.ptyProcess || slot.kind === "acp")) payload.dictatable = true;
  // Rides `ended` payloads too — meaningless there (clients prune ended
  // sessions outright), but uniformity beats a special case nobody reads.
  if (slot.idle) payload.idle = true;
  // Additive spawn attribution: echoes the requestId of the watch spawn that
  // created this session, so a client whose spawn call timed out can match
  // the late arrival to it ("arrived late", not a mystery session). Clients
  // that don't know the field ignore it.
  if (slot.spawnRequestId) payload.spawnRequestId = slot.spawnRequestId;
  // Additive subheading meta (#97, Halo v2): the session's model display name,
  // ACP permission-mode id, and integer context-used percent. Written only by
  // the ACP lane (register seed + teed updates, acp.js), so PTY/hook sessions
  // simply omit them; absent means preserve, per the title doctrine.
  // `contextPct` is compared as a number, not truthiness — 0% is a real value
  // a fresh session legitimately reports.
  if (slot.model) payload.model = slot.model;
  if (slot.mode) payload.mode = slot.mode;
  if (typeof slot.contextPct === "number") payload.contextPct = slot.contextPct;
  return payload;
}

// Refresh + broadcast: when an opportunistic refresh changes a running slot's
// git metadata, clients learn it through ONE idempotent `session` running
// event (the same shape the connect-time sync re-sends).
function announceGitMetadataRefresh(slot) {
  if (!refreshGitMetadata(slot)) return;
  if (slot.state !== "running") return;
  pushSseEvent(
    "session",
    sessionEventPayload(slot, { state: "running", agent: slot.agent, cwd: slot.cwd, folderName: slot.folderName }),
    slot.id,
  );
}

// --- Workflow activity (issue #55) ------------------------------------------
// The Workflow tool runs subagents whose only bridge-observable trail is the
// per-workflow journal Claude Code writes next to the transcript:
//   <transcriptPath minus ".jsonl">/subagents/workflows/wf_*/journal.jsonl
// with one {"type":"started","key":…,"agentId":…} line per launched agent and
// one {"type":"result","key":…} line per finished one (matched on `key`,
// falling back to agentId for records that lack it). running = started keys
// without a matching result key.
//
// Lifecycle: the launch signal is a Workflow tool call — the teed ACP
// tool_call whose _meta names the tool (acp.js, issue #105) calls
// markWorkflowActivity, which flips the slot workflow-active and runs the
// first scan immediately so the indicator does not wait out a poll interval.
// A module-level poll then visits ONLY armed or watching slots every
// WORKFLOW_POLL_MS (the poll's cheap boolean gate makes idle ticks free,
// issue #108's watching notwithstanding). A bridge restarted
// mid-workflow loses the in-memory arming and never sees a fresh launch
// signal; reconcileWorkflowActivity re-derives the indicator from the on-disk
// journal when the surviving session re-registers (registerAcpSession) — so a
// re-registering session's stale blue is corrected rather than stranded
// (issue #68).
//
// Completion vs. between-phases: a multi-phase workflow legitimately reads
// zero running in the gap between phases (phase N's agents all finished,
// phase N+1 not spawned yet), and the journal is then indistinguishable from
// a genuinely finished workflow — the only difference is future growth. So a
// zero-running scan is NOT completion while the workflow tree is still fresh;
// the indicator holds and the poll stays armed (issue #70, flaw 1). The
// explicit {running: 0} completion state is broadcast once — clearing the
// indicator and going workflow-inactive — only when the whole tree has gone
// quiet for the staleness window (below). The cost is the indicator lingering
// up to WORKFLOW_STALE_MS after the final agent finishes; the alternative
// (clearing on a transient inter-phase zero) drops it mid-workflow, which is
// the bug this guards against. A zero only counts at all once the workflow was
// actually OBSERVED since arming (a running agent seen, or a write since the
// launch signal — the signal races the runner's first journal write, so an
// early scan can see nothing live).
//
// Staleness / liveness: a workflow dir counts as dead once its newest write is
// older than WORKFLOW_STALE_MS. That newest write is taken across BOTH
// journal.jsonl AND the sibling agent-<id>.jsonl transcripts: journal.jsonl
// only gains a line when an agent starts or finishes, so a phase with one
// long-running agent would otherwise look dead for minutes while that agent's
// transcript is actively written (issue #70, flaw 2). A killed workflow stops
// writing everything, so it still goes stale and clears the indicator instead
// of pinning it forever. `done` aggregates only live (non-stale) dirs, so a
// long session's completed workflow history cannot inflate the count.
//
// Watching / resurrection (issue #108): the staleness window decides only WHEN
// the honest zero is broadcast — it is not a verdict of death. A machine-sleep
// resume, or a single tool call silent for longer than the window (transcripts
// gain nothing until a tool result returns), makes a "completed" tree start
// writing again with the one-shot launch signal long past; before #108 nothing
// could re-arm short of a bridge restart. So a stale-clear moves the slot from
// armed to WATCHING rather than fully disarming it: the same poll keeps
// re-statting the tree (watchWorkflowActivity — a bounded stat sweep; the tree
// exists, so the slot has no claim to the idle fast path), and the moment the
// newest write is back inside the staleness window it re-arms and re-publishes
// exactly as the restart reconcile does. The watch rides the existing
// WORKFLOW_POLL_MS tick rather than a slower sibling: the sweep is a handful
// of stats, the interval is already coarse, and a second timer would mean a
// second gate to keep the idle path free. Watching ends with the slot's life
// (session end or prune — the poll's state gate) or with the tree itself
// vanishing, never with silence; only sessions with NO workflow tree stay off
// the poll entirely.

// A real journal is a few KB. Reading only a prefix of an oversized one could
// see a `started` whose `result` sits beyond the cap — a phantom running
// agent forever — so oversized journals are skipped entirely, not truncated.
const WORKFLOW_JOURNAL_MAX_BYTES = 1024 * 1024;

// Cheap gate for the poll tick: false ⇒ the tick returns without touching the
// sessions map at all. True while any slot is armed OR watching (issue #108):
// a watching slot must keep the poll alive, or the stale-clear that flipped it
// would also be the tick that silenced its own resurrection path.
let anyWorkflowTracked = false;

// Count one journal's agents: running = started keys without a result, done =
// started keys WITH one (an orphan result whose started line we never saw is
// ignored rather than counted). Malformed lines are skipped silently, same as
// transcript parsing. Throws only I/O errors; the caller absorbs them.
function countWorkflowJournal(journalPath, size) {
  const started = new Set();
  const finished = new Set();
  for (const line of readFileBounded(journalPath, size).split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    let record;
    try { record = JSON.parse(trimmed); } catch { continue; }
    if (!record || typeof record !== "object") continue;
    const key = (typeof record.key === "string" && record.key) ? record.key
      : (typeof record.agentId === "string" && record.agentId) ? record.agentId
      : null;
    if (!key) continue;
    if (record.type === "started") started.add(key);
    else if (record.type === "result") finished.add(key);
  }
  let running = 0;
  let done = 0;
  for (const key of started) {
    if (finished.has(key)) done++;
    else running++;
  }
  return { running, done };
}

// Newest mtime among a workflow dir's agent-<id>.jsonl transcripts (0 if
// none). Liveness fallback for when journal.jsonl itself already looks stale:
// journal.jsonl only gains a line on an agent start/finish, so a long-running
// single-agent phase keeps only its transcript warm (issue #70, flaw 2). The
// readdir cost is paid only on that quiet path, never on the common one.
// Never throws.
function newestAgentTranscriptMtimeMs(wfDir) {
  let names;
  try { names = fs.readdirSync(wfDir); } catch { return 0; }
  let newest = 0;
  for (const name of names) {
    if (!name.startsWith("agent-") || !name.endsWith(".jsonl")) continue;
    let stat;
    try { stat = fs.statSync(path.join(wfDir, name)); } catch { continue; }
    if (stat.isFile() && stat.mtimeMs > newest) newest = stat.mtimeMs;
  }
  return newest;
}

// Enumerate the slot's wf_* journal dirs and aggregate {running, done} over
// the live (non-stale) ones, plus latestMtimeMs — the newest write seen across
// all dirs (0 when there are none), which lets the caller tell "written since
// the launch signal" apart from "only leftovers from an earlier workflow" and
// "the tree is still live" apart from "gone quiet". Returns null when there is
// no journal tree to observe (no transcript, no workflows dir yet). Per-journal
// counts are cached stat-gated on (mtime, size) — an unchanged journal costs
// one stat. Never throws.
function scanWorkflowActivity(slot, now) {
  const transcriptPath = slot.transcriptPath;
  if (typeof transcriptPath !== "string" || !transcriptPath.endsWith(".jsonl")) return null;
  const wfRoot = path.join(transcriptPath.slice(0, -".jsonl".length), "subagents", "workflows");
  let entries;
  try { entries = fs.readdirSync(wfRoot, { withFileTypes: true }); } catch { return null; }
  const cache = slot.workflowJournalCache ?? (slot.workflowJournalCache = new Map());
  const seen = new Set();
  let running = 0;
  let done = 0;
  let latestMtimeMs = 0;
  let unreadableLive = false;
  for (const entry of entries) {
    if (!entry.isDirectory() || !entry.name.startsWith("wf_")) continue;
    const wfDir = path.join(wfRoot, entry.name);
    const journalPath = path.join(wfDir, "journal.jsonl");
    let stat;
    try { stat = fs.statSync(journalPath); } catch { continue; }
    if (!stat.isFile()) continue; // never open a non-regular file (see readFileBounded)
    seen.add(journalPath);
    // Liveness is the newest write anywhere in the dir. Only when journal.jsonl
    // itself already looks stale do we pay to stat the agent transcripts — a
    // long-running single-agent phase keeps only its transcript warm while the
    // journal sits untouched for minutes (issue #70, flaw 2).
    let dirMtimeMs = stat.mtimeMs;
    if (now - dirMtimeMs > WORKFLOW_STALE_MS) {
      const agentMtimeMs = newestAgentTranscriptMtimeMs(wfDir);
      if (agentMtimeMs > dirMtimeMs) dirMtimeMs = agentMtimeMs;
    }
    if (dirMtimeMs > latestMtimeMs) latestMtimeMs = dirMtimeMs;
    if (now - dirMtimeMs > WORKFLOW_STALE_MS) continue; // dead workflow dir
    let counts = cache.get(journalPath);
    if (!counts || counts.mtimeMs !== stat.mtimeMs || counts.size !== stat.size) {
      // A LIVE journal the scan cannot read — outgrown the cap (see the cap
      // comment) or a racing I/O failure — is INDETERMINATE, not absent:
      // fall back to its last-known counts and flag the scan, so a
      // zero-running aggregate is never mistaken for completion while an
      // unreadable live journal exists (skipping it outright made the
      // indicator vanish mid-run, and the disarm is final until the next
      // Workflow hook). Once the journal goes stale the ordinary staleness
      // gate above retires it and completion proceeds normally.
      if (stat.size > WORKFLOW_JOURNAL_MAX_BYTES) {
        unreadableLive = true;
        if (counts) { running += counts.running; done += counts.done; }
        continue;
      }
      try {
        counts = { mtimeMs: stat.mtimeMs, size: stat.size, ...countWorkflowJournal(journalPath, stat.size) };
      } catch {
        unreadableLive = true;
        if (counts) { running += counts.running; done += counts.done; }
        continue;
      }
      cache.set(journalPath, counts);
    }
    running += counts.running;
    done += counts.done;
  }
  // Drop cache entries for vanished journals so the map cannot grow forever.
  for (const key of cache.keys()) {
    if (!seen.has(key)) cache.delete(key);
  }
  return { running, done, latestMtimeMs, unreadableLive };
}

// Scan one active slot; on a change to {running, done}, update slot.agents (a
// REPLACED object, never mutated in place — broadcast payloads hold a
// reference) and push the idempotent running `session` event. A zero-running
// scan clears the indicator and flips the slot workflow-inactive ONLY once the
// tree has gone stale (a fresh zero is the between-phases gap — see the
// completion note above, issue #70); after that the slot WATCHES its tree —
// a fresh Workflow hook or the tree coming back to life re-arms scanning
// (issue #108, watchWorkflowActivity).
function scanAndAnnounceWorkflowActivity(slot, now) {
  const counts = scanWorkflowActivity(slot, now);
  if (counts && counts.running > 0) slot.workflowSawRunning = true;
  // Remember the peak completed-agent count from a live scan. Completion fires
  // only once the tree is stale, and a stale dir is excluded from aggregation
  // (its `done` re-reads as 0) — so without this the completion broadcast would
  // report done: 0 instead of the true total. Reset per arming (below).
  if (counts && counts.done > (slot.workflowDone ?? 0)) slot.workflowDone = counts.done;
  // A zero-running scan may count as anything only if this arming actually
  // observed its workflow: a running agent on some scan since the launch
  // signal, or a write since it. Otherwise the launch signal beat the runner's
  // first journal write (the workflows dir holding nothing, or only stale
  // leftovers from an earlier workflow) — that is "observed nothing live", not
  // "observed completion", and must be treated exactly like an absent journal
  // tree below rather than broadcast as a spurious {running: 0}. (running > 0
  // implies observed, so this equivalently gates the whole scan.)
  const observed = counts !== null && (
    slot.workflowSawRunning === true ||
    (slot.workflowActivatedAt !== undefined && counts.latestMtimeMs >= slot.workflowActivatedAt)
  );
  if (!counts || !observed) {
    // Nothing observable yet (journal tree absent, or nothing live since the
    // launch signal). Stay armed for the next poll, but give up after the
    // staleness window so a Workflow hook whose journals never materialize
    // cannot pin the poll on forever.
    if (slot.workflowActivatedAt !== undefined && now - slot.workflowActivatedAt > WORKFLOW_STALE_MS) {
      slot.workflowActive = false;
      // The give-up is not a verdict either (issue #108): a tree that EXISTS
      // (stale leftovers) keeps being watched — a future write re-arms. Only
      // the no-tree case goes fully quiet, preserving the zero-syscall idle
      // path for ordinary sessions.
      if (counts) slot.workflowWatching = true;
    }
    return;
  }
  // Zero running while a LIVE journal was unreadable (oversized/racing I/O)
  // is indeterminate, not completion: keep the last broadcast state and stay
  // armed. The journal going stale — or readable again — resolves it.
  if (counts.running === 0 && counts.unreadableLive) return;
  // Zero running while the workflow tree is still fresh is the gap BETWEEN
  // phases, not completion — the journal cannot tell the two apart (see the
  // completion note above). Hold the last broadcast state and stay armed; only
  // a tree quiet for the whole staleness window is a real completion. (now -
  // latestMtimeMs naturally treats latestMtimeMs === 0 as long-stale.)
  if (counts.running === 0 && now - counts.latestMtimeMs <= WORKFLOW_STALE_MS) return;
  if (counts.running === 0) {
    slot.workflowActive = false;
    // The zero below is honest UI, not a verdict of death: keep WATCHING the
    // tree so a resume (machine wake, a longer-than-window silent tool call
    // returning) re-arms without a launch signal (issue #108).
    slot.workflowWatching = true;
  }
  // Report the peak done — equal to counts.done mid-run, but preserved across
  // the completion broadcast where the now-stale dir aggregates to 0.
  const done = Math.max(counts.done, slot.workflowDone ?? 0);
  const prev = slot.agents;
  if (prev && prev.running === counts.running && prev.done === done) return;
  slot.agents = { running: counts.running, done };
  log("info", `Workflow activity: session ${slot.id} running=${counts.running} done=${done}`);
  if (slot.state !== "running") return;
  pushSseEvent(
    "session",
    sessionEventPayload(slot, { state: "running", agent: slot.agent, cwd: slot.cwd, folderName: slot.folderName }),
    slot.id,
  );
}

// Launch signal, called by acp.js when a teed tool_call names the Workflow
// tool. Marks the slot active and scans immediately so the indicator appears
// without waiting out a poll interval.
export function markWorkflowActivity(sessionId) {
  const slot = sessions.get(sessionId);
  if (!slot) return;
  log("info", `Workflow launch signal: armed scanner for session ${sessionId}`);
  slot.workflowActive = true;
  // An armed slot is scanned, not watched — the launch signal supersedes any
  // watch a previous workflow's stale-clear left behind (issue #108).
  slot.workflowWatching = false;
  slot.workflowActivatedAt = Date.now();
  // Each arming must observe ITS workflow before a zero-running scan may
  // count as completion (see scanAndAnnounceWorkflowActivity).
  slot.workflowSawRunning = false;
  // Peak done is per-workflow: a fresh arming (a new Workflow call, hence a new
  // journal) must not inherit the previous workflow's completed count.
  slot.workflowDone = 0;
  anyWorkflowTracked = true;
  scanAndAnnounceWorkflowActivity(slot, Date.now());
}

// Issue #68: workflow arming lives only in memory, so a workflow in flight when
// the bridge restarts never re-arms — the scan never runs, the completion
// {running: 0} is never computed, and a client's preserve-on-absence `agents`
// value stays stuck blue forever. A session that outlives a bridge restart
// re-registers through registerAcpSession (the fork's replay), so that is
// where we reconcile: read the workflow journal tree from disk and set
// the slot's authoritative `agents`, which the imminent running broadcast (and
// the connect-time sync, which reuses sessionEventPayload) then carry to the
// reconnecting client, overwriting whatever it latched before the restart.
//   - LIVE journal (a workflow still in flight): re-arm the scanner so the poll
//     tracks it to completion exactly as a fresh launch would, and seed the
//     current running count.
//   - STALE journal (the workflow finished or died during the downtime): set the
//     explicit zero so the client's stale blue clears. Nothing live to track, so
//     do not arm — but WATCH the tree (issue #108): the downtime may have been a
//     machine sleep the workflow survived, and its resumed writes must re-arm.
//   - no journal tree: no workflow ran — leave `agents` absent, adding no noise
//     to the far commoner ordinary-session registration.
// A fresh Workflow hook that already armed this slot takes precedence (guarded).
function reconcileWorkflowActivity(slot, now = Date.now()) {
  if (slot.workflowActive) return;
  const counts = scanWorkflowActivity(slot, now);
  if (!counts) return;
  if (now - counts.latestMtimeMs <= WORKFLOW_STALE_MS) {
    // A workflow is in flight — re-arm so the poll tracks it to completion.
    // Unlike markWorkflowActivity (which races the runner's first journal
    // write), reconcile arms only AFTER scanning a live journal tree, so its
    // workflow is observed by construction: mark it seen so that once the tree
    // goes stale the poll broadcasts the completion zero (clearing the client)
    // rather than giving up silently on an "observed nothing live" slot — which
    // would strand a client that finished-during-downtime on a fresh journal.
    slot.workflowActive = true;
    slot.workflowWatching = false;
    slot.workflowActivatedAt = now;
    slot.workflowSawRunning = true;
    slot.workflowDone = counts.done;
    anyWorkflowTracked = true;
    log("info", `Workflow reconcile: live journal for session ${slot.id} — re-armed (running=${counts.running})`);
    // Publish only an UNAMBIGUOUS count. running > 0 re-seeds a truthful blue.
    // running === 0 on a live tree is indeterminate — the between-phases gap
    // (#70) or a live journal the scan could not read (oversized / racing I/O,
    // counts.unreadableLive) — exactly the states scanAndAnnounceWorkflowActivity
    // refuses to broadcast. Leaving `agents` absent lets the client's
    // preserve-on-absence hold its current value while the armed poll resolves
    // it (running > 0 once the next phase writes, or the explicit zero once the
    // tree goes stale); broadcasting a {running: 0} here would wrongly clear a
    // still-live workflow's blue to green.
    if (counts.running > 0) slot.agents = { running: counts.running, done: counts.done };
    return;
  }
  // Stale tree: the workflow finished or died during the downtime (running and
  // done are both 0 — every dir is stale and skipped from the aggregate).
  // Broadcast the explicit zero so a client latched on a pre-restart blue
  // clears. Nothing live to track, so do not arm. The peak done survives when
  // this slot itself tracked the workflow (an ACP re-register after the
  // completion broadcast, issue #105) so the zero re-announced here agrees
  // with the completion state clients already hold; a restart-fresh slot has
  // no peak and reports 0, exactly as before.
  slot.agents = { running: 0, done: slot.workflowDone ?? 0 };
  // The zero is a broadcastable state, not the end of observation: the
  // downtime may have been a machine sleep the workflow survived. Watch the
  // tree so resumed writes re-arm without a fresh launch signal (issue #108) —
  // and raise the gate, or the poll would never visit the watch.
  slot.workflowWatching = true;
  anyWorkflowTracked = true;
}

// Issue #108: the watch tick for a slot whose stale-clear (or reconcile onto a
// stale tree) already published its zero. Re-stat the tree each poll; the
// moment its newest write is back inside the staleness window, re-arm and
// re-publish exactly as the restart reconcile does — observed by construction,
// running > 0 published, an ambiguous zero left for the armed poll to resolve.
// (running > 0 needs no separate check: running aggregates only over live
// dirs, so any running agent implies a fresh newest write.) A genuinely dead
// tree keeps costing one bounded stat sweep per tick for the slot's lifetime —
// watching ends at session end or prune (the poll's state gate), never with
// silence.
function watchWorkflowActivity(slot, now) {
  const counts = scanWorkflowActivity(slot, now);
  if (!counts) {
    // The tree itself vanished (transcript dir cleaned away): nothing left to
    // watch, and the no-tree idle fast path applies again.
    slot.workflowWatching = false;
    return;
  }
  if (now - counts.latestMtimeMs > WORKFLOW_STALE_MS) return; // still quiet — keep watching
  slot.workflowWatching = false;
  slot.workflowActive = true;
  slot.workflowActivatedAt = now;
  // Observed by construction, same as the restart reconcile: the re-arm
  // happened BECAUSE a live tree was scanned, so once it goes stale again the
  // poll must broadcast the zero rather than give up silently.
  slot.workflowSawRunning = true;
  // Max, never assign: the scan aggregates only LIVE dirs, so a sibling wf_*
  // that finished before the silence — its done folded into the retained peak
  // and already broadcast in the stale-clear zero — re-reads as 0 here.
  // Assigning would clobber the peak and let the next completion zero regress
  // below the state clients latched (the pinned #105 agreement). Maxing is
  // safe: a watch re-arm is always the SAME workflow resuming — a genuinely
  // new one re-enters via markWorkflowActivity, which resets the peak.
  slot.workflowDone = Math.max(counts.done, slot.workflowDone ?? 0);
  log("info", `Workflow watch: tree resumed for session ${slot.id} — re-armed (running=${counts.running})`);
  // Publish only an UNAMBIGUOUS count (the reconcile rule): running === 0 on a
  // live tree is the between-phases gap or an unreadable live journal — the
  // armed poll resolves it while the client's latched zero stands. running > 0
  // always differs from the latched zero, so no change gate is needed here.
  if (counts.running === 0) return;
  slot.agents = { running: counts.running, done: slot.workflowDone };
  pushSseEvent(
    "session",
    sessionEventPayload(slot, { state: "running", agent: slot.agent, cwd: slot.cwd, folderName: slot.folderName }),
    slot.id,
  );
}

// Poll tick (exported so tests can drive it with an injectable `now` instead
// of racing the interval). MUST stay a no-op when nothing is workflow-tracked:
// the boolean gate keeps the idle cost at one comparison, and the per-slot
// flags keep a busy bridge from scanning uninvolved sessions — a session with
// NO workflow tree costs zero syscalls per tick. Armed slots scan-and-announce;
// watching slots (post-stale-clear, issue #108) get the resume check. Ended
// slots shed both flags here — watching ends with the slot's life, not with
// silence.
export function pollWorkflowActivity(now = Date.now()) {
  if (!anyWorkflowTracked) return;
  let stillTracked = false;
  for (const [, slot] of sessions) {
    if (!slot.workflowActive && !slot.workflowWatching) continue;
    if (slot.state !== "running") {
      // An ended slot's indicator is moot; let the poll go quiet for it.
      slot.workflowActive = false;
      slot.workflowWatching = false;
      continue;
    }
    if (slot.workflowActive) scanAndAnnounceWorkflowActivity(slot, now);
    else watchWorkflowActivity(slot, now);
    if (slot.workflowActive || slot.workflowWatching) stillTracked = true;
  }
  anyWorkflowTracked = stillTracked;
}

// unref() so importing this module never keeps the process alive on its own
// (same pattern as the prune interval below).
setInterval(() => pollWorkflowActivity(), WORKFLOW_POLL_MS).unref();

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

  // Bytes out of the PTY are the bridge-owned equivalent of the tool-output
  // hook: work is happening, so the slot is working (issue #60). The guard
  // tests `!== false`, NOT truthiness: a PTY slot is born with no flag at all,
  // and `undefined` is falsy, so a truthy guard left a slot that had never
  // idled unflagged FOREVER. That was invisible while absence meant "working"
  // everywhere — but the connect-time sync now reads an unflagged slot as "no
  // turn signal ever observed" and clients render that grey, so a PTY session
  // mid-long-command greyed out (and restarted its elapsed clock) on every
  // reconnect. Same one-assignment-per-turn cost on this, the hottest path in
  // the bridge: after the first byte the guard is false every time.
  proc.stdout.on("data", (data) => {
    if (!slot.firstOutputSeen) flushReadyWaiters(slot, true);
    if (slot.idle !== false) slot.idle = false;
    pushSseEvent("pty-output", { text: data.toString() }, sessionId);
  });

  proc.stderr.on("data", (data) => {
    if (!slot.firstOutputSeen) flushReadyWaiters(slot, true);
    if (slot.idle !== false) slot.idle = false;
    pushSseEvent("pty-output", { text: data.toString() }, sessionId);
  });

  proc.on("close", (exitCode, signal) => {
    log("info", `Session ${sessionId} (${slot.agent}) PTY exited: code=${exitCode} signal=${signal}`);
    slot.state = "ended";
    slot.endedAt = Date.now();
    slot.endedAuthoritatively = true; // process really exited — never revive
    slot.ptyProcess = null;
    flushReadyWaiters(slot, false);
    runSessionCleanupHooks(sessionId, "pty-closed");
    pushSseEvent("session", sessionEventPayload(slot, { state: "ended", exitCode, signal, agent: slot.agent, folderName: slot.folderName }), sessionId);
  });

  proc.on("error", (err) => {
    log("error", `Session ${sessionId} PTY spawn error: ${err.message}`);
    slot.state = "ended";
    slot.endedAt = Date.now();
    slot.endedAuthoritatively = true; // spawn/exec failure is terminal — never revive
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

  // PTY slots have a real cwd too: derive git metadata once at creation so
  // the initial running event (built via sessionEventPayload, which folds the
  // additive fields) already carries it.
  refreshGitMetadata(slot);
  pushSseEvent("session", sessionEventPayload(slot, { state: "running", agent, cwd, folderName }), sessionId);

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
  // Re-attach is an opportunistic metadata moment: the slot may have sat
  // PTY-less for a while, so its git branch can be stale.
  announceGitMetadataRefresh(slot);
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
  // Deliberately NOT endedAuthoritatively: killing a slot the bridge owns no
  // process for (a Codex-scanner slot) only marks it ended — the watch cannot
  // actually stop the process, so the still-alive session's next observed
  // write must be able to revive it (issue #53, the scanner's touch path). A
  // PTY slot's real end IS authoritative, but that is set by the PTY close
  // handler, not here.
  pushSseEvent("session", sessionEventPayload(slot, { state: "ended", agent: slot.agent, folderName: slot.folderName, killed: true }), sessionId);
  log("info", `Session ${sessionId} killed`);
  return true;
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
    // Additive git metadata (issue #54): branch once derivable at the root;
    // worktree/repoRoot ONLY for a verified linked worktree. Mirrors
    // sessionEventPayload.
    ...(s.branch ? { branch: s.branch } : {}),
    ...(s.worktree ? { worktree: true } : {}),
    ...(s.repoRoot ? { repoRoot: s.repoRoot } : {}),
    // Additive workflow activity (issue #55): present once observed; the
    // completion state is the explicit {running: 0, done: N}.
    ...(s.agents ? { agents: s.agents } : {}),
    // Additive: present (=true) for ACP slots (Zed's process, not ours);
    // omitted for bridge-owned PTY slots (clients treat absent as
    // external=false). Kept in lockstep with sessionEventPayload's SSE tag.
    ...(s.kind === "acp" ? { external: true } : {}),
    // Additive session-type discriminator + DERIVED dictatable flag (S3 #77 /
    // S4 #78), in lockstep with sessionEventPayload (see the rationale there).
    ...(s.kind ? { kind: s.kind } : {}),
    ...(s.state !== "ended" && (s.ptyProcess || s.kind === "acp") ? { dictatable: true } : {}),
    // Additive turn-end flag (issue #60): present (=true) when the slot's last
    // lifecycle signal was a Stop/TaskComplete. Same lockstep obligation — a
    // REST snapshot that disagreed with the SSE snapshot about whether a
    // session is working would just relocate the bug.
    ...(s.idle ? { idle: true } : {}),
    // Additive subheading meta (#97), in lockstep with sessionEventPayload —
    // including the number-not-truthiness comparison that keeps a real 0%
    // from vanishing off the REST snapshot.
    ...(s.model ? { model: s.model } : {}),
    ...(s.mode ? { mode: s.mode } : {}),
    ...(typeof s.contextPct === "number" ? { contextPct: s.contextPct } : {}),
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

// --- Zombie ageing (issue #65) ----------------------------------------------
// pruneEndedSessions above only ever considers `ended`, and NOTHING used to
// consider `running` — so a session whose death the bridge never OBSERVED sat
// in the map forever and was re-sent to every client on every connect. The
// original report: a slot whose last observed signal was a turn end at 10:01
// was still being announced as an active session at 20:20, with no owning
// process the bridge could see and no path to ever leave the map.
//
// In the ACP era most deaths ARE observed — an inbox drop ends every session
// bound to that fork (`acp-fork-disconnected`), even on SIGKILL — so the
// surviving zombie class is narrow, and worth naming exactly:
//   * an ACP slot whose connection binding LEAKED — a register (or a watch
//     spawn's early register) that landed after its fork's inbox had already
//     closed, or one that carried no connection at all. The inbox close that
//     would have ended it has already run, so nothing else ever will;
//   * Codex-scanner slots, which have no process handle and no connection:
//     observed only through the rollout files under ~/.codex/sessions.
//
// Liveness evidence beats a timeout wherever it exists (the issue is explicit,
// and #53 is binding: never fabricate an end for a session that may be alive):
//   * a bridge-owned PTY is alive while its process object is — its close
//     handler ends the slot, so the ageing must never race it;
//   * an ACP slot whose fork inbox is live is ALIVE, however long it has been
//     idle. That is the whole answer to "a long-idle session must not be
//     reaped": in the ACP era every real session has a fork holding it open;
//   * a transcript written recently proves SOMETHING is running the session,
//     for the slots no probe can speak for (the issue's own suggestion).
// Only when every one of those comes up empty does the window apply, and even
// then the end is NOT authoritative: it says "no evidence", not "it is dead",
// so a later register or Codex write revives the slot through the same path a
// watch-kill revive uses. And it is an END, broadcast like any other, never a
// silent delete — clients observe the transition instead of watching a row
// vanish.

/** @type {Array<(slot: any) => boolean | null>} */
const livenessProbes = [];

/** Register a liveness probe. Returns `true` (something demonstrably hosts
 *  this slot), `false` (nothing does — a stronger statement than silence), or
 *  `null` for "no opinion", which is what a probe MUST return for slots of a
 *  kind it does not own. Lives here rather than being imported so acp.js can
 *  answer for its own sessions without sessions.js importing it back. */
export function registerSessionLivenessProbe(probe) {
  livenessProbes.push(probe);
}

function probeSessionLiveness(slot) {
  let verdict = null;
  for (const probe of livenessProbes) {
    let answer;
    try { answer = probe(slot); } catch { answer = null; }
    if (answer === true) return true; // any positive proof of life wins outright
    if (answer === false) verdict = false;
  }
  return verdict;
}

/** Note that the bridge just OBSERVED this slot existing — an ACP register, a
 *  turn boundary, a Codex scan. The ageing window below runs from here, so a
 *  session that keeps speaking is never a zombie however quiet its transcript.
 *  Exported for the observation paths outside this module (codex.js). */
export function markSessionObserved(slot) {
  if (slot) slot.observedAt = Date.now();
}

// Newest transcript write, or 0 when there is no readable transcript. Only
// consulted for a slot that has ALREADY gone silent past its window, so the
// stat costs nothing on the common path. Non-regular files are skipped for the
// same reason readFileBounded's callers gate on isFile().
function transcriptMtimeMs(slot) {
  if (typeof slot.transcriptPath !== "string" || !slot.transcriptPath) return 0;
  try {
    const stat = fs.statSync(slot.transcriptPath);
    return stat.isFile() ? stat.mtimeMs : 0;
  } catch {
    return 0;
  }
}

/** End a slot the bridge has no evidence is alive. Deliberately NOT
 *  endedAuthoritatively: this is an absence of evidence, not an observed
 *  death, so the session's next sign of life revives it (issue #53). */
function endUnevidencedSession(slot, reason, now) {
  slot.state = "ended";
  slot.endedAt = now;
  runSessionCleanupHooks(slot.id, reason);
  pushSseEvent(
    "session",
    sessionEventPayload(slot, { state: "ended", agent: slot.agent, folderName: slot.folderName, reason }),
    slot.id,
  );
  log("warn", `Aged out running session ${slot.id} (${slot.agent}, ${slot.folderName}): ${reason}`);
}

// Sweep every running slot. `now` is injectable so tests exercise the windows
// without waiting them out.
export function ageOutZombieSessions(now = Date.now()) {
  for (const [, slot] of sessions) {
    if (slot.state !== "running") continue;
    // Bridge-owned PTY: the process object IS the evidence, and its close
    // handler owns the ending.
    if (slot.ptyProcess) {
      slot.observedAt = now;
      continue;
    }
    const hosted = probeSessionLiveness(slot);
    if (hosted === true) {
      // Alive now: reset the clock, so a fork that dies later is measured from
      // the moment it stopped being observable, not from its last turn.
      slot.observedAt = now;
      continue;
    }
    const observedAt = slot.observedAt ?? slot.createdAt;
    // A probe's `false` is a positive verdict about the HOST, which a file
    // mtime cannot overturn — the same transcript can legitimately be written
    // by the successor slot of a resumed session. Only the no-opinion case
    // falls back to transcript evidence.
    const evidenceAt = hosted === false ? observedAt : Math.max(observedAt, transcriptMtimeMs(slot));
    const grace = hosted === false ? SESSION_UNHOSTED_GRACE_MS : SESSION_SILENT_GRACE_MS;
    if (now - evidenceAt < grace) continue;
    endUnevidencedSession(slot, hosted === false ? "host-gone" : "no-liveness", now);
  }
}

// unref() so importing this module (e.g. from an in-process unit test) never
// keeps the process alive on its own. Ageing runs BEFORE the prune so a slot it
// ends still waits out the full grace period in `ended` — clients get their
// usual window to observe the transition.
setInterval(() => {
  ageOutZombieSessions();
  pruneEndedSessions();
}, SESSION_PRUNE_INTERVAL_MS).unref();

// --- ACP sessions (watch dictation via the Zed adapter, S3 #77) --------------
// An ACP session is hosted by the forked claude-agent-acp launched by Zed, not
// by a PTY the bridge owns. The fork announces each session over the loopback
// channel (see acp.js). Represented with kind "acp" + dictatable + external
// (`external` is derived from `kind === "acp"` — see sessionEventPayload /
// getSessionsSnapshot). This channel is the SOLE way a claude session reaches
// the bridge: the settings.json hook observation it once twinned with was
// retired repo-wide (#87, Zed-only product).

// The workflow-activity scanner reads slot.transcriptPath, but the ACP channel
// has no transcript field on any wire — so an ACP slot had nothing to scan and
// the DELEGATED indicator could never arm (issue #105). Derive the path
// instead, from the cwd + SDK session id the registration already holds.
//
// CONVENTION-COUPLED: mirrors the CLI's projects-dir layout,
//   <CLAUDE_PROJECTS_ROOT>/<cwd with [^a-zA-Z0-9] → "-">/<session_id>.jsonl
// Sanitization char class, verified two ways on this machine: the bundled CLI
// computes the projects dir name as `cwd.replace(/[^a-zA-Z0-9]/g, "-")`
// (paths over 200 chars are further truncated and hash-suffixed), and real
// dirs under ~/.claude/projects/ agree, e.g.
//   /home/deck/Development/claude-watch → -home-deck-Development-claude-watch.
// The over-200-chars hole is inherited: such a cwd's derived path misses and
// the indicator simply stays unfed for that session. The id gate keeps a
// foreign registration body from planting path separators (or a bare "..")
// inside the filename; a real SDK uuid always passes, and an id that does not
// just means no derivation — the slot behaves exactly as before this feature.
const PROJECT_DIR_SANITIZE_RE = /[^a-zA-Z0-9]/g;

function sanitizeProjectPath(p) {
  return p.replace(PROJECT_DIR_SANITIZE_RE, "-");
}

function deriveAcpTranscriptPath(cwd, sdkSessionId) {
  if (typeof cwd !== "string" || !path.isAbsolute(cwd)) return null;
  if (typeof sdkSessionId !== "string" || !/^[A-Za-z0-9_-]+$/.test(sdkSessionId)) return null;
  return path.join(CLAUDE_PROJECTS_ROOT, sanitizeProjectPath(path.resolve(cwd)), `${sdkSessionId}.jsonl`);
}

/** Register (or idempotently refresh) an ACP session. `sdkSessionId` is the
 *  SDK's underlying session_id, used to derive the transcript path; in this
 *  fork it equals `sessionId`, but it is passed explicitly so the derivation
 *  is correct even if that ever diverges. Returns the slot. */
export function registerAcpSession({ sessionId, sdkSessionId, cwd, active, title, detached, model, mode, contextPct }) {
  const boundSdkId = sdkSessionId || sessionId;
  const resolvedCwd = cwd || CLI_CWD || process.env.HOME || process.cwd();
  const folderName = path.basename(resolvedCwd) || resolvedCwd;

  let slot = sessions.get(sessionId);
  if (slot) {
    // Idempotent re-register (resume/load, or a reconnect): refresh + revive.
    slot.agent = "claude";
    slot.kind = "acp";
    slot.ptyProcess = null;
    slot.cwd = resolvedCwd;
    slot.folderName = folderName;
    slot.state = "running";
    // `active` is NOT applied to a slot we already track. The initial register
    // races the turn that starts right after it, and a stale `active: false`
    // landing after the turn-start boundary flips a working session back to
    // idle — an idle-looking watch mid-turn. Turn boundaries are the authority
    // for a slot the bridge has been following; `active` only seeds a slot the
    // bridge is meeting for the first time (below), which is the bridge-restart
    // case it exists for.
    //
    // `idle` is deliberately NOT reset either. Re-registration is a re-ANNOUNCEMENT —
    // a Zed restart, a session resume, a fork reconnect — not new work. Clearing
    // it here told the wrist a session was working whenever the user restarted
    // Zed, even though nothing had started; the flag only moves on a real turn
    // boundary (`kind: "turn"`), which is the sole authority for it.
    if (typeof title === "string" && title && !slot.title) slot.title = title;
    // Subheading meta (#97). Model/mode apply on a re-register: the fork's
    // noteSessionMeta keeps its replay copy current, so these are never staler
    // than what the slot holds. `contextPct` deliberately does NOT — it is the
    // one meta value the replay carries at its REGISTRATION-TIME reading (the
    // adapter refreshes model/mode only), so on a slot the bridge has been
    // following it would rewind a percent the teed usage_updates had advanced.
    // It only seeds a first-met slot (below), the bridge-restart case, same as
    // `active`.
    if (typeof model === "string" && model) slot.model = model;
    if (typeof mode === "string" && mode) slot.mode = mode;
    slot.endedAt = undefined;
    slot.endedAuthoritatively = false;
    // Watch-spawned pickup state, driven entirely by what the fork announces:
    // a register WITH the flag (spawn, or the replay after a bridge restart)
    // marks it; one WITHOUT (adoption's re-register, or any normal session)
    // clears it — so the wrist-only permission policy ends the moment a Zed
    // thread owns the session.
    slot.detached = detached === true;
  } else {
    slot = {
      id: sessionId,
      agent: "claude",
      cwd: resolvedCwd,
      folderName,
      ptyProcess: null,
      state: "running",
      createdAt: Date.now(),
      kind: "acp",
      // A slot rebuilt by a re-announce (bridge restart) must not claim to be
      // working just because it is new: the fork tells us whether a turn is
      // actually in flight. Absent (older fork) keeps the previous behaviour.
      ...(typeof active === "boolean" ? { idle: !active } : {}),
      // Carried on the re-announce so a bridge restart restores the title
      // immediately: the adapter only pushes session_info_update when the title
      // CHANGES, so without this the watch shows the raw uuid until the next
      // turn ends.
      ...(typeof title === "string" && title ? { title, titleIsAi: true } : {}),
      // Watch-spawned, no editor thread yet (see the refresh branch above).
      ...(detached === true ? { detached: true } : {}),
      // Subheading meta (#97), seeded from the register body so a fresh slot
      // (and a bridge restart's rebuilt one) has the fields before any teed
      // update arrives. Integer-guarded: the percent is bridge-computed
      // (contextPctOf in acp.js) but a foreign caller must not plant NaN.
      ...(typeof model === "string" && model ? { model } : {}),
      ...(typeof mode === "string" && mode ? { mode } : {}),
      ...(Number.isInteger(contextPct) ? { contextPct } : {}),
    };
    sessions.set(sessionId, slot);
  }
  // A register (first, or the fork's replay after a restart) is the bridge
  // observing the session; the zombie ageing measures silence from here.
  markSessionObserved(slot);
  // Feed the workflow scanner (issue #105). Derivation only fills an EMPTY
  // slot: the transcript's location is fixed by the session's BIRTH cwd, so
  // the first register's derivation stays correct even if a later re-announce
  // ever named a different directory. Only the caller's explicit cwd may feed
  // it: the spawn-result early-register can race in cwd-less, and locking a
  // path derived from the fallback chain would leave a guess-on-a-guess that
  // the fork's real register (which does carry the cwd, and lands here
  // moments later) could no longer correct.
  //
  // Reconciliation then runs the #68 restart semantics on EVERY register,
  // because both restart shapes land here: a bridge restart re-meets the
  // session as a fresh slot, and a fork restart (Zed relaunch) re-registers a
  // surviving slot whose poll went quiet when the inbox drop ended it —
  // either way a workflow still on disk must re-arm the scan, and a finished
  // one must broadcast its explicit zero instead of stranding a stale blue.
  // An armed slot is untouched (reconcile's first guard), so the common live
  // re-register stays a no-op.
  if (!slot.transcriptPath) {
    const derived = deriveAcpTranscriptPath(cwd, boundSdkId);
    if (derived) slot.transcriptPath = derived;
  }
  reconcileWorkflowActivity(slot);
  refreshGitMetadata(slot);
  log("info", `Registered ACP session ${sessionId} (${folderName})`);
  pushSseEvent(
    "session",
    sessionEventPayload(slot, { state: "running", agent: "claude", cwd: resolvedCwd, folderName }),
    sessionId,
  );
  return slot;
}

/** End an ACP session (the fork's query closed / Zed quit / connection dropped).
 *  Authoritative — a stray later observation must not revive it. Returns the
 *  slot id or null if unknown / already ended. */
export function endAcpSession(sessionId, reason = "acp-closed") {
  const slot = sessions.get(sessionId);
  if (!slot || slot.state === "ended") return slot?.id ?? null;
  slot.state = "ended";
  slot.endedAt = Date.now();
  slot.endedAuthoritatively = true;
  runSessionCleanupHooks(slot.id, reason);
  pushSseEvent(
    "session",
    sessionEventPayload(slot, { state: "ended", agent: slot.agent, folderName: slot.folderName, reason }),
    slot.id,
  );
  log("info", `ACP session ${slot.id} ended (${reason})`);
  return slot.id;
}

// --- Authoritative connect-time session sync (issue #66) --------------------
// Send current sessions state so late-connecting SSE clients see existing
// sessions (runs on every GET /events connect).
//
// The per-slot re-send is ADDITIVE: it can create or refresh a session, but it
// has no way to say "drop everything I did not mention". So a session the
// bridge FORGOT — a restart wiping the in-memory map, a crash — could never
// be retracted, and
// every connected client held that ghost forever: green, labelled running, for
// a process that had not existed since the day before. Force-stopping the app
// was the only cure, because that discards the client's state wholesale. A
// bridge that has forgotten a session is definitionally unable to emit its
// `ended` event, so no amount of bridge-side ageing (issue #65) can reach this
// class — only an authoritative set can.
//
// So the sync CLOSES with the whole truth: one framed event listing every
// running slot. Clients drop what it does not list — the same doctrine as
// #63's permission-sync, one lane up: any sync that claims to describe current
// state is authoritative about absence.
//
// Ordering is the mirror image of permission-sync's, for the same no-flicker
// reason read the other way. Permissions retract FIRST because their re-sends
// restore the payloads; sessions retract LAST because the re-sends ARE the
// payloads, so refreshing before pruning means no row ever blinks out and back.
// It also makes an interrupted sync harmless BY CONSTRUCTION: a client whose
// connection dies mid-snapshot never receives the closing frame, so it never
// prunes against a half-told story. (The frame is one SSE event, so it also
// cannot arrive half-parsed — a truncated frame is simply not delivered.)
//
// `complete` is the claim the pruning rests on. This bridge enumerates one
// in-memory map, so it can always make it; a future sync that describes only
// PART of the session set (a paged snapshot, a relay forwarding one bridge of
// several) must omit it, and clients then treat the frame as informational —
// never as a licence to drop what it did not mention.
//
// Each entry also carries the slot's turn-level truth as a TRI-STATE (issue
// #60), which the `session` payload's `idle` deliberately cannot: on a session
// event the flag is present-only-when-true, so "working" and "the bridge has
// no idea" are the same absence there, and a client meeting the session for
// the first time has to guess — which is how a session idle for three hours
// rendered green on a freshly-paired watch. In a SYNC the two are told apart:
//   idle: true   — the last lifecycle signal was a turn end
//   idle: false  — a turn is in flight
//   omitted      — no turn signal has ever been observed for this slot
// A sync is a description of current state, so it says all three out loud
// rather than leaning on a merge rule. (The per-session payload's flag is
// unchanged: it stays the one-way latch every live event uses.)
registerSseSyncProvider(function* runningSessionsSync() {
  const listed = [];
  for (const [sid, slot] of sessions) {
    if (slot.state !== "running") continue;
    listed.push({ id: sid, ...(typeof slot.idle === "boolean" ? { idle: slot.idle } : {}) });
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
  yield { event: "session-sync", data: JSON.stringify({ sessions: listed, complete: true }) };
});
