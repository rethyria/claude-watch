# Claude Watch on Wear OS — Install & Setup

Run your Claude Code sessions on your wrist: live terminal, permission
approvals, and `AskUserQuestion` prompts on a Wear OS watch.

> **Heads-up:** the repo's top-level `README.md` describes the original **Apple
> Watch / iOS** build (macOS + Xcode). This guide is the **Wear OS / Android**
> path. They share one thing — the same **bridge** — and differ only in which
> watch app you install.

---

## How it fits together

There are two pieces you run:

```
  Wear OS watch  ──Wi-Fi (SSE + HTTP)──►  Bridge (Node.js)  ──HTTP hooks──►  Claude Code
   (the app)                              on your computer                  on your computer
```

- **The bridge** is a small Node.js server that ships **inside this repo**
  (`skill/bridge/server.js`). It is **not** a download, a binary, or a Play
  Store item — you clone the repo and run the script. It receives Claude Code's
  hook events and streams them to your watch, and it blocks Claude on a
  permission prompt until you answer from the wrist.
- **The bridge runs on the same computer as Claude Code.** On its own it does
  nothing — it's a companion to a running Claude Code session, not a standalone
  service.
- **The watch and the computer must be on the same Wi-Fi network.**

---

## Prerequisites

- **Node.js 18.1+** on the computer that runs Claude Code.
- **Claude Code** installed and working on that computer.
- A **Wear OS watch** (Wear OS 3 / Android 11+ — the app's `minSdk` is 30) on
  the **same Wi-Fi** as the computer.
- To build the watch app yourself (there is no Play Store release yet — see
  [Getting the app](#2-get-the-watch-app-onto-your-watch)): the **Android SDK**
  and a **JDK 17**. Gradle itself comes with the repo (wrapper, Gradle 8.11.1).
  Gradle must be able to find the SDK — see the build step for how.

---

## 1. Get the bridge running

```bash
git clone <this-repo-url> claude-watch
cd claude-watch/skill/bridge
npm install          # once — installs the one dependency (bonjour-service)
node server.js
```

`node server.js` prints a banner:

```
╔═══════════════════════════════════════╗
║        AGENT WATCH BRIDGE             ║
╠═══════════════════════════════════════╣
║  Pairing Code:  648505                ║   ← first run; once a device is paired this reads "Pairing: locked (SIGUSR1)"
║  IP Address:    192.168.1.20          ║
║  Port:          7860                  ║
║  Agents:        Claude                ║
║  Devices:       0                     ║
╚═══════════════════════════════════════╝
```

Leave it running. It binds port **7860** by default (walking up to 7869 if
taken) and writes the actual port to `~/.claude-watch/port`.

> `skill/setup.sh` is a shortcut for the `npm install` step. You can also start
> the bridge through the bundled Claude Code skill (`/claude-watch`), but
> `node server.js` is the clearest path to start with.

### Wire Claude Code to the bridge

In a second terminal, install the hooks that make **every** Claude Code session
stream to the bridge:

```bash
cd claude-watch
./skill/setup-hooks.sh
```

This adds HTTP hooks to your global `~/.claude/settings.json` for Claude Code's
tool-use, permission, stop, session-end, failure, and notification events (eight
hook entries in all), every one pointing at `http://127.0.0.1:<port>/hooks/…`.
It reads the port from `~/.claude-watch/port`, so **start the bridge first**; if
you run it too early it falls back to 7860 and warns — just re-run it once the
bridge is up.

To undo it later: `./skill/setup-hooks.sh --remove`.

> If you also have the `codex` CLI installed, the installer drops a small
> `codex-watch` wrapper into `~/.local/bin` (and `--remove` takes it back out).
> Harmless if you only use Claude Code.

---

## 2. Get the watch app onto your watch

There is **no Play Store listing yet** (tracked as a future item). For now you
**build the APK from source and sideload it** over adb.

### Build the APK

First, point Gradle at your Android SDK — a fresh clone has neither of these set
(`local.properties` is gitignored), and without one the build fails with *"SDK
location not found."* Either export an env var:

```bash
export ANDROID_HOME=/path/to/Android/Sdk
```

or create `wear/local.properties` containing `sdk.dir=/path/to/Android/Sdk`.

Then build (from the repo root):

```bash
cd wear
./gradlew :app:assembleDebug
cd ..
```

The APK lands at (relative to the repo root — hence the `cd ..` above, so the
`adb install` path below resolves):

```
wear/app/build/outputs/apk/debug/app-debug.apk
```

### Sideload it onto the watch

1. On the watch: **Settings → Developer options → Wireless debugging** → on.
   (Enable Developer options first by tapping **Settings → System → About →
   Software → Build number** several times.)
2. Pair/connect over adb from the computer (the watch shows an IP and port):
   ```bash
   adb pair <watch-ip>:<pair-port>        # first time only, uses the on-watch code
   adb connect <watch-ip>:<connect-port>
   adb install -r wear/app/build/outputs/apk/debug/app-debug.apk
   ```
   Wear OS wireless-debugging ports rotate and the watch must be awake to
   advertise; if `adb connect` fails, re-open the Wireless debugging screen.

Open the app on the watch — it shows a **not paired** screen with **Manual** and
**Discover** buttons.

---

## 3. Pair the watch (zero typing)

Pairing is gated by a **single-use window** you open on the bridge — no code
typing needed on the Discover path.

1. **On the computer**, open the pairing window (loopback-only operator
   control):
   ```bash
   curl -X POST http://127.0.0.1:7860/admin/pairing/open
   # → {"ok":true,"code":"913410","expiresInMs":300000}
   ```
   (Use the port from the bridge banner if it isn't 7860.)
2. **On the watch**, tap **Discover**.
3. Wait a moment — it scans the Wi-Fi for bridges.
4. It shows a **list** of discovered bridges (e.g. `Home — 192.168.1.20:7860`).
5. Tap yours. It pairs and connects — **no IP, port, or code typed**.

The window is single-use: it closes the moment one device pairs. To pair
another device, open it again.

**Prefer typing it in?** Tap **Manual** instead and enter the host, port, and
the `code` the `curl` returned.

---

## 4. Managing devices

The bridge keeps a per-device token for each watch you pair. Operator controls
(loopback-only — never reachable from the LAN; use your bridge's actual port if
it isn't 7860):

```bash
# List paired devices (id is a short hash prefix, never a secret)
curl http://127.0.0.1:7860/admin/devices

# Disconnect one device by its id prefix
curl -X POST http://127.0.0.1:7860/admin/devices/revoke -d '{"id":"fddd67de25f8"}'

# Disconnect all devices
curl -X POST http://127.0.0.1:7860/admin/devices/revoke -d '{"all":true}'
```

A watch can also **unpair itself**: on the watch, swipe to the **Settings** page
(leftmost) and tap **Unpair** twice.

---

## Troubleshooting

- **Discover finds nothing.** The watch and computer must be on the **same
  Wi-Fi**, and that network must allow multicast/mDNS between clients (some
  guest or "AP isolation" networks block it). Confirm the bridge logged
  `Bonjour advertising _claude-watch._tcp`. On the watch's Wi-Fi settings, turn
  **off** any "private/randomized MAC" option for that network. As a fallback,
  use **Manual** with the IP + port from the bridge banner.
- **"Couldn't pair: 403 / window closed."** Open the pairing window first
  (step 3.1). It's single-use, so re-open it for each new device.
- **The watch shows sessions as green when nothing's happening / other state
  oddities.** If you changed bridge code, restart `node server.js` — a running
  bridge does not reload on file changes.
- **Permission prompts feel slow to clear when answered on the computer.** They
  clear when you answer; if they linger, make sure the bridge is the current
  build.

---

## What's next / not done yet

- A **Play Store** release (so you skip the build-from-source step) is planned,
  not shipped.
- **Off-LAN / remote** access (watch and computer on different networks) needs a
  phone relay and is not built yet.
