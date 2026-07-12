package dev.claudewatch.wear.net

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device coverage of the production held-Wi-Fi mechanism (the simulated
 * path failure that TRIGGERS escalation is covered engine-side by
 * DiscoveryTest against the [NetworkEscalator] interface; this test pins what
 * [WifiNetworkEscalator] itself then does with the platform):
 *
 *  - escalate() registers exactly ONE NetworkRequest, and it asks for
 *    TRANSPORT_WIFI (a cellular/BT request would hold the wrong radio);
 *  - escalate() is idempotent — repeated path-broken retries must not stack
 *    platform requests that a single release() then cannot drop;
 *  - release() unregisters the held callback, is idempotent, and never throws
 *    (it runs inside the engine's teardown path);
 *  - a SecurityException from requestNetwork degrades cleanly: nothing is
 *    held, release() stays a no-op, and a later escalate() may try again.
 *
 * [NetworkRequest] instances are built by real framework code, so this runs
 * instrumented; the recording fake stands in only for the two
 * ConnectivityManager binder calls (whose class cannot be faked directly).
 */
@RunWith(AndroidJUnit4::class)
class WifiNetworkEscalatorTest {

    private class RecordingConnectivity : WifiNetworkEscalator.Connectivity {
        val registered = mutableListOf<Pair<NetworkRequest, ConnectivityManager.NetworkCallback>>()
        val unregistered = mutableListOf<ConnectivityManager.NetworkCallback>()
        var throwOnRequest: RuntimeException? = null
        var throwOnUnregister: RuntimeException? = null

        override fun requestNetwork(
            request: NetworkRequest,
            callback: ConnectivityManager.NetworkCallback,
        ) {
            throwOnRequest?.let { throw it }
            registered.add(request to callback)
        }

        override fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            throwOnUnregister?.let { throw it }
            unregistered.add(callback)
        }
    }

    @Test
    fun escalateHoldsExactlyOneWifiRequestAndIsIdempotent() {
        val connectivity = RecordingConnectivity()
        val escalator = WifiNetworkEscalator(connectivity)

        escalator.escalate()
        escalator.escalate() // reconnect loop retries while the path stays broken
        escalator.escalate()

        assertEquals("repeat escalations must not stack requests", 1, connectivity.registered.size)
        val request = connectivity.registered.single().first
        assertTrue(
            "the held request must ask for Wi-Fi — that is the radio the LAN bridge lives on",
            request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        )
        assertFalse(
            "the request must not also match cellular (metered radio held by accident)",
            request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
    }

    @Test
    fun releaseUnregistersTheHeldCallbackAndIsIdempotent() {
        val connectivity = RecordingConnectivity()
        val escalator = WifiNetworkEscalator(connectivity)

        escalator.release() // release with nothing held: quiet no-op
        assertEquals(0, connectivity.unregistered.size)

        escalator.escalate()
        escalator.release()
        escalator.release() // teardown paths can release twice (stop + shutdown)

        assertEquals("exactly the held callback is unregistered, once", 1, connectivity.unregistered.size)
        assertSame(connectivity.registered.single().second, connectivity.unregistered.single())

        // A new escalation after release registers a FRESH callback.
        escalator.escalate()
        assertEquals(2, connectivity.registered.size)
        assertNotSame(connectivity.registered[0].second, connectivity.registered[1].second)
    }

    @Test
    fun releaseNeverThrowsEvenWhenThePlatformDoes() {
        val connectivity = RecordingConnectivity()
        val escalator = WifiNetworkEscalator(connectivity)
        escalator.escalate()

        // "Already unregistered" is an IllegalArgumentException from the real
        // ConnectivityManager; release() runs inside engine teardown and must
        // swallow it.
        connectivity.throwOnUnregister = IllegalArgumentException("NetworkCallback was not registered")
        escalator.release()

        // The hold is considered dropped: no retry loop against a callback
        // the platform already forgot.
        connectivity.throwOnUnregister = null
        escalator.release()
        assertEquals(0, connectivity.unregistered.size)
    }

    @Test
    fun securityExceptionDegradesToNothingHeldAndStaysReleasable() {
        val connectivity = RecordingConnectivity()
        val escalator = WifiNetworkEscalator(connectivity)

        // A profile that denies CHANGE_NETWORK_STATE: best-effort means the
        // engine keeps its ordinary reconnect loop, nothing crashes, nothing
        // is held.
        connectivity.throwOnRequest = SecurityException("missing CHANGE_NETWORK_STATE")
        escalator.escalate()
        assertEquals(0, connectivity.registered.size)

        escalator.release() // engine teardown after the denied escalation
        assertEquals("nothing was held, nothing to unregister", 0, connectivity.unregistered.size)

        // The denial was not latched: once permitted, escalation works again.
        connectivity.throwOnRequest = null
        escalator.escalate()
        assertEquals(1, connectivity.registered.size)
        escalator.release()
        assertEquals(1, connectivity.unregistered.size)
    }

    @Test
    fun smokeTestAgainstTheRealConnectivityManager() {
        // The production constructor against the real system service: an
        // escalate/release cycle (and their idempotent repeats) must not
        // throw on-device — this is the exact call sequence the engine makes
        // across a path-broken episode.
        val escalator =
            WifiNetworkEscalator(ApplicationProvider.getApplicationContext<android.content.Context>())
        escalator.escalate()
        escalator.escalate()
        escalator.release()
        escalator.release()
    }
}
