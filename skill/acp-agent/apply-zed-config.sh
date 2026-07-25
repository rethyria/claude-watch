#!/usr/bin/env bash
# Install / repair / verify the "Claude (watch)" agent_servers entry in Zed's
# (flatpak) settings.json. Run on the HOST.
#
#   ./apply-zed-config.sh            install or REPAIR the entry (idempotent)
#   ./apply-zed-config.sh --check    verify only; non-zero exit if anything is wrong
#   ./apply-zed-config.sh --remove   remove the entry
#
# Why repair rather than refuse: Zed's agent panel has rewritten this entry once
# already (2026-07-22 23:26), replacing the custom `command` with a
# `{"type":"registry"}` stub — which silently stops the adapter from launching at
# all, with nothing in Zed's logs (it does not log agent_servers activity). The
# previous version of this script bailed out whenever `agent_servers` existed,
# i.e. it could not fix the one failure it was most likely to meet. Now it
# rewrites just its own key and leaves any other agent untouched.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETTINGS="${ZED_SETTINGS:-$HOME/.var/app/dev.zed.Zed/config/zed/settings.json}"
LAUNCHER="$HERE/launch-claude-watch-acp.sh"
AGENT_NAME="Claude (watch)"

MODE="apply"
case "${1:-}" in
  --check)  MODE="check" ;;
  --remove) MODE="remove" ;;
  "")       MODE="apply" ;;
  *) echo "usage: $0 [--check|--remove]" >&2; exit 2 ;;
esac

[ -f "$SETTINGS" ] || { echo "Zed settings not found: $SETTINGS" >&2; exit 1; }

# Zed's settings.json is JSONC: comments AND trailing commas. json.load chokes on
# both, and rewriting the file from parsed JSON would destroy the user's comments
# and formatting. So: strip a COPY to validate, but edit the ORIGINAL text
# surgically via brace matching.
python3 - "$SETTINGS" "$LAUNCHER" "$AGENT_NAME" "$MODE" <<'PY'
import json, os, re, shutil, sys

settings_path, launcher, agent_name, mode = sys.argv[1:5]
text = open(settings_path, encoding="utf-8").read()


def strip_jsonc(s):
    """Comment/trailing-comma stripper that respects string literals."""
    out, i, n = [], 0, len(s)
    instr = esc = False
    while i < n:
        c = s[i]
        if instr:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                instr = False
            i += 1
            continue
        if c == '"':
            instr = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and s[i + 1] == "/":
            while i < n and s[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and s[i + 1] == "*":
            i += 2
            while i + 1 < n and not (s[i] == "*" and s[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return re.sub(r",(\s*[}\]])", r"\1", "".join(out))


def value_span(s, key):
    """(start_of_key, end_of_value) for `"key": { ... }`, brace-matched so a
    nested object (e.g. default_config_options) cannot end the span early."""
    m = re.search(r'"' + re.escape(key) + r'"\s*:\s*', s)
    if not m or m.end() >= len(s) or s[m.end()] != "{":
        return None
    i, depth = m.end(), 0
    instr = esc = False
    while i < len(s):
        c = s[i]
        if instr:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                instr = False
        else:
            if c == '"':
                instr = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return (m.start(), i + 1)
        i += 1
    return None


try:
    parsed = json.loads(strip_jsonc(text))
except Exception as e:
    print(f"FAIL: {settings_path} is not parseable even as JSONC: {e}", file=sys.stderr)
    sys.exit(1)

current = (parsed.get("agent_servers") or {}).get(agent_name)
current_cmd = current.get("command") if isinstance(current, dict) else None
current_type = current.get("type") if isinstance(current, dict) else None
# Both must hold. A `command` without `type: "custom"` looks right in the file
# but Zed will not register it as a custom agent — so the doctor must fail on it,
# or it green-lights the exact state that produces "is not registered".
ok = current_cmd == launcher and current_type == "custom"

if mode == "check":
    if ok:
        print(f"OK: '{agent_name}' -> {launcher}")
        sys.exit(0)
    if current is None:
        print(f"FAIL: '{agent_name}' is not configured in {settings_path}", file=sys.stderr)
    elif current_cmd is None:
        print(
            f"FAIL: '{agent_name}' has NO 'command' (found keys: {sorted(current)}). "
            "Zed has replaced it — probably with a registry stub — so the adapter cannot launch.",
            file=sys.stderr,
        )
    elif current_cmd != launcher:
        print(f"FAIL: '{agent_name}' points at {current_cmd}, expected {launcher}", file=sys.stderr)
    else:
        print(
            f"FAIL: '{agent_name}' has the right command but type={current_type!r}, expected 'custom'. "
            "Zed will not register an untagged entry as a custom agent server — opening a thread "
            "fails with \"Custom agent server ... is not registered\".",
            file=sys.stderr,
        )
    print("  Repair:  ./apply-zed-config.sh", file=sys.stderr)
    sys.exit(1)

# "type": "custom" is REQUIRED, not decorative. Zed's agent_servers schema is a
# tagged union — the binary carries `custom | command | env` alongside the
# registry variant's `default_config_options | favorite_config_option_values`.
# Without the tag Zed does not classify this as a custom agent server: opening a
# thread fails with `Custom agent server ... is not registered`, and the agent
# panel rewrites the entry into a `{"type": "registry"}` stub. That rewrite is
# what happened on 2026-07-22 and again on 2026-07-25.
entry = (
    f'"{agent_name}": {{\n'
    f'      "type": "custom",\n'
    f'      "command": "{launcher}",\n'
    f'      "args": []\n'
    f"    }}"
)

if mode == "remove":
    span = value_span(text, agent_name)
    if span is None:
        print(f"OK: '{agent_name}' was not present — nothing to remove.")
        sys.exit(0)
    start, end = span
    # Take a trailing comma with it if there is one, else a leading one.
    tail = re.match(r"\s*,", text[end:])
    if tail:
        end += tail.end()
    else:
        lead = re.search(r",\s*$", text[:start])
        if lead:
            start = lead.start()
    new_text = text[:start] + text[end:]
else:  # apply / repair
    if ok:
        print(f"OK: '{agent_name}' already correct — no change.")
        sys.exit(0)
    span = value_span(text, agent_name)
    if span is not None:
        start, end = span
        new_text = text[:start] + entry + text[end:]
        action = "repaired"
    else:
        blk = value_span(text, "agent_servers")
        if blk is not None:
            # agent_servers exists but has no entry of ours: insert after its '{'.
            open_brace = text.index("{", blk[0])
            sep = "" if text[open_brace + 1 :].lstrip().startswith("}") else ","
            new_text = (
                text[: open_brace + 1] + f"\n    {entry}{sep}" + text[open_brace + 1 :]
            )
            action = "added to existing agent_servers"
        else:
            m = re.search(r"^\s*\{\s*$", text, re.MULTILINE)
            if not m:
                print("FAIL: could not find the top-level '{' to insert into", file=sys.stderr)
                sys.exit(1)
            block = f'\n  "agent_servers": {{\n    {entry}\n  }},'
            new_text = text[: m.end()] + block + text[m.end() :]
            action = "installed"

# Never write something we cannot read back.
try:
    check = json.loads(strip_jsonc(new_text))
except Exception as e:
    print(f"FAIL: refusing to write — result would not parse: {e}", file=sys.stderr)
    sys.exit(1)
if mode != "remove":
    written = (check.get("agent_servers") or {}).get(agent_name, {})
    got, got_type = written.get("command"), written.get("type")
    if got != launcher or got_type != "custom":
        print(
            f"FAIL: refusing to write — post-edit entry is type={got_type!r} command={got!r}",
            file=sys.stderr,
        )
        sys.exit(1)

backup = settings_path + ".bak-claudewatch"
shutil.copy(settings_path, backup)
with open(settings_path, "w", encoding="utf-8") as f:
    f.write(new_text)
print(f"OK: {'removed' if mode == 'remove' else action} '{agent_name}' in {settings_path}")
print(f"     backup: {backup}")
PY

# Everything else the launch path needs. Reported for --check and after an apply,
# because a correct settings entry pointing at an unbuilt adapter still fails.
rc=0
[ -x "$LAUNCHER" ] || { echo "FAIL: launcher not executable: $LAUNCHER" >&2; rc=1; }
if [ ! -f "$HERE/dist/index.js" ]; then
  echo "FAIL: dist/ not built — run: (cd '$HERE' && npm ci && npm run build)" >&2
  rc=1
elif [ -n "$(find "$HERE/src" "$HERE/tsconfig.json" "$HERE/package.json" \
              -newer "$HERE/dist/index.js" -print -quit 2>/dev/null || true)" ]; then
  echo "FAIL: dist/ is STALE — run: (cd '$HERE' && npm run build)" >&2
  rc=1
fi
PORT_FILE="${CLAUDE_WATCH_CREDENTIALS_DIR:-$HOME/.claude-watch}/port"
if [ -f "$PORT_FILE" ]; then
  echo "note: bridge port file says $(cat "$PORT_FILE")"
else
  echo "note: no bridge port file at $PORT_FILE — start the bridge before using Zed"
fi
exit $rc
