#!/usr/bin/env bash
# Wear walking-skeleton e2e: runs INSIDE the emulator-runner `script` step,
# i.e. after the Wear AVD has fully booted (so the 5-minute pairing-code TTL
# is not spent on emulator boot).
#
# Starts the real, unmodified bridge on the runner host, scrapes the port and
# pairing code from its stdout banner, and hands both to the instrumented
# test, which talks to the bridge via the emulator's host alias 10.0.2.2.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

# The session-id-scoped command path shells out to a `claude` binary
# (`claude -p <prompt> --continue` against the hook-created, PTY-less
# session). CI has no real Claude Code install, so provide a stub that just
# echoes — the assertion is about the bridge accepting the command (2xx),
# not about agent output.
mkdir -p "$HOME/.local/bin"
cat > "$HOME/.local/bin/claude" <<'EOF'
#!/usr/bin/env bash
echo "stub-claude invoked: $*"
EOF
chmod +x "$HOME/.local/bin/claude"
export PATH="$HOME/.local/bin:$PATH"

# Fresh credentials dir: the bridge must start unpaired and print a code.
export CLAUDE_WATCH_CREDENTIALS_DIR="$(mktemp -d)"

node skill/bridge/server.js > bridge.log 2>&1 &
BRIDGE_PID=$!
cleanup() {
  echo "--- bridge.log ---"
  cat bridge.log || true
  kill "$BRIDGE_PID" 2>/dev/null || true
}
trap cleanup EXIT

PORT=""
CODE=""
for _ in $(seq 1 60); do
  PORT="$(grep -oE 'Port:[[:space:]]+[0-9]+' bridge.log | grep -oE '[0-9]+' | head -1 || true)"
  CODE="$(grep -oE 'Pairing Code:[[:space:]]+[0-9]{6}' bridge.log | grep -oE '[0-9]{6}' | head -1 || true)"
  if [ -n "$PORT" ] && [ -n "$CODE" ]; then break; fi
  if ! kill -0 "$BRIDGE_PID" 2>/dev/null; then
    echo "bridge exited early" >&2
    exit 1
  fi
  sleep 1
done
if [ -z "$PORT" ] || [ -z "$CODE" ]; then
  echo "failed to scrape port/pairing code from bridge stdout" >&2
  exit 1
fi
echo "bridge up: port=$PORT code=$CODE"

cd wear
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.bridgeHost=10.0.2.2 \
  -Pandroid.testInstrumentationRunnerArguments.bridgePort="$PORT" \
  -Pandroid.testInstrumentationRunnerArguments.pairingCode="$CODE"
