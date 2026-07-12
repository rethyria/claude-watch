# Claude Watch — Wear OS client

A standalone Wear OS app that speaks the bridge's `/v1` protocol end to end:
pair with the code from the bridge banner, stream events over SSE, watch each
session's live terminal in a pager, send session-scoped commands, spawn/kill
sessions, and answer blocking permission hooks.

- **Module layout:** `:app` (Compose UI + OkHttp networking) and `:shared`
  (pure-JVM protocol layer; eventually `:phone` joins).
- **`:shared`:** typed kotlinx.serialization wire models for the `/v1` SSE
  contract — tolerant to unknown fields, strict on the contract (a missing
  `permissionId` or a behavior-less permission option fails loudly) — plus the
  pure `BridgeEventReducer` that folds frames into per-session activity/elapsed
  state. Sessions are pruned on `session` `ended`; `lastEventId` is committed
  only after a frame fully parses AND applies, so rejected frames are replayed
  on reconnect. Tested against the fixture corpus in
  `shared/src/test/resources/fixtures/bridge-events.ndjson` (bridge ring-buffer
  entry format: `{id, event, data}` per line).
- **Terminal presentation (`:shared` `terminal/`):** the human-readable
  pipeline that replaced the raw `$type $data` JSON log — `AnsiStripper`
  (CSI/OSC/two-byte escapes), `ToolOutputFormatter` (Bash as `$ cmd` + first 5
  output lines, Read/Edit/Write as the filename, Grep as the pattern,
  `[codex] ` prefix), and an immutable 200-line `RingBuffer` per session. The
  reducer folds `pty-output`/`tool-output`/`stop`/`error` events into each
  session's terminal; `BridgeState.echoCommand` echoes a locally sent command
  (`> text`) and raises the session's thinking cursor, which the session's
  next output clears.
- **Session pager (`:app` `ui/SessionPagerScreen.kt`):** a foundation
  `HorizontalPager` (the primitive Horologist wraps) — page 0 is the
  control/debug page (pairing, command box, spawn actions, event log), then
  one live terminal page per session: `ScalingLazyColumn`, 30-line viewport
  over the 200-line ring, 11 sp monospace, design tokens
  `#E87A35`/`#34C759`/`#FF3B30` on black, blinking block cursor while a
  command awaits output, kill action in the page header. Connection state is
  rendered here but owned by the connection-lifecycle layer.
- **Approval flow (`:app` `ui/PermissionSheet.kt`):** permission prompts are
  a per-session QUEUE keyed by `permissionId` (`BridgeState.pendingPermissions`
  from the shared reducer), rendered by a single presenter: a full-screen,
  gesture-undismissable sheet over the pager. The newest prompt fronts (a
  prompt resolved from another device gets no `permission-cleared`, so a
  stale entry must never shadow a live one), with a "N more waiting" depth
  indicator. Each card shows WHAT is asked (tool + the actual
  command/file/pattern via `ToolOutputFormatter.describeToolRequest`) and
  WHICH session asks (folder name). Answers are keyed to the RENDERED card's
  `permissionId` and dismissal is ack-gated: the card leaves only on a 2xx
  ack or an authoritative 404 (resolved elsewhere / timed out — surfaced,
  never a false "approved"); any other failure keeps the card on screen with
  the error shown, so a lost POST can never silently invert an approval into
  the 10-minute auto-deny. Option buttons come from the bridge's canonical
  behavior-keyed option list (`allow` / `allow-always` / `deny`) — decisions
  send the machine-readable `behavior`, never label or position matching.
- **AskUserQuestion card (issue #18):** question prompts ride the same sheet
  (same queue, ack-gating, swipe-immunity, escape hatch) with a question body
  instead of behavior buttons. They carry no canonical options; the typed
  `PermissionRequestEvent.questions` surfaces EVERY question of
  `tool_input.questions` (parsed leniently — hook content never fails the
  frame), each with its own option chips (single-select replaces, `multiSelect`
  toggles and joins in option order) plus a free-text field per question. One
  Send goes out only when every question has an answer, POSTing
  `decision: {behavior: "allow", answers: {<question text>: <answer>}}` —
  the /v1 object form the bridge maps back to the blocked hook as
  `updatedInput.answers`. A failed send restores the card with the picks
  intact for retry.
- **Stack:** Kotlin, Compose for Wear OS, OkHttp + okhttp-sse (readTimeout 0,
  `Last-Event-ID` reconnect replay).
- **Standalone:** `com.google.android.wearable.standalone=true` — no phone
  relay.

## Cleartext policy (v1: HTTP on the LAN only)

Android blocks cleartext by default and this app never sets the global
cleartext flag. Because the network security config cannot express CIDR
ranges, the RFC1918-only scope is enforced in two cooperating layers:

1. `res/xml/network_security_config.xml` permits cleartext **only** for the
   app-private hostname `bridge.internal` (plus `10.0.2.2`, `localhost`,
   `127.0.0.1`). Everything else keeps the platform default: blocked.
2. `BridgeClient` sends all bridge traffic to `http://bridge.internal:<port>`
   and pins that hostname's DNS answer (OkHttp `Dns` override) to the
   operator-entered IPv4 address, which `PrivateHosts` must first validate as
   RFC1918 (`10/8`, `172.16/12`, `192.168/16`) or loopback. A public address
   never gets a socket.

TLS is a separate, deferred issue.

## Emulator networking recipe

The bridge runs on your workstation; the emulator reaches it through one of:

- **`10.0.2.2` (default):** the Android emulator's alias for the host's
  loopback/host machine. Start the bridge (`node skill/bridge/server.js`),
  note the port from the banner (first free port in 7860–7869), and enter
  `10.0.2.2` + that port + the 6-digit pairing code in the app. This is what
  CI uses — see `.github/scripts/wear-e2e.sh`.
- **`adb reverse` (works on real watches over adb too):**
  `adb reverse tcp:7860 tcp:7860`, then enter host `127.0.0.1`, port `7860`.
- **Real watch on the LAN:** enter the workstation's RFC1918 address
  (e.g. `192.168.1.20`) and the bridge port. Public addresses are rejected by
  design.

Note: the emulator must be able to reach the bridge's bound port; the bridge
binds `0.0.0.0`, so no extra forwarding is needed for `10.0.2.2`.

## Building

```sh
cd wear
./gradlew :app:assembleDebug          # requires JDK 17+ and the Android SDK
./gradlew :app:testDebugUnitTest      # JVM unit tests (MockWebServer)
```

`local.properties` (gitignored) must point `sdk.dir` at your Android SDK if
`ANDROID_HOME` is not set.

## Running the e2e locally

1. Boot a Wear AVD (API 33, `android-wear`, x86_64).
2. From the repo root: `bash .github/scripts/wear-e2e.sh` — it starts the
   real bridge, scrapes the pairing code and port from its stdout, and runs
   `:app:connectedDebugAndroidTest` with them as instrumentation arguments.

The instrumented e2e (`WalkingSkeletonTest`) drives the actual UI: it pairs,
waits for a hook-generated SSE event to render, sends a session-scoped
command (expects 2xx), then exercises the approval flow end to end — two
curl-simulated sessions post blocking `/hooks/permission` requests
concurrently, both queue on the sheet, and each answer unblocks exactly the
hook whose card was rendered (deny lands on one, allow on the other), plus an
allow-always answer for a hook with `permission_suggestions` asserting the
bridge maps it to `behavior: "allow"` + `updatedPermissions` so the prompt
does not recur — and the AskUserQuestion card: a blocking two-question hook
renders both questions, one answered by option chip and one by typed free
text, and unblocks with both answers keyed by question text in
`updatedInput.answers` — then spawns a session from the watch, swipes the pager to
its live terminal (the stubbed agent's PTY output renders as terminal
lines), and kills it from the page header, asserting the bridge's
`session ended` prunes the page.

`SessionPagerTest` (instrumented, no bridge) renders `SessionPagerScreen`
directly from fixture events folded through the shared reducer: pager
navigation across the control page and one terminal page per live session,
thinking-cursor raise/clear, and page pruning on session end.
`ApprovalFlowTest` (instrumented, no bridge) covers the sheet itself:
what/which-session/queue-depth rendering, answers keyed to the rendered
card's `permissionId`, swipe-immunity in all four directions, error surfacing
without dropping the prompt, the behavior-keyed allow-always button, and the
AskUserQuestion card (all questions rendered, send gated on completeness,
free-text and multi-select answers, approval-card dismissal/restore parity).
