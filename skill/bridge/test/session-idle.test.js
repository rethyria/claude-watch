// Issue #60: the turn-end `idle` flag on session payloads.
//
// The bug this suite exists for was found on hardware, not in a test: a
// `claypot` session whose last hook was a Stop three hours earlier rendered
// GREEN on a freshly-paired watch. The bridge has only `running` and `ended`
// — Stop deliberately does NOT end a session (it fires per TURN) — so the
// connect-time snapshot re-sent that long-idle slot as plain `running`, the
// watch created the never-before-seen session as WORKING, and the `stop` that
// would have corrected it had aged out of the SSE replay ring hours before the
// watch ever connected. Green on the at-a-glance screen, for a session doing
// nothing at all.
//
// Everything here is black-box against the real bridge process, driven exactly
// as installed Claude Code hooks and a watch client drive it. The load-bearing
// detail is HOW the snapshot is observed: a fresh SSE connection (no
// Last-Event-ID) replays no buffered events and gets only the terminal backlog
// (pty-output/tool-output) plus the connect-time sync — so any `session` event
// such a client sees is, by construction, the snapshot. That is the code path
// the live bug lived on.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
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

// Create an external session via a hook and return its bridge session id,
// without any SSE client ever having been connected.
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

// Poll the REST snapshot until `predicate` holds for the session, so a test can
// wait on state that changes when a child process exits (no event announces it
// — setting the flag deliberately never broadcasts).
async function waitForStatusEntry(bridge, token, sessionId, predicate, what) {
  const deadline = Date.now() + 10_000;
  let last;
  while (Date.now() < deadline) {
    last = await statusEntry(bridge, token, sessionId);
    if (predicate(last)) return last;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new assert.AssertionError({ message: `timed out waiting for ${what}; last entry ${JSON.stringify(last)}` });
}

test("THE BUG: a session idled BEFORE any client connects is announced idle in the connect-time snapshot", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = "/tmp/idle-60-before-connect";

  // A Claude Code instance works and then finishes its turn — all of it while
  // NOBODY is watching. This is the live scenario verbatim: by the time the
  // watch connects, the `stop` event is only reachable via the replay ring,
  // which a freshly-paired client does not read (and which ages out anyway).
  const sessionId = await createSession(bridge, token, "cc-idled-early", cwd);
  const stop = await request(bridge.port, "POST", "/hooks/stop", {
    body: { session_id: "cc-idled-early", cwd },
  });
  assert.equal(stop.status, 200);

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

  const sessionId = await createSession(bridge, token, "cc-busy", cwd);

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

test("Stop marks the slot idle; the next tool-output clears it again", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = "/tmp/idle-60-roundtrip";

  const sessionId = await createSession(bridge, token, "cc-turns", cwd);
  assert.equal(Object.hasOwn(await statusEntry(bridge, token, sessionId), "idle"), false, "born working");

  // Turn ends.
  await request(bridge.port, "POST", "/hooks/stop", { body: { session_id: "cc-turns", cwd } });
  assert.equal((await snapshotSessionEvent(t, bridge, token, sessionId)).idle, true);

  // Next turn starts: a completed tool use is the bridge-side markWorking
  // signal, and the flag must vanish from the payload — not go to `false`.
  await request(bridge.port, "POST", "/hooks/tool-output", {
    body: { session_id: "cc-turns", cwd, tool_name: "Bash", tool_output: "back to work" },
  });
  const working = await snapshotSessionEvent(t, bridge, token, sessionId);
  assert.equal(
    Object.hasOwn(working, "idle"),
    false,
    `output must clear the flag off the payload entirely; got ${JSON.stringify(working)}`,
  );
  assert.equal(Object.hasOwn(await statusEntry(bridge, token, sessionId), "idle"), false);

  // ...and a second Stop idles it again: the flag tracks the LAST signal, it
  // is not a one-way latch.
  await request(bridge.port, "POST", "/hooks/stop", { body: { session_id: "cc-turns", cwd } });
  assert.equal((await snapshotSessionEvent(t, bridge, token, sessionId)).idle, true);
});

test("TaskCompleted marks the slot idle too", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = "/tmp/idle-60-task-complete";

  const sessionId = await createSession(bridge, token, "cc-task", cwd);
  const done = await request(bridge.port, "POST", "/hooks/task-complete", {
    body: { session_id: "cc-task", cwd, summary: "finished" },
  });
  assert.equal(done.status, 200);

  // The watch reducer treats task-complete as markIdle exactly like stop; the
  // bridge flag has to agree, or a session that ended on a TaskCompleted
  // would still snapshot green.
  assert.equal((await snapshotSessionEvent(t, bridge, token, sessionId)).idle, true);
});

test("an idled session that a LIVE client is watching still gets the flag on its next session event", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = "/tmp/idle-60-live-client";

  // A client is connected the whole time here — it learns the turn ended from
  // the transient `stop` event. The flag still has to be correct on the wire,
  // because that same client will reconnect (the watch drops SSE constantly)
  // and its next snapshot must not un-idle the session.
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  await request(bridge.port, "POST", "/hooks/tool-output", {
    body: { session_id: "cc-live", cwd, tool_name: "Read", tool_output: "hi" },
  });
  const running = await sse.waitFor((e) => e.event === "session" && e.parsed?.cwd === cwd);
  const sessionId = running.parsed.sessionId;
  assert.equal(Object.hasOwn(running.parsed, "idle"), false, "the initial running event is working");

  await request(bridge.port, "POST", "/hooks/stop", { body: { session_id: "cc-live", cwd } });
  await sse.waitFor((e) => e.event === "stop" && e.parsed?.session_id === "cc-live");

  assert.equal((await snapshotSessionEvent(t, bridge, token, sessionId)).idle, true);
});

test("dictating at an external session is refused and does NOT flip the idle slot to working (issue #69)", { timeout: 60_000 }, async (t) => {
  // The bridge owns no PTY for an external hook-created session, so a dictated
  // command is refused (409) instead of run as a detached `claude -p --continue`
  // fork of the user's live session (issue #69). The refusal must not touch the
  // slot: an idle external session stays idle — no phantom "working" (which was
  // the old headless path's idle=false, itself issue #60's symptom).
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), "idle-69-dictated-"));
  t.after(() => { try { fs.rmSync(cwd, { recursive: true, force: true }); } catch { /* ignore */ } });

  const sessionId = await createSession(bridge, token, "cc-dictated", cwd);
  await request(bridge.port, "POST", "/hooks/stop", { body: { session_id: "cc-dictated", cwd } });
  assert.equal((await statusEntry(bridge, token, sessionId)).idle, true, "idled by the Stop");

  const resp = await request(bridge.port, "POST", "/command", {
    token,
    body: { sessionId, command: "do the thing\n" },
  });
  assert.equal(resp.status, 409, "dictation to an external session is refused, not forked");
  assert.equal(resp.body.external, true);
  assert.notEqual(resp.body.ok, true);

  // The refusal left the slot exactly as it was: still idle, never flipped to
  // "working".
  assert.equal(
    (await statusEntry(bridge, token, sessionId)).idle, true,
    "the refused dictation did not flip the slot to working",
  );
});
