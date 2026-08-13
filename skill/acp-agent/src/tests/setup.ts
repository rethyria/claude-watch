// Suite-wide pins (vitest setupFiles).
//
// CLAUDE_WATCH_NO_BRIDGE_SPAWN: starting a channel now ensures a bridge exists
// (#92) — a probe of the port file plus the bridge's real port-walk range, and
// a REAL detached `server.js` spawn on a miss. Unpinned, every test that calls
// channel.start() would knock on a developer's live bridge (the walk covers
// the production range) or worse, leak an actual bridge process out of a unit
// test. The bridge-spawn suite unpins it around its own isolated port range.
process.env.CLAUDE_WATCH_NO_BRIDGE_SPAWN = "1";
