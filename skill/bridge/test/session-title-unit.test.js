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
import { execFileSync } from "node:child_process";
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

test("a partial head+tail scan never reverts a known ai-title to an older head one or a prompt fallback", async () => {
  const { sessions, resolveHookSession, refreshSessionTitle } = await import("../sessions.js");

  try {
    const filler = JSON.stringify({
      type: "assistant",
      message: { role: "assistant", content: [{ type: "text", text: "x".repeat(1024) }] },
    });

    // Small enough for a full scan: title evolves from "Old early title"
    // (which will end up inside the immutable 256 KiB head chunk) to
    // "New pivot title".
    const lines = [JSON.stringify(userPrompt("the first prompt")), JSON.stringify(aiTitle("Old early title"))];
    for (let i = 0; i < 300; i++) lines.push(filler);
    lines.push(JSON.stringify(aiTitle("New pivot title")));
    const transcript = writeTranscript(lines);
    assert.ok(fs.statSync(transcript).size <= 2 * 256 * 1024, "starts small enough for a full scan");

    const sid = resolveHookSession({ session_id: "cc-title-9", cwd: "/tmp/title-proj-9", transcript_path: transcript });
    const slot = sessions.get(sid);
    assert.equal(slot.title, "New pivot title");

    // The session runs long: enough output lands AFTER the pivot to push the
    // transcript past the full-scan threshold, leaving "New pivot title" in
    // the skipped middle and only "Old early title" inside the head chunk.
    fs.appendFileSync(transcript, Array(450).fill(filler).join("\n") + "\n");
    assert.ok(fs.statSync(transcript).size > 2 * 256 * 1024, "now scanned head+tail");

    assert.equal(refreshSessionTitle(slot, transcript), false, "no change announced");
    assert.equal(slot.title, "New pivot title", "the known ai-title is not reverted to the older head one");

    // Idempotent: the cache absorbs the unchanged transcript, and even a
    // forced re-derivation (mtime bump) must not regress.
    fs.appendFileSync(transcript, filler + "\n");
    assert.equal(refreshSessionTitle(slot, transcript), false);
    assert.equal(slot.title, "New pivot title");

    // A genuinely newer ai-title lands in the tail: that one DOES win.
    fs.appendFileSync(transcript, JSON.stringify(aiTitle("Even newer tail title")) + "\n");
    assert.equal(refreshSessionTitle(slot, transcript), true);
    assert.equal(slot.title, "Even newer tail title");
  } finally {
    sessions.clear();
  }
});

test("non-regular transcript files (FIFO, device nodes, directories) yield no title and never block", async () => {
  const { sessions, refreshSessionTitle } = await import("../sessions.js");

  try {
    const slot = { id: "unit-slot", state: "running" };

    // A FIFO with no writer would block openSync/readFileSync forever; a
    // character device like /dev/zero stats as size 0 but reads unboundedly.
    // All must be rejected by the isFile() gate before any read happens.
    const fifo = path.join(transcriptDir, "transcript.fifo");
    execFileSync("mkfifo", [fifo]);
    assert.equal(refreshSessionTitle(slot, fifo), false, "FIFO: rejected without blocking");

    for (const special of ["/dev/zero", "/dev/null", transcriptDir]) {
      if (special.startsWith("/dev/") && !fs.existsSync(special)) continue;
      assert.equal(refreshSessionTitle(slot, special), false, `${special}: no title, no error`);
    }
    assert.equal(slot.title, undefined, "no title was ever derived");
  } finally {
    sessions.clear();
  }
});

test("a slot that existed before its first hook is titled MID-turn, not at the turn's end", async () => {
  const { sessions, registerAcpSession, resolveHookSession } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");

  try {
    // An ACP session is registered when Zed creates it — before a first
    // message, so before any transcript exists.
    registerAcpSession({ sessionId: "acp-title-1", sdkSessionId: "acp-title-1", cwd: "/tmp/acp-title-proj" });
    assert.equal(sessions.get("acp-title-1").title, undefined, "no transcript yet: no title");

    // The user sends the first message; Claude Code writes the prompt and,
    // seconds later, the ai-title — all before the turn's first tool call.
    const transcript = writeTranscript([userPrompt("make the nav dots curve"), aiTitle("Curve navigation dots")]);

    // The turn's FIRST hook must surface it: this slot is already bound, so it
    // takes the fast path that used to skip the transcript entirely and leave
    // the watch showing the bare folder name until Stop.
    resolveHookSession({ session_id: "acp-title-1", cwd: "/tmp/acp-title-proj", transcript_path: transcript });
    assert.equal(sessions.get("acp-title-1").title, "Curve navigation dots");
    const announced = lastSessionEvent(sseBuffer, "acp-title-1");
    assert.equal(announced?.state, "running");
    assert.equal(announced?.title, "Curve navigation dots", "clients learn it through an idempotent running event");
  } finally {
    sessions.clear();
  }
});

test("the mid-turn scan is bounded: an AI title disarms it, and it runs at most once a second", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    // Slot created by its own first hook, BEFORE the ai-title landed: born
    // with the first-prompt fallback.
    const transcript = writeTranscript([userPrompt("make the nav dots curve")]);
    const sid = resolveHookSession({ session_id: "cc-title-mid", cwd: "/tmp/title-proj-mid", transcript_path: transcript });
    const slot = sessions.get(sid);
    assert.equal(slot.title, "make the nav dots curve");
    assert.equal(slot.titleIsAi, false);

    // The turn's next hook scans (nothing has changed yet) and starts the
    // rate-limit clock.
    resolveHookSession({ session_id: "cc-title-mid", cwd: "/tmp/title-proj-mid", transcript_path: transcript });
    assert.equal(typeof slot.titleScanAt, "number");

    // The ai-title lands mid-turn. Hooks inside the 1s window skip the scan —
    // a burst of parallel tool calls costs one read, not one per call.
    fs.appendFileSync(transcript, JSON.stringify(aiTitle("Curve navigation dots")) + "\n");
    resolveHookSession({ session_id: "cc-title-mid", cwd: "/tmp/title-proj-mid", transcript_path: transcript });
    assert.equal(slot.title, "make the nav dots curve", "rate-limited: still the fallback");

    // A hook a second later picks it up.
    slot.titleScanAt -= 1000;
    resolveHookSession({ session_id: "cc-title-mid", cwd: "/tmp/title-proj-mid", transcript_path: transcript });
    assert.equal(slot.title, "Curve navigation dots");
    assert.equal(slot.titleIsAi, true);

    // Disarmed for good: a later ai-title is a title EVOLUTION, which is not
    // urgent and keeps riding the Stop refresh rather than the hot path.
    const scannedAt = slot.titleScanAt;
    fs.appendFileSync(transcript, JSON.stringify(aiTitle("Curve the halo navigation dots")) + "\n");
    slot.titleScanAt -= 10_000;
    resolveHookSession({ session_id: "cc-title-mid", cwd: "/tmp/title-proj-mid", transcript_path: transcript });
    assert.equal(slot.title, "Curve navigation dots", "hot path no longer reads the transcript");
    assert.equal(slot.titleScanAt, scannedAt - 10_000, "…and no longer even stamps an attempt");
  } finally {
    sessions.clear();
  }
});

test("a transcript that never yields an ai-title cannot scan forever: the mid-turn scan is capped", async () => {
  const { sessions, resolveHookSession } = await import("../sessions.js");

  try {
    // No ai-title record will ever appear, so the AI-title gate never disarms
    // the scan — only the hard cap can.
    const transcript = writeTranscript([userPrompt("a session whose title never becomes ai-derived")]);
    const sid = resolveHookSession({ session_id: "cc-title-cap", cwd: "/tmp/title-proj-cap", transcript_path: transcript });
    const slot = sessions.get(sid);
    assert.equal(slot.titleIsAi, false);

    for (let i = 0; i < 60; i++) {
      // Each hook is a second later than the last, and the transcript keeps
      // growing, so nothing but the cap can stop the scans.
      if (slot.titleScanAt !== undefined) slot.titleScanAt -= 1000;
      fs.appendFileSync(transcript, JSON.stringify({ type: "assistant", message: { role: "assistant", content: [{ type: "text", text: `turn ${i}` }] } }) + "\n");
      resolveHookSession({ session_id: "cc-title-cap", cwd: "/tmp/title-proj-cap", transcript_path: transcript });
    }
    assert.equal(slot.titleScanCount, 30, "capped at MID_TURN_TITLE_SCAN_LIMIT, not once per hook");
    assert.equal(slot.title, "a session whose title never becomes ai-derived", "the fallback title still stands");
  } finally {
    sessions.clear();
  }
});
