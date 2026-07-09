// Permission flow: pending permission requests from Claude Code hooks that
// block until the watch responds (or the timeout auto-denies, or the hook
// request itself goes away and the pending entry is canceled).
import { log } from "./util.js";
import { PERMISSION_TIMEOUT_MS } from "./config.js";
import { registerSseSyncProvider } from "./transport-sse.js";

/** @type {Map<string, {resolve: Function, timer: ReturnType<typeof setTimeout>, sessionId: string | null, payload: Record<string, any> | null}>} */
export const pendingPermissions = new Map();
/** @type {Map<string, Array>} */
export const pendingPermissionBodies = new Map();

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

// `sessionId` and `payload` (the permission-request event body) are kept on
// the pending entry so connect-time snapshots can re-send the prompt to a
// client that missed it — a pending permission-request can be evicted from
// the SSE ring buffer by ordinary pty-output before a disconnected watch
// reconnects.
export function waitForPermission(permissionId, { sessionId = null, payload = null } = {}) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      pendingPermissions.delete(permissionId);
      // The suggestion body stored by hooks.js would otherwise leak forever:
      // the decision path (commands.js) deletes it, but nobody answers here.
      pendingPermissionBodies.delete(permissionId);
      log("warn", `Permission ${permissionId} timed out after ${PERMISSION_TIMEOUT_MS / 1000}s, auto-denying`);
      resolve({ behavior: "deny", reason: "Timed out waiting for watch response" });
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

// Cancel a pending permission whose blocking hook request went away (Claude
// Code answered in the terminal, the user pressed Esc, or the hook-side
// timeout aborted the request). The resolved decision is marked `canceled` so
// the hook handler knows there is no live socket to answer on. Returns false
// if the permission was already resolved/timed out (cancel is a no-op then).
export function cancelPermission(permissionId) {
  const pending = pendingPermissions.get(permissionId);
  if (!pending) return false;
  clearTimeout(pending.timer);
  pendingPermissions.delete(permissionId);
  pendingPermissionBodies.delete(permissionId);
  pending.resolve({ behavior: "deny", reason: "Hook request aborted", canceled: true });
  return true;
}

// Connect-time snapshot: re-send every pending hook permission to a newly
// connected SSE client. Runs on EVERY connect (mirroring the Codex synthetic
// permission sync in codex.js) so late joiners and fresh pairs always see the
// full set of prompts awaiting an answer, even after ring-buffer eviction.
registerSseSyncProvider(function* pendingPermissionsSync() {
  for (const [permissionId, pending] of pendingPermissions) {
    if (!pending.payload) continue;
    const payload = { ...pending.payload, permissionId };
    if (pending.sessionId != null) payload.sessionId = pending.sessionId;
    yield { event: "permission-request", data: JSON.stringify(payload) };
  }
});
