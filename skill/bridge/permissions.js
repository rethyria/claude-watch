// Permission flow: pending permission requests from Claude Code hooks that
// block until the watch responds (or the prompt expires unanswered, or the
// hook request itself goes away and the pending entry is canceled).
import { log, stableStringify } from "./util.js";
import { PERMISSION_TIMEOUT_MS } from "./config.js";
import { registerSseSyncProvider, pushSseEvent } from "./transport-sse.js";

/** @type {Map<string, {resolve: Function, timer: ReturnType<typeof setTimeout>, sessionId: string | null, payload: Record<string, any> | null, toolUseId: string | null}>} */
export const pendingPermissions = new Map();
/** @type {Map<string, Array>} */
export const pendingPermissionBodies = new Map();
/**
 * tool_use_id -> permissionId. The reverse index that lets a PostToolUse hook
 * find the prompt it just proved was answered on the computer (issue #63).
 * @type {Map<string, string>}
 */
export const permissionsByToolUseId = new Map();

// ---------------------------------------------------------------------------
// Machine-readable decision semantics (/v1 contract)
// ---------------------------------------------------------------------------
// Every permission option the bridge broadcasts carries a `behavior` field so
// clients act on machine-readable semantics, never on option position or
// English label substrings (which silently invert an approval into a denial
// when wording or ordering changes):
//   allow        — approve this request once
//   allow-always — approve AND persist the hook's permission suggestions
//                  (exactly the legacy iOS `allowAll` path)
//   deny         — reject the request
export const PERMISSION_BEHAVIORS = new Set(["allow", "allow-always", "deny"]);

// Normalize an option list to the canonical shape {behavior, label,
// description?}. Every permission surface (the Claude hook prompt and the
// Codex synthetic exec-approval menu) builds its options through here.
// Throwing on a behavior-less option beats silently broadcasting one that a
// client could only interpret by guessing from its position or wording.
export function canonicalPermissionOptions(entries) {
  return entries.map((entry) => {
    if (!entry || !PERMISSION_BEHAVIORS.has(entry.behavior)) {
      throw new Error(`Permission option without machine-readable behavior: ${JSON.stringify(entry)}`);
    }
    const option = { behavior: entry.behavior, label: String(entry.label ?? "") };
    if (entry.description !== undefined) option.description = String(entry.description);
    return option;
  });
}

// Canonical option list for a Claude Code permission prompt. The allow-always
// option is only offered when the hook supplied permission suggestions to
// persist — offering it without any would be the old "Yes, allow all" lie
// (nothing gets remembered and the prompt recurs on the next tool call).
export function defaultPermissionOptions({ canAllowAlways = false } = {}) {
  const entries = [
    { behavior: "allow", label: "Yes", description: "Allow this once" },
  ];
  if (canAllowAlways) {
    entries.push({
      behavior: "allow-always",
      label: "Yes, don't ask again",
      description: "Allow and apply the suggested permission rules",
    });
  }
  entries.push({ behavior: "deny", label: "No", description: "Deny this request" });
  return canonicalPermissionOptions(entries);
}

// Every way a pending permission dies WITHOUT the user deciding funnels
// through here, so there is exactly one place that chooses what the blocked
// hook is told and exactly one place that tells clients the prompt is void.
//
// `noDecision` is the honest wire. The bridge used to answer `deny`, and
// Claude Code's permission fan-out acts on it: `if(!R||!c())return; if(f&&y)
// f.cancelRequest(y); ... d(R)` — a deny that arrives while the user has NOT
// yet answered CANCELS the dialog still on their screen and records a
// `reject` in the transcript. The bridge was fabricating a refusal AND
// destroying the user's chance to answer (issue #63). An empty
// hookSpecificOutput makes runHooks fall through to `return null`, which
// exits before cancelRequest: nothing allowed, nothing denied, the agent's
// own prompt keeps the answer.
function voidPermission(permissionId, reason, { canceled = false } = {}) {
  const pending = pendingPermissions.get(permissionId);
  if (!pending) return false;
  clearTimeout(pending.timer);
  pendingPermissions.delete(permissionId);
  // The suggestion body stored by hooks.js would otherwise leak forever: the
  // decision path (commands.js) deletes it, but nobody answers here.
  pendingPermissionBodies.delete(permissionId);
  if (pending.toolUseId) permissionsByToolUseId.delete(pending.toolUseId);
  // Push BEFORE resolve(): resolve() lets hooks.js finish the HTTP response
  // and Claude Code move on, and a client told only after that has been left
  // holding a lying card for the whole window.
  pushSseEvent("permission-cleared", { permissionId, reason }, pending.sessionId);
  pending.resolve({ noDecision: true, canceled, reason });
  return true;
}

// `sessionId` and `payload` (the permission-request event body) are kept on
// the pending entry so connect-time snapshots can re-send the prompt to a
// client that missed it — a pending permission-request can be evicted from
// the SSE ring buffer by ordinary pty-output before a disconnected watch
// reconnects. `toolUseId` is the correlation key from the PreToolUse that
// fired microseconds earlier (see claimToolUseId) — null when we could not
// pair, which is a fallback to expiry, never a guess.
export function waitForPermission(permissionId, { sessionId = null, payload = null, toolUseId = null } = {}) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      // Deliberately out of the decision vocabulary: from here on a `deny` in
      // bridge.log means a human chose deny. Nothing else may mint one.
      log("warn", `Permission ${permissionId} expired after ${PERMISSION_TIMEOUT_MS / 1000}s unanswered — returning no-decision (the agent's own prompt keeps the answer). Nothing was denied.`);
      voidPermission(permissionId, "expired");
    }, PERMISSION_TIMEOUT_MS);

    pendingPermissions.set(permissionId, { resolve, timer, sessionId, payload, toolUseId });
    if (toolUseId) permissionsByToolUseId.set(toolUseId, permissionId);
  });
}

export function resolvePermission(permissionId, decision) {
  const pending = pendingPermissions.get(permissionId);
  if (!pending) return false;
  clearTimeout(pending.timer);
  pendingPermissions.delete(permissionId);
  // Without this the reverse index leaks an entry per watch-answered prompt,
  // and a later PostToolUse for that tool_use_id would chase a dead id.
  if (pending.toolUseId) permissionsByToolUseId.delete(pending.toolUseId);
  pending.resolve(decision);
  return true;
}

// Cancel a pending permission whose blocking hook request went away (the
// hook-side timeout aborted the request, or the process died). The resolved
// decision is marked `canceled` so the hook handler knows there is no live
// socket to answer on. Returns false if the permission was already resolved
// or expired (cancel is a no-op then).
//
// NOTE this is NOT the "answered in the terminal" path it once claimed to be:
// Claude Code's fan-out gives the IDE bridge, MCP channel and approval
// watcher mutual teardown handles but NONE for the hooks, so answering
// elsewhere leaves this socket open and simply discards our late reply. That
// is why this handler never fired in 48h of production logs, and why the
// tool_use_id correlation below exists at all.
export function cancelPermission(permissionId) {
  return voidPermission(permissionId, "hook-aborted", { canceled: true });
}

// ---------------------------------------------------------------------------
// PreToolUse -> PermissionRequest correlation (issue #63)
// ---------------------------------------------------------------------------
// Claude Code's PermissionRequest hook input is the ONE tool-scoped event that
// carries no tool_use_id (verified against the 2.1.215 binary:
// `hook_event_name:"PermissionRequest",tool_name:e,tool_input:r,permission_
// suggestions:i`). PreToolUse fires 5-17ms earlier for the same tool call and
// DOES carry it, so this map is the only bridge between a prompt and the
// tool_use_id that a later PostToolUse will use to prove it was answered.
//
// Pairing is on (tool_name, tool_input) and deliberately NOT on session: at
// bridge.log:4108-4110 a PreToolUse resolved to one session while the
// PermissionRequest 17ms later resolved to another, and session-scoped
// pairing would simply have missed. Both events carry the ORIGINAL tool_input
// (the fan-out passes `t`, not `n.updatedInput`), so the fingerprint holds
// even when the user later approves-with-edits.
/** @type {Map<string, {toolUseId: string | null, atMs: number}>} */
const preToolUseByFingerprint = new Map();
const PRE_TOOL_USE_TTL_MS = 60_000;
const PRE_TOOL_USE_MAX = 256;

// Deterministic fingerprint. Null tool_name means unfingerprintable, which
// costs nothing: the permission stays uncorrelated and falls back to expiry.
export function toolFingerprint(body) {
  if (typeof body?.tool_name !== "string" || !body.tool_name) return null;
  return `${body.tool_name} ${stableStringify(body.tool_input ?? null)}`;
}

function prunePreToolUse(nowMs) {
  for (const [fingerprint, entry] of preToolUseByFingerprint) {
    if (nowMs - entry.atMs > PRE_TOOL_USE_TTL_MS) preToolUseByFingerprint.delete(fingerprint);
  }
  // Map iteration is insertion order, so the first key is the oldest.
  while (preToolUseByFingerprint.size > PRE_TOOL_USE_MAX) {
    const oldest = preToolUseByFingerprint.keys().next().value;
    preToolUseByFingerprint.delete(oldest);
  }
}

// Record a PreToolUse so the PermissionRequest that follows it can claim its
// tool_use_id. Two live tool calls with an identical fingerprint are marked
// AMBIGUOUS (toolUseId: null) rather than last-write-wins: binding a prompt to
// the wrong tool_use_id would clear the wrong card off the wrist, which is
// strictly worse than leaving it to expire honestly.
export function notePreToolUse(body) {
  if (typeof body?.tool_use_id !== "string" || !body.tool_use_id) return;
  const fingerprint = toolFingerprint(body);
  if (!fingerprint) return;
  const now = Date.now();
  prunePreToolUse(now);
  const existing = preToolUseByFingerprint.get(fingerprint);
  if (existing && existing.toolUseId !== body.tool_use_id) {
    preToolUseByFingerprint.set(fingerprint, { toolUseId: null, atMs: now });
    return;
  }
  preToolUseByFingerprint.set(fingerprint, { toolUseId: body.tool_use_id, atMs: now });
}

// Claim the tool_use_id recorded for this PermissionRequest's tool call.
// CONSUMING: one PreToolUse backs at most one permission, so a second prompt
// with the same fingerprint cannot inherit a stale id. Returns null on miss,
// TTL expiry or ambiguity — every branch fails closed to honest expiry.
export function claimToolUseId(body) {
  const fingerprint = toolFingerprint(body);
  if (!fingerprint) return null;
  const entry = preToolUseByFingerprint.get(fingerprint);
  if (!entry) return null;
  preToolUseByFingerprint.delete(fingerprint);
  if (Date.now() - entry.atMs > PRE_TOOL_USE_TTL_MS) return null;
  return entry.toolUseId;
}

// A PostToolUse (or PostToolUseFailure) for a gated tool_use_id is the one
// EXACT proof the bridge ever receives that a prompt was answered on the
// computer: a tool that RAN was ALLOWED. Claude Code never aborts the
// blocking permission hook when the prompt is answered in the IDE, so without
// this the prompt sat on the wrist until it expired 570s later.
//
// Correlation is by tool_use_id ALONE. Session-activity inference was scored
// against the production log and would have cleared a LIVE prompt 16ms after
// it appeared, because sibling subagent traffic straddled the registration.
// Matching PostToolUse's tool_input directly against the permission's would
// also fail exactly when the user approves-with-EDITS in the IDE dialog: the
// permission carries the pre-edit input, PostToolUse the executed one.
export function expirePermissionForToolUse(toolUseId) {
  if (typeof toolUseId !== "string" || !toolUseId) return null;
  const permissionId = permissionsByToolUseId.get(toolUseId);
  if (!permissionId) return null;
  if (voidPermission(permissionId, "answered-elsewhere")) return permissionId;
  // Index entry outlived its permission (belt-and-braces; every resolve path
  // clears it). Drop it so it cannot chase a dead id again.
  permissionsByToolUseId.delete(toolUseId);
  return null;
}

// ---------------------------------------------------------------------------
// Connect-time snapshot
// ---------------------------------------------------------------------------
// Other modules own permission maps of their own (codex.js's synthetic
// exec-approvals) and cannot be imported from here — they import THIS module.
// They register their live ids instead. Any future permission source MUST
// register too: the authoritative frame below retracts every id it does not
// list, so an unregistered source would have its LIVE prompts nuked on every
// reconnect.
/** @type {Array<() => Iterable<string>>} */
const pendingPermissionIdSources = [];

export function registerPendingPermissionIdSource(source) {
  pendingPermissionIdSources.push(source);
}

// Re-send every pending hook permission to a newly connected SSE client. Runs
// on EVERY connect (mirroring the Codex synthetic permission sync in codex.js)
// so late joiners and fresh pairs always see the full set of prompts awaiting
// an answer, even after ring-buffer eviction.
export function* pendingPermissionsSync() {
  const permissionIds = [...pendingPermissions.keys()];
  for (const source of pendingPermissionIdSources) permissionIds.push(...source());
  // #63, structural half: the per-prompt re-send below is ADDITIVE and cannot
  // tell a client to DROP a prompt the bridge no longer has. A watch offline
  // when a prompt died — whose permission-cleared was evicted from the ring
  // buffer (500 entries, and production burns ~9000 tool-outputs in 48h)
  // before it reconnected — held that card until the app was force-stopped.
  // This frame is the whole truth: drop every pending prompt whose id is
  // absent. Retraction ONLY; it never creates, so the re-sends below still
  // carry the payloads. Emitting it first is safe precisely because the
  // retained set is a superset of what those re-sends restore — no flicker.
  yield { event: "permission-sync", data: JSON.stringify({ permissionIds }) };
  for (const [permissionId, pending] of pendingPermissions) {
    if (!pending.payload) continue;
    const payload = { ...pending.payload, permissionId };
    if (pending.sessionId != null) payload.sessionId = pending.sessionId;
    yield { event: "permission-request", data: JSON.stringify(payload) };
  }
}

registerSseSyncProvider(pendingPermissionsSync);
