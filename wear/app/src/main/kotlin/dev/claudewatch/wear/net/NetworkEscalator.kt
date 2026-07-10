package dev.claudewatch.wear.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Escalation hook for a broken network PATH (as opposed to a stopped bridge).
 *
 * On Wear OS the default route is usually the Bluetooth phone proxy, which
 * cannot reach a bridge on the workstation's LAN when the phone is away or
 * the proxy is wedged. When the [ConnectionEngine]'s /v1/ping preflight
 * classifies a failure as path-broken (unreachable/timeout — NOT a connection
 * refusal, which means the host answered), it calls [escalate] to ask the
 * platform for a held Wi-Fi network; once the stream is healthy again it
 * calls [release]. Both calls are idempotent and may arrive on any thread.
 *
 * This interface exists so the JVM connection tests can observe the
 * escalation contract with a recording fake; [WifiNetworkEscalator] is the
 * production implementation.
 */
interface NetworkEscalator {
    /** The path to the bridge is broken: request (and hold) Wi-Fi. */
    fun escalate()

    /** The path recovered: drop the hold so the radio can sleep again. */
    fun release()

    companion object {
        /** For contexts with no platform network stack (JVM unit tests). */
        val NOOP: NetworkEscalator = object : NetworkEscalator {
            override fun escalate() {}
            override fun release() {}
        }
    }
}

/**
 * Production escalator: `ConnectivityManager.requestNetwork(TRANSPORT_WIFI)`
 * with a callback held for as long as the escalation lasts. Holding the
 * request is what keeps the watch's Wi-Fi radio up (Wear OS otherwise turns
 * it off aggressively to save battery); releasing unregisters the callback so
 * the platform is free to fall back to the Bluetooth proxy path.
 *
 * Escalation is best-effort: on a device/profile where the request is denied
 * it degrades to the ordinary reconnect loop over the default network.
 */
class WifiNetworkEscalator(context: Context) : NetworkEscalator {

    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun escalate() {
        synchronized(lock) {
            if (callback != null) return // already held
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {}
            try {
                connectivity.requestNetwork(request, cb)
            } catch (_: RuntimeException) {
                return // SecurityException etc.: escalation is best-effort
            }
            callback = cb
        }
    }

    override fun release() {
        synchronized(lock) {
            val cb = callback ?: return
            callback = null
            try {
                connectivity.unregisterNetworkCallback(cb)
            } catch (_: RuntimeException) {
                // Already unregistered: releasing must never crash the engine.
            }
        }
    }
}
