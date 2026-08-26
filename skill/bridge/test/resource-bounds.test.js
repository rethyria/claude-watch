// Resource-bound hardening, black-box: oversized request bodies, permission
// timeout cleanup, and ended-session pruning against the real bridge process.
// Timing-dependent paths use the CLAUDE_WATCH_* test-only overrides from
// config.js (via startBridge's env option) so minutes-long production
// timeouts run in seconds. In-process unit coverage for the same bounds
// lives in resource-bounds-unit.test.js.
import { test } from "node:test";
import assert from "node:assert/strict";
import { startBridge, request, connectSse } from "./helpers.js";

// POST a body larger than the 1 MiB readBody cap. The bridge answers 413 and
// destroys the socket; depending on flush-vs-reset timing the client sees
// either the 413 or a connection error, so both count as rejected.
async function postOversized(port, path, paddingBytes) {
  const body = JSON.stringify({
    cwd: "/tmp/resource-bounds-project",
    padding: "x".repeat(paddingBytes),
  });
  try {
    const res = await fetch(`http://127.0.0.1:${port}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
      signal: AbortSignal.timeout(15_000),
    });
    return res.status;
  } catch {
    return "destroyed";
  }
}

test("oversized POST bodies are rejected pre-auth and the bridge survives", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  // Both unauthenticated surfaces cap: the watch API (/pair) and the
  // loopback ACP uplink (/acp/*).
  const pairAttempt = await postOversized(port, "/pair", 2 * 1024 * 1024);
  assert.ok(
    pairAttempt === 413 || pairAttempt === "destroyed",
    `oversized /pair body must be rejected, got: ${pairAttempt}`,
  );
  const acpAttempt = await postOversized(port, "/acp/register", 2 * 1024 * 1024);
  assert.ok(
    acpAttempt === 413 || acpAttempt === "destroyed",
    `oversized /acp/register body must be rejected, got: ${acpAttempt}`,
  );

  // The bridge did not OOM/crash and still serves normal traffic on both
  // surfaces afterwards.
  assert.equal(bridge.proc.exitCode, null, "bridge process must survive oversized requests");
  const acpOk = await request(port, "POST", "/acp/register", {
    body: { connection: "conn-bounds", sessionId: "acp-bounds-ok", cwd: "/tmp/resource-bounds-project" },
  });
  assert.equal(acpOk.status, 200);
  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  assert.ok(pair.body.token, "pairing still works after oversized requests");
});

// This test used to pin the OPPOSITE: that an unanswered ACP prompt expired
// on the bridge's timer. That timer was a hook-era rule applied to a lane it
// never fitted, and it produced the reported bug — a question asked in Zed
// vanished from the wrist ~9.5 minutes later while Zed's form was still open
// and the fork still blocked, leaving the session showing its last activity
// (green/RUNNING) with nothing to answer. ACP cards now live exactly as long
// as their request: the fork retracts them on every exit, and its death drops
// the inbox, which cancels them. What follows pins BOTH halves — the card
// outlives the old window, and the fork's retraction still cleans it up.
test("an unanswered ACP permission outlives the expiry window, then the fork's retraction clears it", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "1500" },
  });
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  const sse = connectSse(port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  // The fork's inbox is held open so the (never-sent) decision frame would be
  // observable — expiry must send NOTHING down it.
  const inbox = connectSse(port, undefined, { path: "/acp/inbox?connection=conn-expiry" });
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);
  await request(port, "POST", "/acp/register", {
    body: { connection: "conn-expiry", sessionId: "acp-expiry", cwd: "/tmp/resource-bounds-project" },
  });

  // The agent hits a permission prompt (teed by the fork); the watch never
  // answers.
  const raised = await request(port, "POST", "/acp/update", {
    body: {
      connection: "conn-expiry",
      sessionId: "acp-expiry",
      kind: "permission",
      payload: {
        sessionId: "acp-expiry",
        toolCall: { toolCallId: "tc-expiry", title: "Bash", rawInput: { command: "rm -rf ./build" } },
        options: [
          { optionId: "allow_always", name: "Always Allow", kind: "allow_always" },
          { optionId: "allow", name: "Allow", kind: "allow_once" },
          { optionId: "reject", name: "Reject", kind: "reject_once" },
        ],
      },
    },
  });
  assert.equal(raised.status, 200);

  const promptEvent = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
  );
  const permissionId = promptEvent.parsed.permissionId;
  assert.ok(permissionId, "permission-request carries a permissionId");

  // Well past the configured 1.5s window, the card is STILL live: nothing was
  // cleared, and a reconnecting watch is still told about it by the
  // authoritative connect-time sync — the request it mirrors is still open.
  await new Promise((resolve) => setTimeout(resolve, 3_000));
  assert.ok(
    !sse.events.some((e) => e.event === "permission-cleared"),
    "an open request's card must not be retracted out from under the wrist",
  );
  const rejoin = connectSse(port, token);
  t.after(() => rejoin.close());
  assert.equal(await rejoin.statusCode(), 200);
  const sync = await rejoin.waitFor((e) => e.event === "permission-sync");
  assert.ok(
    sync.parsed.permissionIds.includes(permissionId),
    `the still-open prompt must survive the old expiry window; got ${JSON.stringify(sync.parsed)}`,
  );

  // Nothing was sent down the fork's inbox either: no decision was made, so
  // the agent's own dialog (Zed's) still owns the answer (#63).
  assert.ok(
    !inbox.events.some((e) => e.event === "permission-decision"),
    "an unanswered prompt must send nothing down the fork's inbox",
  );

  // The fork settles it elsewhere (the user answered in Zed) — THAT is what
  // retires the card now, and it leaves nothing pending: a late decision
  // finds nothing.
  const resolved = await request(port, "POST", "/acp/update", {
    body: {
      connection: "conn-expiry",
      sessionId: "acp-expiry",
      kind: "permission-resolved",
      payload: { sessionId: "acp-expiry", toolCallId: "tc-expiry" },
    },
  });
  assert.equal(resolved.status, 200);
  await sse.waitFor(
    (e) => e.event === "permission-cleared" && e.parsed?.permissionId === permissionId,
  );
  const late = await request(port, "POST", "/command", {
    token,
    body: { permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(late.status, 404);
});

test("ended sessions stay in snapshots for the grace period, then get pruned", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, {
    env: {
      CLAUDE_WATCH_SESSION_PRUNE_GRACE_MS: "1500",
      CLAUDE_WATCH_SESSION_PRUNE_INTERVAL_MS: "200",
    },
  });
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  // The fork registers a session, then deregisters it (Zed closed the thread):
  // the observed ending every ACP slot gets.
  const registered = await request(port, "POST", "/acp/register", {
    body: { connection: "conn-prune", sessionId: "acp-prune", cwd: "/tmp/prune-e2e-project" },
  });
  assert.equal(registered.status, 200);

  const running = await request(port, "GET", "/status", { token });
  const slot = running.body.sessions.find((s) => s.id === "acp-prune");
  assert.ok(slot, "registered session appears in the status snapshot");
  assert.equal(slot.state, "running");

  const ended = await request(port, "POST", "/acp/deregister", {
    body: { connection: "conn-prune", sessionId: "acp-prune", reason: "query-closed" },
  });
  assert.equal(ended.status, 200);

  // Grace period: the ended session is still visible right after it dies, so
  // clients observe the "ended" state before the slot disappears.
  const graceSnapshot = await request(port, "GET", "/status", { token });
  const endedSlot = graceSnapshot.body.sessions.find((s) => s.id === "acp-prune");
  assert.equal(endedSlot?.state, "ended", "ended session stays in snapshots during the grace period");

  // After the grace period the pruning interval removes it from snapshots.
  const deadline = Date.now() + 15_000;
  for (;;) {
    const snap = await request(port, "GET", "/status", { token });
    if (!snap.body.sessions.some((s) => s.id === "acp-prune")) break;
    assert.ok(Date.now() < deadline, "ended session must be pruned after the grace period");
    await new Promise((r) => setTimeout(r, 100).unref());
  }
  await bridge.waitForOutput(/Pruned ended session/);
});
