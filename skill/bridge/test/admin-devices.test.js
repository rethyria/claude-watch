// Operator device admin (issue #72), black-box against the real bridge
// process: GET /admin/devices lists paired devices with a short hash-PREFIX id
// (never a token or full hash), POST /admin/devices/revoke disconnects one
// device ({id}) or all ({all:true}), ambiguous/unknown/malformed ids are
// refused without touching the store, the whole surface is loopback-only, and a
// revoked device's live SSE stream is dropped immediately.
import { test } from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { startBridge, request } from "./helpers.js";

const sha256 = (s) => crypto.createHash("sha256").update(s, "utf-8").digest("hex");

// First non-internal IPv4 address of this machine, or null on a loopback-only
// host — the same pattern endpoint-hardening.test.js uses to simulate a genuine
// non-loopback source by connecting to the bridge's own LAN address.
function lanIPv4() {
  for (const addrs of Object.values(os.networkInterfaces())) {
    for (const addr of addrs || []) {
      if (addr.family === "IPv4" && !addr.internal) return addr.address;
    }
  }
  return null;
}

// SIGUSR1-reopen pairing and scrape the freshly minted code (the reopen logs
// "Pairing code generated: ..." exactly like startup). --allow-pairing only
// sets the INITIAL open window; the lock re-engages after every successful
// pair, so pairing a second device requires an explicit reopen.
async function reopenPairing(bridge) {
  const seen = (bridge.output().match(/Pairing code generated:/g) || []).length;
  bridge.proc.kill("SIGUSR1");
  const deadline = Date.now() + 10_000;
  for (;;) {
    const codes = [...bridge.output().matchAll(/Pairing code generated: (\d{6})/g)];
    if (codes.length > seen) return codes[codes.length - 1][1];
    if (Date.now() > deadline) throw new Error(`no new pairing code after SIGUSR1\n${bridge.output()}`);
    await new Promise((r) => setTimeout(r, 25).unref());
  }
}

// A raw /events SSE connection that exposes when the server ENDS the stream —
// connectSse (helpers.js) only surfaces events, not closure, and the drop test
// needs the closure signal.
function openRawSse(port, token) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: "127.0.0.1",
        port,
        path: "/events",
        method: "GET",
        headers: { Accept: "text/event-stream", Authorization: `Bearer ${token}` },
      },
      (res) => {
        let firstResolve;
        const firstByte = new Promise((r) => { firstResolve = r; });
        res.on("data", () => firstResolve());
        const closed = new Promise((r) => {
          res.on("end", () => r("end"));
          res.on("close", () => r("close"));
        });
        resolve({ status: res.statusCode, firstByte, closed, destroy: () => req.destroy() });
      },
    );
    req.on("error", reject);
    req.end();
  });
}

test("GET /admin/devices lists prefix ids and no secrets; revoke {id} disconnects exactly one", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;

  // Device A pairs on the legacy surface; device B on /v1 after a reopen.
  const pairA = await request(port, "POST", "/pair", { body: { code: bridge.pairingCode, deviceName: "watch-a" } });
  assert.equal(pairA.status, 200);
  const tokenA = pairA.body.token;
  const codeB = await reopenPairing(bridge);
  const pairB = await request(port, "POST", "/v1/pair", { body: { code: codeB, deviceName: "watch-b", proto: 3 } });
  assert.equal(pairB.status, 200);
  const tokenB = pairB.body.token;
  const hashA = sha256(tokenA);
  const hashB = sha256(tokenB);

  // List: two devices, correct metadata, id is a 12-hex prefix of the hash.
  const list = await request(port, "GET", "/admin/devices");
  assert.equal(list.status, 200);
  assert.equal(list.body.devices.length, 2);
  const byName = Object.fromEntries(list.body.devices.map((d) => [d.deviceName, d]));
  assert.ok(byName["watch-a"] && byName["watch-b"], "both device names listed");
  assert.equal(byName["watch-a"].surface, "legacy");
  assert.equal(byName["watch-b"].surface, "v1");
  for (const d of list.body.devices) {
    assert.match(d.id, /^[0-9a-f]{12}$/, "id is a 12-hex prefix");
    assert.ok(!Number.isNaN(Date.parse(d.createdAt)), "createdAt is a valid timestamp");
  }
  assert.equal(byName["watch-a"].id, hashA.slice(0, 12), "id is the hash prefix");
  assert.equal(byName["watch-b"].id, hashB.slice(0, 12));

  // The list response leaks no token and no FULL hash (only the 12-hex prefix).
  const listJson = JSON.stringify(list.body);
  for (const secret of [tokenA, tokenB, hashA, hashB]) {
    assert.ok(!listJson.includes(secret), "list response must leak no token/full-hash");
  }

  // Both tokens authenticate before the revoke.
  assert.equal((await request(port, "GET", "/status", { token: tokenA })).status, 200);
  assert.equal((await request(port, "GET", "/status", { token: tokenB })).status, 200);

  // Revoke device A by its prefix id.
  const revoke = await request(port, "POST", "/admin/devices/revoke", { body: { id: byName["watch-a"].id } });
  assert.equal(revoke.status, 200);
  assert.deepEqual(revoke.body, { ok: true, revoked: "watch-a" }, "revoked names the device, never a token/hash");

  // A's token now 401s (requireAuth no longer matches it); B still 200s.
  assert.equal((await request(port, "GET", "/status", { token: tokenA })).status, 401);
  assert.equal((await request(port, "GET", "/status", { token: tokenB })).status, 200);

  // The list now shows only B.
  const list2 = await request(port, "GET", "/admin/devices");
  assert.equal(list2.body.devices.length, 1);
  assert.equal(list2.body.devices[0].deviceName, "watch-b");

  // No token or full hash ever reached the bridge log either.
  const out = bridge.output();
  for (const secret of [tokenA, tokenB, hashA, hashB]) {
    assert.ok(!out.includes(secret), "bridge log must leak no token/full-hash");
  }
});

test("POST /admin/devices/revoke {all:true} empties the store; every prior token 401s", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;

  const pairA = await request(port, "POST", "/pair", { body: { code: bridge.pairingCode, deviceName: "a" } });
  const tokenA = pairA.body.token;
  const codeB = await reopenPairing(bridge);
  const pairB = await request(port, "POST", "/pair", { body: { code: codeB, deviceName: "b" } });
  const tokenB = pairB.body.token;

  assert.equal((await request(port, "GET", "/admin/devices")).body.devices.length, 2);

  const revoke = await request(port, "POST", "/admin/devices/revoke", { body: { all: true } });
  assert.equal(revoke.status, 200);
  assert.deepEqual(revoke.body, { ok: true, revoked: 2 });

  // Store empty; both prior tokens now 401.
  assert.equal((await request(port, "GET", "/admin/devices")).body.devices.length, 0);
  assert.equal((await request(port, "GET", "/status", { token: tokenA })).status, 401);
  assert.equal((await request(port, "GET", "/status", { token: tokenB })).status, 401);

  // Revoke-all does NOT auto-open pairing: the operator still has to reopen.
  const stillLocked = await request(port, "POST", "/pair", { body: { code: "000000" } });
  assert.equal(stillLocked.status, 403);
});

test("revoke unknown id → 404; malformed id → 400; the device survives both", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;
  const pair = await request(port, "POST", "/pair", { body: { code: bridge.pairingCode, deviceName: "keeper" } });
  const token = pair.body.token;

  // Well-formed hex prefix matching nothing → 404.
  const unknown = await request(port, "POST", "/admin/devices/revoke", { body: { id: "deadbeefdead" } });
  assert.equal(unknown.status, 404);

  // Malformed ids → 400 (empty must NOT match-all and revoke the only device;
  // too-short, non-hex, and missing are likewise refused).
  for (const id of ["", "abc", "xyz123abc456", undefined]) {
    const bad = await request(port, "POST", "/admin/devices/revoke", { body: { id } });
    assert.equal(bad.status, 400, `id ${JSON.stringify(id)} must be rejected as malformed`);
  }

  // The device is untouched: still listed, still authenticates.
  assert.equal((await request(port, "GET", "/admin/devices")).body.devices.length, 1);
  assert.equal((await request(port, "GET", "/status", { token })).status, 200);
});

test("revoke ambiguous prefix → 400, nothing removed; a longer prefix disambiguates", { timeout: 60_000 }, async (t) => {
  // Two random real pairings never share even a 6-hex prefix, so ambiguity is
  // forced with a crafted store of two valid entries sharing a 12-hex prefix.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-admin-ambi-"));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const shared = "abcabcabcabc"; // 12 hex
  const hash1 = shared + "0".repeat(52);
  const hash2 = shared + "1".repeat(52);
  fs.writeFileSync(
    path.join(dir, "credentials.json"),
    JSON.stringify({
      version: 1,
      tokens: [
        { hash: hash1, deviceName: "twin-1", createdAt: new Date().toISOString(), surface: "v1" },
        { hash: hash2, deviceName: "twin-2", createdAt: new Date().toISOString(), surface: "v1" },
      ],
    }),
  );

  const bridge = await startBridge(t, { credentialsDir: dir });
  const { port } = bridge;
  assert.equal((await request(port, "GET", "/admin/devices")).body.devices.length, 2);

  // The shared prefix matches both → 400, nothing removed.
  const ambi = await request(port, "POST", "/admin/devices/revoke", { body: { id: shared } });
  assert.equal(ambi.status, 400);
  assert.match(ambi.body.error, /ambiguous/i);
  assert.equal(
    (await request(port, "GET", "/admin/devices")).body.devices.length,
    2,
    "an ambiguous revoke must remove nothing",
  );

  // A longer prefix unique to hash1 removes exactly that one.
  const one = await request(port, "POST", "/admin/devices/revoke", { body: { id: hash1.slice(0, 20) } });
  assert.equal(one.status, 200);
  assert.equal(one.body.revoked, "twin-1");
  const remaining = await request(port, "GET", "/admin/devices");
  assert.equal(remaining.body.devices.length, 1);
  assert.equal(remaining.body.devices[0].deviceName, "twin-2");
});

test("/admin is loopback-only: non-loopback GET and POST are rejected 403", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;

  // Loopback control: the list endpoint answers on 127.0.0.1.
  assert.equal((await request(port, "GET", "/admin/devices")).status, 200);

  const lanIP = lanIPv4();
  if (!lanIP) {
    t.diagnostic("no non-internal IPv4 interface; skipping the LAN-source rejection half");
    return;
  }

  // Connecting to the bridge's own LAN address gives it a genuine non-loopback
  // remoteAddress; a spoofed Host must not help (the gate keys off the address).
  const getRes = await fetch(`http://${lanIP}:${port}/admin/devices`, {
    headers: { Host: "localhost" },
    signal: AbortSignal.timeout(10_000),
  });
  assert.equal(getRes.status, 403, "non-loopback GET /admin/devices must be rejected");
  assert.equal((await getRes.json()).error, "Admin endpoints are only accepted from localhost");

  const postRes = await fetch(`http://${lanIP}:${port}/admin/devices/revoke`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Host: "localhost" },
    body: JSON.stringify({ all: true }),
    signal: AbortSignal.timeout(10_000),
  });
  assert.equal(postRes.status, 403, "non-loopback POST revoke must be rejected");
});

test("revoke {id} drops the device's live SSE stream immediately", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;
  const pair = await request(port, "POST", "/pair", { body: { code: bridge.pairingCode, deviceName: "streamer" } });
  const token = pair.body.token;

  // Open a raw SSE stream and wait until it is actually live (":connected").
  const conn = await openRawSse(port, token);
  t.after(() => conn.destroy());
  assert.equal(conn.status, 200);
  await conn.firstByte;

  const list = await request(port, "GET", "/admin/devices");
  const id = list.body.devices[0].id;

  const revoke = await request(port, "POST", "/admin/devices/revoke", { body: { id } });
  assert.equal(revoke.status, 200);

  // The server ended the stream on its own — not our timeout.
  const how = await Promise.race([
    conn.closed,
    new Promise((r) => setTimeout(() => r("timeout"), 5000).unref()),
  ]);
  assert.notEqual(how, "timeout", "the revoked device's SSE stream must be dropped immediately");
});

// Guards the "spares others" half of the targeted drop (dropSseClientsForHashes
// filters by _deviceHash). Without this, a regression from per-device targeting
// to an all-clients drop — which would force-disconnect the user's other live
// watches on every single-device revoke — passes the rest of the suite green.
test("revoke {id} spares OTHER devices' live SSE streams", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;

  // Two paired devices, each with a live stream: A is the innocent bystander,
  // B is the revoke target.
  const pairA = await request(port, "POST", "/pair", { body: { code: bridge.pairingCode, deviceName: "keep-a" } });
  const tokenA = pairA.body.token;
  const codeB = await reopenPairing(bridge);
  const pairB = await request(port, "POST", "/pair", { body: { code: codeB, deviceName: "revoke-b" } });
  const tokenB = pairB.body.token;

  const connA = await openRawSse(port, tokenA);
  t.after(() => connA.destroy());
  const connB = await openRawSse(port, tokenB);
  t.after(() => connB.destroy());
  assert.equal(connA.status, 200);
  assert.equal(connB.status, 200);
  await connA.firstByte;
  await connB.firstByte;

  // Revoke ONLY device B.
  const list = await request(port, "GET", "/admin/devices");
  const idB = list.body.devices.find((d) => d.deviceName === "revoke-b").id;
  assert.equal((await request(port, "POST", "/admin/devices/revoke", { body: { id: idB } })).status, 200);

  // B's stream is dropped: waiting on it also proves the drop pass has run.
  const howB = await Promise.race([
    connB.closed,
    new Promise((r) => setTimeout(() => r("timeout"), 5000).unref()),
  ]);
  assert.notEqual(howB, "timeout", "the revoked device's SSE stream must be dropped");

  // A's stream is UNTOUCHED. The drop is synchronous in the revoke handler, so
  // an all-clients drop would have ended A in the same loop that ended B — by
  // the time B's closure landed, A's would be in flight too. It stays open.
  const howA = await Promise.race([
    connA.closed,
    new Promise((r) => setTimeout(() => r("open"), 1000).unref()),
  ]);
  assert.equal(howA, "open", "a targeted revoke must NOT drop other devices' live SSE streams");
});

// Guards the revoke-all force-drop (dropAllSseClients): test 2 exercises the
// store-emptying but never opens a live stream, so the immediate-drop half of
// revoke-all had zero coverage — deleting the drop call left the suite green.
test("revoke {all:true} drops live SSE streams immediately", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;
  const pair = await request(port, "POST", "/pair", { body: { code: bridge.pairingCode, deviceName: "streamer" } });
  const token = pair.body.token;

  const conn = await openRawSse(port, token);
  t.after(() => conn.destroy());
  assert.equal(conn.status, 200);
  await conn.firstByte;

  assert.equal((await request(port, "POST", "/admin/devices/revoke", { body: { all: true } })).status, 200);

  // The server force-dropped the stream — not our timeout. Without the drop the
  // already-open stream survives (an open /events is never re-authed), so this
  // catches a regression that stops dropping on revoke-all.
  const how = await Promise.race([
    conn.closed,
    new Promise((r) => setTimeout(() => r("timeout"), 5000).unref()),
  ]);
  assert.notEqual(how, "timeout", "revoke-all must drop every live SSE stream immediately");
});

// Guards the malformed-body path: readBody resolves a 4-byte `null` body to JS
// null, and reading `.all` off it threw a TypeError that surfaced as a 500.
// A malformed body must be a clean 400 with nothing revoked.
test("revoke with a JSON `null` body → clean 400, not 500", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;
  await request(port, "POST", "/pair", { body: { code: bridge.pairingCode, deviceName: "keeper" } });

  // request() sends JSON.stringify(null) === "null" as the body.
  const res = await request(port, "POST", "/admin/devices/revoke", { body: null });
  assert.equal(res.status, 400, "a null body must be a clean 400, never a 500");

  // Nothing was revoked — the lone device is untouched.
  assert.equal((await request(port, "GET", "/admin/devices")).body.devices.length, 1);
});

test("POST /admin/pairing/open reopens the single-use window — a code-less pair then succeeds", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port } = bridge;

  // Pair once with the startup code — this locks the single-use window.
  const first = await request(port, "POST", "/v1/pair", { body: { code: bridge.pairingCode, proto: 3, deviceName: "first" } });
  assert.equal(first.status, 200);

  // Locked: a code-less Discover pair is refused (the operator hasn't opened it).
  const lockedAttempt = await request(port, "POST", "/v1/pair", { body: { proto: 3, deviceName: "discover" } });
  assert.equal(lockedAttempt.status, 403);

  // The "initialise pairing" control reopens the window and hands back the
  // fresh Manual-path code + TTL (so the operator need not grep the log).
  const opened = await request(port, "POST", "/admin/pairing/open");
  assert.equal(opened.status, 200);
  assert.equal(opened.body.ok, true);
  assert.match(opened.body.code, /^\d{6}$/, "a fresh 6-digit code is returned");
  assert.ok(opened.body.expiresInMs > 0, "the code TTL is reported");

  // Now the code-less Discover pair succeeds against the reopened window.
  const discoverPair = await request(port, "POST", "/v1/pair", { body: { proto: 3, deviceName: "discover" } });
  assert.equal(discoverPair.status, 200, "a code-less pair succeeds after /admin/pairing/open");
  assert.ok(discoverPair.body.token);

  // Single-use: that success relocked the window; a second code-less pair 403s.
  const relocked = await request(port, "POST", "/v1/pair", { body: { proto: 3, deviceName: "discover-2" } });
  assert.equal(relocked.status, 403, "the window is single-use — reopening does not weaken that");
});

test("POST /admin/pairing/open is loopback-only", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const lanIP = lanIPv4();
  if (!lanIP) {
    t.diagnostic("no non-internal IPv4 interface; skipping the LAN-source rejection");
    return;
  }
  const res = await fetch(`http://${lanIP}:${bridge.port}/admin/pairing/open`, {
    method: "POST",
    headers: { Host: "localhost" },
    signal: AbortSignal.timeout(10_000),
  });
  assert.equal(res.status, 403, "non-loopback POST /admin/pairing/open must be rejected");
});
