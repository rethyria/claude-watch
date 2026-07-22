#!/usr/bin/env bash
# Add the "Claude (watch)" agent_servers entry to Zed's (flatpak) settings.json.
# Backs up first; refuses if an agent_servers block already exists. Run on the HOST.
set -euo pipefail

S="$HOME/.var/app/dev.zed.Zed/config/zed/settings.json"
[ -f "$S" ] || { echo "Zed settings not found: $S" >&2; exit 1; }

if grep -q '"agent_servers"' "$S"; then
  echo "agent_servers already present in $S — not modifying (add 'Claude (watch)' by hand)." >&2
  exit 2
fi

BAK="$S.bak-claudewatch"
cp "$S" "$BAK"

# Insert a single-line agent_servers entry right after the first top-level '{'.
awk '
  ins==0 && /^[[:space:]]*\{[[:space:]]*$/ {
    print
    print "  \"agent_servers\": { \"Claude (watch)\": { \"command\": \"/home/deck/Development/claude-watch/skill/acp-agent/launch-claude-watch-acp.sh\", \"args\": [] } },"
    ins=1
    next
  }
  { print }
' "$BAK" > "$S"

echo "OK: added 'Claude (watch)' agent to $S"
echo "     backup: $BAK"
