// Regression: SSE ids must be monotonic ACROSS bridge restarts. Per-device
// tokens survive a restart (credentials.json), and clients persist their
// Last-Event-ID replay cursor across process death — so a restarted bridge
// whose id counter reset to 0 replayed nothing (`entry.id > lastId` was never
// true) and also skipped the fresh-client terminal backlog (a Last-Event-ID
// header was present): the entire post-restart backlog was silently dropped,
// with no 401 to force re-onboarding. This test restarts the REAL bridge
// process (a genuinely fresh id space) — a same-process simulation whose ids
// only ever grow cannot catch this.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { startBridge, request, connectSse } from "./helpers.js";

// Register an ACP session (idempotent) and drive one prose turn through it,
// exactly as the fork's client tee does; the bridge flushes the chunk as one
// buffered `message` event at the turn end.
async function speak(bridge, sessionId, cwd, text) {
  const reg = await request(bridge.port, "POST", "/acp/register", {
    body: { connection: "conn-restart", sessionId, sdkSessionId: sessionId, cwd },
  });
  assert.equal(reg.status, 200);
  const update = (kind, payload) => request(bridge.port, "POST", "/acp/update", {
    body: { connection: "conn-restart", sessionId, kind, payload },
  });
  assert.equal((await update("turn", { phase: "start" })).status, 200);
  assert.equal((await update("session_update", {
    sessionId,
    update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text } },
  })).status, 200);
  assert.equal((await update("turn", { phase: "end" })).status, 200);
}

test("bridge restart: post-restart events replay past a pre-restart Last-Event-ID cursor", { timeout: 60_000 }, async (t) => {
  const credentialsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-sse-restart-"));
  t.after(() => fs.rmSync(credentialsDir, { recursive: true, force: true }));

  // Pair once; the per-device token survives restarts, so the client's
  // session (and its replay cursor) does too.
  const bridge1 = await startBridge(t, { credentialsDir });
  const pair = await request(bridge1.port, "POST", "/pair", { body: { code: bridge1.pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  // The watch sees an event and records its id — the cursor a real client
  // persists and resends as Last-Event-ID on every reconnect. The event
  // source is the product's: an ACP session the fork registered, speaking one
  // coalesced prose turn.
  const sse1 = connectSse(bridge1.port, token);
  t.after(() => sse1.close());
  assert.equal(await sse1.statusCode(), 200);
  await speak(bridge1, "acp-restart", "/tmp/p", "before restart");
  const before = await sse1.waitFor(
    (e) => e.event === "message" && e.parsed?.text === "before restart",
  );
  assert.ok(Number.isFinite(before.id), "event carries a numeric id");
  sse1.close();

  await bridge1.stop();

  // The bridge restarts, and an event lands while the watch is still waiting
  // out its reconnect backoff — reachable only via Last-Event-ID replay.
  const bridge2 = await startBridge(t, { credentialsDir });
  await speak(bridge2, "acp-restart", "/tmp/p", "after restart");

  // The watch reconnects with its pre-restart cursor. With a reset id space
  // this replay stayed silent forever — the confirmed silent-gap bug.
  const sse2 = connectSse(bridge2.port, token, { lastEventId: before.id });
  t.after(() => sse2.close());
  assert.equal(await sse2.statusCode(), 200, "pre-restart token still authenticates");
  const after = await sse2.waitFor(
    (e) => e.event === "message" && e.parsed?.text === "after restart",
    15_000,
  );
  assert.ok(
    after.id > before.id,
    `post-restart id ${after.id} must stay above the pre-restart cursor ${before.id}`,
  );
});
