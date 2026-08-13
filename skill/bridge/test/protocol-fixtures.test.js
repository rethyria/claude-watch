// Recorded protocol fixtures (issue #14): the executable wire contract.
//
// Each scenario drives the REAL bridge process black-box (same helpers as the
// rest of the suite), records every request, response, and SSE event, and
// deep-compares the normalized recording against the checked-in corpus:
//
//   test/fixtures/v1-corpus.json      — the /v1 surface (PROTOCOL.md)
//   test/fixtures/legacy-corpus.json  — the FROZEN legacy surface: replaying
//                                       it green proves existing iOS/watchOS
//                                       clients keep working
//
// The fixtures were derived from real bridge responses. To regenerate after a
// DELIBERATE contract change (a PROTOCOL.md change for /v1; the legacy
// surface is frozen for CLIENTS — its one non-client edit was #87's removal
// of the server-local hook steps, whose stimulus role the ACP uplink now
// plays):
//
//   CLAUDE_WATCH_UPDATE_FIXTURES=1 node --test test/protocol-fixtures.test.js
//
// Volatile values (tokens, UUIDs, hostname, timestamps) are learned during
// the run and replaced with stable placeholders, so the corpus captures the
// SHAPE and semantics — including that the legacy top-level sessionId is the
// same value as bridgeId (both normalize to "<bridge-id>"), while /v1
// responses carry no such alias.
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { startBridge, request, connectSse } from "./helpers.js";

const FIXTURES_DIR = fileURLToPath(new URL("./fixtures", import.meta.url));
const UPDATE_FIXTURES = process.env.CLAUDE_WATCH_UPDATE_FIXTURES === "1";

// SSE waits: this suite runs alongside emulators and parallel test files.
const SSE_WAIT_MS = 30_000;

// A deterministic bridge for recording: an empty HOME (so the Codex monitor
// finds no real ~/.codex sessions to surface) and stubbed agent binaries
// (never spawned — no PTY output enters the recording — but they make
// availableAgents a stable ["claude", "codex"]).
async function startFixtureBridge(t) {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-fixture-home-"));
  t.after(() => fs.rmSync(home, { recursive: true, force: true }));
  return startBridge(t, {
    env: {
      HOME: home,
      CLAUDE_WATCH_CLAUDE_BIN: process.execPath,
      CLAUDE_WATCH_CODEX_BIN: process.execPath,
    },
  });
}

// ---------------------------------------------------------------------------
// Recorder: normalization + fixture compare/update
// ---------------------------------------------------------------------------

function createRecorder(port) {
  const redactions = new Map(); // exact volatile value -> stable placeholder
  const recording = [];

  function normalize(value) {
    if (Array.isArray(value)) return value.map(normalize);
    if (value && typeof value === "object") {
      const out = {};
      for (const [key, entry] of Object.entries(value)) {
        if ((key === "createdAt" || key === "endedAt") && typeof entry === "number") {
          out[key] = "<timestamp>";
        } else {
          out[key] = normalize(entry);
        }
      }
      return out;
    }
    if (typeof value === "string" && redactions.has(value)) return redactions.get(value);
    return value;
  }

  return {
    // Register a volatile runtime value so every later occurrence (in any
    // request, response, or event) normalizes to the same placeholder.
    learn(actual, placeholder) {
      if (typeof actual === "string" && actual.length > 0 && !redactions.has(actual)) {
        redactions.set(actual, placeholder);
      }
    },

    // Issue a request and record its normalized request/response pair.
    // `learn(res)` runs before normalization so identifiers minted by this
    // very response (tokens, bridgeId) redact themselves.
    async step(name, method, reqPath, { token, body } = {}, learn) {
      const res = await request(port, method, reqPath, { token, body });
      if (learn) learn(res);
      recording.push({
        name,
        request: {
          method,
          path: reqPath,
          authenticated: Boolean(token),
          body: body === undefined ? null : normalize(body),
        },
        response: { status: res.status, body: normalize(res.body) },
      });
      return res;
    },

    // Record an already-received response (e.g. a blocking hook's).
    recordResponse(name, description, res) {
      recording.push({ name, ...description, response: { status: res.status, body: normalize(res.body) } });
    },

    // Await a matching SSE event, learn identifiers from it, record it.
    async sseEvent(name, sse, predicate, learn) {
      const event = await sse.waitFor(predicate, SSE_WAIT_MS);
      if (learn) learn(event.parsed);
      recording.push({ name, event: { event: event.event, data: normalize(event.parsed) } });
      return event;
    },

    record(name, entry) {
      recording.push({ name, ...normalize(entry) });
    },

    // Compare the full recording against the checked-in corpus (or rewrite it
    // in update mode). Step-by-step comparison keeps mismatch output readable.
    finish(fixtureBasename) {
      const fixtureFile = path.join(FIXTURES_DIR, fixtureBasename);
      if (UPDATE_FIXTURES) {
        fs.mkdirSync(FIXTURES_DIR, { recursive: true });
        fs.writeFileSync(fixtureFile, JSON.stringify(recording, null, 2) + "\n");
        return;
      }
      const fixture = JSON.parse(fs.readFileSync(fixtureFile, "utf-8"));
      assert.deepEqual(
        recording.map((s) => s.name),
        fixture.map((s) => s.name),
        `step sequence diverged from ${fixtureBasename}`,
      );
      fixture.forEach((expected, i) => {
        assert.deepEqual(recording[i], expected, `step "${expected.name}" diverged from ${fixtureBasename}`);
      });
    },
  };
}

// ---------------------------------------------------------------------------
// ACP-side stimulus. The corpus records the CLIENT wire only; the fork's
// server-local /acp/* uplink is the stimulus that produces it (the hook
// surface that used to play this role was retired in #87), so these calls are
// deliberately NOT recorded as corpus steps.
// ---------------------------------------------------------------------------

async function acpRegister(port, sessionId, cwd) {
  const res = await request(port, "POST", "/acp/register", {
    body: { connection: "conn-fixture", sessionId, sdkSessionId: sessionId, cwd },
  });
  assert.equal(res.status, 200);
}

async function acpUpdate(port, sessionId, kind, payload) {
  const res = await request(port, "POST", "/acp/update", {
    body: { connection: "conn-fixture", sessionId, kind, payload },
  });
  assert.equal(res.status, 200);
}

function acpPermissionPayload(sessionId, toolCallId, title, rawInput) {
  return {
    sessionId,
    toolCall: { toolCallId, title, rawInput },
    options: [
      { optionId: "allow_always", name: "Always Allow", kind: "allow_always" },
      { optionId: "allow", name: "Allow", kind: "allow_once" },
      { optionId: "reject", name: "Reject", kind: "reject_once" },
    ],
  };
}

// ---------------------------------------------------------------------------
// /v1 corpus
// ---------------------------------------------------------------------------

test("recorded /v1 fixture corpus replays green against the current bridge", { timeout: 180_000 }, async (t) => {
  const bridge = await startFixtureBridge(t);
  const { port, pairingCode } = bridge;
  const rec = createRecorder(port);
  rec.learn(pairingCode, "<pairing-code>");
  rec.learn(os.hostname(), "<machine-name>");

  // --- Discovery and auth gates ---
  await rec.step("ping", "GET", "/v1/ping", {}, (res) => {
    rec.learn(res.body?.bridgeId, "<bridge-id>");
  });
  await rec.step("status unauthenticated", "GET", "/v1/status");
  const anonSse = connectSse(port, null, { path: "/v1/events" });
  t.after(() => anonSse.close());
  rec.record("events unauthenticated", { response: { status: await anonSse.statusCode() } });

  // --- Pairing, including the min-version gate ---
  await rec.step("pair refused below min proto", "POST", "/v1/pair", {
    body: { code: pairingCode, proto: 2, deviceName: "fixture-watch" },
  });
  await rec.step("pair refused without proto", "POST", "/v1/pair", {
    body: { code: pairingCode, deviceName: "fixture-watch" },
  });
  const pair = await rec.step("pair", "POST", "/v1/pair", {
    body: { code: pairingCode, proto: 3, deviceName: "fixture-watch" },
  }, (res) => rec.learn(res.body?.token, "<token>"));
  const token = pair.body.token;
  await rec.step("pair locked after success", "POST", "/v1/pair", {
    body: { code: pairingCode, proto: 3, deviceName: "second-watch" },
  });
  await rec.step("status", "GET", "/v1/status", { token });

  // --- Event stream ---
  const sse = connectSse(port, token, { path: "/v1/events" });
  t.after(() => sse.close());
  rec.record("events connect", { response: { status: await sse.statusCode() } });

  // --- An ACP session registers and its running event reaches the wire ---
  await acpRegister(port, "fixture-acp-session-1", "/tmp/fixture-project");
  await rec.sseEvent("sse: session running", sse,
    (e) => e.event === "session" && e.parsed?.state === "running",
    (parsed) => rec.learn(parsed?.sessionId, "<session-1>"));

  // --- Permission round-trip: behavior-tagged options, allow-always ---
  await acpUpdate(port, "fixture-acp-session-1", "permission",
    acpPermissionPayload("fixture-acp-session-1", "fixture-tool-call-1", "Bash", { command: "ls -la" }));
  await rec.sseEvent("sse: permission-request Bash", sse,
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
    (parsed) => rec.learn(parsed?.permissionId, "<permission-1>"));
  const bashPermissionId = sse.events.find((e) => e.event === "permission-request").parsed.permissionId;
  await rec.step("decision allow-always", "POST", "/v1/command", {
    token,
    body: { permissionId: bashPermissionId, decision: { behavior: "allow-always" } },
  });

  // --- AskUserQuestion: multi-question answers ---
  const questions = [
    { header: "Color", question: "Favorite color?", options: [{ label: "Blue" }, { label: "Red" }], multiSelect: false },
    { header: "Style", question: "Tabs or spaces?", options: [{ label: "Tabs" }, { label: "Spaces" }], multiSelect: false },
  ];
  await acpUpdate(port, "fixture-acp-session-1", "input-request",
    { sessionId: "fixture-acp-session-1", toolCallId: "fixture-tool-call-2", questions });
  await rec.sseEvent("sse: permission-request AskUserQuestion (no top-level options)", sse,
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "AskUserQuestion",
    (parsed) => rec.learn(parsed?.permissionId, "<permission-2>"));
  const askPermissionId = sse.events.find(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "AskUserQuestion",
  ).parsed.permissionId;
  await rec.step("decision with answers for every question", "POST", "/v1/command", {
    token,
    body: { permissionId: askPermissionId, decision: { behavior: "allow" }, answers: ["Blue", "Tabs"] },
  });

  // --- Deny with a message ---
  await acpUpdate(port, "fixture-acp-session-1", "permission",
    acpPermissionPayload("fixture-acp-session-1", "fixture-tool-call-3", "Write", { file_path: "notes.txt", content: "hello" }));
  await rec.sseEvent("sse: permission-request Write", sse,
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Write",
    (parsed) => rec.learn(parsed?.permissionId, "<permission-3>"));
  const writePermissionId = sse.events.find(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Write",
  ).parsed.permissionId;
  await rec.step("decision deny with message", "POST", "/v1/command", {
    token,
    body: { permissionId: writePermissionId, decision: { behavior: "deny", message: "Denied from fixture" } },
  });

  // --- The rest of the event catalog ---
  // Assistant prose, coalesced and flushed at the turn end.
  await acpUpdate(port, "fixture-acp-session-1", "turn", { phase: "start" });
  await acpUpdate(port, "fixture-acp-session-1", "session_update", {
    sessionId: "fixture-acp-session-1",
    update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "fixture prose" } },
  });
  await acpUpdate(port, "fixture-acp-session-1", "turn", { phase: "end" });
  await rec.sseEvent("sse: message (coalesced prose)", sse, (e) => e.event === "message");
  const idleEvent = await rec.sseEvent("sse: session running carries idle after the turn end", sse,
    (e) => e.event === "session" && e.parsed?.state === "running" && e.parsed?.idle === true);

  // The fork's deregister ends the slot.
  const dereg = await request(port, "POST", "/acp/deregister", {
    body: { connection: "conn-fixture", sessionId: "fixture-acp-session-1", reason: "query-closed" },
  });
  assert.equal(dereg.status, 200);
  await rec.sseEvent("sse: session ended", sse, (e) => e.event === "session" && e.parsed?.state === "ended");

  // --- Last-Event-ID replay: a reconnecting client catches up ---
  const replaySse = connectSse(port, token, { path: "/v1/events", lastEventId: idleEvent.id });
  t.after(() => replaySse.close());
  rec.record("events reconnect with Last-Event-ID", { response: { status: await replaySse.statusCode() } });
  await rec.sseEvent("sse replay: session ended", replaySse, (e) => e.event === "session" && e.parsed?.state === "ended");

  // --- Command surface error shapes ---
  await rec.step("command unauthenticated", "POST", "/v1/command", { body: { command: "hello\n" } });
  await rec.step("command without any action", "POST", "/v1/command", { token, body: {} });

  rec.finish("v1-corpus.json");
});

// ---------------------------------------------------------------------------
// Legacy corpus (freeze proof)
// ---------------------------------------------------------------------------

test("frozen legacy fixtures replay green against the current bridge", { timeout: 180_000 }, async (t) => {
  const bridge = await startFixtureBridge(t);
  const { port, pairingCode } = bridge;
  const rec = createRecorder(port);
  rec.learn(pairingCode, "<pairing-code>");
  rec.learn(os.hostname(), "<machine-name>");

  await rec.step("ping", "GET", "/ping", {}, (res) => {
    rec.learn(res.body?.bridgeId, "<bridge-id>");
  });

  // --- Frozen pairing shapes, including proof that legacy /pair performs no
  // protocol-version check (an ancient client body pairs unchanged) and that
  // the response keeps the top-level sessionId alias for bridgeId ---
  await rec.step("pair missing code", "POST", "/pair", { body: {} });
  await rec.step("pair invalid code", "POST", "/pair", { body: { code: "000000" } });
  const pair = await rec.step("pair (no version gate on legacy)", "POST", "/pair", {
    body: { code: pairingCode, proto: 1, deviceName: "legacy-watch" },
  }, (res) => rec.learn(res.body?.token, "<token>"));
  const token = pair.body.token;
  await rec.step("pair locked after success", "POST", "/pair", { body: { code: pairingCode } });

  await rec.step("status keeps sessionId alias", "GET", "/status", { token });

  const sse = connectSse(port, token);
  t.after(() => sse.close());
  rec.record("events connect", { response: { status: await sse.statusCode() } });

  // --- A session registers; the legacy stream carries its running event ---
  await acpRegister(port, "legacy-acp-session-1", "/tmp/legacy-project");
  await rec.sseEvent("sse: session running", sse,
    (e) => e.event === "session" && e.parsed?.state === "running",
    (parsed) => rec.learn(parsed?.sessionId, "<session-1>"));

  // --- Legacy permission round-trip with the allowAll flag ---
  await acpUpdate(port, "legacy-acp-session-1", "permission",
    acpPermissionPayload("legacy-acp-session-1", "legacy-tool-call-1", "Bash", { command: "git status" }));
  await rec.sseEvent("sse: permission-request (options field is additive)", sse,
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
    (parsed) => rec.learn(parsed?.permissionId, "<permission-1>"));
  const bashPermissionId = sse.events.find((e) => e.event === "permission-request").parsed.permissionId;
  await rec.step("decision legacy allowAll", "POST", "/command", {
    token,
    body: { permissionId: bashPermissionId, decision: { behavior: "allow" }, allowAll: true },
  });

  // --- Legacy AskUserQuestion: single selectedOption answers question 1 ---
  const questions = [
    { header: "Color", question: "Favorite color?", options: [{ label: "Blue" }, { label: "Red" }], multiSelect: false },
    { header: "Style", question: "Tabs or spaces?", options: [{ label: "Tabs" }, { label: "Spaces" }], multiSelect: false },
  ];
  await acpUpdate(port, "legacy-acp-session-1", "input-request",
    { sessionId: "legacy-acp-session-1", toolCallId: "legacy-tool-call-2", questions });
  const askEvent = await rec.sseEvent("sse: permission-request AskUserQuestion", sse,
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "AskUserQuestion",
    (parsed) => rec.learn(parsed?.permissionId, "<permission-2>"));
  const askPermissionId = sse.events.find(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "AskUserQuestion",
  ).parsed.permissionId;
  await rec.step("decision legacy selectedOption", "POST", "/command", {
    token,
    body: { permissionId: askPermissionId, decision: { behavior: "allow" }, selectedOption: "Blue" },
  });

  // --- Frozen event shapes: the ended session, and Last-Event-ID replay ---
  const dereg = await request(port, "POST", "/acp/deregister", {
    body: { connection: "conn-fixture", sessionId: "legacy-acp-session-1", reason: "query-closed" },
  });
  assert.equal(dereg.status, 200);
  await rec.sseEvent("sse: session ended", sse, (e) => e.event === "session" && e.parsed?.state === "ended");

  const replaySse = connectSse(port, token, { lastEventId: askEvent.id });
  t.after(() => replaySse.close());
  rec.record("events reconnect with Last-Event-ID", { response: { status: await replaySse.statusCode() } });
  await rec.sseEvent("sse replay: session ended", replaySse, (e) => e.event === "session" && e.parsed?.state === "ended");

  await rec.step("command unauthenticated", "POST", "/command", { body: { command: "hello\n" } });

  rec.finish("legacy-corpus.json");
});
