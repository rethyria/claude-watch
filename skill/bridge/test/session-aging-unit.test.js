// Issue #65, in-process: a session that dies without the bridge OBSERVING it
// used to stay `running` forever.
//
// pruneEndedSessions only ever considered `ended`, so nothing aged out a slot
// stuck in `running`. The live report: a slot whose last observed signal was a
// turn end at 10:01 was still being announced as an active session at 20:20 —
// no owning process the bridge could see, and no path to ever leave the map,
// so every connect re-sent it to every client.
//
// The ageing is deliberately evidence-first (issue #53 is binding: never
// fabricate an end for a session that may be alive). A bridge-owned PTY is
// alive while its process is; an ACP slot whose fork connection is live is
// alive however long it has been idle; a recently-written transcript proves
// something is running the session. Only when all of that comes up empty does
// a window apply — and the end it emits is revivable, not authoritative.
//
// The clock is injected here rather than overridden through the environment,
// so these tests exercise the PRODUCTION windows.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Never let a bridge module near the real ~/.claude-watch, even lazily.
const credsDir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-aging-creds-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = credsDir;
after(() => {
  try { fs.rmSync(credsDir, { recursive: true, force: true }); } catch { /* ignore */ }
});

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;

function lastSessionEvent(sseBuffer, sessionId) {
  for (let i = sseBuffer.length - 1; i >= 0; i--) {
    const entry = sseBuffer[i];
    if (entry.event !== "session") continue;
    const parsed = JSON.parse(entry.data);
    if (parsed.sessionId === sessionId) return parsed;
  }
  return null;
}

// One stub probe for the whole file, answering only for the slots a test has
// an opinion about — exactly the contract a real probe follows (`null` for a
// kind it does not own). acp.js registers the real one; the ACP fork's
// connection liveness is exercised black-box in session-aging.test.js.
const probeAnswers = new Map();
let probeInstalled = false;
async function installProbe() {
  if (probeInstalled) return;
  const { registerSessionLivenessProbe } = await import("../sessions.js");
  registerSessionLivenessProbe((slot) => (probeAnswers.has(slot.id) ? probeAnswers.get(slot.id) : null));
  probeInstalled = true;
}

// A Codex-scanner-shaped slot: no PTY, no connection — the class only the
// silence window can speak for. Created directly (the scanner's
// touchExternalSession is module-private) with the same fields it writes.
function codexSlot(sessions, markSessionObserved, id, extra = {}) {
  const slot = {
    id,
    agent: "codex",
    cwd: `/tmp/aging-65-${id}`,
    folderName: `aging-65-${id}`,
    ptyProcess: null,
    state: "running",
    createdAt: Date.now(),
    idle: false,
    ...extra,
  };
  markSessionObserved(slot);
  sessions.set(id, slot);
  return slot;
}

test("a scanner session silent past the window is ENDED — visibly, and non-authoritatively", async () => {
  const { sessions, markSessionObserved, ageOutZombieSessions } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");
  await installProbe();

  const slot = codexSlot(sessions, markSessionObserved, "cdx-zombie");
  assert.equal(slot.state, "running");

  // Well inside the window nothing happens: the whole point is that a session
  // simply waiting on its user is not a zombie.
  ageOutZombieSessions(Date.now() + 6 * HOUR);
  assert.equal(slot.state, "running", "a few hours of silence is not evidence of death");

  ageOutZombieSessions(Date.now() + 13 * HOUR);
  assert.equal(slot.state, "ended", "a slot nothing can vouch for eventually ends");

  // ENDING, not deleting: the client observes the transition instead of
  // watching a row vanish (the issue asks for exactly this).
  const event = lastSessionEvent(sseBuffer, "cdx-zombie");
  assert.equal(event.state, "ended");
  assert.equal(event.reason, "no-liveness", `the end names its evidence; got ${JSON.stringify(event)}`);

  // And it is an absence of evidence, never a verdict: the scanner's next
  // observed write revives the slot (touchExternalSession's revive branch),
  // which this non-authoritative flag is what permits (issue #53).
  assert.notEqual(slot.endedAuthoritatively, true, "an ageing end must never be authoritative");
});

test("a session that keeps being observed is never aged out, however quiet its transcript", async () => {
  const { sessions, markSessionObserved, ageOutZombieSessions } = await import("../sessions.js");
  await installProbe();

  const slot = codexSlot(sessions, markSessionObserved, "cdx-chatty");
  // 20 hours pass, but the scanner keeps seeing the session write: the window
  // measures silence, not age.
  for (let i = 0; i < 4; i++) {
    markSessionObserved(slot);
    ageOutZombieSessions(Date.now() + 5 * HOUR);
  }
  assert.equal(slot.state, "running");
});

test("a transcript written recently is liveness evidence — the timeout never overrules it", async () => {
  const { sessions, markSessionObserved, ageOutZombieSessions } = await import("../sessions.js");
  await installProbe();

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-aging-transcript-"));
  after(() => { try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* ignore */ } });
  const transcriptPath = path.join(dir, "session.jsonl");
  fs.writeFileSync(transcriptPath, "{}\n");

  const slot = codexSlot(sessions, markSessionObserved, "cdx-writing", { transcriptPath });

  // Every other signal stopped, but the session is plainly still running: its
  // transcript is being written. Ageing it out here would be the opposite bug
  // — a live session vanishing off the wrist.
  fs.utimesSync(transcriptPath, new Date(), new Date(Date.now() + 13 * HOUR));
  ageOutZombieSessions(Date.now() + 13 * HOUR);
  assert.equal(slot.state, "running", "a live transcript is proof something is running the session");

  // Once even the transcript goes quiet for the whole window, nothing can
  // speak for the session any more.
  fs.utimesSync(transcriptPath, new Date(), new Date());
  ageOutZombieSessions(Date.now() + 13 * HOUR);
  assert.equal(slot.state, "ended");
});

test("a bridge-owned PTY slot is never aged out: its process object IS the evidence", async () => {
  const { sessions, ageOutZombieSessions } = await import("../sessions.js");
  await installProbe();

  // A PTY slot ends through its own close handler; ageing must never race it.
  sessions.set("pty-aging", {
    id: "pty-aging",
    agent: "claude",
    cwd: "/tmp/aging-65-pty",
    folderName: "aging-65-pty",
    ptyProcess: { kill() { /* noop */ } },
    state: "running",
    createdAt: Date.now() - 30 * HOUR,
  });
  ageOutZombieSessions(Date.now() + 30 * HOUR);
  assert.equal(sessions.get("pty-aging").state, "running");
});

test("a slot whose host reports it ALIVE is never aged out, however long it has been idle", async () => {
  const { sessions, ageOutZombieSessions } = await import("../sessions.js");
  await installProbe();

  // The ACP era's normal case: a Zed thread nobody has typed into for two
  // days, whose fork still holds its connection. It is alive, and the whole
  // reason liveness evidence outranks a timeout.
  sessions.set("acp-alive", {
    id: "acp-alive",
    agent: "claude",
    cwd: "/tmp/aging-65-alive",
    folderName: "aging-65-alive",
    ptyProcess: null,
    state: "running",
    kind: "acp",
    idle: true,
    createdAt: Date.now() - 48 * HOUR,
    observedAt: Date.now() - 48 * HOUR,
  });
  probeAnswers.set("acp-alive", true);

  ageOutZombieSessions(Date.now() + 48 * HOUR);
  assert.equal(sessions.get("acp-alive").state, "running", "a live host outranks any amount of silence");
  // The probe also re-arms the clock, so the window starts when the host
  // stopped being observable rather than at the last turn.
  probeAnswers.set("acp-alive", false);
  ageOutZombieSessions(Date.now() + 48 * HOUR);
  assert.equal(sessions.get("acp-alive").state, "running", "the unhosted window starts now, not at the last turn");
});

test("a slot whose host is GONE ends on the short window, not the long one", async () => {
  const { sessions, ageOutZombieSessions } = await import("../sessions.js");
  const { sseBuffer } = await import("../transport-sse.js");
  await installProbe();

  // The surviving ACP-era zombie class: a slot whose connection binding leaked
  // — a register that landed after its fork's inbox had already closed, so the
  // close handler that ends every session bound to that fork has already run
  // and nothing else ever will. "Nothing hosts this" is a positive verdict, so
  // it does not wait out the twelve-hour silence window.
  sessions.set("acp-orphan", {
    id: "acp-orphan",
    agent: "claude",
    cwd: "/tmp/aging-65-orphan",
    folderName: "aging-65-orphan",
    ptyProcess: null,
    state: "running",
    kind: "acp",
    createdAt: Date.now(),
    observedAt: Date.now(),
  });
  probeAnswers.set("acp-orphan", false);

  ageOutZombieSessions(Date.now() + MINUTE);
  assert.equal(sessions.get("acp-orphan").state, "running", "the window still outlasts a fork reconnect's backoff");

  ageOutZombieSessions(Date.now() + 3 * MINUTE);
  assert.equal(sessions.get("acp-orphan").state, "ended");
  const event = lastSessionEvent(sseBuffer, "acp-orphan");
  assert.equal(event.reason, "host-gone", `the end names its evidence; got ${JSON.stringify(event)}`);
  assert.notEqual(sessions.get("acp-orphan").endedAuthoritatively, true);
});
