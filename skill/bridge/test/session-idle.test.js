// Issue #60: the turn-end `idle` flag on session payloads.
//
// The bug this suite exists for was found on hardware, not in a test: a
// session whose last lifecycle signal was a turn end three hours earlier
// rendered GREEN on a freshly-paired watch. The bridge has only `running` and
// `ended` — a turn end deliberately does NOT end a session — so the
// connect-time snapshot re-sent that long-idle slot as plain `running`, the
// watch created the never-before-seen session as WORKING, and the event that
// would have corrected it had aged out of the SSE replay ring hours before the
// watch ever connected. Green on the at-a-glance screen, for a session doing
// nothing at all.
//
// Everything here is black-box against the real bridge process, driven exactly
// as the Zed-launched ACP fork and a watch client drive it. The load-bearing
// detail is HOW the snapshot is observed: a fresh SSE connection (no
// Last-Event-ID) replays no buffered events and gets only the terminal backlog
// (pty-output/tool-output) plus the connect-time sync — so any `session` event
// such a client sees is, by construction, the snapshot. That is the code path
// the live bug lived on.
import { test } from "node:test";
import assert from "node:assert/strict";
import { startBridge, request, connectSse } from "./helpers.js";

async function pair(bridge) {
  const res = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(res.status, 200);
  return res.body.token;
}

// Open a NEW SSE client and return the connect-time snapshot's `session`
// running payload for `sessionId` — i.e. what a watch pairing right now would
// be told about that session.
async function snapshotSessionEvent(t, bridge, token, sessionId) {
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  const event = await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.state === "running" && e.parsed?.sessionId === sessionId,
  );
  sse.close();
  return event.parsed;
}

// The /v1 REST snapshot's entry for the same session — kept in lockstep with
// the SSE payload, because a client that asked the other way round must not
// get a different answer.
async function statusEntry(bridge, token, sessionId) {
  const status = await request(bridge.port, "GET", "/status", { token });
  assert.equal(status.status, 200);
  return status.body.sessions.find((s) => s.id === sessionId);
}

// Register an ACP session (the fork's announce), without any SSE client ever
// having been connected. `active: true` reports a turn in flight.
async function createAcpSession(bridge, connection, sessionId, cwd, { active } = {}) {
  const res = await request(bridge.port, "POST", "/acp/register", {
    body: { connection, sessionId, sdkSessionId: sessionId, cwd, ...(active === undefined ? {} : { active }) },
  });
  assert.equal(res.status, 200);
  return sessionId;
}

// The fork's explicit turn boundary.
async function turn(bridge, connection, sessionId, phase) {
  const res = await request(bridge.port, "POST", "/acp/update", {
    body: { connection, sessionId, kind: "turn", payload: { phase } },
  });
  assert.equal(res.status, 200);
}

test("THE BUG: a session idled BEFORE any client connects is announced idle in the connect-time snapshot", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = "/tmp/idle-60-before-connect";

  // A session works and then finishes its turn — all of it while NOBODY is
  // watching. This is the live scenario verbatim: by the time the watch
  // connects, the turn-end event is only reachable via the replay ring, which
  // a freshly-paired client does not read (and which ages out anyway).
  const sessionId = await createAcpSession(bridge, "conn-idle-early", "acp-idled-early", cwd, { active: true });
  await turn(bridge, "conn-idle-early", sessionId, "start");
  await turn(bridge, "conn-idle-early", sessionId, "end");

  // Now the watch pairs and connects for the first time.
  const snapshot = await snapshotSessionEvent(t, bridge, token, sessionId);
  assert.equal(
    snapshot.idle,
    true,
    `the connect-time snapshot must tell a first-time client this session is idle; got ${JSON.stringify(snapshot)}`,
  );
  // The slot is still `running` — a finished turn is not a finished session,
  // and conflating them would kill live sessions on the watch.
  assert.equal(snapshot.state, "running");

  // Same answer over REST, or the two snapshots would disagree about the one
  // thing the whole screen is colour-coding.
  assert.equal((await statusEntry(bridge, token, sessionId)).idle, true);
});

test("a working session's snapshot omits `idle` entirely", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = "/tmp/idle-60-working";

  const sessionId = await createAcpSession(bridge, "conn-busy", "acp-busy", cwd, { active: true });

  // Present-only-when-true, exactly like `external`/`worktree`: a working
  // session carries no flag at all, so older clients see the payload they
  // always saw and `idle: false` never has to mean anything.
  const snapshot = await snapshotSessionEvent(t, bridge, token, sessionId);
  assert.equal(
    Object.hasOwn(snapshot, "idle"),
    false,
    `a working session must not carry the key at all; got ${JSON.stringify(snapshot)}`,
  );
  assert.equal(Object.hasOwn(await statusEntry(bridge, token, sessionId), "idle"), false);
});

test("a turn end marks the slot idle; the next turn start clears it again", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = "/tmp/idle-60-roundtrip";

  const sessionId = await createAcpSession(bridge, "conn-turns", "acp-turns", cwd, { active: true });
  assert.equal(Object.hasOwn(await statusEntry(bridge, token, sessionId), "idle"), false, "born working");

  // Turn ends.
  await turn(bridge, "conn-turns", sessionId, "end");
  assert.equal((await snapshotSessionEvent(t, bridge, token, sessionId)).idle, true);

  // Next turn starts: the flag must vanish from the payload — not go to
  // `false`.
  await turn(bridge, "conn-turns", sessionId, "start");
  const working = await snapshotSessionEvent(t, bridge, token, sessionId);
  assert.equal(
    Object.hasOwn(working, "idle"),
    false,
    `a turn start must clear the flag off the payload entirely; got ${JSON.stringify(working)}`,
  );
  assert.equal(Object.hasOwn(await statusEntry(bridge, token, sessionId), "idle"), false);

  // ...and a second turn end idles it again: the flag tracks the LAST signal,
  // it is not a one-way latch.
  await turn(bridge, "conn-turns", sessionId, "end");
  assert.equal((await snapshotSessionEvent(t, bridge, token, sessionId)).idle, true);
});

test("dictating at an unreachable ACP session is refused and does NOT flip the idle slot to working (issue #69)", { timeout: 60_000 }, async (t) => {
  // The bridge owns no input channel into an ACP session whose fork inbox is
  // gone, so a dictated command is refused (502) instead of run as a detached
  // `claude -p --continue` fork of the user's live session (issue #69/#81).
  // The refusal must not touch the slot: an idle session stays idle — no
  // phantom "working" (which was the old headless path's idle=false, itself
  // issue #60's symptom).
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = "/tmp/idle-69-dictated";

  // Registered but its fork never held an inbox: unreachable for delivery.
  const sessionId = await createAcpSession(bridge, "conn-dictated", "acp-dictated", cwd, { active: true });
  await turn(bridge, "conn-dictated", sessionId, "end");
  assert.equal((await statusEntry(bridge, token, sessionId)).idle, true, "idled by the turn end");

  const resp = await request(bridge.port, "POST", "/command", {
    token,
    body: { sessionId, command: "do the thing\n" },
  });
  assert.equal(resp.status, 502, "dictation to an unreachable ACP session is refused, not forked");
  assert.notEqual(resp.body.ok, true);

  // The refusal left the slot exactly as it was: still idle, never flipped to
  // "working".
  assert.equal(
    (await statusEntry(bridge, token, sessionId)).idle, true,
    "the refused dictation did not flip the slot to working",
  );
});
