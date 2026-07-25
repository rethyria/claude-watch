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
import { pushSseEvent } from "./transport-sse.js";

/** Live fork inboxes: connectionId -> { res, heartbeat }. The held SSE response
 *  the bridge writes `inject` frames to. */
const acpInboxes = new Map();
/** Routing: ACP sessionId -> the connectionId (fork) that owns it. The source
 *  of truth for which inbox a dictation goes down. */
const sessionConnection = new Map();

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

// POST /acp/register { connection, sessionId, sdkSessionId, cwd }
export async function handleAcpRegister(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  const body = await readAcpBody(req, res);
  if (body === null) return;

  const { connection, sessionId, sdkSessionId, cwd } = body;
  if (typeof connection !== "string" || !connection || typeof sessionId !== "string" || !sessionId) {
    return jsonResponse(res, 400, { error: "Missing 'connection' or 'sessionId'" });
  }

  registerAcpSession({ sessionId, sdkSessionId, cwd });
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
    if (phase === "end") markSessionIdle(sessionId);
    else if (phase === "start") markSessionWorking(sessionId);
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
  if (body.kind === "session_update" && sessions.has(sessionId)) {
    const update = body.payload?.update;
    if (update?.sessionUpdate === "agent_message_chunk" && update.content?.type === "text") {
      const text = update.content.text;
      if (typeof text === "string" && text) {
        pushSseEvent("message", { role: "assistant", text }, sessionId);
      }
    }
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

  return jsonResponse(res, 200, { ok: true });
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
  const frame = `event: inject\ndata: ${JSON.stringify({ sessionId, text, source })}\n\n`;
  try {
    inbox.res.write(frame);
  } catch {
    return false;
  }
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
