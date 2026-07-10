#!/bin/bash
# Agent Watch — Install global hooks so ALL Claude Code sessions stream to the bridge.
#
# Usage: ./setup-hooks.sh [port]
#   port: bridge server port (default: the port file written by a running
#         bridge at ~/.claude-watch/port, falling back to 7860)
#
# This writes HTTP hooks to ~/.claude/settings.json (global, all projects).
# To remove: ./setup-hooks.sh --remove

set -e

SETTINGS="$HOME/.claude/settings.json"

# Client-side timeout (seconds) for the blocking PermissionRequest hook.
# MUST equal HOOK_PERMISSION_TIMEOUT_MS in bridge/config.js (a bridge test
# asserts this): the bridge auto-denies a safety margin BEFORE this window so
# the hook always receives a deterministic response instead of racing its own
# timeout.
PERMISSION_HOOK_TIMEOUT_S=600

# The EXACT set of /hooks/<path> endpoints this installer writes — the single
# source of truth for recognizing OUR hook objects during removal and
# reinstall dedup. A hook object is ours only if its URL is exactly
# http://127.0.0.1:<port>/hooks/<one of these> (any port: the bridge walks
# 7860-7869, so a previous install may target a different port). Anything
# else — other loopback ports, other paths, other /hooks/* endpoints — is the
# user's and must never be touched. The install-mode Python asserts every URL
# it writes is covered by this list.
HOOK_PATHS="tool-output permission stop session-end error notification"

# ── Remove mode ──────────────────────────────────────────────────────────────
if [ "$1" = "--remove" ]; then
  # Remove codex wrapper
  rm -f "$HOME/.local/bin/codex-watch" 2>/dev/null && echo "Removed codex-watch wrapper" || true

  if [ ! -f "$SETTINGS" ]; then
    echo "No settings file found at $SETTINGS"
    exit 0
  fi

  # Remove the hooks we added: match individual hook OBJECTS (never whole
  # entries) by exact URL, so a user's own hooks sharing an entry with ours
  # survive. All inputs travel via the environment — nothing is interpolated
  # into the Python source, so a HOME containing quotes can't break or hijack
  # the script. The rewrite is atomic (temp file + rename): an interruption
  # mid-write can never corrupt settings.json.
  CLAUDE_WATCH_SETTINGS="$SETTINGS" \
  CLAUDE_WATCH_HOOK_PATHS="$HOOK_PATHS" \
  python3 - <<'PYEOF'
import json, os, re, stat

settings_path = os.environ['CLAUDE_WATCH_SETTINGS']
hook_paths = os.environ['CLAUDE_WATCH_HOOK_PATHS'].split()
claude_watch_url = re.compile(
    r'^http://127\.0\.0\.1:\d+/hooks/(?:' + '|'.join(map(re.escape, hook_paths)) + r')$')


def is_ours(hook):
    url = hook.get('url')
    return isinstance(url, str) and bool(claude_watch_url.match(url))


def strip_claude_watch_hooks(hooks):
    """Drop OUR hook objects; entries keeping any user hooks survive intact."""
    changed = False
    for event in list(hooks.keys()):
        entries = []
        event_changed = False
        for entry in hooks[event]:
            hook_objs = entry.get('hooks', [])
            kept = [h for h in hook_objs if not is_ours(h)]
            if len(kept) == len(hook_objs):
                entries.append(entry)
                continue
            event_changed = True
            if kept:
                trimmed = dict(entry)
                trimmed['hooks'] = kept
                entries.append(trimmed)
        if event_changed:
            changed = True
            if entries:
                hooks[event] = entries
            else:
                del hooks[event]
    return changed


def write_settings_atomically(path, data):
    """Temp file in the same directory + rename: readers only ever see either
    the old complete file or the new complete file, never a partial write.

    The rename discards the original file's permissions (the temp file's
    umask-derived mode would win), and settings.json can carry secrets (env
    vars, apiKeyHelper config) that users protect with e.g. chmod 600 — so
    replicate the existing mode onto the temp file BEFORE the rename. A brand
    new settings file keeps the umask default, same as a plain open(path, 'w').

    Resolve symlinks first: dotfiles managers (stow, chezmoi, ...) commonly
    make ~/.claude/settings.json a symlink into a repo. os.replace on the
    symlink itself would swap the link for a regular file, silently detaching
    the user's dotfiles setup — so rewrite the link's TARGET instead, exactly
    like the plain open(path, 'w') this replaced used to. (os.stat below
    follows symlinks anyway, so the mode logic is unaffected.)"""
    path = os.path.realpath(path)
    try:
        mode = stat.S_IMODE(os.stat(path).st_mode)
    except FileNotFoundError:
        mode = None
    tmp = f'{path}.tmp.{os.getpid()}'
    try:
        with open(tmp, 'w') as f:
            if mode is not None:
                os.fchmod(f.fileno(), mode)
            json.dump(data, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


with open(settings_path) as f:
    settings = json.load(f)

hooks = settings.get('hooks', {})
if strip_claude_watch_hooks(hooks):
    if not hooks:
        del settings['hooks']
    write_settings_atomically(settings_path, settings)
    print(f'Agent Watch hooks removed from {settings_path}')
else:
    print('No Agent Watch hooks found.')
PYEOF
  exit 0
fi

# ── Install mode ─────────────────────────────────────────────────────────────

# Port resolution: the bridge may not be on 7860 — it walks 7860-7869 when the
# default is taken (Gradio's default port, notably) and publishes its ACTUAL
# bound port to a port file on startup. That file is the single source of
# truth for hook URLs. Precedence: explicit argument > port file > 7860.
PORT_FILE="${CLAUDE_WATCH_CREDENTIALS_DIR:-$HOME/.claude-watch}/port"

# The fallback default mirrors the bridge's port-range start. The env override
# is test-only (same convention as CLAUDE_WATCH_PORT_RANGE_START in config.js):
# it lets the test suite exercise the fallback path in a private port range
# instead of racing other tests for the real 7860. Production never sets it.
DEFAULT_PORT="${CLAUDE_WATCH_PORT_RANGE_START:-7860}"
case "$DEFAULT_PORT" in
  ''|*[!0-9]*) DEFAULT_PORT=7860 ;;
esac

PORT_SOURCE="default"
if [ -n "$1" ]; then
  PORT="$1"
  PORT_SOURCE="argument"
elif [ -f "$PORT_FILE" ]; then
  PORT="$(tr -cd '0-9' < "$PORT_FILE")"
  if [ -n "$PORT" ]; then
    PORT_SOURCE="port-file"
    echo "Using bridge port ${PORT} from ${PORT_FILE}"
  fi
else
  PORT=""
fi
case "$PORT" in
  ''|*[!0-9]*) PORT="$DEFAULT_PORT"; [ "$PORT_SOURCE" = "argument" ] || PORT_SOURCE="default" ;;
esac
BRIDGE_URL="http://127.0.0.1:${PORT}"

echo "Installing Agent Watch hooks..."
echo "  Bridge URL: ${BRIDGE_URL}"
echo "  Settings:   ${SETTINGS}"
echo ""

# Verify the bridge (not just anything — Gradio answers HTTP too) is reachable:
# the bridge's unauthenticated /ping identity probe always carries a bridgeId
# (/status requires a bearer token). --max-time bounds the whole request so a
# non-HTTP squatter on the port can't hang the installer.
if curl -s --connect-timeout 2 --max-time 4 "${BRIDGE_URL}/ping" 2>/dev/null | grep -q '"bridgeId"'; then
  echo "  Bridge status: RUNNING"
elif [ "$PORT_SOURCE" = "default" ]; then
  # No explicit port, no port file, nothing answering on 7860: we are guessing.
  # If 7860 is (or later gets) occupied by something else — Gradio, notably —
  # the bridge will bind 7861+ and hooks pinned to 7860 will post to the wrong
  # instance. Warn loudly instead of promising the hooks will "just work".
  echo "  Bridge status: NOT RUNNING"
  echo ""
  echo "  WARNING: no bridge port file found at ${PORT_FILE} and nothing is"
  echo "  answering on the default port ${DEFAULT_PORT}, so the hook URLs above are a"
  echo "  GUESS. If ${DEFAULT_PORT} is taken when the bridge starts (e.g. by Gradio),"
  echo "  the bridge will bind a different port and these hooks will post to"
  echo "  the wrong place."
  echo ""
  echo "  Fix: start the bridge first (cd skill/bridge && node server.js),"
  echo "  then re-run ./skill/setup-hooks.sh so it reads the actual port."
else
  echo "  Bridge status: NOT RUNNING (hooks will work once you start the bridge on port ${PORT})"
fi

mkdir -p "$(dirname "$SETTINGS")"

# Merge hooks into existing settings using Python (preserves existing config).
# All inputs travel via the environment — nothing is interpolated into the
# Python source, so a HOME containing quotes can't break or hijack the script.
CLAUDE_WATCH_SETTINGS="$SETTINGS" \
CLAUDE_WATCH_BRIDGE_URL="$BRIDGE_URL" \
CLAUDE_WATCH_HOOK_PATHS="$HOOK_PATHS" \
CLAUDE_WATCH_PERMISSION_TIMEOUT_S="$PERMISSION_HOOK_TIMEOUT_S" \
python3 - <<'PYEOF'
import json, os, re, stat

settings_path = os.environ['CLAUDE_WATCH_SETTINGS']
BRIDGE = os.environ['CLAUDE_WATCH_BRIDGE_URL']
PERMISSION_TIMEOUT_S = int(os.environ['CLAUDE_WATCH_PERMISSION_TIMEOUT_S'])
hook_paths = os.environ['CLAUDE_WATCH_HOOK_PATHS'].split()
claude_watch_url = re.compile(
    r'^http://127\.0\.0\.1:\d+/hooks/(?:' + '|'.join(map(re.escape, hook_paths)) + r')$')

# The hooks we want to install
new_hooks = {
    'PostToolUse': [{
        'hooks': [{
            'type': 'http',
            'url': f'{BRIDGE}/hooks/tool-output',
            'timeout': 5
        }]
    }],
    'PreToolUse': [{
        'hooks': [{
            'type': 'http',
            'url': f'{BRIDGE}/hooks/tool-output',
            'timeout': 5
        }]
    }],
    'PermissionRequest': [{
        'hooks': [{
            'type': 'http',
            'url': f'{BRIDGE}/hooks/permission',
            'timeout': PERMISSION_TIMEOUT_S
        }]
    }],
    'Stop': [{
        'hooks': [{
            'type': 'http',
            'url': f'{BRIDGE}/hooks/stop',
            'timeout': 5
        }]
    }],
    'SessionEnd': [{
        'hooks': [{
            'type': 'http',
            'url': f'{BRIDGE}/hooks/session-end',
            'timeout': 5
        }]
    }],
    'PostToolUseFailure': [{
        'hooks': [{
            'type': 'http',
            'url': f'{BRIDGE}/hooks/error',
            'timeout': 5
        }]
    }],
    'StopFailure': [{
        'hooks': [{
            'type': 'http',
            'url': f'{BRIDGE}/hooks/error',
            'timeout': 5
        }]
    }],
    'Notification': [{
        'matcher': 'idle_prompt|permission_prompt',
        'hooks': [{
            'type': 'http',
            'url': f'{BRIDGE}/hooks/notification',
            'timeout': 5
        }]
    }]
}


def is_ours(hook):
    url = hook.get('url')
    return isinstance(url, str) and bool(claude_watch_url.match(url))


def strip_claude_watch_hooks(hooks):
    """Drop OUR hook objects; entries keeping any user hooks survive intact."""
    changed = False
    for event in list(hooks.keys()):
        entries = []
        event_changed = False
        for entry in hooks[event]:
            hook_objs = entry.get('hooks', [])
            kept = [h for h in hook_objs if not is_ours(h)]
            if len(kept) == len(hook_objs):
                entries.append(entry)
                continue
            event_changed = True
            if kept:
                trimmed = dict(entry)
                trimmed['hooks'] = kept
                entries.append(trimmed)
        if event_changed:
            changed = True
            if entries:
                hooks[event] = entries
            else:
                del hooks[event]
    return changed


def write_settings_atomically(path, data):
    """Temp file in the same directory + rename: readers only ever see either
    the old complete file or the new complete file, never a partial write.

    The rename discards the original file's permissions (the temp file's
    umask-derived mode would win), and settings.json can carry secrets (env
    vars, apiKeyHelper config) that users protect with e.g. chmod 600 — so
    replicate the existing mode onto the temp file BEFORE the rename. A brand
    new settings file keeps the umask default, same as a plain open(path, 'w').

    Resolve symlinks first: dotfiles managers (stow, chezmoi, ...) commonly
    make ~/.claude/settings.json a symlink into a repo. os.replace on the
    symlink itself would swap the link for a regular file, silently detaching
    the user's dotfiles setup — so rewrite the link's TARGET instead, exactly
    like the plain open(path, 'w') this replaced used to. (os.stat below
    follows symlinks anyway, so the mode logic is unaffected.)"""
    path = os.path.realpath(path)
    try:
        mode = stat.S_IMODE(os.stat(path).st_mode)
    except FileNotFoundError:
        mode = None
    tmp = f'{path}.tmp.{os.getpid()}'
    try:
        with open(tmp, 'w') as f:
            if mode is not None:
                os.fchmod(f.fileno(), mode)
            json.dump(data, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


# HOOK_PATHS drives the removal/dedup matcher: every URL we are about to
# install must be covered by it, or a later removal would leave strays.
for entries in new_hooks.values():
    for entry in entries:
        for hook in entry['hooks']:
            assert is_ours(hook), f"hook URL not covered by HOOK_PATHS: {hook['url']}"

try:
    with open(settings_path) as f:
        settings = json.load(f)
except FileNotFoundError:
    settings = {}

existing_hooks = settings.get('hooks', {})

# Dedup any previous claude-watch install (possibly targeting a different
# port — the bridge walks the range) without touching the user's own hooks:
# object-level filtering on exact claude-watch URLs only.
strip_claude_watch_hooks(existing_hooks)

# Merge: add our hooks without removing user's existing hooks
for event, entries in new_hooks.items():
    existing_hooks.setdefault(event, []).extend(entries)

settings['hooks'] = existing_hooks

write_settings_atomically(settings_path, settings)

print('Hooks installed successfully!')
print()
print('Events hooked:')
for event in new_hooks:
    print(f'  • {event}')
PYEOF

echo ""

# ── Codex hooks ──────────────────────────────────────────────────────────────

CODEX_CONFIG="$HOME/.codex/config.toml"

if command -v codex &>/dev/null; then
  echo "Codex detected. Installing Codex hooks..."
  mkdir -p "$(dirname "$CODEX_CONFIG")"

  # Codex doesn't have HTTP hooks like Claude Code.
  # Instead, create a wrapper script that pipes --json events to the bridge.
  WRAPPER="$HOME/.local/bin/codex-watch"
  mkdir -p "$(dirname "$WRAPPER")"

  cat > "$WRAPPER" << 'WRAPPER_EOF'
#!/bin/bash
# codex-watch: Runs Codex and streams events to Agent Watch bridge.
# Drop-in replacement for `codex` — use `codex-watch` instead.
#
# The bridge may not be on 7860 (it walks up when the default port is taken)
# and publishes its actual bound port to a port file on startup. Resolve at
# launch: CLAUDE_WATCH_PORT env > port file > default 7860.
BRIDGE_PORT="$CLAUDE_WATCH_PORT"
if [ -z "$BRIDGE_PORT" ]; then
  PORT_FILE="${CLAUDE_WATCH_CREDENTIALS_DIR:-$HOME/.claude-watch}/port"
  if [ -f "$PORT_FILE" ]; then
    BRIDGE_PORT="$(tr -cd '0-9' < "$PORT_FILE")"
  fi
fi
case "$BRIDGE_PORT" in
  ''|*[!0-9]*) BRIDGE_PORT=7860 ;;
esac
BRIDGE_URL="http://127.0.0.1:${BRIDGE_PORT}"

# If the bridge isn't running (a bridgeId in the unauthenticated /ping probe
# is the tell — some other service like Gradio may hold the port, and /status
# requires a bearer token), just run codex normally.
if ! curl -s --connect-timeout 1 --max-time 3 "${BRIDGE_URL}/ping" 2>/dev/null | grep -q '"bridgeId"'; then
  exec codex "$@"
fi

# For non-exec commands (login, mcp, etc), run directly
case "$1" in
  exec|e) ;; # continue to bridge mode
  "") ;; # interactive — can't bridge, run normally
  *) exec codex "$@" ;;
esac

# Run codex exec with --json and pipe to bridge
codex "$@" --json 2>/dev/null | while IFS= read -r line; do
  TYPE=$(echo "$line" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('type',''))" 2>/dev/null || true)
  case "$TYPE" in
    item.completed)
      # Forward the whole event — let the bridge parse it
      curl -s -X POST "${BRIDGE_URL}/hooks/tool-output" \
        -H "Content-Type: application/json" \
        -d "$(echo "$line" | python3 -c "
import sys,json
e=json.load(sys.stdin)
item=e.get('item',{})
t=item.get('type','')
out={}
if t=='command_execution':
    out={'tool_name':'Bash','tool_input':{'command':item.get('command','')},'tool_output':item.get('aggregated_output',''),'source':'codex'}
elif t in ('file_edit','file_create'):
    out={'tool_name':'Edit','tool_input':{'file_path':item.get('file_path','')},'source':'codex'}
elif t=='file_read':
    out={'tool_name':'Read','tool_input':{'file_path':item.get('file_path','')},'source':'codex'}
elif t=='agent_message':
    out={'tool_name':'CodexMessage','tool_input':{},'tool_output':item.get('text',''),'source':'codex'}
if out:
    print(json.dumps(out))
else:
    print('{}')
" 2>/dev/null)" > /dev/null 2>&1 &
      ;;
    turn.completed)
      curl -s -X POST "${BRIDGE_URL}/hooks/stop" \
        -H "Content-Type: application/json" \
        -d '{"source":"codex"}' > /dev/null 2>&1 &
      ;;
  esac
done
WRAPPER_EOF

  chmod +x "$WRAPPER"
  echo "  Created: $WRAPPER"
  echo "  Use 'codex-watch exec \"prompt\"' instead of 'codex exec'"
  echo ""
else
  echo "Codex not detected — skipping Codex hooks."
  echo ""
fi

echo "Done! Sessions will stream to the bridge."
echo ""
echo "Usage:"
echo "  1. Start bridge:  cd skill/bridge && node server.js"
echo "  2. Claude Code:   just use normally (hooks auto-forward)"
echo ""
echo "To remove:  ./setup-hooks.sh --remove"
