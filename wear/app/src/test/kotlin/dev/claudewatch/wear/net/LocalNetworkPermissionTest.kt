package dev.claudewatch.wear.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The #29 SDK gate, pinned across the boundary. Live Android-16/17 images
 * cannot run in this harness (the Wear e2e emulator tops out below API 36),
 * so the JVM-tested pure halves ARE the verification: the gate must be
 * closed on every SDK that ships today — including 36, where local-network
 * protection exists only as an adb-only compat opt-in — and open from 37,
 * where ACCESS_LOCAL_NETWORK is the platform's real runtime gate.
 */
class LocalNetworkPermissionTest {

    // -- The boundary itself --------------------------------------------------

    @Test
    fun gateIsClosedOnEveryShippedSdkIncludingAndroid16() {
        // minSdk 30 through API 36: no platform in this range gates LAN access
        // in production, so the ask must not exist — a request below 37 would
        // auto-deny with no dialog (the name is unknown to the platform) and
        // poison the flow with a fake permanent denial.
        for (sdk in 30..36) {
            assertFalse("API $sdk must not gate", LocalNetworkPermission.gatesLocalNetwork(sdk))
        }
    }

    @Test
    fun gateOpensAtApi37AndStaysOpen() {
        for (sdk in intArrayOf(37, 38, 40)) {
            assertTrue("API $sdk gates LAN access", LocalNetworkPermission.gatesLocalNetwork(sdk))
        }
    }

    // -- The pure ask decision ------------------------------------------------

    @Test
    fun asksOnlyWhenThePlatformGatesAndTheGrantIsMissing() {
        assertTrue(LocalNetworkPermission.needsRequest(37, granted = false))
        assertFalse("a held grant never re-asks", LocalNetworkPermission.needsRequest(37, granted = true))
    }

    @Test
    fun neverAsksBelowTheGateEvenThoughTheUnknownPermissionReadsAsDenied() {
        // Below 37 checkSelfPermission reports the (platform-unknown, though
        // manifest-declared) permission as DENIED — the gate must dominate, or
        // every watch that exists today would surface a dead ask.
        assertFalse(LocalNetworkPermission.needsRequest(34, granted = false))
        assertFalse(LocalNetworkPermission.needsRequest(36, granted = false))
    }
}
