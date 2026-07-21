// HTTP handlers for the watch-client API surface: POST /pair, POST /command
// (spawn/kill/permission-decision/PTY injection), GET /status, and the
// unauthenticated GET /ping discovery probe.
import { spawn as childSpawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { log, jsonResponse, readBody } from "./util.js";
import {
  BRIDGE_ID,
  CLAUDE_BIN,
  CODEX_BIN,
  CLI_CWD,
  PROTOCOL_VERSION,
  MIN_SUPPORTED_CLIENT_PROTO,
  SPAWN_INJECT_TIMEOUT_MS,
  availableAgentsList,
} from "./config.js";
import {
  generatePairingCode,
  issueToken,
  requireAuth,
  isPairingOpen,
  lockPairing,
  isPairingCodeExpired,
  isPairingReopened,
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
  markSessionIdle,
  sessionEventPayload,
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

  // Which surface this pair request arrived on. Legacy /pair is frozen; the
  // /v1 divergences below (min-version gate, proto in the response, no
  // top-level sessionId alias) apply only to /v1/pair. Classified from the
  // router-parsed pathname (server.js), NOT the raw req.url string: an
  // absolute-form request target would otherwise route as /v1 but classify
  // as legacy, bypassing the min-version gate. Moved ABOVE the code check so
  // the code-optional relaxation below can be scoped to /v1 only.
  const surface = req.pathname === "/v1/pair" ? "v1" : "legacy";

  // `code` is REQUIRED on the frozen legacy /pair, OPTIONAL on /v1/pair. The
  // /v1 Discover-pairing path (issue #23 follow-up) sends no code at all:
  // there the operator-opened pairing window (isPairingOpen, checked below)
  // plus the per-IP rate limit and the single-use lock on success are the
  // whole gate — no code is ever entered. Legacy stays frozen: legacy-corpus
  // "pair missing code" replays `POST /pair {}` -> 400 IN SEQUENCE, and a
  // code-less legacy success would call lockPairing() and cascade-403 the
  // following legacy fixtures. So the relaxation is guarded on surface==="v1".
  const hasCode = typeof code === "string" && code.length > 0;
  if (surface === "legacy" && !hasCode) {
    return jsonResponse(res, 400, { error: "Missing 'code' field" });
  }

  // /v1 min-version gate (PROTOCOL.md "Versioning"): the request must declare
  // the client's protocol version, and it must meet the bridge's minimum.
  // Checked before the lockout/code paths so an outdated app always learns it
  // must update — a clear, machine-readable refusal instead of the
  // undetectable old-app/new-bridge wire mismatches versioning exists to
  // prevent. The legacy /pair surface stays frozen and never checks proto.
  if (surface === "v1") {
    const clientProto = body.proto;
    if (!Number.isInteger(clientProto) || clientProto < MIN_SUPPORTED_CLIENT_PROTO) {
      const declared = Number.isInteger(clientProto)
        ? `client protocol version ${clientProto}`
        : "a client that does not declare its protocol version ('proto' missing from the pair request)";
      log("warn", `Pairing refused on /v1: ${declared} is below the minimum supported version ${MIN_SUPPORTED_CLIENT_PROTO}`);
      return jsonResponse(res, 426, {
        error: `Unsupported protocol version: this bridge requires proto >= ${MIN_SUPPORTED_CLIENT_PROTO}, but the pair request declared ${Number.isInteger(clientProto) ? clientProto : "none"}. Update the watch app.`,
        proto: PROTOCOL_VERSION,
        minProto: MIN_SUPPORTED_CLIENT_PROTO,
      });
    }
  }

  // Pairing lockout: after any successful pair the surface locks (on both
  // /pair and /v1/pair) until the operator reopens it via SIGUSR1 or a
  // restart with --allow-pairing. Before per-device tokens, a re-pair here
  // silently overwrote the token and deauthenticated the current device.
  if (!isPairingOpen()) {
    return jsonResponse(res, 403, { error: "Already paired. Re-pairing requires explicit authorization on the bridge." });
  }

  if (isPairingCodeExpired()) {
    // A window opened by an operator reopen (SIGUSR1) relocks on expiry: a
    // reopened-and-forgotten surface must not keep minting fresh codes
    // forever. The initial startup window keeps regenerating (first-run UX
    // unchanged) — an operator watching the console can still grab a fresh
    // code by attempting a pair.
    if (isPairingReopened()) {
      lockPairing();
      log("warn", "Reopened pairing window expired without a successful pair — pairing locked again");
      return jsonResponse(res, 403, { error: "Pairing code expired and pairing is locked again. Send SIGUSR1 on the bridge to reopen." });
    }
    generatePairingCode();
    return jsonResponse(res, 401, { error: "Pairing code expired. A new code has been generated." });
  }

  // Only a supplied code is matched. A code-less /v1 Discover pair skips this
  // check — its gate is the open window verified above (the expiry/relock
  // gate still ran, so a reopened-and-expired window has already 403'd here).
  // A code-BEARING pair (Manual path, or any legacy pair) still fails a wrong
  // code exactly as before.
  if (hasCode && !matchesPairingCode(code)) {
    return jsonResponse(res, 401, { error: "Invalid pairing code" });
  }

  // Success: mint a per-device token (only its SHA-256 hash is persisted) and
  // lock the pairing surface until the next explicit reopen.
  const token = issueToken({ deviceName, surface });
  clearPairingCode();
  lockPairing();
  setBridgeState("connected");
  pushSseEvent("session", { state: "connected" });

  log("info", "Watch paired successfully");
  const response = {
    token,
    bridgeId: BRIDGE_ID,
    availableAgents: availableAgentsList(),
    sessions: getSessionsSnapshot(),
  };
  if (surface === "v1") {
    // /v1 disambiguation: the top level identifies the BRIDGE INSTANCE as
    // `bridgeId` only — `sessionId` is reserved for agent-session slot ids
    // (SSE payloads, sessions[].id). The response also echoes the bridge's
    // protocol version so the client can pin what it paired against.
    response.proto = PROTOCOL_VERSION;
  } else {
    response.sessionId = BRIDGE_ID; // frozen legacy alias for bridgeId
  }
  return jsonResponse(res, 200, response);
}

// Resolve the working directory for a spawn (issue #56), shared by the
// explicit spawn action and the auto-spawn of the command-injection fallback.
// The literal "~" is the "no project" sentinel: the watch cannot know the
// bridge user's home path, so it sends "~" and the bridge resolves it to
// os.homedir(). Any other provided value must be an absolute path to an
// existing directory — before validation, a bogus target reached the PTY
// spawn and died into an instantly-ended session, which from the wrist looked
// like a silent no-op. An omitted (or empty) cwd keeps the historical
// fallback chain unchanged: bridge CLI positional arg → $HOME → bridge cwd.
// Returns the resolved directory, or null AFTER writing the 400 response —
// callers must return immediately on null so no session slot is ever created
// for an invalid target.
function resolveSpawnCwd(res, requestedCwd) {
  if (!requestedCwd) {
    return CLI_CWD || process.env.HOME || process.cwd();
  }
  const resolved = requestedCwd === "~" ? os.homedir() : requestedCwd;
  try {
    if (path.isAbsolute(resolved) && fs.statSync(resolved).isDirectory()) {
      return resolved;
    }
  } catch { /* ENOENT/EACCES — fall through to the 400 */ }
  jsonResponse(res, 400, { error: `spawn cwd is not a directory: ${resolved}` });
  return null;
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
  // A dictated prompt starts real work on a slot that was very likely idle
  // (that is why the user is dictating at it): clear the turn-end flag now, so
  // a watch reconnecting mid-run sees it green rather than grey (issue #60).
  // The agent's own output will keep it cleared.
  targetSession.idle = false;
  // Built through sessionEventPayload like every other session push: this used
  // to be the one hand-rolled payload in the bridge, which silently dropped
  // `title`/`external`/`branch` (and would have dropped `idle`) from an event
  // clients treat as an ordinary idempotent refresh. Uniformity by
  // construction, not by the accident of a nearby assignment.
  pushSseEvent(
    "session",
    sessionEventPayload(targetSession, {
      state: "running",
      agent: targetSession.agent,
      cwd: targetSession.cwd,
      folderName: targetSession.folderName,
    }),
    targetSessionId,
  );

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
  // A finished headless run is a turn END, and it is the ONLY turn-end signal
  // this slot will ever get for it: we spawn the RAW agent binary here, not the
  // codex-watch wrapper, so nothing POSTs /hooks/stop on our behalf (and a
  // `claude -p --continue` that reports a fresh session_id gets its hooks
  // attributed to a different slot entirely). Without this the idle=false set
  // above is permanent, and one dictated prompt pins the session "working"
  // forever — re-creating issue #60's green-when-idle symptom on the very slot
  // the flag was added to keep honest. Setting it never broadcasts; it rides
  // the next session event, snapshots included (see sessions.js).
  proc.on("close", (exitCode) => {
    log("info", `Prompt process exited (code ${exitCode}) for session ${targetSessionId}`);
    markSessionIdle(targetSessionId);
  });
  proc.on("error", (err) => {
    // Spawn/exec failure: no output, no hooks, no run — the slot is doing even
    // less than idle, and must not be left claiming otherwise.
    log("error", `Prompt process error for session ${targetSessionId}: ${err.message}`);
    markSessionIdle(targetSessionId);
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
    answers,
  } = body;

  // --- Spawn a new session ---
  if (spawnRequest) {
    const validAgents = ["claude", "codex"];
    if (!validAgents.includes(spawnRequest)) {
      return jsonResponse(res, 400, { error: `Invalid agent: ${spawnRequest}. Use: ${validAgents.join(", ")}` });
    }
    const cwd = resolveSpawnCwd(res, body.cwd);
    if (cwd === null) return; // 400 already sent — no session slot created
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
    // Capture the machine-readable behavior before normalization: the Codex
    // fallthrough below resolves it against the synthetic menu's canonical
    // option list, and allow-always is rewritten to allow for the hook.
    const requestedBehavior = typeof decision?.behavior === "string" ? decision.behavior : undefined;
    if (decision) {
      // allow-always is the machine-readable form of the legacy allowAll
      // flag: both collapse to an allow that applies the permission
      // suggestions stored when the hook arrived.
      const allowAlways = decision.behavior === "allow-always" || (allowAll && decision.behavior === "allow");
      if (allowAlways) {
        decision.behavior = "allow";
        decision.updatedPermissions = pendingPermissionBodies.get(permissionId) || [];
      }
      pendingPermissionBodies.delete(permissionId);

      // Forward the watch's selected option so the hook response can include it
      if (selectedOption !== undefined) decision.selectedOption = selectedOption;
      if (Number.isInteger(optionIndex)) decision.optionIndex = optionIndex;
      // AskUserQuestion answers for every question (array aligned with the
      // questions, or an object keyed by question text — see hooks.js).
      if (answers !== undefined && decision.answers === undefined) decision.answers = answers;

      const resolved = resolvePermission(permissionId, decision);
      if (resolved) {
        log("info", `Permission ${permissionId} resolved: ${decision.behavior}${allowAll || requestedBehavior === "allow-always" ? " (allow all)" : ""}`);
        return jsonResponse(res, 200, { ok: true });
      }
    }

    const resolvedSynthetic = resolveCodexSyntheticPermission(permissionId, selectedOption, optionIndex, requestedBehavior);
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
      const cwd = resolveSpawnCwd(res, body.cwd);
      if (cwd === null) return; // 400 already sent — no session slot created
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

// Unauthenticated discovery probe: this is what watch clients hit to verify a
// candidate bridge address (localhost fallback, manual IP entry, or the
// Android emulator's 10.0.2.2 host alias) before they hold a token. It
// deliberately exposes only the bridge identity — no session snapshot, no
// project paths, no client counts. Everything richer lives behind auth on
// /status.
export function handlePing(_req, res) {
  return jsonResponse(res, 200, {
    proto: PROTOCOL_VERSION,
    bridgeId: BRIDGE_ID,
    machineName: os.hostname(),
  });
}

export function handleStatus(req, res) {
  // The session snapshot enumerates every project's absolute path; on a
  // 0.0.0.0 bind that must not be readable by arbitrary LAN peers. Discovery
  // probes use the unauthenticated GET /ping instead.
  if (!requireAuth(req)) {
    return jsonResponse(res, 401, { error: "Unauthorized" });
  }
  // /v1 disambiguation (mirrors handlePair, including the router-parsed
  // pathname rationale): no top-level `sessionId` alias — that name means an
  // agent-session slot id everywhere on /v1.
  const isV1 = req.pathname === "/v1/status";
  const mostRecentRunningSession = findMostRecentRunningSession();
  return jsonResponse(res, 200, {
    bridgeId: BRIDGE_ID,
    ...(isV1 ? {} : { sessionId: BRIDGE_ID }), // frozen legacy alias
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
