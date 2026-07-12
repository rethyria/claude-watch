// Session titles (issue #50), in-process: the bridge derives a session's
// title from the Claude Code transcript the hook payloads point at via
// `transcript_path` — the LAST `{"type":"ai-title"}` record wins, falling
// back to the first real user prompt (truncated) — and emits it as an
// additive optional `title` field on session SSE events and snapshots.
// Derivation is cached on (path, mtime, size) and refreshed only at
// opportunistic moments; unreadable/malformed/missing transcripts silently
// yield no title.
//
// Env overrides must be set before any bridge module loads (config.js reads
// them once at evaluation), hence the dynamic imports inside the tests.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Never let a bridge module near the real ~/.claude-watch, even lazily.
const credsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-title-creds-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = credsDir;

const transcriptDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-title-transcripts-"));
after(() => {
  try { fs.rmSync(credsDir, { recursive: true, force: true }); } catch { /* ignore */ }
  try { fs.rmSync(transcriptDir, { recursive: true, force: true }); } catch { /* ignore */ }
});

let transcriptCounter = 0;
function writeTranscript(lines) {
  const file = path.join(transcriptDir, `session-${transcriptCounter++}.jsonl`);
  fs.writeFileSync(file, lines.map((l) => (typeof l === "string" ? l : JSON.stringify(l))).join("\n") + "\n");
  return file;
}

const aiTitle = (title) => ({ type: "ai-title", aiTitle: title, sessionId: "cc-any" });
const userPrompt = (text) => ({ type: "user", message: { role: "user", content: [{ type: "text", text }] } });

function lastSessionEvent(sseBuffer, sessionId) {
  for (let i = sseBuffer.length - 1; i >= 0; i--) {
    const entry = sseBuffer[i];
    if (entry.event !== "session") continue;
    const parsed = JSON.parse(entry.data);
    if (parsed.sessionId === sessionId) return parsed;
  }
  return null;
}

test("ai-title beats the first-prompt fallback; the LAST ai-title wins; title rides session events and snapshots", async () => {
  const { sessions, resolveHookSession, getSessionsSnapshot } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  try {
    const transcript = writeTranscript([
      userPrompt("please fix the flaky test in ci"),
      aiTitle("Fix flaky CI test"),
      { type: "assistant", message: { role: "assistant", content: [{ type: "text", text: "on it" }] } },
      aiTitle("Fix flaky CI test and speed up the suite"),
    ]);

    const sid = resolveHookSession({
      session_id: "cc-title-1",
      cwd: "/tmp/title-proj",
      transcript_path: transcript,
      tool_name: "Bash",
    });

    assert.equal(sessions.get(sid).title, "Fix flaky CI test and speed up the suite", "last ai-title record wins");

    // The initial running event already carries the title...
    const running = lastSessionEvent(sseBuffer, sid);
    assert.equal(running?.state, "running");
    assert.equal(running?.title, "Fix flaky CI test and speed up the suite");

    // ...and so does the /status- and /pair-shaped snapshot.
    const snapshot = getSessionsSnapshot().find((s) => s.id === sid);
    assert.equal(snapshot.title, "Fix flaky CI test and speed up the suite");
  } finally {
    sessions.clear();
  }
});

test("without an ai-title the first real user prompt becomes the title, truncated to ~60 chars", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    const longPrompt = "please refactor the authentication flow so that expired tokens are refreshed transparently";
    const transcript = writeTranscript([
      // Meta and synthetic records must be skipped, not used as the title.
      { type: "user", isMeta: true, message: { role: "user", content: "Caveat: the messages below were generated..." } },
      { type: "user", message: { role: "user", content: "<command-name>/clear</command-name>" } },
      userPrompt(longPrompt),
      userPrompt("a later prompt that must not win"),
    ]);

    const sid = resolveHookSession({ session_id: "cc-title-2", cwd: "/tmp/title-proj-2", transcript_path: transcript });
    const title = sessions.get(sid).title;
    assert.ok(title.length <= 60, `fallback title is truncated (got ${title.length} chars)`);
    assert.ok(longPrompt.startsWith(title.slice(0, -1)), "title is a prefix of the first real prompt");
    assert.ok(title.endsWith("…"), "truncation is marked with an ellipsis");
  } finally {
    sessions.clear();
  }
});

test("missing, unreadable, or malformed transcripts yield no title and never throw", async () => {
  const { sessions, resolveHookSession, refreshSessionTitle } = await import("../sessions.js");

  try {
    // Missing file.
    const sidMissing = resolveHookSession({
      session_id: "cc-title-3",
      cwd: "/tmp/title-proj-3",
      transcript_path: path.join(transcriptDir, "does-not-exist.jsonl"),
    });
    assert.equal(sessions.get(sidMissing).title, undefined, "missing transcript: no title");

    // Malformed lines are skipped; a valid ai-title among garbage still wins.
    const garbled = writeTranscript([
      "{ not json at all",
      "42",
      '"just a string"',
      aiTitle("Survives the garbage"),
      "{\"type\":\"ai-title\",\"aiTitle\":",
    ]);
    const sidGarbled = resolveHookSession({ session_id: "cc-title-4", cwd: "/tmp/title-proj-4", transcript_path: garbled });
    assert.equal(sessions.get(sidGarbled).title, "Survives the garbage");

    // Entirely malformed: silently no title.
    const junk = writeTranscript(["%%%%", "{{{{"]);
    const sidJunk = resolveHookSession({ session_id: "cc-title-5", cwd: "/tmp/title-proj-5", transcript_path: junk });
    assert.equal(sessions.get(sidJunk).title, undefined);

    // Non-string / empty transcript_path values are ignored outright.
    assert.equal(refreshSessionTitle(sessions.get(sidJunk), null), false);
    assert.equal(refreshSessionTitle(sessions.get(sidJunk), 42), false);
    assert.equal(refreshSessionTitle(null, "/anywhere"), false);
  } finally {
    sessions.clear();
  }
});

test("the title refreshes when the transcript's ai-title changes, and the cache skips unchanged transcripts", async () => {
  const { sessions, resolveHookSession, refreshSessionTitle, refreshHookSessionTitle } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  try {
    const transcript = writeTranscript([userPrompt("start here"), aiTitle("First title")]);
    const sid = resolveHookSession({ session_id: "cc-title-6", cwd: "/tmp/title-proj-6", transcript_path: transcript });
    const slot = sessions.get(sid);
    assert.equal(slot.title, "First title");

    // Unchanged transcript: the (path, mtime, size) cache short-circuits.
    assert.equal(refreshSessionTitle(slot, transcript), false, "cache hit: no change reported");

    // The title evolves: Claude Code appends a new ai-title record.
    fs.appendFileSync(transcript, JSON.stringify(aiTitle("Second, better title")) + "\n");
    // Stop is the opportunistic refresh point; a change is broadcast as an
    // idempotent running event carrying the new title.
    refreshHookSessionTitle(sid, { session_id: "cc-title-6", transcript_path: transcript });
    assert.equal(slot.title, "Second, better title");
    const announced = lastSessionEvent(sseBuffer, sid);
    assert.equal(announced?.state, "running");
    assert.equal(announced?.title, "Second, better title");

    // A transcript that stops yielding a title never clears the known one.
    fs.writeFileSync(transcript, "");
    assert.equal(refreshSessionTitle(slot, transcript), false);
    assert.equal(slot.title, "Second, better title", "stale title beats flapping back to no label");
  } finally {
    sessions.clear();
  }
});

test("huge transcripts are scanned head+tail, never fully read: first prompt and the latest ai-title both survive", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    // > 2 * 256 KiB of filler between the head (first prompt) and the tail
    // (latest ai-title re-emission).
    const filler = JSON.stringify({
      type: "assistant",
      message: { role: "assistant", content: [{ type: "text", text: "x".repeat(1024) }] },
    });
    const lines = [JSON.stringify(userPrompt("the very first prompt"))];
    for (let i = 0; i < 700; i++) lines.push(filler);
    lines.push(JSON.stringify(aiTitle("Title from the tail")));
    const transcript = writeTranscript(lines);
    assert.ok(fs.statSync(transcript).size > 2 * 256 * 1024, "fixture is actually huge");

    const sid = resolveHookSession({ session_id: "cc-title-7", cwd: "/tmp/title-proj-7", transcript_path: transcript });
    assert.equal(sessions.get(sid).title, "Title from the tail");

    // Same shape but with the ai-title records only in the skipped middle:
    // the head's first prompt is the honest fallback.
    const middleLines = [JSON.stringify(userPrompt("fallback prompt for the huge transcript"))];
    for (let i = 0; i < 350; i++) middleLines.push(filler);
    middleLines.push(JSON.stringify(aiTitle("Buried in the middle")));
    for (let i = 0; i < 350; i++) middleLines.push(filler);
    const middle = writeTranscript(middleLines);
    assert.ok(fs.statSync(middle).size > 2 * 256 * 1024);
    const sidMiddle = resolveHookSession({ session_id: "cc-title-8", cwd: "/tmp/title-proj-8", transcript_path: middle });
    assert.equal(sessions.get(sidMiddle).title, "fallback prompt for the huge transcript");
  } finally {
    sessions.clear();
  }
});
