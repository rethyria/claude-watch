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
import { injectToAcpSession, requestAcpSpawn, requestAcpClose } from "./acp.js";

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
    // Claude sessions are born in Zed-land (the fork Zed launches hosts them),
    // never in a bridge-owned PTY: the product is Zed-only and a PTY fallback
    // would silently produce a second species of session that can never appear
    // in the editor. No fork connected = an honest error the wrist can show.
    // Codex has no ACP adapter, so it keeps the PTY path below.
    if (spawnRequest === "claude") {
      const acp = await requestAcpSpawn(cwd);
      if (acp === null) {
        return jsonResponse(res, 409, {
          error: "No Zed agent connection — open Zed (claude-watch agent) and try again",
        });
      }
      if (!acp.ok) {
        // `spawnRequestId` rides the error so the client can attribute a
        // session that finishes creating AFTER this response (the fork's own
        // register still announces it over SSE — see the self-healing notes
        // in acp.js).
        return jsonResponse(res, 409, { error: acp.error, spawnRequestId: acp.requestId });
      }
      return jsonResponse(res, 200, {
        ok: true, sessionId: acp.sessionId, agent: "claude", kind: "acp", spawnRequestId: acp.requestId,
      });
    }
    const newId = spawnSession(spawnRequest, cwd);
    if (!newId) {
      return jsonResponse(res, 500, { error: `Failed to spawn ${spawnRequest}` });
    }
    return jsonResponse(res, 200, { ok: true, sessionId: newId, agent: spawnRequest });
  }

  // --- Kill a session ---
  if (killRequest && sessionId) {
    const target = sessions.get(sessionId);
    if (!target) {
      return jsonResponse(res, 404, { error: "No session with that ID" });
    }
    // A LIVE ACP session runs inside Zed's fork, so ending the slot here would
    // stop nothing — the #53 fake kill, with the agent still editing the tree.
    // Ask the fork to tear it down instead (#88) and report only what actually
    // happened: the `ended` event comes from the fork's own deregister, never
    // from this handler. An ACP slot that has already ended falls through to
    // killSession below, where marking an over session over is no lie.
    if (target.kind === "acp" && target.state !== "ended") {
      const closed = await requestAcpClose(sessionId);
      if (closed === null) {
        return jsonResponse(res, 502, {
          error: "ACP session is not reachable (its Zed adapter is not connected); nothing was stopped",
          sessionId,
        });
      }
      if (!closed.ok) {
        return jsonResponse(res, 504, { error: closed.error, sessionId });
      }
      return jsonResponse(res, 200, { ok: true, sessionId, kind: "acp" });
    }
    killSession(sessionId);
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
        // The rewrite is the HOOK response's contract, but the resolved
        // decision also reaches the ACP echo (acp.js), which must map the
        // behavior the user CHOSE — keyed on the rewritten value, a wrist
        // "Always Allow" landed on the agent as its allow_once option (#110).
        decision.requestedBehavior = requestedBehavior;
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

    // ACP session (issue #77): hosted by Zed's forked adapter, not a PTY we own
    // and not a headless fork. Dictation is delivered into the LIVE session over
    // the loopback channel — the fork's injectUserPrompt wakes it if idle. No
    // detached `claude -p` (that corrupts the tree); a fork that is not
    // connected is surfaced honestly so the wear side keeps the text as a draft.
    if (targetSession && targetSession.kind === "acp") {
      const promptText = command.replace(/\n$/, "").trim();
      if (!promptText) return jsonResponse(res, 400, { error: "Empty command" });
      if (!injectToAcpSession(targetSession.id, promptText, "watch")) {
        return jsonResponse(res, 502, {
          error: "ACP session is not reachable (its Zed adapter is not connected); dictation not delivered",
          sessionId: targetSession.id,
        });
      }
      // The injected turn's working/idle rides the settings.json hooks the SDK
      // fires, which resolve to this same slot (hook-twin correlation).
      return jsonResponse(res, 200, { ok: true, sessionId: targetSession.id, agent: targetSession.agent, prompt: true });
    }

    // Session exists but has no PTY, and it is not ACP — so the bridge owns no
    // input channel into it at all (issue #69 / #81). This used to run
    // `claude -p "<text>" --continue`: a DETACHED headless fork of the live
    // session, concurrently editing the same working tree. A control that
    // claimed to talk to the session actually spawned a second, invisible
    // editor. Refuse honestly instead — the same honesty class as #53's
    // fake-kill, and the reason the watch gates its Dictate affordance on
    // `dictatable` rather than on `external`.
    //
    // The two refusals are distinguished deliberately: a bridge-SPAWNED session
    // that has merely ended has the same PTY-less shape as an external one, and
    // must not be mislabeled as a session the bridge does not own.
    if (targetSession && !targetSession.ptyProcess) {
      if (targetSession.state === "ended") {
        return jsonResponse(res, 409, {
          error: `Session ${targetSession.id} has ended; command not injected`,
          sessionId: targetSession.id,
        });
      }
      return jsonResponse(res, 409, {
        error: `Session ${targetSession.id} is an external session the bridge does not own; dictation is unavailable`,
        sessionId: targetSession.id,
        external: true,
      });
    }

    if (!targetSession) {
      // Auto-spawn a new session and dictate into it.
      const requestedAgent = agent || "claude";
      const cwd = resolveSpawnCwd(res, body.cwd);
      if (cwd === null) return; // 400 already sent — no session slot created

      // Dictating with nothing to dictate INTO. Under Zed-only a claude session
      // is born in the fork exactly like the explicit spawn action (#91): this
      // composes the same two machines — requestAcpSpawn, then the ordinary ACP
      // inject — instead of the PTY auto-spawn it replaced, which was the last
      // path that could mint a claude session Zed never sees (and which
      // inherited #86's unsubmitted-text bug). Codex has no ACP adapter, so it
      // keeps the PTY path below.
      if (requestedAgent === "claude") {
        const promptText = command.replace(/\n$/, "").trim();
        if (!promptText) return jsonResponse(res, 400, { error: "Empty command" });
        const acp = await requestAcpSpawn(cwd);
        if (acp === null) {
          return jsonResponse(res, 409, {
            error: "No Zed agent connection — open Zed (claude-watch agent) and try again",
          });
        }
        if (!acp.ok) {
          // Same attribution contract as the spawn action: a session that
          // finishes creating after this answer announces itself over SSE
          // carrying this requestId, so the client can recognise it.
          return jsonResponse(res, 409, { error: acp.error, spawnRequestId: acp.requestId });
        }
        // The session EXISTS from here on, so a delivery failure must name it:
        // the fork can die between its ack and this write, and a client told
        // only "failed" would strand a live session it could still dictate at.
        if (!injectToAcpSession(acp.sessionId, promptText, "watch")) {
          return jsonResponse(res, 502, {
            error: "Spawned a Zed session but its adapter is no longer reachable; dictation not delivered",
            sessionId: acp.sessionId, agent: "claude", kind: "acp",
            spawnRequestId: acp.requestId, spawned: true,
          });
        }
        return jsonResponse(res, 200, {
          ok: true, sessionId: acp.sessionId, agent: "claude", kind: "acp",
          spawnRequestId: acp.requestId, spawned: true, prompt: true,
        });
      }
      // Codex: a bridge-owned PTY. Inject the command only once the PTY has
      // produced its first output (the agent is actually up); a blind timed
      // write silently dropped the command when the PTY died or wasn't ready,
      // while the client still saw ok:true.
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
