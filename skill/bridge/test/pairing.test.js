// Per-device token store + pairing lockout (issue #7), black-box against the
// real bridge process: multiple devices hold independent persisted tokens, a
// restart no longer unpairs anyone, and after a successful pair the pairing
// surface locks until the scriptable operator reopen (SIGUSR1 or
// --allow-pairing at startup).
import { test } from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { startBridge, request, connectSse } from "./helpers.js";

const LOCKOUT_ERROR = "Already paired. Re-pairing requires explicit authorization on the bridge.";

// Send SIGUSR1 to the bridge and scrape the freshly minted pairing code from
// its log (the reopen logs "Pairing code generated: ..." exactly like startup).
async function reopenPairing(bridge) {
  const seen = (bridge.output().match(/Pairing code generated:/g) || []).length;
  bridge.proc.kill("SIGUSR1");
  const deadline = Date.now() + 10_000;
  for (;;) {
    const codes = [...bridge.output().matchAll(/Pairing code generated: (\d{6})/g)];
    if (codes.length > seen) return codes[codes.length - 1][1];
    if (Date.now() > deadline) {
      throw new Error(`no new pairing code after SIGUSR1\n${bridge.output()}`);
    }
    await new Promise((r) => setTimeout(r, 25).unref());
  }
}

test("two devices pair sequentially; both tokens work concurrently", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  // Device A pairs with the startup code
  const pairA = await request(port, "POST", "/pair", {
    body: { code: pairingCode, deviceName: "watch-a" },
  });
  assert.equal(pairA.status, 200);
  const tokenA = pairA.body.token;

  // The pairing surface is now locked: a second well-formed attempt is
  // refused instead of silently regenerating a code (the old behavior)
  const refused = await request(port, "POST", "/pair", {
    body: { code: pairingCode, deviceName: "watch-b" },
  });
  assert.equal(refused.status, 403);
  assert.equal(refused.body.error, LOCKOUT_ERROR);

  // Malformed requests keep their frozen legacy shape even while locked
  const malformed = await request(port, "POST", "/pair", { body: {} });
  assert.equal(malformed.status, 400);
  assert.equal(malformed.body.error, "Missing 'code' field");

  // Operator reopens pairing via SIGUSR1; device B pairs over /v1
  const newCode = await reopenPairing(bridge);
  const pairB = await request(port, "POST", "/v1/pair", {
    body: { code: newCode, deviceName: "watch-b" },
  });
  assert.equal(pairB.status, 200);
  const tokenB = pairB.body.token;
  assert.notEqual(tokenB, tokenA, "each device gets its own token");

  // Pairing device B did NOT deauthenticate device A: both tokens hold
  // concurrent SSE connections and both receive events
  const sseA = connectSse(port, tokenA);
  t.after(() => sseA.close());
  const sseB = connectSse(port, tokenB, { path: "/v1/events" });
  t.after(() => sseB.close());
  assert.equal(await sseA.statusCode(), 200);
  assert.equal(await sseB.statusCode(), 200);

  const posted = await request(port, "POST", "/hooks/tool-output", {
    body: { tool_name: "Read", cwd: "/tmp/pairing-test", tool_output: "both devices see this" },
  });
  assert.equal(posted.status, 200);
  const eventA = await sseA.waitFor((e) => e.event === "tool-output");
  const eventB = await sseB.waitFor((e) => e.event === "tool-output");
  assert.equal(eventA.parsed.tool_output, "both devices see this");
  assert.equal(eventB.parsed.tool_output, "both devices see this");
});

test("after a successful pair, both pairing surfaces 403 until reopen, then lock again", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;

  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);

  // Locked on the legacy surface AND the /v1 surface
  const legacy = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(legacy.status, 403);
  assert.equal(legacy.body.error, LOCKOUT_ERROR);
  const v1 = await request(port, "POST", "/v1/pair", { body: { code: pairingCode } });
  assert.equal(v1.status, 403);
  assert.equal(v1.body.error, LOCKOUT_ERROR);

  // Reopen admits exactly one more device, then the latch re-engages
  // (five recorded attempts total in this test — exactly the per-IP budget)
  const newCode = await reopenPairing(bridge);
  const second = await request(port, "POST", "/pair", { body: { code: newCode } });
  assert.equal(second.status, 200);
  const relocked = await request(port, "POST", "/pair", { body: { code: newCode } });
  assert.equal(relocked.status, 403);
  assert.equal(relocked.body.error, LOCKOUT_ERROR);
});

test("bridge restart: persisted tokens still authenticate; pairing starts locked; --allow-pairing reopens", { timeout: 60_000 }, async (t) => {
  const credentialsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-restart-"));
  t.after(() => fs.rmSync(credentialsDir, { recursive: true, force: true }));

  // First bridge: pair a device, then die
  const bridge1 = await startBridge(t, { credentialsDir });
  const pair = await request(bridge1.port, "POST", "/pair", {
    body: { code: bridge1.pairingCode, deviceName: "survivor" },
  });
  assert.equal(pair.status, 200);
  const token = pair.body.token;
  await bridge1.stop();

  // Second bridge on the same credentials dir: the old token still works and
  // the pairing surface comes up locked (no pairing code in the banner)
  const bridge2 = await startBridge(t, { credentialsDir });
  assert.equal(bridge2.pairingCode, null, "restarted bridge must not advertise a pairing code");
  assert.equal(bridge2.pairingLocked, true);

  const sse = connectSse(bridge2.port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200, "token from before the restart authenticates");
  sse.close();

  const status = await request(bridge2.port, "GET", "/status", { token });
  assert.equal(status.body.state, "connected", "restored credentials restore paired state");

  const refused = await request(bridge2.port, "POST", "/pair", { body: { code: "000000" } });
  assert.equal(refused.status, 403);
  assert.equal(refused.body.error, LOCKOUT_ERROR);
  await bridge2.stop();

  // Third bridge with --allow-pairing: pairing opens at startup even though
  // devices are already paired, and the old token keeps working alongside
  const bridge3 = await startBridge(t, { credentialsDir, args: ["--allow-pairing"] });
  assert.ok(bridge3.pairingCode, "--allow-pairing advertises a fresh code");
  const pairNew = await request(bridge3.port, "POST", "/pair", {
    body: { code: bridge3.pairingCode, deviceName: "newcomer" },
  });
  assert.equal(pairNew.status, 200);

  const sseOld = connectSse(bridge3.port, token);
  t.after(() => sseOld.close());
  const sseNew = connectSse(bridge3.port, pairNew.body.token);
  t.after(() => sseNew.close());
  assert.equal(await sseOld.statusCode(), 200);
  assert.equal(await sseNew.statusCode(), 200);
});

test("credentials.json: 0600 file in 0700 dir, hashed tokens only, per-device metadata", { timeout: 60_000 }, async (t) => {
  // Point the bridge at a not-yet-existing subdirectory so the test observes
  // the bridge's own directory creation mode, not mkdtemp's
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-perms-"));
  t.after(() => fs.rmSync(parent, { recursive: true, force: true }));
  const credentialsDir = path.join(parent, "creds");

  const bridge = await startBridge(t, { credentialsDir });
  const pairA = await request(bridge.port, "POST", "/pair", {
    body: { code: bridge.pairingCode, deviceName: "qa-watch" },
  });
  assert.equal(pairA.status, 200);
  const newCode = await reopenPairing(bridge);
  const pairB = await request(bridge.port, "POST", "/v1/pair", { body: { code: newCode } });
  assert.equal(pairB.status, 200);

  const file = path.join(credentialsDir, "credentials.json");
  assert.equal(fs.statSync(credentialsDir).mode & 0o777, 0o700, "credentials dir is 0700");
  assert.equal(fs.statSync(file).mode & 0o777, 0o600, "credentials file is 0600");

  const raw = fs.readFileSync(file, "utf-8");
  assert.ok(!raw.includes(pairA.body.token), "plaintext token A must not be stored");
  assert.ok(!raw.includes(pairB.body.token), "plaintext token B must not be stored");

  const store = JSON.parse(raw);
  assert.equal(store.version, 1);
  assert.equal(store.tokens.length, 2);

  const [entryA, entryB] = store.tokens;
  const sha256 = (s) => crypto.createHash("sha256").update(s, "utf-8").digest("hex");
  assert.equal(entryA.hash, sha256(pairA.body.token), "stored hash is SHA-256 of the token");
  assert.equal(entryA.deviceName, "qa-watch");
  assert.equal(entryA.surface, "legacy");
  assert.ok(!Number.isNaN(Date.parse(entryA.createdAt)), "createdAt is a valid timestamp");

  assert.equal(entryB.hash, sha256(pairB.body.token));
  assert.equal(entryB.surface, "v1", "surface records which endpoint issued the token");
  assert.equal(entryB.deviceName, undefined, "deviceName is optional");
});
