// SSE transport: event ring buffer, connected clients, broadcast, and the
// GET /events handler (including replay, connect-time sync, and heartbeat).
import { log, jsonResponse } from "./util.js";
import { SSE_BUFFER_SIZE, SSE_HEARTBEAT_INTERVAL_MS } from "./config.js";
import { requireAuth } from "./credentials.js";

let sseEventId = 0;
/** @type {Array<{id: number, event: string, data: string}>} */
export const sseBuffer = [];
/** @type {Set<import("node:http").ServerResponse>} */
export const sseClients = new Set();

// Connect-time sync providers. Higher-level modules (sessions.js, codex.js)
// register generators that yield {event, data} entries to write to a newly
// connected client, so this module never has to import them (which would
// create an import cycle — they import pushSseEvent from here). Registration
// order is evaluation order: sessions.js evaluates before codex.js because
// codex.js imports sessions.js.
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
  if (lastIdHeader) {
    const lastId = parseInt(lastIdHeader, 10);
    if (!isNaN(lastId)) {
      for (const entry of sseBuffer) {
        if (entry.id > lastId) {
          res.write(formatSseMessage(entry));
        }
      }
    }
  }

  sseClients.add(res);
  log("info", `SSE client connected (total: ${sseClients.size})`);

  // Send current state so late-connecting clients see existing sessions and
  // pending Codex synthetic permissions (contributed by sessions.js/codex.js).
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
