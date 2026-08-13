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
import fs from "node:fs";
import path from "node:path";
import { startBridge, request, connectSse, tempDir } from "./helpers.js";

async function pair(bridge) {
  const res = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(res.status, 200);
  return res.body.token;
}

// Create a session the way the product creates one — an ACP register from
// the Zed-launched fork — and return its (caller-chosen) session id.
// `active: true` reports a turn in flight, seeding the working verdict.
async function createSession(bridge, sessionId, cwd, { active } = {}) {
  const res = await request(bridge.port, "POST", "/acp/register", {
    body: {
      connection: `conn-${sessionId}`, sessionId, sdkSessionId: sessionId, cwd,
      ...(active === undefined ? {} : { active }),
    },
  });
  assert.equal(res.status, 200);
  return sessionId;
}

// The Codex lane is driven by a file scanner on its own interval, so its state
// arrives when it arrives: poll /status (which omits `idle` unless true, the
// same present-only-when-true rule the session payload follows) until the slot
// exists and its verdict matches.
async function waitForStatusIdle(bridge, token, sessionId, wantIdle, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  let seen = null;
  for (;;) {
    const status = await request(bridge.port, "GET", "/status", { token });
    seen = status.body.sessions.find((s) => s.id === sessionId) ?? null;
    if (seen && (seen.idle === true) === wantIdle) return seen;
    if (Date.now() > deadline) {
      assert.fail(`session ${sessionId} never reached idle=${wantIdle}; last seen ${JSON.stringify(seen)}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
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

  const a = await createSession(bridge, "acp-sync-a", "/tmp/sync-66-a");
  const b = await createSession(bridge, "acp-sync-b", "/tmp/sync-66-b");

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

  const kept = await createSession(bridge, "acp-sync-kept", "/tmp/sync-66-kept");
  const gone = await createSession(bridge, "acp-sync-gone", "/tmp/sync-66-gone");
  assert.deepEqual((await snapshot(t, bridge, token)).sync.sessions.map((s) => s.id).sort(), [kept, gone].sort());

  // The fork's deregister is the observed death; the point here is what the
  // NEXT connect says, because that is all a client that was offline for it
  // ever gets.
  const ended = await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "conn-acp-sync-gone", sessionId: "acp-sync-gone", reason: "query-closed" },
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
  const idle = await createSession(bridge, "acp-tri-idle", "/tmp/sync-60-idle", { active: true });
  assert.equal((await request(bridge.port, "POST", "/acp/update", {
    body: { connection: "conn-acp-tri-idle", sessionId: idle, kind: "turn", payload: { phase: "end" } },
  })).status, 200);

  // Working: the fork reports a turn in flight.
  const working = await createSession(bridge, "acp-tri-working", "/tmp/sync-60-working", { active: true });

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

test("a PTY session producing output says `idle: false` out loud, never an omitted verdict", { timeout: 60_000 }, async (t) => {
  // The omitted verdict means "no turn signal EVER observed", and clients paint
  // that grey — so a slot that is genuinely working must never fall into it.
  // A bridge-owned PTY is the kind most at risk: nothing in that lane calls
  // markSessionWorking, so its flag is written by the stdout handler alone,
  // and a slot that had never been idled used to leave it unset forever. The
  // wrist paid for it on every reconnect: a session mid-long-command greyed
  // out and its elapsed clock restarted from zero at the next byte.
  const binDir = tempDir(t, "claude-watch-sync-60-bin-");
  const bin = path.join(binDir, "codex");
  fs.writeFileSync(bin, "#!/bin/sh\necho SYNC-60-WORKING\nexec cat\n", { mode: 0o755 });
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_CODEX_BIN: bin } });
  const token = await pair(bridge);

  // Codex keeps the PTY path — claude spawns are born in Zed-land (#86).
  const live = connectSse(bridge.port, token);
  t.after(() => live.close());
  assert.equal(await live.statusCode(), 200);

  const spawned = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { spawn: "codex", cwd: tempDir(t, "claude-watch-sync-60-project-") },
  });
  assert.equal(spawned.status, 200, JSON.stringify(spawned.body));
  const sessionId = spawned.body.sessionId;

  // Wait for the first byte rather than racing the stub's echo: the assertion
  // is about what the flag says once work has been observed.
  await live.waitFor((e) => e.event === "pty-output" && e.parsed?.sessionId === sessionId, 30_000);

  const { sync } = await snapshot(t, bridge, token);
  const entry = sync.sessions.find((s) => s.id === sessionId);
  assert.ok(entry, `the running PTY slot is listed; got ${JSON.stringify(sync.sessions)}`);
  assert.equal(
    entry.idle,
    false,
    `a PTY slot that has produced output must say idle: false; got ${JSON.stringify(entry)}`,
  );
});

test("a Codex session's verdict tracks its turns — working while it writes, idle once the task completes", { timeout: 60_000 }, async (t) => {
  // The other lane with no markSessionWorking of its own: a Codex slot is born
  // and refreshed by the session-file scanner, so before this its flag was
  // never written at all and every sync omitted the verdict — a session
  // mid-exec painted grey on the wrist. Both directions matter: saying `false`
  // for a session that finished its turn hours ago would be the same lie
  // inverted, and would WAKE it (restarting its elapsed clock) on every
  // reconnect.
  const home = tempDir(t, "claude-watch-sync-60-codex-home-");
  const rolloutDir = path.join(home, ".codex", "sessions", "2026", "08", "07");
  fs.mkdirSync(rolloutDir, { recursive: true });
  const rollout = path.join(rolloutDir, "rollout-sync-60.jsonl");
  const codexId = "cdx-sync-60";
  const append = (obj) => fs.appendFileSync(rollout, `${JSON.stringify(obj)}\n`);
  append({ type: "session_meta", payload: { id: codexId, cwd: home, timestamp: new Date().toISOString() } });

  const bridge = await startBridge(t, { env: { HOME: home } });
  const token = await pair(bridge);
  await waitForStatusIdle(bridge, token, codexId, false);

  const working = (await snapshot(t, bridge, token)).sync.sessions.find((s) => s.id === codexId);
  assert.equal(
    working.idle,
    false,
    `a Codex slot the scanner just watched write must say idle: false; got ${JSON.stringify(working)}`,
  );

  // Codex's turn end. The client folds the `task-complete` frame into markIdle,
  // so the slot's own flag has to move with it or the snapshot contradicts the
  // stream it closes.
  append({ type: "event_msg", payload: { type: "task_complete" } });
  await waitForStatusIdle(bridge, token, codexId, true);
  assert.equal((await snapshot(t, bridge, token)).sync.sessions.find((s) => s.id === codexId).idle, true);

  // ...and the next turn's tool output lowers it again, through the one funnel
  // every Codex `tool-output` push goes down.
  append({ type: "response_item", payload: { type: "function_call", call_id: "call-60", name: "exec_command", arguments: JSON.stringify({ cmd: "ls" }) } });
  append({ type: "response_item", payload: { type: "function_call_output", call_id: "call-60", output: "README.md" } });
  await waitForStatusIdle(bridge, token, codexId, false);
  assert.equal((await snapshot(t, bridge, token)).sync.sessions.find((s) => s.id === codexId).idle, false);
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
