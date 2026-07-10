// Codex integration: session-file scanner (~/.codex/sessions JSONL tailing),
// TUI log monitor (~/.codex/log/codex-tui.log), synthetic exec-approval
// permissions, and keystroke-based resolution into the Codex PTY.
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { log } from "./util.js";
import {
  CODEX_SESSION_SCAN_INTERVAL_MS,
  CODEX_SESSION_BOOTSTRAP_LOOKBACK_MS,
  CODEX_SESSION_SCAN_LIMIT,
  CODEX_SESSION_ROOT,
  CODEX_LOG_FILE,
} from "./config.js";
import { pushSseEvent, registerSseSyncProvider } from "./transport-sse.js";
import { canonicalPermissionOptions, PERMISSION_BEHAVIORS } from "./permissions.js";
import {
  sessions,
  attachPtyToSession,
  registerSessionCleanupHook,
  writeToSessionStdin,
} from "./sessions.js";

/** @type {Map<string, {offset: number, remainder: string, sessionId: string | null, cwd?: string, createdAt?: number, initialized: boolean}>} */
const codexSessionFiles = new Map();
/** @type {Map<string, {sessionId: string, name: string, args: Record<string, any>}>} */
const codexPendingToolCalls = new Map();
/** @type {Map<string, {command: string, justification: string, workdir: string, prefixRule: string[], createdAt: number}>} */
const codexExecApprovalCandidates = new Map();
/** @type {Map<string, {sessionId: string, optionCount: number, payload: Record<string, any>}>} */
export const codexSyntheticPermissions = new Map();
/** @type {Map<string, string>} */
const codexSyntheticPermissionBySession = new Map();
const codexLogState = { offset: 0, remainder: "", initialized: false };
let codexMonitorInterval = null;

function safeStat(targetPath) {
  try {
    return fs.statSync(targetPath);
  } catch {
    return null;
  }
}

function listRecentCodexSessionFiles(rootDir) {
  const results = [];
  const stack = [rootDir];

  while (stack.length > 0) {
    const current = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      continue;
    }

    for (const entry of entries) {
      const fullPath = path.join(current, entry.name);
      if (entry.isDirectory()) {
        stack.push(fullPath);
        continue;
      }
      if (!entry.isFile() || !entry.name.endsWith(".jsonl")) continue;
      const stat = safeStat(fullPath);
      if (!stat) continue;
      results.push({ filePath: fullPath, mtimeMs: stat.mtimeMs, size: stat.size });
    }
  }

  results.sort((a, b) => b.mtimeMs - a.mtimeMs);
  return results.slice(0, CODEX_SESSION_SCAN_LIMIT);
}

function readFileSlice(filePath, start, length) {
  const fd = fs.openSync(filePath, "r");
  try {
    const buffer = Buffer.alloc(length);
    const bytesRead = fs.readSync(fd, buffer, 0, length, start);
    return buffer.subarray(0, bytesRead).toString("utf-8");
  } finally {
    fs.closeSync(fd);
  }
}

function touchExternalSession(sessionId, cwd, createdAt) {
  const resolvedCwd = cwd || process.env.HOME || process.cwd();
  const folderName = path.basename(resolvedCwd) || resolvedCwd;
  const existing = sessions.get(sessionId);

  if (existing) {
    const wasEnded = existing.state !== "running";
    existing.agent = "codex";
    existing.cwd = resolvedCwd;
    existing.folderName = folderName;
    existing.state = "running";
    existing.createdAt = createdAt || existing.createdAt || Date.now();
    if (wasEnded) {
      pushSseEvent("session", { state: "running", agent: "codex", cwd: resolvedCwd, folderName }, sessionId);
      log("info", `Revived Codex session ${sessionId} (${folderName}) from local session data`);
    }
    return existing;
  }

  const slot = {
    id: sessionId,
    agent: "codex",
    cwd: resolvedCwd,
    folderName,
    ptyProcess: null,
    state: "running",
    createdAt: createdAt || Date.now(),
  };
  sessions.set(sessionId, slot);
  pushSseEvent("session", { state: "running", agent: "codex", cwd: resolvedCwd, folderName }, sessionId);
  log("info", `Detected Codex session ${sessionId} (${folderName}) from local session data`);
  return slot;
}

function endExternalSession(sessionId, reason = "codex-exit") {
  const slot = sessions.get(sessionId);
  if (!slot || slot.state === "ended") return;
  slot.state = "ended";
  slot.ptyProcess = null;
  clearCodexSyntheticPermissionForSession(sessionId, reason);
  pushSseEvent("session", { state: "ended", agent: slot.agent, folderName: slot.folderName, reason }, sessionId);
  log("info", `Marked external session ${sessionId} as ended (${reason})`);
}

function parseJsonLine(line) {
  try {
    return JSON.parse(line);
  } catch {
    return null;
  }
}

function parseFunctionCallArgs(rawArgs) {
  if (typeof rawArgs !== "string") return {};
  try {
    const parsed = JSON.parse(rawArgs);
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function extractPatchPaths(rawPatch) {
  if (typeof rawPatch !== "string" || rawPatch.length === 0) return [];
  const paths = [];
  for (const line of rawPatch.split("\n")) {
    const match = line.match(/^\*\*\* (?:Update|Add|Delete) File: (.+)$/);
    if (match) paths.push(match[1]);
  }
  return [...new Set(paths)];
}

function emitCodexToolEvent(sessionId, toolName, toolInput = {}, toolOutput = null) {
  pushSseEvent("tool-output", {
    source: "codex",
    tool_name: toolName,
    tool_input: toolInput,
    tool_output: toolOutput,
  }, sessionId);
}

function emitCodexToolResult(sessionId, pendingCall, output) {
  if (!pendingCall || !sessionId) return;

  switch (pendingCall.name) {
    case "exec_command":
      emitCodexToolEvent(sessionId, "Bash", { command: pendingCall.args.cmd || "" }, output);
      break;
    case "apply_patch": {
      const patchPaths = extractPatchPaths(pendingCall.args.patch);
      if (patchPaths.length === 0) {
        emitCodexToolEvent(sessionId, "Edit", {}, output);
        break;
      }
      for (const filePath of patchPaths) {
        emitCodexToolEvent(sessionId, "Edit", { file_path: filePath }, output);
      }
      break;
    }
    default:
      emitCodexToolEvent(sessionId, pendingCall.name, pendingCall.args, output);
      break;
  }
}

function truncateText(value, maxLength = 80) {
  if (typeof value !== "string") return "";
  return value.length > maxLength ? `${value.slice(0, maxLength - 1)}…` : value;
}

// Canonical option list for the Codex synthetic exec-approval menu — built
// through the same canonicalPermissionOptions() as the Claude hook prompts,
// so every option carries a machine-readable behavior. Exported for the unit
// tests in test/permission-semantics-unit.test.js.
export function buildCodexApprovalOptions(prefixRule = []) {
  const entries = [
    {
      behavior: "allow",
      label: "Yes, proceed",
      description: "Run this command once",
    },
  ];

  if (Array.isArray(prefixRule) && prefixRule.length > 0) {
    entries.push({
      behavior: "allow-always",
      label: "Yes, don't ask again",
      description: `Trust ${prefixRule.join(" ")} in future`,
    });
  }

  entries.push({
    behavior: "deny",
    label: "No",
    description: "Deny this command and return to Codex",
  });

  return canonicalPermissionOptions(entries);
}

function recordCodexExecApprovalCandidate(line) {
  const match = line.match(/ToolCall: exec_command (\{.*\}) thread_id=([0-9a-f-]+)/i);
  if (!match) return;

  let args;
  try {
    args = JSON.parse(match[1]);
  } catch {
    return;
  }

  if (args?.sandbox_permissions !== "require_escalated") return;

  codexExecApprovalCandidates.set(match[2], {
    command: args.cmd || "",
    justification: args.justification || "Would you like to run this command?",
    workdir: args.workdir || "",
    prefixRule: Array.isArray(args.prefix_rule) ? args.prefix_rule : [],
    createdAt: Date.now(),
  });
}

function surfaceCodexExecApproval(sessionId) {
  const slot = sessions.get(sessionId);
  const candidate = codexExecApprovalCandidates.get(sessionId);
  if (!slot || !candidate) return;

  const existingId = codexSyntheticPermissionBySession.get(sessionId);
  if (existingId) return;

  const permissionId = crypto.randomUUID();
  const options = buildCodexApprovalOptions(candidate.prefixRule);
  const payload = {
    permissionId,
    source: "codex",
    tool_name: "ExecApproval",
    // Top-level canonical options mirror the question's option list so /v1
    // clients read one uniform shape across Claude and Codex prompts.
    options,
    tool_input: {
      command: candidate.command,
      workdir: candidate.workdir,
      questions: [
        {
          header: truncateText(`Run: ${candidate.command}`, 72),
          question: candidate.justification || "Would you like to run this command?",
          options,
        },
      ],
    },
  };
  codexSyntheticPermissions.set(permissionId, { sessionId, optionCount: options.length, payload });
  codexSyntheticPermissionBySession.set(sessionId, permissionId);

  pushSseEvent("permission-request", payload, sessionId);

  log("info", `Surfaced Codex approval ${permissionId} for session ${sessionId}`);
}

export function clearCodexSyntheticPermissionForSession(sessionId, reason = "cleared") {
  const permissionId = codexSyntheticPermissionBySession.get(sessionId);
  if (!permissionId) return false;

  codexSyntheticPermissionBySession.delete(sessionId);
  codexSyntheticPermissions.delete(permissionId);
  codexExecApprovalCandidates.delete(sessionId);
  pushSseEvent("permission-cleared", { permissionId, reason }, sessionId);
  return true;
}

export function resolveCodexSyntheticPermission(permissionId, selectedOption, optionIndex, behavior) {
  const synthetic = codexSyntheticPermissions.get(permissionId);
  if (!synthetic) return false;

  const slot = sessions.get(synthetic.sessionId);
  if (!slot) return false;

  const proc = slot.ptyProcess || attachPtyToSession(slot);
  if (!proc || !proc.stdin) return false;

  // Resolve the answer to a canonical behavior. A machine-readable behavior
  // wins outright; legacy index/label answers are looked up in the canonical
  // option list the bridge itself broadcast — never re-derived from position
  // or wording heuristics that could invert an approval into a denial.
  const options = Array.isArray(synthetic.payload?.options) ? synthetic.payload.options : [];
  let resolvedBehavior = "deny";
  if (typeof behavior === "string" && PERMISSION_BEHAVIORS.has(behavior)) {
    resolvedBehavior = behavior;
  } else {
    const normalizedIndex = Number.isInteger(optionIndex) ? optionIndex : -1;
    const chosen = options[normalizedIndex]
      || (selectedOption != null
        ? options.find((opt) => opt.label.toLowerCase() === String(selectedOption).trim().toLowerCase())
        : undefined);
    if (chosen) resolvedBehavior = chosen.behavior;
  }

  // Keystrokes into the Codex TUI approval menu: "y" approves once, "2\n"
  // selects the trust-this-prefix entry (only present when the menu offers
  // it — allow-always degrades to a single allow otherwise), Esc denies and
  // returns to Codex.
  let input = "\u001b";
  if (resolvedBehavior === "allow") {
    input = "y";
  } else if (resolvedBehavior === "allow-always") {
    input = options.some((opt) => opt.behavior === "allow-always") ? "2\n" : "y";
  }

  // Guarded write: a keystroke racing PTY death must not crash the bridge or
  // report a resolution that never reached Codex.
  if (!writeToSessionStdin(slot, input)) return false;
  clearCodexSyntheticPermissionForSession(synthetic.sessionId, "resolved");
  log("info", `Resolved Codex approval ${permissionId} for session ${synthetic.sessionId}`);
  return true;
}

function handleCodexJsonlLine(line, fileState, options = {}) {
  const parsed = parseJsonLine(line);
  if (!parsed) return;

  const bootstrap = options.bootstrap === true;

  if (parsed.type === "session_meta") {
    const sessionId = parsed.payload?.id;
    if (!sessionId) return;

    fileState.sessionId = sessionId;
    fileState.cwd = parsed.payload?.cwd || fileState.cwd;
    fileState.createdAt = Date.parse(parsed.payload?.timestamp || parsed.timestamp || "") || fileState.createdAt || Date.now();

    if (bootstrap && options.allowBootstrap !== true) return;

    touchExternalSession(sessionId, fileState.cwd, fileState.createdAt);
    return;
  }

  const sessionId = fileState.sessionId;
  if (!sessionId || bootstrap) return;
  if (!sessions.has(sessionId) || sessions.get(sessionId)?.state !== "running") {
    touchExternalSession(sessionId, fileState.cwd, fileState.createdAt);
  }

  if (parsed.type === "response_item" && parsed.payload?.type === "function_call") {
    const callId = parsed.payload.call_id;
    if (!callId) return;
    codexPendingToolCalls.set(callId, {
      sessionId,
      name: parsed.payload.name,
      args: parseFunctionCallArgs(parsed.payload.arguments),
    });
    return;
  }

  if (parsed.type === "response_item" && parsed.payload?.type === "function_call_output") {
    const pendingCall = codexPendingToolCalls.get(parsed.payload.call_id);
    emitCodexToolResult(sessionId, pendingCall, parsed.payload.output ?? null);
    if (parsed.payload.call_id) {
      codexPendingToolCalls.delete(parsed.payload.call_id);
    }
    return;
  }

  if (parsed.type !== "event_msg") return;

  const payloadType = parsed.payload?.type;
  if (payloadType === "task_started") {
    touchExternalSession(sessionId, fileState.cwd, fileState.createdAt);
    return;
  }
  if (payloadType === "agent_message" && parsed.payload?.message) {
    emitCodexToolEvent(sessionId, "CodexMessage", {}, parsed.payload.message);
    return;
  }
  if (payloadType === "exec_command_end") {
    const pendingCall = codexPendingToolCalls.get(parsed.payload.call_id);
    const command = pendingCall?.args?.cmd
      || (Array.isArray(parsed.payload.command) ? parsed.payload.command.join(" ") : "");
    emitCodexToolEvent(sessionId, "Bash", { command }, parsed.payload.aggregated_output ?? null);
    if (parsed.payload.call_id) {
      codexPendingToolCalls.delete(parsed.payload.call_id);
    }
    return;
  }
  if (payloadType === "task_complete") {
    pushSseEvent("task-complete", { source: "codex" }, sessionId);
  }
}

function initializeCodexSessionFile(filePath, stat, fileState) {
  const headerSize = Math.min(stat.size, 64 * 1024);
  const header = headerSize > 0 ? readFileSlice(filePath, 0, headerSize) : "";
  const allowBootstrap = Date.now() - stat.mtimeMs <= CODEX_SESSION_BOOTSTRAP_LOOKBACK_MS;

  for (const line of header.split("\n")) {
    if (!line.trim()) continue;
    handleCodexJsonlLine(line, fileState, { bootstrap: true, allowBootstrap });
    if (fileState.sessionId) break;
  }

  fileState.offset = stat.size;
  fileState.remainder = "";
  fileState.initialized = true;
}

function readCodexSessionFileDelta(filePath, stat, fileState) {
  if (stat.size < fileState.offset) {
    fileState.offset = 0;
    fileState.remainder = "";
  }
  if (stat.size === fileState.offset) return;

  const delta = readFileSlice(filePath, fileState.offset, stat.size - fileState.offset);
  fileState.offset = stat.size;

  let chunk = fileState.remainder + delta;
  const lines = chunk.split("\n");
  fileState.remainder = lines.pop() ?? "";

  for (const line of lines) {
    if (!line.trim()) continue;
    handleCodexJsonlLine(line, fileState);
  }
}

function scanCodexSessionFiles() {
  const statRoot = safeStat(CODEX_SESSION_ROOT);
  if (!statRoot || !statRoot.isDirectory()) return;

  const seen = new Set();
  for (const entry of listRecentCodexSessionFiles(CODEX_SESSION_ROOT)) {
    seen.add(entry.filePath);
    const fileState = codexSessionFiles.get(entry.filePath) || {
      offset: 0,
      remainder: "",
      sessionId: null,
      cwd: undefined,
      createdAt: undefined,
      initialized: false,
    };

    if (!fileState.initialized) {
      initializeCodexSessionFile(entry.filePath, entry, fileState);
      codexSessionFiles.set(entry.filePath, fileState);
      continue;
    }

    readCodexSessionFileDelta(entry.filePath, entry, fileState);
    codexSessionFiles.set(entry.filePath, fileState);
  }

  for (const filePath of codexSessionFiles.keys()) {
    if (!seen.has(filePath)) {
      codexSessionFiles.delete(filePath);
    }
  }
}

function consumeCodexLogChunk(text) {
  const combined = codexLogState.remainder + text;
  const lines = combined.split("\n");
  codexLogState.remainder = lines.pop() ?? "";

  for (const line of lines) {
    recordCodexExecApprovalCandidate(line);

    const approvalMatch = line.match(/thread_id=([0-9a-f-]+).*codex\.op="exec_approval".*codex_core::codex: (new|close)/i);
    if (approvalMatch) {
      const [, sessionId, state] = approvalMatch;
      if (state === "new") {
        surfaceCodexExecApproval(sessionId);
      } else {
        clearCodexSyntheticPermissionForSession(sessionId, "closed");
      }
    }

    if (line.includes("Shutting down Codex instance")) {
      const match = line.match(/thread_id=([0-9a-f-]+)/i);
      if (match) {
        clearCodexSyntheticPermissionForSession(match[1], "codex-shutdown");
        endExternalSession(match[1], "codex-shutdown");
      }
    }
  }
}

function scanCodexLog() {
  const stat = safeStat(CODEX_LOG_FILE);
  if (!stat || !stat.isFile()) return;

  if (!codexLogState.initialized) {
    const lookbackSize = Math.min(stat.size, 128 * 1024);
    const startOffset = Math.max(0, stat.size - lookbackSize);
    const bootstrapText = lookbackSize > 0 ? readFileSlice(CODEX_LOG_FILE, startOffset, lookbackSize) : "";
    codexLogState.offset = stat.size;
    codexLogState.remainder = "";
    codexLogState.initialized = true;
    if (bootstrapText) {
      consumeCodexLogChunk(bootstrapText);
    }
    return;
  }

  if (stat.size < codexLogState.offset) {
    codexLogState.offset = 0;
    codexLogState.remainder = "";
  }
  if (stat.size === codexLogState.offset) return;

  const text = readFileSlice(CODEX_LOG_FILE, codexLogState.offset, stat.size - codexLogState.offset);
  codexLogState.offset = stat.size;
  consumeCodexLogChunk(text);
}

export function startCodexMonitor() {
  if (codexMonitorInterval) return;

  scanCodexSessionFiles();
  scanCodexLog();

  codexMonitorInterval = setInterval(() => {
    try {
      scanCodexSessionFiles();
      scanCodexLog();
    } catch (err) {
      log("warn", `Codex monitor scan failed: ${err.message}`);
    }
  }, CODEX_SESSION_SCAN_INTERVAL_MS);
}

export function stopCodexMonitor() {
  if (codexMonitorInterval) {
    clearInterval(codexMonitorInterval);
    codexMonitorInterval = null;
  }
}

// When a PTY-backed session ends, clear any synthetic permission tied to it
// (registered as a callback to avoid a sessions.js → codex.js import cycle).
registerSessionCleanupHook(clearCodexSyntheticPermissionForSession);

// Replay pending synthetic permissions to late-connecting SSE clients (runs
// after the sessions.js provider — sessions.js evaluates first).
registerSseSyncProvider(function* syntheticPermissionsSync() {
  for (const [permissionId, synthetic] of codexSyntheticPermissions) {
    yield {
      event: "permission-request",
      data: JSON.stringify({
        ...synthetic.payload,
        permissionId,
        sessionId: synthetic.sessionId,
      }),
    };
  }
});
