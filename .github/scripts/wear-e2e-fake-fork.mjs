#!/usr/bin/env node
// The e2e harness's Zed-fork stand-in (issue #107). Since the ACP-only spawn
// pivot, claude sessions are born in the fork Zed launches: the bridge only
// relays a watch spawn down its /acp/inbox and honestly 409s when no fork is
// connected — so a forkless harness made the WalkingSkeleton spawn leg
// deterministically red. This client plays the fork role against the
// THROWAWAY bridge only: it holds the inbox SSE and services each `spawn`
// frame with the real adapter's own wire moves, in the real adapter's order
// (skill/acp-agent acp-agent.ts onSpawn → bridge-channel.ts):
//
//   1. POST /acp/register   — the detached session, with the #97 meta seed
//   2. POST /acp/spawn-result — the explicit ack the bridge correlates by
//      requestId (never piggybacked on the register)
//   3. one scripted greeting turn over POST /acp/update (turn start →
//      agent_message_chunk → turn end), so the bridge's prose coalescer
//      flushes a `message` event the instrumented test can see in the feed —
//      a newborn watch session has said nothing, and an empty feed proves
//      nothing.
//
// The wire contract is pinned from both ends: the bridge's own tests fake
// this side (skill/bridge/test/acp-spawn.test.js) and the adapter's tests
// fake the bridge side (skill/acp-agent watch-spawn.test.ts), so the frames
// below mirror bridge-channel.ts field for field. `inject` frames are echoed
// as a turn for the same reason the spawn greets: any future dictation leg
// needs observable output. The desk pickup (/acp/claim) is deliberately not
// exercised — there is no editor in this harness to adopt a session.
//
// Isolation: the ONLY address this process ever touches is the loopback port
// passed by wear-e2e.sh — the throwaway bridge's scraped port, inside the
// harness's test-only range — never 7860, never the live bridge, no mDNS.
// Lifetime: killed by the harness's cleanup trap with the bridge; if the
// bridge disappears first, the reconnect loop gives up after a bounded window
// so an aborted run cannot leak a forever-spinning orphan.
import { randomUUID } from "node:crypto";

const port = Number.parseInt(process.argv[2] ?? "", 10);
if (!Number.isInteger(port) || port <= 0) {
  console.error("usage: wear-e2e-fake-fork.mjs <throwaway-bridge-port>");
  process.exit(2);
}
const BRIDGE = `http://127.0.0.1:${port}`;
// One connection id per process, like the real fork (HttpBridgeChannel).
const CONNECTION = `wear-e2e-fake-fork-${randomUUID()}`;

function log(msg) {
  console.log(`[fake-fork] ${new Date().toISOString()} ${msg}`);
}

/** Fire-and-forget uplink POST, the adapter's best-effort contract: a failed
 *  frame is logged for the harness dump, never thrown into the reader loop. */
async function post(route, body) {
  try {
    const resp = await fetch(`${BRIDGE}${route}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!resp.ok) log(`POST ${route} -> ${resp.status}`);
    return resp.ok;
  } catch (err) {
    log(`POST ${route} failed: ${err?.message ?? err}`);
    return false;
  }
}

function update(sessionId, kind, payload) {
  return post("/acp/update", { connection: CONNECTION, sessionId, kind, payload });
}

/** One scripted assistant turn, the exact frames the real adapter's client
 *  tee + forwardTurnBoundary produce. Sequential on purpose: the bridge
 *  clears its prose buffer on a turn start and flushes it at the end, so an
 *  out-of-order chunk would silently vanish instead of failing the leg. */
async function speak(sessionId, text) {
  await update(sessionId, "turn", { phase: "start" });
  await update(sessionId, "session_update", {
    sessionId,
    update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text } },
  });
  await update(sessionId, "turn", { phase: "end", stopReason: "end_turn" });
}

async function serviceSpawn({ requestId, cwd }) {
  const sessionId = randomUUID();
  // The real fork's order: register (detached) inside createSession, THEN the
  // spawn-result ack — the bridge tolerates the reverse (early-register race)
  // but the harness should walk the common path, not the racy one.
  await post("/acp/register", {
    connection: CONNECTION,
    sessionId,
    sdkSessionId: sessionId,
    cwd,
    active: false,
    detached: true,
    // The #97 subheading seed the real register carries (model display name,
    // ACP mode id, context TOKENS — the bridge owns the percent).
    model: "Claude Sonnet",
    mode: "default",
    contextUsed: 1_000,
    contextSize: 200_000,
  });
  await post("/acp/spawn-result", { connection: CONNECTION, requestId, ok: true, sessionId, cwd });
  log(`spawn ${requestId}: registered detached session ${sessionId} (cwd ${cwd})`);
  // The marker WalkingSkeletonTest's spawn leg greps its feed for.
  await speak(sessionId, `wear-e2e-fake-fork ready: session ${sessionId.slice(0, 8)} spawned in ${cwd}`);
}

/** Parse one SSE frame (the adapter's handleFrame, minus the lanes a spawn
 *  harness has no use for). ":comment" heartbeats carry no data and fall out
 *  on the dataLines check. */
function handleFrame(frame) {
  let event = "message";
  const dataLines = [];
  for (const line of frame.split("\n")) {
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""));
  }
  if (dataLines.length === 0) return;
  let data;
  try {
    data = JSON.parse(dataLines.join("\n"));
  } catch {
    return;
  }
  if (event === "spawn" && typeof data.requestId === "string" && typeof data.cwd === "string") {
    log(`inbox spawn request ${data.requestId} (cwd=${data.cwd})`);
    void serviceSpawn(data);
  } else if (event === "inject" && typeof data.sessionId === "string" && typeof data.text === "string") {
    log(`inbox inject for session ${data.sessionId}`);
    void speak(data.sessionId, `wear-e2e-fake-fork echo: ${data.text}`);
  }
}

const CONNECT_RETRY_MS = 500;
// How long the bridge may stay unreachable before this process declares it
// dead and exits: long enough to ride out a slow boot, short enough that an
// aborted harness (trap never ran) leaves no long-lived orphan.
const GIVE_UP_AFTER_MS = 30_000;

async function runInbox() {
  let lastAliveAt = Date.now();
  for (;;) {
    try {
      const resp = await fetch(`${BRIDGE}/acp/inbox?connection=${CONNECTION}`, {
        headers: { accept: "text/event-stream" },
      });
      if (!resp.ok || !resp.body) throw new Error(`inbox status ${resp.status}`);
      log(`inbox connected (connection ${CONNECTION})`);
      lastAliveAt = Date.now();
      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        // Any bytes — the bridge's 15s heartbeats included — prove liveness.
        lastAliveAt = Date.now();
        buffer += decoder.decode(value, { stream: true });
        let sep;
        while ((sep = buffer.indexOf("\n\n")) >= 0) {
          const frame = buffer.slice(0, sep);
          buffer = buffer.slice(sep + 2);
          handleFrame(frame);
        }
      }
      log("inbox stream ended");
    } catch (err) {
      log(`inbox error: ${err?.message ?? err}`);
    }
    if (Date.now() - lastAliveAt > GIVE_UP_AFTER_MS) {
      log("bridge unreachable past the give-up window; exiting");
      process.exit(1);
    }
    await new Promise((resolve) => setTimeout(resolve, CONNECT_RETRY_MS));
  }
}

void runInbox();
