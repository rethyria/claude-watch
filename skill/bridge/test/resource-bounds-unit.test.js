// Resource-bound hardening, in-process: the pieces that are impractical to
// drive over a real socket. SSE backpressure eviction needs a client whose
// writableLength is pinned high (a real loopback socket drains instantly),
// and pruning/timeout cutoffs are asserted deterministically against the
// exported maps. Black-box coverage lives in resource-bounds.test.js.
//
// Env overrides must be set before any bridge module loads (config.js reads
// them once at evaluation), hence the dynamic imports inside the tests —
// static imports would hoist above these assignments. node --test runs each
// test file in its own process, so the overrides leak nowhere else.
process.env.CLAUDE_WATCH_PERMISSION_TIMEOUT_MS = "100";

import { test, after } from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Never let a bridge module near the real ~/.claude-watch, even lazily.
const credsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-unit-creds-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = credsDir;
after(() => {
  try { fs.rmSync(credsDir, { recursive: true, force: true }); } catch { /* ignore */ }
});

function fakeRequest(headers = {}) {
  const req = new EventEmitter();
  req.headers = headers;
  req.destroyed = false;
  req.destroy = () => { req.destroyed = true; };
  return req;
}

function fakeResponse() {
  return {
    headersSent: false,
    statusCode: null,
    body: null,
    writeHead(status) {
      this.headersSent = true;
      this.statusCode = status;
    },
    end(payload, cb) {
      this.body = payload;
      if (cb) cb();
    },
  };
}

test("readBody rejects a streamed body over the cap with 413 and destroys the request", async () => {
  const { readBody, MAX_REQUEST_BODY_BYTES } = await import("../util.js");
  const req = fakeRequest();
  const res = fakeResponse();
  const pending = readBody(req, res);

  const chunk = Buffer.alloc(64 * 1024, 0x78);
  for (let sent = 0; sent <= MAX_REQUEST_BODY_BYTES; sent += chunk.length) {
    req.emit("data", chunk);
  }

  await assert.rejects(pending, (err) => err.tooLarge === true);
  assert.equal(req.destroyed, true, "oversized request must be destroyed");
  assert.equal(res.statusCode, 413, "413 must be sent while headers are still writable");
  // Late chunks after the rejection are ignored rather than buffered.
  req.emit("data", chunk);
  req.emit("end");
});

test("readBody rejects an oversized declared Content-Length before buffering anything", async () => {
  const { readBody, MAX_REQUEST_BODY_BYTES } = await import("../util.js");
  const req = fakeRequest({ "content-length": String(MAX_REQUEST_BODY_BYTES + 1) });
  const res = fakeResponse();

  await assert.rejects(readBody(req, res), (err) => err.tooLarge === true);
  assert.equal(req.destroyed, true);
  assert.equal(res.statusCode, 413);
});

test("pushSseEvent destroys clients whose write buffer exceeds the bound", async () => {
  const { pushSseEvent, sseClients } = await import("../transport-sse.js");
  const { SSE_MAX_BUFFERED_BYTES } = await import("../config.js");

  const makeClient = (writableLength) => ({
    writableLength,
    destroyed: false,
    write() { return false; }, // a destroyed response buffers instead of throwing
    destroy() { this.destroyed = true; },
  });
  const stalled = makeClient(SSE_MAX_BUFFERED_BYTES + 1);
  const atBound = makeClient(SSE_MAX_BUFFERED_BYTES);
  const healthy = makeClient(0);
  sseClients.add(stalled);
  sseClients.add(atBound);
  sseClients.add(healthy);

  try {
    pushSseEvent("unit-test", { n: 1 });
    assert.equal(sseClients.has(stalled), false, "stalled client must be evicted");
    assert.equal(stalled.destroyed, true, "stalled client's connection must be destroyed");
    assert.equal(sseClients.has(atBound), true, "client exactly at the bound survives");
    assert.equal(atBound.destroyed, false);
    assert.equal(sseClients.has(healthy), true, "healthy client is untouched");
    assert.equal(healthy.destroyed, false);
  } finally {
    sseClients.clear();
  }
});

test("pruneEndedSessions removes ended sessions only after the grace period", async () => {
  const { sessions, pruneEndedSessions, getSessionsSnapshot } = await import("../sessions.js");
  const { SESSION_PRUNE_GRACE_MS } = await import("../config.js");

  const now = Date.now();
  const slot = (id, state, extra = {}) => ({
    id,
    agent: "claude",
    cwd: "/tmp/prune-unit",
    folderName: "prune-unit",
    ptyProcess: null,
    state,
    createdAt: now - 10 * SESSION_PRUNE_GRACE_MS,
    ...extra,
  });

  try {
    sessions.set("aged-out", slot("aged-out", "ended", { endedAt: now - SESSION_PRUNE_GRACE_MS - 1 }));
    sessions.set("in-grace", slot("in-grace", "ended", { endedAt: now - 1000 }));
    sessions.set("no-endedAt", slot("no-endedAt", "ended")); // pre-endedAt slot: ages out via createdAt
    sessions.set("running", slot("running", "running"));

    pruneEndedSessions(now);
    assert.equal(sessions.has("aged-out"), false, "session past the grace period is pruned");
    assert.equal(sessions.has("no-endedAt"), false, "endedAt-less session falls back to createdAt");
    assert.equal(sessions.has("running"), true, "running sessions are never pruned");

    // Within the grace period the ended session still appears in snapshots.
    const inGrace = getSessionsSnapshot().find((s) => s.id === "in-grace");
    assert.equal(inGrace?.state, "ended", "ended session stays visible during the grace period");

    pruneEndedSessions(now + SESSION_PRUNE_GRACE_MS);
    assert.equal(sessions.has("in-grace"), false, "session is pruned once its grace period elapses");
    assert.equal(sessions.has("running"), true);
  } finally {
    sessions.clear();
  }
});

test("permission expiry returns no decision, announces itself, and clears the stored suggestion body", async () => {
  const { PERMISSION_TIMEOUT_MS } = await import("../config.js");
  assert.equal(PERMISSION_TIMEOUT_MS, 100, "test-only env override must reach config.js");

  const { waitForPermission, pendingPermissions, pendingPermissionBodies } =
    await import("../permissions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  // hooks.js stores the suggestions before blocking on waitForPermission.
  pendingPermissionBodies.set("perm-timeout-1", [{ type: "rule", value: "Bash(rm:*)" }]);

  const decision = await waitForPermission("perm-timeout-1");
  // NOT a deny: a fabricated deny cancels the dialog the user still has on
  // screen and writes a `reject` they never chose (issue #63).
  assert.equal(decision.noDecision, true);
  assert.equal(decision.behavior, undefined, "expiry must never mint a behavior");
  assert.equal(decision.reason, "expired");
  assert.equal(pendingPermissions.has("perm-timeout-1"), false);
  assert.equal(
    pendingPermissionBodies.has("perm-timeout-1"),
    false,
    "expired permission must not leave an orphaned suggestion body",
  );
  // The silent half of #63: the bridge used to drop the permission without
  // telling anyone, so the watch rendered the card until the app was
  // force-stopped. Every non-answer exit must announce itself.
  const cleared = sseBuffer.filter(
    (e) => e.event === "permission-cleared" && JSON.parse(e.data).permissionId === "perm-timeout-1",
  );
  assert.equal(cleared.length, 1, "expiry must push exactly one permission-cleared");
  assert.equal(JSON.parse(cleared[0].data).reason, "expired");
});

test("tool correlation records are bounded and never leak an index entry", async () => {
  // The PreToolUse map added for #63 is fed by EVERY tool call, not just gated
  // ones — production burns ~9000 of them per 48h — so an unbounded map would
  // be a slow leak on a long-lived bridge.
  const {
    notePreToolUse,
    claimToolUseId,
    waitForPermission,
    resolvePermission,
    permissionsByToolUseId,
  } = await import("../permissions.js");

  // Cap: far more distinct fingerprints than PRE_TOOL_USE_MAX (256). The
  // oldest are dropped, so the earliest call can no longer be claimed while a
  // recent one still can.
  for (let i = 0; i < 400; i++) {
    notePreToolUse({ tool_name: "Bash", tool_input: { command: `cmd-${i}` }, tool_use_id: `tu-${i}` });
  }
  assert.equal(
    claimToolUseId({ tool_name: "Bash", tool_input: { command: "cmd-0" } }),
    null,
    "the oldest record must have been evicted by the cap",
  );
  assert.equal(
    claimToolUseId({ tool_name: "Bash", tool_input: { command: "cmd-399" } }),
    "tu-399",
    "a recent record must still correlate",
  );
  // Consuming: one PreToolUse backs at most one permission, so a second prompt
  // with the same fingerprint cannot inherit a stale id.
  assert.equal(
    claimToolUseId({ tool_name: "Bash", tool_input: { command: "cmd-399" } }),
    null,
    "claiming must consume the record",
  );

  // Key order must not defeat the fingerprint: Claude Code re-serializes hook
  // bodies between PreToolUse and PermissionRequest.
  notePreToolUse({ tool_name: "Bash", tool_input: { a: 1, b: 2 }, tool_use_id: "tu-order" });
  assert.equal(
    claimToolUseId({ tool_name: "Bash", tool_input: { b: 2, a: 1 } }),
    "tu-order",
    "fingerprinting must be independent of JSON key order",
  );

  // Index leak: a watch-answered permission must release its reverse-index
  // entry, or a later PostToolUse would chase a dead permissionId forever.
  notePreToolUse({ tool_name: "Bash", tool_input: { command: "answered" }, tool_use_id: "tu-answered" });
  const toolUseId = claimToolUseId({ tool_name: "Bash", tool_input: { command: "answered" } });
  const decision = waitForPermission("perm-indexed", { toolUseId });
  assert.equal(permissionsByToolUseId.get("tu-answered"), "perm-indexed");
  resolvePermission("perm-indexed", { behavior: "allow" });
  await decision;
  assert.equal(
    permissionsByToolUseId.has("tu-answered"),
    false,
    "answering must release the correlation index entry",
  );
});
