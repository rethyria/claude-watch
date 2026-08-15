package dev.claudewatch.wear.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android's local-network gate (issue #29). From API 37, every LAN socket
 * this app opens — the NSD multicast [BridgeDiscovery] rides, the pairing
 * POST, the SSE stream itself — sits behind the ACCESS_LOCAL_NETWORK
 * runtime permission. #29 was filed against "Wear OS 6 / Android 16", but
 * the shipped mechanism landed one release later: Android 16 (API 36)
 * carries local-network protection only as an adb-only compat opt-in
 * (`am compat enable RESTRICT_LOCAL_NETWORK`, gated on NEARBY_WIFI_DEVICES)
 * that no production watch ever enables, and the real, user-reachable
 * enforcement is API 37's ACCESS_LOCAL_NETWORK. So the gate here keys on 37
 * — asking on 36 would throw a dialog no platform requires — and the
 * NEARBY_WIFI_DEVICES test vehicle is deliberately not wired: it would put
 * a permanent "Nearby devices" ask in front of every API 33+ watch for a
 * developer-only compat flag the app cannot even detect.
 *
 * The SDK gate is load-bearing, not an optimisation: below 37 the
 * permission NAME is unknown to the platform, so a runtime request would
 * auto-deny with no dialog — poisoning the flow with a fake permanent
 * denial on every watch that exists today. Hence the pure
 * [gatesLocalNetwork]/[needsRequest] split: the sdkInt-taking halves are
 * JVM-tested across the boundary, the [Context] convenience only reads the
 * live grant state.
 *
 * Apps targeting below 37 (this one targets 34) hold the permission
 * IMPLICITLY on API 37+ (split off INTERNET, like the storage splits), so
 * [needsRequest] stays false there until the user revokes it in settings —
 * which is exactly "ask when, and only when, the platform requires it".
 */
object LocalNetworkPermission {
    /** compileSdk 35 predates the constant; the literal is pinned here. */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** First SDK whose platform gates LAN sockets behind [PERMISSION]. */
    const val FIRST_GATED_SDK = 37

    /** Pure SDK half of the gate — see the class doc for why 36 is OUT. */
    fun gatesLocalNetwork(sdkInt: Int): Boolean = sdkInt >= FIRST_GATED_SDK

    /** Pure decision: the platform gates LAN access AND the grant is missing. */
    fun needsRequest(sdkInt: Int, granted: Boolean): Boolean =
        gatesLocalNetwork(sdkInt) && !granted

    /** The live-device convenience the pairing surface and discovery use. */
    fun needsRequest(context: Context): Boolean =
        needsRequest(
            Build.VERSION.SDK_INT,
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED,
        )
}
