// SSE transport: event ring buffer, connected clients, broadcast, and the
// GET /events handler (including replay, connect-time sync, and heartbeat).
import { log, jsonResponse } from "./util.js";
import {
  SSE_BUFFER_SIZE,
  SSE_HEARTBEAT_INTERVAL_MS,
  SSE_MAX_BUFFERED_BYTES,
  SSE_SYNC_TERMINAL_BACKLOG,
  SSE_TCP_KEEPALIVE_MS,
} from "./config.js";
import { requireAuth } from "./credentials.js";

let sseEventId = 0;
/** @type {Array<{id: number, event: string, data: string}>} */
export const sseBuffer = [];
/** @type {Set<import("node:http").ServerResponse>} */
export const sseClients = new Set();

// Connect-time sync providers. Higher-level modules (sessions.js,
// permissions.js, codex.js) register generators that yield {event, data}
// entries to write to a newly connected client, so this module never has to
// import them (which would create an import cycle — they import from here).
// Registration order is evaluation order: sessions.js evaluates before
// codex.js because codex.js imports sessions.js.
/** @type {Array<() => Iterable<{event: string, data: string}>>} */
const sseSyncProviders = [];

export function registerSseSyncProvider(provider) {
  sseSyncProviders.push(provider);
}

export function pushSseEvent(event, data, sessionId = null) {
  sseEventId++;

  // Inject sessionId into the data payload
  let payload;
  if (typeof data === "string") {
    try {
      payload = JSON.parse(data);
    } catch {
      payload = { raw: data };
    }
  } else {
    payload = { ...data };
  }
  if (sessionId !== null) {
    payload.sessionId = sessionId;
  }

  const entry = { id: sseEventId, event, data: JSON.stringify(payload) };

  // Ring buffer
  if (sseBuffer.length >= SSE_BUFFER_SIZE) {
    sseBuffer.shift();
  }
  sseBuffer.push(entry);

  // Broadcast to connected clients
  const formatted = formatSseMessage(entry);
  for (const client of sseClients) {
    try {
      client.write(formatted);
      // Backpressure bound: write() on a stalled or silently-dead client
      // keeps buffering into the response stream (a destroyed response
      // returns false rather than throwing), so events would pile up there
      // for ~15 minutes. A client this far behind gets destroyed; it can
      // reconnect and catch up via Last-Event-ID replay.
      if (client.writableLength > SSE_MAX_BUFFERED_BYTES) {
        const buffered = client.writableLength;
        sseClients.delete(client);
        try { client.destroy(); } catch { /* already gone */ }
        log("warn", `SSE client evicted: ${buffered} buffered bytes exceeds ${SSE_MAX_BUFFERED_BYTES} (total: ${sseClients.size})`);
      }
    } catch {
      sseClients.delete(client);
    }
  }
}

export function formatSseMessage(entry) {
  let msg = `id: ${entry.id}\n`;
  msg += `event: ${entry.event}\n`;
  for (const line of entry.data.split("\n")) {
    msg += `data: ${line}\n`;
  }
  msg += "\n";
  return msg;
}

export function handleEvents(req, res) {
  if (req.method !== "GET") {
    return jsonResponse(res, 405, { error: "Method not allowed" });
  }
  if (!requireAuth(req)) {
    return jsonResponse(res, 401, { error: "Unauthorized" });
  }

  // TCP keepalive: a peer that vanishes without FIN/RST (network loss, watch
  // out of range) otherwise looks connected for ~15 minutes while events
  // buffer into the dead socket. Keepalive probes surface the drop, which
  // fires the 'close' handler below and cleans the client up.
  req.socket.setKeepAlive(true, SSE_TCP_KEEPALIVE_MS);

  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",
  });
  // Node buffers headers until the first body write; without this a client
  // with nothing to replay receives no bytes until the first heartbeat (10s).
  res.write(":connected\n\n");

  // Replay from Last-Event-ID if provided
  const lastIdHeader = req.headers["last-event-id"];
  let replayed = false;
  if (lastIdHeader) {
    const lastId = parseInt(lastIdHeader, 10);
    if (!isNaN(lastId)) {
      replayed = true;
      for (const entry of sseBuffer) {
        if (entry.id > lastId) {
          res.write(formatSseMessage(entry));
        }
      }
    }
  }

  sseClients.add(res);
  log("info", `SSE client connected (total: ${sseClients.size})`);

  // Connect-time snapshot, part 1: recent terminal backlog. A fresh client
  // (no Last-Event-ID — new pair, app reinstall) would otherwise start with a
  // blank terminal until the next output arrives. Replaying clients already
  // received everything after their last id, so resending would duplicate
  // terminal output for them.
  if (!replayed) {
    const backlog = sseBuffer
      .filter((e) => e.event === "pty-output" || e.event === "tool-output")
      .slice(-SSE_SYNC_TERMINAL_BACKLOG);
    for (const entry of backlog) {
      try { res.write(formatSseMessage(entry)); } catch { /* ignore */ }
    }
  }

  // Connect-time snapshot, part 2: authoritative current state, sent on EVERY
  // connect (replay or not) — running sessions (sessions.js), pending hook
  // permissions (permissions.js), and pending Codex synthetic permissions
  // (codex.js). Pending permissions in particular must not rely on the ring
  // buffer: ordinary pty-output can evict a permission-request before a
  // disconnected watch reconnects.
  for (const provider of sseSyncProviders) {
    for (const { event, data } of provider()) {
      const syncEntry = formatSseMessage({ id: sseEventId++, event, data });
      try { res.write(syncEntry); } catch { /* ignore */ }
    }
  }

  const heartbeat = setInterval(() => {
    try {
      res.write(":heartbeat\n\n");
    } catch {
      clearInterval(heartbeat);
      sseClients.delete(res);
    }
  }, SSE_HEARTBEAT_INTERVAL_MS);

  req.on("close", () => {
    clearInterval(heartbeat);
    sseClients.delete(res);
    log("info", `SSE client disconnected (total: ${sseClients.size})`);
  });
}
