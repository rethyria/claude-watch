// The bridge-mediated release (issue #89): a LIVE ACP session re-registered
// from a DIFFERENT Zed window's fork is torn down in the OLD fork before the
// new registration goes live. Two fake inboxes stand in for the two forks —
// the cross-process dual-writer no in-process check can see — and every test
// pins the wrist's view too: the session MIGRATES, it never dies, so no
// `ended` event may leak while it changes hosts.
import { test } from "node:test";
import assert from "node:assert/strict";
import { startBridge, request, tempDir, connectSse } from "./helpers.js";

async function pair(bridge) {
  const res = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(res.status, 200);
  return res.body.token;
}

function connectInbox(t, bridge, connectionId) {
  const inbox = connectSse(bridge.port, undefined, { path: `/acp/inbox?connection=${connectionId}` });
  t.after(() => inbox.close());
  return inbox;
}

function registerAcp(bridge, { connection, sessionId, cwd }) {
  return request(bridge.port, "POST", "/acp/register", {
    body: { connection, sessionId, sdkSessionId: sessionId, cwd },
  });
}

async function slotOf(bridge, token, sessionId) {
  const status = await request(bridge.port, "GET", "/status", { token });
  assert.equal(status.status, 200);
  return status.body.sessions.find((s) => s.id === sessionId);
}

function endedEventsFor(sse, sessionId) {
  return sse.events.filter(
    (e) => e.event === "session" && e.parsed?.sessionId === sessionId && e.parsed?.state === "ended",
  );
}

/** Assert an in-flight promise has NOT settled after `ms`. Slowness makes this
 *  pass, never fail — only the bridge answering EARLY (the ordering bug being
 *  pinned) can trip it. */
async function assertStillHeld(promise, ms, message) {
  const winner = await Promise.race([
    promise.then(() => "settled"),
    new Promise((r) => setTimeout(r, ms, "held")),
  ]);
  assert.equal(winner, "held", message);
}

/** Route a dictation and assert the inject frame lands on `winner`'s inbox —
 *  the observable proof of which fork owns the session after a migration. */
async function assertDictationRoutesTo(bridge, token, sessionId, winner, text) {
  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { sessionId, command: `${text}\n` },
  });
  assert.equal(resp.status, 200);
  const frame = await winner.waitFor((e) => e.event === "inject" && e.parsed?.text === text);
  assert.equal(frame.parsed.sessionId, sessionId);
}

test("a live session re-registered from a new fork is released from the old one first", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-release-happy-");

  const oldFork = connectInbox(t, bridge, "fork-old");
  assert.equal(await oldFork.statusCode(), 200);
  assert.equal((await registerAcp(bridge, { connection: "fork-old", sessionId: "mig-1", cwd })).status, 200);

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "mig-1" && e.parsed?.state === "running");

  const newFork = connectInbox(t, bridge, "fork-new");
  assert.equal(await newFork.statusCode(), 200);

  // The other window's fork registers the SAME live session: the old fork must
  // be told to tear its copy down, exactly the #88 close frame the adapter's
  // teardownSession already services.
  const regPromise = registerAcp(bridge, { connection: "fork-new", sessionId: "mig-1", cwd });
  const frame = await oldFork.waitFor((e) => e.event === "close");
  assert.equal(frame.parsed.sessionId, "mig-1");
  assert.equal(frame.parsed.reason, "superseded");

  // Release-then-register ordering: the registration does not go live while
  // the old copy still runs.
  await assertStillHeld(regPromise, 300, "the new registration must wait for the old fork's release");
  const midFlight = await slotOf(bridge, token, "mig-1");
  assert.equal(midFlight.state, "running", "the slot never leaves running during the handoff");

  // The old fork services the close; its deregister is the HANDOFF.
  const dereg = await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "fork-old", sessionId: "mig-1", reason: "query-closed" },
  });
  assert.equal(dereg.status, 200);
  assert.equal(dereg.body.handoff, true, "the deregister was consumed as the handoff, not an ending");

  const reg = await regPromise;
  assert.equal(reg.status, 200);

  // The session now lives under the new fork: dictation lands on ITS inbox.
  await assertDictationRoutesTo(bridge, token, "mig-1", newFork, "carry on where we were");
  assert.equal(
    oldFork.events.find((e) => e.event === "inject"),
    undefined,
    "the released fork gets nothing further",
  );

  // The wrist saw one continuous session: never an ended event, still running.
  assert.equal(endedEventsFor(sse, "mig-1").length, 0, "a migration must not leak an ended event");
  assert.equal((await slotOf(bridge, token, "mig-1")).state, "running");
});

test("an old fork that never services the release is outwaited, then the registration proceeds", { timeout: 60_000 }, async (t) => {
  // The stale-adapter (or wedged-fork) case: the close frame is dropped
  // silently. Holding the migration hostage would keep BOTH queries writing,
  // so the bounded timeout lets the new registration through — and the old
  // fork's LATE deregister must not kill the migrated session.
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_ACP_CLOSE_TIMEOUT_MS: "400" } });
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-release-deaf-");

  const oldFork = connectInbox(t, bridge, "fork-deaf");
  assert.equal(await oldFork.statusCode(), 200);
  assert.equal((await registerAcp(bridge, { connection: "fork-deaf", sessionId: "mig-2", cwd })).status, 200);

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "mig-2" && e.parsed?.state === "running");

  const newFork = connectInbox(t, bridge, "fork-next");
  assert.equal(await newFork.statusCode(), 200);
  const reg = await registerAcp(bridge, { connection: "fork-next", sessionId: "mig-2", cwd });
  assert.equal(reg.status, 200, "the timeout releases the registration, not refuses it");

  // The old fork's slow teardown finally lands (the #88 unbounded interrupt):
  // it no longer owns the session, so this is a stale echo, not an ending.
  const late = await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "fork-deaf", sessionId: "mig-2", reason: "query-closed" },
  });
  assert.equal(late.status, 200);
  assert.equal(late.body.stale, true, "a late deregister from the superseded fork is named stale");

  assert.equal((await slotOf(bridge, token, "mig-2")).state, "running", "the migrated session survives the echo");
  await assertDictationRoutesTo(bridge, token, "mig-2", newFork, "still here");
  assert.equal(endedEventsFor(sse, "mig-2").length, 0, "no ended event on the timeout path either");
});

test("the old fork dying mid-release completes the migration (its death IS the release)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-release-forkdeath-");

  const oldFork = connectInbox(t, bridge, "fork-dying");
  assert.equal(await oldFork.statusCode(), 200);
  assert.equal((await registerAcp(bridge, { connection: "fork-dying", sessionId: "mig-3", cwd })).status, 200);

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "mig-3" && e.parsed?.state === "running");

  const newFork = connectInbox(t, bridge, "fork-heir");
  assert.equal(await newFork.statusCode(), 200);
  const started = Date.now();
  const regPromise = registerAcp(bridge, { connection: "fork-heir", sessionId: "mig-3", cwd });
  await oldFork.waitFor((e) => e.event === "close");
  oldFork.close(); // that Zed window quit while tearing down

  const reg = await regPromise;
  assert.equal(reg.status, 200);
  assert.ok(Date.now() - started < 5000, "settled by the fork's death, not by the timeout");

  // A fork death ordinarily ends its sessions with an SSE `ended` — but this
  // one was mid-handoff, so the wrist must see the migration, not a flap.
  assert.equal((await slotOf(bridge, token, "mig-3")).state, "running");
  await assertDictationRoutesTo(bridge, token, "mig-3", newFork, "adopted");
  assert.equal(endedEventsFor(sse, "mig-3").length, 0, "no ended/revived flap for a mid-handoff fork death");
});

test("the incoming fork's updates are held behind the handoff, never interleaved with the teardown", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-release-serialize-");

  const oldFork = connectInbox(t, bridge, "fork-loud");
  assert.equal(await oldFork.statusCode(), 200);
  assert.equal((await registerAcp(bridge, { connection: "fork-loud", sessionId: "mig-4", cwd })).status, 200);

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "mig-4" && e.parsed?.state === "running");

  const newFork = connectInbox(t, bridge, "fork-eager");
  assert.equal(await newFork.statusCode(), 200);
  const regPromise = registerAcp(bridge, { connection: "fork-eager", sessionId: "mig-4", cwd });
  await oldFork.waitFor((e) => e.event === "close");

  // The new fork's resumed query starts a turn immediately (its posts are
  // fire-and-forget — it does not wait for its register to be answered).
  const updatePromise = request(bridge.port, "POST", "/acp/update", {
    body: { connection: "fork-eager", sessionId: "mig-4", kind: "turn", payload: { phase: "start" } },
  });
  await assertStillHeld(updatePromise, 300, "the incoming fork's update must queue behind the handoff");
  assert.equal(
    sse.events.find((e) => e.event === "session" && e.parsed?.sessionId === "mig-4" && e.parsed?.idle === false),
    undefined,
    "no working announce reaches the wrist while the old copy is still going down",
  );

  // The OLD fork's death rattle flows untouched: the cancelled turn's end…
  const rattle = await request(bridge.port, "POST", "/acp/update", {
    body: { connection: "fork-loud", sessionId: "mig-4", kind: "turn", payload: { phase: "end", stopReason: "cancelled" } },
  });
  assert.equal(rattle.status, 200);
  // …then its deregister hands the session over, releasing the queue.
  await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "fork-loud", sessionId: "mig-4", reason: "query-closed" },
  });

  assert.equal((await regPromise).status, 200);
  assert.equal((await updatePromise).status, 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "mig-4" && e.parsed?.idle === false);
  assert.equal(endedEventsFor(sse, "mig-4").length, 0);
});

test("a fork re-announcing its OWN session triggers no release (the reconnect replay, #68)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-release-replay-");

  const fork = connectInbox(t, bridge, "fork-replay");
  assert.equal(await fork.statusCode(), 200);
  assert.equal((await registerAcp(bridge, { connection: "fork-replay", sessionId: "mig-5", cwd })).status, 200);

  // The adapter re-POSTs register verbatim after an inbox reconnect. Same
  // connection = same fork = no dual writer: the reconcile path, untouched.
  const replay = await registerAcp(bridge, { connection: "fork-replay", sessionId: "mig-5", cwd });
  assert.equal(replay.status, 200);

  const frame = await fork.waitFor((e) => e.event === "close", 800).catch(() => null);
  assert.equal(frame, null, "a same-connection replay must never close its own session");
  assert.equal((await slotOf(bridge, token, "mig-5")).state, "running");
});
