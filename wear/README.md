# Claude Watch — Wear OS client (walking skeleton)

A standalone Wear OS debug app that speaks the bridge's `/v1` protocol end to
end: pair with the code from the bridge banner, stream events over SSE, send a
session-id-scoped command, and answer blocking permission hooks.

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

The instrumented test (`WalkingSkeletonTest`) drives the actual UI: it pairs,
waits for a hook-generated SSE event to render as raw text, sends a
session-scoped command (expects 2xx), then posts a blocking
`/hooks/permission` request and answers it with Allow — asserting the hook
stays blocked until the decision and unblocks with `behavior: "allow"`.
