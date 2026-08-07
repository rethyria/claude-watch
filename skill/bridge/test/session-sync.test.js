// Issue #66: the connect-time session sync is AUTHORITATIVE.
//
// The bug was reported from the wrist: two sessions showing green, one
// inactive since the previous day. The bridge process had been restarted in
// between, which clears the in-memory session map completely — the restarted
// bridge had no knowledge of that session at all, and therefore no way to emit
// its `ended` event. The client's session set only ever GREW (the sole removal
// path is an explicit `session` event with `state: "ended"`), so the ghost sat
// there forever, holding a ring segment and a list row, until the app was
// force-stopped.
//
// The fix is a closing frame that carries the whole truth. These tests are
// black-box against the real bridge process, exercising the exact code path a
// watch drives: connect to /events and read what the snapshot says.
import { test } from "node:test";
import assert from "node:assert/strict";
import { startBridge, request, connectSse } from "./helpers.js";

async function pair(bridge) {
  const res = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(res.status, 200);
  return res.body.token;
}

// Create an external session via a hook and return its bridge session id.
async function createSession(bridge, token, hookSessionId, cwd) {
  const res = await request(bridge.port, "POST", "/hooks/tool-output", {
    body: { session_id: hookSessionId, cwd, tool_name: "Read", tool_output: "hello" },
  });
  assert.equal(res.status, 200);
  const status = await request(bridge.port, "GET", "/status", { token });
  const slot = status.body.sessions.find((s) => s.cwd === cwd && s.state === "running");
  assert.ok(slot, `session for ${cwd} was created`);
  return slot.id;
}

// Open a NEW SSE client and return its whole connect-time snapshot, up to and
// including the closing `session-sync` frame.
async function snapshot(t, bridge, token) {
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  const sync = await sse.waitFor((e) => e.event === "session-sync");
  const frames = sse.events.slice(0, sse.events.indexOf(sync) + 1);
  sse.close();
  return { sync: sync.parsed, frames };
}

test("the connect-time snapshot closes with an authoritative session-sync listing every running slot", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);

  const a = await createSession(bridge, token, "cc-sync-a", "/tmp/sync-66-a");
  const b = await createSession(bridge, token, "cc-sync-b", "/tmp/sync-66-b");

  const { sync, frames } = await snapshot(t, bridge, token);
  assert.equal(sync.complete, true, "this bridge enumerates one in-memory map, so it always claims completeness");
  assert.deepEqual(
    sync.sessions.map((s) => s.id).sort(),
    [a, b].sort(),
    `the sync must list every running slot; got ${JSON.stringify(sync.sessions)}`,
  );

  // Ordering is the contract: the per-session re-sends come FIRST, so a client
  // refreshes before it prunes and no row ever blinks out and back. It is also
  // what makes an interrupted snapshot harmless — a connection that dies
  // mid-snapshot never delivers the closing frame, so it never prunes.
  const running = frames.filter((e) => e.event === "session" && e.parsed?.state === "running");
  assert.equal(running.length, 2, "both running sessions were re-sent");
  assert.ok(
    frames.indexOf(running[running.length - 1]) < frames.length - 1,
    "the session-sync frame must be the LAST of the session snapshot, not the first",
  );
});

test("a session the bridge no longer runs is absent from the next sync — the retraction the additive re-send could never make", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);

  const kept = await createSession(bridge, token, "cc-sync-kept", "/tmp/sync-66-kept");
  const gone = await createSession(bridge, token, "cc-sync-gone", "/tmp/sync-66-gone");
  assert.deepEqual((await snapshot(t, bridge, token)).sync.sessions.map((s) => s.id).sort(), [kept, gone].sort());

  // SessionEnd is the observed death; the point here is what the NEXT connect
  // says, because that is all a client that was offline for it ever gets.
  const ended = await request(bridge.port, "POST", "/hooks/session-end", {
    body: { session_id: "cc-sync-gone", cwd: "/tmp/sync-66-gone" },
  });
  assert.equal(ended.status, 200);

  const { sync } = await snapshot(t, bridge, token);
  assert.deepEqual(sync.sessions.map((s) => s.id), [kept], "the ended slot is retracted by absence");
  assert.equal(sync.complete, true);
});

// --- The sync's tri-state activity (issue #60) -------------------------------
// A `session` payload's `idle` is present-only-when-true, so on that event
// "working" and "the bridge has no idea" are the SAME absence — which is how a
// session idled hours before the watch existed rendered green on first sight.
// The sync is a description of current state, so it tells all three apart.

test("the sync says out loud whether each session is idle, working, or unobserved", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);

  // Idle: its last lifecycle signal was a turn end.
  const idle = await createSession(bridge, token, "cc-tri-idle", "/tmp/sync-60-idle");
  assert.equal((await request(bridge.port, "POST", "/hooks/stop", {
    body: { session_id: "cc-tri-idle", cwd: "/tmp/sync-60-idle" },
  })).status, 200);

  // Working: a completed tool use is the bridge-side markWorking signal, and
  // createSession already fired one.
  const working = await createSession(bridge, token, "cc-tri-working", "/tmp/sync-60-working");

  // Unobserved: an ACP session registered by a fork that reports no `active`
  // has never produced a turn signal at all. The bridge must not invent one.
  const unobserved = "acp-sync-60-unobserved";
  assert.equal((await request(bridge.port, "POST", "/acp/register", {
    body: { connection: "conn-sync-60", sessionId: unobserved, cwd: "/tmp/sync-60-acp" },
  })).status, 200);

  const { sync } = await snapshot(t, bridge, token);
  const byId = new Map(sync.sessions.map((s) => [s.id, s]));
  assert.equal(byId.get(idle).idle, true, "a turn-ended slot says idle: true");
  assert.equal(byId.get(working).idle, false, `a mid-turn slot says idle: false out loud; got ${JSON.stringify(byId.get(working))}`);
  assert.equal(
    Object.hasOwn(byId.get(unobserved), "idle"),
    false,
    `a slot with no turn signal must OMIT the verdict rather than guess; got ${JSON.stringify(byId.get(unobserved))}`,
  );

  // The per-session payload is unchanged: still present-only-when-true, so the
  // one-way latch every live event relies on keeps working (issue #60's rule).
  const { frames } = await snapshot(t, bridge, token);
  const workingPayload = frames.find((e) => e.event === "session" && e.parsed?.sessionId === working).parsed;
  assert.equal(
    Object.hasOwn(workingPayload, "idle"),
    false,
    "a working session's `session` payload still carries no flag at all",
  );
});

test("a bridge with nothing running still sends the sync — an empty set is the whole truth, not silence", { timeout: 60_000 }, async (t) => {
  // The reported bug verbatim: a restarted bridge knows about NOTHING, which is
  // precisely when a client must be told to drop what it is holding. Silence
  // here would leave every ghost in place.
  const bridge = await startBridge(t);
  const token = await pair(bridge);

  const { sync } = await snapshot(t, bridge, token);
  assert.deepEqual(sync.sessions, []);
  assert.equal(sync.complete, true, "an empty authoritative set is meaningful and must still claim completeness");
});
