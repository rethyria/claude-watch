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
// DELIBERATE contract change (a PROTOCOL.md version bump for /v1; never for
// legacy, which is frozen):
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

  // --- Permission round-trip: behavior-tagged options, allow-always ---
  const suggestions = [
    { type: "addRules", rules: [{ toolName: "Bash", ruleContent: "ls:*" }], behavior: "allow", destination: "session" },
  ];
  const bashHook = request(port, "POST", "/v1/hooks/permission", {
    body: {
      tool_name: "Bash",
      session_id: "fixture-hook-session-1",
      cwd: "/tmp/fixture-project",
      tool_input: { command: "ls -la" },
      permission_suggestions: suggestions,
    },
  });
  await rec.sseEvent("sse: session running", sse,
    (e) => e.event === "session" && e.parsed?.state === "running",
    (parsed) => rec.learn(parsed?.sessionId, "<session-1>"));
  await rec.sseEvent("sse: permission-request Bash", sse,
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
    (parsed) => rec.learn(parsed?.permissionId, "<permission-1>"));
  const bashPermissionId = sse.events.find((e) => e.event === "permission-request").parsed.permissionId;
  await rec.step("decision allow-always", "POST", "/v1/command", {
    token,
    body: { permissionId: bashPermissionId, decision: { behavior: "allow-always" } },
  });
  rec.recordResponse("hook response: allow-always applies suggestions",
    { request: { method: "POST", path: "/v1/hooks/permission", blocking: true } },
    await bashHook);

  // --- AskUserQuestion: multi-question answers ---
  const questions = [
    { header: "Color", question: "Favorite color?", options: [{ label: "Blue" }, { label: "Red" }], multiSelect: false },
    { header: "Style", question: "Tabs or spaces?", options: [{ label: "Tabs" }, { label: "Spaces" }], multiSelect: false },
  ];
  const askHook = request(port, "POST", "/v1/hooks/permission", {
    body: {
      tool_name: "AskUserQuestion",
      session_id: "fixture-hook-session-1",
      cwd: "/tmp/fixture-project",
      tool_input: { questions },
    },
  });
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
  rec.recordResponse("hook response: AskUserQuestion answers",
    { request: { method: "POST", path: "/v1/hooks/permission", blocking: true } },
    await askHook);

  // --- Deny with a message ---
  const writeHook = request(port, "POST", "/v1/hooks/permission", {
    body: {
      tool_name: "Write",
      session_id: "fixture-hook-session-1",
      cwd: "/tmp/fixture-project",
      tool_input: { file_path: "notes.txt", content: "hello" },
    },
  });
  await rec.sseEvent("sse: permission-request Write (no allow-always without suggestions)", sse,
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Write",
    (parsed) => rec.learn(parsed?.permissionId, "<permission-3>"));
  const writePermissionId = sse.events.find(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Write",
  ).parsed.permissionId;
  await rec.step("decision deny with message", "POST", "/v1/command", {
    token,
    body: { permissionId: writePermissionId, decision: { behavior: "deny", message: "Denied from fixture" } },
  });
  rec.recordResponse("hook response: deny carries message",
    { request: { method: "POST", path: "/v1/hooks/permission", blocking: true } },
    await writeHook);

  // --- The rest of the event catalog ---
  await rec.step("hook notification", "POST", "/v1/hooks/notification", {
    body: { session_id: "fixture-hook-session-1", cwd: "/tmp/fixture-project", notification_type: "permission_prompt", message: "Claude needs your permission" },
  });
  await rec.sseEvent("sse: notification carries notification_type", sse, (e) => e.event === "notification");

  await rec.step("hook tool-output", "POST", "/v1/hooks/tool-output", {
    body: { session_id: "fixture-hook-session-1", cwd: "/tmp/fixture-project", tool_name: "Read", tool_output: "fixture file contents" },
  });
  await rec.sseEvent("sse: tool-output", sse, (e) => e.event === "tool-output");

  await rec.step("hook stop", "POST", "/v1/hooks/stop", {
    body: { session_id: "fixture-hook-session-1", cwd: "/tmp/fixture-project" },
  });
  await rec.sseEvent("sse: stop", sse, (e) => e.event === "stop");

  await rec.step("hook task-complete", "POST", "/v1/hooks/task-complete", {
    body: { session_id: "fixture-hook-session-1", cwd: "/tmp/fixture-project", summary: "fixture task done" },
  });
  const taskCompleteEvent = await rec.sseEvent("sse: task-complete", sse, (e) => e.event === "task-complete");

  await rec.step("hook error", "POST", "/v1/hooks/error", {
    body: { session_id: "fixture-hook-session-1", cwd: "/tmp/fixture-project", error: "fixture error" },
  });
  await rec.sseEvent("sse: error", sse, (e) => e.event === "error");

  await rec.step("hook session-end", "POST", "/v1/hooks/session-end", {
    body: { session_id: "fixture-hook-session-1", cwd: "/tmp/fixture-project" },
  });
  await rec.sseEvent("sse: session ended", sse, (e) => e.event === "session" && e.parsed?.state === "ended");

  // --- Last-Event-ID replay: a reconnecting client catches up ---
  const replaySse = connectSse(port, token, { path: "/v1/events", lastEventId: taskCompleteEvent.id });
  t.after(() => replaySse.close());
  rec.record("events reconnect with Last-Event-ID", { response: { status: await replaySse.statusCode() } });
  await rec.sseEvent("sse replay: error", replaySse, (e) => e.event === "error");
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

  // --- Legacy permission round-trip with the allowAll flag ---
  const suggestions = [
    { type: "addRules", rules: [{ toolName: "Bash", ruleContent: "git status" }], behavior: "allow", destination: "session" },
  ];
  const bashHook = request(port, "POST", "/hooks/permission", {
    body: {
      tool_name: "Bash",
      session_id: "legacy-hook-session-1",
      cwd: "/tmp/legacy-project",
      tool_input: { command: "git status" },
      permission_suggestions: suggestions,
    },
  });
  await rec.sseEvent("sse: session running", sse,
    (e) => e.event === "session" && e.parsed?.state === "running",
    (parsed) => rec.learn(parsed?.sessionId, "<session-1>"));
  await rec.sseEvent("sse: permission-request (options field is additive)", sse,
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
    (parsed) => rec.learn(parsed?.permissionId, "<permission-1>"));
  const bashPermissionId = sse.events.find((e) => e.event === "permission-request").parsed.permissionId;
  await rec.step("decision legacy allowAll", "POST", "/command", {
    token,
    body: { permissionId: bashPermissionId, decision: { behavior: "allow" }, allowAll: true },
  });
  rec.recordResponse("hook response: allowAll applies suggestions",
    { request: { method: "POST", path: "/hooks/permission", blocking: true } },
    await bashHook);

  // --- Legacy AskUserQuestion: single selectedOption answers question 1 ---
  const questions = [
    { header: "Color", question: "Favorite color?", options: [{ label: "Blue" }, { label: "Red" }], multiSelect: false },
    { header: "Style", question: "Tabs or spaces?", options: [{ label: "Tabs" }, { label: "Spaces" }], multiSelect: false },
  ];
  const askHook = request(port, "POST", "/hooks/permission", {
    body: {
      tool_name: "AskUserQuestion",
      session_id: "legacy-hook-session-1",
      cwd: "/tmp/legacy-project",
      tool_input: { questions },
    },
  });
  await rec.sseEvent("sse: permission-request AskUserQuestion", sse,
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "AskUserQuestion",
    (parsed) => rec.learn(parsed?.permissionId, "<permission-2>"));
  const askPermissionId = sse.events.find(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "AskUserQuestion",
  ).parsed.permissionId;
  await rec.step("decision legacy selectedOption", "POST", "/command", {
    token,
    body: { permissionId: askPermissionId, decision: { behavior: "allow" }, selectedOption: "Blue" },
  });
  rec.recordResponse("hook response: selectedOption answers first question only",
    { request: { method: "POST", path: "/hooks/permission", blocking: true } },
    await askHook);

  // --- Frozen event shapes ---
  await rec.step("hook tool-output", "POST", "/hooks/tool-output", {
    body: { session_id: "legacy-hook-session-1", cwd: "/tmp/legacy-project", tool_name: "Read", tool_output: "legacy file contents" },
  });
  const toolOutputEvent = await rec.sseEvent("sse: tool-output", sse, (e) => e.event === "tool-output");

  await rec.step("hook stop", "POST", "/hooks/stop", {
    body: { session_id: "legacy-hook-session-1", cwd: "/tmp/legacy-project" },
  });
  await rec.sseEvent("sse: stop", sse, (e) => e.event === "stop");

  // --- Last-Event-ID replay on the legacy stream ---
  const replaySse = connectSse(port, token, { lastEventId: toolOutputEvent.id });
  t.after(() => replaySse.close());
  rec.record("events reconnect with Last-Event-ID", { response: { status: await replaySse.statusCode() } });
  await rec.sseEvent("sse replay: stop", replaySse, (e) => e.event === "stop");

  await rec.step("command unauthenticated", "POST", "/command", { body: { command: "hello\n" } });

  rec.finish("legacy-corpus.json");
});
