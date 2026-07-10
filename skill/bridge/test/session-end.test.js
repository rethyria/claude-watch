// Black-box coverage of session_id attribution and the SessionEnd lifecycle
// against the real bridge process, driven exactly like installed Claude Code
// hooks: external sessions are keyed by the hook payload's session_id, end on
// /hooks/session-end (never on /hooks/stop, which fires per turn), and ended
// externals get pruned. The installer must wire the SessionEnd hook up.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { startBridge, request, connectSse } from "./helpers.js";

const execFileAsync = promisify(execFile);

async function pairAndConnect(t, bridge) {
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  const sse = connectSse(bridge.port, pair.body.token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  return { sse, token: pair.body.token };
}

test("two external sessions in the same cwd stay separate; events route by session_id", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { sse } = await pairAndConnect(t, bridge);
  const cwd = "/tmp/e2e-same-cwd";

  // Two Claude Code instances in the same directory post hook events.
  await request(bridge.port, "POST", "/hooks/tool-output", {
    body: { session_id: "cc-instance-a", cwd, tool_name: "Read", tool_output: "from A" },
  });
  const eventA = await sse.waitFor((e) => e.event === "tool-output" && e.parsed?.tool_output === "from A");
  assert.ok(eventA.parsed.sessionId, "tool-output is attributed to a session");

  await request(bridge.port, "POST", "/hooks/tool-output", {
    body: { session_id: "cc-instance-b", cwd, tool_name: "Read", tool_output: "from B" },
  });
  const eventB = await sse.waitFor((e) => e.event === "tool-output" && e.parsed?.tool_output === "from B");
  assert.ok(eventB.parsed.sessionId);
  assert.notEqual(
    eventB.parsed.sessionId,
    eventA.parsed.sessionId,
    "a second instance in the same cwd must get its own session, not A's",
  );

  // Later events for instance A route back to A's session.
  await request(bridge.port, "POST", "/hooks/tool-output", {
    body: { session_id: "cc-instance-a", cwd, tool_name: "Read", tool_output: "A again" },
  });
  const eventA2 = await sse.waitFor((e) => e.event === "tool-output" && e.parsed?.tool_output === "A again");
  assert.equal(eventA2.parsed.sessionId, eventA.parsed.sessionId, "session_id keeps routing to the same session");
});

test("SessionEnd ends the external session and it is later pruned; Stop does not end it", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, {
    env: {
      CLAUDE_WATCH_SESSION_PRUNE_GRACE_MS: "300",
      CLAUDE_WATCH_SESSION_PRUNE_INTERVAL_MS: "100",
    },
  });
  const { sse, token } = await pairAndConnect(t, bridge);
  const cwd = "/tmp/e2e-session-end";

  // A hook event auto-creates the external session.
  await request(bridge.port, "POST", "/hooks/tool-output", {
    body: { session_id: "cc-ender", cwd, tool_name: "Bash", tool_output: "hi" },
  });
  const running = await sse.waitFor((e) => e.event === "session" && e.parsed?.state === "running" && e.parsed?.cwd === cwd);
  const sessionId = running.parsed.sessionId;
  assert.ok(sessionId, "external session announced over SSE");

  // Stop fires at the end of every turn and must NOT end the session.
  const stopped = await request(bridge.port, "POST", "/hooks/stop", { body: { session_id: "cc-ender", cwd } });
  assert.equal(stopped.status, 200);
  await sse.waitFor((e) => e.event === "stop" && e.parsed?.session_id === "cc-ender");
  let status = await request(bridge.port, "GET", "/status", { token });
  let snapshot = status.body.sessions.find((s) => s.id === sessionId);
  assert.equal(snapshot?.state, "running", "Stop must not end the session");
  assert.equal(
    sse.events.some((e) => e.event === "session" && e.parsed?.state === "ended" && e.parsed?.sessionId === sessionId),
    false,
    "no session-ended event after Stop",
  );

  // SessionEnd for an unrelated session leaves this one alone (and creates nothing).
  const unrelated = await request(bridge.port, "POST", "/hooks/session-end", {
    body: { session_id: "cc-somebody-else", cwd: "/tmp/e2e-elsewhere" },
  });
  assert.equal(unrelated.status, 200);
  status = await request(bridge.port, "GET", "/status", { token });
  assert.equal(status.body.sessions.find((s) => s.id === sessionId)?.state, "running");
  assert.equal(
    status.body.sessions.some((s) => s.cwd === "/tmp/e2e-elsewhere"),
    false,
    "SessionEnd must never auto-create a session",
  );

  // SessionEnd for THIS session transitions it to ended...
  const ended = await request(bridge.port, "POST", "/hooks/session-end", { body: { session_id: "cc-ender", cwd } });
  assert.equal(ended.status, 200);
  const endedEvent = await sse.waitFor(
    (e) => e.event === "session" && e.parsed?.state === "ended" && e.parsed?.sessionId === sessionId,
  );
  assert.equal(endedEvent.parsed.reason, "session-end");

  // ...and the pruner deletes it after the (shortened) grace period.
  const deadline = Date.now() + 10_000;
  for (;;) {
    status = await request(bridge.port, "GET", "/status", { token });
    if (!status.body.sessions.some((s) => s.id === sessionId)) break;
    assert.ok(Date.now() < deadline, `ended external session was never pruned: ${JSON.stringify(status.body.sessions)}`);
    await new Promise((r) => setTimeout(r, 100).unref());
  }
});

test("setup-hooks.sh installs the SessionEnd hook pointing at /hooks/session-end", { timeout: 60_000 }, async (t) => {
  const fakeHome = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-hooks-home-"));
  t.after(() => {
    try { fs.rmSync(fakeHome, { recursive: true, force: true }); } catch { /* ignore */ }
  });

  const script = fileURLToPath(new URL("../../setup-hooks.sh", import.meta.url));
  // Port 7869 is the top of the bridge range; no bridge needs to be running —
  // the installer only warns and proceeds.
  await execFileAsync("bash", [script, "7869"], {
    env: { ...process.env, HOME: fakeHome },
    timeout: 30_000,
  });

  const settings = JSON.parse(fs.readFileSync(path.join(fakeHome, ".claude", "settings.json"), "utf-8"));
  const sessionEnd = settings.hooks?.SessionEnd;
  assert.ok(Array.isArray(sessionEnd) && sessionEnd.length > 0, "SessionEnd hook entry installed");
  const urls = sessionEnd.flatMap((entry) => (entry.hooks || []).map((h) => h.url));
  assert.ok(
    urls.includes("http://127.0.0.1:7869/hooks/session-end"),
    `SessionEnd must POST to /hooks/session-end, got: ${urls.join(", ")}`,
  );
  // Stop stays wired to /hooks/stop — it fires per turn and must not be
  // remapped to session end.
  const stopUrls = (settings.hooks?.Stop || []).flatMap((entry) => (entry.hooks || []).map((h) => h.url));
  assert.ok(stopUrls.includes("http://127.0.0.1:7869/hooks/stop"), "Stop hook still targets /hooks/stop");
});
