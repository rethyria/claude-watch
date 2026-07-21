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
- [Hook surface (server-local)](#hook-surface-server-local)
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
- **`/hooks/*` is loopback-only** (see [Hook surface](#hook-surface-server-local)):
  non-loopback sources get `403 {"error": "Hooks are only accepted from
  localhost"}` before the body is read.
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
`/hooks/*` requires:

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
  "hasPty": true,
  "activeAgent": "claude"
}
```

- `state`: `"idle"` (never paired) | `"connected"` (at least one device
  paired).
- `sessions[].state`: `"running"` | `"ended"`. Ended sessions linger in
  snapshots for a grace period (~5 min), then get pruned.
- `sessions[].title` (string, **optional, additive**): the session's
  human-readable title, present only once the bridge has derived it from the
  session's Claude Code transcript (see the [`session`](#session) event for
  the derivation order). Clients must tolerate its absence.
- `sessions[].external` (boolean, **optional, additive**): `true` only for a
  hook-created (external, PTY-less) session whose process the bridge does not
  own; **omitted** for bridge-owned PTY slots. Clients must treat its absence
  as `external: false` (killable). See the [`session`](#session) event.
- `sessions[].idle` (boolean, **optional, additive**): `true` when the
  session's last lifecycle signal was a turn end (`Stop`/`TaskCompleted`);
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

**4. Send text to a session** — `{ "command": "fix the tests\n",
"sessionId"?: "<uuid>", "agent"?: "claude", "cwd"?: "/path" }`:

- With `sessionId` naming a PTY-backed session: the text is written to its
  stdin → `200 { "ok": true, "sessionId": ..., "agent": ... }`.
- With `sessionId` naming an external (hook-created, PTY-less) session: the
  bridge runs the agent CLI headlessly in that session's cwd (`claude -p
  <text> --continue` / `codex exec <text>`), streaming output as `pty-output`
  events → `200 { "ok": true, "sessionId": ..., "agent": ..., "prompt": true }`.
- Without `sessionId`: routed to the most recent active session, or
  **auto-spawns** one (`agent`, default `"claude"`); the command is injected
  only after the new PTY produces output → `200 { "ok": true, "sessionId":
  ..., "agent": ..., "spawned": true }`, or `500` (with `sessionId`,
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
running session, one `permission-sync` carrying the authoritative set of live
prompt ids, and a re-sent `permission-request` per pending prompt (so a prompt
evicted from the ring buffer can never be lost). A fresh client (no
`Last-Event-ID`) additionally receives up to the last 50 buffered
`pty-output`/`tool-output` events as terminal backlog. Consequence: clients
MUST handle duplicate delivery — deduplicate permissions by `permissionId`
and treat `session` events as idempotent state, not transitions. The
`permission-sync` frame is emitted BEFORE the per-prompt re-sends, and it
RETRACTS: drop every pending prompt whose id it omits (the re-sends then carry
the payloads for everything it kept). This exists because the per-prompt
re-send is additive and cannot tell a client to drop a prompt that DIED while
it was offline — its `permission-cleared` long since evicted from the ring
buffer (issue #63).

**Connection care.** Stalled clients (> 1 MiB unflushed) are destroyed and
expected to reconnect with replay; TCP keepalive probes run every 30 s.

## SSE event catalog

Hook-originated payloads are the hook's JSON body **plus** the
bridge-injected fields noted below; unknown keys must be tolerated on every
event.

### `permission-request`
An agent wants approval (blocking). Claude Code shape:

```json
{
  "permissionId": "<uuid>",
  "tool_name": "Bash",
  "session_id": "<claude code's own session uuid>",
  "cwd": "/home/u/proj",
  "tool_input": { "command": "ls -la" },
  "permission_suggestions": [ ... ],
  "options": [
    { "behavior": "allow",        "label": "Yes", "description": "Allow this once" },
    { "behavior": "allow-always", "label": "Yes, don't ask again", "description": "Allow and apply the suggested permission rules" },
    { "behavior": "deny",         "label": "No",  "description": "Deny this request" }
  ],
  "sessionId": "<slot uuid>"
}
```

- `options` is the server-normalized menu; every option carries a
  machine-readable `behavior` (see
  [Permission decision semantics](#permission-decision-semantics)).
  `allow-always` is offered only when `permission_suggestions` exist.
- **`AskUserQuestion`** prompts (content questions, not permission gates)
  carry **no top-level `options`**; render `tool_input.questions[]` instead —
  each `{header, question, options: [{label, description?}], multiSelect}` —
  and answer every question.
- **Codex synthetic approvals** (`source: "codex"`, `tool_name:
  "ExecApproval"`): top-level `options` present and mirrored in
  `tool_input.questions[0].options`; `tool_input` also carries `command` and
  `workdir`.
- May be re-delivered on reconnect (connect-time snapshot); deduplicate by
  `permissionId`.

### `permission-cleared`
The prompt identified by `permissionId` is void — dismiss it.
`{ "permissionId": "<uuid>", "reason": "hook-aborted" | "answered-elsewhere" | "expired" | "resolved" | "...", "sessionId": ... }`

`reason` is an OPEN set; an unrecognised value must never fail the frame (the
drop is the contract, the wording is a courtesy). Known values:

- `hook-aborted` — the agent withdrew the request; nothing to say.
- `answered-elsewhere` — a `PostToolUse`/`PostToolUseFailure` for this prompt's
  `tool_use_id` proved the tool ran, so it was approved on the computer.
- `expired` — the bridge's window closed without a decision reaching it. The
  bridge returned NO DECISION: nothing was allowed and nothing was denied here.
  This one reason covers three outcomes the bridge cannot tell apart — a genuine
  no-answer (the agent's own prompt is still live on the computer), a DENY made
  in the IDE (Claude Code fires no `PostToolUse` for a tool it never ran, so the
  deny never reaches the bridge), and an approve-and-long-run whose `PostToolUse`
  lands after the window. A client MUST NOT tell the user the prompt went
  "unanswered" or must still be answered; whatever the outcome, it lives on the
  computer, which is all a client can honestly say.
- `resolved` — a Codex synthetic approval was answered.

The bridge never fabricates a decision. A `deny` in a bridge log or a hook
response is always a decision a human made.

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
  (`"session-end"`, `"evicted"`, ...).

**`title`** (string, **optional, additive**): the session's human-readable
title, carried on `running`/`ended` payloads (and the `/v1/status` and pair
snapshots) once the bridge can derive it. Derivation order, from the Claude
Code transcript that hook payloads reference via `transcript_path`:

1. the **last** `{"type": "ai-title", "aiTitle": "…"}` record in the
   transcript (Claude Code re-emits it as the title evolves);
2. otherwise the first real user prompt, truncated to ~60 chars.

The field is absent until a hook event has pointed the bridge at a readable
transcript that yields a title (external `codex` sessions, unreadable or
empty transcripts, and PTY sessions that have not emitted a hook yet have
none). The title is refreshed opportunistically (session creation, `Stop`,
`SessionEnd` hooks); a mid-session change is broadcast as a re-sent
idempotent `running` event. Per the additive-field rules this does not bump
the protocol version; clients fall back to their own label when it is
absent.

**`external`** (boolean, **optional, additive**): `true` for a HOOK-CREATED
(external, PTY-less) session the bridge does not own the process of — it was
observed via hooks, not spawned into a bridge PTY. Carried uniformly on
EVERY session event of such a slot (`running`/`ended` and the connect-time
sync), and OMITTED entirely for bridge-owned PTY slots. Clients must treat
its absence as `external: false`: a PTY session is killable (`kill` command),
whereas an external session has no bridge-owned process to stop, so a client
should offer an honest "hide from view" instead of a kill. Additive: this
does not bump the protocol version and older clients ignore it.

**`idle`** (boolean, **optional, additive** — issue #60): PRESENT (`true`)
when the bridge's **last lifecycle signal** for the session was a turn **end**
— a `Stop` or `TaskCompleted` hook. **OMITTED** when the bridge considers the
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
span that had in fact already stopped. Live `stop`/`task-complete`/output
events remain the authority for every other transition.

The field exists because `state` cannot express it: `Stop` fires per turn and
must NOT end a session, so an idle session stays `state: "running"` forever.
Before this flag, a client seeing such a session for the first time had to
guess WORKING — and the `stop` that had idled it hours earlier was long gone
from the SSE replay ring, so nothing ever corrected the guess. Additive: no
protocol-version bump, and older clients ignore it.

A `kill` on an external session is therefore best-effort and non-authoritative:
the bridge marks the slot `ended`, but if the still-alive process emits another
hook the bridge **revives** it — re-broadcasting the idempotent `running` event
(and clearing the zombie `ended` state) rather than swallowing the event.
Only an authoritative end (the `SessionEnd` hook, or a bridge-owned PTY exit)
is final and never revives.

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
with no `branch` at all preserves the whole trio. Refreshed at the same
opportunistic points as `title`; a change is broadcast as the idempotent
`running` event.

**`agents`** (object `{ "running": n, "done": n }`, **optional, additive** —
issue #55): multi-agent workflow activity observed for this session. The
bridge learns a workflow **started** from the Workflow tool's PostToolUse hook
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
bridge restarted mid-workflow misses the launch hook and
shows no indicator — accepted. Clients should render an indicator only while
`running > 0`, and must not offer any control affordance (a workflow cannot
be stopped from a client).

### `pty-output`
Raw terminal output from a bridge-owned PTY (ANSI escapes included) or from a
headless prompt run: `{ "text": "...", "sessionId": ... }`.

### `tool-output`
A completed tool use, forwarded from the PostToolUse hook: hook body (e.g.
`tool_name`, `tool_output`, `cwd`, `session_id`) plus `source`
(`"claude"`/`"codex"`) and `sessionId`.

### `stop`
The agent finished a turn and is idle (fires per turn — NOT session end).
Hook body plus `sessionId`.

### `notification`
Claude Code Notification hook events, always with a `notification_type` key
(string or `null`; e.g. `"permission_prompt"`, `"idle_prompt"`) so clients
can render "waiting on you" instead of "stopped". Body plus `sessionId`.

### `task-complete`
A long-running task finished (TaskCompleted hook / Codex `turn.completed`).
Body plus `sessionId`.

### `error`
An error the agent surfaced: `{ "error": "...", ..., "sessionId": ... }`.

## Permission decision semantics

Approve/deny meaning is machine-readable end-to-end; clients never infer it
from option position or label wording.

`behavior` values:

| `behavior` | Meaning |
|---|---|
| `allow` | approve this request once |
| `allow-always` | approve AND persist the hook's `permission_suggestions` (the legacy `allowAll` path) |
| `deny` | reject the request |

Decision request (`POST /v1/command`):

```json
{ "permissionId": "<uuid>", "decision": { "behavior": "deny", "message": "not on my watch" } }
```

- `decision.behavior` — required, one of the table above. Echo the behavior
  of the option the user chose; never send a behavior that was not offered.
- `decision.message` — optional, forwarded to the agent on `deny`.
- **AskUserQuestion answers:** send `answers` (top-level or inside
  `decision`) as an array aligned with `tool_input.questions` — or an object
  keyed by question text — answering **every** question:

```json
{ "permissionId": "<uuid>", "decision": { "behavior": "allow" }, "answers": ["Blue", "Tabs"] }
```

- Codex synthetic approvals accept the same behaviors (`allow-always`
  degrades to `allow` when the menu offers no trust entry).

Unanswered permissions are auto-denied by the bridge shortly before the
agent-side hook timeout (~10 min).

## Hook surface (server-local)

`POST /hooks/{permission, tool-output, stop, session-end, task-complete,
error, notification}` (also reachable under `/v1/`) is how Claude Code hook
scripts and the Codex wrapper feed the bridge **on the same machine**. It is
unauthenticated but **loopback-only**. Watch clients never call it; it is
documented because its request bodies define the hook-originated SSE payloads
above. The permission hook blocks until a decision (or answers immediately
with no decision when zero SSE clients are connected). Permission state is
shared across surfaces: a `/v1/command` decision resolves a hook received on
either surface.

## Admin surface (server-local, operator)

`POST /admin/pairing/open`, `GET /admin/devices` and `POST /admin/devices/revoke`
are **operator tooling on the running bridge**, not part of this versioned client
protocol — a watch client never calls them, and `PROTOCOL_VERSION` does not gate
them. They are
**loopback-only** (same operator-on-machine trust as the hook surface: no
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

Regenerate — only after a deliberate, versioned `/v1` contract change (the
legacy corpus is frozen; a legacy diff means you broke the freeze):

```sh
CLAUDE_WATCH_UPDATE_FIXTURES=1 node --test test/protocol-fixtures.test.js
```

Review the fixture diff like an API review, update this document, and bump
`PROTOCOL_VERSION`/`MIN_SUPPORTED_CLIENT_PROTO` if the change is breaking.
