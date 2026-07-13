// Issue #53, in-process: "closing" an EXTERNAL (hook-created, PTY-less) session
// from the watch cannot actually stop the still-alive Claude Code process.
// killSession only marks the slot ended; the alive session's next hook used to
// attach to the ended slot SILENTLY, leaving a zombie window until the prune
// deleted it and the next hook recreated it fresh minutes later. The fix:
//   * a hook for an ended-but-watch-killed external slot REVIVES it to running
//     and re-broadcasts the idempotent running `session` event;
//   * an AUTHORITATIVE end (the SessionEnd hook, or a bridge-owned PTY exit) is
//     final — a slot flagged endedAuthoritatively never revives;
//   * PTY-backed slots keep TRUE kill semantics (they are never hookCreated);
//   * every session payload + snapshot entry for a hook-created slot carries the
//     additive `external: true` field (omitted for PTY slots).
//
// Env overrides must be set before any bridge module loads (config.js reads them
// once at evaluation), hence the dynamic imports inside the tests.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Never let a bridge module near the real ~/.claude-watch, even lazily.
const credsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-revive-creds-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = credsDir;
after(() => {
  try { fs.rmSync(credsDir, { recursive: true, force: true }); } catch { /* ignore */ }
});

function lastSessionEvent(sseBuffer, sessionId) {
  for (let i = sseBuffer.length - 1; i >= 0; i--) {
    const entry = sseBuffer[i];
    if (entry.event !== "session") continue;
    const parsed = JSON.parse(entry.data);
    if (parsed.sessionId === sessionId) return parsed;
  }
  return null;
}

// Minimal PTY stand-in: killSession only calls .kill(); bindPtyProcess is not
// exercised here, so no stdio streams are needed.
function ptySlot(id, cwd, extra = {}) {
  return {
    id,
    agent: "claude",
    cwd,
    folderName: path.basename(cwd),
    ptyProcess: { kill() { /* noop */ } },
    state: "running",
    createdAt: Date.now(),
    ...extra,
  };
}

test("a hook on a watch-killed external slot revives it to running and broadcasts a running event", async () => {
  const { sessions, resolveHookSession, killSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  try {
    const id = resolveHookSession({ session_id: "cc-revive", cwd: "/tmp/revive-proj", tool_name: "Bash" });
    assert.equal(sessions.get(id).state, "running");
    assert.equal(sessions.get(id).hookCreated, true, "hook-created slot is external");

    // The watch "closes" the session: killSession marks it ended, but the real
    // process is still alive and NOT flagged authoritative.
    assert.equal(killSession(id), true);
    assert.equal(sessions.get(id).state, "ended");
    assert.notEqual(sessions.get(id).endedAuthoritatively, true, "a watch kill is not authoritative");

    const before = sseBuffer.length;
    // The still-alive session emits another hook: it must revive the slot.
    resolveHookSession({ session_id: "cc-revive", cwd: "/tmp/revive-proj", tool_name: "Bash" });
    const slot = sessions.get(id);
    assert.equal(slot.state, "running", "watch-killed external slot revives on the next hook");
    assert.equal(slot.endedAt, undefined, "endedAt is cleared on revive");

    assert.ok(sseBuffer.length > before, "revive pushed a new SSE event");
    const running = lastSessionEvent(sseBuffer, id);
    assert.equal(running?.state, "running", "the revive broadcast is a running session event");
    assert.equal(running?.external, true, "the revived running event carries external:true");
  } finally {
    sessions.clear();
  }
});

test("a SessionEnd-ended slot stays ended through a subsequent stray hook (no revive)", async () => {
  const { sessions, resolveHookSession, endHookSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  try {
    const id = resolveHookSession({ session_id: "cc-auth", cwd: "/tmp/auth-proj" });
    assert.equal(sessions.get(id).state, "running");

    // SessionEnd is the real process exiting — an AUTHORITATIVE end.
    assert.equal(endHookSession({ session_id: "cc-auth", cwd: "/tmp/auth-proj" }), id);
    const slot = sessions.get(id);
    assert.equal(slot.state, "ended");
    assert.equal(slot.endedAuthoritatively, true, "SessionEnd marks the ending authoritative");

    const before = sseBuffer.length;
    // A stray hook after a true end must NOT revive the slot or broadcast running.
    resolveHookSession({ session_id: "cc-auth", cwd: "/tmp/auth-proj", tool_name: "Bash" });
    assert.equal(sessions.get(id).state, "ended", "an authoritatively-ended slot stays ended");
    assert.equal(sseBuffer.length, before, "no revive broadcast for an authoritative end");
    assert.equal(lastSessionEvent(sseBuffer, id)?.state, "ended", "last session event is still ended");
  } finally {
    sessions.clear();
  }
});

test("a PTY-backed session killSession kills the PTY and stays ended; a bound hook never revives it", async () => {
  const { sessions, resolveHookSession, killSession } = await import("../sessions.js");

  try {
    let killed = false;
    const slot = ptySlot("pty-kill", "/tmp/pty-kill", { ptyProcess: { kill() { killed = true; } } });
    sessions.set("pty-kill", slot);

    // Bind a hook session_id to the PTY slot (first cwd-matched event).
    assert.equal(resolveHookSession({ session_id: "cc-pty", cwd: "/tmp/pty-kill" }), "pty-kill");
    assert.equal(sessions.get("pty-kill").hookSessionId, "cc-pty");

    assert.equal(killSession("pty-kill"), true);
    assert.equal(killed, true, "killSession actually kills the PTY");
    assert.equal(sessions.get("pty-kill").state, "ended");

    // The bound session_id fires again: a PTY slot is never hookCreated, so the
    // revive path must not touch it — true kill semantics are preserved.
    resolveHookSession({ session_id: "cc-pty", cwd: "/tmp/pty-kill", tool_name: "Bash" });
    assert.equal(sessions.get("pty-kill").state, "ended", "PTY slot stays ended; revive is external-only");
  } finally {
    sessions.clear();
  }
});

test("session payloads and the snapshot carry external:true for hook-created slots and omit it for PTY slots", async () => {
  const { sessions, resolveHookSession, killSession, getSessionsSnapshot } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  try {
    // Hook-created (external) slot: external:true on the running event + snapshot.
    const extId = resolveHookSession({ session_id: "cc-ext", cwd: "/tmp/ext-proj" });
    assert.equal(lastSessionEvent(sseBuffer, extId)?.external, true, "external running event tagged");
    const extSnap = getSessionsSnapshot().find((s) => s.id === extId);
    assert.equal(extSnap.external, true, "external slot snapshot tagged");

    // PTY-backed slot: the field is OMITTED entirely (not false).
    sessions.set("pty-ext", ptySlot("pty-ext", "/tmp/pty-ext"));
    const ptySnap = getSessionsSnapshot().find((s) => s.id === "pty-ext");
    assert.equal("external" in ptySnap, false, "PTY slot snapshot omits external");

    killSession("pty-ext");
    const ptyEnded = lastSessionEvent(sseBuffer, "pty-ext");
    assert.equal(ptyEnded?.state, "ended");
    assert.equal("external" in ptyEnded, false, "PTY slot ended event omits external");

    // The external slot's ended event (when it truly ends) still carries it.
    killSession(extId);
    const extEnded = lastSessionEvent(sseBuffer, extId);
    assert.equal(extEnded?.external, true, "external slot ended event still tagged");
  } finally {
    sessions.clear();
  }
});
