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
- **Dictated/typed commands with ack-gated echo (issue #20):** voice input
  rides `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (never a raw
  `SpeechRecognizer` on Wear) and feeds the exact same send path as typed
  text. The confirmed watchOS trap — echoing `> command` + a thinking cursor
  BEFORE the network call and swallowing every failure, silently losing the
  transcription — is inverted: the command shows as **pending** until the
  bridge acks, the terminal echo + thinking cursor happen only on a 2xx, and
  any failure (transport, timeout, non-2xx) echoes nothing, surfaces an error
  and **restores the text into the input** so retry re-sends the same text.
  Unpaired/no-session input refuses cleanly instead of pretending to send.
  Outcomes speak a haptic grammar via `VibrationEffect` (`Haptics.kt`): one
  crisp tick on ack, a double buzz on failure/refusal.
- **Stack:** Kotlin, Compose for Wear OS, OkHttp + okhttp-sse
  (`Last-Event-ID` reconnect replay, 25 s heartbeat read timeout), DataStore.
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

## Connection lifecycle

`ConnectionEngine` owns the whole lifecycle and the derived connection state
(`Stopped / Pairing / Connecting / Connected / Reconnecting / AuthExpired /
ProtoMismatch`); the UI only renders it. Every design point below is pinned to
a confirmed bug in the iOS/watchOS clients this port replaces:

- **Reconnect backoff:** exponential 1 s → 30 s with jitter (`BackoffPolicy`),
  never a fixed-cadence hammer.
- **Heartbeat watchdog:** the bridge writes a `:heartbeat` SSE comment every
  10 s. okhttp-sse never surfaces comments, so the watchdog is the SSE
  socket's 25 s read timeout, enforced by OkHttp's always-live scheduler
  threads — never an app timer that can be parked on a run-loop-less thread.
  Silence beyond the window fails the stream, which becomes a reconnect.
- **Single-flight reconnect:** an epoch counter plus explicit teardown means
  at most one `EventSource` exists at a time; stale callbacks are dropped and
  nothing leaks a connection per retry.
- **STOPPED is not a failure:** user disconnect/unpair cancels the engine,
  clears credentials, and is never retried — zero further requests reach the
  bridge, and no zombie reconnect after unpair.
- **Credential retention:** transient network errors (timeouts, resets,
  refusals, airplane mode, cold-start probe failures) NEVER wipe credentials —
  they land in the retry loop. Only a definitive HTTP 401 wipes the pairing,
  with an on-screen explanation asking the user to pair again.
- **Expired prompts:** a 404 on a permission answer clears the dead
  Allow/Deny card and shows "expired" instead of leaving a lying button.
- **No polling fallback, ever:** a broken stream is shown as broken and
  retried; it is never silently degraded to a one-way channel.
- **Proto gate:** before pairing, the client checks the bridge's `proto`
  (unauthenticated `GET /v1/ping`) against its minimum and explains a
  mismatch instead of failing weirdly later.

Restart-matrix note: with per-device tokens on the bridge, a bridge restart is
a seamless resume (the token survives and the stream replays from the
persisted `lastEventId`). Against an older bridge whose token store resets on
restart, the same event is a definitive 401 → re-onboard.

## Discovery (issue #22 — the emulator-verifiable rungs)

Manual IP entry is the first-class pairing path (host + port + code on the
pairing screen), not a buried fallback. Everything else builds on the
unauthenticated `GET /v1/ping` probe:

- **Shape validation (`BridgePing`):** a responder only counts as a bridge
  when `/v1/ping` returns 2xx JSON with a positive integer `proto`, a
  non-empty `bridgeId` and a non-empty `machineName`. The bridge's default
  port (7860) is also Gradio's default, so "an HTTP server answered" proves
  nothing — a decoy is rejected at pair time and at reconnect time, and never
  receives a token.
- **bridgeId pinning:** the bridge's `bridgeId` is pinned when pairing and
  verified by a `/v1/ping` preflight on every reconnect and cold start
  (`ConnectionEngine.connect`). A host that answers with a foreign `bridgeId`
  lands in the terminal `BridgeMismatch` state: a clear re-pair prompt, the
  token is never offered to the stranger, and credentials are deliberately
  NOT wiped (the real bridge may still exist — e.g. DHCP handed its IP to
  another machine). Both legacy clients paired with the first mDNS hit and
  could nondeterministically talk to the wrong Mac; this is that bug's fix.
  Pinning only works because the bridge's identity is STABLE: `BRIDGE_ID` is
  generated once and persisted next to `credentials.json`
  (`skill/bridge/config.js`), exactly like the bearer tokens it travels with —
  a per-process id would turn every routine bridge restart into a terminal
  `BridgeMismatch` (and make port relocation impossible), regressing the
  seamless restart-resume above.
- **Port probe ladder:** when the known port refuses the connection
  (bridge-down: the host answered, the bridge is gone) or a decoy answers on
  it, the engine pings the bridge's port-walk range (7860–7869, mirroring
  `PORT_RANGE_START/END` in `skill/bridge/config.js`) on the same host and
  relocates ONLY to a responder whose `bridgeId` matches the pinned one. The
  move persists (same token, same replay cursor, new port).
- **Bridge-down vs path-broken:** a preflight failure that is a connection
  REFUSAL means the host is reachable → ordinary backoff (+ port probe).
  Anything else (timeout, unreachable) is a broken PATH — on the watch,
  typically the Bluetooth phone proxy not seeing the workstation's LAN — and
  retrying down the same dead route cannot help. The engine then escalates
  via `WifiNetworkEscalator`: `ConnectivityManager.requestNetwork` with
  `TRANSPORT_WIFI`, held until the SSE stream is healthy again, then released
  so the platform can return to the battery-friendly BT-proxy path. (This
  held-Wi-Fi hook is also the prerequisite for the battery matrix's held-Wi-Fi
  scenario.)

mDNS/NSD zero-typing discovery is a separate HITL issue: the emulator's NAT
drops LAN multicast, so it cannot be verified here.

## Storage

Credentials (token, host, port, bridgeId) and the SSE replay cursor
(`lastEventId`) persist in a DataStore (`CredentialStore`, custom-`Serializer`
single-object flavor) whose on-disk bytes are wholly AES/GCM-encrypted with a
non-exportable Android Keystore key (`KeystoreTokenCipher`). `lastEventId`
survives process death so a relaunch resumes the replay from where it
stopped; a blob that fails to decrypt is treated as corruption and replaced
with the unpaired default instead of crashing.

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
`DictationFlowTest` (instrumented, on-device MockWebServer bridge) covers the
dictation flow with the recognizer activity result stubbed to a fixed
transcription (real voice cannot run headlessly; the real-voice smoke test is
in the hardware QA checklist): stubbed result → command POSTed → echo only
after the 2xx ack, injected 5xx → no echo + error + text restored for a retry
that re-sends the same text, and unpaired input refusing cleanly.
