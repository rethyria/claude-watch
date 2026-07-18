// GET /v1/usage (issue #57), black-box: the bridge proxies the OAuth usage
// endpoint on demand — reading Claude Code's access token, normalizing the
// upstream limits[] into labeled bars, and falling back to Claude Code's own
// cached snapshot (~/.claude.json → cachedUsageUtilization) when the API is
// unreachable. The upstream API is a tiny local node:http mock the bridge is
// pointed at via the test-only CLAUDE_WATCH_USAGE_* overrides; the
// credentials/cache files are fixtures in temp dirs so the real ~/.claude
// state is never touched.
//
// SECURITY: the fixture access token is a recognizable sentinel, and every
// test asserts it never appears in a response body or in the bridge's log.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { startBridge, request, tempDir } from "./helpers.js";

const FIXTURE_TOKEN = "fixture-oauth-access-token-DO-NOT-LEAK-9f3a";

// The verified upstream shape (see issue #57): kinds session / weekly_all /
// weekly_scoped, USED percent, snake_case resets_at, scoped entries naming
// their model. Percents are deliberately fractional to pin the rounding.
const API_LIMITS_UPSTREAM = [
  { kind: "session", percent: 41.6, resets_at: "2026-07-18T19:10:00Z" },
  { kind: "weekly_all", percent: 12, resets_at: "2026-07-24T00:00:00Z" },
  {
    kind: "weekly_scoped",
    percent: 3.4,
    resets_at: "2026-07-24T00:00:00Z",
    scope: { model: { display_name: "Fable", id: "claude-fable-5" } },
  },
];
const API_LIMITS_NORMALIZED = [
  { kind: "session", label: "5-hour", percent: 42, resetsAt: "2026-07-18T19:10:00Z" },
  { kind: "weekly_all", label: "weekly", percent: 12, resetsAt: "2026-07-24T00:00:00Z" },
  { kind: "weekly_scoped", label: "Fable", percent: 3, resetsAt: "2026-07-24T00:00:00Z" },
];

// A distinct limits set for the cache fixture, so a response proves WHICH
// source served it.
const CACHE_FETCHED_AT_MS = 1752850000000;
const CACHE_LIMITS_UPSTREAM = [
  { kind: "session", percent: 87.5, resets_at: "2026-07-18T21:00:00Z" },
  {
    kind: "weekly_scoped",
    percent: 55,
    resets_at: "2026-07-24T00:00:00Z",
    scope: { model: { display_name: "Fable" } },
  },
];
const CACHE_LIMITS_NORMALIZED = [
  { kind: "session", label: "5-hour", percent: 88, resetsAt: "2026-07-18T21:00:00Z" },
  { kind: "weekly_scoped", label: "Fable", percent: 55, resetsAt: "2026-07-24T00:00:00Z" },
];

function writeCredsFixture(t) {
  const file = path.join(tempDir(t, "claude-watch-usage-creds-"), ".credentials.json");
  fs.writeFileSync(file, JSON.stringify({ claudeAiOauth: { accessToken: FIXTURE_TOKEN } }));
  return file;
}

function writeCacheFixture(t) {
  const file = path.join(tempDir(t, "claude-watch-usage-cache-"), ".claude.json");
  fs.writeFileSync(file, JSON.stringify({
    cachedUsageUtilization: {
      fetchedAtMs: CACHE_FETCHED_AT_MS,
      utilization: { limits: CACHE_LIMITS_UPSTREAM },
    },
  }));
  return file;
}

// A path that exists in no filesystem the bridge can see.
function absentPath(t) {
  return path.join(tempDir(t, "claude-watch-usage-absent-"), "does-not-exist.json");
}

// Tiny local stand-in for api.anthropic.com. `handler` gets (req, res); the
// returned `seen` array records every request's url+headers for header
// assertions. closeAllConnections in cleanup so a deliberately-hanging
// handler cannot wedge the test process.
function startMockApi(t, handler) {
  const seen = [];
  const server = http.createServer((req, res) => {
    seen.push({ url: req.url, headers: req.headers });
    handler(req, res);
  });
  t.after(() => {
    server.closeAllConnections();
    server.close();
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      resolve({ seen, url: `http://127.0.0.1:${server.address().port}` });
    });
  });
}

async function pairedUsageBridge(t, env) {
  const bridge = await startBridge(t, { env });
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  return { bridge, token: pair.body.token };
}

// The invariant every scenario re-checks: the OAuth access token is used only
// on the upstream request header — never served, never logged.
function assertTokenNeverLeaks(resp, bridge) {
  assert.ok(
    !JSON.stringify(resp.body).includes(FIXTURE_TOKEN),
    "the OAuth token must never appear in a response body",
  );
  assert.ok(
    !bridge.output().includes(FIXTURE_TOKEN),
    "the OAuth token must never appear in the bridge log",
  );
}

test("api 200 → normalized limits with source 'api'; scoped label comes from display_name", { timeout: 60_000 }, async (t) => {
  const mock = await startMockApi(t, (req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ limits: API_LIMITS_UPSTREAM, five_hour: { ignored: true } }));
  });
  const { bridge, token } = await pairedUsageBridge(t, {
    CLAUDE_WATCH_USAGE_API_URL: mock.url,
    CLAUDE_WATCH_USAGE_CREDENTIALS_PATH: writeCredsFixture(t),
    CLAUDE_WATCH_USAGE_CACHE_PATH: absentPath(t),
  });

  // Authed exactly like every /v1 endpoint: no bearer token, no data.
  const unauthed = await request(bridge.port, "GET", "/v1/usage");
  assert.equal(unauthed.status, 401);
  assert.equal(unauthed.body.error, "Unauthorized");
  assert.equal(mock.seen.length, 0, "an unauthed request must never reach the upstream API");

  const resp = await request(bridge.port, "GET", "/v1/usage", { token });
  assert.equal(resp.status, 200);
  // Exact-shape assertion: normalized limits in upstream order, source "api",
  // and NO fetchedAtMs (that key is cache-only).
  assert.deepEqual(resp.body, { limits: API_LIMITS_NORMALIZED, source: "api" });

  // The upstream call carries the token + beta header the OAuth endpoint needs.
  assert.equal(mock.seen.length, 1, "one page-open, one upstream fetch — no polling");
  assert.equal(mock.seen[0].url, "/api/oauth/usage");
  assert.equal(mock.seen[0].headers["authorization"], `Bearer ${FIXTURE_TOKEN}`);
  assert.equal(mock.seen[0].headers["anthropic-beta"], "oauth-2025-04-20");

  assertTokenNeverLeaks(resp, bridge);
});

test("api 401 → cache fallback with source 'cache' + fetchedAtMs", { timeout: 60_000 }, async (t) => {
  const mock = await startMockApi(t, (req, res) => {
    res.writeHead(401, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: { type: "authentication_error" } }));
  });
  const { bridge, token } = await pairedUsageBridge(t, {
    CLAUDE_WATCH_USAGE_API_URL: mock.url,
    CLAUDE_WATCH_USAGE_CREDENTIALS_PATH: writeCredsFixture(t),
    CLAUDE_WATCH_USAGE_CACHE_PATH: writeCacheFixture(t),
  });

  const resp = await request(bridge.port, "GET", "/v1/usage", { token });
  assert.equal(resp.status, 200);
  assert.deepEqual(resp.body, {
    limits: CACHE_LIMITS_NORMALIZED,
    source: "cache",
    fetchedAtMs: CACHE_FETCHED_AT_MS,
  });
  assert.equal(mock.seen.length, 1, "the API was tried before falling back");
  assertTokenNeverLeaks(resp, bridge);
});

test("api timeout → cache fallback (the bounded fetch cannot pin the request)", { timeout: 60_000 }, async (t) => {
  const mock = await startMockApi(t, () => { /* never respond */ });
  const { bridge, token } = await pairedUsageBridge(t, {
    CLAUDE_WATCH_USAGE_API_URL: mock.url,
    CLAUDE_WATCH_USAGE_CREDENTIALS_PATH: writeCredsFixture(t),
    CLAUDE_WATCH_USAGE_CACHE_PATH: writeCacheFixture(t),
    CLAUDE_WATCH_USAGE_FETCH_TIMEOUT_MS: "300",
  });

  const resp = await request(bridge.port, "GET", "/v1/usage", { token });
  assert.equal(resp.status, 200);
  assert.equal(resp.body.source, "cache");
  assert.equal(resp.body.fetchedAtMs, CACHE_FETCHED_AT_MS);
  assert.deepEqual(resp.body.limits, CACHE_LIMITS_NORMALIZED);
  assertTokenNeverLeaks(resp, bridge);
});

test("missing credentials with a cache present → cache without touching the API", { timeout: 60_000 }, async (t) => {
  const mock = await startMockApi(t, (req, res) => {
    res.writeHead(200);
    res.end("{}");
  });
  const { bridge, token } = await pairedUsageBridge(t, {
    CLAUDE_WATCH_USAGE_API_URL: mock.url,
    CLAUDE_WATCH_USAGE_CREDENTIALS_PATH: absentPath(t),
    CLAUDE_WATCH_USAGE_CACHE_PATH: writeCacheFixture(t),
  });

  const resp = await request(bridge.port, "GET", "/v1/usage", { token });
  assert.equal(resp.status, 200);
  assert.equal(resp.body.source, "cache");
  assert.deepEqual(resp.body.limits, CACHE_LIMITS_NORMALIZED);
  assert.equal(mock.seen.length, 0, "no token, no upstream call");
  assertTokenNeverLeaks(resp, bridge);
});

test("no credentials and no cache → 503 with a clear error", { timeout: 60_000 }, async (t) => {
  const mock = await startMockApi(t, (req, res) => {
    res.writeHead(200);
    res.end("{}");
  });
  const { bridge, token } = await pairedUsageBridge(t, {
    CLAUDE_WATCH_USAGE_API_URL: mock.url,
    CLAUDE_WATCH_USAGE_CREDENTIALS_PATH: absentPath(t),
    CLAUDE_WATCH_USAGE_CACHE_PATH: absentPath(t),
  });

  const resp = await request(bridge.port, "GET", "/v1/usage", { token });
  assert.equal(resp.status, 503);
  assert.match(resp.body.error, /usage unavailable/);
  assert.equal(resp.body.limits, undefined, "an error payload carries no bars");
  assertTokenNeverLeaks(resp, bridge);
});
