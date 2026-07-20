package dev.claudewatch.wear.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * mDNS/NSD discovery of the bridge's advertisement (issue #23), used for two
 * things: zero-typing pairing (discover ANY bridge on the LAN to pre-fill the
 * pairing form) and DHCP self-heal (after the stored ip goes dark, re-discover
 * the SAME bridge — filtered by its pinned bridgeId — at its new address).
 *
 * Why a seam at all: NSD discovery cannot be exercised on the emulator or in a
 * JVM unit test — the emulator's NAT drops host-LAN multicast, and the JVM has
 * no [NsdManager]/[ConnectivityManager]/[WifiManager]. So the framework glue
 * sits behind [NsdBridgeDiscovery.Platform] (a recording fake substitutes for
 * it) and the *logic* — the bridgeId filter, the confirm-by-ping gate, and the
 * release-on-every-path discipline — is JVM-testable. This mirrors
 * [NetworkEscalator] exactly: interface + [NOOP] for tests + a production impl
 * whose framework calls hide behind a nested internal seam.
 *
 * On Wear the default route is the Bluetooth phone proxy, which drops the LAN
 * multicast mDNS rides on. So discovery must run over the real Wi-Fi transport:
 * [NsdBridgeDiscovery] holds a WifiManager MulticastLock AND binds the process
 * to a transient TRANSPORT_WIFI network for the duration of a scan — both
 * released again the instant the scan ends, on EVERY path (see [discover]).
 */
interface BridgeDiscovery {
    /** A bridge found on the LAN and confirmed to answer /v1/ping right now. */
    data class Discovered(val hostIp: String, val port: Int, val bridgeId: String)

    /**
     * Run one discovery scan. [bridgeId] filters to a specific bridge (the
     * self-heal case — never relocate to a stranger) or is null to accept the
     * first bridge that answers (the zero-typing pairing case). Returns null on
     * timeout, on no answer, or in any context with no platform network stack
     * (the [NOOP], i.e. every JVM/emulator run). [timeoutMs] bounds the whole
     * scan — multicast is slow, so this is a hard ceiling, not a hint.
     */
    suspend fun discover(bridgeId: String?, timeoutMs: Long): Discovered?

    companion object {
        /**
         * One multicast scan is ~this long. Kept modest: a self-heal fires at
         * most once per backoff window (~30 s cap), so a 6 s scan there is
         * self-limiting, and a pairing-form pre-fill wants an answer fast.
         */
        const val DEFAULT_TIMEOUT_MS = 6_000L

        /** For contexts with no platform network stack (JVM unit tests, emulator). */
        val NOOP: BridgeDiscovery = object : BridgeDiscovery {
            override suspend fun discover(bridgeId: String?, timeoutMs: Long): Discovered? = null
        }
    }
}

/**
 * Production discovery over [NsdManager]. Structured so ALL of the reviewable
 * logic is JVM-testable against a recording [Platform]:
 *
 *  - the bridgeId filter (cheap TXT pre-filter, then a confirm-by-ping that
 *    proves the candidate is a live bridge with the pinned identity — mDNS
 *    caches go stale across a DHCP move, so an advertisement alone is not
 *    trusted);
 *  - RESOURCE DISCIPLINE (issue #23 acceptance criterion 3, the #1 review
 *    target): the MulticastLock, the process Wi-Fi bind AND the NSD browse are
 *    each released in a single `finally` in reverse acquire order, so a throw,
 *    a timeout, a no-match or a cancellation all release everything. A stranded
 *    `bindProcessToNetwork` would pin the WHOLE process (the live SSE stream
 *    included) to a possibly-dead transient network — hence the finally, and
 *    hence the [inFlight] single-flight so two scans never fight over the
 *    process-global bind.
 *
 * [Platform] is the framework seam (three verbs, each yielding a [Closeable]);
 * [SystemPlatform] is the real glue and is HITL-only — never constructed in a
 * unit test. [confirm] is the second seam: the /v1/ping cross-check, injected
 * so the confirm gate is testable without a real bridge.
 */
class NsdBridgeDiscovery internal constructor(
    private val platform: Platform,
    private val confirm: suspend (hostIp: String, port: Int) -> String?,
) : BridgeDiscovery {

    constructor(context: Context) : this(
        SystemPlatform(context.applicationContext),
        // Confirm over /v1/ping on the REAL Wi-Fi transport (the process bind is
        // still held while confirm runs): a resolved candidate is trusted only
        // once it answers like a bridge NOW and yields its authoritative
        // bridgeId — the same [BridgePing] shape gate pairing/preflight use.
        confirm = { hostIp, port -> withContext(Dispatchers.IO) { pingBridgeId(hostIp, port) } },
    )

    /** A resolved NSD service: its endpoint plus the TXT `bridgeId` (null if TXT unread). */
    internal data class Candidate(val hostIp: String, val port: Int, val bridgeId: String?)

    /**
     * The three framework surfaces discovery touches, each as a [Closeable] so
     * the caller owns release ordering. Behind this seam live: WifiManager's
     * MulticastLock, ConnectivityManager.requestNetwork(TRANSPORT_WIFI) +
     * bindProcessToNetwork, and NsdManager.discoverServices + resolveService.
     */
    internal interface Platform {
        /** Acquire the multicast lock (mDNS RX is dropped without it). Closeable releases it. */
        fun acquireMulticastLock(): Closeable

        /**
         * Request a TRANSPORT_WIFI network and bind the process to it, blocking
         * up to [timeoutMs] for the radio to come up. Closeable unbinds
         * (`bindProcessToNetwork(null)`) and unregisters the callback. Throws if
         * the network never arrives — the caller's finally still releases the
         * already-held multicast lock.
         */
        suspend fun bindWifiProcess(timeoutMs: Long): Closeable

        /** Start NSD discovery+resolve; the handle streams resolved candidates and stops on close. */
        fun startBrowse(): Browse

        interface Browse : Closeable {
            /** Collect every candidate resolved within [timeoutMs], then return. */
            suspend fun awaitCandidates(timeoutMs: Long): List<Candidate>
        }
    }

    // bindProcessToNetwork is process-global, so two overlapping scans would
    // fight over the bind. The engine only ever launches one self-heal at a
    // time, but discoverForPairing could race it — this is the belt-and-braces.
    private val inFlight = AtomicBoolean(false)

    override suspend fun discover(bridgeId: String?, timeoutMs: Long): BridgeDiscovery.Discovered? {
        if (!inFlight.compareAndSet(false, true)) return null
        try {
            // Holders declared BEFORE the try so a partial acquisition (e.g.
            // bindWifiProcess throws after the lock is held) still releases only
            // what was actually acquired — never a null.close(), never a leak.
            var lock: Closeable? = null
            var bind: Closeable? = null
            var browse: Platform.Browse? = null
            try {
                lock = platform.acquireMulticastLock()
                bind = platform.bindWifiProcess(timeoutMs)
                browse = platform.startBrowse()
                for (candidate in browse.awaitCandidates(timeoutMs)) {
                    // Cheap TXT pre-filter: skip an obviously-foreign bridge with
                    // no network round-trip. A candidate with no readable TXT
                    // bridgeId falls through to confirm, which pings for its real
                    // identity rather than treating absence as a match.
                    if (bridgeId != null && candidate.bridgeId != null && candidate.bridgeId != bridgeId) {
                        continue
                    }
                    // Authoritative gate: it must answer /v1/ping like a bridge
                    // right now (rejects a decoy or a stale advertisement).
                    val confirmedId = confirm(candidate.hostIp, candidate.port) ?: continue
                    // Defense in depth over the TXT pre-filter: never relocate to
                    // a stranger even if a candidate's TXT lied or was empty.
                    if (bridgeId != null && confirmedId != bridgeId) continue
                    return BridgeDiscovery.Discovered(candidate.hostIp, candidate.port, confirmedId)
                }
                return null
            } finally {
                // Criterion 3: release EVERYTHING on EVERY path — success, no
                // match, timeout, exception, cancellation. Reverse acquire order,
                // each in its own runCatching so one failing release cannot
                // strand the others (an unbind that throws must never leave the
                // multicast lock — or the radio — held).
                runCatching { browse?.close() }
                runCatching { bind?.close() }
                runCatching { lock?.close() }
            }
        } finally {
            inFlight.set(false)
        }
    }

    /**
     * Real framework glue — HITL-only, never constructed in a unit test (the
     * JVM has no NsdManager/ConnectivityManager/WifiManager, and the emulator's
     * NAT drops LAN multicast anyway). Kept as thin as possible: all decisions
     * live in [discover] above, this only wraps the platform calls as
     * [Closeable]s.
     */
    private class SystemPlatform(context: Context) : Platform {
        private val connectivity =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        override fun acquireMulticastLock(): Closeable {
            val lock = wifi.createMulticastLock("claude-watch-nsd").apply {
                setReferenceCounted(false)
                acquire()
            }
            return Closeable { runCatching { if (lock.isHeld) lock.release() } }
        }

        override suspend fun bindWifiProcess(timeoutMs: Long): Closeable {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val available = CompletableDeferred<Network>()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    available.complete(network)
                }
            }
            connectivity.requestNetwork(request, callback)
            val network = try {
                withTimeout(timeoutMs) { available.await() }
            } catch (e: Throwable) {
                // Never got Wi-Fi: unregister and let the caller's finally drop
                // the multicast lock. (The bind itself was never applied.)
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                throw e
            }
            connectivity.bindProcessToNetwork(network)
            return Closeable {
                runCatching { connectivity.bindProcessToNetwork(null) }
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }

        override fun startBrowse(): Platform.Browse = SystemBrowse(nsd)
    }

    /**
     * One NSD browse+resolve session. [NsdManager.resolveService] pre-API-34
     * serves ONE resolve at a time ("listener already in use"), so resolves are
     * serialized through a small queue. Resolved candidates stream over a
     * channel that [awaitCandidates] drains until [timeoutMs]; [close] stops
     * discovery. HITL-only.
     */
    private class SystemBrowse(private val nsd: NsdManager) : Platform.Browse {
        private val candidates = Channel<Candidate>(Channel.UNLIMITED)
        private val lock = Any()
        private val pending = ArrayDeque<NsdServiceInfo>()
        private var resolving = false
        private var started = false

        private val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                candidates.close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        init {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            started = true
        }

        private fun enqueue(info: NsdServiceInfo) {
            synchronized(lock) { pending.addLast(info) }
            pump()
        }

        private fun pump() {
            val next = synchronized(lock) {
                if (resolving) return
                val head = pending.removeFirstOrNull() ?: return
                resolving = true
                head
            }
            @Suppress("DEPRECATION")
            nsd.resolveService(next, resolveListener())
        }

        @Suppress("DEPRECATION")
        private fun resolveListener() = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) { resolving = false }
                pump()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                val id = info.attributes?.get("bridgeId")?.toString(Charsets.UTF_8)
                if (host != null) candidates.trySend(Candidate(host, info.port, id))
                synchronized(lock) { resolving = false }
                pump()
            }
        }

        override suspend fun awaitCandidates(timeoutMs: Long): List<Candidate> {
            val out = mutableListOf<Candidate>()
            // Drain until the window closes (multicast has no "done" signal, so
            // the timeout IS the settle). Cancellation propagates out to
            // discover()'s finally, which stops the browse.
            withTimeoutOrNull(timeoutMs) {
                for (c in candidates) out.add(c)
            }
            return out
        }

        override fun close() {
            if (started) runCatching { nsd.stopServiceDiscovery(discoveryListener) }
            candidates.close()
        }
    }

    companion object {
        /**
         * The bridge advertises type "claude-watch" over TCP (skill/bridge:
         * server.js publish) → the mDNS fqdn `_claude-watch._tcp`. Passed to
         * discoverServices WITHOUT a trailing dot; the resolved
         * NsdServiceInfo.serviceType is reformatted inconsistently by the
         * framework, so candidates are identified by their TXT/ping bridgeId,
         * never by re-matching this string.
         */
        internal const val SERVICE_TYPE = "_claude-watch._tcp"

        /** The default [confirm]: /v1/ping the candidate and return its bridgeId, or null. */
        private fun pingBridgeId(hostIp: String, port: Int): String? =
            try {
                BridgePing.from(BridgeClient(hostIp, port).ping())?.bridgeId
            } catch (_: Exception) {
                // Non-private literal (BridgeClient ctor), unreachable, decoy: not a bridge.
                null
            }
    }
}
