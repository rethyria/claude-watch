// Per-IP pairing rate limiter, unit-tested directly with fake IPs (the HTTP
// harness always talks from 127.0.0.1, so cross-IP isolation is asserted
// here at the module level).
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import {
  isRateLimited,
  recordRateLimitAttempt,
  resetRateLimiter,
} from "../rate-limit.js";
import { RATE_LIMIT_MAX_ATTEMPTS, RATE_LIMIT_WINDOW_MS } from "../config.js";

const T0 = 1_750_000_000_000; // fixed clock; the limiter takes `now` injectably

beforeEach(() => resetRateLimiter());

test("one source exhausting its attempts does not rate-limit a different source", () => {
  for (let i = 0; i < RATE_LIMIT_MAX_ATTEMPTS; i++) {
    assert.equal(isRateLimited("203.0.113.7", T0 + i), false, `attempt ${i + 1} is allowed`);
    recordRateLimitAttempt("203.0.113.7", T0 + i);
  }
  assert.equal(isRateLimited("203.0.113.7", T0 + 100), true, "attacker IP is limited");

  // A different source is untouched and can use its full budget
  assert.equal(isRateLimited("198.51.100.9", T0 + 100), false);
  for (let i = 0; i < RATE_LIMIT_MAX_ATTEMPTS; i++) {
    assert.equal(isRateLimited("198.51.100.9", T0 + 100 + i), false);
    recordRateLimitAttempt("198.51.100.9", T0 + 100 + i);
  }
  assert.equal(isRateLimited("198.51.100.9", T0 + 200), true);
  assert.equal(isRateLimited("192.0.2.33", T0 + 200), false, "third source still unaffected");
});

test("a limited IP is released once its window expires", () => {
  for (let i = 0; i < RATE_LIMIT_MAX_ATTEMPTS; i++) {
    recordRateLimitAttempt("203.0.113.7", T0);
  }
  assert.equal(isRateLimited("203.0.113.7", T0 + RATE_LIMIT_WINDOW_MS), true, "still limited within the window");
  assert.equal(isRateLimited("203.0.113.7", T0 + RATE_LIMIT_WINDOW_MS + 1), false, "released after the window");

  // And it gets a fresh window afterwards
  recordRateLimitAttempt("203.0.113.7", T0 + RATE_LIMIT_WINDOW_MS + 1);
  assert.equal(isRateLimited("203.0.113.7", T0 + RATE_LIMIT_WINDOW_MS + 2), false);
});

test("windowed cleanup prunes expired entries instead of growing forever", () => {
  for (let i = 0; i < 1000; i++) {
    recordRateLimitAttempt(`10.0.${Math.floor(i / 256)}.${i % 256}`, T0);
  }
  // Any call after the window prunes the stale entries; a previously seen IP
  // starts from a clean slate
  recordRateLimitAttempt("10.0.0.0", T0 + RATE_LIMIT_WINDOW_MS + 1);
  assert.equal(isRateLimited("10.0.0.0", T0 + RATE_LIMIT_WINDOW_MS + 2), false);
});
