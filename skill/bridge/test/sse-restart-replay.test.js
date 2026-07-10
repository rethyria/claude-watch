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
  // persists and resends as Last-Event-ID on every reconnect.
  const sse1 = connectSse(bridge1.port, token);
  t.after(() => sse1.close());
  assert.equal(await sse1.statusCode(), 200);
  const posted1 = await request(bridge1.port, "POST", "/hooks/tool-output", {
    body: { tool_name: "Read", cwd: "/tmp/p", tool_output: "before restart" },
  });
  assert.equal(posted1.status, 200);
  const before = await sse1.waitFor(
    (e) => e.event === "tool-output" && e.parsed?.tool_output === "before restart",
  );
  assert.ok(Number.isFinite(before.id), "event carries a numeric id");
  sse1.close();

  await bridge1.stop();

  // The bridge restarts, and an event lands while the watch is still waiting
  // out its reconnect backoff — reachable only via Last-Event-ID replay.
  const bridge2 = await startBridge(t, { credentialsDir });
  const posted2 = await request(bridge2.port, "POST", "/hooks/tool-output", {
    body: { tool_name: "Write", cwd: "/tmp/p", tool_output: "after restart" },
  });
  assert.equal(posted2.status, 200);

  // The watch reconnects with its pre-restart cursor. With a reset id space
  // this replay stayed silent forever — the confirmed silent-gap bug.
  const sse2 = connectSse(bridge2.port, token, { lastEventId: before.id });
  t.after(() => sse2.close());
  assert.equal(await sse2.statusCode(), 200, "pre-restart token still authenticates");
  const after = await sse2.waitFor(
    (e) => e.event === "tool-output" && e.parsed?.tool_output === "after restart",
    15_000,
  );
  assert.ok(
    after.id > before.id,
    `post-restart id ${after.id} must stay above the pre-restart cursor ${before.id}`,
  );
});
