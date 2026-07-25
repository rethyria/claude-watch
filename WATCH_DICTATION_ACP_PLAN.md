# Plan — Watch dictation into a live session via ACP (Zed + forked claude-agent-acp)

## Summary
Let the watch dictate into a **live, idle-or-busy** Claude Code session, for real — by
hosting the session through the **Agent Client Protocol (ACP)**. Zed (unmodified) is the
local editor/client; a **fork of `claude-agent-acp`** (Node, Apache-2.0) is the agent it
launches; the claude-watch **bridge** wires the watch to it. Dictation becomes a normal
`session/prompt` pushed onto the SDK's input stream. Stays on the **claude.ai subscription**.

> Corrected after a 52-agent adversarial review (epic #74): architecture confirmed sound,
> S1 unblocked; six must-fix + four should-fix text corrections folded in below. **Issues
> #75–#81 are the authoritative work items** and carry the per-slice acceptance criteria.

## Why (the decision trail)
- The watch must reach the user's *live* session. #69 (held) **disabled** dictation as
  "impossible"; the old bridge instead ran `claude -p --continue`, a **fork** that corrupts
  the tree and kills the slot. Both are wrong.
- Exhaustive check of every way to inject into an **idle** session a 3rd party doesn't own:
  hooks can't (no idle door; `Notification` is non-blocking), Channels are gated + preview,
  Remote Control is Anthropic-cloud + first-party-only, the VS Code extension exposes no API,
  and the user's extension sessions have **no PTY**. **Only owning the input channel works.**
- tmux (`send-keys`) works for a *terminal* session but was rejected (setup friction; SteamOS
  has no tmux/libevent/ncurses). "Bridge hosts the session" works but loses local typing.
- **ACP resolves it cleanly:** the editor↔agent channel is JSON-RPC over stdio, and the
  *client* owns it. Forking a heavy C++ editor (kate-code) is the wrong layer; the right layer
  is the **Node ACP agent**. Zed can point `agent_servers` at *any* custom agent command, so we
  don't fork Zed — we fork the adapter it launches. Local typing (Zed) + watch dictation both
  feed one session; the agent is the multiplexer. No tmux, no editor build, no container.

## Verified facts (from source)
- `claude-agent-acp`: TypeScript, Apache-2.0, active. Each session drives the Agent SDK off
  `input: Pushable<SDKUserMessage>`. `prompt()` = build msg → `turnQueue.push(turn)` →
  `session.input.push(userMessage)` → `ensureConsumer()`. Injection is the **same push**;
  the SDK always consumes the stream, so an **idle session wakes natively** (guarded by
  `queryClosed` for ended sessions).
- Output **and permissions** funnel through the **AcpClient** (`this.client`, class at
  caa-acp-agent.ts:1282): `this.client.sessionUpdate` (prose, `tool_call`/`tool_call_update`,
  mode/commands/plan) **and** the `this.client.requestPermission` **RPC** (1234/4359). Tap the
  AcpClient — **not** `sendUpdate`, which misses tool results and every permission prompt.
- Auth: first-class **"Claude Subscription"** method (`auth login --claudeai`) alongside an
  optional Console/API path. Subscription works; same usage pool as normal Claude Code. Billing
  is diverted by the whole `PROVIDER_ROUTING_ENV_VARS` set (7531), not just `ANTHROPIC_API_KEY`.
- Zed: `agent_servers` custom command, ACP over stdio, no official-agent restriction; prebuilt
  Linux binary (no compile). Its WASM extension sandbox can't host this — the ACP path is right.

## Architecture
```
Zed (unmodified)  --ACP/stdio-->  forked claude-agent-acp (skill/acp-agent/)  --> Claude Agent SDK (subscription)
                                          |  ^
                          inject dictation |  | tap this.client: sessionUpdate + requestPermission
                                          v  |
                                  claude-watch bridge  <--SSE-->  wear app (watch)
```
- The fork is a **subpackage inside claude-watch** (`skill/acp-agent/`), a clean vendored fork
  we can re-pull from upstream. We do **not** dissolve claude-watch into the adapter.
- Fork ↔ bridge talk over **loopback** at runtime (the fork is launched by Zed).

## Work breakdown (authoritative slices are issues #75–#81)
1. **Vendor the fork** (#75) — add `skill/acp-agent/` (fork of `claude-agent-acp`), build it, its
   own ACP test suite passes. No bridge/wear coupling. Proves the base.
2. **Zed drives the fork** (#76, HITL) — `agent_servers` → the fork; a typed prompt round-trips on
   the **subscription**. Prove the Zed **flatpak** can launch the node adapter + reach loopback +
   `~/.claude`. Go/no-go must assert absence of the **whole** routing-var set (below).
3. **Inject seam + side-channel** (#77) — factor `prompt()`'s enqueue into a helper; add
   `injectUserPrompt(sessionId, text, source)` reusing it (no `turnQueue`/orphan desync). On
   `newSession` register (id, cwd, **SDK session_id**); receive dictation → inject; tap
   **`this.client`** (`sessionUpdate` + `requestPermission`) → forward. **Deregister** on
   `queryClosed`/`closeSession`/dispose (no zombie slot). **Correlate with hooks** so one ACP
   session ≠ two bridge slots (suppress the fork's SDK-session curl-hooks, or bind the SDK
   session_id in `resolveHookSession`).
4. **Dictate from the watch** (#78) — add a dedicated additive **discriminator**
   (`dictatable:true` / `kind:acp|pty|hook`) on the session event + `/v1/status`, threaded
   SessionEvent→SessionState→HaloSession (preserve-on-resend like `external`); gate the Dictate
   pill on `dictatable`; keep `external`=true so the row still offers **Hide, not a fake Kill**
   (#53). Wire wear dictation → bridge inject route.
5. **Watch feed** (#79) — additive SSE `message` event for assistant prose (`agent_message_chunk`,
   **assistant-only** — there is no `user_message_chunk` in the adapter). Pick ONE echo authority
   for the user's dictated text (drop the wear local echo for ACP sessions, or keep local echo and
   don't re-render it) to avoid double-echo; proto stays additive.
6. **Permissions from the watch** (#80) — intercept the `requestPermission` **RPC** (not a stream
   update). Handle the Zed-vs-watch **dual-responder race** (Promise.race + the request's
   cancellation signal ~1235); register ACP permission ids so a reconnect can retract stale prompts.
7. **Repurposed** (#81) — `runHeadlessPrompt` was **already deleted by #69**; this slice reconciles
   PROTOCOL.md (~361-364, ~608-609) + stale `sessions.js` comments to the shipped 409/ACP reality
   and acts as the regression gate that the honest external/ended refusal survives once #78 adds
   PTY-less ACP sessions (the two existing 409 tests stay unchanged).

## Risks / unknowns
- **Turn bookkeeping** (`turnQueue`, orphan-count on cancel, FIFO attribution) is intricate;
  `injectUserPrompt` must reuse the exact enqueue path. Highest-care item.
- **Billing leak**: the whole `PROVIDER_ROUTING_ENV_VARS` set flips billing/backend
  (`ANTHROPIC_BASE_URL`, `CLAUDE_CODE_USE_BEDROCK`/`VERTEX`, `ANTHROPIC_API_KEY`,
  `ANTHROPIC_AUTH_TOKEN`; caa-acp-agent.ts:7531). Pin `agent_servers` env clean, add a startup
  assertion that fails loudly if any is set, and make #76 assert absence of every one.
- **Hook-twin / lifecycle**: the fork's SDK sessions also fire global hooks (dup-slot risk) and
  nothing deregisters on Zed quit (zombie slots) — both handled in slice 3 (#77).
- **Shared-session mechanics**: a Zed-side cancel can settle a queued watch turn as 'cancelled'
  after inject ack'd (surface it, don't drop); permissions need the dual-responder race (slice 6).
- **Zed on SteamOS** (GPU/Wayland; flatpak sandbox) — the S2/#76 go/no-go.
- **Quota**: ACP sessions draw the same subscription weekly pool as normal Claude Code.

## Non-goals / out of scope
- Injecting into **VS Code extension-panel** sessions (channel owned by VS Code — impossible;
  those stay hook-observed, unchanged).
- Merging the wear app / bridge into the adapter fork.
- tmux / Channels / Remote Control paths (evaluated, rejected — see decision trail).

## Guardrails
- Hold all pushes until asked. proto stays **additive** (currently 3). Never touch the live
  bridge on 127.0.0.1:7861 (user's restart). OAuth token never leaks into `/v1` or logs.
- Physical watch adb `-s 192.168.8.191:43377`; e2e `ANDROID_SERIAL=emulator-5554`.
