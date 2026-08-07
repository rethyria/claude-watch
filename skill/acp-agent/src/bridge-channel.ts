// claude-watch: the fork <-> bridge loopback channel (S3 #77).
//
// This whole file is a claude-watch addition — it is NOT upstream
// `claude-agent-acp` code, so re-pulling the fork from upstream only reconciles
// the handful of marked injection points in acp-agent.ts / index.ts, never this
// module.
//
// Direction of travel:
//   fork -> bridge   register / update / deregister  (plain fire-and-forget POSTs)
//   bridge -> fork   dictation `inject`              (a long-lived SSE the fork holds)
//
// The uplink is stateless POSTs; the downlink is one persistent SSE "inbox" the
// fork opens to the bridge. That connection's liveness IS this fork process's
// liveness: when the fork dies (even on SIGKILL, where the graceful
// `deregister` never runs) the inbox socket closes and the bridge ends every
// ACP slot bound to it — so a Zed quit strands no zombie slot.
//
// Everything here is strictly best-effort. The bridge being down, slow, or
// absent must NEVER change how the ACP session behaves for the Zed user: every
// call swallows its own errors and the SSE reader reconnects on its own.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import type { AcpClient, Logger } from "./acp-agent.js";
import type {
  RequestPermissionRequest,
  RequestPermissionResponse,
  SessionNotification,
  ReadTextFileRequest,
  ReadTextFileResponse,
  WriteTextFileRequest,
  WriteTextFileResponse,
  CreateElicitationRequest,
  CreateElicitationResponse,
  CompleteElicitationNotification,
} from "@agentclientprotocol/sdk";

/** Delivered by the bridge down the inbox SSE when the watch dictates. */
export type InjectHandler = (sessionId: string, text: string, source: string) => void;

/** The wrist's answer to a permission request, arriving down the same inbox as
 *  dictation. `optionId` is one the AGENT offered — the bridge echoes our own
 *  vocabulary back rather than inventing one from the behavior. */
export type PermissionDecision = {
  sessionId: string;
  toolCallId: string;
  optionId: string;
  behavior: string;
};
export type PermissionDecisionHandler = (decision: PermissionDecision) => void;

/** The wrist's answers to an AskUserQuestion input-request (#111), arriving
 *  down the same inbox as permission decisions. `answers` is POSITIONAL — one
 *  entry per question, in the order the agent raised them; `null` marks a
 *  question the wrist skipped. Positional by contract: the agent re-keys by
 *  question text against the very list it raised, so duplicate question texts
 *  must stay distinct all the way to that final fold. */
export type InputDecision = {
  sessionId: string;
  toolCallId: string;
  answers: (string | null)[];
};
export type InputDecisionHandler = (decision: InputDecision) => void;

/** A watch "new session" the bridge routed to this fork (the third inbox frame
 *  type). The fork creates a detached session for `cwd` and answers with
 *  {@link BridgeChannel.reportSpawnResult}, echoing the `requestId`. */
export type SpawnRequest = { requestId: string; cwd: string; agent: string };
export type SpawnHandler = (request: SpawnRequest) => void;

/** The wrist ended a session (#88). The fork tears it down for real — there is
 *  no separate ack frame, because the teardown's own `deregister` IS the ack:
 *  the bridge reports the ending it observes, so a fork that cannot honour the
 *  close can never be mistaken for one that did. */
export type CloseRequest = { sessionId: string; reason: string };
export type CloseHandler = (request: CloseRequest) => void;

/** The seam the agent (and its client tee) talk to. A test supplies a fake;
 *  production uses {@link HttpBridgeChannel}. Every method is best-effort and
 *  MUST NOT throw into agent code. */
export interface BridgeChannel {
  /** Register a live ACP session so the watch can see and dictate at it. The
   *  SDK session_id equals the ACP session id (the fork passes
   *  `options.sessionId = sessionId`), so one id correlates the ACP slot with
   *  the settings.json hook events the SDK also fires — the bridge binds them
   *  to a single slot. */
  registerSession(info: {
    sessionId: string;
    sdkSessionId: string;
    cwd: string;
    /** Known thread title, so a bridge restart restores it from the
     *  re-announce instead of showing the raw uuid until the next turn end. */
    title?: string;
    /** The session was spawned from the watch and no editor thread exists for
     *  it yet. Cleared by re-registering WITHOUT the flag when a Zed thread
     *  adopts it (desk pickup / session load). Rides the replay too, so a
     *  bridge restart keeps knowing which sessions are pickup candidates. */
    detached?: boolean;
    /** Halo v2 (#97): the wrist subheading's `model · mode · use%`. The model
     *  is the human DISPLAY name (default-alias already resolved by the agent
     *  — the bridge has no model list to resolve against); the mode is the ACP
     *  permission-mode id verbatim; the context pair is TOKENS (used + window
     *  size), from which the bridge derives its integer percent. All mid-
     *  session changes reach the bridge through the client tee — these only
     *  seed the slot and ride the restart replay. */
    model?: string;
    mode?: string;
    contextUsed?: number;
    contextSize?: number;
  }): void;
  /** The ACP session ended (query closed / closeSession / dispose). */
  deregisterSession(sessionId: string, reason: string): void;
  /** Atomically take the newest unclaimed watch-spawned session for `cwd` from
   *  the bridge's pickup registry, or resolve `null` when there is none (or the
   *  bridge is down/slow — a New Thread must never hang on this). At most one
   *  caller wins a given session; the bridge clears the entry on claim. */
  takePendingPickup(cwd: string): Promise<string | null>;
  /** Mirror of a client `sessionUpdate` (prose, tool calls, mode, plan, …). */
  forwardSessionUpdate(params: SessionNotification): void;
  /** Mirror of a client `requestPermission` RPC (missed by `sendUpdate`). */
  forwardPermissionRequest(params: RequestPermissionRequest): void;
  /** Turn boundary. The ACP `sessionUpdate` union has no turn-end variant —
   *  turn end is the `session/prompt` RPC's `stopReason`, a return value on the
   *  agent→client path that never reaches the client tee. Without this the
   *  bridge can infer "working" from activity but can never observe idle, so it
   *  would have to guess from silence. Every settle lane reports its
   *  `stopReason`, so a cancelled/refused turn idles the slot like a completed
   *  one. */
  forwardTurnBoundary(params: {
    sessionId: string;
    phase: "start" | "end";
    stopReason?: string;
  }): void;
  /** Tell the bridge a permission request was settled somewhere else (the user
   *  answered in Zed, or the agent cancelled it), so it can retract the wrist
   *  card instead of leaving a zombie prompt (#80). */
  forwardPermissionResolved(params: { sessionId: string; toolCallId: string }): void;
  /** Raise an AskUserQuestion form elicitation on the wrist (#111). An
   *  elicitation is a client-bound REQUEST — the client tee mirrors only
   *  notifications and the requestPermission RPC — so without this explicit
   *  raise the bridge never hears the question at all. `questions` is the
   *  tool's validated question list verbatim; the bridge reshapes it into the
   *  hook-era question-card wire shape the watch already renders. */
  forwardInputRequest(params: {
    sessionId: string;
    toolCallId: string;
    questions: unknown[];
  }): void;
  /** The elicitation settled somewhere else (Zed answered, the turn was
   *  cancelled, or the client failed) — retract the wrist's question card.
   *  The forwardPermissionResolved twin for the input lane (#111). */
  forwardInputResolved(params: { sessionId: string; toolCallId: string }): void;
  /** Remember a freshly-learned thread title for the next re-announce (#79). */
  noteSessionTitle(sessionId: string, title: string): void;
  /** Remember a mid-session model/mode change for the next re-announce (#97,
   *  the noteSessionTitle pattern). The LIVE bridge already learns these from
   *  the teed `config_option_update`/`current_mode_update`; this only keeps
   *  the restart replay from re-announcing the values the session was born
   *  with. The context pair is deliberately NOT noted: a replayed percent one
   *  turn stale is corrected by the next teed `usage_update`, whereas a wrong
   *  model/mode would sit until the user changes it again. */
  noteSessionMeta(sessionId: string, meta: { model?: string; mode?: string }): void;
  /** Register the handler the inbox calls when the watch dictates. */
  onInject(handler: InjectHandler): void;
  /** Register the handler the inbox calls when the watch answers a permission
   *  request (#80). */
  onPermissionDecision(handler: PermissionDecisionHandler): void;
  /** Register the handler the inbox calls when the watch answers an
   *  AskUserQuestion input-request (#111). */
  onInputDecision(handler: InputDecisionHandler): void;
  /** Register the handler the inbox calls when the watch asks this fork to
   *  create a session (the born-in-Zed spawn). */
  onSpawn(handler: SpawnHandler): void;
  /** Register the handler the inbox calls when the watch KILLS a session
   *  (#88) — a real teardown, never a hide. */
  onClose(handler: CloseHandler): void;
  /** Answer a spawn frame: the explicit ack the bridge correlates by
   *  `requestId` (never piggybacked on register — a createSession throw must
   *  surface immediately, and register replay must not re-trigger
   *  correlation). Best-effort like every uplink POST. */
  reportSpawnResult(result: {
    requestId: string;
    ok: boolean;
    sessionId?: string;
    cwd?: string;
    error?: string;
  }): void;
  /** Open the inbox SSE and begin its reconnect loop. */
  start(): void;
  /** Stop the inbox loop and release the connection. */
  stop(): void;
}

function credentialsDir(): string {
  return process.env.CLAUDE_WATCH_CREDENTIALS_DIR || path.join(os.homedir(), ".claude-watch");
}

/** The bridge publishes its ACTUAL bound port here on startup (it walks a port
 *  range because 7860 is often taken), so we must read it rather than assume a
 *  port. Re-read on every (re)connect: a bridge restart can land on a new port. */
function readBridgePort(): number | null {
  try {
    const raw = fs.readFileSync(path.join(credentialsDir(), "port"), "utf8").trim();
    const port = Number.parseInt(raw, 10);
    return Number.isInteger(port) && port > 0 ? port : null;
  } catch {
    return null;
  }
}

const INBOX_MIN_BACKOFF_MS = 500;
const INBOX_MAX_BACKOFF_MS = 10_000;

export class HttpBridgeChannel implements BridgeChannel {
  /** Stable id for this fork process across all its POSTs and its inbox SSE, so
   *  the bridge knows which sessions belong to which fork (and which inbox to
   *  push a dictation down). */
  private readonly connectionId = randomUUID();
  private injectHandler: InjectHandler | null = null;
  private permissionHandler: PermissionDecisionHandler | null = null;
  private inputHandler: InputDecisionHandler | null = null;
  private spawnHandler: SpawnHandler | null = null;
  private closeHandler: CloseHandler | null = null;
  private stopped = false;
  private abort: AbortController | null = null;

  /** Every session registered and not yet deregistered, kept so they can be
   *  re-announced after the bridge comes back. registerSession fires exactly
   *  once per session (at creation), so without this a bridge restart silently
   *  orphans every live Zed thread: the inbox SSE reconnects and everything
   *  LOOKS healthy, but the bridge has no slot, dictation 502s, and the thread
   *  is invisible until the user closes and reopens it in Zed. */
  private readonly liveSessions = new Map<
    string,
    {
      sessionId: string;
      sdkSessionId: string;
      cwd: string;
      /** The bridge has accepted a register for this session on this connection. */
      acked: boolean;
      /** Last known thread title, refreshed as the SDK generates one, so a
       *  re-announce after a bridge restart carries it. */
      title?: string;
      /** Watch-spawned, no editor thread yet (see the interface doc). Kept here
       *  so a replay after a bridge restart re-announces the truth — a restarted
       *  bridge that lost its pickup registry relearns it from this flag. */
      detached?: boolean;
      /** Subheading meta (#97). Model/mode are refreshed by noteSessionMeta as
       *  they change, so a replay re-announces the CURRENT values; the context
       *  pair stays as registered (see the interface doc for why). */
      model?: string;
      mode?: string;
      contextUsed?: number;
      contextSize?: number;
      /** Whether a turn is in flight for this session. Tracked here because a
       *  bridge restart rebuilds its table from the re-announce alone: without
       *  it the bridge has to guess, and guessing "working" shows a live-looking
       *  session on the wrist for a thread that is sitting idle. Every boundary
       *  already passes through forwardTurnBoundary, so this costs nothing. */
      active: boolean;
      /** A register POST for it is outstanding RIGHT NOW. Distinct from !acked:
       *  start() and registerSession() race, so the inbox can come up while the
       *  very first register is still on the wire. Replaying then would send a
       *  second, duplicate register for a session that was never missing. */
      inFlight: boolean;
    }
  >();

  /** Whether the inbox has ever been up. Distinguishes "first connect" (each
   *  session's own register POST is authoritative) from "reconnect" (the bridge
   *  may be a fresh process with an empty table, so everything must be re-sent). */
  private connectedOnce = false;

  constructor(private readonly logger: Logger) {}

  onInject(handler: InjectHandler): void {
    this.injectHandler = handler;
  }

  onPermissionDecision(handler: PermissionDecisionHandler): void {
    this.permissionHandler = handler;
  }

  onInputDecision(handler: InputDecisionHandler): void {
    this.inputHandler = handler;
  }

  onSpawn(handler: SpawnHandler): void {
    this.spawnHandler = handler;
  }

  onClose(handler: CloseHandler): void {
    this.closeHandler = handler;
  }

  reportSpawnResult(result: {
    requestId: string;
    ok: boolean;
    sessionId?: string;
    cwd?: string;
    error?: string;
  }): void {
    void this.post("/acp/spawn-result", {
      connection: this.connectionId,
      ...result,
    });
  }

  registerSession(info: {
    sessionId: string;
    sdkSessionId: string;
    cwd: string;
    title?: string;
    detached?: boolean;
    model?: string;
    mode?: string;
    contextUsed?: number;
    contextSize?: number;
  }): void {
    // A session that has never run a turn is not working — the honest default,
    // and the one the wrist should show for a thread just opened in Zed.
    // An attach-time re-register (same key, no `detached`) deliberately
    // replaces the old entry, clearing the flag for future replays; the
    // title/active reset it also causes is benign — the bridge keeps its own
    // slot state, the next turn boundary refreshes `active`, and the next
    // title change re-notes `title`.
    const entry = { ...info, acked: false, inFlight: true, active: false };
    this.liveSessions.set(info.sessionId, entry);
    void this.post("/acp/register", {
      connection: this.connectionId,
      sessionId: info.sessionId,
      sdkSessionId: info.sdkSessionId,
      cwd: info.cwd,
      active: entry.active,
      title: entry.title,
      model: entry.model,
      mode: entry.mode,
      contextUsed: entry.contextUsed,
      contextSize: entry.contextSize,
      ...(entry.detached ? { detached: true } : {}),
    }).then((ok) => {
      // Only a POST the bridge actually accepted counts. One that failed (bridge
      // still starting, no port file yet) leaves acked=false so the next inbox
      // connect re-sends it — that is the "Zed opened before the bridge" case.
      if (this.liveSessions.get(info.sessionId) !== entry) return; // deregistered meanwhile
      entry.inFlight = false;
      entry.acked = ok;
    });
  }

  deregisterSession(sessionId: string, reason: string): void {
    this.liveSessions.delete(sessionId);
    void this.post("/acp/deregister", {
      connection: this.connectionId,
      sessionId,
      reason,
    });
  }

  /** Ask the bridge for (and atomically claim) the newest unclaimed
   *  watch-spawned session for `cwd`. Bounded hard at 1s: this sits on the
   *  session/new path, and a down/slow bridge must degrade to "no pickup",
   *  never to a hung New Thread. */
  async takePendingPickup(cwd: string): Promise<string | null> {
    const port = readBridgePort();
    if (port === null) return null;
    try {
      const resp = await fetch(`http://127.0.0.1:${port}/acp/claim`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ connection: this.connectionId, cwd }),
        signal: AbortSignal.timeout(1000),
      });
      if (!resp.ok) return null;
      const body = (await resp.json()) as { sessionId?: unknown };
      return typeof body.sessionId === "string" && body.sessionId ? body.sessionId : null;
    } catch {
      return null;
    }
  }

  forwardSessionUpdate(params: SessionNotification): void {
    void this.post("/acp/update", {
      connection: this.connectionId,
      sessionId: params.sessionId,
      kind: "session_update",
      payload: params,
    });
  }

  forwardPermissionRequest(params: RequestPermissionRequest): void {
    void this.post("/acp/update", {
      connection: this.connectionId,
      sessionId: params.sessionId,
      kind: "permission",
      payload: params,
    });
  }

  /** Remember a title the agent just learned, so the next re-announce carries
   *  it. Cheap: the agent already computes this for its own client update. */
  noteSessionTitle(sessionId: string, title: string): void {
    const live = this.liveSessions.get(sessionId);
    if (live) live.title = title;
  }

  /** Remember a model/mode change for the next re-announce (#97) — the
   *  noteSessionTitle pattern; the agent already computed the display name for
   *  its own client update. Partial on purpose: an absent key preserves the
   *  other value, so a mode flip never clobbers the model (or vice versa). */
  noteSessionMeta(sessionId: string, meta: { model?: string; mode?: string }): void {
    const live = this.liveSessions.get(sessionId);
    if (!live) return;
    if (meta.model !== undefined) live.model = meta.model;
    if (meta.mode !== undefined) live.mode = meta.mode;
  }

  forwardPermissionResolved(params: { sessionId: string; toolCallId: string }): void {
    void this.post("/acp/update", {
      connection: this.connectionId,
      sessionId: params.sessionId,
      kind: "permission-resolved",
      payload: { sessionId: params.sessionId, toolCallId: params.toolCallId },
    });
  }

  forwardInputRequest(params: {
    sessionId: string;
    toolCallId: string;
    questions: unknown[];
  }): void {
    void this.post("/acp/update", {
      connection: this.connectionId,
      sessionId: params.sessionId,
      kind: "input-request",
      payload: params,
    });
  }

  forwardInputResolved(params: { sessionId: string; toolCallId: string }): void {
    void this.post("/acp/update", {
      connection: this.connectionId,
      sessionId: params.sessionId,
      kind: "input-resolved",
      payload: { sessionId: params.sessionId, toolCallId: params.toolCallId },
    });
  }

  forwardTurnBoundary(params: {
    sessionId: string;
    phase: "start" | "end";
    stopReason?: string;
  }): void {
    const live = this.liveSessions.get(params.sessionId);
    if (live) live.active = params.phase === "start";
    void this.post("/acp/update", {
      connection: this.connectionId,
      sessionId: params.sessionId,
      kind: "turn",
      payload: { phase: params.phase, ...(params.stopReason && { stopReason: params.stopReason }) },
    });
  }

  start(): void {
    if (this.stopped) return;
    void this.runInbox();
  }

  stop(): void {
    this.stopped = true;
    try {
      this.abort?.abort();
    } catch {
      /* already gone */
    }
  }

  /** Fire-and-forget POST to the bridge on loopback. Never rejects into caller
   *  code: a missing port (bridge not up) or a network error is swallowed. */
  private async post(route: string, body: Record<string, unknown>): Promise<boolean> {
    const port = readBridgePort();
    if (port === null) return false;
    try {
      const resp = await fetch(`http://127.0.0.1:${port}${route}`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      });
      return resp.ok;
    } catch (err) {
      this.logger.error(`claude-watch: bridge POST ${route} failed: ${String(err)}`);
      return false;
    }
  }

  /** Re-POST /acp/register for every still-live session. Called on each inbox
   *  (re)connect so a bridge that restarted learns about threads that were
   *  created while it was down. Snapshotted first: a deregister landing
   *  mid-replay must not mutate the map we are iterating. */
  private async replayRegistrations(): Promise<void> {
    const pending = [...this.liveSessions.values()].filter((s) => !s.acked && !s.inFlight);
    if (pending.length === 0) return;
    this.logger.error(
      `claude-watch: re-announcing ${pending.length} live session(s) to the bridge`,
    );
    await Promise.all(
      pending.map((info) =>
        this.post("/acp/register", {
          connection: this.connectionId,
          sessionId: info.sessionId,
          sdkSessionId: info.sdkSessionId,
          cwd: info.cwd,
          active: info.active,
          title: info.title,
          model: info.model,
          mode: info.mode,
          contextUsed: info.contextUsed,
          contextSize: info.contextSize,
          ...(info.detached ? { detached: true } : {}),
        }).then((ok) => {
          if (ok && this.liveSessions.get(info.sessionId) === info) info.acked = true;
        }),
      ),
    );
  }

  /** Hold the inbox SSE open, reconnecting with capped backoff until stopped. */
  private async runInbox(): Promise<void> {
    let backoff = INBOX_MIN_BACKOFF_MS;
    while (!this.stopped) {
      const port = readBridgePort();
      if (port === null) {
        await sleep(backoff);
        backoff = Math.min(backoff * 2, INBOX_MAX_BACKOFF_MS);
        continue;
      }
      this.abort = new AbortController();
      try {
        const resp = await fetch(
          `http://127.0.0.1:${port}/acp/inbox?connection=${this.connectionId}`,
          {
            headers: { accept: "text/event-stream" },
            signal: this.abort.signal,
          },
        );
        if (!resp.ok || !resp.body) {
          throw new Error(`inbox status ${resp.status}`);
        }
        // Connected: reset backoff and drain frames until the stream ends.
        backoff = INBOX_MIN_BACKOFF_MS;
        // A RECONNECT means the bridge we are now talking to may be a fresh
        // process with an empty session table, so nothing we sent earlier can be
        // assumed to have survived — invalidate every ack and re-announce.
        // registerSession only ever fires at session creation, so this is the
        // one thing that puts an already-open Zed thread back on the bridge.
        // On the FIRST connect we skip the invalidation: each session's own
        // register POST is authoritative, and replaying it would just duplicate
        // the POST. Sessions whose register failed (bridge not up yet) still
        // have acked=false and get sent either way.
        if (this.connectedOnce) {
          for (const s of this.liveSessions.values()) {
            s.acked = false;
            // Any POST still outstanding across a reconnect was aimed at the
            // bridge that just went away, so it cannot be trusted to land.
            // Clearing it lets the replay cover that session; a duplicate
            // register is harmless (handleAcpRegister refreshes in place).
            s.inFlight = false;
          }
        }
        this.connectedOnce = true;
        // Not awaited: a slow re-register must not delay reading inject frames.
        void this.replayRegistrations();
        await this.drainInbox(resp.body);
      } catch (err) {
        if (this.stopped) break;
        this.logger.error(`claude-watch: bridge inbox disconnected: ${String(err)}`);
      }
      if (this.stopped) break;
      await sleep(backoff);
      backoff = Math.min(backoff * 2, INBOX_MAX_BACKOFF_MS);
    }
  }

  private async drainInbox(body: ReadableStream<Uint8Array>): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      buffer += decoder.decode(value, { stream: true });
      let sep: number;
      // Frames are separated by a blank line, exactly like the bridge's SSE.
      while ((sep = buffer.indexOf("\n\n")) >= 0) {
        const frame = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        this.handleFrame(frame);
      }
    }
  }

  private handleFrame(frame: string): void {
    let event = "message";
    const dataLines: string[] = [];
    for (const line of frame.split("\n")) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""));
      // ":comment" heartbeats and anything else are ignored.
    }
    if (dataLines.length === 0) return;
    if (event === "permission-decision") {
      let d: Partial<PermissionDecision>;
      try {
        d = JSON.parse(dataLines.join("\n"));
      } catch {
        return;
      }
      if (
        typeof d.sessionId !== "string" ||
        typeof d.toolCallId !== "string" ||
        typeof d.optionId !== "string"
      ) {
        return;
      }
      this.logger.log(
        `claude-watch: inbox permission decision for ${d.toolCallId} (${d.behavior ?? "?"})`,
      );
      try {
        this.permissionHandler?.(d as PermissionDecision);
      } catch (err) {
        this.logger.error(`claude-watch: permission handler threw: ${String(err)}`);
      }
      return;
    }
    if (event === "input-decision") {
      let d: { sessionId?: unknown; toolCallId?: unknown; answers?: unknown };
      try {
        d = JSON.parse(dataLines.join("\n"));
      } catch {
        return;
      }
      if (
        typeof d.sessionId !== "string" ||
        typeof d.toolCallId !== "string" ||
        !Array.isArray(d.answers)
      ) {
        return;
      }
      // Positional: a non-string entry (a skipped slot's null, or junk) stays
      // null so the array never loses alignment with the questions.
      const answers = d.answers.map((a) => (typeof a === "string" ? a : null));
      this.logger.log(
        `claude-watch: inbox input decision for ${d.toolCallId} (${answers.length} answer(s))`,
      );
      try {
        this.inputHandler?.({ sessionId: d.sessionId, toolCallId: d.toolCallId, answers });
      } catch (err) {
        this.logger.error(`claude-watch: input handler threw: ${String(err)}`);
      }
      return;
    }
    if (event === "spawn") {
      let s: Partial<SpawnRequest>;
      try {
        s = JSON.parse(dataLines.join("\n"));
      } catch {
        return;
      }
      if (typeof s.requestId !== "string" || !s.requestId || typeof s.cwd !== "string" || !s.cwd) {
        return;
      }
      const agent = typeof s.agent === "string" && s.agent ? s.agent : "claude";
      this.logger.log(`claude-watch: inbox spawn request ${s.requestId} (cwd=${s.cwd})`);
      try {
        this.spawnHandler?.({ requestId: s.requestId, cwd: s.cwd, agent });
      } catch (err) {
        this.logger.error(`claude-watch: spawn handler threw: ${String(err)}`);
      }
      return;
    }
    if (event === "close") {
      let c: { sessionId?: unknown; reason?: unknown };
      try {
        c = JSON.parse(dataLines.join("\n"));
      } catch {
        return;
      }
      if (typeof c.sessionId !== "string" || !c.sessionId) return;
      const reason = typeof c.reason === "string" && c.reason ? c.reason : "watch-kill";
      this.logger.log(`claude-watch: inbox close request for session ${c.sessionId} (${reason})`);
      try {
        this.closeHandler?.({ sessionId: c.sessionId, reason });
      } catch (err) {
        this.logger.error(`claude-watch: close handler threw: ${String(err)}`);
      }
      return;
    }
    if (event !== "inject") return;
    let msg: { sessionId?: unknown; text?: unknown; source?: unknown };
    try {
      msg = JSON.parse(dataLines.join("\n"));
    } catch {
      return;
    }
    if (typeof msg.sessionId !== "string" || typeof msg.text !== "string") return;
    const source = typeof msg.source === "string" ? msg.source : "watch";
    this.logger.log(`claude-watch: inbox inject for session ${msg.sessionId} (source=${source})`);
    try {
      this.injectHandler?.(msg.sessionId, msg.text, source);
    } catch (err) {
      this.logger.error(`claude-watch: inject handler threw: ${String(err)}`);
    }
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Build the bridge channel, or `null` when this fork was not launched by
 *  claude-watch (`CLAUDE_WATCH_ACP` unset). Returning null keeps the adapter's
 *  behaviour byte-for-byte identical to upstream for every other user of the
 *  fork, and keeps the upstream test suite untouched. */
export function createBridgeChannel(logger: Logger = console): BridgeChannel | null {
  if (process.env.CLAUDE_WATCH_ACP !== "1") return null;
  return new HttpBridgeChannel(logger);
}

/** How long a detached session's permission request — or AskUserQuestion
 *  input-request (#111) — may sit unanswered before the pending wait settles
 *  as cancelled. Slightly ABOVE the bridge's own wrist-card expiry (~9.5 min),
 *  so the wrist always gets the full window first; after it, the turn ends
 *  honestly ("Tool use aborted") instead of wedging forever in a session with
 *  no second surface to fall back to. */
export const DETACHED_PERMISSION_TIMEOUT_MS = 600_000;

/** Wrap an {@link AcpClient} so a DETACHED session — one spawned from the watch
 *  that no editor thread exists for yet — never reaches the editor. ACP routes
 *  notifications and RPCs by session id, and the editor has never seen this id:
 *  best case the message is dropped, worst case a strict client errors on it.
 *  So while `isDetached(sessionId)` holds:
 *
 *    - notifications (`sessionUpdate`, `extNotification`,
 *      `completeElicitation`) resolve without being sent;
 *    - `requestPermission` never asks the editor: it stays pending so the
 *      wrist lane of the #80 race is the only answerable surface, resolves
 *      `cancelled` when the tool call's signal aborts (turn cancel), and
 *      resolves `cancelled` after {@link DETACHED_PERMISSION_TIMEOUT_MS} as a
 *      backstop so an unanswered card can never wedge the turn forever;
 *    - fs and elicitation REQUESTS reject, which every call site already
 *      catches and degrades gracefully (MCP elicitation → decline,
 *      AskUserQuestion → the wrist race parks the rejection and waits on the
 *      watch alone (#111), refusal dialog → cancelled).
 *
 *  Compose UNDER the bridge tee (`teeClientToBridge(guardDetachedClient(...))`)
 *  so the watch mirror keeps flowing while the editor leg is suppressed. The
 *  guard re-checks `isDetached` on every call, so the moment a session is
 *  adopted by a Zed thread the same client object serves it normally. */
export function guardDetachedClient(
  inner: AcpClient,
  isDetached: (sessionId: string) => boolean,
): AcpClient {
  return {
    sessionUpdate(params: SessionNotification): Promise<void> {
      if (isDetached(params.sessionId)) return Promise.resolve();
      return inner.sessionUpdate(params);
    },
    requestPermission(
      params: RequestPermissionRequest,
      signal?: AbortSignal,
    ): Promise<RequestPermissionResponse> {
      if (!isDetached(params.sessionId)) return inner.requestPermission(params, signal);
      return new Promise<RequestPermissionResponse>((resolve) => {
        const settle = () => {
          clearTimeout(timer);
          signal?.removeEventListener("abort", settle);
          resolve({ outcome: { outcome: "cancelled" } });
        };
        // unref'd: a pending backstop must not hold the process open past a
        // Zed quit — the fork's lifetime belongs to the ACP connection.
        const timer = setTimeout(settle, DETACHED_PERMISSION_TIMEOUT_MS);
        timer.unref?.();
        if (signal?.aborted) settle();
        else signal?.addEventListener("abort", settle, { once: true });
      });
    },
    readTextFile(params: ReadTextFileRequest): Promise<ReadTextFileResponse> {
      if (isDetached(params.sessionId)) {
        return Promise.reject(new Error("detached session: no editor attached to read from"));
      }
      return inner.readTextFile(params);
    },
    writeTextFile(params: WriteTextFileRequest): Promise<WriteTextFileResponse> {
      if (isDetached(params.sessionId)) {
        return Promise.reject(new Error("detached session: no editor attached to write to"));
      }
      return inner.writeTextFile(params);
    },
    unstable_createElicitation(
      params: CreateElicitationRequest,
      signal?: AbortSignal,
    ): Promise<CreateElicitationResponse> {
      // The request is a scope union: session-scoped variants carry
      // `sessionId`, request-scoped ones don't (and aren't session-bound, so
      // they pass through).
      const sid = (params as { sessionId?: unknown }).sessionId;
      if (typeof sid === "string" && isDetached(sid)) {
        return Promise.reject(new Error("detached session: no editor to present this"));
      }
      return inner.unstable_createElicitation(params, signal);
    },
    unstable_completeElicitation(params: CompleteElicitationNotification): Promise<void> {
      // Carries only an elicitationId — and no elicitation can exist for a
      // detached session (creation is blocked above), so pass through.
      return inner.unstable_completeElicitation(params);
    },
    extNotification(method: string, params: Record<string, unknown>): Promise<void> {
      if (typeof params.sessionId === "string" && isDetached(params.sessionId)) {
        return Promise.resolve();
      }
      return inner.extNotification(method, params);
    },
  };
}

/** Decorate an {@link AcpClient} so every `sessionUpdate` and every
 *  `requestPermission` RPC is ALSO mirrored to the bridge, without altering the
 *  real call's arguments, return value, or timing. This is the review-mandated
 *  tap point: `sendUpdate` alone misses tool results and every permission
 *  prompt, so we tap the client — the one surface both funnel through. Every
 *  other method passes straight through untouched. */
export function teeClientToBridge(inner: AcpClient, bridge: BridgeChannel): AcpClient {
  return {
    sessionUpdate(params: SessionNotification): Promise<void> {
      bridge.forwardSessionUpdate(params);
      return inner.sessionUpdate(params);
    },
    requestPermission(
      params: RequestPermissionRequest,
      signal?: AbortSignal,
    ): Promise<RequestPermissionResponse> {
      bridge.forwardPermissionRequest(params);
      return inner.requestPermission(params, signal);
    },
    readTextFile(params: ReadTextFileRequest): Promise<ReadTextFileResponse> {
      return inner.readTextFile(params);
    },
    writeTextFile(params: WriteTextFileRequest): Promise<WriteTextFileResponse> {
      return inner.writeTextFile(params);
    },
    unstable_createElicitation(
      params: CreateElicitationRequest,
      signal?: AbortSignal,
    ): Promise<CreateElicitationResponse> {
      return inner.unstable_createElicitation(params, signal);
    },
    unstable_completeElicitation(params: CompleteElicitationNotification): Promise<void> {
      return inner.unstable_completeElicitation(params);
    },
    extNotification(method: string, params: Record<string, unknown>): Promise<void> {
      return inner.extNotification(method, params);
    },
  };
}

/** Fold the wrist's positional AskUserQuestion answers into the tool's input
 *  (#111) — the wire-side twin of elicitation.ts's applyAskElicitationResponse,
 *  producing the same `{ [questionText]: answer }` map the tool's own call()
 *  reads back. Positional in, keyed out: duplicate question texts stay
 *  distinct on the wire and collapse only at this final fold, exactly as the
 *  Zed path's indexed form fields do. A skipped slot (null/blank) leaves its
 *  question unanswered, which the tool supports — nothing in the form is
 *  required. An answer that is an option label and one the user dictated are
 *  indistinguishable here ON PURPOSE: the tool records the literal string
 *  either way, so the wrist's dictation lane IS the free-text "Other" box. */
export function applyWristAskAnswers(
  answers: ReadonlyArray<string | null>,
  toolInput: Record<string, unknown>,
  questions: ReadonlyArray<{ question: string }>,
): Record<string, unknown> {
  const collected: Record<string, string> = {};
  questions.forEach((question, index) => {
    const value = answers[index];
    if (typeof value !== "string") return;
    const text = value.trim();
    if (text === "") return;
    collected[question.question] = text;
  });
  return { ...toolInput, answers: collected };
}
