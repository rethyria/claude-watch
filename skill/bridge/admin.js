// Operator admin surface (issue #72): LIST and REVOKE paired devices on the
// RUNNING bridge. The server owns the token set in memory and rewrites the
// store on every change, so device revocation must happen in-process — a
// separate CLI editing credentials.json would race the running bridge (which
// overwrites the file on the next pair/revoke).
//
// Loopback-only, mirroring /hooks/*: this is operator-on-machine trust, not a
// bearer-authed watch surface. A LAN peer must never be able to enumerate or
// disconnect paired devices. It is NOT part of the versioned /v1 client
// protocol — it is a server-local operator surface (see PROTOCOL.md "Admin
// surface"). The /v1 prefix fallback makes /v1/admin/... resolve here too;
// harmless, still loopback-gated.
import { jsonResponse, readBody, log, isLoopbackAddress } from "./util.js";
import { listDevices, revokeDevice, revokeAllDevices, reopenPairing } from "./credentials.js";
import { dropSseClientsForHashes, dropAllSseClients } from "./transport-sse.js";
import { PAIRING_CODE_TTL_MS } from "./config.js";

// Admin endpoints expose and mutate the credential store, so they carry the
// same operator-on-machine trust as /hooks/*: reject any non-loopback source
// before touching the store. The message is deliberately distinct from the
// hook gate's "Hooks are only accepted from localhost" (that string is frozen
// and asserted by endpoint-hardening.test.js) so an /admin 403 does not claim
// to be about hooks.
function requireLoopback(req, res) {
  const addr = req.socket?.remoteAddress;
  if (isLoopbackAddress(addr)) return true;
  log("warn", `Admin request rejected: non-loopback source ${addr || "unknown"}`);
  jsonResponse(res, 403, { error: "Admin endpoints are only accepted from localhost" });
  return false;
}

// POST /admin/pairing/open → open the single-use pairing window (issue #72
// follow-up). This is the operator-facing "initialise pairing" control: it does
// exactly what SIGUSR1 does (reopenPairing), but as a loopback curl instead of
// a signal to a pid you have to hunt for. The code-less Discover path needs
// ONLY the open window — it ignores the code — but the fresh code is returned
// for the Manual path (and so the operator sees it without grepping the log; a
// pairing code on a loopback-only surface is operator-privileged, not a leak).
// The window stays single-use: the next successful pair (code-less or
// code-bearing) relocks it, so opening it does not weaken the security model.
export function handleAdminPairingOpen(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  const code = reopenPairing();
  log("info", "Admin: pairing window opened (single-use) via POST /admin/pairing/open");
  return jsonResponse(res, 200, { ok: true, code, expiresInMs: PAIRING_CODE_TTL_MS });
}

// GET /admin/devices → { devices: [{ id, deviceName, createdAt, surface }] }.
// `id` is a SHORT hash prefix (listDevices), never a token or full hash.
export function handleAdminDevices(req, res) {
  if (req.method !== "GET") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  return jsonResponse(res, 200, { devices: listDevices() });
}

// POST /admin/devices/revoke
//   { id }        → revoke the device whose hash starts with `id`
//   { all: true } → revoke every device
export async function handleAdminRevoke(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  // readBody resolves the parsed JSON verbatim, so a body that is the JSON
  // literal `null` — or any JSON scalar/array — is NOT a plain object. Reading
  // `.all`/`.id` off `null` throws a TypeError, which the server dispatch turns
  // into a misleading 500 for what is really a malformed request. Normalize any
  // non-object body to empty so it falls through to the "missing or malformed
  // id" 400, exactly like a bodiless request.
  if (body === null || typeof body !== "object" || Array.isArray(body)) {
    body = {};
  }

  // Revoke every device: the graceful in-process equivalent of emptying the
  // store. Every prior token stops authenticating at once, and since no token
  // survives, every open SSE stream now belongs to a revoked device — drop
  // them all immediately. Does NOT reopen pairing (the operator still SIGUSR1s;
  // the emptied store fails closed to LOCKED on the next restart — see
  // revokeAllDevices / ARCHITECTURE.md).
  if (body.all === true) {
    const { revoked } = revokeAllDevices();
    const dropped = dropAllSseClients();
    log("info", `Admin: revoked all ${revoked} paired device(s); dropped ${dropped} live SSE stream(s)`);
    return jsonResponse(res, 200, { ok: true, revoked });
  }

  const result = revokeDevice(body.id);
  if (result.error === "invalid-id") {
    return jsonResponse(res, 400, {
      error: "Missing or malformed 'id' (expected a hex hash prefix; send { all: true } to revoke everything)",
    });
  }
  if (result.notFound) {
    return jsonResponse(res, 404, { error: "No paired device matches that id" });
  }
  if (result.ambiguous) {
    // 400, nothing removed — never guess which of several devices to disconnect.
    return jsonResponse(res, 400, {
      error: `Ambiguous id: ${result.count} devices match that prefix — use a longer prefix`,
    });
  }
  // Success: the device's token no longer authenticates. Drop its live SSE
  // stream now (tagged at connect time) instead of waiting for its next authed
  // request. `revoked` and the log carry the deviceName or the short id only —
  // never a token or hash (removedHash is used solely as the internal drop key).
  const dropped = dropSseClientsForHashes([result.removedHash]);
  log("info", `Admin: revoked device ${result.revoked}; dropped ${dropped} live SSE stream(s)`);
  return jsonResponse(res, 200, { ok: true, revoked: result.revoked });
}
