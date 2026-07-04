// Per-IP pairing rate limiter. Each remote address gets its own fixed window
// of RATE_LIMIT_MAX_ATTEMPTS per RATE_LIMIT_WINDOW_MS, so one source
// exhausting its attempts cannot 429 a different source (the old single
// global counter let 5 attacker requests lock everyone out for 5 minutes).
// Expired windows are pruned on every call, so the map cannot grow unbounded.
// `now` is injectable for tests; production callers omit it.
import { RATE_LIMIT_WINDOW_MS, RATE_LIMIT_MAX_ATTEMPTS } from "./config.js";

/** @type {Map<string, {count: number, windowStart: number}>} */
const attemptsByIp = new Map();

function pruneExpired(now) {
  for (const [ip, entry] of attemptsByIp) {
    if (now - entry.windowStart > RATE_LIMIT_WINDOW_MS) {
      attemptsByIp.delete(ip);
    }
  }
}

export function isRateLimited(ip, now = Date.now()) {
  pruneExpired(now);
  const entry = attemptsByIp.get(ip);
  return entry !== undefined && entry.count >= RATE_LIMIT_MAX_ATTEMPTS;
}

export function recordRateLimitAttempt(ip, now = Date.now()) {
  pruneExpired(now);
  const entry = attemptsByIp.get(ip);
  if (entry) {
    entry.count++;
  } else {
    attemptsByIp.set(ip, { count: 1, windowStart: now });
  }
}

// Test hook: reset all windows.
export function resetRateLimiter() {
  attemptsByIp.clear();
}
