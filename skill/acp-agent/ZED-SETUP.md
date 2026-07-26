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
`zed-agent-servers.example.json`); backup at `settings.json.bak-claudewatch`.

```sh
./apply-zed-config.sh          # install or REPAIR the entry (idempotent)
./apply-zed-config.sh --check  # doctor: verify config + launcher + build; non-zero if wrong
./apply-zed-config.sh --remove
```

**Zed rewrites this entry.** On 2026-07-22 23:26 its agent panel replaced the `command` with a
`{"type": "registry"}` stub, which stops the adapter launching entirely — and Zed logs nothing about
`agent_servers`, so the only symptom is that claude-watch never connects. The script therefore
_repairs_ rather than refusing: it rewrites only its own key (brace-matched, so other agents,
comments and trailing commas survive) and refuses to write anything it cannot parse back. Run
`--check` first whenever Zed "isn't connected".

```json
"agent_servers": {
  "Claude (watch)": {
    "type": "custom",
    "command": "/home/deck/Development/claude-watch/skill/acp-agent/launch-claude-watch-acp.sh",
    "args": []
  }
}
```

### `"type": "custom"` is required

`agent_servers` is a **tagged union**: the Zed binary carries `custom | command | env`
alongside the registry variant's `default_config_options | favorite_config_option_values`.
An entry with a `command` but no `type` is not registered as a custom agent server —
opening a thread fails with:

```
Custom agent server `Claude (watch)` is not registered
```

and the agent panel rewrites the entry into a `{"type": "registry"}` stub. That is the
mechanism behind both observed reverts (2026-07-22 23:26 and 2026-07-25 18:15); the
missing tag was the cause, and the registry stub was the symptom. `--check` verifies the
tag as well as the command, so an untagged entry fails the doctor rather than passing it.

## Billing guard (must-fix from review #74)

Two layers, because a scrub you can walk around is not a guarantee:

1. `launch-claude-watch-acp.sh` **unsets** the provider-routing set before exec and warns on stderr.
2. `src/index.ts` **refuses to start** (exit 78) if any of them is still set — checked _after_ the
   managed-policy env is applied, so a policy-injected var is caught too. This is the layer that
   holds when the launcher is bypassed: `npm start` execs `dist/index.js` directly, and Zed's
   `command` has silently reverted once already. Override with `CLAUDE_WATCH_ALLOW_API_BILLING=1`.

Guarded set: `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL`,
`ANTHROPIC_BEDROCK_BASE_URL`, `ANTHROPIC_VERTEX_BASE_URL`, `ANTHROPIC_CUSTOM_HEADERS`,
`CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_VERTEX`. The regional/project vars (`AWS_REGION`,
`CLOUD_ML_REGION`, `ANTHROPIC_VERTEX_PROJECT_ID`) are deliberately excluded: inert without one of
the switches above, and failing on them would false-positive on any machine with the AWS CLI set up.

## `dist/` is what actually runs, and it is not tracked

`.gitignore` excludes `dist/`, so it is only ever whatever this machine last compiled. A fresh
clone gets one from the `prepare` script — npm runs it on install, so **`npm ci` alone is
enough** (#82); there is no separate build step to remember. An edit to `src/` with no rebuild
used to be a **silent no-op** (Zed keeps running the old adapter). The launcher now refuses to start
when any of `src/`, `tsconfig.json` or `package.json` is newer than `dist/index.js`:

```
claude-watch-acp: dist/ is STALE — refusing to launch the old adapter.
```

Deliberately a refusal, not an auto-build: putting `tsc` in Zed's agent-launch path trades a loud
failure for a slow, flaky one. Override with `CLAUDE_WATCH_SKIP_BUILD_CHECK=1`.

## Verified AFK

- Adapter builds (`tsc`), upstream suite passes (652 tests), `--version` → 0.61.0
- ACP `initialize` over ndjson works; offers **Claude Subscription** + Anthropic Console auth
- Host is logged in (`~/.claude/.credentials.json`); `ANTHROPIC_API_KEY` unset
- Faithful replay of Zed's exact spawn (host `/bin/bash -c "<launcher>"`) round-trips `initialize`

## Remaining (the live go/no-go — needs Zed's UI)

Open Zed → agent panel → new thread with **"Claude (watch)"** → type a prompt → confirm a response.
Reload/restart Zed first if the failed agent is cached.
