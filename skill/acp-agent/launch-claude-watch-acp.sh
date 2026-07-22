#!/usr/bin/env bash
# claude-watch ACP adapter launcher — run on the HOST (via Zed's flatpak-spawn --host).
#
# Pins the session to the claude.ai subscription: scrubs every provider-routing env var
# so billing can NEVER divert to API / Bedrock / Vertex. (Zed's agent_servers `env` can
# only ADD vars, not unset them — hence this wrapper.) See S2 / issue #76.
set -euo pipefail

for v in ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN ANTHROPIC_BASE_URL CLAUDE_CODE_USE_BEDROCK CLAUDE_CODE_USE_VERTEX; do
  if [ -n "${!v:-}" ]; then
    echo "claude-watch-acp: WARNING: $v was set — unsetting it to stay on the claude.ai subscription (no API billing)." >&2
  fi
  unset "$v" 2>/dev/null || true
done

NODE="${CLAUDE_WATCH_NODE:-}"
if [ -z "$NODE" ] || [ ! -x "$NODE" ]; then
  for c in "${HOME:-}/.local/bin/node" /home/deck/.local/bin/node "$(command -v node 2>/dev/null || true)"; do
    if [ -n "${c:-}" ] && [ -x "$c" ]; then NODE="$c"; break; fi
  done
fi
[ -x "$NODE" ] || { echo "claude-watch-acp: no usable node found (set CLAUDE_WATCH_NODE)" >&2; exit 127; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$NODE" "$HERE/dist/index.js" "$@"
