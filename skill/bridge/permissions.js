// Permission flow: the shared pending-permission core. Prompts are raised by
// the ACP lane (acp.js — permission requests and AskUserQuestion cards teed
// from Zed's adapter) and by the Codex synthetic exec-approval menu
// (codex.js); each blocks its waiter until the watch responds, the prompt
// expires unanswered, or the request is settled elsewhere and canceled.
import { log } from "./util.js";
import { PERMISSION_TIMEOUT_MS } from "./config.js";
import { registerSseSyncProvider, pushSseEvent } from "./transport-sse.js";

/** @type {Map<string, {resolve: Function, timer: ReturnType<typeof setTimeout>, sessionId: string | null, payload: Record<string, any> | null}>} */
export const pendingPermissions = new Map();

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

// Every way a pending permission dies WITHOUT the user deciding funnels
// through here, so there is exactly one place that chooses what the blocked
// hook is told and exactly one place that tells clients the prompt is void.
//
// `noDecision` is the honest wire (issue #63): fabricating a deny for a
// prompt nobody answered would reach the agent as a decision the user never
// made — the ACP lane deliberately sends NOTHING down the inbox for a
// no-decision, so the agent's own dialog (Zed's) keeps the answer.
function voidPermission(permissionId, reason, { canceled = false } = {}) {
  const pending = pendingPermissions.get(permissionId);
  if (!pending) return false;
  clearTimeout(pending.timer);
  pendingPermissions.delete(permissionId);
  // Push BEFORE resolve(): resolve() lets the raising lane finish and move
  // on, and a client told only after that has been left holding a lying card
  // for the whole window.
  pushSseEvent("permission-cleared", { permissionId, reason }, pending.sessionId);
  pending.resolve({ noDecision: true, canceled, reason });
  return true;
}

// `sessionId` and `payload` (the permission-request event body) are kept on
// the pending entry so connect-time snapshots can re-send the prompt to a
// client that missed it — a pending permission-request can be evicted from
// the SSE ring buffer by ordinary pty-output before a disconnected watch
// reconnects.
export function waitForPermission(permissionId, { sessionId = null, payload = null } = {}) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      // Deliberately out of the decision vocabulary: from here on a `deny` in
      // bridge.log means a human chose deny. Nothing else may mint one.
      log("warn", `Permission ${permissionId} expired after ${PERMISSION_TIMEOUT_MS / 1000}s unanswered — returning no-decision (the agent's own prompt keeps the answer). Nothing was denied.`);
      voidPermission(permissionId, "expired");
    }, PERMISSION_TIMEOUT_MS);

    pendingPermissions.set(permissionId, { resolve, timer, sessionId, payload });
  });
}

export function resolvePermission(permissionId, decision) {
  const pending = pendingPermissions.get(permissionId);
  if (!pending) return false;
  clearTimeout(pending.timer);
  pendingPermissions.delete(permissionId);
  pending.resolve(decision);
  return true;
}

// Cancel a pending permission whose request was settled somewhere else — the
// user answered in Zed, the agent withdrew the request, or the session died.
// The resolved decision is marked `canceled` so the raising lane knows there
// is nothing left to answer. Returns false if the permission was already
// resolved or expired (cancel is a no-op then).
export function cancelPermission(permissionId) {
  return voidPermission(permissionId, "hook-aborted", { canceled: true });
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

// Re-send every pending permission to a newly connected SSE client. Runs
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
