package dev.claudewatch.wear

import dev.claudewatch.wear.net.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chip text mapping (issue #24), pinned as a table: the OngoingActivity
 * chip is a sliver on a round watch face, so every label must stay one or
 * two short words — and Connecting/Reconnecting must collapse into the same
 * word, because from the wrist they are the same promise.
 */
class ServiceStatusTextTest {

    @Test
    fun mapsEveryConnectionStateToItsShortChipLabel() {
        assertEquals("connected", serviceStatusText(ConnectionState.Connected))
        assertEquals("reconnecting", serviceStatusText(ConnectionState.Connecting(attempt = 0)))
        assertEquals(
            "reconnecting",
            serviceStatusText(ConnectionState.Reconnecting(attempt = 3, reason = "stream failure: timeout")),
        )
        assertEquals("stopped", serviceStatusText(ConnectionState.Stopped))
        assertEquals("pairing", serviceStatusText(ConnectionState.Pairing))
        assertEquals("pair failed", serviceStatusText(ConnectionState.PairFailed("401 bad code")))
        assertEquals(
            "proto mismatch",
            serviceStatusText(ConnectionState.ProtoMismatch(bridgeProto = "2", minProto = 3)),
        )
        assertEquals(
            "auth expired",
            serviceStatusText(ConnectionState.AuthExpired("token rejected")),
        )
        assertEquals(
            "bridge mismatch",
            serviceStatusText(ConnectionState.BridgeMismatch(expectedBridgeId = "b-1", actualBridgeId = "b-2")),
        )
    }
}
