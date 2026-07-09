// Session attribution, in-process: hook events are keyed on the payload's
// session_id and bound to PTY-backed slots on the first cwd-matched event;
// cwd is only used when session_id is absent, and a cwd mismatch must never
// fall back to "most recent active" (that used to display project B's
// permission prompts under project A). PTY-backed slots are impractical to
// create over a real socket (they'd spawn real agent binaries), hence the
// direct sessions-map setup here; black-box coverage of the external-session
// flow lives in session-end.test.js.
//
// Env overrides must be set before any bridge module loads (config.js reads
// them once at evaluation), hence the dynamic imports inside the tests.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Never let a bridge module near the real ~/.claude-watch, even lazily.
const credsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-unit-creds-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = credsDir;
after(() => {
  try { fs.rmSync(credsDir, { recursive: true, force: true }); } catch { /* ignore */ }
});

const fakePty = () => ({ kill() { /* noop */ } });

function ptySlot(id, cwd, extra = {}) {
  return {
    id,
    agent: "claude",
    cwd,
    folderName: path.basename(cwd),
    ptyProcess: fakePty(),
    state: "running",
    createdAt: Date.now(),
    ...extra,
  };
}

test("two PTY sessions in the same cwd stay separate; events route by session_id", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    sessions.set("slot-1", ptySlot("slot-1", "/tmp/attr-same-cwd"));
    sessions.set("slot-2", ptySlot("slot-2", "/tmp/attr-same-cwd"));

    // First event from each Claude Code instance binds it to an unclaimed slot.
    const first = resolveHookSession({ session_id: "cc-a", cwd: "/tmp/attr-same-cwd", tool_name: "Bash" });
    const second = resolveHookSession({ session_id: "cc-b", cwd: "/tmp/attr-same-cwd", tool_name: "Bash" });
    assert.equal(first, "slot-1", "first session_id claims the first unbound slot");
    assert.equal(second, "slot-2", "second session_id must NOT collapse onto the already-claimed slot");
    assert.notEqual(first, second, "two instances in the same cwd stay separate");

    // Subsequent events keep routing to the bound slot, in either order.
    assert.equal(resolveHookSession({ session_id: "cc-b", cwd: "/tmp/attr-same-cwd" }), "slot-2");
    assert.equal(resolveHookSession({ session_id: "cc-a", cwd: "/tmp/attr-same-cwd" }), "slot-1");
    // ... even if the payload omits cwd once bound.
    assert.equal(resolveHookSession({ session_id: "cc-a" }), "slot-1");
  } finally {
    sessions.clear();
  }
});

test("hook events from an unknown cwd never attach to an existing different-cwd session", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    sessions.set("proj-a-slot", ptySlot("proj-a-slot", "/tmp/attr-proj-a"));

    // Legacy payload (no session_id) from a different cwd: must not fall back
    // to the most-recent-active PTY session in /tmp/attr-proj-a.
    const legacySid = resolveHookSession({ cwd: "/tmp/attr-proj-b", tool_name: "Bash" });
    assert.notEqual(legacySid, "proj-a-slot", "cwd mismatch must not attach to a different-cwd session");
    const legacySlot = sessions.get(legacySid);
    assert.equal(legacySlot.cwd, "/tmp/attr-proj-b", "auto-created session carries the hook's own cwd");
    assert.equal(legacySlot.ptyProcess, null, "auto-created session is external (no bridge PTY)");

    // Same guarantee when the payload carries a session_id.
    const keyedSid = resolveHookSession({ session_id: "cc-c", cwd: "/tmp/attr-proj-c" });
    assert.notEqual(keyedSid, "proj-a-slot");
    assert.equal(sessions.get(keyedSid).cwd, "/tmp/attr-proj-c");

    // The different-cwd session_id must not have claimed the PTY slot either.
    assert.equal(sessions.get("proj-a-slot").hookSessionId, undefined);
  } finally {
    sessions.clear();
  }
});

test("legacy payloads without session_id reuse the exact-cwd session", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    const first = resolveHookSession({ cwd: "/tmp/attr-legacy", source: "codex" });
    const second = resolveHookSession({ cwd: "/tmp/attr-legacy", source: "codex" });
    assert.equal(first, second, "same-cwd legacy events share one session");
    assert.equal(sessions.get(first).agent, "codex");
  } finally {
    sessions.clear();
  }
});

test("endHookSession ends external sessions only and never creates one", async () => {
  const { sessions, resolveHookSession, endHookSession } = await import("../sessions.js");

  try {
    // External session created by a hook event.
    const externalId = resolveHookSession({ session_id: "cc-end", cwd: "/tmp/attr-end" });
    assert.equal(sessions.get(externalId).state, "running");

    // SessionEnd for an unknown session must not create anything.
    const before = sessions.size;
    assert.equal(endHookSession({ session_id: "cc-never-seen", cwd: "/tmp/attr-nowhere" }), null);
    assert.equal(sessions.size, before, "SessionEnd never auto-creates a session");

    // SessionEnd for the known external session ends it.
    assert.equal(endHookSession({ session_id: "cc-end", cwd: "/tmp/attr-end" }), externalId);
    const ended = sessions.get(externalId);
    assert.equal(ended.state, "ended");
    assert.ok(ended.endedAt, "ended session gets an endedAt so pruning can age it out");

    // A PTY-backed slot is left to its PTY close handler.
    sessions.set("pty-slot", ptySlot("pty-slot", "/tmp/attr-pty"));
    resolveHookSession({ session_id: "cc-pty", cwd: "/tmp/attr-pty" });
    assert.equal(endHookSession({ session_id: "cc-pty" }), "pty-slot");
    assert.equal(sessions.get("pty-slot").state, "running", "bridge-owned slot ends via PTY close, not the hook");
  } finally {
    sessions.clear();
  }
});

test("ended external sessions are pruned after the grace period", async () => {
  const { sessions, resolveHookSession, endHookSession, pruneEndedSessions } = await import("../sessions.js");
  const { SESSION_PRUNE_GRACE_MS } = await import("../config.js");

  try {
    const sid = resolveHookSession({ session_id: "cc-prune", cwd: "/tmp/attr-prune" });
    endHookSession({ session_id: "cc-prune" });

    pruneEndedSessions(Date.now());
    assert.ok(sessions.has(sid), "ended session stays visible during the grace period");

    pruneEndedSessions(Date.now() + SESSION_PRUNE_GRACE_MS);
    assert.equal(sessions.has(sid), false, "ended external session is pruned after the grace period");

    // The session_id binding died with the slot: a later event with the same
    // session_id gets a fresh slot instead of a dangling reference.
    const fresh = resolveHookSession({ session_id: "cc-prune", cwd: "/tmp/attr-prune" });
    assert.notEqual(fresh, sid);
    assert.equal(sessions.get(fresh).state, "running");
  } finally {
    sessions.clear();
  }
});
