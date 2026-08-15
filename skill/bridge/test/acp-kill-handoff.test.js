// A wrist kill racing a cross-fork handoff (issue #125): the kill's close
// frame goes to the OLD fork — sessionConnection names it for the whole
// handoff window — and that fork's deregister is consumed by the #89 release
// machinery as the migration, which settles only pendingAcpReleases and never
// ends the session. The kill waiter therefore starved to ACP_CLOSE_TIMEOUT_MS
// and answered a 504 blaming the adapter while the session lived on under the
// new fork. The kill is the user's intent against the SESSION, not against
// whichever fork hosted it at the tap, so it must FOLLOW the session: once the
// handoff settles and the new fork owns it, the close is re-sent down the new
// inbox and the kill settles on the real ending, exactly like any other #88
// close. Two fake inboxes stand in for the two Zed windows; both orderings of
// the race (kill-then-register, register-then-kill) converge on the same
// swallow, so both are pinned, plus the inbox-close release short-circuit (the
// old fork dying mid-release) that the issue names alongside the handoff
// branch.
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

/** Stand up the racing pair: session live on `fork-old`, a watch SSE watching
 *  it, and `fork-new` connected and ready to claim it. */
async function setupRace(t, bridge, token, sessionId, cwd) {
  const oldFork = connectInbox(t, bridge, "fork-old");
  assert.equal(await oldFork.statusCode(), 200);
  assert.equal((await registerAcp(bridge, { connection: "fork-old", sessionId, cwd })).status, 200);

  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === sessionId && e.parsed?.state === "running");

  const newFork = connectInbox(t, bridge, "fork-new");
  assert.equal(await newFork.statusCode(), 200);
  return { oldFork, newFork, sse };
}

/** The convergence every ordering must reach: the close frame lands on the
 *  NEW fork, whose own teardown deregister — the only honest ack — settles the
 *  kill as done, ends the slot, and the wrist hears exactly one ending. */
async function assertKillFollowsSession(bridge, token, sessionId, newFork, sse, killPromise) {
  const forwarded = await newFork.waitFor((e) => e.event === "close" && e.parsed?.sessionId === sessionId);
  assert.equal(forwarded.parsed.reason, "watch-kill", "the re-routed frame still carries the kill's own reason");

  await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "fork-new", sessionId, reason: "query-closed" },
  });

  const resp = await killPromise;
  assert.equal(resp.status, 200, "the kill settles on the migrated copy's real ending, not a starved 504");
  assert.equal(resp.body.ok, true);
  assert.equal(resp.body.kind, "acp");

  await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.sessionId === sessionId && e.parsed?.state === "ended",
  );
  assert.equal((await slotOf(bridge, token, sessionId)).state, "ended", "the session really died — no living-on-migrated lie");
  assert.equal(endedEventsFor(sse, sessionId).length, 1, "one kill, one ending — the handoff itself leaks none");
}

test("a kill in flight when the handoff starts follows the session to the new fork", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_ACP_CLOSE_TIMEOUT_MS: "3000" } });
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-kill-handoff-1-");
  const { oldFork, newFork, sse } = await setupRace(t, bridge, token, "race-1", cwd);

  // The wrist taps Kill first: the close frame goes to the old fork, which is
  // still the owner of record.
  const killPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { kill: true, sessionId: "race-1" },
  });
  await oldFork.waitFor((e) => e.event === "close" && e.parsed?.reason === "watch-kill");

  // The other Zed window opens the same thread: its fork registers the live
  // session, and the release asks the old fork to tear its copy down.
  const regPromise = registerAcp(bridge, { connection: "fork-new", sessionId: "race-1", cwd });
  await oldFork.waitFor((e) => e.event === "close" && e.parsed?.reason === "superseded");

  // The old fork's teardown deregister is consumed as the handoff — the very
  // branch that used to swallow the kill.
  const dereg = await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "fork-old", sessionId: "race-1", reason: "query-closed" },
  });
  assert.equal(dereg.body.handoff, true);
  assert.equal((await regPromise).status, 200);

  await assertKillFollowsSession(bridge, token, "race-1", newFork, sse, killPromise);
});

test("a kill arriving mid-release follows the session too (the other ordering)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_ACP_CLOSE_TIMEOUT_MS: "3000" } });
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-kill-handoff-2-");
  const { oldFork, newFork, sse } = await setupRace(t, bridge, token, "race-2", cwd);

  // The registration goes first; the release is in flight once the superseded
  // close frame is out.
  const regPromise = registerAcp(bridge, { connection: "fork-new", sessionId: "race-2", cwd });
  await oldFork.waitFor((e) => e.event === "close" && e.parsed?.reason === "superseded");

  // Now the tap lands: sessionConnection still names the old fork for the
  // whole handoff window, so the kill's frame goes there and its deregister
  // below is consumed as the handoff.
  const killPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { kill: true, sessionId: "race-2" },
  });
  await oldFork.waitFor((e) => e.event === "close" && e.parsed?.reason === "watch-kill");

  const dereg = await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "fork-old", sessionId: "race-2", reason: "query-closed" },
  });
  assert.equal(dereg.body.handoff, true);
  assert.equal((await regPromise).status, 200);

  await assertKillFollowsSession(bridge, token, "race-2", newFork, sse, killPromise);
});

test("the old fork dying mid-release (the inbox-close short-circuit) does not swallow the kill either", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_ACP_CLOSE_TIMEOUT_MS: "3000" } });
  const token = await pair(bridge);
  const cwd = tempDir(t, "acp-kill-handoff-3-");
  const { oldFork, newFork, sse } = await setupRace(t, bridge, token, "race-3", cwd);

  const killPromise = request(bridge.port, "POST", "/v1/command", {
    token,
    body: { kill: true, sessionId: "race-3" },
  });
  await oldFork.waitFor((e) => e.event === "close" && e.parsed?.reason === "watch-kill");

  const regPromise = registerAcp(bridge, { connection: "fork-new", sessionId: "race-3", cwd });
  await oldFork.waitFor((e) => e.event === "close" && e.parsed?.reason === "superseded");

  // That Zed window quits while tearing down: the inbox close is consumed as
  // the release (the copy died with its fork), the migration completes, and
  // the kill must still follow the session rather than starve.
  oldFork.close();
  assert.equal((await regPromise).status, 200);

  await assertKillFollowsSession(bridge, token, "race-3", newFork, sse, killPromise);
});
