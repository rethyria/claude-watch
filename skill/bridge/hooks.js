// HTTP handlers for the /hooks/* surface: Claude Code (and Codex wrapper)
// hook scripts POST here; the permission hook blocks until the watch decides.
import crypto from "node:crypto";
import { log, jsonResponse, readBody } from "./util.js";
import { pushSseEvent, sseClients } from "./transport-sse.js";
import { resolveHookSession } from "./sessions.js";
import { waitForPermission, cancelPermission, pendingPermissionBodies } from "./permissions.js";

export async function handleHookToolOutput(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  const sid = resolveHookSession(body);
  const source = body.source || "claude";
  log("info", `Hook: ${source === "codex" ? "Codex" : "PostToolUse"} received [${source}]${sid ? ` session=${sid}` : ""}`, body.tool_name || "");
  pushSseEvent("tool-output", { ...body, source }, sid);
  return jsonResponse(res, 200, { ok: true });
}

export async function handleHookPermission(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
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

  // Register the pending entry (with its event payload, so connect-time
  // snapshots can re-send the prompt) BEFORE broadcasting, so an answer can
  // never race the registration.
  const decisionPromise = waitForPermission(permissionId, {
    sessionId: sid,
    payload: { permissionId, ...body },
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

  pushSseEvent("permission-request", { permissionId, ...body }, sid);

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

  // For AskUserQuestion: forward the watch-selected option as the answer so Claude
  // Code doesn't fall back to waiting for terminal input.
  if (decision.selectedOption !== undefined && body.tool_name === "AskUserQuestion") {
    const questions = body.tool_input?.questions;
    if (questions && questions.length > 0 && questions[0]?.question) {
      const answers = { [questions[0].question]: decision.selectedOption };
      hookResponse.hookSpecificOutput.decision.updatedInput = { questions, answers };
      log("info", `AskUserQuestion answer forwarded: "${decision.selectedOption}"`);
    }
  }

  return jsonResponse(res, 200, hookResponse);
}

export async function handleHookStop(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  const sid = resolveHookSession(body);
  log("info", `Hook: Stop received${sid ? ` session=${sid}` : ""}`);
  pushSseEvent("stop", body, sid);
  return jsonResponse(res, 200, { ok: true });
}

export async function handleHookTaskComplete(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  const sid = resolveHookSession(body);
  log("info", `Hook: TaskCompleted received${sid ? ` session=${sid}` : ""}`);
  pushSseEvent("task-complete", body, sid);
  return jsonResponse(res, 200, { ok: true });
}

export async function handleHookError(req, res) {
  if (req.method !== "POST") return jsonResponse(res, 405, { error: "Method not allowed" });
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
