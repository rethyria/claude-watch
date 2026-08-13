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

# No stub `claude` binary: nothing in this run ever execs one. The harness's
# stub existed for the hook-era headless prompt path (`claude -p --continue`
# against a hook-created, PTY-less session — retired in #81) and for PTY
# claude spawns (retired in the ACP-only spawn pivot: a watch spawn is born in
# the Zed fork, played here by the fake fork below, issue #107). The skeleton
# fabricates its own sessions over the ACP wire (#87), no codex leg PTY-spawns
# anything, and the wear app never reads the /ping agents list — so binary
# discovery finding nothing on CI costs only a startup warning.

# Throwaway-bridge isolation (issue #107; environment finding from #99's
# review): pin the bridge into a TEST-ONLY port range, UNCONDITIONALLY. The
# production range starts at 7860 — the live bridge's port — so with a
# developer's live bridge down the throwaway bridge binds 7860 itself, and
# the host's REAL Zed ACP adapter (which reconnects to the port its port
# file last named) walks straight into the test bridge: real editor sessions
# register into the census and the run fails on counts it never controlled.
# A range no real adapter is ever pointed at makes the impersonation
# impossible whether the live bridge is up or down. mDNS is disabled for the
# same reason from the discovery direction: a throwaway advertising the
# shared _claude-watch._tcp name can steal the live bridge's identity, and
# bonjour-service surfaces the name conflict as an uncaught error that kills
# whichever process loses the multicast coin flip.
export CLAUDE_WATCH_PORT_RANGE_START=7970
export CLAUDE_WATCH_PORT_RANGE_END=7999
export CLAUDE_WATCH_DISABLE_MDNS=1

# The bridge self-reaps once no fork inbox is connected for the grace window
# (#92). The fake fork below holds its inbox for the whole run, and the
# 15-minute default dwarfs the pre-fork scrape loop anyway — but this run's
# verdict must never depend on runner speed, so the reaper is pinned off.
export CLAUDE_WATCH_NO_IDLE_EXIT=1

# Fresh credentials dir: the bridge must start unpaired and print a code.
export CLAUDE_WATCH_CREDENTIALS_DIR="$(mktemp -d)"

# A UNIQUE log file, not repo-root bridge.log: a developer's live bridge often
# logs there, and sharing the path both interleaves two processes' output and
# poisons the scrape below (stray ANSI bytes make grep call the file binary).
# -a keeps the scrape working even if PTY escapes end up in OUR log.
BRIDGE_LOG="$(mktemp "${TMPDIR:-/tmp}/wear-e2e-bridge.XXXXXX")"
node skill/bridge/server.js > "$BRIDGE_LOG" 2>&1 &
BRIDGE_PID=$!
cleanup() {
  status=$?
  echo "--- bridge log ($BRIDGE_LOG) ---"
  cat "$BRIDGE_LOG" || true
  if [ -n "${FORK_LOG:-}" ]; then
    echo "--- fake fork log ($FORK_LOG) ---"
    cat "$FORK_LOG" || true
  fi
  # On a failure the bridge log only proves what DIDN'T arrive. The app writes
  # no logcat of its own (deliberately — nothing about a session should reach
  # the system log), so the only wrist-side evidence worth pulling is a crash
  # or an ANR; the app's own verdict reaches the report through the assertion
  # messages instead. A green run stays quiet.
  if [ "$status" -ne 0 ]; then
    echo "--- watch crashes/ANRs (empty is normal) ---"
    adb logcat -d -v time -b crash 2>/dev/null | tail -60 || true
    adb logcat -d -v time 2>/dev/null \
      | grep -aE "FATAL EXCEPTION|ANR in|claudewatch" | tail -40 || true
  fi
  # The fake fork dies WITH the throwaway bridge — never before its spawn
  # answer, never after the run.
  kill "${FORK_PID:-}" 2>/dev/null || true
  kill "$BRIDGE_PID" 2>/dev/null || true
}
trap cleanup EXIT

PORT=""
CODE=""
for _ in $(seq 1 60); do
  PORT="$(grep -a -oE 'Port:[[:space:]]+[0-9]+' "$BRIDGE_LOG" | grep -oE '[0-9]+' | head -1 || true)"
  CODE="$(grep -a -oE 'Pairing Code:[[:space:]]+[0-9]{6}' "$BRIDGE_LOG" | grep -oE '[0-9]{6}' | head -1 || true)"
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

# --- The fork side of the spawn leg (issue #107) ---------------------------
# Decision record: the WalkingSkeleton spawn leg runs against a FAKE Zed fork
# rather than being split into a self-skipping test. Since the ACP-only spawn
# pivot a claude spawn is born in the Zed-launched fork — the bridge only
# relays the request down its /acp/inbox and honestly 409s with no fork
# connected — so a forkless harness made the leg deterministically red (the
# "flaky, re-run it" habit masked it for weeks). The fake fork services each
# spawn frame with the real adapter's own wire moves (register detached →
# spawn-result → one greeting turn whose prose is the leg's feed evidence);
# the contract is pinned from both ends, because the bridge's tests fake the
# fork side (skill/bridge/test/acp-spawn.test.js) and the adapter's tests
# fake the bridge side (skill/acp-agent watch-spawn.test.ts). A self-skip was
# rejected: it would quietly retire the only end-to-end coverage of the
# wrist's spawn affordance while looking green. The fork talks ONLY to the
# throwaway bridge's scraped loopback port (inside the test-only range
# above), and the cleanup trap tears it down with the bridge.
FORK_LOG="$(mktemp "${TMPDIR:-/tmp}/wear-e2e-fake-fork.XXXXXX")"
node .github/scripts/wear-e2e-fake-fork.mjs "$PORT" > "$FORK_LOG" 2>&1 &
FORK_PID=$!
# Gate on the bridge SEEING the inbox before gradle starts: a spawn against a
# not-yet-connected fork 409s, and minutes of gradle warmup must not be what
# hides a fork that failed to come up.
for _ in $(seq 1 30); do
  if grep -aq "ACP fork inbox connected" "$BRIDGE_LOG"; then break; fi
  if ! kill -0 "$FORK_PID" 2>/dev/null; then break; fi
  sleep 1
done
if ! grep -aq "ACP fork inbox connected" "$BRIDGE_LOG"; then
  echo "fake fork never connected to the bridge's ACP inbox" >&2
  exit 1
fi
echo "fake fork up: pid=$FORK_PID"

cd wear
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.bridgeHost=10.0.2.2 \
  -Pandroid.testInstrumentationRunnerArguments.bridgePort="$PORT" \
  -Pandroid.testInstrumentationRunnerArguments.pairingCode="$CODE"

# --- The kill leg's proof (issue #88) ---------------------------------------
# The skeleton's close tap is a REAL kill now, not the local hide it was: the
# wrist's kill rides a `close` frame to the fork, whose deregister is what ends
# the slot. From inside the app both look identical (a card vanishes), so the
# evidence is checked here, on BOTH sides of the loopback channel — a silent
# regression to hide-only, or an unserviced frame the bridge times out on,
# would otherwise leave the leg green.
if ! grep -aq "ACP close requested" "$BRIDGE_LOG"; then
  echo "the wrist kill never became a close frame (bridge log)" >&2
  exit 1
fi
if ! grep -aq "inbox close request" "$FORK_LOG"; then
  echo "the close frame never reached the fake fork" >&2
  exit 1
fi
echo "kill leg: the close frame was seen on both sides of the inbox"
