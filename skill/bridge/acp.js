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
import { ACP_INBOX_HEARTBEAT_MS } from "./config.js";
import { waitForPermission, canonicalPermissionOptions } from "./permissions.js";
import crypto from "node:crypto";
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

// POST /acp/register { connection, sessionId, sdkSessionId, cwd, active? }
export async function handleAcpRegister(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  const body = await readAcpBody(req, res);
  if (body === null) return;

  const { connection, sessionId, sdkSessionId, cwd, active } = body;
  if (typeof connection !== "string" || !connection || typeof sessionId !== "string" || !sessionId) {
    return jsonResponse(res, 400, { error: "Missing 'connection' or 'sessionId'" });
  }

  // `active` is the fork's report of whether a turn is in flight; it is what
  // stops a re-announce (bridge restart) from showing an idle thread as working.
  registerAcpSession({ sessionId, sdkSessionId, cwd, active });
  sessionConnection.set(sessionId, connection);
  return jsonResponse(res, 200, { ok: true });
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
    announceAcpSlot(sessionId);
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
  }

  // A permission request is a PAUSE, not a turn end: the turn is still open but
  // the user has to answer, so flush what led up to it (#79, the workflow-pause
  // case) and then raise the prompt on the wrist (#80).
  if (body.kind === "permission" && sessions.has(sessionId)) {
    flushProse(sessionId);
    raiseAcpPermission(sessionId, body.payload);
  }

  return jsonResponse(res, 200, { ok: true });
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

/** Write one SSE frame down the owning fork's inbox. The single place that
 *  touches the downlink, so dictation (#78) and permission decisions (#80)
 *  cannot drift apart in framing or error handling. Returns false when the
 *  session is unknown or its fork has no live inbox. */
function writeAcpFrame(sessionId, event, data) {
  const connectionId = sessionConnection.get(sessionId);
  if (!connectionId) return false;
  const inbox = acpInboxes.get(connectionId);
  if (!inbox) return false;
  try {
    inbox.res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  } catch {
    return false;
  }
  return true;
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
 *  until it expired while the user answered in Zed anyway. */
function raiseAcpPermission(sessionId, payload) {
  if (sseClients.size === 0) return;
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
function announceAcpSlot(sessionId) {
  const slot = sessions.get(sessionId);
  if (!slot || slot.state !== "running") return;
  pushSseEvent(
    "session",
    sessionEventPayload(slot, {
      state: "running", agent: slot.agent, cwd: slot.cwd, folderName: slot.folderName,
    }),
    sessionId,
  );
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

  acpInboxes.set(connectionId, { res, heartbeat });
  log("info", `ACP fork inbox connected (connection ${connectionId}; ${acpInboxes.size} total)`);

  req.on("close", () => {
    clearInterval(heartbeat);
    // Only tear down if THIS response is still the registered one (a reconnect
    // may have replaced it already, in which case the new inbox owns the
    // sessions and must not be collaterally cleaned up).
    if (acpInboxes.get(connectionId)?.res !== res) return;
    acpInboxes.delete(connectionId);
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
  for (const { res, heartbeat } of acpInboxes.values()) {
    clearInterval(heartbeat);
    try { res.end(); } catch { /* ignore */ }
  }
  acpInboxes.clear();
  sessionConnection.clear();
}
