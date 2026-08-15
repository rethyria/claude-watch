// An ended ACP slot's sessionConnection binding must die with it (#127,
// bridge-state-4), in-process — the age-out half needs the injectable clock.
//
// The leak: endUnevidencedSession never deleted the binding (only the
// deregister and inbox-close paths did). The adapter reuses its connection id
// across inbox reconnects, so an aged-out session's stale binding came back
// to LIFE when its fork reconnected — injectToAcpSession then passed every
// check and reported ok for a prompt the fork dropped on the floor, while
// the wear side discarded the user's draft on the strength of that ok.
//
// The ACP handlers are driven with minimal hand-built req/res fakes (the
// loopback HTTP surface is the module's only door to its private maps); the
// assertion runs against the exported injectToAcpSession — the exact function
// the /command dictation path trusts.
import { test, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// Isolate homedir-derived paths before any bridge module loads.
const fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), "claude-watch-acp-binding-"));
process.env.CLAUDE_WATCH_CREDENTIALS_DIR = path.join(fixtureRoot, "creds");
process.env.CLAUDE_WATCH_CLAUDE_PROJECTS_ROOT = path.join(fixtureRoot, "projects");
after(() => {
  try { fs.rmSync(fixtureRoot, { recursive: true, force: true }); } catch { /* ignore */ }
});

const MINUTE = 60 * 1000;

/** A POST-shaped fake request: loopback source, JSON body delivered through
 *  the data/end events readBody listens on. */
function fakeReq(body, { method = "POST", url = "/" } = {}) {
  const listeners = {};
  return {
    method,
    url,
    headers: {},
    socket: { remoteAddress: "127.0.0.1", setKeepAlive() {} },
    on(event, fn) {
      listeners[event] = fn;
      if (event === "end") {
        queueMicrotask(() => {
          if (body !== undefined && listeners.data) listeners.data(Buffer.from(JSON.stringify(body)));
          listeners.end();
        });
      }
      return this;
    },
  };
}

/** A response fake that records what was written — enough for jsonResponse
 *  and for the inbox's SSE writes. */
function fakeRes() {
  return {
    statusCode: null,
    headersSent: false,
    writes: [],
    writeHead(code) { this.statusCode = code; this.headersSent = true; return this; },
    write(chunk) { this.writes.push(String(chunk)); return true; },
    end() {},
  };
}

test("an aged-out session's binding dies with it — a reconnecting fork cannot resurrect delivery (#127)", async () => {
  const { registerAcpSession, ageOutZombieSessions, sessions } = await import("../sessions.js");
  const { SESSION_UNHOSTED_GRACE_MS } = await import("../config.js");
  const { handleAcpUpdate, handleAcpInbox, injectToAcpSession, closeAllAcpInboxes } = await import("../acp.js");
  after(() => closeAllAcpInboxes());

  registerAcpSession({ sessionId: "bind-age-1", cwd: fixtureRoot });
  // The binding-refresh path asserts the routing (a register whose POST was
  // dropped is the case it exists for) — into a vacant binding, slot running.
  await handleAcpUpdate(fakeReq({ connection: "conn-age", sessionId: "bind-age-1", kind: "session_update", payload: {} }), fakeRes());

  // No inbox holds this session, so the liveness probe answers host-gone and
  // the short window ends it — the ending that leaked its binding.
  ageOutZombieSessions(Date.now() + SESSION_UNHOSTED_GRACE_MS + MINUTE);
  assert.equal(sessions.get("bind-age-1").state, "ended", "the unhosted slot ages out");

  // The fork reconnects its inbox under the SAME connection id — the adapter
  // id is per-process, and an SSE retry reuses it.
  const inboxRes = fakeRes();
  handleAcpInbox(fakeReq(undefined, { method: "GET", url: "/acp/inbox?connection=conn-age" }), inboxRes);

  assert.equal(
    injectToAcpSession("bind-age-1", "hello?"), false,
    "delivery into the aged-out slot must refuse — a retained binding would report ok into a void",
  );
  assert.ok(
    !inboxRes.writes.some((w) => w.includes("event: inject")),
    "no inject frame reaches the reconnected fork for the dead session",
  );
});

test("a late update cannot re-bind an ended slot (#127)", async () => {
  const { registerAcpSession, endAcpSession } = await import("../sessions.js");
  const { handleAcpUpdate, handleAcpInbox, injectToAcpSession, closeAllAcpInboxes } = await import("../acp.js");
  after(() => closeAllAcpInboxes());

  registerAcpSession({ sessionId: "bind-late-1", cwd: fixtureRoot });
  endAcpSession("bind-late-1", "acp-closed");

  // The fire-and-forget update legally reordered behind the deregister: it
  // must not re-assert routing for a slot that is only grace-window visible.
  await handleAcpUpdate(fakeReq({ connection: "conn-late", sessionId: "bind-late-1", kind: "session_update", payload: {} }), fakeRes());
  const inboxRes = fakeRes();
  handleAcpInbox(fakeReq(undefined, { method: "GET", url: "/acp/inbox?connection=conn-late" }), inboxRes);

  assert.equal(
    injectToAcpSession("bind-late-1", "hello?"), false,
    "the ended slot must stay unroutable however the update raced the deregister",
  );
});
