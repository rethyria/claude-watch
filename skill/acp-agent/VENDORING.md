# Vendored fork — claude-agent-acp

Upstream: https://github.com/agentclientprotocol/claude-agent-acp (branch main)
Commit:   809d41c6b7c9e7ba3cb5b206d00793a70edba64a
Vendored: 2026-07-22 for claude-watch epic #74 / slice S1 #75
License:  Apache-2.0 (see LICENSE)

Re-pull: fetch upstream at a newer commit and re-apply our local changes.
Local modifications (added in later slices, keep MINIMAL + clearly marked):
- S3 #77: injectUserPrompt seam (factored from prompt()) + bridge loopback side-channel
  (register session incl. SDK session_id; tap this.client sessionUpdate + requestPermission;
  deregister on queryClosed/closeSession/dispose).
