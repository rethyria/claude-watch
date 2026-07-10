// Pairing-surface refinements (issue #37), black-box against the real bridge
// process: a credentials store that EXISTS but is unreadable/invalid fails
// closed (pairing LOCKED), a missing store keeps the historical fail-open
// first run, a SIGUSR1-reopened pairing window relocks when its code expires
// without a successful pair, and the initial startup window keeps its
// regenerate-on-expiry behavior.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { startBridge, request } from "./helpers.js";

const LOCKOUT_ERROR = "Already paired. Re-pairing requires explicit authorization on the bridge.";
const RELOCK_ERROR = "Pairing code expired and pairing is locked again. Send SIGUSR1 on the bridge to reopen.";
const EXPIRED_ERROR = "Pairing code expired. A new code has been generated.";

// A pairing-code TTL short enough to expire inside a test, long enough that
// deliberate pair attempts made "immediately" never race it.
const SHORT_TTL_MS = 1000;
const SHORT_TTL_ENV = { CLAUDE_WATCH_PAIRING_CODE_TTL_MS: String(SHORT_TTL_MS) };

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function countGeneratedCodes(bridge) {
  return (bridge.output().match(/Pairing code generated:/g) || []).length;
}

// Create a per-test credentials dir, optionally pre-populated with a
// credentials.json of the given contents.
function makeCredentialsDir(t, contents) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-refine-"));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  if (contents !== undefined) {
    fs.writeFileSync(path.join(dir, "credentials.json"), contents);
  }
  return dir;
}

// Send SIGUSR1 to the bridge and scrape the freshly minted pairing code from
// its log (the reopen logs "Pairing code generated: ..." exactly like startup).
async function reopenPairing(bridge) {
  const seen = countGeneratedCodes(bridge);
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

test("unparseable credentials.json → bridge starts LOCKED with a loud warning", { timeout: 60_000 }, async (t) => {
  const credentialsDir = makeCredentialsDir(t, "{ this is not json");
  const bridge = await startBridge(t, { credentialsDir });

  assert.equal(bridge.pairingCode, null, "corrupt store must not advertise a pairing code");
  assert.equal(bridge.pairingLocked, true, "corrupt store must lock the pairing surface");
  assert.match(bridge.output(), /SECURITY: credentials file .+ exists but is invalid/);
  assert.match(bridge.output(), /Pairing LOCKED: credentials store exists but is unreadable\/invalid/);

  const refused = await request(bridge.port, "POST", "/pair", { body: { code: "000000" } });
  assert.equal(refused.status, 403);
  assert.equal(refused.body.error, LOCKOUT_ERROR);
});

test("credentials.json with only invalid entries → LOCKED; --allow-pairing recovers with a fresh store", { timeout: 60_000 }, async (t) => {
  const credentialsDir = makeCredentialsDir(
    t,
    JSON.stringify({ version: 1, tokens: [{ hash: "not-hex" }, { bogus: true }] }),
  );

  // Fail closed: parses fine, but no entry is a usable credential
  const bridge1 = await startBridge(t, { credentialsDir });
  assert.equal(bridge1.pairingCode, null);
  assert.equal(bridge1.pairingLocked, true);
  assert.match(bridge1.output(), /SECURITY: credentials file .+ exists but is invalid \(no valid token entries\)/);
  const refused = await request(bridge1.port, "POST", "/pair", { body: { code: "000000" } });
  assert.equal(refused.status, 403);
  await bridge1.stop();

  // Explicit operator recovery: --allow-pairing opens pairing despite the
  // corrupt store, and the next successful pair rewrites a valid store
  const bridge2 = await startBridge(t, { credentialsDir, args: ["--allow-pairing"] });
  assert.ok(bridge2.pairingCode, "--allow-pairing must advertise a fresh code even with a corrupt store");
  const paired = await request(bridge2.port, "POST", "/pair", {
    body: { code: bridge2.pairingCode, deviceName: "recovered" },
  });
  assert.equal(paired.status, 200);
  assert.ok(paired.body.token);

  const store = JSON.parse(fs.readFileSync(path.join(credentialsDir, "credentials.json"), "utf-8"));
  assert.equal(store.tokens.length, 1, "recovery pair rewrites a fresh valid store");
  assert.match(store.tokens[0].hash, /^[0-9a-f]{64}$/);
});

test("unreadable credentials.json (a directory at that path) → LOCKED", { timeout: 60_000 }, async (t) => {
  const credentialsDir = makeCredentialsDir(t);
  fs.mkdirSync(path.join(credentialsDir, "credentials.json"));

  const bridge = await startBridge(t, { credentialsDir });
  assert.equal(bridge.pairingCode, null);
  assert.equal(bridge.pairingLocked, true);
  assert.match(bridge.output(), /SECURITY: credentials file .+ exists but could not be read/);

  const refused = await request(bridge.port, "POST", "/pair", { body: { code: "000000" } });
  assert.equal(refused.status, 403);
  assert.equal(refused.body.error, LOCKOUT_ERROR);
});

test("missing credentials.json → pairing opens at startup exactly as before", { timeout: 60_000 }, async (t) => {
  const credentialsDir = makeCredentialsDir(t); // dir exists, file does not
  const bridge = await startBridge(t, { credentialsDir });

  assert.ok(bridge.pairingCode, "absent store keeps the fail-open first-run window");
  assert.equal(bridge.pairingLocked, false);
  assert.ok(!/SECURITY:/.test(bridge.output()), "no corruption warning for a merely absent store");

  const paired = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(paired.status, 200);
  assert.ok(paired.body.token);
});

test("SIGUSR1-reopened window relocks on code expiry instead of regenerating", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: SHORT_TTL_ENV });

  // Operator reopens pairing (works from the initial window too), then forgets
  const reopenedCode = await reopenPairing(bridge);
  assert.equal(countGeneratedCodes(bridge), 2); // startup + reopen
  await sleep(SHORT_TTL_MS + 500);

  // The attempt that observes the expiry relocks the surface — no fresh code
  const relocked = await request(bridge.port, "POST", "/pair", { body: { code: reopenedCode } });
  assert.equal(relocked.status, 403);
  assert.equal(relocked.body.error, RELOCK_ERROR);
  assert.match(bridge.output(), /Reopened pairing window expired without a successful pair/);
  assert.equal(countGeneratedCodes(bridge), 2, "expiry after reopen must NOT mint a fresh code");

  // Subsequent attempts hit the ordinary lockout latch
  const stillLocked = await request(bridge.port, "POST", "/pair", { body: { code: reopenedCode } });
  assert.equal(stillLocked.status, 403);
  assert.equal(stillLocked.body.error, LOCKOUT_ERROR);
  assert.equal(countGeneratedCodes(bridge), 2);

  // The relock is not a dead end: another explicit reopen pairs normally
  const freshCode = await reopenPairing(bridge);
  const paired = await request(bridge.port, "POST", "/pair", { body: { code: freshCode } });
  assert.equal(paired.status, 200);
  assert.ok(paired.body.token);
});

test("initial startup window still regenerates on code expiry (first-run UX unchanged)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: SHORT_TTL_ENV });
  assert.ok(bridge.pairingCode);
  await sleep(SHORT_TTL_MS + 500);

  // Frozen legacy behavior: expired startup code → 401 and a fresh code
  const expired = await request(bridge.port, "POST", "/pair", { body: { code: bridge.pairingCode } });
  assert.equal(expired.status, 401);
  assert.equal(expired.body.error, EXPIRED_ERROR);

  await bridge.waitForOutput(/Pairing code generated: \d{6}[\s\S]*Pairing code generated: \d{6}/);
  const codes = [...bridge.output().matchAll(/Pairing code generated: (\d{6})/g)];
  assert.equal(codes.length, 2, "expiry during the startup window regenerates a code");
  const newCode = codes[codes.length - 1][1];

  const paired = await request(bridge.port, "POST", "/pair", { body: { code: newCode } });
  assert.equal(paired.status, 200);
  assert.ok(paired.body.token);
});
