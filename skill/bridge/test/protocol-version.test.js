// Protocol versioning (issue #14, see PROTOCOL.md): the /v1/pair min-version
// gate, the proto version in the /v1 pair response and Bonjour TXT record,
// and the /v1 disambiguation of the two historical sessionId meanings.
// Black-box against the real bridge process except for the TXT record, which
// is asserted through the same bonjourTxtRecord() helper server.js publishes.
import { test } from "node:test";
import assert from "node:assert/strict";
import os from "node:os";
import { startBridge, request, connectSse, rawRequest } from "./helpers.js";
import {
  PROTOCOL_VERSION,
  MIN_SUPPORTED_CLIENT_PROTO,
  BRIDGE_ID,
  bonjourTxtRecord,
} from "../config.js";

test("Bonjour TXT record carries the protocol version under v (and the frozen legacy aliases)", () => {
  const txt = bonjourTxtRecord();
  assert.equal(txt.v, String(PROTOCOL_VERSION), "canonical TXT version key is v");
  assert.equal(txt.v, "3");
  assert.equal(txt.version, "3", "legacy version key stays present");
  assert.equal(txt.bridgeId, BRIDGE_ID);
  assert.equal(txt.sessionId, BRIDGE_ID, "legacy sessionId alias stays present");
  assert.equal(txt.machineName, os.hostname());
});

test("/v1/pair refuses clients below the min protocol version with a clear error; the code survives for a capable client", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  // An outdated app declaring proto 2 is refused up front...
  const outdated = await request(port, "POST", "/v1/pair", {
    body: { code: pairingCode, proto: MIN_SUPPORTED_CLIENT_PROTO - 1, deviceName: "old-watch" },
  });
  assert.equal(outdated.status, 426);
  assert.match(outdated.body.error, /protocol version/i, "error names the protocol problem");
  assert.match(outdated.body.error, new RegExp(`>= ${MIN_SUPPORTED_CLIENT_PROTO}`), "error states the required minimum");
  assert.equal(outdated.body.minProto, MIN_SUPPORTED_CLIENT_PROTO);
  assert.equal(outdated.body.proto, PROTOCOL_VERSION);
  assert.equal(outdated.body.token, undefined, "no token is minted for a refused client");

  // ...and so is a /v1 client that declares no version at all (pre-versioning
  // /v1 apps must fail detectably, not pair into wire mismatches).
  const undeclared = await request(port, "POST", "/v1/pair", {
    body: { code: pairingCode, deviceName: "undeclared-watch" },
  });
  assert.equal(undeclared.status, 426);
  assert.match(undeclared.body.error, /Update the watch app/);

  // The refusals burned neither the pairing code nor the pairing window: a
  // capable client pairs with the same code, and its response carries proto.
  const pair = await request(port, "POST", "/v1/pair", {
    body: { code: pairingCode, proto: MIN_SUPPORTED_CLIENT_PROTO, deviceName: "new-watch" },
  });
  assert.equal(pair.status, 200);
  assert.ok(pair.body.token, "capable client still pairs");
  assert.equal(pair.body.proto, PROTOCOL_VERSION, "v1 pair response carries the bridge protocol version");

  // /v1 disambiguation: bridgeId is the only top-level bridge identity —
  // sessionId means agent-session slot ids on /v1.
  assert.equal(pair.body.bridgeId.length, 36);
  assert.ok(!("sessionId" in pair.body), "v1 pair response has no top-level sessionId alias");
  const status = await request(port, "GET", "/v1/status", { token: pair.body.token });
  assert.equal(status.status, 200);
  assert.ok(!("sessionId" in status.body), "v1 status has no top-level sessionId alias");
  assert.equal(status.body.bridgeId, pair.body.bridgeId);

  // The token from a proto-gated pair works on the /v1 event stream.
  const sse = connectSse(port, pair.body.token, { path: "/v1/events" });
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
});

test("legacy /pair is frozen: no version check, sessionId alias intact, /ping shared", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  // A legacy client that declares nothing (or garbage) still pairs — the
  // min-version gate exists only on /v1/pair.
  const pair = await request(port, "POST", "/pair", {
    body: { code: pairingCode, proto: 1, deviceName: "legacy-watch" },
  });
  assert.equal(pair.status, 200);
  assert.ok(pair.body.token);
  assert.equal(pair.body.sessionId, pair.body.bridgeId, "legacy top-level sessionId alias for bridgeId is frozen");
  assert.ok(!("proto" in pair.body), "legacy pair response shape is frozen (no proto field)");

  // Legacy /status keeps the alias too.
  const status = await request(port, "GET", "/status", { token: pair.body.token });
  assert.equal(status.body.sessionId, status.body.bridgeId);

  // The shared discovery probe reports the current protocol version on both
  // surfaces (it post-dates the legacy freeze).
  const ping = await request(port, "GET", "/ping");
  assert.equal(ping.body.proto, PROTOCOL_VERSION);
  const v1Ping = await request(port, "GET", "/v1/ping");
  assert.equal(v1Ping.body.proto, PROTOCOL_VERSION);
});

// Absolute-form request targets ("POST http://host:port/v1/pair HTTP/1.1")
// are legal HTTP/1.1 and route through the same /v1 table as origin-form.
// The surface classification in handlePair/handleStatus once re-derived the
// surface from the raw req.url string prefix, so an absolute-form /v1/pair
// skipped the min-version gate entirely (426 never sent, token minted, the
// pairing window burned) and both handlers answered in the legacy shape.
test("absolute-form /v1 request targets stay on the /v1 surface: gate enforced, no legacy shape", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  const parseRaw = (raw) => {
    const status = parseInt(raw.match(/^HTTP\/1\.1 (\d{3})/)?.[1], 10);
    const body = JSON.parse(raw.slice(raw.indexOf("\r\n\r\n") + 4));
    return { status, body };
  };

  // An absolute-form /v1/pair that declares no proto must hit the /v1
  // min-version gate exactly like the origin-form request does.
  const gateBody = JSON.stringify({ code: pairingCode, deviceName: "absolute-form-watch" });
  const gated = parseRaw(await rawRequest(
    port,
    `POST http://127.0.0.1:${port}/v1/pair HTTP/1.1\r\n` +
      `Host: 127.0.0.1:${port}\r\n` +
      "Content-Type: application/json\r\n" +
      `Content-Length: ${Buffer.byteLength(gateBody)}\r\n` +
      "Connection: close\r\n\r\n" +
      gateBody,
  ));
  assert.equal(gated.status, 426, "absolute-form /v1/pair without proto is refused by the gate");
  assert.equal(gated.body.minProto, MIN_SUPPORTED_CLIENT_PROTO);
  assert.equal(gated.body.token, undefined, "no token minted through the absolute-form path");

  // The refusal burned neither the code nor the window, and an absolute-form
  // pair from a capable client answers in the /v1 shape (proto present, no
  // top-level sessionId alias) — not the legacy one.
  const pairBody = JSON.stringify({
    code: pairingCode,
    proto: MIN_SUPPORTED_CLIENT_PROTO,
    deviceName: "absolute-form-watch",
  });
  const pair = parseRaw(await rawRequest(
    port,
    `POST http://127.0.0.1:${port}/v1/pair HTTP/1.1\r\n` +
      `Host: 127.0.0.1:${port}\r\n` +
      "Content-Type: application/json\r\n" +
      `Content-Length: ${Buffer.byteLength(pairBody)}\r\n` +
      "Connection: close\r\n\r\n" +
      pairBody,
  ));
  assert.equal(pair.status, 200, "gate refusal did not burn the pairing code");
  assert.ok(pair.body.token);
  assert.equal(pair.body.proto, PROTOCOL_VERSION, "absolute-form /v1 pair response carries proto");
  assert.ok(!("sessionId" in pair.body), "absolute-form /v1 pair response has no legacy sessionId alias");

  // Same classification skew existed in handleStatus: absolute-form
  // /v1/status must use the /v1 shape too.
  const status = parseRaw(await rawRequest(
    port,
    `GET http://127.0.0.1:${port}/v1/status HTTP/1.1\r\n` +
      `Host: 127.0.0.1:${port}\r\n` +
      `Authorization: Bearer ${pair.body.token}\r\n` +
      "Connection: close\r\n\r\n",
  ));
  assert.equal(status.status, 200);
  assert.ok(!("sessionId" in status.body), "absolute-form /v1 status has no legacy sessionId alias");
  assert.equal(status.body.bridgeId, pair.body.bridgeId);
});

// PROTOCOL.md guarantees every SSE event id is a monotonically increasing
// integer. The connect-time snapshot once reissued the previous event's id
// to its first event (post- vs pre-increment skew against pushSseEvent), so
// a client deduping by id — a strategy the guarantee invites — silently
// dropped the first snapshot event on every connect.
test("SSE event ids are strictly increasing across the backlog/snapshot boundary on a fresh connect", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/v1/pair", {
    body: { code: pairingCode, proto: MIN_SUPPORTED_CLIENT_PROTO, deviceName: "id-watch" },
  });
  assert.equal(pair.status, 200);
  const token = pair.body.token;

  // Observer client, connected BEFORE any activity: its snapshot is empty,
  // so it never advances the event-id counter — the fresh connect below must
  // be the FIRST connect after the buffered events, with the counter still
  // sitting exactly on the last buffered id (the boundary the bug lived on).
  const sseA = connectSse(port, token, { path: "/v1/events" });
  t.after(() => sseA.close());
  assert.equal(await sseA.statusCode(), 200);

  // Buffered activity, terminal output LAST: a blocking permission prompt
  // (creates the session), then a tool-output. The connect-time backlog
  // replays only terminal events, so the last id the fresh client sees
  // before the snapshot is the tool-output's — the counter's current value.
  const hookResponse = request(port, "POST", "/hooks/permission", {
    body: { tool_name: "Bash", cwd: "/tmp/sse-id-project", tool_input: { command: "true" } },
  });
  const prompt = await sseA.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
  );
  const posted = await request(port, "POST", "/hooks/tool-output", {
    body: { tool_name: "Read", cwd: "/tmp/sse-id-project", tool_output: "sse-id-marker" },
  });
  assert.equal(posted.status, 200);
  await sseA.waitFor((e) => e.event === "tool-output" && e.parsed?.tool_output === "sse-id-marker");

  // Fresh client (no Last-Event-ID): receives backlog, then the snapshot
  // (session running + re-sent permission-request).
  const sseB = connectSse(port, token, { path: "/v1/events" });
  t.after(() => sseB.close());
  assert.equal(await sseB.statusCode(), 200);
  await sseB.waitFor((e) => e.event === "tool-output" && e.parsed?.tool_output === "sse-id-marker");
  await sseB.waitFor((e) => e.event === "session" && e.parsed?.state === "running");
  await sseB.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.permissionId === prompt.parsed.permissionId,
  );

  const ids = sseB.events.map((e) => e.id);
  assert.ok(ids.length >= 3, `expected backlog + snapshot events, saw ids ${JSON.stringify(ids)}`);
  for (let i = 0; i < ids.length; i++) {
    assert.ok(Number.isInteger(ids[i]), `event ${i} carries an integer id (got ${ids[i]})`);
    if (i > 0) {
      assert.ok(
        ids[i] > ids[i - 1],
        `SSE ids must be strictly increasing (PROTOCOL.md): saw ${JSON.stringify(ids)}`,
      );
    }
  }

  // Unblock the hook so teardown is clean.
  const decision = await request(port, "POST", "/v1/command", {
    token,
    body: { permissionId: prompt.parsed.permissionId, decision: { behavior: "allow" } },
  });
  assert.equal(decision.status, 200);
  await hookResponse;
});
