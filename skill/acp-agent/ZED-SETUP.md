# Zed + claude-watch ACP adapter (S2 / issue #76)

How the watch-dictatable session is hosted: **Zed (unmodified) → our forked `claude-agent-acp`
→ Claude Agent SDK**, on the **claude.ai subscription**.

## How Zed launches it
Zed is a flatpak (`dev.zed.Zed`). Its sandbox grants (via `flatpak info --show-permissions`):
- `filesystems=home` → reads `~/.claude` (the `--claudeai` login), `~/.local/bin/node`, the adapter
- `shared=network` → loopback `127.0.0.1` reaches the host claude-watch bridge
- `org.freedesktop.Flatpak=talk` → **Zed runs `agent_servers` commands on the HOST**

Because Zed already runs the agent command on the host, the `command` is the **host launcher
directly** — NOT wrapped in `flatpak-spawn` (which exists only inside the sandbox, not on the host;
wrapping it produced `command not found` / `No such file or directory`). The launcher runs host node
against the built adapter, using the host `~/.claude` subscription login and host loopback.

## Config (already applied)
`~/.var/app/dev.zed.Zed/config/zed/settings.json` gained an `agent_servers` entry (see
`zed-agent-servers.example.json`); backup at `settings.json.bak-claudewatch`. Applied by
`apply-zed-config.sh` (backs up, refuses if `agent_servers` already exists).

```json
"agent_servers": {
  "Claude (watch)": {
    "command": "/home/deck/Development/claude-watch/skill/acp-agent/launch-claude-watch-acp.sh",
    "args": []
  }
}
```

## Billing guard (must-fix from review #74)
`launch-claude-watch-acp.sh` **unsets** the whole provider-routing set (`ANTHROPIC_API_KEY`,
`ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL`, `CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_VERTEX`)
before exec, so billing can never divert off the subscription. Warns loudly on stderr if any was set.

## Verified AFK
- Adapter builds (`tsc`), upstream suite passes (652 tests), `--version` → 0.61.0
- ACP `initialize` over ndjson works; offers **Claude Subscription** + Anthropic Console auth
- Host is logged in (`~/.claude/.credentials.json`); `ANTHROPIC_API_KEY` unset
- Faithful replay of Zed's exact spawn (host `/bin/bash -c "<launcher>"`) round-trips `initialize`

## Remaining (the live go/no-go — needs Zed's UI)
Open Zed → agent panel → new thread with **"Claude (watch)"** → type a prompt → confirm a response.
Reload/restart Zed first if the failed agent is cached.
