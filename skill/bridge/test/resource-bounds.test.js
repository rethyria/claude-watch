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

test("never-answered permission expires with no decision and leaves nothing pending", { timeout: 60_000 }, async (t) => {
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

  // The prompt expires on its own — with NO decision, so the agent's own
  // dialog (Zed's) keeps the answer instead of recording a refusal (#63).
  await bridge.waitForOutput(/expired after 1\.5s unanswered/);

  // ...and the watch is told, so the card leaves the wrist. Before #63 the
  // bridge dropped the permission silently and the prompt stayed rendered
  // until the app was force-stopped.
  const cleared = await sse.waitFor(
    (e) => e.event === "permission-cleared" && e.parsed?.permissionId === permissionId,
  );
  assert.equal(cleared.parsed.reason, "expired");
  assert.ok(
    !inbox.events.some((e) => e.event === "permission-decision"),
    "expiry must send nothing down the fork's inbox — no decision was made",
  );

  // The pending permission was cleaned up: a late decision finds nothing.
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
