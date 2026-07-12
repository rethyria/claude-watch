// Bridge identity persistence: clients pin the /ping bridgeId at pair time
// and verify it on every reconnect preflight (the wrong-Mac guard). Bearer
// tokens survive restarts, so the id they are pinned to must too — a
// per-process random id would put every paired client into a terminal
// "different bridge, re-pair required" state after any routine restart, with
// the pairing surface locked.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { startBridge, request } from "./helpers.js";

function tempDir(t, prefix) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  t.after(() => { try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* ignore */ } });
  return dir;
}

test("bridgeId survives a bridge restart, so pinned clients can reconnect", { timeout: 60_000 }, async (t) => {
  const credsDir = tempDir(t, "claude-watch-bridge-id-");

  const first = await startBridge(t, { credentialsDir: credsDir });
  const firstPing = await request(first.port, "GET", "/ping");
  assert.equal(firstPing.status, 200);
  const bridgeId = firstPing.body.bridgeId;
  assert.match(bridgeId, /^[0-9a-f-]{36}$/, "bridgeId must be a UUID");

  // A device pairs and the bridge restarts (Mac reboot, bridge upgrade...).
  // The persisted token still authenticates after the restart, so the
  // identity it was pinned to must still answer — otherwise the client
  // refuses to offer the token and dead-ends in a re-pair prompt while the
  // pairing surface is locked.
  const pair = await request(first.port, "POST", "/pair", { body: { code: first.pairingCode } });
  assert.equal(pair.status, 200);
  await first.stop();

  const second = await startBridge(t, { credentialsDir: credsDir });
  const secondPing = await request(second.port, "GET", "/ping");
  assert.equal(secondPing.status, 200);
  assert.equal(
    secondPing.body.bridgeId,
    bridgeId,
    "a restarted bridge must keep its identity — pinned clients refuse a changed bridgeId",
  );

  // The persisted pairing still works against the restarted bridge.
  const status = await request(second.port, "GET", "/status", { token: pair.body.token });
  assert.equal(status.status, 200, "the pre-restart token must authenticate after the restart");
});

test("a corrupt bridge-id file mints a fresh persistent id instead of serving garbage", { timeout: 60_000 }, async (t) => {
  const credsDir = tempDir(t, "claude-watch-bridge-id-");
  fs.writeFileSync(path.join(credsDir, "bridge-id"), "not-a-uuid\n");

  const first = await startBridge(t, { credentialsDir: credsDir });
  const firstPing = await request(first.port, "GET", "/ping");
  assert.equal(firstPing.status, 200);
  assert.match(firstPing.body.bridgeId, /^[0-9a-f-]{36}$/, "corrupt file must be replaced by a fresh UUID");
  assert.notEqual(firstPing.body.bridgeId, "not-a-uuid");
  await first.stop();

  // The freshly minted id was persisted: stable from now on.
  const second = await startBridge(t, { credentialsDir: credsDir });
  const secondPing = await request(second.port, "GET", "/ping");
  assert.equal(secondPing.body.bridgeId, firstPing.body.bridgeId);
});
