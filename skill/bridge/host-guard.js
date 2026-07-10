// Host-header allow-list (DNS-rebinding guard).
// A browser on the LAN can be lured to a hostname whose DNS answer is later
// switched to this bridge's IP; same-origin policy doesn't help because the
// origin *is* the attacker's hostname. Rejecting requests whose Host header
// isn't one of the addresses this machine is actually reachable as closes
// that hole. The allow-list: localhost/loopback, every local interface
// address, 10.0.2.2 (the Android emulator's alias for its host — required so
// emulator-based Wear clients can reach a bridge on the same machine),
// bridge.internal (the Wear client's pinned synthetic hostname: it sends all
// bridge traffic to http://bridge.internal:<port> with DNS pinned to the
// paired IP, so Android's network security config can scope cleartext to
// exactly that name — .internal is ICANN-reserved for private use and never
// resolves on public DNS, so an attacker's page cannot present it as an
// origin), and any operator additions via CLAUDE_WATCH_ALLOWED_HOSTS /
// --allow-host=.
//
// The interface-derived half of the list must NOT be a startup snapshot: a
// laptop that switches networks or gets a new DHCP lease while the bridge
// runs is then reachable only under an address the snapshot never saw, and
// every request — including authenticated ones from already-paired watches —
// would be 403'd until a manual restart. Instead, a Host miss re-snapshots
// the interfaces (throttled, so a flood of attacker Hosts can't turn the
// guard into an os.networkInterfaces() amplifier) and re-checks, so a
// re-addressed bridge self-heals within one request.
//
// This module sits beside util.js at the bottom of the dependency graph and
// must not import any other bridge module.
import os from "node:os";

function normalizeHost(host) {
  // Hostnames compare case-insensitively; WHATWG URL keeps IPv6 literals
  // bracketed ("[::1]") but os.networkInterfaces() reports them bare.
  return host.toLowerCase().replace(/^\[|\]$/g, "");
}

export function createHostAllowList({
  extraHosts = [],
  getNetworkInterfaces = os.networkInterfaces,
  refreshMinIntervalMs = 1000,
  now = Date.now,
} = {}) {
  const staticHosts = new Set(["localhost", "127.0.0.1", "::1", "10.0.2.2", "bridge.internal"]);
  for (const host of extraHosts) {
    staticHosts.add(normalizeHost(host));
  }

  function snapshotInterfaceHosts() {
    const hosts = new Set();
    for (const addrs of Object.values(getNetworkInterfaces())) {
      for (const addr of addrs || []) {
        // Strip any IPv6 zone id; Host headers never carry one.
        hosts.add(addr.address.split("%")[0].toLowerCase());
      }
    }
    return hosts;
  }

  let interfaceHosts = snapshotInterfaceHosts();
  let lastSnapshotAt = now();

  // `hostname` comes from a WHATWG URL parse in the caller: lowercased, port
  // stripped, IPv6 still bracketed ("[::1]").
  return function isAllowedHost(hostname) {
    const host = normalizeHost(hostname);
    if (staticHosts.has(host) || interfaceHosts.has(host)) return true;
    // Miss: the machine's addresses may have changed since the last snapshot
    // (network switch, DHCP re-lease). Re-snapshot and re-check so the bridge
    // self-heals without a restart — but at most once per throttle window.
    if (now() - lastSnapshotAt < refreshMinIntervalMs) return false;
    interfaceHosts = snapshotInterfaceHosts();
    lastSnapshotAt = now();
    return interfaceHosts.has(host);
  };
}
