// HTTP handlers for the /hooks/* surface: Claude Code (and Codex wrapper)
// hook scripts POST here; the permission hook blocks until the watch decides.
import crypto from "node:crypto";
import { log, jsonResponse, readBody, isLoopbackAddress } from "./util.js";
import { pushSseEvent, sseClients } from "./transport-sse.js";
import {
  resolveHookSession,
  endHookSession,
  refreshHookSessionTitle,
  markWorkflowActivity,
  markSessionIdle,
  markSessionWorking,
} from "./sessions.js";
import {
  waitForPermission,
  cancelPermission,
  pendingPermissionBodies,
  defaultPermissionOptions,
} from "./permissions.js";

// Assemble the updatedInput.answers map for an AskUserQuestion decision.
// Preferred (/v1) form: `decision.answers` as an array aligned with the
// questions, or an object keyed by question text — EVERY question gets its
// answer. Legacy form (frozen): a single `selectedOption` that answers the
// first question only.
function collectAskUserQuestionAnswers(questions, decision) {
  const answers = {};
  if (Array.isArray(decision.answers)) {
    questions.forEach((question, index) => {
      const answer = decision.answers[index];
      if (question?.question && answer !== undefined && answer !== null) {
        answers[question.question] = answer;
      }
    });
  } else if (decision.answers && typeof decision.answers === "object") {
    for (const question of questions) {
      const answer = question?.question ? decision.answers[question.question] : undefined;
      if (answer !== undefined && answer !== null) {
        answers[question.question] = answer;
      }
    }
  } else if (decision.selectedOption !== undefined && questions[0]?.question) {
    answers[questions[0].question] = decision.selectedOption;
  }
  return Object.keys(answers).length > 0 ? answers : null;
}

// Hooks are called exclusively by hook scripts that Claude Code (or the Codex
// wrapper) runs on this machine, so they always originate from loopback. On a
// 0.0.0.0 bind this surface is otherwise open to any LAN peer, who could
// spoof permission prompts and terminal output onto the trusted watch UI —
// reject anything that didn't come from localhost before reading the body.
function requireLoopback(req, res) {
  const addr = req.socket?.remoteAddress;
  if (isLoopbackAddress(addr)) return true;
  log("warn", `Hook request rejected: non-loopback source ${addr || "unknown"}`);
  jsonResponse(res, 403, { error: "Hooks are only accepted from localhost" });
  return false;
}

export async function handleHookToolOutput(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  const sid = resolveHookSession(body);
  const source = body.source || "claude";
  // A completed tool use is the canonical "this session is producing work"
  // signal — the same one the watch reducer folds into markWorking — so it
  // clears any idle left by an earlier turn's Stop (issue #60).
  markSessionWorking(sid);
  log("info", `Hook: ${source === "codex" ? "Codex" : "PostToolUse"} received [${source}]${sid ? ` session=${sid}` : ""}`, body.tool_name || "");
  // Workflow launch signal (issue #55): the Workflow tool returns immediately
  // (it runs in the background), so this PostToolUse is the ONLY hook-side
  // moment the bridge learns a workflow started — arm the journal scan now;
  // completion is discovered by the poll, never by another hook.
  if (sid && body.tool_name === "Workflow") markWorkflowActivity(sid);
  pushSseEvent("tool-output", { ...body, source }, sid);
  return jsonResponse(res, 200, { ok: true });
}

export async function handleHookPermission(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  // Disable Node.js default 5-minute requestTimeout for this long-lived blocking request.
  // The hook waits up to PERMISSION_TIMEOUT_MS (10 min) for a watch response.
  req.socket.setTimeout(0);

  const sid = resolveHookSession(body);

  // Fast no-decision: with zero connected SSE clients nobody can possibly
  // answer, and blocking would silently stall every Claude Code session on
  // the machine for the full timeout. Answer immediately with no decision so
  // the terminal dialog appears normally. Nothing is registered or broadcast:
  // a permission-request buffered now would be a zombie by the time a client
  // connects (this hook request will be long gone).
  if (sseClients.size === 0) {
    log("info", `Hook: PermissionRequest skipped (no connected clients, returning no-decision)${sid ? ` session=${sid}` : ""}`, body.tool_name || "");
    return jsonResponse(res, 200, {});
  }

  const permissionId = crypto.randomUUID();
  log("info", `Hook: PermissionRequest received (id: ${permissionId})${sid ? ` session=${sid}` : ""}`, body.tool_name || "");

  if (body.permission_suggestions) {
    pendingPermissionBodies.set(permissionId, body.permission_suggestions);
  }

  // Canonical /v1 semantics: the event carries a server-normalized option
  // list where every option has a machine-readable `behavior` (see
  // permissions.js) — clients never infer approve/deny from position or
  // wording. AskUserQuestion prompts are content, not permission decisions:
  // they carry their own per-question option lists in tool_input.questions
  // (forwarded verbatim, ALL of them), so they get no top-level options.
  const eventPayload = { permissionId, ...body };
  if (body.tool_name !== "AskUserQuestion") {
    eventPayload.options = defaultPermissionOptions({
      canAllowAlways: Array.isArray(body.permission_suggestions) && body.permission_suggestions.length > 0,
    });
  }

  // Register the pending entry (with its event payload, so connect-time
  // snapshots can re-send the prompt) BEFORE broadcasting, so an answer can
  // never race the registration.
  const decisionPromise = waitForPermission(permissionId, {
    sessionId: sid,
    payload: eventPayload,
  });

  // Claude Code can abort this blocking request before a decision arrives
  // (user answered in the terminal, pressed Esc, or the hook-side timeout
  // fired). Without cleanup the pending entry survives as a zombie prompt on
  // the watch whose eventual answer goes into a dead socket. 'close' with an
  // unfinished response means the client went away: cancel the pending entry
  // and tell clients to dismiss the prompt.
  res.on("close", () => {
    if (res.writableEnded) return; // normal completion, nothing to clean up
    if (cancelPermission(permissionId)) {
      log("warn", `Hook: PermissionRequest ${permissionId} aborted by Claude Code, clearing pending prompt`);
      pushSseEvent("permission-cleared", { permissionId, reason: "hook-aborted" }, sid);
    }
  });

  pushSseEvent("permission-request", eventPayload, sid);

  const decision = await decisionPromise;

  // Canceled means the hook request is gone — there is no socket to answer.
  if (decision.canceled || res.writableEnded || res.destroyed) return;

  log("info", `Hook: PermissionRequest resolved (id: ${permissionId}): ${decision.behavior}`);

  const hookResponse = {
    hookSpecificOutput: {
      hookEventName: "PermissionRequest",
      decision: { behavior: decision.behavior },
    },
  };

  if (decision.updatedPermissions && decision.updatedPermissions.length > 0) {
    hookResponse.hookSpecificOutput.decision.updatedPermissions = decision.updatedPermissions;
  }

  if (decision.behavior === "deny" && decision.message) {
    hookResponse.hookSpecificOutput.decision.message = decision.message;
  }

  // For AskUserQuestion: forward the watch answers so Claude Code doesn't
  // fall back to waiting for terminal input. Multi-question payloads get an
  // answer per question (decision.answers); the legacy single selectedOption
  // path keeps answering only the first question.
  if (body.tool_name === "AskUserQuestion") {
    const questions = Array.isArray(body.tool_input?.questions) ? body.tool_input.questions : [];
    const answers = collectAskUserQuestionAnswers(questions, decision);
    if (answers) {
      hookResponse.hookSpecificOutput.decision.updatedInput = { questions, answers };
      if (decision.answers !== undefined) {
        log("info", `AskUserQuestion answers forwarded for ${Object.keys(answers).length} question(s)`);
      } else {
        log("info", `AskUserQuestion answer forwarded: "${decision.selectedOption}"`);
      }
    }
  }

  return jsonResponse(res, 200, hookResponse);
}

export async function handleHookStop(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  const sid = resolveHookSession(body);
  // The turn ended: the session is idle until it produces work again (issue
  // #60). Marked BEFORE the refresh below, so that if the refresh does
  // broadcast, the session event it pushes already tells the truth.
  markSessionIdle(sid);
  // The turn just finished, so the transcript (and possibly its ai-title)
  // just changed: opportunistic title refresh — a change is broadcast as an
  // idempotent `session` running event before the stop lands.
  refreshHookSessionTitle(sid, body);
  log("info", `Hook: Stop received${sid ? ` session=${sid}` : ""}`);
  pushSseEvent("stop", body, sid);
  return jsonResponse(res, 200, { ok: true });
}

// SessionEnd fires once when a Claude Code instance exits (Stop fires per
// turn and must NOT end sessions). Ends the matching external session so
// hook-created slots don't accumulate as permanent "running" zombies;
// endHookSession never creates a session for an unknown SessionEnd.
export async function handleHookSessionEnd(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  const sid = endHookSession(body);
  log("info", `Hook: SessionEnd received${sid ? ` session=${sid}` : " (no matching session)"}`);
  return jsonResponse(res, 200, { ok: true });
}

export async function handleHookTaskComplete(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  const sid = resolveHookSession(body);
  // The other turn-end signal the watch reducer treats as markIdle — the two
  // must agree, or a TaskComplete-ending session would still snapshot green
  // (issue #60).
  markSessionIdle(sid);
  log("info", `Hook: TaskCompleted received${sid ? ` session=${sid}` : ""}`);
  pushSseEvent("task-complete", body, sid);
  return jsonResponse(res, 200, { ok: true });
}

// Notification hook events (permission_prompt / idle_prompt / ...): forwarded
// as a dedicated `notification` SSE event that always carries
// `notification_type`, so clients can render "waiting on you" instead of
// "stopped". setup-hooks.sh points the Notification hook here; older installs
// that still post Notification bodies to /hooks/stop keep the frozen legacy
// behavior (a plain `stop` event).
export async function handleHookNotification(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  const sid = resolveHookSession(body);
  const notificationType = body.notification_type ?? null;
  log("info", `Hook: Notification received${sid ? ` session=${sid}` : ""}`, notificationType || "");
  pushSseEvent("notification", { ...body, notification_type: notificationType }, sid);
  return jsonResponse(res, 200, { ok: true });
}

export async function handleHookError(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  if (!requireLoopback(req, res)) return;
  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  const sid = resolveHookSession(body);
  log("info", `Hook: Error received${sid ? ` session=${sid}` : ""}`, body.error || "");
  pushSseEvent("error", body, sid);
  return jsonResponse(res, 200, { ok: true });
}
