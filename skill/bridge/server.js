// Bridge server entry point: router, startup (port binding, pairing banner,
// Bonjour advertisement, Codex monitor), and graceful shutdown.
// All domain logic lives in the focused modules — see ARCHITECTURE.md.
import http from "node:http";
import os from "node:os";
import { Bonjour } from "bonjour-service";
import { log, jsonResponse } from "./util.js";
import {
  PORT_RANGE_START,
  PORT_RANGE_END,
  BRIDGE_ID,
  CLAUDE_BIN,
  CODEX_BIN,
  ALLOW_PAIRING_FLAG,
} from "./config.js";
import {
  generatePairingCode,
  loadTokenStore,
  lockPairing,
  reopenPairing,
} from "./credentials.js";
import { sseClients, handleEvents } from "./transport-sse.js";
import { sessions } from "./sessions.js";
import { pendingPermissions } from "./permissions.js";
import { startCodexMonitor, stopCodexMonitor } from "./codex.js";
import { handlePair, handleCommand, handleStatus } from "./commands.js";
import {
  handleHookToolOutput,
  handleHookPermission,
  handleHookStop,
  handleHookSessionEnd,
  handleHookTaskComplete,
  handleHookError,
} from "./hooks.js";

// ---------------------------------------------------------------------------
// Process-level guards
// ---------------------------------------------------------------------------
// Last-resort safety net: a stray rejection or exception anywhere in the
// bridge must not kill the process — that would tear down every PTY session
// and strand every in-flight permission hook. Log loudly and keep serving.

process.on("unhandledRejection", (reason) => {
  log(
    "error",
    "Unhandled promise rejection (bridge kept alive):",
    reason instanceof Error ? reason.stack : String(reason),
  );
});

process.on("uncaughtException", (err) => {
  log(
    "error",
    "Uncaught exception (bridge kept alive):",
    err instanceof Error ? err.stack : String(err),
  );
});

// Test-only fault injection (test/crash-resilience.test.js): fires a stray
// rejection/exception shortly after startup so the guards above have black-box
// coverage. Inert unless the env var is set.
const TEST_FAULT = process.env.CLAUDE_WATCH_TEST_FAULT;
if (TEST_FAULT === "unhandledRejection") {
  setTimeout(() => { Promise.reject(new Error("injected test fault: unhandled rejection")); }, 50);
} else if (TEST_FAULT === "uncaughtException") {
  setTimeout(() => { throw new Error("injected test fault: uncaught exception"); }, 50);
}

// Bonjour
let bonjourInstance = null;
let bonjourService = null;

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

const routes = {
  "POST /pair": handlePair,
  "POST /command": handleCommand,
  "GET /events": handleEvents,
  "POST /hooks/tool-output": handleHookToolOutput,
  "POST /hooks/permission": handleHookPermission,
  "POST /hooks/stop": handleHookStop,
  "POST /hooks/session-end": handleHookSessionEnd,
  "POST /hooks/task-complete": handleHookTaskComplete,
  "POST /hooks/error": handleHookError,
  "GET /status": handleStatus,
};

async function onRequest(req, res) {
  // The Host header is attacker-controlled and reaches this parse pre-auth:
  // a malformed value (e.g. "bad host" via a raw socket) must yield a 400,
  // not an unhandled rejection that kills the bridge. RFC 9112 §3.2 mandates
  // 400 for an invalid Host field. Only `pathname` is used for routing, so
  // well-formed requests behave exactly as before.
  let url;
  try {
    url = new URL(req.url, `http://${req.headers.host}`);
  } catch {
    jsonResponse(res, 400, { error: "Bad request" });
    return;
  }

  // /v1 routing skeleton: every endpoint is also reachable under a /v1 prefix
  // (e.g. /v1/pair → /pair). The unprefixed legacy surface stays frozen for
  // existing clients; when a /v1 endpoint needs to diverge, add its exact
  // "METHOD /v1/..." key to the routes table — exact matches win over the
  // prefix-stripped fallback (see ARCHITECTURE.md).
  let routeKey = `${req.method} ${url.pathname}`;
  let handler = routes[routeKey];
  if (!handler && (url.pathname === "/v1" || url.pathname.startsWith("/v1/"))) {
    routeKey = `${req.method} ${url.pathname.slice(3) || "/"}`;
    handler = routes[routeKey];
  }

  if (handler) {
    try {
      await handler(req, res);
    } catch (err) {
      log("error", `Unhandled error in ${routeKey} (requested: ${url.pathname}):`, err.message);
      if (!res.headersSent) {
        jsonResponse(res, 500, { error: "Internal server error" });
      }
    }
  } else {
    jsonResponse(res, 404, { error: "Not found" });
  }
}

// ---------------------------------------------------------------------------
// Server startup
// ---------------------------------------------------------------------------

function tryListen(server, port) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "0.0.0.0", () => {
      server.removeListener("error", reject);
      resolve(port);
    });
  });
}

async function startServer() {
  const server = http.createServer(onRequest);

  let boundPort = null;
  for (let port = PORT_RANGE_START; port <= PORT_RANGE_END; port++) {
    try {
      boundPort = await tryListen(server, port);
      break;
    } catch (err) {
      if (err.code === "EADDRINUSE") {
        log("warn", `Port ${port} in use, trying next...`);
        continue;
      }
      throw err;
    }
  }

  if (boundPort === null) {
    log("error", `No available port in range ${PORT_RANGE_START}-${PORT_RANGE_END}`);
    process.exit(1);
  }

  log("info", `Bridge server listening on 0.0.0.0:${boundPort}`);

  // Pairing lockout at startup: with persisted device credentials the bridge
  // comes back paired, so the pairing surface starts locked unless the
  // operator explicitly asked to pair a new device via --allow-pairing.
  const storedCredentials = loadTokenStore();
  let code = null;
  if (storedCredentials > 0 && !ALLOW_PAIRING_FLAG) {
    lockPairing();
    log("info", `Pairing locked (${storedCredentials} device(s) paired). Send SIGUSR1 or restart with --allow-pairing to pair a new device.`);
  } else {
    code = generatePairingCode();
  }

  // Scriptable reopen: SIGUSR1 unlocks the pairing surface and mints a fresh
  // code with the normal TTL; the surface locks again after the next
  // successful pair.
  process.on("SIGUSR1", () => {
    log("info", "SIGUSR1 received: reopening pairing for a new device");
    reopenPairing();
  });

  // Bonjour (error callback: mDNS failures — bound 5353, no multicast — must
  // not crash the bridge; discovery degrades to manual IP entry)
  bonjourInstance = new Bonjour(undefined, (err) => {
    log("warn", `Bonjour/mDNS error (discovery disabled): ${err?.message || err}`);
  });
  bonjourService = bonjourInstance.publish({
    name: `Agent Watch Bridge (${os.hostname()})`,
    type: "claude-watch",
    protocol: "tcp",
    port: boundPort,
    txt: {
      version: "2",
      bridgeId: BRIDGE_ID,
      sessionId: BRIDGE_ID, // backward compat
      machineName: os.hostname(),
    },
  });

  log("info", `Bonjour advertising _claude-watch._tcp on port ${boundPort}`);
  startCodexMonitor();

  const agents = [];
  if (CLAUDE_BIN) agents.push("Claude");
  if (CODEX_BIN) agents.push("Codex");
  log("info", `Bridge ready. Available agents: ${agents.join(", ") || "none"}. Sessions spawn on demand.`);

  // Get LAN IP
  const interfaces = os.networkInterfaces();
  let lanIP = "127.0.0.1";
  for (const [, addrs] of Object.entries(interfaces)) {
    for (const addr of addrs) {
      if (addr.family === "IPv4" && !addr.internal) {
        lanIP = addr.address;
        break;
      }
    }
    if (lanIP !== "127.0.0.1") break;
  }

  const agentLine = agents.length ? agents.join(" + ") : "none";
  // Unlocked banner line is byte-identical to the historical output (test
  // helpers and operator muscle memory scrape it); the locked variant is new.
  const pairingLine = code !== null
    ? `║  Pairing Code:  ${code}                ║`
    : `║  Pairing:       ${"locked (SIGUSR1)".padEnd(22)}║`;
  console.log("");
  console.log("╔═══════════════════════════════════════╗");
  console.log("║        AGENT WATCH BRIDGE             ║");
  console.log("╠═══════════════════════════════════════╣");
  console.log(pairingLine);
  console.log(`║  IP Address:    ${lanIP.padEnd(20)}║`);
  console.log(`║  Port:          ${String(boundPort).padEnd(20)}║`);
  console.log(`║  Agents:        ${agentLine.padEnd(20)}║`);
  console.log("╚═══════════════════════════════════════╝");
  console.log("");

  // --- Graceful shutdown ---

  let shuttingDown = false;

  async function shutdown(signal) {
    if (shuttingDown) return;
    shuttingDown = true;
    log("info", `Received ${signal}, shutting down gracefully...`);

    for (const client of sseClients) {
      try { client.end(); } catch { /* ignore */ }
    }
    sseClients.clear();

    // Kill all session PTYs
    for (const [id, slot] of sessions) {
      if (slot.ptyProcess) {
        try { slot.ptyProcess.kill(); } catch { /* ignore */ }
        log("info", `Killed session ${id} (${slot.agent})`);
      }
    }
    sessions.clear();
    stopCodexMonitor();

    if (bonjourService) {
      try { bonjourInstance.unpublishAll(); } catch { /* ignore */ }
    }
    if (bonjourInstance) {
      try { bonjourInstance.destroy(); } catch { /* ignore */ }
    }

    for (const [id, pending] of pendingPermissions) {
      clearTimeout(pending.timer);
      pending.resolve({ behavior: "deny", reason: "Server shutting down" });
    }
    pendingPermissions.clear();

    server.close(() => {
      log("info", "Server closed");
      process.exit(0);
    });

    setTimeout(() => {
      log("warn", "Forced exit after timeout");
      process.exit(1);
    }, 5000);
  }

  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));

  return { server, port: boundPort };
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

startServer().catch((err) => {
  log("error", "Failed to start server:", err.message);
  process.exit(1);
});
