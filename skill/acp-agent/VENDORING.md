# Vendored fork — claude-agent-acp

Upstream: https://github.com/agentclientprotocol/claude-agent-acp (branch main)
Commit: 809d41c6b7c9e7ba3cb5b206d00793a70edba64a
Vendored: 2026-07-22 for claude-watch epic #74 / slice S1 #75
License: Apache-2.0 (see LICENSE)

Re-pull: fetch upstream at a newer commit and re-apply our local changes.
Local modifications (added in later slices, keep MINIMAL + clearly marked):

- S3 #77: injectUserPrompt seam (factored from prompt()) + bridge loopback side-channel
  (register session incl. SDK session_id; tap this.client sessionUpdate + requestPermission;
  deregister on queryClosed/closeSession/dispose).
- Watch spawn (born-in-Zed sessions; the `// claude-watch:` blocks in acp-agent.ts):
  - `Session.detached` + `creationOpts.detached` in createSession. RE-PULL TRAP: `detached`
    is destructured OFF before `creationOpts` is spread into the SDK `Options` (the spread
    line uses `sdkCreationOpts`) — re-applying upstream's `...creationOpts` verbatim leaks
    an unknown key into the query options. Pinned by a vitest asserting the captured options.
  - `spawnDetachedSession` / `isSessionDetached` / `attachDetachedSession`, the pickup branch
    in `newSession` (claim → adopt live / resume dead / forfeit gone), and the detached-adopt
    branch in `getOrCreateSession` (adopt regardless of fingerprint — never teardown a live
    watch session over the editor's MCP list).
  - `runAcp`: client wired as tee(guard(base)) — tee OUTSIDE so the bridge mirror flows while
    `guardDetachedClient` (bridge-channel.ts) suppresses the editor leg; onSpawn wiring.
  - bridge-channel.ts is fork-owned (never reconciled): spawn frame handling, reportSpawnResult,
    takePendingPickup, `detached` through register + replay, guardDetachedClient.
