# Bridge Architecture

## Module map

| Module | Responsibility |
|---|---|
| `server.js` | Entry point: routes table, `/v1` prefix normalization, port binding, pairing banner, Bonjour advertisement, graceful shutdown. |
| `util.js` | Shared low-level helpers: `log()`, `jsonResponse()`, `readBody()`. No bridge-module imports. |
| `config.js` | Configuration constants, `BRIDGE_ID`, and `claude`/`codex` binary discovery (logs at startup). |
| `credentials.js` | Session token, pairing-code generation/validation, pairing rate limiting, `requireAuth()`, bridge state. |
| `transport-sse.js` | SSE event ring buffer, connected clients, `pushSseEvent()` broadcast, and the `GET /events` handler (replay, connect-time sync, heartbeat). |
| `permissions.js` | Pending permission maps and the blocking `waitForPermission()` / `resolvePermission()` pair (10-minute auto-deny timeout). |
| `sessions.js` | Sessions map, PTY spawn/attach/kill lifecycle (via `pty.js`), session lookup helpers, snapshot, and hook-to-session resolution. |
| `codex.js` | All Codex integration: `~/.codex/sessions` JSONL scanner, TUI log monitor, synthetic exec-approval permissions, keystroke resolution into the Codex PTY. |
| `hooks.js` | HTTP handlers for the `/hooks/*` surface (permission, tool-output, stop, task-complete, error). |
| `commands.js` | HTTP handlers for the watch-client API surface (`/pair`, `/command`, `/status`). |
| `pty.js` | Platform-specific PTY spawning via the system `script` utility. |

Dependency direction is strictly downward (`server.js` → handlers → sessions/codex → transport-sse → credentials → config → util). Two would-be cycles are broken with callbacks registered at module evaluation: `sessions.js` exposes `registerSessionCleanupHook()` (used by `codex.js` to clear synthetic permissions when a PTY dies), and `transport-sse.js` exposes `registerSseSyncProvider()` (used by `sessions.js` and `codex.js` to contribute connect-time catch-up events).

## HTTP surface policy

- **Legacy unprefixed routes (`/pair`, `/command`, `/events`, `/status`, `/hooks/*`) are FROZEN.** Existing iOS/watchOS clients depend on their exact response shapes, status codes, log output, and timings. Do not change them.
- **Every route is also reachable under a `/v1` prefix** (e.g. `/v1/pair`): `server.js` strips the prefix before route lookup, so today the two surfaces are byte-identical.
- **Protocol-shape changes** — per-device pairing, machine-readable permission semantics, richer session metadata, etc. — land on dedicated `/v1` handlers only (split the route in the table when a `/v1` endpoint needs to diverge). Legacy clients keep the frozen behavior.
- **Behavior-internal hardening** (bug fixes with no observable protocol change, security tightening, performance) applies to both surfaces, since they share handlers until they are deliberately split.
