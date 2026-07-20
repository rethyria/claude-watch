// Permission honesty and answered-elsewhere detection, black-box (issue #63).
//
// Two independent defects lived here. The bridge auto-DENIED a prompt nobody
// answered — and Claude Code acts on that deny, cancelling the dialog the user
// still had on screen and writing a `reject` they never chose. And it dropped
// the pending entry SILENTLY, so a watch kept rendering a card the bridge had
// already finished with, until the app was force-stopped.
//
// The fixes: every non-answer exit returns NO DECISION and announces itself
// with `permission-cleared`; a PostToolUse carrying the prompt's tool_use_id
// proves it was answered on the computer and clears it immediately; and the
// connect-time sync carries an authoritative id set so a watch that was
// offline when a prompt died drops it on reconnect.
import { test } from "node:test";
import assert from "node:assert/strict";
import { startBridge, request, connectSse } from "./helpers.js";

// Raw fetch for the blocking permission hook: these tests need it in flight
// (unawaited) while other hooks arrive, which request() cannot express.
function postPermissionHook(port, body, signal) {
  return fetch(`http://127.0.0.1:${port}/hooks/permission`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal,
  });
}

async function pairAndConnect(t, bridge) {
  const { port, pairingCode } = bridge;
  const pair = await request(port, "POST", "/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;
  const sse = connectSse(port, token);
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  return { token, sse };
}

const settle = (ms) => new Promise((r) => setTimeout(r, ms).unref());

// ---------------------------------------------------------------------------
// Honest expiry (the defect users actually hit)
// ---------------------------------------------------------------------------

test("expired permission returns no decision, never a deny", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "1200" } });
  const { port } = bridge;
  await pairAndConnect(t, bridge);

  const hookRes = await postPermissionHook(port, {
    tool_name: "Bash",
    cwd: "/tmp/honesty-project",
    tool_input: { command: "rm -rf ./build" },
    permission_suggestions: [{ type: "rule", value: "Bash(rm:*)" }],
  });
  assert.equal(hookRes.status, 200);
  const body = await hookRes.json();

  // The whole point: an empty body makes Claude Code's runHooks fall through
  // to `return null`, which exits BEFORE `f.cancelRequest(y)` — so the user's
  // dialog survives. Any hookSpecificOutput here is a fabricated decision.
  assert.deepEqual(body, {}, "expiry must answer with an empty object");
  assert.equal(body.hookSpecificOutput, undefined, "expiry must not fabricate a decision");
  await bridge.waitForOutput(/expired after 1\.2s unanswered/);
});

test("expired permission tells connected clients to drop the card", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "1200" } });
  const { port } = bridge;
  const { token, sse } = await pairAndConnect(t, bridge);

  const inFlight = postPermissionHook(port, {
    tool_name: "Bash",
    cwd: "/tmp/honesty-project",
    tool_input: { command: "make deploy" },
  });

  const prompt = await sse.waitFor((e) => e.event === "permission-request");
  const permissionId = prompt.parsed.permissionId;

  // Before #63 nothing was ever pushed here, so the watch rendered this card
  // indefinitely while the bridge had already moved on.
  const cleared = await sse.waitFor(
    (e) => e.event === "permission-cleared" && e.parsed?.permissionId === permissionId,
  );
  assert.equal(cleared.parsed.reason, "expired");

  await inFlight;
  const status = await request(port, "GET", "/status", { token });
  assert.equal(status.body.pendingPermissions, 0, "expired permission must not stay pending");
});

// ---------------------------------------------------------------------------
// Answered elsewhere: exact tool_use_id correlation
// ---------------------------------------------------------------------------

test("PostToolUse for the prompt's tool_use_id clears it long before the timeout", { timeout: 60_000 }, async (t) => {
  // The timeout is deliberately enormous: if this passes it is because the
  // correlation fired, not because the clock ran out.
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "300000" } });
  const { port } = bridge;
  const { token, sse } = await pairAndConnect(t, bridge);

  const toolInput = { command: "ls -la" };

  // PreToolUse fires 5-17ms before PermissionRequest in production and is the
  // ONLY carrier of tool_use_id — PermissionRequest itself has none.
  await request(port, "POST", "/hooks/tool-output", {
    body: {
      hook_event_name: "PreToolUse",
      tool_name: "Bash",
      tool_input: toolInput,
      tool_use_id: "tu_correlated",
      cwd: "/tmp/honesty-project",
    },
  });

  const inFlight = postPermissionHook(port, {
    tool_name: "Bash",
    tool_input: toolInput,
    cwd: "/tmp/honesty-project",
  });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");
  const permissionId = prompt.parsed.permissionId;
  await bridge.waitForOutput(/PermissionRequest received .*tool_use=tu_correlated/);

  // The user approves in the VS Code dialog. Claude Code does NOT abort this
  // hook request — it just runs the tool and reports it.
  await request(port, "POST", "/hooks/tool-output", {
    body: {
      hook_event_name: "PostToolUse",
      tool_name: "Bash",
      tool_input: toolInput,
      tool_response: { stdout: "total 0\n" },
      tool_use_id: "tu_correlated",
      cwd: "/tmp/honesty-project",
    },
  });

  const cleared = await sse.waitFor(
    (e) => e.event === "permission-cleared" && e.parsed?.permissionId === permissionId,
    10_000,
  );
  assert.equal(cleared.parsed.reason, "answered-elsewhere");

  const hookBody = await (await inFlight).json();
  assert.deepEqual(hookBody, {}, "a prompt answered elsewhere must not be answered again here");

  const status = await request(port, "GET", "/status", { token });
  assert.equal(status.body.pendingPermissions, 0);
});

test("correlation survives key reordering in array-bearing AskUserQuestion input", { timeout: 60_000 }, async (t) => {
  // Every other correlation test uses a flat {command} scalar. Production's
  // dominant prompt is AskUserQuestion, whose tool_input is
  // {questions:[{header,question,options:[...]}]} — an ARRAY of NESTED objects.
  // Pairing binds only if stableStringify hashes the PreToolUse body and the
  // PermissionRequest body identically, and Claude Code re-serializes those
  // nested objects between the two hooks: the keys arrive in DIFFERENT order.
  // So this is the exact case the recursive key-sort inside the array branch
  // exists for. A regression that stops normalizing keys inside array elements
  // (e.g. JSON.stringify-per-element) would fingerprint the two bodies
  // differently and silently drop the correlation for the tool type that most
  // needs it — falling back to the full expiry window while the whole suite,
  // which only ever exercises flat scalars, stayed green.
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "300000" } });
  const { port } = bridge;
  const { token, sse } = await pairAndConnect(t, bridge);

  // The PreToolUse carries the tool_use_id and one key ordering...
  const preInput = {
    questions: [
      {
        header: "Ship it?",
        question: "Deploy to prod?",
        multiSelect: false,
        options: [{ label: "Yes", description: "go" }, { label: "No" }],
      },
    ],
  };
  // ...the PermissionRequest that follows carries the SAME questions with keys
  // reordered at every level (outer question object AND each nested option),
  // exactly as a re-serialization does. Only recursive key-sorting through the
  // array branch collapses these two to one fingerprint.
  const permInput = {
    questions: [
      {
        options: [{ description: "go", label: "Yes" }, { label: "No" }],
        question: "Deploy to prod?",
        multiSelect: false,
        header: "Ship it?",
      },
    ],
  };

  await request(port, "POST", "/hooks/tool-output", {
    body: {
      hook_event_name: "PreToolUse",
      tool_name: "AskUserQuestion",
      tool_input: preInput,
      tool_use_id: "tu_auq",
    },
  });

  const inFlight = postPermissionHook(port, {
    tool_name: "AskUserQuestion",
    tool_input: permInput,
  });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");
  const permissionId = prompt.parsed.permissionId;
  // Correlation fired only if the two reordered bodies fingerprinted equal.
  await bridge.waitForOutput(/PermissionRequest received .*tool_use=tu_auq/);

  // Answered in the IDE: the tool ran, PostToolUse reports it (keyed by
  // tool_use_id, so its own input ordering is irrelevant to the clear).
  await request(port, "POST", "/hooks/tool-output", {
    body: {
      hook_event_name: "PostToolUse",
      tool_name: "AskUserQuestion",
      tool_input: preInput,
      tool_response: {},
      tool_use_id: "tu_auq",
    },
  });

  const cleared = await sse.waitFor(
    (e) => e.event === "permission-cleared" && e.parsed?.permissionId === permissionId,
    10_000,
  );
  assert.equal(cleared.parsed.reason, "answered-elsewhere");

  const hookBody = await (await inFlight).json();
  assert.deepEqual(hookBody, {}, "an AskUserQuestion answered elsewhere must not be answered again here");

  const status = await request(port, "GET", "/status", { token });
  assert.equal(status.body.pendingPermissions, 0);
});

test("PostToolUseFailure also proves the prompt was answered elsewhere", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "300000" } });
  const { port } = bridge;
  const { sse } = await pairAndConnect(t, bridge);

  const toolInput = { command: "false" };
  await request(port, "POST", "/hooks/tool-output", {
    body: { hook_event_name: "PreToolUse", tool_name: "Bash", tool_input: toolInput, tool_use_id: "tu_failed" },
  });
  const inFlight = postPermissionHook(port, { tool_name: "Bash", tool_input: toolInput });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");

  // The tool ran and failed. It still RAN, so it was still allowed.
  await request(port, "POST", "/hooks/error", {
    body: {
      hook_event_name: "PostToolUseFailure",
      tool_name: "Bash",
      tool_input: toolInput,
      tool_use_id: "tu_failed",
      error: "exit status 1",
    },
  });

  const cleared = await sse.waitFor(
    (e) => e.event === "permission-cleared" && e.parsed?.permissionId === prompt.parsed.permissionId,
    10_000,
  );
  assert.equal(cleared.parsed.reason, "answered-elsewhere");
  await inFlight;
});

test("a sibling tool's PostToolUse does not dismiss a live prompt", { timeout: 60_000 }, async (t) => {
  // The regression guard for the alternative design that was rejected. Scoring
  // session-activity inference against the production log showed it would have
  // cleared a LIVE prompt 16ms after it appeared, because parallel subagent
  // traffic straddled the registration. Exact id matching cannot do that.
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "300000" } });
  const { port } = bridge;
  const { token, sse } = await pairAndConnect(t, bridge);

  const siblingInput = { command: "ls" };
  const gatedInput = { command: "pwd" };
  await request(port, "POST", "/hooks/tool-output", {
    body: { hook_event_name: "PreToolUse", tool_name: "Bash", tool_input: siblingInput, tool_use_id: "tu_sibling" },
  });
  await request(port, "POST", "/hooks/tool-output", {
    body: { hook_event_name: "PreToolUse", tool_name: "Bash", tool_input: gatedInput, tool_use_id: "tu_gated" },
  });

  postPermissionHook(port, { tool_name: "Bash", tool_input: gatedInput, cwd: "/tmp/honesty-project" });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");

  // The SIBLING finishes. It says nothing about the gated prompt.
  await request(port, "POST", "/hooks/tool-output", {
    body: {
      hook_event_name: "PostToolUse",
      tool_name: "Bash",
      tool_input: siblingInput,
      tool_response: { stdout: "" },
      tool_use_id: "tu_sibling",
    },
  });

  await settle(700);
  assert.ok(
    !sse.events.some((e) => e.event === "permission-cleared"),
    "a sibling tool completing must never clear another prompt",
  );
  const status = await request(port, "GET", "/status", { token });
  assert.equal(status.body.pendingPermissions, 1, "the gated prompt must still be live");
  assert.ok(prompt.parsed.permissionId);
});

test("two identical concurrent tool calls leave the permission uncorrelated rather than guessing", { timeout: 60_000 }, async (t) => {
  // PermissionRequest carries no tool_use_id, so pairing is by
  // (tool_name, tool_input). Two live calls with an identical fingerprint are
  // indistinguishable — binding to either would risk clearing the WRONG card,
  // which is strictly worse than letting it expire honestly.
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "300000" } });
  const { port } = bridge;
  const { token, sse } = await pairAndConnect(t, bridge);

  const sameInput = { command: "date" };
  await request(port, "POST", "/hooks/tool-output", {
    body: { hook_event_name: "PreToolUse", tool_name: "Bash", tool_input: sameInput, tool_use_id: "tu_twin_a" },
  });
  await request(port, "POST", "/hooks/tool-output", {
    body: { hook_event_name: "PreToolUse", tool_name: "Bash", tool_input: sameInput, tool_use_id: "tu_twin_b" },
  });

  postPermissionHook(port, { tool_name: "Bash", tool_input: sameInput });
  await sse.waitFor((e) => e.event === "permission-request");
  await bridge.waitForOutput(/PermissionRequest received .*\(uncorrelated\)/);

  // Neither twin's completion may clear it.
  for (const id of ["tu_twin_a", "tu_twin_b"]) {
    await request(port, "POST", "/hooks/tool-output", {
      body: {
        hook_event_name: "PostToolUse",
        tool_name: "Bash",
        tool_input: sameInput,
        tool_response: { stdout: "" },
        tool_use_id: id,
      },
    });
  }
  await settle(700);
  assert.ok(
    !sse.events.some((e) => e.event === "permission-cleared"),
    "an ambiguous fingerprint must fall back to expiry, never guess",
  );
  const status = await request(port, "GET", "/status", { token });
  assert.equal(status.body.pendingPermissions, 1);
});

test("a PreToolUse must never be misread as a completion and clear a live prompt", { timeout: 60_000 }, async (t) => {
  // PreToolUse and PostToolUse arrive on the SAME endpoint, so the handler
  // must discriminate on hook_event_name — NOT on "has a tool_use_id that
  // matches a pending permission". A re-emitted PreToolUse for the gated tool
  // carries exactly that matching id; a handler that cleared on it would
  // dismiss the live prompt. Run this as a pair with the correlation test
  // above: if that one ever fails, this one's green means nothing.
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "300000" } });
  const { port } = bridge;
  const { token, sse } = await pairAndConnect(t, bridge);

  const toolInput = { command: "whoami" };
  await request(port, "POST", "/hooks/tool-output", {
    body: { hook_event_name: "PreToolUse", tool_name: "Bash", tool_input: toolInput, tool_use_id: "tu_pre_only" },
  });
  postPermissionHook(port, { tool_name: "Bash", tool_input: toolInput });
  await sse.waitFor((e) => e.event === "permission-request");
  await bridge.waitForOutput(/PermissionRequest received .*tool_use=tu_pre_only/);

  // A second PreToolUse for the same gated tool_use_id lands while the prompt
  // is live (a duplicate/re-emit). It must be recorded, never treated as the
  // tool having run.
  await request(port, "POST", "/hooks/tool-output", {
    body: { hook_event_name: "PreToolUse", tool_name: "Bash", tool_input: toolInput, tool_use_id: "tu_pre_only" },
  });

  await settle(700);
  assert.ok(
    !sse.events.some((e) => e.event === "permission-cleared"),
    "a PreToolUse must never clear a prompt",
  );
  const status = await request(port, "GET", "/status", { token });
  assert.equal(status.body.pendingPermissions, 1);
});

// ---------------------------------------------------------------------------
// Answering on the watch: unchanged
// ---------------------------------------------------------------------------

test("answering on the watch still resolves the hook with a real decision", { timeout: 60_000 }, async (t) => {
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "300000" } });
  const { port } = bridge;
  const { token, sse } = await pairAndConnect(t, bridge);

  const toolInput = { command: "git status" };
  // Correlated, to prove correlation does not disturb the answer path.
  await request(port, "POST", "/hooks/tool-output", {
    body: { hook_event_name: "PreToolUse", tool_name: "Bash", tool_input: toolInput, tool_use_id: "tu_watch" },
  });
  const inFlight = postPermissionHook(port, {
    tool_name: "Bash",
    tool_input: toolInput,
    permission_suggestions: [{ type: "rule", value: "Bash(git:*)" }],
  });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");
  const permissionId = prompt.parsed.permissionId;

  const answer = await request(port, "POST", "/command", {
    token,
    body: { permissionId, decision: { behavior: "allow-always" } },
  });
  assert.equal(answer.status, 200);

  const hookBody = await (await inFlight).json();
  assert.equal(hookBody.hookSpecificOutput.decision.behavior, "allow");
  assert.deepEqual(
    hookBody.hookSpecificOutput.decision.updatedPermissions,
    [{ type: "rule", value: "Bash(git:*)" }],
    "allow-always must still persist the hook's suggestions",
  );

  const status = await request(port, "GET", "/status", { token });
  assert.equal(status.body.pendingPermissions, 0);

  // A watch-answered prompt must also release its correlation index entry, or
  // the tool's own PostToolUse would later chase a dead permissionId.
  await request(port, "POST", "/hooks/tool-output", {
    body: {
      hook_event_name: "PostToolUse",
      tool_name: "Bash",
      tool_input: toolInput,
      tool_response: { stdout: "clean" },
      tool_use_id: "tu_watch",
    },
  });
  await settle(400);
  assert.ok(
    !sse.events.some((e) => e.event === "permission-cleared"),
    "the tool completing after a watch answer must not emit a spurious clear",
  );
});

// ---------------------------------------------------------------------------
// Authoritative connect-time sync
// ---------------------------------------------------------------------------

test("connect-time sync retracts a prompt that died while the client was away", { timeout: 60_000 }, async (t) => {
  // The remaining route to the reported symptom. permission-cleared goes into
  // a ring buffer that production burns through in minutes, so a watch offline
  // at clear time never sees it — and the per-prompt re-send is ADDITIVE and
  // cannot retract. Without the authoritative frame the card survives until
  // the app is force-stopped.
  const bridge = await startBridge(t, {
    env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "1200", CLAUDE_WATCH_SSE_BUFFER_SIZE: "4" },
  });
  const { port } = bridge;
  const { token, sse } = await pairAndConnect(t, bridge);

  const inFlight = postPermissionHook(port, {
    tool_name: "Bash",
    tool_input: { command: "make release" },
  });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");
  const permissionId = prompt.parsed.permissionId;
  const lastEventId = prompt.id;

  // The watch drops off, and the prompt expires while it is away.
  sse.close();
  await inFlight;

  // Ordinary traffic evicts the permission-cleared from the tiny ring buffer,
  // exactly as ~9000 tool-outputs per 48h do in production.
  for (let i = 0; i < 8; i++) {
    await request(port, "POST", "/hooks/tool-output", {
      body: { hook_event_name: "PostToolUse", tool_name: "Read", tool_input: { file: `f${i}` }, tool_response: {} },
    });
  }

  const rejoined = connectSse(port, token, { lastEventId });
  t.after(() => rejoined.close());
  assert.equal(await rejoined.statusCode(), 200);

  const sync = await rejoined.waitFor((e) => e.event === "permission-sync");
  assert.ok(Array.isArray(sync.parsed.permissionIds), "permission-sync must carry an id array");
  assert.ok(
    !sync.parsed.permissionIds.includes(permissionId),
    "a dead prompt must be absent from the authoritative set, so the client drops it",
  );
  // Replay must not resurrect it either.
  assert.ok(
    !rejoined.events.some((e) => e.event === "permission-request" && e.parsed?.permissionId === permissionId),
    "the connect-time re-send must not resurrect a cleared prompt",
  );
});

test("connect-time sync lists a still-live prompt so clients keep it", { timeout: 60_000 }, async (t) => {
  // The other half: retraction must never eat a prompt that IS live, or the
  // frame would clear the wrist on every reconnect.
  const bridge = await startBridge(t, { env: { CLAUDE_WATCH_PERMISSION_TIMEOUT_MS: "300000" } });
  const { port } = bridge;
  const { token, sse } = await pairAndConnect(t, bridge);

  postPermissionHook(port, { tool_name: "Bash", tool_input: { command: "sleep 30" } });
  const prompt = await sse.waitFor((e) => e.event === "permission-request");
  const permissionId = prompt.parsed.permissionId;

  const second = connectSse(port, token);
  t.after(() => second.close());
  assert.equal(await second.statusCode(), 200);

  const sync = await second.waitFor((e) => e.event === "permission-sync");
  assert.ok(
    sync.parsed.permissionIds.includes(permissionId),
    "a live prompt must be listed, or reconnecting would clear the wrist",
  );
  // And it is still re-sent with its payload — the frame retracts, never creates.
  await second.waitFor((e) => e.event === "permission-request" && e.parsed?.permissionId === permissionId);
});
