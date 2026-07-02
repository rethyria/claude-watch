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
} from "./config.js";
import { generatePairingCode } from "./credentials.js";
import { sseClients, handleEvents } from "./transport-sse.js";
import { sessions } from "./sessions.js";
import { pendingPermissions } from "./permissions.js";
import { startCodexMonitor, stopCodexMonitor } from "./codex.js";
import { handlePair, handleCommand, handleStatus } from "./commands.js";
import {
  handleHookToolOutput,
  handleHookPermission,
  handleHookStop,
  handleHookTaskComplete,
  handleHookError,
} from "./hooks.js";

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
  "POST /hooks/task-complete": handleHookTaskComplete,
  "POST /hooks/error": handleHookError,
  "GET /status": handleStatus,
};

async function onRequest(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);

  // /v1 routing skeleton: every endpoint is also reachable under a /v1 prefix
  // (e.g. /v1/pair → /pair) with byte-identical behavior. The unprefixed
  // legacy surface stays frozen for existing clients; future protocol-shape
  // changes land on dedicated /v1 handlers only (see ARCHITECTURE.md).
  let pathname = url.pathname;
  if (pathname === "/v1" || pathname.startsWith("/v1/")) {
    pathname = pathname.slice(3) || "/";
  }

  const routeKey = `${req.method} ${pathname}`;

  const handler = routes[routeKey];
  if (handler) {
    try {
      await handler(req, res);
    } catch (err) {
      log("error", `Unhandled error in ${routeKey}:`, err.message);
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

  const code = generatePairingCode();

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
  console.log("");
  console.log("╔═══════════════════════════════════════╗");
  console.log("║        AGENT WATCH BRIDGE             ║");
  console.log("╠═══════════════════════════════════════╣");
  console.log(`║  Pairing Code:  ${code}                ║`);
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
