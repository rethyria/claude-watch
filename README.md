<p align="center">
  <img src="logo.png" width="140" alt="Claude Logo" />
</p>

<h1 align="center"><strong>Agent Watch</strong></h1>

<p align="center">
  Control Claude Code from your Apple Watch.<br/>
  See terminal output, approve permissions, and send voice commands — all from your wrist.
</p>

https://github.com/user-attachments/assets/5f478c28-2086-4696-9d76-e43dda853201

> ### ⌚ On **Wear OS / Android**? This README covers the original **Apple Watch (iOS)** build.
> For the Wear OS watch app, follow **[wear/INSTALL.md](wear/INSTALL.md)** instead. Both use the
> same Node.js bridge described below — only the watch app differs.

---

```
                    WCSession
 Apple Watch  <===============>  iPhone  <=======>  Mac
  (SwiftUI)     sendMessage       (Relay)   HTTP    Bridge Server
                transferUserInfo           SSE     (Node.js)
                                                      |
                                            ACP uplink | PTY stdin
                                                      v
                                              Agent Session
                                        (Zed's claude-agent-acp fork)
```

## What It Does

- **Live terminal output** on your Apple Watch — see what Claude is doing in real-time
- **Permission prompts** — approve or deny Claude's actions from your wrist (Edit file? Run command?)
- **Dynamic questions** — answer `AskUserQuestion` prompts with all options displayed
- **Voice commands** — dictate commands to Claude via watchOS dictation
- **iPhone companion** — pairing UI, connection status, terminal preview, permission approvals
- **Bridge server** — Node.js server on your Mac that connects agent sessions to the watch via a local ACP channel + SSE

## Architecture

The system has three components:

### 1. Bridge Server (Mac)
A Node.js HTTP server (`skill/bridge/server.js`) that:
- Mirrors agent sessions announced over a loopback-only ACP channel by the forked [`claude-agent-acp`](skill/acp-agent) adapter Zed launches (registers, turn boundaries, teed tool calls and permission requests)
- Streams events to connected clients via Server-Sent Events (SSE)
- Handles pairing with a 6-digit code + session token
- Advertises itself on the local network via Bonjour/mDNS
- Raises permission prompts on the watch/phone and sends the decision back down the adapter's channel to the agent

### 2. iPhone App
A SwiftUI iOS app that:
- Discovers the bridge via Bonjour (or localhost fallback)
- Pairs using the 6-digit code
- Shows connection status + terminal output
- Displays interactive permission prompts (Yes / Yes all / No)
- Relays events to the Apple Watch via WCSession

### 3. watchOS App
A SwiftUI watchOS app that:
- Connects directly to the bridge over Wi-Fi (Bonjour or manual IP entry)
- Shows live terminal output (Read, Edit, Bash, Grep operations)
- Displays permission prompts with all options as scrollable buttons
- Supports voice command input via watchOS dictation
- Haptic feedback for task completion, approvals, and errors

## Quick Start

### Prerequisites
- macOS with Node.js 18+
- Xcode 16+ with watchOS SDK
- Apple Watch on the same Wi-Fi as your Mac
- Claude Code CLI installed

### Apple Watch Wi-Fi Setup
1. Make sure your Apple Watch is connected to the **same Wi-Fi network** as the Mac running your Claude Code session
2. On your Apple Watch, go to **Settings > Wi-Fi > your network** and turn **Private Wi-Fi Address** to **Off** — this is required for Bonjour/mDNS discovery to work reliably on the local network

### 1. Install the bridge

```bash
cd skill/bridge
npm install
```

### 2. Start the bridge server

```bash
cd skill/bridge
node server.js
```

You'll see:
```
╔═══════════════════════════════════════╗
║        AGENT WATCH BRIDGE             ║
╠═══════════════════════════════════════╣
║  Pairing Code:  648505                ║
║  IP Address:    192.168.1.4           ║
║  Port:          7860                  ║
╚═══════════════════════════════════════╝
```

If the default port 7860 is taken (Gradio's default, notably), the bridge
binds the next free port and publishes it to `~/.claude-watch/port` — the
ACP adapter reads that file to find the bridge.

### 3. Wire the editor to the bridge

Sessions reach the bridge through the forked
[`claude-agent-acp`](skill/acp-agent) adapter running as an agent server in
Zed (the hook-observation channel that once mirrored terminal sessions was
retired in #87 — the product is Zed-only). Build the adapter and install its
`agent_servers` entry:

```bash
cd skill/acp-agent
npm install && npm run build
./apply-zed-config.sh        # idempotent; --check verifies, --remove undoes
```

Every session started under that agent in Zed announces itself to the
running bridge automatically.

### 4. Build the iOS + watchOS apps

```bash
cd ios/ClaudeWatch
xcodegen generate    # Generates the .xcodeproj
open ClaudeWatch.xcodeproj
```

In Xcode:
1. Set your **Development Team** on both targets (ClaudeWatch + ClaudeWatchWatch)
2. Select the **ClaudeWatch** scheme for the iPhone, or **ClaudeWatchWatch** for the watch
3. Build and run (Cmd+R)

### 5. Pair

**iPhone:** Enter the 6-digit pairing code from the bridge banner.

**Apple Watch:** The app auto-discovers the bridge via Bonjour. If that fails, enter the IP address shown in the bridge banner manually.

**Discovery probe:** When a watch client verifies a candidate bridge address — the localhost fallback, a manually entered IP, or `10.0.2.2` from an Android emulator — it probes the unauthenticated `GET /ping` endpoint, which answers with `{proto, bridgeId, machineName}`. `GET /status` requires the paired device's bearer token and cannot be used for discovery.

### 6. Use the agent normally

Start a session under the "Claude (watch)" agent in Zed. Turn activity and assistant prose stream to the watch and phone in real-time. Permission prompts appear as interactive cards.

## Project Structure

```
claude-watch/
├── skill/
│   ├── bridge/
│   │   ├── server.js          # Bridge server (HTTP + SSE + Bonjour)
│   │   └── package.json       # Node.js dependencies
│   ├── acp-agent/             # Forked claude-agent-acp (the Zed agent server)
│   ├── setup.sh               # Install bridge dependencies
│   └── SKILL.md               # Claude Code skill definition
│
├── ios/ClaudeWatch/
│   ├── project.yml            # XcodeGen project spec
│   │
│   ├── Shared/                # Shared between iOS + watchOS
│   │   ├── Models/
│   │   │   ├── SessionState.swift
│   │   │   ├── TerminalLine.swift
│   │   │   ├── ApprovalRequest.swift
│   │   │   ├── WatchMessage.swift
│   │   │   └── OutputRingBuffer.swift
│   │   ├── Connectivity/
│   │   │   └── WatchSessionManager.swift
│   │   └── Extensions/
│   │       ├── Color+Hex.swift
│   │       └── ClaudeMascot.swift     # Official Claude logo as SwiftUI Shape
│   │
│   ├── ClaudeWatch iOS/       # iPhone app
│   │   ├── App/ClaudeWatchApp.swift
│   │   ├── Views/
│   │   │   ├── PairingView.swift      # 6-digit code entry
│   │   │   ├── ConnectionStatusView.swift  # Terminal + status
│   │   │   └── SettingsView.swift
│   │   ├── Networking/
│   │   │   ├── BonjourDiscovery.swift # LAN bridge discovery
│   │   │   ├── BridgeClient.swift     # HTTP client
│   │   │   └── SSEClient.swift        # Server-Sent Events
│   │   └── Services/
│   │       ├── RelayService.swift     # Coordinates bridge <-> watch
│   │       └── NotificationService.swift
│   │
│   └── ClaudeWatch watchOS/   # Apple Watch app
│       ├── App/ClaudeWatchWatchApp.swift
│       ├── Views/
│       │   ├── OnboardingView.swift   # Pairing (Bonjour + manual IP)
│       │   ├── SessionView.swift      # Terminal output + mic FAB
│       │   ├── ApprovalView.swift     # Dynamic permission prompts
│       │   ├── VoiceInputView.swift   # Dictation input
│       │   └── StatusDashboard.swift
│       ├── Services/
│       │   ├── WatchViewState.swift   # Watch-specific state + SSE
│       │   ├── WatchBridgeClient.swift # Direct HTTP to bridge
│       │   ├── HapticManager.swift
│       │   └── SpeechService.swift
│       └── Complications/
│           └── ComplicationProvider.swift
│
└── .claude/skills/claude-watch/
    └── SKILL.md               # /claude-watch skill for Claude Code
```

## How It Works

### Event Flow (Mac -> Watch)

1. The agent runs its turn inside the Zed-launched `claude-agent-acp` fork
2. The fork tees each session update (turn boundaries, tool calls, assistant prose) to the bridge over its loopback `/acp/*` uplink
3. Bridge pushes the event to all connected SSE clients
4. The watch/phone receives the SSE event and renders it in the session feed

### Permission Flow (Mac -> Watch -> Mac)

1. The agent hits a permission prompt (e.g., "Do you want to edit this file?")
2. The fork mirrors the ACP permission request to the bridge, which registers a pending prompt
3. Bridge pushes a `permission-request` SSE event with the question + options
4. Watch shows the approval sheet with all options as tappable buttons
5. User taps an option — watch sends the decision back to the bridge via HTTP
6. Bridge writes a `permission-decision` frame down the fork's inbox naming the agent's own option — the agent continues or stops (Zed's dialog stays a second answering surface; whichever answers first wins)

### AskUserQuestion Flow

Same as permission flow, but the card carries `tool_input.questions` with dynamic options (label + description); the answers ride back as one positional `input-decision` frame. The watch renders the questions as a scrollable list.

## Configuration

### Bridge Server

| Env Var | Default | Description |
|---------|---------|-------------|
| `PORT` | 7860 | Starting port (tries 7860-7869) |

### Unpairing

- **iPhone:** Settings > Forget Mac
- **Watch:** Restart the app (credentials clear when bridge restarts)

## Requirements

| Component | Minimum Version |
|-----------|----------------|
| macOS | 13.0+ |
| Node.js | 18+ |
| Xcode | 16+ |
| iOS | 17.0 |
| watchOS | 10.0 |
| Claude Code | 2.1+ |

## Troubleshooting

### Watch shows "Bridge not found"
- Ensure `node server.js` is running on your Mac
- Check that your watch is on the same Wi-Fi network
- Use the "Enter IP manually" option with the IP shown in the bridge banner

### Watch shows "unsupported architecture"
- Clean build folder in Xcode (Cmd+Shift+Option+K)
- Select the correct scheme: **ClaudeWatchWatch** (not ClaudeWatch)
- Deploy via paired iPhone destination if direct watch deployment fails

### iPhone shows "Connection failed"
- Check that the bridge is running (`curl http://127.0.0.1:7860/ping`)
- The bridge must be on the same LAN as the iPhone

### Permission prompts don't appear on watch
- Verify the Zed agent entry is healthy: `./skill/acp-agent/apply-zed-config.sh --check`
- Check bridge logs for "ACP permission … raised on the wrist"
- Ensure the watch is connected to the bridge (green status dot)

### Bridge exits immediately
- The bridge no longer auto-spawns Claude. It waits for the Zed-launched adapter to announce sessions.
- Start a session under the "Claude (watch)" agent in Zed — the adapter forwards events automatically.

## License

MIT
