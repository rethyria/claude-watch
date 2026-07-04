# Bridge Architecture

## Module map

| Module | Responsibility |
|---|---|
| `server.js` | Entry point: routes table, `/v1` prefix normalization, port binding, pairing banner, Bonjour advertisement, graceful shutdown. |
| `util.js` | Shared low-level helpers: `log()`, `jsonResponse()`, `readBody()`. No bridge-module imports. |
| `config.js` | Configuration constants, `BRIDGE_ID`, and `claude`/`codex` binary discovery (logs at startup). |
| `credentials.js` | Per-device token store (SHA-256 hashes persisted to `credentials.json`), pairing-code generation/validation, pairing lockout latch, `requireAuth()`, bridge state. |
| `rate-limit.js` | Per-IP pairing rate limiter (fixed window per remote address, expired windows pruned on every call). |
| `transport-sse.js` | SSE event ring buffer, connected clients, `pushSseEvent()` broadcast, and the `GET /events` handler (replay, connect-time sync, heartbeat). |
| `permissions.js` | Pending permission maps and the blocking `waitForPermission()` / `resolvePermission()` pair (10-minute auto-deny timeout). |
| `sessions.js` | Sessions map, PTY spawn/attach/kill lifecycle (via `pty.js`), session lookup helpers, snapshot, and hook-to-session resolution. |
| `codex.js` | All Codex integration: `~/.codex/sessions` JSONL scanner, TUI log monitor, synthetic exec-approval permissions, keystroke resolution into the Codex PTY. |
| `hooks.js` | HTTP handlers for the `/hooks/*` surface (permission, tool-output, stop, task-complete, error). |
| `commands.js` | HTTP handlers for the watch-client API surface (`/pair`, `/command`, `/status`). |
| `pty.js` | Platform-specific PTY spawning via the system `script` utility. |

Dependency direction is strictly downward (`server.js` → handlers → sessions/codex → transport-sse → credentials → config → util; `rate-limit.js` sits beside `credentials.js` and imports only `config.js`). Two would-be cycles are broken with additive callbacks registered at module evaluation: `sessions.js` exposes `registerSessionCleanupHook()` (used by `codex.js` to clear synthetic permissions when a PTY dies), and `transport-sse.js` exposes `registerSseSyncProvider()` (used by `sessions.js` and `codex.js` to contribute connect-time catch-up events). Consequence: `codex.js` must be imported for those registrations to happen — importing `sessions.js`/`transport-sse.js`/`hooks.js` in isolation (e.g. from a future in-process unit test) yields a bridge without codex cleanup/sync wiring. The production entry point `server.js` always imports everything.

## HTTP surface policy

- **Legacy unprefixed routes (`/pair`, `/command`, `/events`, `/status`, `/hooks/*`) are FROZEN.** Existing iOS/watchOS clients depend on their exact response shapes, status codes, log output, and timings. Do not change them.
- **Every route is also reachable under a `/v1` prefix** (e.g. `/v1/pair`): `server.js` looks up the exact path first, then falls back to the prefix-stripped legacy key, so today the two surfaces are byte-identical.
- **Protocol-shape changes** — per-device pairing, machine-readable permission semantics, richer session metadata, etc. — land on dedicated `/v1` handlers only: add an exact `"METHOD /v1/..."` key to the routes table and it wins over the fallback. Legacy clients keep the frozen behavior.
- **Behavior-internal hardening** (bug fixes with no observable protocol change, security tightening, performance) applies to both surfaces, since they share handlers until they are deliberately split.
- **Hook endpoints (`/hooks/*`) are unauthenticated and are called by installed hook scripts on the legacy unprefixed paths.** Permission state (`permissions.js`) is shared across surfaces: a decision posted on either surface must resolve a hook received on either surface.
- **Auth contract:** tokens are per-device and are drawn from a single shared store — a token issued by either `/pair` or `/v1/pair` is valid on both surfaces, and legacy-issued tokens keep working everywhere. `requireAuth()` accepts a bearer token if its SHA-256 hash matches any stored credential (constant-time compare on the hash buffers). Pairing a new device never invalidates an existing one.

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
