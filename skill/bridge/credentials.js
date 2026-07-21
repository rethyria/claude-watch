// Pairing and authentication state: the per-device token store (SHA-256
// hashes persisted to credentials.json), pairing-code generation/validation,
// the pairing lockout latch, requireAuth(), and bridge-level state.
// All mutable state is module-private; other modules go through the accessor
// functions below. Per-IP pairing rate limiting lives in rate-limit.js.
import crypto from "node:crypto";
import fs from "node:fs";
import { log } from "./util.js";
import {
  PAIRING_CODE_TTL_MS,
  CREDENTIALS_DIR,
  CREDENTIALS_FILE,
} from "./config.js";

// Token store: [{hash, deviceName?, createdAt, surface}], hash = SHA-256 hex
// of the bearer token. Plaintext tokens are returned to the client at pair
// time and never stored. Loaded lazily so unit tests that import this module
// don't read the real ~/.claude-watch unless they exercise auth.
let tokens = [];
let storeLoaded = false;

// Fail-closed corruption latch: true when credentials.json EXISTS but is
// unreadable or yields zero valid entries. A store that exists but cannot be
// trusted must lock the pairing surface (an attacker who can corrupt — not
// even read — the file must not be able to force a fresh pairing window);
// only a genuinely absent file keeps the historical fail-open first-run UX.
let storeCorrupt = false;

let pairingCode = null;
let pairingCodeExpiresAt = 0;

// Pairing lockout latch: once any device pairs successfully, the pairing
// surface locks until an explicit operator action (SIGUSR1 or --allow-pairing
// at startup) reopens it. server.js decides the initial state at startup.
let pairingOpen = true;

// Reopen latch: true while the current pairing window was opened by a runtime
// operator reopen (SIGUSR1) rather than the initial startup window. When a
// reopened window's code expires without a successful pair, the surface
// relocks instead of regenerating — an operator who reopens and forgets must
// not leave the surface open forever. The initial startup window keeps the
// regenerate-on-expiry behavior (first-run UX unchanged).
let pairingReopened = false;

// Bridge-level state: "idle" | "connected"
let bridgeState = "idle";

function sha256Hex(input) {
  return crypto.createHash("sha256").update(input, "utf-8").digest("hex");
}

function isValidEntry(entry) {
  return (
    entry &&
    typeof entry.hash === "string" &&
    /^[0-9a-f]{64}$/.test(entry.hash)
  );
}

function ensureStoreLoaded() {
  if (storeLoaded) return;
  storeLoaded = true;

  let raw;
  try {
    raw = fs.readFileSync(CREDENTIALS_FILE, "utf-8");
  } catch (err) {
    if (err.code === "ENOENT") return; // no store: historical fail-open first run
    storeCorrupt = true;
    log("error", `SECURITY: credentials file ${CREDENTIALS_FILE} exists but could not be read (${err.message}). Failing closed: pairing will be LOCKED. Restore or delete the file, or restart with --allow-pairing to pair a new device.`);
    return;
  }

  try {
    const parsed = JSON.parse(raw);
    const entries = Array.isArray(parsed?.tokens) ? parsed.tokens : null;
    if (entries === null) throw new Error("missing 'tokens' array");
    const valid = entries.filter(isValidEntry);
    if (valid.length === 0) {
      throw new Error(entries.length > 0 ? "no valid token entries" : "empty token list");
    }
    if (valid.length < entries.length) {
      log("warn", `Ignoring ${entries.length - valid.length} invalid entr(ies) in ${CREDENTIALS_FILE}`);
    }
    tokens = valid;
    bridgeState = "connected";
    log("info", `Loaded ${tokens.length} paired device credential(s) from ${CREDENTIALS_FILE}`);
  } catch (err) {
    tokens = [];
    storeCorrupt = true;
    log("error", `SECURITY: credentials file ${CREDENTIALS_FILE} exists but is invalid (${err.message}). Failing closed: pairing will be LOCKED. Restore or delete the file, or restart with --allow-pairing to pair a new device.`);
  }
}

// Atomic persist: write a 0600 temp file in the same directory, then rename
// over the real file so a crash mid-write can never truncate the store.
function persistStore(nextTokens) {
  fs.mkdirSync(CREDENTIALS_DIR, { recursive: true, mode: 0o700 });
  const tmpFile = `${CREDENTIALS_FILE}.tmp`;
  const payload = JSON.stringify({ version: 1, tokens: nextTokens }, null, 2) + "\n";
  fs.rmSync(tmpFile, { force: true }); // a stale tmp would keep its old mode
  fs.writeFileSync(tmpFile, payload, { mode: 0o600 });
  fs.chmodSync(tmpFile, 0o600); // writeFileSync's mode is ignored on reuse
  fs.renameSync(tmpFile, CREDENTIALS_FILE);
}

// Explicit startup load so server.js can decide the initial pairing lockout
// state (and log) before binding. Returns the number of stored credentials
// and whether an existing store file had to be rejected as corrupt (which
// must also lock the pairing surface — see storeCorrupt above).
export function loadTokenStore() {
  ensureStoreLoaded();
  return { count: tokens.length, corrupt: storeCorrupt };
}

export function hasTokens() {
  ensureStoreLoaded();
  return tokens.length > 0;
}

export function generatePairingCode() {
  const code = crypto.randomInt(0, 1_000_000).toString().padStart(6, "0");
  pairingCode = code;
  pairingCodeExpiresAt = Date.now() + PAIRING_CODE_TTL_MS;
  log("info", `Pairing code generated: ${code} (expires in 5 minutes)`);
  return code;
}

// Issue a fresh per-device token: the plaintext goes back to the client, only
// the SHA-256 hash is stored. The in-memory store is committed only after the
// persist succeeds, so a disk failure surfaces as a pair error instead of a
// token that silently vanishes on restart.
export function issueToken({ deviceName, surface } = {}) {
  ensureStoreLoaded();
  const token = crypto.randomBytes(32).toString("hex");
  const entry = {
    hash: sha256Hex(token),
    createdAt: new Date().toISOString(),
    surface: surface === "v1" ? "v1" : "legacy",
  };
  if (typeof deviceName === "string" && deviceName.length > 0) {
    entry.deviceName = deviceName.slice(0, 200);
  }
  const nextTokens = [...tokens, entry];
  persistStore(nextTokens);
  tokens = nextTokens;
  return token;
}

// --- Operator device admin (issue #72): list + revoke paired devices on the
// RUNNING bridge. The server holds the token set in memory and rewrites the
// store on change, so revocation has to happen in-process — a separate CLI
// editing credentials.json would race the running bridge, which overwrites the
// file on the next pair/revoke. These three are reached only through the
// loopback-gated /admin surface (admin.js), never by a watch client.

// Sanitized listing for the operator. `id` is a SHORT hash PREFIX — the first
// 12 hex of the SHA-256 hash — enough to disambiguate a handful of devices and
// to target a revoke, but NEVER the token and NEVER the full 64-hex hash (the
// full hash stays off the wire entirely; the prefix leaks strictly less, and
// the plaintext token is not even derivable from the full hash). The raw
// `hash` is deliberately absent from the returned object.
export function listDevices() {
  ensureStoreLoaded();
  return tokens.map((entry) => ({
    id: entry.hash.slice(0, 12),
    deviceName: entry.deviceName ?? null,
    createdAt: entry.createdAt ?? null,
    surface: entry.surface ?? null,
  }));
}

// Revoke exactly one device by hash-prefix. Returns a tagged result the admin
// handler maps to a status code:
//   {error:"invalid-id"}     → 400
//   {notFound:true}          → 404
//   {ambiguous:true,count}   → 400, NOTHING removed (never guess which device)
//   {revoked, removedHash}   → 200 (removedHash is INTERNAL — the SSE-drop key;
//                                   never serialized, never logged)
export function revokeDevice(id) {
  ensureStoreLoaded();
  // Reject empty/short/non-hex up front. hash.startsWith("") is true for EVERY
  // entry, so an unvalidated empty id would match-all and — with exactly one
  // paired device — silently revoke the only one. listDevices emits 12 hex;
  // accept >= 6 for operator convenience, lowercase hex only.
  if (typeof id !== "string" || !/^[0-9a-f]{6,64}$/.test(id)) {
    return { error: "invalid-id" };
  }
  const matches = tokens.filter((entry) => entry.hash.startsWith(id));
  if (matches.length === 0) return { notFound: true };
  if (matches.length > 1) return { ambiguous: true, count: matches.length };
  const [victim] = matches;
  // Persist BEFORE committing the in-memory swap (mirrors issueToken): a disk
  // failure throws here and leaves the token still authenticating, staying
  // consistent with what is actually on disk.
  const nextTokens = tokens.filter((entry) => entry !== victim);
  persistStore(nextTokens);
  tokens = nextTokens;
  // Zero devices left means nothing can authenticate — say so on /status
  // instead of lying "connected" (the pairing latch is untouched — see below).
  if (tokens.length === 0) bridgeState = "idle";
  return { revoked: victim.deviceName || id, removedHash: victim.hash };
}

// Revoke every device — the graceful in-process equivalent of emptying the
// store. Persists `{version:1, tokens:[]}` and clears the in-memory set, so
// every prior token stops authenticating immediately. Returns the removed
// count plus the removed hashes (INTERNAL — the SSE-drop keys).
//
// Deliberately does NOT touch the pairing lockout latch: revoke-all is not a
// reopen, so the operator still SIGUSR1s (or restarts --allow-pairing) to pair
// a new device. RESTART HAZARD, documented here and in ARCHITECTURE.md: a
// well-formed but EMPTY store is treated as CORRUPT by ensureStoreLoaded (an
// empty token list must never silently reopen pairing), so a bridge restarted
// after revoke-all comes up LOCKED and logs a SECURITY "empty token list" line
// — fail-closed-correct, but the "unreadable/invalid" wording overreads for a
// cleanly-emptied store. Re-pairing is the normal locked-store recovery
// (SIGUSR1 / --allow-pairing). This keeps the minimal blast radius: the frozen
// empty-store-is-corrupt invariant (pairing-refinements.test.js) is untouched.
export function revokeAllDevices() {
  ensureStoreLoaded();
  const removed = tokens.map((entry) => entry.hash);
  persistStore([]);
  tokens = [];
  bridgeState = "idle";
  return { revoked: removed.length, removed };
}

// The SHA-256 hash (64-hex) of the request's Bearer token, or null when no
// Bearer is present. Used ONLY to tag an authenticated SSE connection with its
// device hash (transport-sse.js) so a targeted revoke can drop that device's
// live stream immediately. A full hash is exactly what the admin surface must
// keep off the wire, so this value is internal only — never serialized, never
// logged. Callers must gate on requireAuth first: after auth passes, the value
// returned here equals the matching stored credential's hash.
export function presentedTokenHash(req) {
  const auth = req.headers["authorization"];
  if (!auth || !auth.startsWith("Bearer ")) return null;
  return sha256Hex(auth.slice(7));
}

export function requireAuth(req) {
  ensureStoreLoaded();
  const auth = req.headers["authorization"];
  if (!auth || !auth.startsWith("Bearer ")) return false;
  const presented = Buffer.from(sha256Hex(auth.slice(7)), "hex");
  // Compare against every stored hash without short-circuiting, using a
  // constant-time comparison on the hash buffers.
  let matched = false;
  for (const entry of tokens) {
    const stored = Buffer.from(entry.hash, "hex");
    if (stored.length === presented.length && crypto.timingSafeEqual(stored, presented)) {
      matched = true;
    }
  }
  return matched;
}

export function isPairingOpen() {
  return pairingOpen;
}

export function lockPairing() {
  pairingOpen = false;
  pairingReopened = false;
  clearPairingCode();
}

// Operator reopen (SIGUSR1): unlock the pairing surface and mint a fresh code
// with the normal TTL, logged exactly like startup. Marks the window as a
// reopen so that code expiry relocks instead of regenerating (see
// pairingReopened above).
export function reopenPairing() {
  pairingOpen = true;
  pairingReopened = true;
  return generatePairingCode();
}

export function isPairingReopened() {
  return pairingReopened;
}

export function isPairingCodeExpired() {
  return Date.now() > pairingCodeExpiresAt;
}

export function matchesPairingCode(code) {
  return code === pairingCode;
}

export function clearPairingCode() {
  pairingCode = null;
  pairingCodeExpiresAt = 0;
}

export function getBridgeState() {
  return bridgeState;
}

export function setBridgeState(state) {
  bridgeState = state;
}
