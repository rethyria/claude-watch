// Issue #52, in-process: harden the project-root attribution heuristics from
// issue #51. Four confirmed-minor, label/grouping-only vectors, one focused
// test each:
//   1. a hook cwd OUTSIDE the true project root must never be sanitize-collision
//      "verified" onto a sibling it does not live under;
//   2/3. the unverified ancestor-cwd ratchet is floored at the shallowest cwd
//      ever observed AND requires two consistent observations, so a single stray
//      cwd=/home or / can't drag the root up (and a verified root never ratchets);
//   4. legacy / codex-watch wrapper slots (no session_id, exact-cwd routing) are
//      never rebound.
//
// Env overrides must be set before any bridge module loads (config.js reads them
// once at evaluation), hence the dynamic imports inside the tests.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Never let a bridge module near the real ~/.claude-watch, even lazily.
const credsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-attr52-creds-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = credsDir;
after(() => {
  try { fs.rmSync(credsDir, { recursive: true, force: true }); } catch { /* ignore */ }
});

// Real-shaped fixture (see project-attribution-unit.test.js): the project name
// itself contains a dash, so its sanitized form is ambiguous.
const PROJECT_ROOT = "/home/deck/Development/claude-watch";
const PROJECT_DIR = "-home-deck-Development-claude-watch";
const transcriptIn = (projectDir) =>
  `/home/deck/.claude/projects/${projectDir}/11111111-2222-3333-4444-555555555555.jsonl`;

// Vector 1 -------------------------------------------------------------------
test("a hook cwd outside the true root is not sanitize-collision verified onto a sibling", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    // The transcript dir `-a52-b-c` sanitize-collides with BOTH /a52/b/c and
    // /a52/b-c (the lossy "/" == "-" mapping). The hook fires from inside the
    // b-c tree, which is NOT under /a52/b/c. Verification must only ever yield a
    // directory the observed cwd actually lives under, so it binds /a52/b-c —
    // never the sibling /a52/b/c the cwd never touched.
    const sid = resolveHookSession({
      session_id: "vec1-collision",
      cwd: "/a52/b-c/work",
      transcript_path: transcriptIn("-a52-b-c"),
    });
    const slot = sessions.get(sid);
    assert.equal(slot.cwd, "/a52/b-c", "binds the ancestor the cwd is inside");
    assert.notEqual(slot.cwd, "/a52/b/c", "never a sanitize-colliding sibling the cwd is outside of");

    // A cwd that is a genuine SIBLING of the true root (outside it entirely)
    // finds no matching ancestor and falls back to the observed cwd — it is
    // never falsely verified up to the root.
    const outside = resolveHookSession({
      session_id: "vec1-outside",
      cwd: `${PROJECT_ROOT}-other/sub`,
      transcript_path: transcriptIn(PROJECT_DIR),
    });
    const outsideSlot = sessions.get(outside);
    assert.equal(outsideSlot.cwd, `${PROJECT_ROOT}-other/sub`, "cwd outside the root is not verified onto it");
    assert.notEqual(outsideSlot.projectRootVerified, true);
  } finally {
    sessions.clear();
  }
});

// Vector 2/3 -----------------------------------------------------------------
test("a single unverified ancestor observation does not rebind", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    const sid = resolveHookSession({ session_id: "vec3-single", cwd: "/tmp/vec3/proj/deep/sub" });
    const slot = sessions.get(sid);
    assert.equal(slot.cwd, "/tmp/vec3/proj/deep/sub");

    // One ancestor observation is not enough: the ratchet waits for a second
    // consistent sighting before it commits an unverified rebind.
    resolveHookSession({ session_id: "vec3-single", cwd: "/tmp/vec3/proj" });
    assert.equal(slot.cwd, "/tmp/vec3/proj/deep/sub", "one observation leaves the root put");

    // A second consistent observation of the SAME cwd finally commits it.
    resolveHookSession({ session_id: "vec3-single", cwd: "/tmp/vec3/proj" });
    assert.equal(slot.cwd, "/tmp/vec3/proj", "two consistent observations rebind");
  } finally {
    sessions.clear();
  }
});

test("a single /home hook does not ratchet the root up, and a verified root never ratchets at all", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    // Unverified slot, ratcheted up to its real project root by two consistent
    // observations.
    const sid = resolveHookSession({ session_id: "vec2-unverified", cwd: "/tmp/vec2/proj/deep" });
    const slot = sessions.get(sid);
    resolveHookSession({ session_id: "vec2-unverified", cwd: "/tmp/vec2/proj" });
    resolveHookSession({ session_id: "vec2-unverified", cwd: "/tmp/vec2/proj" });
    assert.equal(slot.cwd, "/tmp/vec2/proj", "two observations establish the project root");

    // A lone stray hook from /home must NOT drag the root all the way up.
    resolveHookSession({ session_id: "vec2-unverified", cwd: "/home" });
    assert.equal(slot.cwd, "/tmp/vec2/proj", "a single /home hook does not ratchet the root up");
    // Nor a lone hook from the filesystem root.
    resolveHookSession({ session_id: "vec2-unverified", cwd: "/" });
    assert.equal(slot.cwd, "/tmp/vec2/proj", "a single / hook does not ratchet the root up");

    // A transcript-VERIFIED slot skips the ancestor heuristic entirely, so no
    // number of shallow hooks moves it.
    const vid = resolveHookSession({
      session_id: "vec2-verified",
      cwd: `${PROJECT_ROOT}/skill/bridge`,
      transcript_path: transcriptIn(PROJECT_DIR),
    });
    const vslot = sessions.get(vid);
    assert.equal(vslot.cwd, PROJECT_ROOT);
    resolveHookSession({ session_id: "vec2-verified", cwd: "/home" });
    resolveHookSession({ session_id: "vec2-verified", cwd: "/home" });
    resolveHookSession({ session_id: "vec2-verified", cwd: "/" });
    assert.equal(vslot.cwd, PROJECT_ROOT, "a verified root never ratchets, even on repeated /home or / hooks");
  } finally {
    sessions.clear();
  }
});

// Vector 4 -------------------------------------------------------------------
test("legacy / codex-watch wrapper slots (no session_id) are never rebound", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    // Legacy no-session_id codex slot, routed by EXACT cwd — created hookCreated
    // (for the eviction cap) but never fed through updateProjectAttribution.
    const first = resolveHookSession({ cwd: "/tmp/vec4/repo/sub", source: "codex" });
    assert.equal(sessions.get(first).hookCreated, true);

    // Repeated exact-cwd events reuse the one slot and never rebind it, even
    // though an ancestor-cwd rebind would fire for a session_id-keyed slot.
    resolveHookSession({ cwd: "/tmp/vec4/repo/sub", source: "codex" });
    resolveHookSession({ cwd: "/tmp/vec4/repo/sub", source: "codex" });
    assert.equal(sessions.get(first).cwd, "/tmp/vec4/repo/sub", "legacy/codex slot cwd never rebinds");
    assert.equal(sessions.get(first).folderName, "sub");

    // An ancestor-cwd legacy event is a DIFFERENT session (exact-cwd routing)
    // and still leaves the original slot's cwd untouched.
    const ancestor = resolveHookSession({ cwd: "/tmp/vec4/repo", source: "codex" });
    assert.notEqual(ancestor, first);
    assert.equal(sessions.get(first).cwd, "/tmp/vec4/repo/sub");
  } finally {
    sessions.clear();
  }
});
