// Endpoint hardening (issue #6), black-box against the real bridge process:
// hook endpoints only accept loopback sources, /status requires the bearer
// token, GET /ping answers discovery probes unauthenticated with the bridge
// identity only, and a Host-header allow-list closes the DNS-rebinding hole.
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import os from "node:os";
import { startBridge, request, connectSse } from "./helpers.js";
import { isLoopbackAddress } from "../util.js";

// First non-internal IPv4 address of this machine, or null when the test
// machine only has loopback (e.g. a bare CI container).
function lanIPv4() {
  for (const addrs of Object.values(os.networkInterfaces())) {
    for (const addr of addrs || []) {
      if (addr.family === "IPv4" && !addr.internal) return addr.address;
    }
  }
  return null;
}

// Issue a request to 127.0.0.1 while controlling the Host header exactly —
// fetch() refuses to override Host, http.request doesn't.
function hostRequest(port, path, hostHeader) {
  return new Promise((resolve, reject) => {
    const options = { host: "127.0.0.1", port, path, method: "GET" };
    if (hostHeader === null) {
      options.setHost = false; // send no Host header at all
    } else {
      options.headers = { Host: hostHeader };
    }
    const req = http.request(options, (res) => {
      let data = "";
      res.on("data", (c) => { data += c.toString(); });
      res.on("end", () => {
        let body = null;
        try { body = JSON.parse(data); } catch { /* non-JSON */ }
        resolve({ status: res.statusCode, body });
      });
    });
    req.setTimeout(10_000, () => { req.destroy(); reject(new Error("timeout")); });
    req.on("error", reject);
    req.end();
  });
}

test("isLoopbackAddress: loopback forms pass, everything else fails", () => {
  assert.equal(isLoopbackAddress("127.0.0.1"), true);
  assert.equal(isLoopbackAddress("127.255.0.9"), true, "whole 127/8 block is loopback");
  assert.equal(isLoopbackAddress("::1"), true);
  assert.equal(isLoopbackAddress("::ffff:127.0.0.1"), true, "IPv4-mapped IPv6 loopback");
  assert.equal(isLoopbackAddress("192.168.1.20"), false);
  assert.equal(isLoopbackAddress("::ffff:192.168.1.20"), false);
  assert.equal(isLoopbackAddress("10.0.2.2"), false);
  assert.equal(isLoopbackAddress("1270.0.0.1"), false);
  assert.equal(isLoopbackAddress("127.0.0.256"), false);
  assert.equal(isLoopbackAddress(""), false);
  assert.equal(isLoopbackAddress(undefined), false);
});

test("hook endpoints reject non-loopback sources; loopback keeps working", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;

  // Loopback control: the same request Claude Code hooks send succeeds.
  const local = await request(port, "POST", "/hooks/tool-output", {
    body: { tool_name: "Read", cwd: "/tmp/hardening", tool_output: "from localhost" },
  });
  assert.equal(local.status, 200);

  const lanIP = lanIPv4();
  if (!lanIP) {
    t.diagnostic("no non-internal IPv4 interface; skipping the LAN-source rejection half");
    return;
  }

  // The attack: a LAN peer POSTs to the hook surface. Connecting to our own
  // LAN address gives the bridge a genuine non-loopback remoteAddress.
  for (const path of ["/hooks/tool-output", "/hooks/permission", "/hooks/stop"]) {
    const res = await fetch(`http://${lanIP}:${port}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tool_name: "Bash", tool_input: { command: "rm -rf /" } }),
      signal: AbortSignal.timeout(10_000),
    });
    assert.equal(res.status, 403, `${path} must reject a non-loopback source`);
    const body = await res.json();
    assert.equal(body.error, "Hooks are only accepted from localhost");
  }

  // The spoof attempt must not have reached the trusted watch UI: pair, then
  // check no permission-request or tool-output from the LAN peer is replayed.
  const pair = await request(port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(pair.status, 200);
  const sse = connectSse(port, pair.body.token, { lastEventId: 0 });
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  const spoofed = await sse.waitFor((e) => e.event === "tool-output");
  assert.equal(spoofed.parsed.tool_output, "from localhost", "only the loopback hook was broadcast");
  assert.ok(
    !sse.events.some((e) => e.event === "permission-request"),
    "spoofed permission prompt must never reach the watch",
  );
});

test("/status requires the bearer token; /ping answers unauthenticated with identity only", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  // /status without a token: 401 on both surfaces, no body beyond the error.
  const anonStatus = await request(port, "GET", "/status");
  assert.equal(anonStatus.status, 401);
  assert.deepEqual(anonStatus.body, { error: "Unauthorized" });
  const anonV1Status = await request(port, "GET", "/v1/status");
  assert.equal(anonV1Status.status, 401);

  // A garbage token is also refused.
  const badToken = await request(port, "GET", "/status", { token: "not-a-real-token" });
  assert.equal(badToken.status, 401);

  // /ping answers without any token and exposes exactly the discovery triple.
  const ping = await request(port, "GET", "/ping");
  assert.equal(ping.status, 200);
  assert.deepEqual(Object.keys(ping.body).sort(), ["bridgeId", "machineName", "proto"]);
  assert.equal(ping.body.proto, "2");
  assert.equal(ping.body.machineName, os.hostname());
  assert.match(ping.body.bridgeId, /^[0-9a-f-]{36}$/);

  // After pairing, the token unlocks the full /status snapshot.
  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const authed = await request(port, "GET", "/status", { token: pair.body.token });
  assert.equal(authed.status, 200);
  assert.equal(authed.body.bridgeId, ping.body.bridgeId);
  assert.ok(Array.isArray(authed.body.sessions), "authed /status still carries the snapshot");
});

test("unknown Host header is rejected; localhost, bound LAN IP, and 10.0.2.2 pass", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;

  // DNS-rebinding attempt: request arrives on the loopback socket but carries
  // an attacker-controlled hostname.
  const evil = await hostRequest(port, "/ping", `evil.example.com:${port}`);
  assert.equal(evil.status, 403);
  assert.deepEqual(evil.body, { error: "Forbidden Host header" });

  // A missing Host header is likewise rejected — Node's HTTP server enforces
  // Host presence for HTTP/1.1 (requireHostHeader) and 400s before routing.
  const noHost = await hostRequest(port, "/ping", null);
  assert.equal(noHost.status, 400);

  // The allow-list: localhost (any case), plain loopback, and the Android
  // emulator's host alias 10.0.2.2 all pass.
  for (const host of [`localhost:${port}`, `LOCALHOST:${port}`, `127.0.0.1:${port}`, `10.0.2.2:${port}`]) {
    const res = await hostRequest(port, "/ping", host);
    assert.equal(res.status, 200, `Host ${host} must be allowed`);
  }

  const lanIP = lanIPv4();
  if (lanIP) {
    const lan = await hostRequest(port, "/ping", `${lanIP}:${port}`);
    assert.equal(lan.status, 200, "the machine's own LAN IP must be allowed");
  }

  // The guard runs pre-auth on every route, not just /ping.
  const evilStatus = await hostRequest(port, "/status", `evil.example.com:${port}`);
  assert.equal(evilStatus.status, 403);

  // A malformed Host still gets the frozen 400 from the URL-parse guard.
  const badPing = await hostRequest(port, "/ping", "bad host");
  assert.equal(badPing.status, 400);
});

test("Host allow-list is extensible via env var and --allow-host flag", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_ALLOWED_HOSTS: "bridge.env-example, second.env-example" },
    args: ["--allow-host=bridge.flag-example"],
  });
  const { port } = bridge;

  for (const host of ["bridge.env-example", "second.env-example", "bridge.flag-example"]) {
    const res = await hostRequest(port, "/ping", `${host}:${port}`);
    assert.equal(res.status, 200, `operator-added Host ${host} must be allowed`);
  }

  // Additions don't open the door for everything else.
  const evil = await hostRequest(port, "/ping", `evil.example.com:${port}`);
  assert.equal(evil.status, 403);
});
