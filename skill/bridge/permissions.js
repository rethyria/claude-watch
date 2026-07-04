// Permission flow: pending permission requests from Claude Code hooks that
// block until the watch responds (or the timeout auto-denies).
import { log } from "./util.js";
import { PERMISSION_TIMEOUT_MS } from "./config.js";

/** @type {Map<string, {resolve: Function, timer: ReturnType<typeof setTimeout>, sessionId: string | null}>} */
export const pendingPermissions = new Map();
/** @type {Map<string, Array>} */
export const pendingPermissionBodies = new Map();

export function waitForPermission(permissionId) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      pendingPermissions.delete(permissionId);
      // The suggestion body stored by hooks.js would otherwise leak forever:
      // the decision path (commands.js) deletes it, but nobody answers here.
      pendingPermissionBodies.delete(permissionId);
      log("warn", `Permission ${permissionId} timed out after ${PERMISSION_TIMEOUT_MS / 1000}s, auto-denying`);
      resolve({ behavior: "deny", reason: "Timed out waiting for watch response" });
    }, PERMISSION_TIMEOUT_MS);

    pendingPermissions.set(permissionId, { resolve, timer });
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
