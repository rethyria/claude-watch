#!/usr/bin/env node

import { resolveSettings } from "@anthropic-ai/claude-agent-sdk";
import { claudeCliPath, runAcp } from "./acp-agent.js";
import packageJson from "../package.json" with { type: "json" };

// `--cli` is checked first so that `--version`/`-v` (and any other flags) are
// forwarded to the wrapped native CLI rather than swallowed by our own version
// handler below. Our version flag only applies when not delegating.
if (process.argv.includes("--cli")) {
  const { spawn } = await import("node:child_process");
  const args = process.argv.slice(2).filter((arg) => arg !== "--cli");
  const child = spawn(await claudeCliPath(), args, { stdio: "inherit" });

  const signals =
    process.platform === "win32"
      ? (["SIGINT", "SIGTERM"] as const)
      : (["SIGINT", "SIGTERM", "SIGHUP"] as const);
  for (const sig of signals) {
    process.on(sig, () => {
      if (!child.killed) child.kill(sig);
    });
  }

  child.on("exit", (code, signal) => {
    if (signal && process.platform !== "win32") {
      // Remove our listener so re-raising actually terminates instead of
      // re-entering the no-op handler, which would let us exit with code 0
      // instead of the signal's conventional 128+N.
      process.removeAllListeners(signal);
      process.kill(process.pid, signal);
    } else {
      process.exit(code ?? 1);
    }
  });
  child.on("error", (err) => {
    console.error(err);
    process.exit(1);
  });
} else if (process.argv.includes("--version") || process.argv.includes("-v")) {
  console.log(packageJson.version);
  process.exit(0);
} else {
  // Apply env vars from the managed-policy tier before any SDK call so the
  // SDK subprocess inherits them. Going through resolveSettings (vs. a raw
  // read of managed-settings.json) also picks up MDM sources on macOS and
  // HKLM/HKCU on Windows.
  const policy = await resolveSettings({ settingSources: [] });
  for (const [key, value] of Object.entries(policy.effective.env ?? {})) {
    process.env[key] = value;
  }

  // claude-watch (#76 must-fix): refuse to start if anything would divert this
  // session off the claude.ai subscription onto API/Bedrock/Vertex billing.
  //
  // Checked HERE, after the managed-policy env is applied, so a routing var
  // injected by policy is caught too — not just an ambient one. The launcher
  // (launch-claude-watch-acp.sh) already scrubs the same list, but that only
  // helps if you came through the launcher: Zed's agent_servers command can be
  // rewritten to exec dist/index.js directly (it has silently reverted to a
  // registry entry once already), and `npm start` bypasses it by design. A
  // scrub you can walk around is not a guarantee; this is the backstop.
  //
  // The regional/project vars (AWS_REGION, CLOUD_ML_REGION,
  // ANTHROPIC_VERTEX_PROJECT_ID) are deliberately NOT here: they are inert
  // without one of the switches below, and failing on them would false-positive
  // on any machine that merely has the AWS CLI configured.
  const PROVIDER_ROUTING_GUARD_VARS = [
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "ANTHROPIC_BASE_URL",
    "ANTHROPIC_BEDROCK_BASE_URL",
    "ANTHROPIC_VERTEX_BASE_URL",
    "ANTHROPIC_CUSTOM_HEADERS",
    "CLAUDE_CODE_USE_BEDROCK",
    "CLAUDE_CODE_USE_VERTEX",
  ] as const;

  const routingLeaks = PROVIDER_ROUTING_GUARD_VARS.filter(
    (name) => (process.env[name] ?? "") !== "",
  );
  if (routingLeaks.length > 0 && !process.env.CLAUDE_WATCH_ALLOW_API_BILLING) {
    // Values may be secrets — name the vars, never print them.
    console.error(
      `claude-watch-acp: REFUSING TO START — provider-routing env var(s) set: ${routingLeaks.join(", ")}.`,
    );
    console.error(
      "  This session would be billed to the API/Bedrock/Vertex instead of the claude.ai subscription.",
    );
    console.error("  Launch via launch-claude-watch-acp.sh (which scrubs them), or unset them.");
    console.error("  Deliberately want API billing? Set CLAUDE_WATCH_ALLOW_API_BILLING=1.");
    process.exit(78); // EX_CONFIG
  }

  // stdout is used to send messages to the client
  // we redirect everything else to stderr to make sure it doesn't interfere with ACP
  console.log = console.error;
  console.info = console.error;
  console.warn = console.error;
  console.debug = console.error;

  process.on("unhandledRejection", (reason, promise) => {
    console.error("Unhandled Rejection at:", promise, "reason:", reason);
  });

  const { connection, agent } = runAcp();

  async function shutdown() {
    await agent.dispose().catch((err) => {
      console.error("Error during cleanup:", err);
    });
    process.exit(0);
  }

  // Exit cleanly when the ACP connection closes (e.g. stdin EOF, transport
  // error). Without this, `process.stdin.resume()` keeps the event loop
  // alive indefinitely, causing orphan process accumulation in oneshot mode.
  connection.closed.then(shutdown);

  process.on("SIGTERM", shutdown);
  process.on("SIGINT", shutdown);

  // Keep process alive while connection is open
  process.stdin.resume();
}
