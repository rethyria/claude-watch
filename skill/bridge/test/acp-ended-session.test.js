// An ENDED ACP slot must be honestly dead on every surface (#127, black-box):
// dictation refuses with the 409 ended refusal (not a 502 blaming a Zed
// adapter that did nothing wrong), a late fire-and-forget update cannot
// re-bind the corpse to a live inbox, and a revived session's first message
// is not contaminated by the dead copy's stranded prose buffer.
//
// The endpoints are driven exactly like the real actors drive them: the fork
// side over the loopback /acp/* surface, the watch side over the authed
// /v1/command surface.
import { test } from "node:test";
import assert from "node:assert/strict";
import { startBridge, request, tempDir, connectSse } from "./helpers.js";

async function pairedBridge(t) {
  const bridge = await startBridge(t);
  const pair = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  return { bridge, token: pair.body.token };
}

function connectInbox(t, bridge, connectionId) {
  const inbox = connectSse(bridge.port, undefined, { path: `/acp/inbox?connection=${connectionId}` });
  t.after(() => inbox.close());
  return inbox;
}

async function registerSession(bridge, connection, sessionId, cwd) {
  const res = await request(bridge.port, "POST", "/acp/register", {
    body: { connection, sessionId, sdkSessionId: sessionId, cwd },
  });
  assert.equal(res.status, 200);
}

test("dictation into an ended ACP session answers the 409 ended refusal, not a 502 (#127)", { timeout: 60_000 }, async (t) => {
  const { bridge, token } = await pairedBridge(t);
  const cwd = tempDir(t, "claude-watch-acp-ended-");
  await registerSession(bridge, "conn-e1", "acp-ended-1", cwd);
  const dereg = await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "conn-e1", sessionId: "acp-ended-1", reason: "acp-closed" },
  });
  assert.equal(dereg.status, 200);

  // The slot is ended but still visible through the prune grace — exactly
  // when a wrist that watched it die can still aim dictation at it.
  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { command: "hello?", sessionId: "acp-ended-1" },
  });
  assert.equal(resp.status, 409, `an ended session is a 409 refusal, not an adapter fault; got ${resp.status}: ${JSON.stringify(resp.body)}`);
  assert.match(resp.body.error, /has ended/, "the refusal names the real reason");
});

test("a late update cannot re-bind an ended slot; dictation is refused, not delivered into a void (#127)", { timeout: 60_000 }, async (t) => {
  const { bridge, token } = await pairedBridge(t);
  const cwd = tempDir(t, "claude-watch-acp-rebind-");
  const inbox = connectInbox(t, bridge, "conn-r1");
  assert.equal(await inbox.statusCode(), 200);
  await registerSession(bridge, "conn-r1", "acp-rebind-1", cwd);
  await request(bridge.port, "POST", "/acp/deregister", {
    body: { connection: "conn-r1", sessionId: "acp-rebind-1", reason: "acp-closed" },
  });

  // The fork's updates are fire-and-forget on pooled sockets: one legally
  // lands AFTER the deregister. It must not re-arm the ended slot's routing.
  const late = await request(bridge.port, "POST", "/acp/update", {
    body: { connection: "conn-r1", sessionId: "acp-rebind-1", kind: "session_update", payload: {} },
  });
  assert.equal(late.status, 200);

  const resp = await request(bridge.port, "POST", "/v1/command", {
    token,
    body: { command: "into the void", sessionId: "acp-rebind-1" },
  });
  assert.equal(resp.status, 409, `dictation into the re-bound corpse must refuse; got ${resp.status}: ${JSON.stringify(resp.body)}`);
  // And nothing went down the inbox: an ok would have been a delivery report
  // for a prompt the fork dropped on the floor.
  assert.ok(
    !inbox.events.some((e) => e.event === "inject"),
    `no inject frame may reach the fork for an ended session; saw ${inbox.events.map((e) => e.event).join(", ")}`,
  );
});

test("a revived session's first message does not inherit the dead copy's stranded prose (#127)", { timeout: 60_000 }, async (t) => {
  const { bridge, token } = await pairedBridge(t);
  const cwd = tempDir(t, "claude-watch-acp-prose-");
  const sse = connectSse(bridge.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);

  const chunk = (connection, text) => request(bridge.port, "POST", "/acp/update", {
    body: {
      connection, sessionId: "acp-prose-1", kind: "session_update",
      payload: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text } } },
    },
  });

  // First life: buffers prose, then dies with its fork (inbox drop) — the
  // ending path that never swept the buffer.
  const inbox1 = connectInbox(t, bridge, "conn-p1");
  assert.equal(await inbox1.statusCode(), 200);
  await registerSession(bridge, "conn-p1", "acp-prose-1", cwd);
  await chunk("conn-p1", "stale interrupted narration ");
  inbox1.close();
  await sse.waitFor((e) => e.event === "session" && e.parsed?.sessionId === "acp-prose-1" && e.parsed?.state === "ended");

  // Second life: the same session id revives under a fresh fork (a Zed
  // restart), speaks, and ends its turn — the flush the wrist reads.
  const inbox2 = connectInbox(t, bridge, "conn-p2");
  assert.equal(await inbox2.statusCode(), 200);
  await registerSession(bridge, "conn-p2", "acp-prose-1", cwd);
  await chunk("conn-p2", "fresh words");
  await request(bridge.port, "POST", "/acp/update", {
    body: { connection: "conn-p2", sessionId: "acp-prose-1", kind: "turn", payload: { phase: "end" } },
  });

  const message = await sse.waitFor((e) => e.event === "message" && e.parsed?.sessionId === "acp-prose-1");
  assert.equal(
    message.parsed.text, "fresh words",
    "the dead copy's buffer must not leak into the revival's first flush",
  );
});
