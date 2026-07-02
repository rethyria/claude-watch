// Pairing and authentication state: session token, pairing-code
// generation/validation, pairing rate limiting, and bridge-level state.
// All mutable state is module-private; other modules go through the accessor
// functions below.
import crypto from "node:crypto";
import { log } from "./util.js";
import {
  PAIRING_CODE_TTL_MS,
  RATE_LIMIT_WINDOW_MS,
  RATE_LIMIT_MAX_ATTEMPTS,
} from "./config.js";

let sessionToken = null;
let pairingCode = null;
let pairingCodeExpiresAt = 0;

// Rate limiting
let rateLimitAttempts = 0;
let rateLimitWindowStart = Date.now();

// Bridge-level state: "idle" | "connected"
let bridgeState = "idle";

export function generatePairingCode() {
  const code = crypto.randomInt(0, 1_000_000).toString().padStart(6, "0");
  pairingCode = code;
  pairingCodeExpiresAt = Date.now() + PAIRING_CODE_TTL_MS;
  log("info", `Pairing code generated: ${code} (expires in 5 minutes)`);
  return code;
}

export function generateSessionToken() {
  const token = crypto.randomBytes(32).toString("hex");
  sessionToken = token;
  return token;
}

export function isRateLimited() {
  const now = Date.now();
  if (now - rateLimitWindowStart > RATE_LIMIT_WINDOW_MS) {
    rateLimitAttempts = 0;
    rateLimitWindowStart = now;
  }
  return rateLimitAttempts >= RATE_LIMIT_MAX_ATTEMPTS;
}

export function recordRateLimitAttempt() {
  const now = Date.now();
  if (now - rateLimitWindowStart > RATE_LIMIT_WINDOW_MS) {
    rateLimitAttempts = 0;
    rateLimitWindowStart = now;
  }
  rateLimitAttempts++;
}

export function requireAuth(req) {
  const auth = req.headers["authorization"];
  if (!auth || !auth.startsWith("Bearer ")) return false;
  const token = auth.slice(7);
  return token === sessionToken && sessionToken !== null;
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
