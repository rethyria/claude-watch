# Bridge Protocol — `/v1` (proto 3)

The wire contract between the bridge and watch clients. This document covers
the **`/v1` surface**, which is the only surface new clients may target. The
unprefixed legacy surface (`/pair`, `/command`, `/events`, `/status`) is
**frozen** for existing iOS/watchOS clients and documented only where it
differs (see [Legacy surface](#legacy-surface-frozen)).

The **executable form of this contract** is the recorded fixture corpus in
[`test/fixtures/`](test/fixtures/) — request/response/SSE recordings taken
from a real bridge and replayed against every build by
`test/protocol-fixtures.test.js`. When this document and the fixtures
disagree, the fixtures win. Client test suites (the Kotlin Wear client's
MockWebServer tests) should feed themselves from the same corpus.

- [Versioning](#versioning)
- [Discovery](#discovery)
- [Transport & request security](#transport--request-security)
- [Pairing — `POST /v1/pair`](#pairing--post-v1pair)
- [Authentication](#authentication)
- [Identity naming: `bridgeId` vs `sessionId`](#identity-naming-bridgeid-vs-sessionid)
- [Status — `GET /v1/status`](#status--get-v1status)
- [Usage — `GET /v1/usage`](#usage--get-v1usage)
- [Commands — `POST /v1/command`](#commands--post-v1command)
- [Event stream — `GET /v1/events`](#event-stream--get-v1events)
- [SSE event catalog](#sse-event-catalog)
- [Permission decision semantics](#permission-decision-semantics)
- [ACP uplink (server-local)](#acp-uplink-server-local)
- [Legacy surface (frozen)](#legacy-surface-frozen)
- [Fixture corpus](#fixture-corpus)

## Versioning

The protocol version is a single integer, currently **3** (`PROTOCOL_VERSION`
in `config.js`). It appears in four places:

| Where | Key | Type |
|---|---|---|
| Bonjour TXT record | `v` (canonical), `version` (legacy alias) | string (mDNS TXT) |
| `GET /ping` / `GET /v1/ping` response | `proto` | number |
| `POST /v1/pair` request body | `proto` (the **client's** version) | number |
| `POST /v1/pair` success response | `proto` (the bridge's version) | number |

**Min-version check (pair time only).** A `/v1/pair` request must declare the
client's protocol version as an integer `proto`. If it is missing or below
the bridge's minimum (`MIN_SUPPORTED_CLIENT_PROTO`, currently 3), pairing is
refused with **`426 Upgrade Required`**:

```json
{
  "error": "Unsupported protocol version: this bridge requires proto >= 3, but the pair request declared 2. Update the watch app.",
  "proto": 3,
  "minProto": 3
}
```

The refusal happens before the pairing code is checked: it burns neither the
code nor the pairing window, mints no token, and is rate-limited like any
other pair attempt. An outdated app therefore fails **detectably at pair
time** with an actionable error — never by silently mis-parsing events later.
Clients should treat any `426` from `/v1/pair` as "update the app" and may
show `minProto` to the user.

**What bumps the version.**

- *Breaking* changes to the `/v1` wire shape or semantics — removing/renaming
  a field, changing a field's type or meaning, changing a status code — bump
  `PROTOCOL_VERSION` (and usually `MIN_SUPPORTED_CLIENT_PROTO`) and require
  regenerating the `/v1` fixture corpus.
- *Additive* changes — a new optional response field, a new SSE event type, a
  new key on an existing event payload — do **not** bump the version. Clients
  MUST ignore unknown JSON keys and unknown SSE event types.
- The legacy surface never changes and has no version negotiation.

**Version check is pair-time only.** After pairing, requests are not
re-checked; a bridge upgrade that raises `minProto` past a paired client's
version surfaces the next time that client needs to pair. Clients may also
compare `proto` from the unauthenticated `/ping` against their own supported
version to warn earlier.

## Discovery

**Bonjour/mDNS.** The bridge advertises `_claude-watch._tcp` with the TXT
record:

```
v=3  version=3  bridgeId=<uuid>  sessionId=<uuid>  machineName=<hostname>
```

`v` is the canonical protocol-version key. `version` and `sessionId` (an
alias for `bridgeId`) are frozen legacy aliases. mDNS may be unavailable
(bound port 5353, no multicast); discovery then degrades to manual IP entry.

**`GET /ping` (unauthenticated).** The probe clients use to verify a
candidate bridge address (manual IP entry, the Android emulator's `10.0.2.2`
host alias, a localhost fallback) before they hold a token:

```json
{ "proto": 3, "bridgeId": "<uuid>", "machineName": "steamdeck" }
```

Exactly this triple and nothing richer — session snapshots and project paths
stay behind auth on `/status`. `bridgeId` is a random UUID minted per bridge
*process*; it changes on restart and identifies a bridge instance, not a
machine.

## Transport & request security

- Plain HTTP on a LAN, bound `0.0.0.0` on the first free port in
  `7860..7869`. The bound port is published to `~/.claude-watch/port` and in
  the Bonjour advertisement.
- **Host-header allow-list (DNS-rebinding guard), pre-auth on every route:**
  `localhost`, loopback literals, the machine's interface addresses
  (re-snapshotted on a miss, so an IP change self-heals), `10.0.2.2` (Android
  emulator host alias), and **`bridge.internal`** — the synthetic hostname the
  Wear client pins all traffic to (DNS resolved client-side to the paired
  bridge IP, keeping the cleartext-HTTP exemption scoped to one name).
  Operators add entries via `CLAUDE_WATCH_ALLOWED_HOSTS` or
  `--allow-host=<host>`. Unknown Host → `403 {"error": "Forbidden Host
  header"}`; malformed Host → `400 {"error": "Bad request"}`.
- **`/acp/*` and `/admin/*` are loopback-only** (see
  [ACP uplink](#acp-uplink-server-local) and
  [Admin surface](#admin-surface-server-local-operator)): non-loopback
  sources get a `403` before the body is read.
- Request bodies are capped at 1 MiB: oversized requests get `413` and a
  destroyed socket.

## Pairing — `POST /v1/pair`

Exchanges the 6-digit code from the bridge's startup banner for a per-device
bearer token.

Request:

```json
{ "code": "123456", "proto": 3, "deviceName": "pixel-watch-2" }
```

`code` (string) — **optional on `/v1/pair`**: omit it for the code-less
Discover pairing path (issue #23 follow-up), where the operator-opened pairing
window is the sole gate; still **required on the frozen legacy `/pair`**.
`proto` (integer, required on `/v1` — see [Versioning](#versioning), and still
required for a code-less pair); `deviceName` (string, optional, truncated to
200 chars, stored for operator bookkeeping).

**Code-less (Discover) pairing.** A `/v1/pair` with no `code` is admitted when
the pairing window is open (`isPairingOpen`), is rate-limited and
min-proto-gated identically to a code-bearing pair, and engages the same
single-use lock on success. When the window is closed it `403`s exactly like a
code-bearing pair — a code-less pair is impossible against a locked window.
This is an additive relaxation (it only drops a required field for clients that
opt in), so the protocol version stays `3` and any client that keeps sending
`{code, proto}` behaves identically.

Success (`200`):

```json
{
  "token": "<64-hex bearer token>",
  "bridgeId": "<uuid>",
  "availableAgents": ["claude", "codex"],
  "sessions": [ { "id": "<uuid>", "agent": "claude", "cwd": "/home/u/proj",
                  "folderName": "proj", "state": "running", "createdAt": 1720000000000 } ],
  "proto": 3
}
```

There is **no top-level `sessionId`** on `/v1` (see
[Identity naming](#identity-naming-bridgeid-vs-sessionid)). The token is
returned in plaintext exactly once; the bridge persists only its SHA-256
hash. Pairing a new device never invalidates existing tokens.

Errors:

| Status | Body `error` | Meaning |
|---|---|---|
| `400` | `Missing 'code' field` | no/invalid `code` — **legacy `/pair` only**; on `/v1/pair` a missing code is not a `400` (it proceeds to the proto/window gates) |
| `400` | `Invalid JSON` | unparseable body |
| `401` | `Invalid pairing code` | wrong code (window still open) |
| `401` | `Pairing code expired. A new code has been generated.` | startup window expired; fresh code is in the bridge console |
| `403` | `Already paired. Re-pairing requires explicit authorization on the bridge.` | pairing lockout engaged |
| `403` | `Pairing code expired and pairing is locked again. Send SIGUSR1 on the bridge to reopen.` | an operator-reopened window expired unpaired |
| `426` | `Unsupported protocol version: ...` | client `proto` missing or `< minProto`; body also carries `proto` and `minProto` |
| `429` | `Too many pairing attempts. Try again later.` | per-IP rate limit (5 attempts / 5 min) |

**Lockout.** After any successful pair the pairing surface locks until an
explicit operator action reopens it: `SIGUSR1` to the bridge process (mints a
fresh code; the window relocks if it expires unpaired) or a restart with
`--allow-pairing`. A bridge that starts with stored credentials starts
locked; a corrupt credential store also locks (fail closed).

## Authentication

Everything except `GET /ping`, `POST /pair`/`/v1/pair`, and the loopback-only
server-local surfaces (`/acp/*`, `/admin/*`) requires:

```
Authorization: Bearer <token>
```

Tokens are per-device, drawn from one shared store: a token issued on either
surface is valid on both. Missing/unknown token → `401 {"error":
"Unauthorized"}`. There is no HTTP revocation endpoint; revocation is
bridge-side (edit `credentials.json` while the bridge is stopped).

## Identity naming: `bridgeId` vs `sessionId`

Historically `sessionId` meant two things: the bridge-instance UUID at the
top level of `/pair`//`/status` responses, and the agent-session slot UUID
inside SSE event payloads. On `/v1` this is disambiguated:

- **`bridgeId`** — the bridge *process* instance (changes on restart). The
  only top-level identity on `/v1` responses.
- **`sessionId`** — always an *agent session slot* id: the `sessions[].id` of
  pair/status snapshots, the `sessionId` injected into SSE payloads, and the
  `sessionId` accepted by `POST /v1/command`.

`/v1/pair` and `/v1/status` responses carry **no** top-level `sessionId`.
(Legacy responses keep `sessionId` = `bridgeId` as a frozen alias, and the
Bonjour TXT keeps its `sessionId` alias key.)

## Status — `GET /v1/status`

Authenticated snapshot:

```json
{
  "bridgeId": "<uuid>",
  "state": "connected",
  "availableAgents": ["claude", "codex"],
  "sessions": [ { "id": "<uuid>", "agent": "claude", "cwd": "/home/u/proj",
                  "folderName": "proj", "state": "running", "createdAt": 1720000000000 } ],
  "sseClients": 1,
  "pendingPermissions": 0,
  "eventBufferSize": 42,
  "loggingDegraded": false,
  "hasPty": true,
  "activeAgent": "claude"
}
```

- `state`: `"idle"` (never paired) | `"connected"` (at least one device
  paired).
- `sessions[].state`: `"running"` | `"ended"`. Ended sessions linger in
  snapshots for a grace period (~5 min), then get pruned.
- `sessions[].title` (string, **optional, additive**): the session's
  human-readable title, present only once the bridge has one (pushed by the
  Zed adapter — see the [`session`](#session) event). Clients must tolerate
  its absence.
- `sessions[].external` (boolean, **optional, additive**): `true` only for a
  session whose process the bridge does not own (an ACP session — Zed's
  process); **omitted** for bridge-owned PTY slots. Clients must treat its
  absence as `external: false` (killable). See the [`session`](#session)
  event.
- `sessions[].idle` (boolean, **optional, additive**): `true` when the
  session's last lifecycle signal was a turn end (an ACP turn end, a Codex
  `task_complete`);
  **omitted** while it is producing work, and by bridges predating the field.
  Unlike every other additive field, absence does NOT mean "preserve" — see
  the [`session`](#session) event for the one-direction consumption rule.
- `sessions[].branch` / `sessions[].worktree` / `sessions[].repoRoot`
  (**optional, additive**): git metadata of the session's project root —
  branch name (detached HEAD → 7-char short sha); `worktree: true` plus the
  main repo's `repoRoot` **only** for a linked git worktree. Absent for
  non-git roots. See the [`session`](#session) event for derivation and the
  absent-means-preserve doctrine.
- `sessions[].agents` (object, **optional, additive**):
  `{ "running": n, "done": n }` — workflow subagent activity, present once
  observed. Completion is the **explicit** `{running: 0, ...}` state; absence
  preserves the last known value. See the [`session`](#session) event.
- `sessions[].model` / `sessions[].mode` / `sessions[].contextPct`
  (**optional, additive** — issue #97): the subheading meta of an ACP
  session — model display name, permission-mode id, integer context-used
  percent. Omitted for PTY/Codex sessions, which never carry them. See the
  [`session`](#session) event.
- `loggingDegraded` (boolean, **additive** — issue #93): `true` once a write
  to the bridge's primary log sink (stdout/stderr) has failed — typically the
  terminal that started it is gone — and log lines are being appended to
  `~/.claude-watch/bridge.log` instead of dropped. One-way for the life of
  the process. `/v1` only (the legacy `/status` shape is frozen); bridges
  predating the field omit it.
- `hasPty` / `activeAgent`: legacy conveniences describing the most recent
  active session; prefer `sessions[]`.

## Usage — `GET /v1/usage`

Authenticated, **additive** (no proto bump — issue #57): the plan-limit
windows of the bridge user's Claude account, fetched **on demand** — the
client calls it on usage-page open only (no polling, and the bridge keeps no
cache of its own). The bridge reads Claude Code's OAuth access token from
`~/.claude/.credentials.json` and proxies the OAuth usage API; the token
itself **never** appears in any response body or log line — the watch sees
only the normalized bars.

Success (`200`):

```json
{
  "limits": [
    { "kind": "session",       "label": "5-hour", "percent": 42, "resetsAt": "2026-07-18T19:10:00Z", "severity": "normal" },
    { "kind": "weekly_all",    "label": "weekly", "percent": 12, "resetsAt": "2026-07-24T00:00:00Z" },
    { "kind": "weekly_scoped", "label": "Fable",  "percent": 3,  "resetsAt": "2026-07-24T00:00:00Z" }
  ],
  "source": "api",
  "fetchedAtMs": 1752850000000
}
```

- `limits[]` is **render-what-you-get**: one entry per window the upstream
  reports, in upstream order — clients render one bar per entry and must not
  assume which kinds are present. `percent` is **USED** percent exactly as
  upstream reports it (rounded to an integer); remaining = `100 - percent`.
  `resetsAt` is the upstream ISO 8601 reset time, verbatim. `label` is the
  bridge-normalized display label: `"5-hour"` for `kind: "session"`,
  `"weekly"` for `weekly_all`, the scoped model's display name (e.g.
  `"Fable"`) for `weekly_scoped` (falling back to the kind), and the kind
  verbatim for any future window.
- `severity` (string, **optional**): the upstream's own color coding for the
  window, passed through **verbatim** when the upstream sends a non-empty
  string (observed value: `"normal"`) — the key is **omitted** otherwise. Its
  exact thresholds are undocumented upstream, so the bridge never interprets
  it; clients treat it as the **authoritative** tier when present and
  non-`"normal"` (the server's word wins; local thresholds are only a
  fallback and may escalate but never downgrade the server's call).
- `source`: `"api"` — live from the OAuth usage endpoint — or `"cache"` —
  the API was unavailable (expired token, offline) and the bars come from
  Claude Code's own cached snapshot in `~/.claude.json`. `fetchedAtMs`
  (epoch ms) is present **only** when `source` is `"cache"`, so clients can
  render an "as of Xm ago" staleness line.
- Neither the API nor the cache yields data →
  `503 {"error": "usage unavailable: ..."}`.

## Commands — `POST /v1/command`

One authenticated endpoint, four mutually exclusive actions, dispatched in
this order:

**1. Spawn a session** — `{ "spawn": "claude" | "codex", "cwd"?: "/path" | "~" }`
→ `200 { "ok": true, "sessionId": "<uuid>", "agent": "claude" }`. Invalid
agent → `400`; spawn failure → `500`.

A **claude** spawn is *born in Zed-land*: the bridge routes it to the forked
adapter Zed launched (a new `spawn` frame down the `/acp/inbox` channel), which
creates a real ACP session — detached until an editor thread adopts it — and
the response carries the additive fields `kind: "acp"` and `spawnRequestId`.
There is deliberately **no PTY fallback**: with no adapter connected the spawn
answers `409 {"error": "No Zed agent connection — open Zed …"}` and creates
nothing. A spawn that outlives the bridge's wait (~10 s) still converges: the
adapter's own register announces the session over SSE with the same
`spawnRequestId`, so the client can attribute the late arrival to the spawn it
reported as failed. The session appears in Zed either via **New Thread** (the
adapter adopts the newest unclaimed watch-spawned session for that directory —
history replayed, live continuation) or Zed's *Import Threads*. Zed creates
the underlying ACP session lazily and sometimes swaps to an already-empty
thread without asking the adapter for anything, so the adoption fires on
whichever action makes Zed actually request a session — in practice: click
New Thread, and if the thread comes up empty, click it once more (or just
type; a sessionless draft materializes on first use). Codex spawns remain
bridge-owned PTYs.

`cwd` selects the new session's working directory (issue #56):

- an **absolute path to an existing directory** — the session spawns there
  (any valid directory from an authed device is allowed; validation is about
  error quality, not policy);
- the literal **`"~"`** — the "no project" sentinel, resolved by the bridge
  to its own user's home directory (the client cannot know that path);
- **omitted** — the bridge's historical default chain, unchanged: its CLI
  positional argument, then `$HOME`, then the bridge process cwd.

Anything else — a relative path, a file, a non-existent directory — is
refused with `400 {"error": "spawn cwd is not a directory: <cwd>"}` and **no
session slot is created** (an unvalidated target used to spawn a PTY that
died into an instantly-ended zombie session). The same `cwd` semantics apply
to the auto-spawn of action 4.

**2. Kill a session** — `{ "kill": true, "sessionId": "<uuid>" }` →
`200 { "ok": true }`; unknown id → `404 {"error": "No session with that ID"}`.

**3. Answer a permission** — see
[Permission decision semantics](#permission-decision-semantics):

```json
{ "permissionId": "<uuid>", "decision": { "behavior": "allow" } }
```

→ `200 { "ok": true }`; unknown/expired id → `404 {"error": "No pending
permission with that ID"}`.

When the prompt carried `agentOptions` (a rich ACP request — see the ACP
`permission-request` notes), the decision SHOULD also name the tapped option:

```json
{ "permissionId": "<uuid>", "decision": { "behavior": "allow-always", "optionId": "acceptEdits" } }
```

`optionId` is taken verbatim from the tapped `agentOptions` entry and wins
over `behavior` when both are present; `behavior` still rides along (derived
from the option's `kind`) so logs and behavior-keyed consumers stay honest.

**4. Send text to a session** — `{ "command": "fix the tests\n",
"sessionId"?: "<uuid>", "agent"?: "claude", "cwd"?: "/path" }`:

- With `sessionId` naming a PTY-backed session: the text is written to its
  stdin and **submitted** — text not already ending in a newline gains a
  terminating carriage return (the Enter keystroke), so a dictated prompt
  (the watch sends the bare transcription) is entered rather than left typed
  in the agent's input box (#86) → `200 { "ok": true, "sessionId": ...,
  "agent": ... }`.
- With `sessionId` naming an **ACP** (Zed-hosted) session: the text is injected
  into the LIVE session over the loopback channel → `200 { "ok": true,
  "sessionId": ..., "agent": ..., "prompt": true }`. A session whose adapter is
  not connected answers **502** so the client can keep the text as a draft.
- With `sessionId` naming any other PTY-less session (a Codex-scanner slot,
  or a bridge-owned session that has ended): **409**, and nothing is run. The
  bridge owns no input channel into it. This used to spawn `claude -p <text>
  --continue` — a detached headless FORK of the live session, concurrently
  editing the same working tree — which is why it was retired (#69/#81). The
  refusals are distinguished: an ended bridge-owned session is reported as
  ended, never mislabeled as external.
- Without `sessionId`: routed to the most recent active session, or
  **auto-spawns** one (`agent`, default `"claude"`).
  - **claude** composes the spawn of action 1 with the ACP injection above
    (there is no PTY auto-spawn left for it): the session is born in Zed's
    adapter and the dictated text is injected into it → `200 { "ok": true,
    "sessionId": ..., "agent": "claude", "kind": "acp", "spawnRequestId": ...,
    "spawned": true, "prompt": true }`. No adapter connected → the same honest
    `409` as action 1, with nothing created; an adapter that fails the spawn →
    `409` carrying its error and the `spawnRequestId`; a session created but no
    longer reachable when the prompt is written → `502` **naming the session**
    (`sessionId`, `spawned: true`), so the client can retry into it rather than
    treat it as lost.
  - **codex** keeps the bridge-owned PTY: the command is injected (with the
    same submit terminator as above) only after
    the new PTY produces output → `200 { "ok": true, "sessionId": ...,
    "agent": ..., "spawned": true }`, or `500` (with `sessionId`,
    `spawned: true`) when the agent never became ready — the failed session is
    killed, never left as a zombie target.
- Unknown `sessionId` → `404`.

None of the actions present → `400 {"error": "Missing 'command', 'spawn',
'kill', or 'permissionId'+'decision'"}`.

## Event stream — `GET /v1/events`

Server-Sent Events, authenticated. Headers: `Accept: text/event-stream`,
`Authorization: Bearer <token>`, optional `Last-Event-ID: <n>`.

Every event is framed as:

```
id: <monotonically increasing integer>
event: <type>
data: <one JSON object>
```

Payloads about a specific agent session carry `sessionId` (the slot id).
Comment lines (`:connected` on connect, `:heartbeat` every 10 s) must be
ignored.

**Replay.** The bridge keeps a 500-event ring buffer. A reconnect with
`Last-Event-ID: <n>` replays every buffered event with id > n. Events older
than the buffer are gone — clients needing full state should reconcile with
`GET /v1/status`.

**Connect-time snapshot.** On every connect the bridge also writes
authoritative current state: a `session` (`state: "running"`) event per
running session followed by one `session-sync` carrying the authoritative set
of running session ids, one `permission-sync` carrying the authoritative set of
live prompt ids, and a re-sent `permission-request` per pending prompt (so a
prompt evicted from the ring buffer can never be lost). A fresh client (no
`Last-Event-ID`) additionally receives up to the last 50 buffered
`pty-output`/`tool-output` events as terminal backlog. Consequence: clients
MUST handle duplicate delivery — deduplicate permissions by `permissionId`
and treat `session` events as idempotent state, not transitions.

Both sync frames exist for the same reason: a per-item re-send is ADDITIVE and
cannot tell a client to drop an item that DIED while it was offline (its
`permission-cleared` / `session ended` long since evicted from the ring
buffer). **Any sync that claims to describe current state is authoritative
about absence.** They sit on opposite sides of their re-sends, and that
ordering is deliberate in both directions:

- `permission-sync` comes **BEFORE** the per-prompt re-sends, which then carry
  the payloads for everything it kept (issue #63).
- `session-sync` comes **AFTER** the per-session re-sends, because for sessions
  the re-sends ARE the payloads: refreshing before pruning means no row ever
  blinks out and back. It also makes an interrupted snapshot harmless — a
  client that loses the connection mid-snapshot never receives the closing
  frame, so it never prunes against a half-told story (issue #66).

**Connection care.** Stalled clients (> 1 MiB unflushed) are destroyed and
expected to reconnect with replay; TCP keepalive probes run every 30 s.

## SSE event catalog

Unknown keys must be tolerated on every event.

### `permission-request`
An agent wants approval (blocking). ACP shape (raised from the Zed adapter's
teed request — see the [ACP notes](#permission-request-from-an-acp-session-80)
below):

```json
{
  "permissionId": "<uuid>",
  "tool_name": "Bash",
  "tool_input": { "command": "ls -la" },
  "options": [
    { "behavior": "allow-always", "label": "Always Allow" },
    { "behavior": "allow",        "label": "Allow" },
    { "behavior": "deny",         "label": "Reject" }
  ],
  "sessionId": "<slot uuid>"
}
```

- `options` is the server-normalized menu built from the agent's own option
  list; every option carries a machine-readable `behavior` (see
  [Permission decision semantics](#permission-decision-semantics)). Labels are
  the agent's own wording.
- **`AskUserQuestion`** prompts (content questions, not permission gates)
  carry **no top-level `options`**; render `tool_input.questions[]` instead —
  each `{header, question, options: [{label, description?}], multiSelect}` —
  and answer every question. They are raised via #111: the adapter mirrors
  its Zed form elicitation to the bridge; answering in Zed retracts the card
  via `permission-cleared`, exactly like a permission settled in Zed.
- **Codex synthetic approvals** (`source: "codex"`, `tool_name:
  "ExecApproval"`): top-level `options` present and mirrored in
  `tool_input.questions[0].options`; `tool_input` also carries `command` and
  `workdir`.
- May be re-delivered on reconnect (connect-time snapshot); deduplicate by
  `permissionId`.

### `permission-cleared`
The prompt identified by `permissionId` is void — dismiss it.
`{ "permissionId": "<uuid>", "reason": "hook-aborted" | "expired" | "resolved" | "...", "sessionId": ... }`

`reason` is an OPEN set; an unrecognised value must never fail the frame (the
drop is the contract, the wording is a courtesy). Known values:

- `hook-aborted` — the request was settled somewhere else (answered in Zed,
  withdrawn by the agent, or its session ended); nothing left to say. The
  wire value predates the retired hook channel and is kept frozen for
  existing clients.
- `expired` — the bridge's window closed without a decision reaching it. The
  bridge returned NO DECISION: nothing was allowed and nothing was denied
  here — the agent's own prompt (Zed's dialog) keeps the answer. A client
  MUST NOT tell the user the prompt went "unanswered" or must still be
  answered; whatever the outcome, it lives on the computer, which is all a
  client can honestly say. ACP-raised prompts never send this: they carry no
  timer, because retracting a card whose request is still open leaves the
  agent blocked with nothing on the wrist to answer.
- `resolved` — a Codex synthetic approval was answered.

The bridge never fabricates a decision. A `deny` in a bridge log is always a
decision a human made.

### `permission-sync`
The authoritative set of live prompt ids, emitted on every connect (see
Connect-time snapshot). RETRACTION ONLY — drop every pending prompt whose id
is absent; never create one (payloads arrive as `permission-request`). An
empty list is legal and meaningful ("nothing is live").
`{ "permissionIds": [ "<uuid>", ... ], "sessionId": ... }`

### `session`
Lifecycle of agent sessions. Variants:

- `{ "state": "connected" }` — a device paired with the bridge (no
  `sessionId`; bridge-level).
- `{ "state": "running", "agent": "claude", "cwd": "/home/u/proj",
  "folderName": "proj", "sessionId": ... }` — session started / observed
  (also re-sent on every SSE connect for each running session, and re-sent
  live when the session's `title` changes).
- `{ "state": "ended", "agent": ..., "folderName": ..., "sessionId": ... }` —
  plus, depending on how it ended: `exitCode` and `signal` (PTY exit),
  `error` (spawn failure), `killed: true` (kill command), or `reason`
  (`"session-end"`, `"evicted"`, `"acp-fork-disconnected"`, ...).

Two `reason` values are **ageing**, not observed deaths (issue #65) — the
bridge ends a slot stuck in `running` that it has no evidence is alive, rather
than announcing it as an active session forever:

- `"host-gone"` — the process hosting the session is demonstrably gone (an ACP
  slot whose fork connection has no live inbox).
- `"no-liveness"` — nothing could speak for the session at all (a
  Codex-scanner slot: no process handle, no connection) and it has been
  silent, with an untouched transcript, for the whole (generous) window.

Both are honest about their uncertainty and neither is authoritative: they say
"no evidence", not "it is dead", so the session's next sign of life **revives**
the slot exactly as after a watch kill. A session with observable liveness is
never aged out, however long it has been idle — an ACP session whose fork still
holds its connection is alive by definition, and so is a bridge-owned PTY whose
process is running.

**`title`** (string, **optional, additive**): the session's human-readable
title, carried on `running`/`ended` payloads (and the `/v1/status` and pair
snapshots) once the bridge has one. Absent until then (`codex` sessions never
have one); per the additive-field rules this does not bump the protocol
version, and clients fall back to their own label when it is absent.

The title arrives from the Zed adapter, which polls the CLI
transcript's `customTitle` (the SDK folds a user `/rename` and its
auto-generated title into that one field) and pushes `session_info_update`;
the bridge re-announces on every change, mid-session included. A manual
thread rename in **Zed's UI** never reaches this path: nothing crosses ACP
on a UI rename — Zed persists it purely in its own thread-metadata store
(`title_override` in `crates/agent_ui/src/thread_metadata_store.rs`; its
external-agent connection implements no `set_title`, and the ACP protocol
has no client→agent title method at all — issue #112's wire investigation).
So Zed's local label and the wrist's can diverge after a UI rename, in that
direction only; the reverse is safe, because Zed's `title_override` outranks
agent-pushed titles in its own UI. The wrist-visible rename for a Zed thread
is the in-thread **`/rename`** slash command, which lands in `customTitle`
and propagates on the next turn end.

**`external`** (boolean, **optional, additive**): `true` for a session the
bridge does not own the process of — an ACP session, hosted by Zed's forked
adapter. Carried uniformly on EVERY session event of such a slot
(`running`/`ended` and the connect-time sync), and OMITTED entirely for
bridge-owned PTY slots. Clients must treat its absence as `external: false`
(a PTY session is killable with the `kill` command); an ACP session is
external AND really killable — its kill rides the adapter's own teardown
(see [Killing an ACP session](#killing-an-acp-session-88)). Additive: this
does not bump the protocol version and older clients ignore it.

**`idle`** (boolean, **optional, additive** — issue #60): PRESENT (`true`)
when the bridge's **last lifecycle signal** for the session was a turn **end**
— an ACP turn boundary, or a Codex `task_complete`. **OMITTED** when the bridge considers the
session to be producing work (tool output, PTY output, a dictated prompt run),
and also, necessarily, by any bridge predating this field. Carried uniformly on
EVERY session event of the slot (`running`/`ended`, the metadata refresh, and
the connect-time sync) and mirrored on `sessions[].idle` in `/v1/status`.

Absence therefore means **"working, or an older bridge"** — never "preserve
what you knew". This field is the one exception to the absent-means-preserve
doctrine that governs `title`/`external`/`branch`, because unlike those it is a
*dynamic* state that flips both ways. Clients MUST therefore consume it in one
direction only: a present `true` may mark the session idle (both when first
learning of it and on a later re-send), while its **absence must never wake a
session up**. The asymmetry matters — the connect-time re-send arrives on every
reconnect, so treating it as a wake signal would restart elapsed clocks
routinely, whereas treating a present `true` as an idle signal only freezes a
span that had in fact already stopped. Live `task-complete`/output events and
the idempotent `session` push at an ACP turn boundary remain the authority
for every other transition.

The field exists because `state` cannot express it: a turn ends per TURN and
must NOT end a session, so an idle session stays `state: "running"` forever.
Before this flag, a client seeing such a session for the first time had to
guess WORKING — and the event that had idled it hours earlier was long gone
from the SSE replay ring, so nothing ever corrected the guess. Additive: no
protocol-version bump, and older clients ignore it.

The connect-time `session-sync` carries the same truth as a **tri-state** per
entry, because a snapshot is a description of current state rather than a
transition: there, `idle: false` is said out loud and an OMITTED verdict means
"no turn signal ever observed" — which clients render idle, not green. See
[`session-sync`](#session-sync).

A `kill` on a Codex-scanner session is best-effort and non-authoritative: the
bridge marks the slot `ended`, but if the scanner observes the still-alive
session write again the bridge **revives** it — re-broadcasting the
idempotent `running` event (and clearing the zombie `ended` state) rather
than swallowing the observation. Only an authoritative end (an ACP
deregister, or a bridge-owned PTY exit) is final and never revives.

**`branch`** / **`worktree`** / **`repoRoot`** (**optional, additive** — issue
#54): git metadata of the session's project root, derived from **file reads
only** (never a spawned `git`): the root's `.git` directory (main checkout) or
`.git` pointer file (linked worktree, `gitdir: …/.git/worktrees/<name>`), and
the applicable `HEAD` file. `branch` is the branch name (a detached HEAD
yields the 7-char short sha). `worktree: true` and the main repo's `repoRoot`
are present **only** when the pointer target matches the
`…/.git/worktrees/<name>` structure exactly — any other layout (submodule,
relocated gitdir) yields at most `branch`, never a guessed `repoRoot`. Clients
group a session under `basename(repoRoot)` when present. Absent fields mean
**preserve what you knew** (the `title` doctrine): a non-git root or an
unreadable HEAD never clears previously-broadcast values — with one
refinement: the trio travels as **one atomic group keyed on `branch`**. A
payload carrying `branch` is authoritative for all three (the bridge always
re-derives them together, emitting `worktree`/`repoRoot` iff true), so a
branch-bearing payload that omits `worktree`/`repoRoot` DROPS a
previously-known worktree claim — that is how a session rebound from a
worktree onto its main checkout sheds the stale `wt` badge. Only a payload
with no `branch` at all preserves the whole trio. Refreshed at opportunistic
points (session creation, an ACP re-register, a PTY re-attach); a change is
broadcast as the idempotent `running` event.

**`agents`** (object `{ "running": n, "done": n }`, **optional, additive** —
issue #55): multi-agent workflow activity observed for this session. The
bridge learns a workflow **started** from the wire that names the Workflow
tool — the teed ACP `tool_call` (issue #105) —
and then watches the session's workflow journals on a slow poll; `running`
counts agents started without a result, `done` counts completed agents of
currently-live workflows. Completion is signaled by the **explicit**
`{"running": 0, "done": n}` broadcast — absence, as everywhere, means
preserve, so omission can never clear the indicator. A **transient** `running:
0` in the gap between the phases of a multi-phase workflow is held, not
broadcast — it is indistinguishable from real completion in the journal, so
the indicator clears only once the whole workflow tree has gone quiet for
~5 min (issue #70). "Quiet" is the newest write across a workflow's
`journal.jsonl` **and** its agents' transcripts — `journal.jsonl` alone only
moves on an agent start/finish, so a long single-agent phase would look dead
mid-run. This same staleness retires a killed workflow's stuck indicator. A
bridge restarted mid-workflow loses its in-memory arming, but re-derives
`agents` from the on-disk journal when the surviving session re-registers —
re-arming a still-live workflow, or broadcasting the explicit zero for one that
finished during the downtime — so a re-registering session's stale blue is
corrected rather than stranded (issue #68). Clients should render
an indicator only while
`running > 0`, and must not offer any control affordance (a workflow cannot
be stopped from a client).

**`model`** / **`mode`** / **`contextPct`** (**optional, additive** — issue
#97, Halo v2): the session subheading's `model · mode · use%`, carried on
every session event of a slot that has them (`running`/`ended`, the
idempotent refresh, the connect-time sync) and mirrored on
`sessions[].model` / `sessions[].mode` / `sessions[].contextPct` in
`/v1/status`. Only **ACP** (Zed-hosted) sessions ever have them — the PTY
and Codex paths carry no equivalent signal, so those sessions simply omit all
three. Absent means **preserve what you knew** (the `title` doctrine); per
the additive-field rules there is no protocol-version bump and older clients
ignore them.

- `model` (string): the **human display name** of the session's current
  model (e.g. `"Opus"`), as the agent's model picker names it — the
  `default` alias is resolved through the model it currently points at
  before falling back to `"Default"`. Third-party backends can yield a raw
  model id here; the bridge passes it verbatim rather than guessing.
- `mode` (string): the ACP permission-mode id **verbatim** (`default` /
  `plan` / `acceptEdits` / `bypassPermissions` / …). Clients wanting a label
  map the ids they know and show the id itself otherwise — the vocabulary is
  the agent's and may grow.
- `contextPct` (integer 0–100): percent of the model's context window
  **USED**, same direction as `/v1/usage`. Seeded at registration from the
  adapter's context accounting and re-announced **only when the integer
  changes** — mid-turn usage streams once per message, but sub-percent
  motion never produces an event. `0` is a real value (a fresh session), so
  clients must key on the field's presence, not its truthiness.

### `session-sync`
The authoritative set of RUNNING sessions, emitted at the END of every
connect-time snapshot (see Connect-time snapshot) — issue #66.

```json
{ "sessions": [ { "id": "<slot uuid>", "idle": true }, ... ], "complete": true }
```

PRUNING ONLY — drop every session whose id is absent; never create one
(payloads arrive as `session` events immediately before). An empty list is
legal and meaningful ("the bridge has nothing running").

Each entry's `idle` is the slot's turn-level truth as a **tri-state** (issue
#60), which the `session` payload's own `idle` deliberately cannot express:

| entry | meaning |
|---|---|
| `"idle": true` | the last lifecycle signal was a turn END |
| `"idle": false` | a turn is in flight — the session really is working |
| omitted | no turn signal has ever been observed for this slot |

On a `session` event the flag is present-only-when-true, so "working" and "the
bridge has no idea" arrive as the same absence and a client meeting the session
for the first time has to guess — which is exactly how a session idle for three
hours rendered green on a freshly-paired watch. A sync DESCRIBES current state,
so it says all three out loud. Clients must render the omitted case as **idle**:
it is not a claim of work, and a session that really is working re-marks itself
on its very next event. (The one-way latch on `session.idle` is unchanged; it
still governs every live event.)

`complete: true` is the bridge's claim that the list describes its WHOLE
session set, and it is what licenses the pruning. A sync that cannot make that
claim (a future paged snapshot, a relay forwarding one bridge of several) MUST
omit the field, and clients MUST then treat the frame as informational and drop
nothing — a partial sync is exactly the state in which dropping is most wrong.
Clients must likewise treat an unparseable frame as no sync at all.

This exists because the per-session re-send is additive and cannot tell a
client to drop a session the bridge FORGOT — a restart wiping its in-memory
map, a crash, an external-session cap eviction — and a bridge that has
forgotten a session is definitionally unable to emit its `ended` event. Before
this frame, every bridge restart orphaned that bridge's whole session set on
every connected client, permanently, rendered green because their last known
activity was WORKING.

Deliberately hidden sessions (the honest hide of #53) live in a client's own UI
state, not in this set: the frame carries no `sessionId`, is not the session
"speaking", and must never un-hide one.

### `pty-output`
Raw terminal output from a bridge-owned PTY (ANSI escapes included) or from a
`{ "text": "...", "sessionId": ... }`.

### `tool-output`
A completed tool use observed by the Codex rollout scanner: `tool_name`,
`tool_input`, `tool_output`, plus `source` (`"codex"`) and `sessionId`.

### `permission-request` from an ACP session (#80)
An ACP session's permission requests reach the wrist through the
`permission-request` / `permission-cleared` events and the `POST
/v1/command` answer path described above — clients need no ACP-specific
handling.

Two behaviours are specific to ACP and worth knowing:

- **Two surfaces, one decision.** Zed shows its own dialog for every request;
  the wrist shows the same one. Whichever answers first wins. If Zed wins, the
  bridge pushes `permission-cleared` and the wrist card disappears; if the
  wrist wins, the agent cancels Zed's dialog.
- **A prompt is only raised if a client is connected**, and only if at least
  one of the agent's options maps to a machine-readable `behavior`. An
  unmappable option is dropped rather than guessed at.
- **Rich option lists (#110).** The canonical `options` menu is
  behavior-keyed, and an agent may offer SEVERAL options with the same
  behavior (Zed's ExitPlanMode approval carries up to three `allow_always`
  mode switches). Electing one silently would make the canonical button a
  roulette, so an ambiguous behavior's canonical button is **dropped**
  (absence beats roulette; unambiguous behaviors keep exact buttons) and the
  agent's own list rides alongside, additively:

  ```json
  "agentOptions": [
    { "optionId": "acceptEdits", "label": "Yes, and auto-accept edits", "kind": "allow_always" },
    { "optionId": "default", "label": "Yes, and manually approve edits", "kind": "allow_once" },
    { "optionId": "plan", "label": "No, keep planning", "kind": "reject_once" }
  ]
  ```

  Present EXACTLY when the canonical flattening is lossy (some behavior had
  more than one option); a simple allow/deny prompt keeps today's wire shape,
  so clients that predate the field keep today's card everywhere. Entries are
  the agent's options verbatim — agent order, label + `optionId` + ACP `kind`
  (`allow_once` / `allow_always` / `reject_once` / `reject_always`; options
  with other kinds or no `optionId` are dropped, never guessed at). A client
  that renders `agentOptions` answers with the tapped entry's `optionId` on
  the decision (see `POST /v1/command` action 3), which the bridge forwards
  to the agent verbatim; a client that ignores the field still answers safely
  through the surviving canonical buttons.

Expiry keeps the no-decision semantics: nothing is sent back to the agent, so
Zed's own dialog keeps the answer. The bridge never fabricates a `deny`.

### `message`
Assistant prose from an ACP (Zed-hosted) session: `{ "role": "assistant",
"text": "...", "sessionId": ... }`. Additive in proto 3 — clients ignore
unknown events, so an older watch is unaffected.

Sourced from the ACP `agent_message_chunk` update, which is **assistant-only**:
the adapter emits no `user_message_chunk`, so a client's own local echo stays
the single authority for the user's dictated text and there is no double-echo.
Only ACP sessions produce this.

**Coalesced, not streamed.** ACP delivers prose as dozens of small deltas per
turn; one frame each would be that many radio wakeups on a watch. The bridge
buffers them and emits ONE `message` carrying the last block, flushed at:

| flush point | why |
|---|---|
| turn end (`kind: "turn"`, `phase: "end"`) | the report — what the agent finished saying |
| permission request (`kind: "permission"`) | a pause needing an answer: the user needs the context that led to it |

A `tool_call` update **resets** the buffer: narration before a tool is
superseded by whatever is said after it, so a flush carries the last block
rather than a transcript of the whole turn. Buffer is capped at 4000 chars
(tail kept). Clients wanting live token-by-token output should not use this
event.

### `stop` (retired emitter; clients keep the handler)
No current bridge lane emits `stop` — it was the retired hook channel's
turn-end event (#87). An ACP session's turn end arrives instead as one
idempotent **`session` running event carrying `idle: true`** (ACP carries no
turn-end update variant, so the fork forwards the boundary over the
server-local `/acp/update` channel and the bridge folds it into the flag), so
an already-connected client learns the turn ended without a new event type.
Clients that still fold a received `stop` into their idle state lose nothing.

### `task-complete`
A long-running task finished (Codex `task_complete`). Body plus `sessionId`.

### `error`
An error the agent surfaced: `{ "error": "...", ..., "sessionId": ... }`.

## Permission decision semantics

Approve/deny meaning is machine-readable end-to-end; clients never infer it
from option position or label wording.

`behavior` values:

| `behavior` | Meaning |
|---|---|
| `allow` | approve this request once |
| `allow-always` | the standing grant: forwarded to the agent as its own `allow_always` option (ACP) or trust entry (Codex); the legacy `allowAll` path |
| `deny` | reject the request |

Decision request (`POST /v1/command`):

```json
{ "permissionId": "<uuid>", "decision": { "behavior": "deny", "message": "not on my watch" } }
```

- `decision.behavior` — required, one of the table above. Echo the behavior
  of the option the user chose; never send a behavior that was not offered.
- `decision.optionId` — the tapped `agentOptions` entry's id, when the prompt
  carried that list (#110 — see the ACP `permission-request` notes). Wins
  over `behavior` for the option forwarded to the agent; never invent one.
- `decision.message` — optional, forwarded to the agent on `deny`.
- **AskUserQuestion answers:** send `answers` (top-level or inside
  `decision`) as an array aligned with `tool_input.questions` — or an object
  keyed by question text — answering **every** question:

```json
{ "permissionId": "<uuid>", "decision": { "behavior": "allow" }, "answers": ["Blue", "Tabs"] }
```

- Codex synthetic approvals accept the same behaviors (`allow-always`
  degrades to `allow` when the menu offers no trust entry).

An ACP-raised prompt (a permission or a question card) lives exactly as long
as the request it mirrors: it is registered even when **no client is
streaming** — the connect-time snapshot replays it, so a watch whose stream
was down when the agent asked still gets the card — and it carries **no expiry
timer**. It is retracted only when the request settles: the fork sends
`permission-resolved`/`input-resolved` on every exit (answered in Zed, turn
cancelled, client failure), and a fork that dies without sending one drops its
inbox, which cancels its sessions' cards. Either way the retraction reaches
clients as `permission-cleared` and **no decision** is sent to the agent.

Prompts from a lane with no retraction channel (the Codex synthetic menu)
still expire after ~9.5 min with no decision, reason `expired`.

## ACP uplink (server-local)

`POST /acp/{register, update, deregister, spawn-result, claim}` plus the held
`GET /acp/inbox` SSE (also reachable under `/v1/`) is how the forked
`claude-agent-acp` Zed launches feeds the bridge **on the same machine**: it
registers/updates/deregisters its sessions, tees session updates and
permission requests, and holds the inbox the bridge writes
`inject`/`permission-decision`/`input-decision`/`spawn`/`close` frames down.
It is unauthenticated but **loopback-only** (`403 {"error": "ACP endpoints
are only accepted from localhost"}` for any non-loopback source). Watch
clients never call it, and it is NOT part of this versioned client protocol —
it is documented here because it is the stimulus behind the ACP-originated
SSE payloads above. Permission state is shared across surfaces: a
`/v1/command` decision resolves a prompt raised on the uplink.

## Admin surface (server-local, operator)

`POST /admin/pairing/open`, `GET /admin/devices` and `POST /admin/devices/revoke`
are **operator tooling on the running bridge**, not part of this versioned client
protocol — a watch client never calls them, and `PROTOCOL_VERSION` does not gate
them. They are
**loopback-only** (same operator-on-machine trust as the ACP uplink: no
bearer token; a non-loopback source gets `403 {"error": "Admin endpoints are
only accepted from localhost"}`), because the running bridge owns the token set
in memory and rewrites `credentials.json` on every change — a separate CLI
editing the file would race it. Documented here so the operator surface is
discoverable, but it carries no protocol-version or legacy-freeze guarantees and
may change without a version bump.

- `POST /admin/pairing/open` → `200 {"ok":true,"code":"<6 digits>","expiresInMs":<ttl>}`
  opens the single-use pairing window — the "initialise pairing" control, doing
  exactly what `SIGUSR1` does but as a loopback call instead of a signal. The
  code-less **Discover** pair needs only the open window and ignores `code`; the
  code is returned for the **Manual** path (and so the operator need not grep the
  log — a pairing code on a loopback-only surface is operator-privileged). The
  window stays single-use: the next successful pair relocks it.
- `GET /admin/devices` → `200 {"devices":[{"id","deviceName","createdAt","surface"}]}`.
  `id` is the **first 12 hex of the credential's SHA-256 hash** — a prefix that
  disambiguates and targets a revoke while keeping the token *and* the full hash
  off the wire. Never a token, never the full 64-hex hash.
- `POST /admin/devices/revoke {"id":"<hex prefix>"}` → `200 {"ok":true,"revoked":"<deviceName|id>"}`
  removes the device whose hash starts with `id`, persists the store, and
  immediately stops that token authenticating (and drops its live SSE stream).
  Refusals remove nothing: ambiguous prefix (≥2 matches) → `400`; unknown prefix
  → `404`; missing/short/non-hex `id` → `400`. The `revoked` value is the device
  name or the short id — never a token or hash.
- `POST /admin/devices/revoke {"all":true}` → `200 {"ok":true,"revoked":<count>}`
  empties the store and force-drops every live SSE stream. It does **not** reopen
  pairing (the operator still `SIGUSR1`s); the emptied store fails closed to
  LOCKED on the next restart (see ARCHITECTURE.md).

Reachable under `/v1/` too (the prefix fallback), harmlessly and still
loopback-gated — but the canonical paths are unprefixed.

## Legacy surface (frozen)

For existing iOS/watchOS clients; **never changes**. Differences from `/v1`:

- `POST /pair` performs **no protocol-version check** (a body with any
  `proto`, or none, pairs) and its success response carries the top-level
  `sessionId` alias (= `bridgeId`) and **no `proto`** field.
- `GET /status` keeps the top-level `sessionId` alias.
- Permission decisions: `allowAll: true` beside `decision` ≡
  `behavior: "allow-always"`; a single `selectedOption` string answers only
  the first `AskUserQuestion` question.
- Everything else (routes, shapes, status codes, SSE framing) is shared with
  `/v1` today; `options` on `permission-request` and the `notification` event
  are additive and invisible to legacy decoders.
- One deliberate hardening exception to the freeze: `GET /status` requires
  the bearer token (its snapshot enumerates project paths).

The frozen corpus in `test/fixtures/legacy-corpus.json` is the proof: it
replays against every build, so a bridge change that would break a legacy
client fails the suite.

## Fixture corpus

- `test/fixtures/v1-corpus.json` — the `/v1` contract described here.
- `test/fixtures/legacy-corpus.json` — the legacy freeze proof.

Both are recorded from a **real bridge process** by
`test/protocol-fixtures.test.js` (volatile values — tokens, UUIDs, hostname,
timestamps — normalized to stable placeholders like `<token>`,
`<bridge-id>`, `<session-1>`) and replayed green on every `npm test`.

Regenerate — only after a deliberate `/v1` contract change (the legacy corpus
is frozen for CLIENT-visible shapes; a legacy diff means you broke the freeze
— its one recorded exception is #87's removal of the server-local hook steps,
whose stimulus role the ACP uplink now plays):

```sh
CLAUDE_WATCH_UPDATE_FIXTURES=1 node --test test/protocol-fixtures.test.js
```

Review the fixture diff like an API review, update this document, and bump
`PROTOCOL_VERSION`/`MIN_SUPPORTED_CLIENT_PROTO` if the change is breaking.

## Killing an ACP session (#88)

`{ "kill": true, "sessionId": ... }` (action 2 above) means different work for
the two species of session, and the difference is visible in the response:

- A **bridge-owned PTY** session is stopped directly → `200 { "ok": true }`,
  unchanged.
- An **ACP** (Zed-hosted) session runs inside the adapter's process, which the
  bridge cannot signal. It relays a `close` frame down that fork's `/acp/inbox`
  instead; the adapter runs the same teardown its own `session/close` does, and
  the **deregister that teardown emits is the only ack** — so the `session`
  `ended` event a client sees is the fork's real ending, never a bridge
  fabrication. The response waits for that ending:
  - ended → `200 { "ok": true, "sessionId": ..., "kind": "acp" }`;
  - no adapter connected → `502 {"error": "ACP session is not reachable …
    nothing was stopped", "sessionId": ...}`;
  - the frame went out but nothing ended within ~10 s (an adapter build too old
    to know the frame drops it silently) → `504 {"error": "Zed's agent did not
    end the session …", "sessionId": ...}`.

Both refusals leave the session **exactly as it was** — still `running`, still
`dictatable`. That is issue #53's doctrine at the wire: a kill the bridge
cannot perform must never look performed, because a slot marked ended under a
live agent goes on absorbing its events invisibly. Clients should offer a real
kill only where one exists — a bridge-owned PTY, or an ACP session; a
Codex-scanner session's process is one nothing in this system can stop.

An already-ended ACP slot needs no frame: it answers `200` directly, since
marking an over session over invents nothing.
