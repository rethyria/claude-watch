// Project-root attribution (issue #51), in-process: a hook-created slot used
// to pin cwd/folderName from the FIRST hook event it saw, so an event firing
// from a subdirectory mislabeled the whole session. Hook payloads carry
// `transcript_path`, whose parent directory name is Claude Code's sanitized
// project root: the bridge walks the observed cwd's ancestors, sanitizes each
// with the same char class ([^a-zA-Z0-9] → "-", verified against the bundled
// Claude Code CLI and against real ~/.claude/projects dir names on this
// machine, e.g. /home/deck/Development/claude-watch →
// -home-deck-Development-claude-watch), and binds the first ancestor whose
// sanitized form matches. Fallbacks: no transcript_path or no match keep the
// observed cwd, and a later event whose cwd is an ANCESTOR of the bound cwd
// rebinds upward. Re-labels broadcast the idempotent re-sent running session
// event so connected clients re-group the session.
//
// The transcript files named here deliberately do NOT exist: attribution is
// pure path logic, and the title refresh silently no-ops on a missing file.
//
// Env overrides must be set before any bridge module loads (config.js reads
// them once at evaluation), hence the dynamic imports inside the tests.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Never let a bridge module near the real ~/.claude-watch, even lazily.
const credsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-attr51-creds-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = credsDir;
after(() => {
  try { fs.rmSync(credsDir, { recursive: true, force: true }); } catch { /* ignore */ }
});

// Real-shaped fixture verified against this machine's ~/.claude/projects/:
// the project name itself contains a dash, so the sanitized form is ambiguous
// ("/" and "-" both map to "-") and can only be resolved by the ancestor walk.
const PROJECT_ROOT = "/home/deck/Development/claude-watch";
const PROJECT_DIR = "-home-deck-Development-claude-watch";
const transcriptIn = (projectDir) =>
  `/home/deck/.claude/projects/${projectDir}/11111111-2222-3333-4444-555555555555.jsonl`;

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

function lastSessionEvent(sseBuffer, sessionId) {
  for (let i = sseBuffer.length - 1; i >= 0; i--) {
    const entry = sseBuffer[i];
    if (entry.event !== "session") continue;
    const parsed = JSON.parse(entry.data);
    if (parsed.sessionId === sessionId) return parsed;
  }
  return null;
}

test("first hook from a subdirectory + transcript_path binds the verified project root, not the subdir", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  try {
    const sid = resolveHookSession({
      session_id: "attr51-subdir",
      cwd: `${PROJECT_ROOT}/wear`,
      transcript_path: transcriptIn(PROJECT_DIR),
      tool_name: "Bash",
    });

    const slot = sessions.get(sid);
    assert.equal(slot.cwd, PROJECT_ROOT, "slot binds the transcript-verified project root");
    assert.equal(slot.folderName, "claude-watch", "folderName is the root's basename, not the subdir's");
    assert.equal(slot.projectRootVerified, true);

    // The initial running event already carries the root, so clients group
    // the session correctly from the start.
    const running = lastSessionEvent(sseBuffer, sid);
    assert.equal(running?.state, "running");
    assert.equal(running?.cwd, PROJECT_ROOT);
    assert.equal(running?.folderName, "claude-watch");
  } finally {
    sessions.clear();
  }
});

test("sanitization matches Claude Code's char class: dots, underscores, and spaces all read back as dashes", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    // /opt/my_app.v2 sanitizes to -opt-my-app-v2 under [^a-zA-Z0-9] → "-".
    const sid = resolveHookSession({
      session_id: "attr51-charclass",
      cwd: "/opt/my_app.v2/sub dir/deep",
      transcript_path: transcriptIn("-opt-my-app-v2"),
    });
    assert.equal(sessions.get(sid).cwd, "/opt/my_app.v2");
    assert.equal(sessions.get(sid).folderName, "my_app.v2", "folderName keeps the real (unsanitized) name");

    // The mapping is lossy: /a/b/c and /a/b-c both sanitize to -a-b-c. The
    // walk starts at the observed cwd, so the deepest matching ancestor wins.
    const ambiguous = resolveHookSession({
      session_id: "attr51-ambiguous",
      cwd: "/a/b/c/sub",
      transcript_path: transcriptIn("-a-b-c"),
    });
    assert.equal(sessions.get(ambiguous).cwd, "/a/b/c", "deepest ancestor whose sanitized form matches wins");
  } finally {
    sessions.clear();
  }
});

test("no ancestor matches the transcript dir → observed cwd is kept (current behavior), ancestor rebind still applies", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    const sid = resolveHookSession({
      session_id: "attr51-nomatch",
      cwd: "/tmp/attr51-elsewhere/sub",
      transcript_path: transcriptIn("-home-somebody-unrelated-project"),
    });
    const slot = sessions.get(sid);
    assert.equal(slot.cwd, "/tmp/attr51-elsewhere/sub", "verification failure falls back to the observed cwd");
    assert.notEqual(slot.projectRootVerified, true);

    // The ancestor-cwd fallback heuristic still corrects it upward later.
    resolveHookSession({
      session_id: "attr51-nomatch",
      cwd: "/tmp/attr51-elsewhere",
      transcript_path: transcriptIn("-home-somebody-unrelated-project"),
    });
    assert.equal(slot.cwd, "/tmp/attr51-elsewhere");
    assert.equal(slot.folderName, "attr51-elsewhere");
  } finally {
    sessions.clear();
  }
});

test("no transcript_path → current behavior; a later ancestor-cwd event rebinds upward and re-broadcasts", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  try {
    const sid = resolveHookSession({ session_id: "attr51-ancestor", cwd: "/tmp/attr51/proj/deep/sub" });
    const slot = sessions.get(sid);
    assert.equal(slot.cwd, "/tmp/attr51/proj/deep/sub", "without a transcript the observed cwd binds as before");

    // Ancestor cwd: rebind upward, update folderName, and broadcast the
    // idempotent running event so clients re-group.
    resolveHookSession({ session_id: "attr51-ancestor", cwd: "/tmp/attr51/proj" });
    assert.equal(slot.cwd, "/tmp/attr51/proj");
    assert.equal(slot.folderName, "proj");
    const announced = lastSessionEvent(sseBuffer, sid);
    assert.equal(announced?.state, "running");
    assert.equal(announced?.cwd, "/tmp/attr51/proj");
    assert.equal(announced?.folderName, "proj");

    // Non-ancestors never rebind: a sibling, a descendant, and a
    // string-prefix-but-not-path-ancestor all leave the binding alone.
    const bufferLen = sseBuffer.length;
    resolveHookSession({ session_id: "attr51-ancestor", cwd: "/tmp/attr51/unrelated" });
    resolveHookSession({ session_id: "attr51-ancestor", cwd: "/tmp/attr51/proj/deep" });
    resolveHookSession({ session_id: "attr51-ancestor", cwd: "/tmp/attr51/pro" });
    assert.equal(slot.cwd, "/tmp/attr51/proj", "sessions never migrate to an unrelated root");
    assert.equal(sseBuffer.length, bufferLen, "no-op events broadcast nothing");
  } finally {
    sessions.clear();
  }
});

test("a transcript-verified root is final: later ancestor cwds do not drag it further up", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    const sid = resolveHookSession({
      session_id: "attr51-final",
      cwd: `${PROJECT_ROOT}/skill/bridge`,
      transcript_path: transcriptIn(PROJECT_DIR),
    });
    const slot = sessions.get(sid);
    assert.equal(slot.cwd, PROJECT_ROOT);

    resolveHookSession({ session_id: "attr51-final", cwd: "/home/deck/Development" });
    resolveHookSession({ session_id: "attr51-final", cwd: "/home/deck", transcript_path: transcriptIn(PROJECT_DIR) });
    assert.equal(slot.cwd, PROJECT_ROOT, "verified root outranks the ancestor heuristic");
  } finally {
    sessions.clear();
  }
});

test("a later event carrying transcript_path re-labels a slot created without one and re-broadcasts", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  try {
    // First event (e.g. right after a bridge restart) has no transcript_path.
    const sid = resolveHookSession({ session_id: "attr51-late", cwd: `${PROJECT_ROOT}/wear` });
    const slot = sessions.get(sid);
    assert.equal(slot.cwd, `${PROJECT_ROOT}/wear`);

    // A later event carries it: verify, rebind, broadcast.
    resolveHookSession({
      session_id: "attr51-late",
      cwd: `${PROJECT_ROOT}/wear`,
      transcript_path: transcriptIn(PROJECT_DIR),
    });
    assert.equal(slot.cwd, PROJECT_ROOT);
    assert.equal(slot.folderName, "claude-watch");
    assert.equal(slot.projectRootVerified, true);
    const announced = lastSessionEvent(sseBuffer, sid);
    assert.equal(announced?.state, "running");
    assert.equal(announced?.folderName, "claude-watch");

    // The failed-attempt cache is keyed on (cwd, transcript path): a first
    // transcript that matches nothing must not stop a later, different one
    // from verifying.
    const other = resolveHookSession({
      session_id: "attr51-late-2",
      cwd: `${PROJECT_ROOT}/wear`,
      transcript_path: transcriptIn("-some-other-project"),
    });
    const otherSlot = sessions.get(other);
    assert.equal(otherSlot.cwd, `${PROJECT_ROOT}/wear`);
    resolveHookSession({
      session_id: "attr51-late-2",
      cwd: `${PROJECT_ROOT}/wear`,
      transcript_path: transcriptIn(PROJECT_DIR),
    });
    assert.equal(otherSlot.cwd, PROJECT_ROOT);
  } finally {
    sessions.clear();
  }
});

test("PTY-backed slots keep their user-chosen spawn cwd (attach/respawn depends on it)", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    // The bridge spawned this PTY in the subdir on purpose.
    sessions.set("pty-51", ptySlot("pty-51", `${PROJECT_ROOT}/wear`));
    assert.equal(
      resolveHookSession({ session_id: "attr51-pty", cwd: `${PROJECT_ROOT}/wear` }),
      "pty-51",
    );

    resolveHookSession({
      session_id: "attr51-pty",
      cwd: `${PROJECT_ROOT}/wear`,
      transcript_path: transcriptIn(PROJECT_DIR),
    });
    resolveHookSession({ session_id: "attr51-pty", cwd: PROJECT_ROOT });
    assert.equal(sessions.get("pty-51").cwd, `${PROJECT_ROOT}/wear`, "PTY slot cwd is never rebound");
  } finally {
    sessions.clear();
  }
});

test("Codex legacy sessions (no session_id, no transcript_path) keep exact-cwd semantics", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    const first = resolveHookSession({ cwd: "/tmp/attr51-codex/repo/sub", source: "codex" });
    const again = resolveHookSession({ cwd: "/tmp/attr51-codex/repo/sub", source: "codex" });
    assert.equal(first, again, "same-cwd legacy events still share one session");

    // Legacy routing is by EXACT cwd: an ancestor cwd is a different session,
    // and the original slot is never rebound (rebinding would break routing).
    const ancestor = resolveHookSession({ cwd: "/tmp/attr51-codex/repo", source: "codex" });
    assert.notEqual(ancestor, first);
    assert.equal(sessions.get(first).cwd, "/tmp/attr51-codex/repo/sub");
    assert.equal(sessions.get(first).folderName, "sub");
  } finally {
    sessions.clear();
  }
});
