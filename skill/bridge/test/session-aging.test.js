// Issue #65, black-box: the ACP-era half of the zombie ageing, driven through
// the real fork channel.
//
// In the ACP era most deaths ARE observed — an inbox drop ends every session
// bound to that fork — so the surviving zombie class is narrow and specific: a
// slot whose connection binding LEAKED, registered after its fork's inbox had
// already closed (or with no live inbox at all). The close handler that would
// have ended it has already run; nothing else ever will.
//
// The mirror obligation matters just as much: a session whose fork connection
// IS live must never be reaped, however long it has sat idle. That is the
// normal state of a Zed thread nobody has typed into since yesterday.
//
// The ageing windows are shortened through the environment here (test-only
// overrides); the production values are exercised with an injected clock in
// session-aging-unit.test.js.
import { test } from "node:test";
import assert from "node:assert/strict";
import { startBridge, request, connectSse } from "./helpers.js";

// A sweep every 200 ms, and "nothing hosts this" acted on at once — the real
// window only has to outlast a fork's reconnect backoff, which is not what is
// under test here.
function startAgingBridge(t) {
  return startBridge(t, {
    env: {
      CLAUDE_WATCH_SESSION_UNHOSTED_GRACE_MS: "1",
      CLAUDE_WATCH_SESSION_PRUNE_INTERVAL_MS: "200",
    },
  });
}

async function pair(bridge) {
  const res = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(res.status, 200);
  return res.body.token;
}

async function registerAcp(bridge, { connection, sessionId, cwd }) {
  const res = await request(bridge.port, "POST", "/acp/register", {
    body: { connection, sessionId, sdkSessionId: sessionId, cwd, active: false },
  });
  assert.equal(res.status, 200);
}

test("an ACP slot whose fork connection is gone is ENDED, and the next sync no longer lists it", { timeout: 60_000 }, async (t) => {
  const bridge = await startAgingBridge(t);
  const token = await pair(bridge);

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  // A register naming a connection that holds no inbox: the leak this ageing
  // exists for. Nothing in the ACP channel will ever end it.
  await registerAcp(bridge, { connection: "conn-never-connected", sessionId: "acp-leaked", cwd: "/tmp/aging-65-leaked" });

  const ended = await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-leaked" && e.parsed?.state === "ended");
  assert.equal(ended.parsed.reason, "host-gone", `the end names its evidence; got ${JSON.stringify(ended.parsed)}`);

  // Ended, not deleted — and the authoritative sync (#66) is what actually
  // takes the row off a client that was offline for the transition.
  const fresh = connectSse(bridge.port, token);
  t.after(() => fresh.close());
  assert.equal(await fresh.statusCode(), 200);
  const sync = await fresh.waitFor((e) => e.event === "session-sync");
  assert.equal(
    sync.parsed.sessions.some((s) => s.id === "acp-leaked"),
    false,
    "an aged-out slot is no longer listed as running",
  );
  fresh.close();
});

test("an ACP session idle for ages is NOT reaped while its fork still holds the connection", { timeout: 60_000 }, async (t) => {
  const bridge = await startAgingBridge(t);
  const token = await pair(bridge);

  // The fork's inbox: its liveness is the session's liveness, which is the
  // whole reason evidence outranks a timeout. Without this the session below
  // would be reaped on the very next sweep.
  const inbox = connectSse(bridge.port, undefined, { path: "/acp/inbox?connection=conn-live" });
  t.after(() => inbox.close());
  assert.equal(await inbox.statusCode(), 200);

  await registerAcp(bridge, { connection: "conn-live", sessionId: "acp-live", cwd: "/tmp/aging-65-live" });
  // ...and it has finished its turn: idle, exactly like a Zed thread nobody
  // has typed into since yesterday. Idle is not death.
  const turn = await request(bridge.port, "POST", "/acp/update", {
    body: { connection: "conn-live", sessionId: "acp-live", kind: "turn", payload: { phase: "end" } },
  });
  assert.equal(turn.status, 200);

  // Several sweeps later it is still there, still running, still idle.
  await new Promise((resolve) => setTimeout(resolve, 1_000));
  const status = await request(bridge.port, "GET", "/status", { token });
  const slot = status.body.sessions.find((s) => s.id === "acp-live");
  assert.ok(slot, "a session with a live host survives every sweep");
  assert.equal(slot.state, "running");
  assert.equal(slot.idle, true, "and it is honestly reported as idle, not ended");
});

test("the fork's own disconnect still ends its sessions — ageing is the backstop, not the mechanism", { timeout: 60_000 }, async (t) => {
  const bridge = await startAgingBridge(t);
  const token = await pair(bridge);

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  const inbox = connectSse(bridge.port, undefined, { path: "/acp/inbox?connection=conn-drops" });
  assert.equal(await inbox.statusCode(), 200);
  await registerAcp(bridge, { connection: "conn-drops", sessionId: "acp-dropped", cwd: "/tmp/aging-65-dropped" });
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-dropped" && e.parsed?.state === "running");

  // Zed quits. The observed death wins the race with the ageing, so the
  // reason a client sees names what actually happened.
  inbox.close();
  const ended = await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-dropped" && e.parsed?.state === "ended");
  assert.equal(ended.parsed.reason, "acp-fork-disconnected", "an OBSERVED death is reported as itself, never as ageing");
});
