#!/usr/bin/env bash
# claude-watch ACP adapter launcher — run on the HOST (via Zed's flatpak-spawn --host).
#
# Pins the session to the claude.ai subscription: scrubs every provider-routing env var
# so billing can NEVER divert to API / Bedrock / Vertex. (Zed's agent_servers `env` can
# only ADD vars, not unset them — hence this wrapper.) See S2 / issue #76.
set -euo pipefail

# Keep this list identical to PROVIDER_ROUTING_GUARD_VARS in src/index.ts — the
# launcher scrubs, the adapter refuses. Both are needed: the scrub only happens
# if you come through this script, and nothing stops Zed (or `npm start`) from
# exec'ing dist/index.js directly.
for v in ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN ANTHROPIC_BASE_URL \
         ANTHROPIC_BEDROCK_BASE_URL ANTHROPIC_VERTEX_BASE_URL ANTHROPIC_CUSTOM_HEADERS \
         CLAUDE_CODE_USE_BEDROCK CLAUDE_CODE_USE_VERTEX; do
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

# dist/ is what actually runs (it is .gitignore'd and NOT tracked, so it is only
# ever whatever this machine last compiled). A fresh clone gets one from the
# `prepare` script — npm runs it on install, so `npm ci` is enough (#82).
#
# An edit to src/ with no rebuild is a SILENT no-op: Zed keeps running the old
# adapter and you debug code that isn't executing. Refuse instead — deliberately
# not an auto-build, because putting tsc in Zed's agent-launch path trades a
# loud failure for a slow, flaky one. Note the refusal only covers LAUNCH: a
# rebuild while Zed is already running cannot be caught here, because the
# running process does not reload. Restart Zed after rebuilding.
# Escape hatch: CLAUDE_WATCH_SKIP_BUILD_CHECK=1.
DIST_ENTRY="$HERE/dist/index.js"
if [ -z "${CLAUDE_WATCH_SKIP_BUILD_CHECK:-}" ]; then
  if [ ! -f "$DIST_ENTRY" ]; then
    echo "claude-watch-acp: dist/ is missing — the adapter has never been built here." >&2
    echo "  Build it:  (cd '$HERE' && npm ci)   # the prepare script builds dist/" >&2
    exit 70
  fi
  # -print -quit stops at the first offender, so this stays O(1)-ish on a warm FS.
  STALE="$(find "$HERE/src" "$HERE/tsconfig.json" "$HERE/package.json" \
             -newer "$DIST_ENTRY" -print -quit 2>/dev/null || true)"
  if [ -n "$STALE" ]; then
    echo "claude-watch-acp: dist/ is STALE — refusing to launch the old adapter." >&2
    echo "  Newer than dist/index.js:  $STALE" >&2
    echo "  Rebuild:  (cd '$HERE' && npm run build)" >&2
    echo "  Override: CLAUDE_WATCH_SKIP_BUILD_CHECK=1" >&2
    exit 70
  fi
fi

# Opt this adapter into the claude-watch bridge loopback channel (S3 #77): the
# adapter registers each ACP session with the bridge and receives watch
# dictation. Unset, the fork behaves exactly like upstream claude-agent-acp.
export CLAUDE_WATCH_ACP=1

exec "$NODE" "$DIST_ENTRY" "$@"
