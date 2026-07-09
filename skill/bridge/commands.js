// HTTP handlers for the watch-client API surface: POST /pair, POST /command
// (spawn/kill/permission-decision/PTY injection), and GET /status.
import { spawn as childSpawn } from "node:child_process";
import { log, jsonResponse, readBody } from "./util.js";
import { BRIDGE_ID, CLAUDE_BIN, CODEX_BIN, CLI_CWD, SPAWN_INJECT_TIMEOUT_MS, availableAgentsList } from "./config.js";
import {
  generatePairingCode,
  issueToken,
  requireAuth,
  isPairingOpen,
  lockPairing,
  isPairingCodeExpired,
  matchesPairingCode,
  clearPairingCode,
  getBridgeState,
  setBridgeState,
} from "./credentials.js";
import { isRateLimited, recordRateLimitAttempt } from "./rate-limit.js";
import { pushSseEvent, sseClients, sseBuffer } from "./transport-sse.js";
import {
  sessions,
  spawnSession,
  killSession,
  findMostRecentActiveSession,
  findMostRecentRunningSession,
  getSessionsSnapshot,
  waitForFirstPtyOutput,
  writeToSessionStdin,
} from "./sessions.js";
import { pendingPermissions, pendingPermissionBodies, resolvePermission } from "./permissions.js";
import { codexSyntheticPermissions, resolveCodexSyntheticPermission } from "./codex.js";

export async function handlePair(req, res) {
  if (req.method !== "POST") {
    return jsonResponse(res, 405, { error: "Method not allowed" });
  }

  const remoteIp = req.socket?.remoteAddress || "unknown";
  if (isRateLimited(remoteIp)) {
    return jsonResponse(res, 429, { error: "Too many pairing attempts. Try again later." });
  }

  let body;
  try {
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
    return jsonResponse(res, 400, { error: "Invalid JSON" });
  }

  recordRateLimitAttempt(remoteIp);

  const { code, deviceName } = body;
  if (!code || typeof code !== "string") {
    return jsonResponse(res, 400, { error: "Missing 'code' field" });
  }

  // Pairing lockout: after any successful pair the surface locks (on both
  // /pair and /v1/pair) until the operator reopens it via SIGUSR1 or a
  // restart with --allow-pairing. Before per-device tokens, a re-pair here
  // silently overwrote the token and deauthenticated the current device.
  if (!isPairingOpen()) {
    return jsonResponse(res, 403, { error: "Already paired. Re-pairing requires explicit authorization on the bridge." });
  }

  if (isPairingCodeExpired()) {
    generatePairingCode();
    return jsonResponse(res, 401, { error: "Pairing code expired. A new code has been generated." });
  }

  if (!matchesPairingCode(code)) {
    return jsonResponse(res, 401, { error: "Invalid pairing code" });
  }

  // Success: mint a per-device token (only its SHA-256 hash is persisted) and
  // lock the pairing surface until the next explicit reopen.
  const surface = req.url === "/v1/pair" || req.url?.startsWith("/v1/pair?") ? "v1" : "legacy";
  const token = issueToken({ deviceName, surface });
  clearPairingCode();
  lockPairing();
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

// Run a dictated prompt for a session the bridge owns no PTY for (external
// hook-created sessions): invoke the agent CLI headlessly in the session's
// cwd and stream its output as pty-output events. Used both when the client
// names such a session explicitly and when the no-session-id fallback selects
// one — the session's own id attributes the output, never the request's.
function runHeadlessPrompt(res, targetSession, command) {
  const targetSessionId = targetSession.id;
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
  pushSseEvent("session", { state: "running", agent: targetSession.agent, cwd: targetSession.cwd, folderName: targetSession.folderName }, targetSessionId);

  const proc = childSpawn(bin, args, {
    cwd: targetSession.cwd,
    env: { ...process.env },
    stdio: ["ignore", "pipe", "pipe"],
  });

  proc.stdout.on("data", (data) => {
    const text = data.toString().trim();
    if (text) pushSseEvent("pty-output", { text }, targetSessionId);
  });
  proc.stderr.on("data", (data) => {
    const text = data.toString().trim();
    if (text && !text.includes("tcgetattr")) {
      pushSseEvent("pty-output", { text }, targetSessionId);
    }
  });
  proc.on("close", (exitCode) => {
    log("info", `Prompt process exited (code ${exitCode}) for session ${targetSessionId}`);
  });
  proc.on("error", (err) => {
    log("error", `Prompt process error for session ${targetSessionId}: ${err.message}`);
  });

  return jsonResponse(res, 200, { ok: true, sessionId: targetSessionId, agent: targetSession.agent, prompt: true });
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
    body = await readBody(req, res);
  } catch (err) {
    if (err?.tooLarge) return; // readBody already sent 413 and destroyed the socket
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
    const cwd = body.cwd || CLI_CWD || process.env.HOME || process.cwd();
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
      if (!targetSession) {
        return jsonResponse(res, 404, { error: "No session with that ID" });
      }
    } else {
      // Backward compat: route to the most recent active session
      targetSession = findMostRecentActiveSession() || findMostRecentRunningSession();
    }

    // Session exists but has no PTY (external hook-created session) — whether
    // it was named explicitly or selected by the no-session-id fallback. Run
    // the prompt via CLI in non-interactive mode; hooks will forward output.
    // (Injecting into targetSession.ptyProcess here would dereference null.)
    if (targetSession && !targetSession.ptyProcess) {
      return runHeadlessPrompt(res, targetSession, command);
    }

    if (!targetSession) {
      // Auto-spawn a new session. Inject the command only once the PTY has
      // produced its first output (the agent is actually up); a blind timed
      // write silently dropped the command when the PTY died or wasn't ready,
      // while the client still saw ok:true.
      const requestedAgent = agent || "claude";
      const cwd = body.cwd || CLI_CWD || process.env.HOME || process.cwd();
      const newId = spawnSession(requestedAgent, cwd);
      if (!newId) {
        return jsonResponse(res, 500, { error: `Failed to spawn ${requestedAgent}` });
      }
      const slot = sessions.get(newId);
      const ready = await waitForFirstPtyOutput(slot, SPAWN_INJECT_TIMEOUT_MS);
      if (!ready) {
        // The failure must not be sticky: a never-ready session left
        // registered as "running" with a live PTY would be selected by the
        // no-session-id fallback on the next command, which then blind-writes
        // into it and returns ok:true — silently swallowing the command (the
        // exact bug the ready gate exists to prevent) and wedging auto-spawn
        // until the zombie process dies on its own.
        killSession(newId);
        log("error", `Session ${newId} (${requestedAgent}) produced no output; command not injected`);
        return jsonResponse(res, 500, {
          error: `Spawned ${requestedAgent} session but it produced no output; command not injected`,
          sessionId: newId,
          agent: requestedAgent,
          spawned: true,
        });
      }
      if (!writeToSessionStdin(slot, command)) {
        // Same sticky-failure hazard as the !ready path above.
        killSession(newId);
        log("error", `Session ${newId} (${requestedAgent}) PTY unavailable; command not injected`);
        return jsonResponse(res, 500, {
          error: `Spawned ${requestedAgent} session but its PTY is not writable; command not injected`,
          sessionId: newId,
          agent: requestedAgent,
          spawned: true,
        });
      }
      log("info", `Command injected into new ${requestedAgent} session ${newId} (${command.length} chars)`);
      return jsonResponse(res, 200, { ok: true, sessionId: newId, agent: requestedAgent, spawned: true });
    }

    if (!writeToSessionStdin(targetSession, command)) {
      return jsonResponse(res, 500, { error: `Session ${targetSession.id} PTY is not writable; command not injected` });
    }
    log("info", `Command injected into session ${targetSession.id} (${command.length} chars)`);
    return jsonResponse(res, 200, { ok: true, sessionId: targetSession.id, agent: targetSession.agent });
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
