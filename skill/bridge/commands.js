// HTTP handlers for the watch-client API surface: POST /pair, POST /command
// (spawn/kill/permission-decision/PTY injection), and GET /status.
import { spawn as childSpawn } from "node:child_process";
import { log, jsonResponse, readBody } from "./util.js";
import { BRIDGE_ID, CLAUDE_BIN, CODEX_BIN, availableAgentsList } from "./config.js";
import {
  generatePairingCode,
  generateSessionToken,
  isRateLimited,
  recordRateLimitAttempt,
  requireAuth,
  isPairingCodeExpired,
  matchesPairingCode,
  clearPairingCode,
  getBridgeState,
  setBridgeState,
} from "./credentials.js";
import { pushSseEvent, sseClients, sseBuffer } from "./transport-sse.js";
import {
  sessions,
  spawnSession,
  killSession,
  findMostRecentActiveSession,
  findMostRecentRunningSession,
  getSessionsSnapshot,
} from "./sessions.js";
import { pendingPermissions, pendingPermissionBodies, resolvePermission } from "./permissions.js";
import { codexSyntheticPermissions, resolveCodexSyntheticPermission } from "./codex.js";

export async function handlePair(req, res) {
  if (req.method !== "POST") {
    return jsonResponse(res, 405, { error: "Method not allowed" });
  }

  if (isRateLimited()) {
    return jsonResponse(res, 429, { error: "Too many pairing attempts. Try again later." });
  }

  let body;
  try {
    body = await readBody(req);
  } catch {
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  recordRateLimitAttempt();

  const { code } = body;
  if (!code || typeof code !== "string") {
    return jsonResponse(res, 400, { error: "Missing 'code' field" });
  }

  if (isPairingCodeExpired()) {
    generatePairingCode();
    return jsonResponse(res, 401, { error: "Pairing code expired. A new code has been generated." });
  }

  if (!matchesPairingCode(code)) {
    return jsonResponse(res, 401, { error: "Invalid pairing code" });
  }

  // Success
  const token = generateSessionToken();
  clearPairingCode();
  setBridgeState("connected");
  pushSseEvent("session", { state: "connected" });

  log("info", "Watch paired successfully");
  return jsonResponse(res, 200, {
    token,
    bridgeId: BRIDGE_ID,
    sessionId: BRIDGE_ID, // backward compat
    availableAgents: availableAgentsList(),
    sessions: getSessionsSnapshot(),
  });
}

export async function handleCommand(req, res) {
  if (req.method !== "POST") {
    return jsonResponse(res, 405, { error: "Method not allowed" });
  }
  if (!requireAuth(req)) {
    return jsonResponse(res, 401, { error: "Unauthorized" });
  }

  let body;
  try {
    body = await readBody(req);
  } catch {
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  const {
    command,
    permissionId,
    decision,
    allowAll,
    agent,
    sessionId,
    spawn: spawnRequest,
    kill: killRequest,
    selectedOption,
    optionIndex,
  } = body;

  // --- Spawn a new session ---
  if (spawnRequest) {
    const validAgents = ["claude", "codex"];
    if (!validAgents.includes(spawnRequest)) {
      return jsonResponse(res, 400, { error: `Invalid agent: ${spawnRequest}. Use: ${validAgents.join(", ")}` });
    }
    const cwd = body.cwd || process.argv[2] || process.env.HOME || process.cwd();
    const newId = spawnSession(spawnRequest, cwd);
    if (!newId) {
      return jsonResponse(res, 500, { error: `Failed to spawn ${spawnRequest}` });
    }
    return jsonResponse(res, 200, { ok: true, sessionId: newId, agent: spawnRequest });
  }

  // --- Kill a session ---
  if (killRequest && sessionId) {
    const killed = killSession(sessionId);
    if (!killed) {
      return jsonResponse(res, 404, { error: "No session with that ID" });
    }
    return jsonResponse(res, 200, { ok: true });
  }

  // --- Permission response ---
  if (permissionId && (decision || selectedOption !== undefined || Number.isInteger(optionIndex))) {
    if (decision) {
      if (allowAll && decision.behavior === "allow") {
        decision.updatedPermissions = pendingPermissionBodies.get(permissionId) || [];
      }
      pendingPermissionBodies.delete(permissionId);

      // Forward the watch's selected option so the hook response can include it
      if (selectedOption !== undefined) decision.selectedOption = selectedOption;
      if (Number.isInteger(optionIndex)) decision.optionIndex = optionIndex;

      const resolved = resolvePermission(permissionId, decision);
      if (resolved) {
        log("info", `Permission ${permissionId} resolved: ${decision.behavior}${allowAll ? " (allow all)" : ""}`);
        return jsonResponse(res, 200, { ok: true });
      }
    }

    const resolvedSynthetic = resolveCodexSyntheticPermission(permissionId, selectedOption, optionIndex);
    if (resolvedSynthetic) {
      return jsonResponse(res, 200, { ok: true });
    }

    return jsonResponse(res, 404, { error: "No pending permission with that ID" });
  }

  // --- PTY command injection ---
  if (command !== undefined) {
    // Find the target session
    let targetSession = null;

    if (sessionId) {
      targetSession = sessions.get(sessionId);
      if (targetSession && !targetSession.ptyProcess) {
        // Session exists but has no PTY (external hook-created session).
        // Run the prompt via CLI in non-interactive mode — hooks will forward output.
        const promptText = command.replace(/\n$/, "").trim();
        if (!promptText) {
          return jsonResponse(res, 400, { error: "Empty command" });
        }

        const bin = targetSession.agent === "codex" ? CODEX_BIN : CLAUDE_BIN;
        if (!bin) {
          return jsonResponse(res, 500, { error: `No binary found for ${targetSession.agent}` });
        }

        const args = targetSession.agent === "codex"
          ? ["exec", promptText]
          : ["-p", promptText, "--continue"];

        log("info", `Running ${targetSession.agent} prompt in ${targetSession.cwd}: "${promptText.slice(0, 80)}"`);

        targetSession.state = "running";
        pushSseEvent("session", { state: "running", agent: targetSession.agent, cwd: targetSession.cwd, folderName: targetSession.folderName }, sessionId);

        const proc = childSpawn(bin, args, {
          cwd: targetSession.cwd,
          env: { ...process.env },
          stdio: ["ignore", "pipe", "pipe"],
        });

        proc.stdout.on("data", (data) => {
          const text = data.toString().trim();
          if (text) pushSseEvent("pty-output", { text }, sessionId);
        });
        proc.stderr.on("data", (data) => {
          const text = data.toString().trim();
          if (text && !text.includes("tcgetattr")) {
            pushSseEvent("pty-output", { text }, sessionId);
          }
        });
        proc.on("close", (exitCode) => {
          log("info", `Prompt process exited (code ${exitCode}) for session ${sessionId}`);
        });
        proc.on("error", (err) => {
          log("error", `Prompt process error for session ${sessionId}: ${err.message}`);
        });

        return jsonResponse(res, 200, { ok: true, sessionId, agent: targetSession.agent, prompt: true });
      }
      if (!targetSession) {
        return jsonResponse(res, 404, { error: "No session with that ID" });
      }
    } else {
      // Backward compat: route to the most recent active session
      targetSession = findMostRecentActiveSession() || findMostRecentRunningSession();
    }

    if (!targetSession) {
      // Auto-spawn a new session
      const requestedAgent = agent || "claude";
      const cwd = body.cwd || process.argv[2] || process.env.HOME || process.cwd();
      const newId = spawnSession(requestedAgent, cwd);
      if (!newId) {
        return jsonResponse(res, 500, { error: `Failed to spawn ${requestedAgent}` });
      }
      const slot = sessions.get(newId);
      setTimeout(() => {
        if (slot && slot.ptyProcess) {
          slot.ptyProcess.stdin.write(command);
          log("info", `Command injected into new ${requestedAgent} session ${newId} (${command.length} chars)`);
        }
      }, 500);
      return jsonResponse(res, 200, { ok: true, sessionId: newId, agent: requestedAgent, spawned: true });
    }

    try {
      targetSession.ptyProcess.stdin.write(command);
      log("info", `Command injected into session ${targetSession.id} (${command.length} chars)`);
      return jsonResponse(res, 200, { ok: true, sessionId: targetSession.id, agent: targetSession.agent });
    } catch (err) {
      return jsonResponse(res, 500, { error: err.message });
    }
  }

  return jsonResponse(res, 400, { error: "Missing 'command', 'spawn', 'kill', or 'permissionId'+'decision'" });
}

export function handleStatus(_req, res) {
  const mostRecentRunningSession = findMostRecentRunningSession();
  return jsonResponse(res, 200, {
    bridgeId: BRIDGE_ID,
    sessionId: BRIDGE_ID, // backward compat
    state: getBridgeState(),
    availableAgents: availableAgentsList(),
    sessions: getSessionsSnapshot(),
    sseClients: sseClients.size,
    pendingPermissions: pendingPermissions.size + codexSyntheticPermissions.size,
    eventBufferSize: sseBuffer.length,
    // Backward compat: expose the most recent active session's info
    hasPty: findMostRecentActiveSession() !== null,
    activeAgent: mostRecentRunningSession?.agent || null,
  });
}
