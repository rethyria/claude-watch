// Issue #54, in-process: additive git metadata (branch / worktree / repoRoot)
// on session payloads, derived at the slot's bound root via FILE READS ONLY.
//   * <root>/.git DIRECTORY  → main checkout: branch from .git/HEAD, no
//     worktree/repoRoot claim;
//   * <root>/.git FILE       → `gitdir:` pointer; a linked worktree ONLY when
//     the target matches …/.git/worktrees/<name> exactly (then repoRoot is the
//     main repo three levels up) — any other pointer yields at most branch,
//     never a guessed repoRoot;
//   * detached HEAD (bare sha) → 7-char short sha; non-git root → no fields;
//   * a HEAD change is picked up at the opportunistic refresh points and
//     broadcast as the idempotent running `session` event.
//
// Env overrides must be set before any bridge module loads (config.js reads
// them once at evaluation), hence the dynamic imports inside the tests.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Never let a bridge module near the real ~/.claude-watch, even lazily.
const credsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-gitmeta-creds-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = credsDir;

const fixturesRoot = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-gitmeta-fixtures-"));
after(() => {
  for (const dir of [credsDir, fixturesRoot]) {
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* ignore */ }
  }
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

// A fake MAIN checkout: <root>/.git/ directory with a HEAD file.
function makeMainCheckout(name, headContent) {
  const root = path.join(fixturesRoot, name);
  fs.mkdirSync(path.join(root, ".git"), { recursive: true });
  fs.writeFileSync(path.join(root, ".git", "HEAD"), headContent);
  return root;
}

// A fake LINKED WORKTREE: a main repo with .git/worktrees/<wt>/HEAD, plus a
// worktree root whose .git is a FILE pointing at that gitdir.
function makeWorktree(mainName, wtName, headContent) {
  const mainRoot = path.join(fixturesRoot, mainName);
  const gitdir = path.join(mainRoot, ".git", "worktrees", wtName);
  fs.mkdirSync(gitdir, { recursive: true });
  fs.writeFileSync(path.join(gitdir, "HEAD"), headContent);
  const wtRoot = path.join(fixturesRoot, `${mainName}-${wtName}-root`);
  fs.mkdirSync(wtRoot, { recursive: true });
  fs.writeFileSync(path.join(wtRoot, ".git"), `gitdir: ${gitdir}\n`);
  return { mainRoot, wtRoot };
}

test("a main-checkout session carries branch and no worktree/repoRoot claim", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const root = makeMainCheckout("plain", "ref: refs/heads/main\n");
  const id = resolveHookSession({ session_id: "cc-git-main", cwd: root, tool_name: "Bash" });
  try {
    const slot = sessions.get(id);
    assert.equal(slot.branch, "main");
    assert.equal(slot.worktree, undefined);
    assert.equal(slot.repoRoot, undefined);
    // The INITIAL running event already carries the branch (derived at
    // creation, before the broadcast).
    const event = lastSessionEvent(sseBuffer, id);
    assert.equal(event.branch, "main");
    assert.ok(!Object.hasOwn(event, "worktree"), "no worktree key for a main checkout");
    assert.ok(!Object.hasOwn(event, "repoRoot"), "no repoRoot key for a main checkout");
  } finally {
    sessions.delete(id);
  }
});

test("a linked-worktree session carries branch + worktree + the main repoRoot", async () => {
  const { sessions, resolveHookSession, getSessionsSnapshot } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const { mainRoot, wtRoot } = makeWorktree("mainrepo", "wt1", "ref: refs/heads/feature/x\n");
  const id = resolveHookSession({ session_id: "cc-git-wt", cwd: wtRoot, tool_name: "Bash" });
  try {
    const slot = sessions.get(id);
    assert.equal(slot.branch, "feature/x");
    assert.equal(slot.worktree, true);
    assert.equal(slot.repoRoot, mainRoot);
    const event = lastSessionEvent(sseBuffer, id);
    assert.equal(event.branch, "feature/x");
    assert.equal(event.worktree, true);
    assert.equal(event.repoRoot, mainRoot);
    // The snapshot mirrors the payload fold.
    const snap = getSessionsSnapshot().find((s) => s.id === id);
    assert.equal(snap.branch, "feature/x");
    assert.equal(snap.worktree, true);
    assert.equal(snap.repoRoot, mainRoot);
  } finally {
    sessions.delete(id);
  }
});

test("a detached HEAD yields the 7-char short sha; a non-git root yields nothing", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  const sha = "0123456789abcdef0123456789abcdef01234567";
  const detachedRoot = makeMainCheckout("detached", `${sha}\n`);
  const detachedId = resolveHookSession({ session_id: "cc-git-detached", cwd: detachedRoot, tool_name: "Bash" });

  const bareRoot = path.join(fixturesRoot, "not-a-repo");
  fs.mkdirSync(bareRoot, { recursive: true });
  const bareId = resolveHookSession({ session_id: "cc-git-none", cwd: bareRoot, tool_name: "Bash" });

  try {
    assert.equal(sessions.get(detachedId).branch, sha.slice(0, 7));
    const bare = sessions.get(bareId);
    assert.equal(bare.branch, undefined);
    assert.equal(bare.worktree, undefined);
    assert.equal(bare.repoRoot, undefined);
  } finally {
    sessions.delete(detachedId);
    sessions.delete(bareId);
  }
});

test("a HEAD change is picked up at the Stop refresh point and broadcast; unchanged HEAD is a no-op", async () => {
  const { sessions, resolveHookSession, refreshHookSessionTitle, refreshGitMetadata } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  const root = makeMainCheckout("switching", "ref: refs/heads/main\n");
  const id = resolveHookSession({ session_id: "cc-git-switch", cwd: root, tool_name: "Bash" });
  try {
    assert.equal(sessions.get(id).branch, "main");

    // Same-mtime-second rewrites are caught by the SIZE leg of the stat gate
    // ("other" vs "main" differ in length), so no sleep is needed here.
    fs.writeFileSync(path.join(root, ".git", "HEAD"), "ref: refs/heads/other\n");
    const before = sseBuffer.length;
    refreshHookSessionTitle(id, {}); // the Stop hook's opportunistic refresh
    assert.equal(sessions.get(id).branch, "other");
    const event = lastSessionEvent(sseBuffer.slice(before), id);
    assert.ok(event, "the branch change was broadcast");
    assert.equal(event.state, "running");
    assert.equal(event.branch, "other");

    // Unchanged HEAD: the stat gate reports no change.
    assert.equal(refreshGitMetadata(sessions.get(id)), false);
  } finally {
    sessions.delete(id);
  }
});

test("a malformed .git file or a non-worktree gitdir never yields a wrong repoRoot", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  // No `gitdir:` prefix at all → not a checkout, nothing derived.
  const junkRoot = path.join(fixturesRoot, "junk-pointer");
  fs.mkdirSync(junkRoot, { recursive: true });
  fs.writeFileSync(path.join(junkRoot, ".git"), "this is not a gitdir pointer\n");
  const junkId = resolveHookSession({ session_id: "cc-git-junk", cwd: junkRoot, tool_name: "Bash" });

  // A submodule-style pointer (…/.git/modules/<sub>) has a readable HEAD but
  // is NOT a linked worktree: branch may derive, repoRoot must not.
  const host = path.join(fixturesRoot, "host-repo");
  const moduleGitdir = path.join(host, ".git", "modules", "sub");
  fs.mkdirSync(moduleGitdir, { recursive: true });
  fs.writeFileSync(path.join(moduleGitdir, "HEAD"), "ref: refs/heads/sub-branch\n");
  const subRoot = path.join(fixturesRoot, "sub-root");
  fs.mkdirSync(subRoot, { recursive: true });
  fs.writeFileSync(path.join(subRoot, ".git"), `gitdir: ${moduleGitdir}\n`);
  const subId = resolveHookSession({ session_id: "cc-git-sub", cwd: subRoot, tool_name: "Bash" });

  try {
    const junk = sessions.get(junkId);
    assert.equal(junk.branch, undefined);
    assert.equal(junk.repoRoot, undefined);
    const sub = sessions.get(subId);
    assert.equal(sub.branch, "sub-branch");
    assert.equal(sub.worktree, undefined, "a submodule pointer is not a worktree");
    assert.equal(sub.repoRoot, undefined, "never guess a repoRoot outside …/.git/worktrees/<name>");
  } finally {
    sessions.delete(junkId);
    sessions.delete(subId);
  }
});
