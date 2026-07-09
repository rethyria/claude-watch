# Bridge Architecture

## Module map

| Module | Responsibility |
|---|---|
| `server.js` | Entry point: routes table, `/v1` prefix normalization, Host-header check (via `host-guard.js`, runs pre-auth on every route), process-level crash guards (`unhandledRejection`/`uncaughtException` log instead of dying), port binding, pairing banner, Bonjour advertisement, graceful shutdown. |
| `util.js` | Shared low-level helpers: `log()`, `jsonResponse()`, `readBody()` (1 MiB body cap: oversized requests get a 413 and a destroyed socket before auth runs — the cap constant lives here because this module must not import `config.js`), `isLoopbackAddress()`. No bridge-module imports. |
| `host-guard.js` | `createHostAllowList()`: the Host-header allow-list behind the DNS-rebinding guard — static entries (localhost/loopback, `10.0.2.2`, operator additions) plus the machine's interface addresses, re-snapshotted on a Host miss (throttled) so an IP change mid-run self-heals. No bridge-module imports. |
| `config.js` | Configuration constants, `BRIDGE_ID`, and `claude`/`codex` binary discovery (logs at startup). |
| `credentials.js` | Per-device token store (SHA-256 hashes persisted to `credentials.json`), pairing-code generation/validation, pairing lockout latch, `requireAuth()`, bridge state. |
| `rate-limit.js` | Per-IP pairing rate limiter (fixed window per remote address, expired windows pruned on every call). |
| `transport-sse.js` | SSE event ring buffer, connected clients, `pushSseEvent()` broadcast (evicts clients whose response write buffer exceeds `SSE_MAX_BUFFERED_BYTES`), and the `GET /events` handler (replay, connect-time sync, heartbeat, TCP keepalive on the socket). |
| `permissions.js` | Pending permission maps and the blocking `waitForPermission()` / `resolvePermission()` pair (10-minute auto-deny timeout, which also clears any stored suggestion body). |
| `sessions.js` | Sessions map, PTY spawn/attach/kill lifecycle (via `pty.js`), session lookup helpers, snapshot, hook-to-session resolution, and ended-session pruning (ended slots stay in snapshots for `SESSION_PRUNE_GRACE_MS`, then get deleted). |
| `codex.js` | All Codex integration: `~/.codex/sessions` JSONL scanner, TUI log monitor, synthetic exec-approval permissions, keystroke resolution into the Codex PTY. |
| `hooks.js` | HTTP handlers for the `/hooks/*` surface (permission, tool-output, stop, task-complete, error). Loopback-only: hook scripts run on this machine, so non-loopback sources get a 403 before the body is read. |
| `commands.js` | HTTP handlers for the watch-client API surface (`/pair`, `/command`, `/status`, `/ping`). |
| `pty.js` | Platform-specific PTY spawning via the system `script` utility. |

Dependency direction is strictly downward (`server.js` → handlers → sessions/codex → transport-sse → credentials → config → util; `rate-limit.js` sits beside `credentials.js` and imports only `config.js`; `host-guard.js` sits beside `util.js` at the bottom and imports nothing). Two would-be cycles are broken with additive callbacks registered at module evaluation: `sessions.js` exposes `registerSessionCleanupHook()` (used by `codex.js` to clear synthetic permissions when a PTY dies), and `transport-sse.js` exposes `registerSseSyncProvider()` (used by `sessions.js` and `codex.js` to contribute connect-time catch-up events). Consequence: `codex.js` must be imported for those registrations to happen — importing `sessions.js`/`transport-sse.js`/`hooks.js` in isolation (e.g. from a future in-process unit test) yields a bridge without codex cleanup/sync wiring. The production entry point `server.js` always imports everything.

## HTTP surface policy

- **Legacy unprefixed routes (`/pair`, `/command`, `/events`, `/status`, `/hooks/*`) are FROZEN.** Existing iOS/watchOS clients depend on their exact response shapes, status codes, log output, and timings. Do not change them.
- **Every route is also reachable under a `/v1` prefix** (e.g. `/v1/pair`): `server.js` looks up the exact path first, then falls back to the prefix-stripped legacy key, so today the two surfaces are byte-identical.
- **Protocol-shape changes** — per-device pairing, machine-readable permission semantics, richer session metadata, etc. — land on dedicated `/v1` handlers only: add an exact `"METHOD /v1/..."` key to the routes table and it wins over the fallback. Legacy clients keep the frozen behavior.
- **Behavior-internal hardening** (bug fixes with no observable protocol change, security tightening, performance) applies to both surfaces, since they share handlers until they are deliberately split.
- **Hook endpoints (`/hooks/*`) are unauthenticated but loopback-only**: installed hook scripts call them from this machine on the legacy unprefixed paths, so any non-loopback `remoteAddress` gets a 403 before the body is read (a LAN peer must not be able to spoof permission prompts or terminal output onto the trusted watch UI). Permission state (`permissions.js`) is shared across surfaces: a decision posted on either surface must resolve a hook received on either surface.
- **Auth contract:** tokens are per-device and are drawn from a single shared store — a token issued by either `/pair` or `/v1/pair` is valid on both surfaces, and legacy-issued tokens keep working everywhere. `requireAuth()` accepts a bearer token if its SHA-256 hash matches any stored credential (constant-time compare on the hash buffers). Pairing a new device never invalidates an existing one.
- **`GET /status` requires the bearer token** (a deliberate hardening exception to the legacy freeze: its session snapshot enumerates every project's absolute path, which must not be readable by arbitrary LAN peers on a 0.0.0.0 bind). Unauthenticated 401s with `{"error": "Unauthorized"}`.
- **`GET /ping` is the unauthenticated discovery probe.** Watch clients verifying a candidate bridge address — the localhost fallback, manual IP entry, or the Android emulator's `10.0.2.2` host alias — probe `GET /ping` instead of `/status`. It returns exactly `{proto, bridgeId, machineName}` (`proto` matches the Bonjour `txt.version`) and nothing richer; everything else stays behind auth.
- **Host-header allow-list (DNS-rebinding guard):** every request is checked pre-auth against an allow-list — `localhost`, loopback addresses, every local interface address (the bound LAN IP), and `10.0.2.2` (the Android emulator's alias for its host; without it emulator-based Wear clients could not reach a bridge on the same machine). The interface-derived entries are **not** a startup snapshot: a Host miss re-snapshots `os.networkInterfaces()` (throttled to once per second) and re-checks, so a bridge whose LAN IP changes mid-run (DHCP re-lease, network switch) keeps serving without a restart. Operators extend the static entries via `CLAUDE_WATCH_ALLOWED_HOSTS` (comma-separated env var) or repeatable `--allow-host=<host>` flags. Unknown or missing Host → `403 {"error": "Forbidden Host header"}`; a syntactically invalid Host keeps its frozen 400.

## Credential store & pairing lockout

**Store.** Paired-device credentials persist in `~/.claude-watch/credentials.json` (directory overridable via the `CLAUDE_WATCH_CREDENTIALS_DIR` env var — tests point it at per-test temp dirs so they never touch the real store). The directory is created `0700`, the file is written `0600` via an atomic temp-file + rename, and it holds only token *hashes* — the plaintext token is returned to the device at pair time and never stored:

```json
{
  "version": 1,
  "tokens": [
    {
      "hash": "<sha256 hex of the bearer token>",
      "deviceName": "watch-a",          // optional, from the pair request body
      "createdAt": "2026-07-03T00:00:00.000Z",
      "surface": "legacy"                // or "v1": which endpoint issued it
    }
  ]
}
```

The store is loaded at startup, so a bridge restart no longer unpairs anyone.

**Pairing lockout.** After any successful pair (on either surface) the pairing surface locks: subsequent `POST /pair` / `POST /v1/pair` return `403 {"error": "Already paired. Re-pairing requires explicit authorization on the bridge."}`. A bridge that starts with a non-empty store starts locked (its banner shows `Pairing: locked (SIGUSR1)` instead of a code). Reopening is a scriptable operator action, never a client request:

- `SIGUSR1` — reopens pairing at runtime and mints a fresh code with the normal 5-minute TTL (logged exactly like startup: `Pairing code generated: ...`); the surface locks again after the next successful pair.
- `--allow-pairing` — CLI flag; starts the bridge with pairing open even when devices are already paired.

**Legacy freeze.** `/pair` keeps its frozen request/response JSON, status codes, and log lines. The invisible fix: success now *adds* a credential instead of overwriting the single global token (previously, re-pairing deauthenticated the current device). The 403 lockout is a new state the legacy flow could not meaningfully reach before — the old behavior silently reopened pairing after success, which is exactly the bug this replaces.

**Rate limiting.** Pairing attempts are limited per remote IP (`rate-limit.js`, 5 attempts per 5-minute fixed window per address), so one source exhausting its attempts cannot 429 a different source.

**Revocation.** Bridge-side only for now: stop the bridge, delete the device's entry (or the whole `tokens` array / file) from `credentials.json`, and restart. Do not edit the file while the bridge runs — it holds the store in memory and rewrites the file on the next successful pair. There is deliberately no HTTP revocation endpoint yet.
