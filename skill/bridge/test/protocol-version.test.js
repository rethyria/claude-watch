// Protocol versioning (issue #14, see PROTOCOL.md): the /v1/pair min-version
// gate, the proto version in the /v1 pair response and Bonjour TXT record,
// and the /v1 disambiguation of the two historical sessionId meanings.
// Black-box against the real bridge process except for the TXT record, which
// is asserted through the same bonjourTxtRecord() helper server.js publishes.
import { test } from "node:test";
import assert from "node:assert/strict";
import os from "node:os";
import { startBridge, request, connectSse } from "./helpers.js";
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
