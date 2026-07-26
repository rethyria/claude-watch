// ACP integration (S3 #77): the loopback channel between the forked
// claude-agent-acp (launched by Zed) and the bridge.
//
//   fork -> bridge   POST /acp/register | /acp/update | /acp/deregister
//   bridge -> fork   GET  /acp/inbox   (a long-lived SSE the fork holds; the
//                    bridge writes `inject` frames down it for watch dictation)
//
// Session slot lifecycle lives in sessions.js (registerAcpSession /
// endAcpSession) so hook-twin correlation and the shared sessions map stay in
// one place; this module owns only the transport: the inbox connections, the
// session -> connection routing, and pushing dictation to the fork.
//
// Every endpoint is loopback-only, mirroring /hooks/* and /admin/*: the fork
// runs on this machine, and a LAN peer must never be able to register phantom
// sessions or inject prompts. It is NOT part of the versioned /v1 client
// protocol.
import { jsonResponse, readBody, log, isLoopbackAddress } from "./util.js";
import {
  registerAcpSession, endAcpSession, sessions, markSessionIdle, markSessionWorking, sessionEventPayload,
} from "./sessions.js";
import { ACP_INBOX_HEARTBEAT_MS, ACP_SPAWN_TIMEOUT_MS } from "./config.js";
import { waitForPermission, canonicalPermissionOptions, cancelPermission } from "./permissions.js";
import crypto from "node:crypto";
import path from "node:path";
import { pushSseEvent, sseClients } from "./transport-sse.js";

/** Live fork inboxes: connectionId -> { res, heartbeat }. The held SSE response
 *  the bridge writes `inject` frames to. */
const acpInboxes = new Map();
/** Routing: ACP sessionId -> the connectionId (fork) that owns it. The source
 *  of truth for which inbox a dictation goes down. */
const sessionConnection = new Map();
/** Coalescing buffer: ACP sessionId -> prose accumulated since the last flush.
 *  Flushed as ONE `message` event at a turn boundary or a pause (see
 *  flushProse), never per delta. */
const proseBuffers = new Map();
/** ACP toolCallId -> the bridge permissionId raised for it (#80), so a request
 *  answered in Zed can retract the wrist prompt for the SAME tool call. */
const acpPermissionsByToolCall = new Map();
/** Hard cap so a pathological turn cannot grow the buffer without bound; the
 *  wrist cannot read more than this anyway. */
const PROSE_BUFFER_MAX = 4000;
/** In-flight watch spawns awaiting the fork's /acp/spawn-result:
 *  requestId -> { resolve, timer, connectionId }. Settled exactly once — by
 *  the result, the timeout, or the owning inbox closing. */
const pendingAcpSpawns = new Map();
/** Watch-spawned sessions no editor thread has adopted yet:
 *  sessionId -> { cwd, createdAt }. The desk-pickup registry /acp/claim takes
 *  from. Deliberately SEPARATE from the sessions map: a fork death ends the
 *  slot, but the pickup must survive it (that is the "Zed restarted between
 *  wrist and desk" case — the claim then resumes the session from disk).
 *  In-memory only; after a bridge restart it is rebuilt from the fork's
 *  register replay, which re-announces live detached sessions with the flag.
 *  (A bridge restart AFTER the fork also died does lose the pickup — the
 *  session still sits on disk for Zed's Import Threads.) */
const pendingPickups = new Map();
/** Cap on remembered pickups; beyond it the OLDEST is evicted. Pickups are
 *  claimed in newest-first order, so evicting the oldest loses the least. */
const PENDING_PICKUPS_MAX = 32;

function requireLoopback(req, res) {
  const addr = req.socket?.remoteAddress;
  if (isLoopbackAddress(addr)) return true;
  log("warn", `ACP request rejected: non-loopback source ${addr || "unknown"}`);
  jsonResponse(res, 403, { error: "ACP endpoints are only accepted from localhost" });
  return false;
}

async function readAcpBody(req, res) {
  try {
    const body = await readBody(req, res);
    if (body === null || typeof body !== "object" || Array.isArray(body)) return {};
    return body;
  } catch (err) {
    if (err?.tooLarge) return null; // readBody already sent 413
    jsonResponse(res, 400, { error: "Invalid JSON" });
    return null;
  }
}

/** Integer percent of the context window used, from a used/size token pair
 *  (#97). One function for BOTH sources of the number — the register body's
 *  seed and every teed `usage_update` — so the seed and the live path can
 *  never round differently. `null` (never a guess) when the pair can't
 *  honestly yield one: a missing/absurd size, or a fork too old to send it.
 *  Clamped to 100 — the SDK can briefly report used > size around a window
 *  correction, and a wrist showing 104% helps nobody. */
function contextPctOf(used, size) {
  if (typeof used !== "number" || typeof size !== "number") return null;
  if (!Number.isFinite(used) || !Number.isFinite(size) || used < 0 || size <= 0) return null;
  return Math.min(100, Math.round((used / size) * 100));
}

// POST /acp/register { connection, sessionId, sdkSessionId, cwd, active? }
export async function handleAcpRegister(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  const body = await readAcpBody(req, res);
  if (body === null) return;

  const { connection, sessionId, sdkSessionId, cwd, active, title, detached, model, mode } = body;
  if (typeof connection !== "string" || !connection || typeof sessionId !== "string" || !sessionId) {
    return jsonResponse(res, 400, { error: "Missing 'connection' or 'sessionId'" });
  }

  // `active` is the fork's report of whether a turn is in flight; it is what
  // stops a re-announce (bridge restart) from showing an idle thread as working.
  // The subheading meta (#97) is seeded here too: the register carries context
  // TOKENS (used + window size) and the bridge owns the percent, so the wire
  // seed and the teed usage_update path share contextPctOf above.
  const slot = registerAcpSession({
    sessionId, sdkSessionId, cwd, active, title, detached, model, mode,
    contextPct: contextPctOf(body.contextUsed, body.contextSize),
  });
  sessionConnection.set(sessionId, connection);
  // Pickup registry maintenance. A register WITH the flag (spawn, or the
  // fork's replay after a bridge restart) marks the session claimable; one
  // WITHOUT it (a normal Zed session, or the attach-time re-register after a
  // desk pickup / session load) retires the pickup — including the case where
  // the user opened the session some other way (Import Threads) and a stale
  // pickup would otherwise hand an already-attached session to a New Thread.
  if (detached === true) {
    addPendingPickup(sessionId, slot.cwd);
  } else {
    pendingPickups.delete(sessionId);
  }
  return jsonResponse(res, 200, { ok: true });
}

/** Remember a watch-spawned session as awaiting its desk pickup. Bounded:
 *  beyond the cap the oldest pickup is evicted (and logged — a silent drop
 *  would read as "covered" when it isn't). */
function addPendingPickup(sessionId, cwd) {
  if (pendingPickups.has(sessionId)) {
    pendingPickups.get(sessionId).cwd = cwd;
    return;
  }
  while (pendingPickups.size >= PENDING_PICKUPS_MAX) {
    const oldest = pendingPickups.keys().next().value;
    pendingPickups.delete(oldest);
    log("warn", `Pickup registry full (${PENDING_PICKUPS_MAX}); evicted oldest pending pickup ${oldest}`);
  }
  pendingPickups.set(sessionId, { cwd, createdAt: Date.now() });
}

// POST /acp/claim { connection, cwd } — atomically take the newest unclaimed
// watch-spawned session for `cwd`. The fork calls this on session/new (the
// desk pickup); at most one caller wins a given session. `sessionId: null`
// means "nothing pending — mint a fresh session".
export async function handleAcpClaim(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  const body = await readAcpBody(req, res);
  if (body === null) return;

  const { cwd } = body;
  if (typeof cwd !== "string" || !cwd) {
    return jsonResponse(res, 400, { error: "Missing 'cwd'" });
  }
  const resolved = path.resolve(cwd);
  let best = null;
  for (const [sid, info] of pendingPickups) {
    if (path.resolve(info.cwd) !== resolved) continue;
    if (!best || info.createdAt > best.info.createdAt) best = { sid, info };
  }
  if (!best) return jsonResponse(res, 200, { ok: true, sessionId: null });
  pendingPickups.delete(best.sid);
  log("info", `ACP pickup claimed: watch-spawned session ${best.sid} adopted by a new editor thread`);
  return jsonResponse(res, 200, { ok: true, sessionId: best.sid });
}

// POST /acp/spawn-result { connection, requestId, ok, sessionId?, cwd?, error? }
// — the fork's explicit answer to a `spawn` frame. Separate from /acp/register
// on purpose: a createSession THROW never registers, and the register replay
// on reconnect re-sends payloads verbatim — piggybacking correlation on either
// would mean failures surface only by timeout and stale requestIds replay.
export async function handleAcpSpawnResult(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  const body = await readAcpBody(req, res);
  if (body === null) return;

  const { connection, requestId, ok, sessionId, cwd, error } = body;
  if (typeof requestId !== "string" || !requestId) {
    return jsonResponse(res, 400, { error: "Missing 'requestId'" });
  }

  // Success bookkeeping happens whether or not the waiter is still around: a
  // session that finished creating AFTER the bridge gave up (the timeout
  // ghost) must still become a visible, attributable, pickable-up session —
  // that convergence is the self-healing contract.
  if (ok === true && typeof sessionId === "string" && sessionId) {
    // Early-register: the fork's own register POST and this ack race on two
    // sockets. Registering here (idempotently) guarantees the slot exists
    // before the watch's spawn response names it, so an immediate follow-up
    // dictation can never miss the slot.
    if (!sessions.has(sessionId)) {
      registerAcpSession({ sessionId, sdkSessionId: sessionId, cwd, detached: true });
    }
    if (typeof connection === "string" && connection) {
      sessionConnection.set(sessionId, connection);
    }
    const slot = sessions.get(sessionId);
    if (slot) {
      addPendingPickup(sessionId, slot.cwd);
      if (slot.spawnRequestId !== requestId) {
        // The requestId rides the session announce so the watch can attribute
        // a late arrival to the spawn it reported as failed ("arrived late",
        // not a mystery session).
        slot.spawnRequestId = requestId;
        announceAcpSlot(sessionId);
      }
    }
  }

  const pending = pendingAcpSpawns.get(requestId);
  if (!pending) {
    log("warn", `ACP spawn-result for unknown/expired request ${requestId} (${ok ? `session ${sessionId}` : `error: ${error}`}) — accepted late`);
    return jsonResponse(res, 200, { ok: true, stale: true });
  }
  settlePendingSpawn(requestId, ok === true && typeof sessionId === "string" && sessionId
    ? { ok: true, sessionId, requestId }
    : { ok: false, error: typeof error === "string" && error ? error : "adapter could not create the session", requestId });
  return jsonResponse(res, 200, { ok: true });
}

/** Settle (exactly once) an in-flight watch spawn. */
function settlePendingSpawn(requestId, result) {
  const pending = pendingAcpSpawns.get(requestId);
  if (!pending) return;
  pendingAcpSpawns.delete(requestId);
  clearTimeout(pending.timer);
  pending.resolve(result);
}

/** Pick the fork connection a watch spawn should land on. Preference order:
 *  a connection already hosting a running ACP session in the SAME directory
 *  (that window's fork is where a later session/load adopts the live session),
 *  else the single live inbox, else the newest one (multi-window Zed with no
 *  cwd match — the most recently opened window is the best guess). `null`
 *  means no fork is connected at all. */
function pickSpawnConnection(cwd) {
  if (acpInboxes.size === 0) return null;
  const resolved = path.resolve(cwd);
  for (const [sid, connectionId] of sessionConnection) {
    if (!acpInboxes.has(connectionId)) continue;
    const slot = sessions.get(sid);
    if (slot && slot.state === "running" && slot.cwd && path.resolve(slot.cwd) === resolved) {
      return connectionId;
    }
  }
  if (acpInboxes.size === 1) return acpInboxes.keys().next().value;
  let newest = null;
  for (const [connectionId, inbox] of acpInboxes) {
    if (!newest || inbox.connectedAt > newest.connectedAt) {
      newest = { connectionId, connectedAt: inbox.connectedAt };
    }
  }
  return newest?.connectionId ?? null;
}

/** Spawn a claude session inside the Zed-launched fork (the watch "new
 *  session" path). Resolves `null` when no fork is connected (caller answers
 *  the watch honestly — there is deliberately NO PTY fallback for claude),
 *  else `{ ok, sessionId?, error?, requestId }`. Never rejects. */
export function requestAcpSpawn(cwd) {
  const connectionId = pickSpawnConnection(cwd);
  if (!connectionId) return Promise.resolve(null);
  const requestId = crypto.randomUUID();
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      // An old adapter build silently DROPS the unknown frame (its reader
      // hard-ignores foreign events), so a timeout with Zed open most likely
      // means a stale dist/ — name that, it already bit once.
      log("warn", `ACP spawn ${requestId} timed out after ${ACP_SPAWN_TIMEOUT_MS}ms (connection ${connectionId})`);
      settlePendingSpawn(requestId, {
        ok: false,
        error: "Zed's agent did not answer — if Zed is open, the claude-watch adapter may need a rebuild",
        requestId,
      });
    }, ACP_SPAWN_TIMEOUT_MS);
    pendingAcpSpawns.set(requestId, { resolve, timer, connectionId });
    log("info", `ACP spawn ${requestId} requested (cwd ${cwd}) → connection ${connectionId}`);
    if (!writeAcpFrameToConnection(connectionId, "spawn", { requestId, cwd, agent: "claude" })) {
      settlePendingSpawn(requestId, { ok: false, error: "Zed agent connection lost", requestId });
    }
  });
}

// POST /acp/update { connection, sessionId, kind: "session_update"|"permission"|"turn", payload }
//
// The tap the review mandated: the fork mirrors BOTH `sessionUpdate` and the
// `requestPermission` RPC here (sendUpdate alone misses tool results and every
// permission prompt), plus an explicit `turn` boundary the ACP protocol has no
// update variant for.
//
// S3 could ack-and-discard because working/idle rode the settings.json hooks
// the SDK fires (hook-twin correlation resolves them onto this same slot —
// verified live 2026-07-25). That channel is being retired, so this handler is
// now the SOLE authority for an ACP slot's turn state: `kind: "turn"` drives
// `slot.idle`, and `agent_message_chunk` is fanned out as assistant prose —
// the one capability hooks never had. Interactive permissions from the wrist
// are #80, so `kind: "permission"` stays ack-only for now.
export async function handleAcpUpdate(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  const body = await readAcpBody(req, res);
  if (body === null) return;

  const { connection, sessionId } = body;
  if (typeof sessionId !== "string" || !sessionId) {
    return jsonResponse(res, 400, { error: "Missing 'sessionId'" });
  }
  // Keep the routing binding fresh: a tapped update for a known session whose
  // connection binding was lost (e.g. the register POST was dropped) re-asserts
  // it, so a subsequent dictation can still be routed.
  if (typeof connection === "string" && connection && sessions.has(sessionId)) {
    sessionConnection.set(sessionId, connection);
  }

  // Turn boundary (#79 / #83). The ACP `sessionUpdate` union has no turn-end
  // variant — turn end is the session/prompt RPC's `stopReason`, which never
  // flows through the client tee — so the fork forwards it explicitly. This is
  // the ONLY driver of turn-level state for an ACP slot: every writer of
  // `slot.idle` is otherwise the hook channel or the headless path, and the
  // hooks block is being retired. `state` is deliberately left alone: it stays
  // "running" across a finished turn by design (issue #60), and `idle` is the
  // turn-level truth that rides the next session event.
  if (body.kind === "turn" && sessions.has(sessionId)) {
    const phase = body.payload?.phase;
    if (phase === "end") {
      markSessionIdle(sessionId);
      flushProse(sessionId);
    } else if (phase === "start") {
      markSessionWorking(sessionId);
      // A new turn starts a new message; anything unflushed is stale.
      proseBuffers.delete(sessionId);
    }
    // Setting the flag is not enough for a watch that is ALREADY connected:
    // `idle` is designed to ride the next session event, and a hook session got
    // that push from the Stop hook. Without an equivalent here the wrist sits on
    // "working" forever. One idempotent `session` running event — the same shape
    // the connect-time sync re-sends, and the same trick announceMetadataRefresh
    // uses — carries the flag with no new event type and no client change.
    announceAcpSlot(sessionId, { announceWorking: phase === "start" });
  }

  // Assistant prose (#79) — the capability hooks never had. Fanned out as a NEW
  // `message` event rather than folded into `tool-output`: clients ignore
  // unknown events, so the proto stays additive and older watches are
  // unaffected. Assistant-only by construction — the adapter emits no
  // `user_message_chunk`, so the watch's own local echo remains the single
  // authority for the user's dictated text (no double-echo).
  //
  // COALESCED, not streamed (see flushProse): the deltas arrive in dozens of
  // tiny chunks per turn, and one SSE frame each is that many radio wakeups on
  // a watch for content the wrist explicitly does not want mid-turn. The wrist
  // wants the LAST thing said — the report or the request.
  if (body.kind === "session_update" && sessions.has(sessionId)) {
    const update = body.payload?.update;
    if (update?.sessionUpdate === "agent_message_chunk" && update.content?.type === "text") {
      const text = update.content.text;
      if (typeof text === "string" && text) {
        proseBuffers.set(sessionId, (proseBuffers.get(sessionId) ?? "") + text);
      }
    }
    // A tool call supersedes whatever was being narrated before it: "let me
    // check the tests" is not the message, the verdict after it is. Dropping the
    // buffer here is what makes the flush "the last block" rather than a
    // transcript of the whole turn.
    if (update?.sessionUpdate === "tool_call") proseBuffers.delete(sessionId);
    // The SDK auto-generates a thread title in the background and the adapter
    // polls it at turn end, pushing `session_info_update`. Without this the slot
    // has no title at all — the transcript-scraping path that titles hook
    // sessions is never fed for ACP — so the watch fell back to the raw uuid.
    if (update?.sessionUpdate === "session_info_update" && typeof update.title === "string") {
      const slot = sessions.get(sessionId);
      const title = update.title.trim();
      if (slot && title && slot.title !== title) {
        slot.title = title;
        slot.titleIsAi = true;
        announceAcpSlot(sessionId);
      }
    }
    // The wrist subheading's `model · mode · use%` (#97). All three ride the
    // client tee already — the adapter sends nothing new — and each announces
    // ONLY on change, via the same idempotent running event the title uses.
    // For usage that change-gate is the throttle: mid-turn usage_updates
    // stream once per message, but the INTEGER percent moves far less often,
    // and a wrist meter has no use for sub-percent motion.
    if (update?.sessionUpdate === "usage_update") {
      const slot = sessions.get(sessionId);
      const contextPct = contextPctOf(update.used, update.size);
      if (slot && contextPct !== null && slot.contextPct !== contextPct) {
        slot.contextPct = contextPct;
        announceAcpSlot(sessionId);
      }
    }
    if (update?.sessionUpdate === "current_mode_update" && typeof update.currentModeId === "string") {
      const slot = sessions.get(sessionId);
      const mode = update.currentModeId;
      if (slot && mode && slot.mode !== mode) {
        slot.mode = mode;
        announceAcpSlot(sessionId);
      }
    }
    // A config_option_update re-sends the WHOLE option list, so one frame can
    // move both fields — and for a mode change made with Zed's native selector
    // (session/set_mode) it is the ONLY teed footprint: the adapter emits
    // current_mode_update on its other mode paths (set_config_option, the
    // plan-mode hooks, the model-switch clamp) but not on that one. Reading
    // the mode option here too is what keeps a Zed mode flip from sitting
    // stale on the wrist until a bridge restart.
    if (update?.sessionUpdate === "config_option_update") {
      const slot = sessions.get(sessionId);
      if (slot) {
        const model = modelDisplayFromConfigOptions(update.configOptions);
        const mode = modeIdFromConfigOptions(update.configOptions);
        let changed = false;
        if (model && slot.model !== model) {
          slot.model = model;
          changed = true;
        }
        if (mode && slot.mode !== mode) {
          slot.mode = mode;
          changed = true;
        }
        if (changed) announceAcpSlot(sessionId);
      }
    }
  }

  // A permission request is a PAUSE, not a turn end: the turn is still open but
  // the user has to answer, so flush what led up to it (#79, the workflow-pause
  // case) and then raise the prompt on the wrist (#80).
  if (body.kind === "permission" && sessions.has(sessionId)) {
    flushProse(sessionId);
    raiseAcpPermission(sessionId, body.payload);
  }

  // The request was settled somewhere else — the user answered in Zed, or the
  // agent cancelled it. Retract the wrist card rather than leaving a zombie
  // whose eventual answer would apply to an already-decided request. Routed
  // through cancelPermission so it announces itself exactly like every other
  // non-answer exit (permission-cleared), and so the waiter resolves as a
  // no-decision rather than hanging until expiry.
  if (body.kind === "permission-resolved") {
    const toolCallId = body.payload?.toolCallId;
    const permissionId = toolCallId ? acpPermissionsByToolCall.get(toolCallId) : null;
    if (permissionId) {
      acpPermissionsByToolCall.delete(toolCallId);
      cancelPermission(permissionId);
    }
  }

  return jsonResponse(res, 200, { ok: true });
}

/** The current model's DISPLAY name from a teed `config_option_update`'s
 *  option list (#97): the model-category option's currentValue, resolved to
 *  its option row's human name. Falls back to the currentValue verbatim when
 *  no row matches — a session running an out-of-picker model (refusal
 *  fallback, allowlist-excluded resume) reports a currentValue with no entry,
 *  and the raw id is then the only honest label. `null` means "this update
 *  says nothing about the model" (no model option at all, or the unresolvable
 *  `default` alias below), never "clear": effort/agent/fast-mode rebuilds
 *  arrive as the same update kind. Option rows can be grouped (an entry
 *  carrying its own `options`), so flatten one level, exactly as the
 *  adapter's own value validation does. */
function modelDisplayFromConfigOptions(configOptions) {
  if (!Array.isArray(configOptions)) return null;
  const option =
    configOptions.find((o) => o?.category === "model") ?? configOptions.find((o) => o?.id === "model");
  if (!option || typeof option.currentValue !== "string" || !option.currentValue) return null;
  // The `default` alias is UNRESOLVABLE here: option rows on the wire carry
  // only value/name, never the `resolvedModel` the issue's default-alias hop
  // needs, so taking the row's own name would rewrite a seeded "Opus" as
  // "Default (recommended)" on the first mode/effort/fast rebuild — and the
  // next bridge restart's replay (which DOES carry the resolved name) would
  // flip it back. Say nothing instead: the register seed and its replay, both
  // computed by the adapter's modelDisplayName — the one place that can
  // resolve the alias — own the field for a session sitting on `default`.
  if (option.currentValue === "default") return null;
  const rows = Array.isArray(option.options)
    ? option.options.flatMap((o) => (o && Array.isArray(o.options) ? o.options : [o]))
    : [];
  const row = rows.find((o) => o?.value === option.currentValue);
  return typeof row?.name === "string" && row.name ? row.name : option.currentValue;
}

/** The current ACP permission-mode id from a teed `config_option_update`'s
 *  option list (#97). Unlike the model there is nothing to resolve: the mode
 *  option's currentValue IS the mode id verbatim, which is exactly the
 *  field's contract. This lookup exists because Zed's native mode selector
 *  lands on session/set_mode, whose only teed footprint is the
 *  config_option_update it triggers — every OTHER mode writer also emits a
 *  current_mode_update, that one does not. `null` means "this update says
 *  nothing about the mode", never "clear". */
function modeIdFromConfigOptions(configOptions) {
  if (!Array.isArray(configOptions)) return null;
  const option =
    configOptions.find((o) => o?.category === "mode") ?? configOptions.find((o) => o?.id === "mode");
  return option && typeof option.currentValue === "string" && option.currentValue
    ? option.currentValue
    : null;
}

/** Emit the session's buffered prose as ONE `message` event and clear it.
 *  Called at the points where the wrist actually wants to read: the end of a
 *  turn, and a pause that needs an answer (a permission request — the user has
 *  to decide, so they need the context that led to it). No-op when nothing has
 *  been said since the last flush. */
function flushProse(sessionId) {
  const text = proseBuffers.get(sessionId);
  proseBuffers.delete(sessionId);
  if (!text) return;
  pushSseEvent("message", { role: "assistant", text: text.slice(-PROSE_BUFFER_MAX).trim() }, sessionId);
}

/** Write one SSE frame down a SPECIFIC fork's inbox. The single place that
 *  touches the downlink socket, so dictation (#78), permission decisions
 *  (#80), and spawn requests cannot drift apart in framing or error handling.
 *  Returns false when that connection has no live inbox. */
function writeAcpFrameToConnection(connectionId, event, data) {
  const inbox = acpInboxes.get(connectionId);
  if (!inbox) return false;
  try {
    inbox.res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  } catch {
    return false;
  }
  return true;
}

/** Session-addressed variant: route by the session's owning fork. Returns
 *  false when the session is unknown or its fork has no live inbox. */
function writeAcpFrame(sessionId, event, data) {
  const connectionId = sessionConnection.get(sessionId);
  if (!connectionId) return false;
  return writeAcpFrameToConnection(connectionId, event, data);
}

/** Map an ACP option's `kind` onto the bridge's machine-readable behavior. The
 *  /v1 contract is explicit that clients must never infer approve/deny from a
 *  label's wording or an option's position, so an unmappable kind is dropped
 *  rather than guessed at. */
function behaviorForAcpOption(option) {
  switch (option?.kind) {
    case "allow_always": return "allow-always";
    case "allow_once": return "allow";
    case "reject_once":
    case "reject_always": return "deny";
    default: return null;
  }
}

/** Raise an ACP permission request on the wrist (#80) and, once answered, send
 *  the decision back down the fork's inbox.
 *
 *  Zed shows its own prompt for the same request no matter what we do, so this
 *  is a SECOND surface for one decision, not the only one — whichever answers
 *  first wins and the fork drops the loser. With no watch connected nobody can
 *  answer here, so we do not raise at all: a prompt nobody sees would just sit
 *  until it expired while the user answered in Zed anyway.
 *
 *  EXCEPT for a detached (watch-spawned, no editor thread) session: there the
 *  wrist is the ONLY surface, so the card is registered even with zero SSE
 *  clients — `pendingPermissionsSync` replays it the moment the watch
 *  connects. If nobody ever answers, the bridge card expires as a no-decision
 *  (nothing is sent down the inbox) and the FORK's own detached backstop
 *  settles the turn as cancelled shortly after — an honest ending, not a
 *  wedge. */
function raiseAcpPermission(sessionId, payload) {
  if (sseClients.size === 0 && sessions.get(sessionId)?.detached !== true) return;
  const toolCallId = payload?.toolCall?.toolCallId;
  if (!toolCallId) return;

  const options = [];
  for (const option of payload?.options ?? []) {
    const behavior = behaviorForAcpOption(option);
    if (behavior) options.push({ behavior, label: String(option.name ?? ""), optionId: option.optionId });
  }
  if (options.length === 0) return; // nothing answerable — leave it to Zed

  const permissionId = crypto.randomUUID();
  acpPermissionsByToolCall.set(toolCallId, permissionId);
  const eventPayload = {
    permissionId,
    sessionId,
    tool_name: payload?.toolCall?.title ?? "tool",
    tool_input: payload?.toolCall?.rawInput ?? null,
    options: canonicalPermissionOptions(options.map(({ behavior, label }) => ({ behavior, label }))),
  };
  // Carry the ACP optionIds alongside, so the decision we send back names the
  // option the AGENT offered rather than one we invented from its behavior.
  const optionIdByBehavior = new Map(options.map((o) => [o.behavior, o.optionId]));

  log("info", `ACP permission ${permissionId} raised on the wrist (session ${sessionId}, tool ${eventPayload.tool_name})`);
  const decision = waitForPermission(permissionId, { sessionId, payload: eventPayload });
  pushSseEvent("permission-request", eventPayload, sessionId);

  void decision.then((answer) => {
    acpPermissionsByToolCall.delete(toolCallId);
    // A no-decision (expiry, or the prompt voided) is NOT an answer: say
    // nothing and let Zed's own prompt keep the decision, exactly as the hook
    // path does. Fabricating a deny here would cancel the dialog on screen.
    if (!answer || answer.noDecision) return;
    const optionId = optionIdByBehavior.get(answer.behavior);
    if (!optionId) return;
    writeAcpFrame(sessionId, "permission-decision", { sessionId, toolCallId, optionId, behavior: answer.behavior });
  });
}

/** Re-announce an ACP slot as one idempotent `session` running event. The
 *  additive fields (`idle`, `title`, git metadata) ride this payload, so a
 *  client that is already connected learns about them without a new event type.
 *  Mirrors announceMetadataRefresh in sessions.js. */
function announceAcpSlot(sessionId, { announceWorking = false } = {}) {
  const slot = sessions.get(sessionId);
  if (!slot || slot.state !== "running") return;
  const payload = sessionEventPayload(slot, {
    state: "running", agent: slot.agent, cwd: slot.cwd, folderName: slot.folderName,
  });
  // `idle` is a ONE-WAY latch on the client (issue #60): a present `true` idles
  // a session, but ABSENCE never wakes one, because every reconnect snapshot
  // omits it and waking on that would restart the elapsed clock each time. A
  // turn start therefore has to say `false` OUT LOUD. Only here — never on a
  // snapshot — which is exactly what keeps the latch's protection intact.
  if (announceWorking) payload.idle = false;
  pushSseEvent("session", payload, sessionId);
}

// POST /acp/deregister { connection, sessionId, reason }
export async function handleAcpDeregister(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  const body = await readAcpBody(req, res);
  if (body === null) return;

  const { sessionId, reason } = body;
  if (typeof sessionId !== "string" || !sessionId) {
    return jsonResponse(res, 400, { error: "Missing 'sessionId'" });
  }
  endAcpSession(sessionId, typeof reason === "string" ? reason : "acp-closed");
  sessionConnection.delete(sessionId);
  proseBuffers.delete(sessionId);
  return jsonResponse(res, 200, { ok: true });
}

// GET /acp/inbox?connection=<id> — the fork's downlink. The bridge holds this
// SSE open and writes `inject` frames to it. The connection's liveness is the
// fork's liveness: when it drops (fork death, even on SIGKILL where the
// graceful deregister never runs) every ACP session bound to it is ended, so a
// Zed quit strands no zombie slot.
export function handleAcpInbox(req, res) {
  if (req.method !== "GET") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;

  let connectionId;
  try {
    connectionId = new URL(req.url, "http://127.0.0.1").searchParams.get("connection");
  } catch {
    connectionId = null;
  }
  if (!connectionId) {
    return jsonResponse(res, 400, { error: "Missing 'connection' query parameter" });
  }

  // Replace any prior inbox for this connection id (a reconnect): end the old
  // response so we never hold two.
  const prior = acpInboxes.get(connectionId);
  if (prior) {
    clearInterval(prior.heartbeat);
    try { prior.res.end(); } catch { /* already gone */ }
  }

  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",
  });
  res.write(":connected\n\n");
  req.socket.setKeepAlive(true, ACP_INBOX_HEARTBEAT_MS);

  const heartbeat = setInterval(() => {
    try {
      res.write(":heartbeat\n\n");
    } catch {
      clearInterval(heartbeat);
    }
  }, ACP_INBOX_HEARTBEAT_MS);

  // `connectedAt` feeds pickSpawnConnection's newest-wins fallback; Map
  // insertion order is NOT a substitute (a reconnect `set()` on an existing
  // key keeps its old position).
  acpInboxes.set(connectionId, { res, heartbeat, connectedAt: Date.now() });
  log("info", `ACP fork inbox connected (connection ${connectionId}; ${acpInboxes.size} total)`);

  req.on("close", () => {
    clearInterval(heartbeat);
    // Only tear down if THIS response is still the registered one (a reconnect
    // may have replaced it already, in which case the new inbox owns the
    // sessions and must not be collaterally cleaned up).
    if (acpInboxes.get(connectionId)?.res !== res) return;
    acpInboxes.delete(connectionId);
    // Fail this fork's in-flight spawns fast — waiting out the timer would
    // just make the wrist stare at a spinner for a fork that is gone.
    for (const [requestId, pending] of pendingAcpSpawns) {
      if (pending.connectionId !== connectionId) continue;
      settlePendingSpawn(requestId, { ok: false, error: "Zed agent connection lost", requestId });
    }
    let ended = 0;
    for (const [sid, conn] of sessionConnection) {
      if (conn !== connectionId) continue;
      sessionConnection.delete(sid);
      if (endAcpSession(sid, "acp-fork-disconnected")) ended++;
    }
    log("info", `ACP fork inbox disconnected (connection ${connectionId}); ended ${ended} session(s)`);
  });
}

/** Push a dictated prompt down the owning fork's inbox. Returns true if it was
 *  written to a live inbox, false if the session is unknown or its fork has no
 *  live inbox (caller surfaces that honestly — the wear side keeps the draft). */
export function injectToAcpSession(sessionId, text, source = "watch") {
  const connectionId = sessionConnection.get(sessionId);
  if (!connectionId) return false;
  const inbox = acpInboxes.get(connectionId);
  if (!inbox) return false;
  if (!writeAcpFrame(sessionId, "inject", { sessionId, text, source })) return false;
  log("info", `Dictated prompt routed to ACP session ${sessionId} (${text.length} chars)`);
  return true;
}

/** Whether a session is an ACP-hosted (dictatable) slot the bridge can inject
 *  into. Used by /command to route dictation to the fork instead of a headless
 *  run. */
export function isAcpSession(sessionId) {
  return sessions.get(sessionId)?.kind === "acp";
}

/** End every inbox (graceful shutdown). */
export function closeAllAcpInboxes() {
  for (const requestId of [...pendingAcpSpawns.keys()]) {
    settlePendingSpawn(requestId, { ok: false, error: "bridge shutting down", requestId });
  }
  for (const { res, heartbeat } of acpInboxes.values()) {
    clearInterval(heartbeat);
    try { res.end(); } catch { /* ignore */ }
  }
  acpInboxes.clear();
  sessionConnection.clear();
}
