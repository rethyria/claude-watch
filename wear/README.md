# Claude Watch — Wear OS client (walking skeleton)

A standalone Wear OS debug app that speaks the bridge's `/v1` protocol end to
end: pair with the code from the bridge banner, stream events over SSE, send a
session-id-scoped command, and answer blocking permission hooks.

- **Module layout:** single `:app` module for the skeleton; later issues split
  out `:shared` (and eventually `:phone`).
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

The instrumented test (`WalkingSkeletonTest`) drives the actual UI: it pairs,
waits for a hook-generated SSE event to render as raw text, sends a
session-scoped command (expects 2xx), then posts a blocking
`/hooks/permission` request and answers it with Allow — asserting the hook
stays blocked until the decision and unblocks with `behavior: "allow"`.
