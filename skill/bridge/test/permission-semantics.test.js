// Machine-readable permission semantics on the /v1 surface (issue #10),
// black-box: behavior-tagged canonical option lists on permission-request
// events, behavior-based decisions (allow-always == the legacy allowAll
// path), full multi-question AskUserQuestion round-trips, and
// notification_type on forwarded notification events. The Codex synthetic
// menu is covered in permission-semantics-unit.test.js (it cannot be driven
// black-box without a real Codex install writing ~/.codex logs).
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { startBridge, request, connectSse } from "./helpers.js";

async function pairedV1Client(t) {
  const bridge = await startBridge(t);
  const { port, pairingCode } = bridge;
  const pair = await request(port, "POST", "/v1/pair", { body: { code: pairingCode } });
  assert.equal(pair.status, 200);
  const token = pair.body.token;
  const sse = connectSse(port, token, { path: "/v1/events" });
  t.after(() => sse.close());
  assert.equal(await sse.statusCode(), 200);
  return { bridge, port, token, sse };
}

test("permission-request carries a behavior per option; a behavior-based deny resolves the hook", { timeout: 60_000 }, async (t) => {
  const { port, token, sse } = await pairedV1Client(t);

  // No permission_suggestions: there is nothing to persist, so no
  // allow-always option is offered (offering one would be a lie — the prompt
  // would just recur).
  const hookResponse = request(port, "POST", "/hooks/permission", {
    body: { tool_name: "Bash", cwd: "/tmp/semantics-project", tool_input: { command: "rm -rf build" } },
  });

  const prompt = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "Bash",
  );
  assert.ok(Array.isArray(prompt.parsed.options), "permission-request must carry a canonical options list");
  assert.deepEqual(
    prompt.parsed.options.map((o) => o.behavior),
    ["allow", "deny"],
    "every option carries a machine-readable behavior; no allow-always without suggestions",
  );
  for (const option of prompt.parsed.options) {
    assert.equal(typeof option.label, "string");
    assert.ok(option.label.length > 0, "options keep a human-readable label");
  }

  // The client answers by behavior alone — no option position, no label text.
  const decision = await request(port, "POST", "/v1/command", {
    token,
    body: { permissionId: prompt.parsed.permissionId, decision: { behavior: "deny", message: "not now" } },
  });
  assert.equal(decision.status, 200);

  const hook = await hookResponse;
  assert.equal(hook.status, 200);
  assert.equal(hook.body.hookSpecificOutput.decision.behavior, "deny");
  assert.equal(hook.body.hookSpecificOutput.decision.message, "not now");
});

test("allow-always applies stored suggestions exactly like the legacy allowAll path", { timeout: 60_000 }, async (t) => {
  const { port, token, sse } = await pairedV1Client(t);
  const suggestions = [
    { type: "rule", value: "Bash(npm test:*)" },
    { type: "rule", value: "Bash(npm run build:*)" },
  ];

  // /v1 behavior-based answer: decision { behavior: "allow-always" }.
  const hookA = request(port, "POST", "/hooks/permission", {
    body: {
      tool_name: "Bash",
      cwd: "/tmp/allow-always-project",
      tool_input: { command: "npm test" },
      permission_suggestions: suggestions,
    },
  });
  const promptA = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_input?.command === "npm test",
  );
  assert.deepEqual(
    promptA.parsed.options.map((o) => o.behavior),
    ["allow", "allow-always", "deny"],
    "suggestions make the allow-always option available",
  );
  const decisionA = await request(port, "POST", "/v1/command", {
    token,
    body: { permissionId: promptA.parsed.permissionId, decision: { behavior: "allow-always" } },
  });
  assert.equal(decisionA.status, 200);
  const a = await hookA;
  assert.equal(a.status, 200);
  assert.equal(a.body.hookSpecificOutput.decision.behavior, "allow");
  assert.deepEqual(a.body.hookSpecificOutput.decision.updatedPermissions, suggestions);

  // Legacy iOS answer: decision { behavior: "allow" } + allowAll flag.
  const hookB = request(port, "POST", "/hooks/permission", {
    body: {
      tool_name: "Bash",
      cwd: "/tmp/allow-always-project",
      tool_input: { command: "npm run build" },
      permission_suggestions: suggestions,
    },
  });
  const promptB = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_input?.command === "npm run build",
  );
  const decisionB = await request(port, "POST", "/command", {
    token,
    body: { permissionId: promptB.parsed.permissionId, decision: { behavior: "allow" }, allowAll: true },
  });
  assert.equal(decisionB.status, 200);
  const b = await hookB;

  // The two paths must be indistinguishable to Claude Code.
  assert.deepEqual(
    a.body.hookSpecificOutput.decision,
    b.body.hookSpecificOutput.decision,
    "allow-always and legacy allowAll must produce identical hook decisions",
  );
});

test("multi-question AskUserQuestion: all questions delivered, all answers forwarded", { timeout: 60_000 }, async (t) => {
  const { port, token, sse } = await pairedV1Client(t);
  const questions = [
    {
      question: "Which color scheme?",
      header: "Color",
      options: [{ label: "Blue" }, { label: "Green" }],
      multiSelect: false,
    },
    {
      question: "Tabs or spaces?",
      header: "Indent",
      options: [{ label: "Tabs" }, { label: "Spaces" }],
      multiSelect: false,
    },
  ];

  // Array-form answers, aligned with the questions.
  const hookA = request(port, "POST", "/hooks/permission", {
    body: { tool_name: "AskUserQuestion", cwd: "/tmp/ask-project", tool_input: { questions } },
  });
  const promptA = await sse.waitFor(
    (e) => e.event === "permission-request" && e.parsed?.tool_name === "AskUserQuestion",
  );
  assert.equal(promptA.parsed.tool_input.questions.length, 2, "every question reaches the client");
  assert.equal(promptA.parsed.tool_input.questions[1].question, "Tabs or spaces?");

  const decisionA = await request(port, "POST", "/v1/command", {
    token,
    body: {
      permissionId: promptA.parsed.permissionId,
      decision: { behavior: "allow" },
      answers: ["Blue", "Spaces"],
    },
  });
  assert.equal(decisionA.status, 200);
  const a = await hookA;
  assert.equal(a.body.hookSpecificOutput.decision.behavior, "allow");
  const updatedInputA = a.body.hookSpecificOutput.decision.updatedInput;
  assert.equal(updatedInputA.questions.length, 2, "the hook response keeps every question");
  assert.deepEqual(updatedInputA.answers, {
    "Which color scheme?": "Blue",
    "Tabs or spaces?": "Spaces",
  });

  // Object-form answers keyed by question text (inside the decision).
  const hookB = request(port, "POST", "/hooks/permission", {
    body: { tool_name: "AskUserQuestion", cwd: "/tmp/ask-project", tool_input: { questions } },
  });
  const promptB = await sse.waitFor(
    (e) => e.event === "permission-request"
      && e.parsed?.tool_name === "AskUserQuestion"
      && e.parsed?.permissionId !== promptA.parsed.permissionId,
  );
  const decisionB = await request(port, "POST", "/v1/command", {
    token,
    body: {
      permissionId: promptB.parsed.permissionId,
      decision: {
        behavior: "allow",
        answers: { "Tabs or spaces?": "Tabs", "Which color scheme?": "Green" },
      },
    },
  });
  assert.equal(decisionB.status, 200);
  const b = await hookB;
  assert.deepEqual(b.body.hookSpecificOutput.decision.updatedInput.answers, {
    "Which color scheme?": "Green",
    "Tabs or spaces?": "Tabs",
  });
});

test("notification hook events are forwarded with notification_type", { timeout: 60_000 }, async (t) => {
  const { port, sse } = await pairedV1Client(t);

  const posted = await request(port, "POST", "/v1/hooks/notification", {
    body: {
      hook_event_name: "Notification",
      notification_type: "permission_prompt",
      message: "Claude needs your permission to use Bash",
      cwd: "/tmp/notify-project",
    },
  });
  assert.equal(posted.status, 200);

  const event = await sse.waitFor(
    (e) => e.event === "notification" && e.parsed?.notification_type === "permission_prompt",
  );
  assert.equal(event.parsed.message, "Claude needs your permission to use Bash");
  assert.ok(event.parsed.sessionId, "notification events keep session attribution");

  // The legacy unprefixed path serves the same handler (hook scripts post
  // to unprefixed /hooks/* paths).
  const legacyPosted = await request(port, "POST", "/hooks/notification", {
    body: { hook_event_name: "Notification", notification_type: "idle_prompt", cwd: "/tmp/notify-project" },
  });
  assert.equal(legacyPosted.status, 200);
  await sse.waitFor(
    (e) => e.event === "notification" && e.parsed?.notification_type === "idle_prompt",
  );
});

test("installer routes Notification hook events to /hooks/notification", () => {
  const script = fs.readFileSync(new URL("../../setup-hooks.sh", import.meta.url), "utf-8");
  const notificationBlock = script.match(/'Notification':[\s\S]*?\}\]/);
  assert.ok(notificationBlock, "setup-hooks.sh must install a Notification hook");
  assert.match(
    notificationBlock[0],
    /\/hooks\/notification/,
    "Notification events must go to the dedicated endpoint, not /hooks/stop",
  );
});
