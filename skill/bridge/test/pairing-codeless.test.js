// Code-less "Discover" pairing (issue #23 follow-up), black-box against the
// real bridge process. `code` is OPTIONAL on /v1/pair: the Discover path sends
// no code and the operator-opened pairing window (isPairingOpen) plus the
// per-IP rate limit and the single-use lock on success are the whole gate. The
// relaxation is /v1-only — the frozen legacy /pair still mandates a code.
//
// The security invariant these tests pin: a code-less pair is IMPOSSIBLE while
// the window is closed, and every successful pair (code-less or code-bearing)
// closes the window single-use. Each test spawns its OWN fresh bridge, so each
// gets its own per-process 5-attempt/5-min rate budget; every test stays well
// under it.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { startBridge, request } from "./helpers.js";

const LOCKOUT_ERROR = "Already paired. Re-pairing requires explicit authorization on the bridge.";

test("code-less /v1/pair succeeds when the window is open", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  // No `code` key at all — a genuinely code-omitting Discover pair (an empty
  // string would still hit the code check). Only the open startup window and
  // the declared proto gate it.
  const pair = await request(bridge.port, "POST", "/v1/pair", {
    body: { proto: 3, deviceName: "wear-discover" },
  });
  assert.equal(pair.status, 200);
  assert.ok(pair.body.token && pair.body.token.length > 0, "a token is minted for a code-less pair");
  assert.ok(pair.body.bridgeId, "the response identifies the bridge");
  assert.equal(pair.body.proto, 3, "the /v1 response echoes the bridge proto");
});

test("code-less /v1/pair is 403 when the window is closed", { timeout: 60_000 }, async (t) => {
  // A bridge that boots with a pre-populated credentials dir comes up LOCKED
  // (no --allow-pairing). This is the direct test of the security invariant:
  // the closed-window 403 fires before any token logic, code or no code.
  const credentialsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-codeless-locked-"));
  t.after(() => fs.rmSync(credentialsDir, { recursive: true, force: true }));

  // First bridge: pair one device (locks the window), then die.
  const bridge1 = await startBridge(t, { credentialsDir });
  const first = await request(bridge1.port, "POST", "/pair", {
    body: { code: bridge1.pairingCode, deviceName: "survivor" },
  });
  assert.equal(first.status, 200);
  await bridge1.stop();

  // Second bridge on the same store: starts locked (stored credential, no
  // --allow-pairing). A code-less Discover pair must 403 exactly like a
  // code-bearing one — the open window is the only thing that could admit it.
  const bridge2 = await startBridge(t, { credentialsDir });
  assert.equal(bridge2.pairingLocked, true, "restarted-with-credentials bridge starts locked");

  const codeless = await request(bridge2.port, "POST", "/v1/pair", {
    body: { proto: 3, deviceName: "wear-discover" },
  });
  assert.equal(codeless.status, 403);
  assert.equal(codeless.body.error, LOCKOUT_ERROR);
});

test("code-less /v1/pair is single-use: a second code-less pair is 403", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);

  const first = await request(bridge.port, "POST", "/v1/pair", {
    body: { proto: 3, deviceName: "wear-a" },
  });
  assert.equal(first.status, 200);

  // The success locked the window; a second code-less pair is refused (no
  // silent re-pair that would deauthenticate the first device).
  const second = await request(bridge.port, "POST", "/v1/pair", {
    body: { proto: 3, deviceName: "wear-b" },
  });
  assert.equal(second.status, 403);
  assert.equal(second.body.error, LOCKOUT_ERROR);
});

test("code-less /v1/pair still requires proto (426 when missing)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  // No code AND no proto: the /v1 min-version gate fires before the window/
  // token path, so a code-less pair without a declared proto is refused 426 —
  // relaxing the code requirement did not relax the version gate.
  const refused = await request(bridge.port, "POST", "/v1/pair", {
    body: { deviceName: "wear-old" },
  });
  assert.equal(refused.status, 426);
  assert.equal(refused.body.minProto, 3, "the refusal names the minimum supported proto");
  // The window was NOT consumed: a proper code-less pair still succeeds after.
  const ok = await request(bridge.port, "POST", "/v1/pair", {
    body: { proto: 3, deviceName: "wear-new" },
  });
  assert.equal(ok.status, 200);
});

test("Manual code path unchanged: wrong code 401, correct code 200", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);

  // A wrong code is still rejected (the window stays open — a bad code neither
  // locks nor consumes it).
  const wrong = await request(bridge.port, "POST", "/v1/pair", {
    body: { code: "000000", proto: 3, deviceName: "wear-manual" },
  });
  assert.equal(wrong.status, 401);
  assert.equal(wrong.body.error, "Invalid pairing code");

  // The correct code still pairs exactly as before the relaxation.
  const right = await request(bridge.port, "POST", "/v1/pair", {
    body: { code: bridge.pairingCode, proto: 3, deviceName: "wear-manual" },
  });
  assert.equal(right.status, 200);
  assert.ok(right.body.token, "the code-bearing Manual path still mints a token");
});

test("legacy /pair with no code is still 400 (the /v1-scoping guard)", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t);
  // The relaxation is /v1-only: the frozen legacy surface must still 400 a
  // missing code. If the require-code check lost its surface scope, this `{}`
  // would code-less-succeed and lock the window — breaking the legacy corpus.
  const missing = await request(bridge.port, "POST", "/pair", { body: {} });
  assert.equal(missing.status, 400);
  assert.equal(missing.body.error, "Missing 'code' field");
});

test("code-less /v1/pair on an EXPIRED reopened window is 403 + relock (the one flagged bypass, pinned)", { timeout: 60_000 }, async (t) => {
  // The design's own "one real bypass": a reopened window whose code TTL has
  // lapsed but which no attempt has yet observed still reads pairingOpen=true,
  // so the closed-window 403 does NOT catch it — only the expiry/relock gate
  // does. That gate sits BEFORE the code check and is code-agnostic, so it must
  // reject a CODE-LESS Discover pair exactly as it rejects a code-bearing one.
  // Making it code-optional touched neither this gate nor the window gate; this
  // test pins that, so a future refactor that scoped the expiry check to
  // `hasCode` (reintroducing the bypass) fails here instead of shipping.
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_PAIRING_CODE_TTL_MS: "1000" } });

  // SIGUSR1 marks the surface reopened (works from the initial window too);
  // then let the freshly minted code's TTL lapse without a successful pair.
  bridge.proc.kill("SIGUSR1");
  await bridge.waitForOutput(/SIGUSR1 received: reopening pairing/);
  await new Promise((r) => setTimeout(r, 1500).unref()); // > the 1000ms TTL

  // The code-less attempt is the FIRST to observe the expiry: it must relock
  // and 403, never mint a token.
  const expired = await request(bridge.port, "POST", "/v1/pair", {
    body: { proto: 3, deviceName: "discover-late" },
  });
  assert.equal(expired.status, 403);
  assert.equal(
    expired.body.error,
    "Pairing code expired and pairing is locked again. Send SIGUSR1 on the bridge to reopen.",
  );
  assert.ok(!expired.body.token, "no token is minted on an expired reopened window");
  assert.match(bridge.output(), /Reopened pairing window expired without a successful pair/);

  // The surface stays locked: a second code-less attempt hits the ordinary
  // lockout latch, not a fresh window.
  const stillLocked = await request(bridge.port, "POST", "/v1/pair", { body: { proto: 3 } });
  assert.equal(stillLocked.status, 403);
  assert.equal(stillLocked.body.error, LOCKOUT_ERROR);
});
