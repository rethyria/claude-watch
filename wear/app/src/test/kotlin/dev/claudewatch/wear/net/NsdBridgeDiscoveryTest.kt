package dev.claudewatch.wear.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The JVM-testable half of NsdBridgeDiscovery (issue #23): the bridgeId filter,
 * the confirm-by-ping gate, and — the #1 review target — RESOURCE DISCIPLINE:
 * the multicast lock, the process Wi-Fi bind and the NSD browse are released on
 * EVERY exit path (success, no match, browse throw, bind throw, cancellation).
 * A stranded process Wi-Fi bind would pin the whole process to a possibly-dead
 * transient network, so this is acceptance criterion 3.
 *
 * The real framework glue (NsdBridgeDiscovery.SystemPlatform: multicast, the
 * TRANSPORT_WIFI bind, NsdManager) is HITL-only and NOT exercised here — the
 * emulator's NAT drops LAN multicast and the JVM has no NsdManager. A
 * RecordingPlatform records the acquire/release ordering and serves canned
 * candidates in its place; a RecordingConfirm stands in for the /v1/ping gate.
 */
class NsdBridgeDiscoveryTest {

    private fun cand(host: String, port: Int, bridgeId: String?) =
        NsdBridgeDiscovery.Candidate(host, port, bridgeId)

    /**
     * Records acquire/release order in [events] and serves canned candidates.
     * Optionally throws at the bind or browse rung, or delays awaitCandidates
     * so a scan can be cancelled while it is blocked in the browse.
     */
    private class RecordingPlatform(
        private val candidates: List<NsdBridgeDiscovery.Candidate> = emptyList(),
        private val bindThrows: Boolean = false,
        private val browseThrows: Boolean = false,
        private val awaitDelayMs: Long = 0,
    ) : NsdBridgeDiscovery.Platform {
        val events = CopyOnWriteArrayList<String>()

        override fun acquireMulticastLock(): Closeable {
            events.add("lock.acquire")
            return Closeable { events.add("lock.release") }
        }

        override suspend fun bindWifiProcess(timeoutMs: Long): Closeable {
            events.add("bind.acquire")
            if (bindThrows) throw RuntimeException("bind timeout")
            return Closeable { events.add("bind.release") }
        }

        override fun startBrowse(): NsdBridgeDiscovery.Platform.Browse {
            events.add("browse.start")
            return object : NsdBridgeDiscovery.Platform.Browse {
                override suspend fun awaitCandidates(timeoutMs: Long): List<NsdBridgeDiscovery.Candidate> {
                    if (awaitDelayMs > 0) delay(awaitDelayMs)
                    if (browseThrows) throw RuntimeException("browse failed")
                    return candidates
                }

                override fun close() {
                    events.add("browse.close")
                }
            }
        }
    }

    /** confirm() answering from a host:port → bridgeId? table, recording every call. */
    private class RecordingConfirm(private val table: Map<String, String?>) {
        val calls = CopyOnWriteArrayList<String>()
        val fn: suspend (String, Int) -> String? = { host, port ->
            calls.add("$host:$port")
            table["$host:$port"]
        }
    }

    // -- B1: happy path releases everything in reverse acquire order ----------

    @Test
    fun releasesAllResourcesInReverseOrderOnSuccess() = runBlocking {
        val platform = RecordingPlatform(candidates = listOf(cand("10.0.0.5", 7860, "b-1")))
        val confirm = RecordingConfirm(mapOf("10.0.0.5:7860" to "b-1"))
        val discovery = NsdBridgeDiscovery(platform, confirm.fn)

        val found = discovery.discover("b-1", 1_000)

        assertEquals(BridgeDiscovery.Discovered("10.0.0.5", 7860, "b-1"), found)
        assertEquals(
            listOf(
                "lock.acquire", "bind.acquire", "browse.start",
                "browse.close", "bind.release", "lock.release",
            ),
            platform.events.toList(),
        )
    }

    // -- B2: browse throwing still releases lock + bind + browse --------------

    @Test
    fun releasesEverythingWhenBrowseThrows() = runBlocking {
        val platform = RecordingPlatform(browseThrows = true)
        val discovery = NsdBridgeDiscovery(platform, RecordingConfirm(emptyMap()).fn)

        val outcome = runCatching { discovery.discover("b-1", 1_000) }
        assertTrue("browse failure propagates", outcome.isFailure)

        assertTrue("browse closed", platform.events.contains("browse.close"))
        assertTrue("bind released", platform.events.contains("bind.release"))
        assertTrue("lock released", platform.events.contains("lock.release"))
    }

    // -- B3: no match (the timeout-empty path) still releases -----------------

    @Test
    fun releasesEverythingWhenNothingMatches() = runBlocking {
        val platform = RecordingPlatform(candidates = emptyList())
        val discovery = NsdBridgeDiscovery(platform, RecordingConfirm(emptyMap()).fn)

        val found = discovery.discover("b-1", 1_000)

        assertNull(found)
        assertTrue("browse closed", platform.events.contains("browse.close"))
        assertTrue("bind released", platform.events.contains("bind.release"))
        assertTrue("lock released", platform.events.contains("lock.release"))
    }

    // -- B4: cancellation mid-scan still releases -----------------------------

    @Test
    fun releasesEverythingOnCancellation() = runBlocking {
        val platform = RecordingPlatform(
            candidates = listOf(cand("10.0.0.5", 7860, "b-1")),
            awaitDelayMs = 10_000, // block inside awaitCandidates until cancelled
        )
        val discovery = NsdBridgeDiscovery(platform, RecordingConfirm(emptyMap()).fn)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val job = scope.launch { discovery.discover("b-1", 10_000) }
        // Wait until the scan is inside the browse, then cancel it.
        while (!platform.events.contains("browse.start")) Thread.sleep(10)
        job.cancelAndJoin()

        assertTrue("browse closed on cancel", platform.events.contains("browse.close"))
        assertTrue("bind released on cancel", platform.events.contains("bind.release"))
        assertTrue("lock released on cancel", platform.events.contains("lock.release"))
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    // -- B5: bind throwing still releases the already-held lock ---------------

    @Test
    fun releasesLockWhenBindThrows() = runBlocking {
        val platform = RecordingPlatform(bindThrows = true)
        val discovery = NsdBridgeDiscovery(platform, RecordingConfirm(emptyMap()).fn)

        val outcome = runCatching { discovery.discover("b-1", 1_000) }
        assertTrue("bind failure propagates", outcome.isFailure)

        // The lock was acquired before the bind failed; it MUST be released.
        assertTrue("lock released", platform.events.contains("lock.release"))
        // Nothing else was acquired, so nothing else starts or releases.
        assertFalse("browse never started", platform.events.contains("browse.start"))
        assertFalse("bind never yielded a Closeable to release", platform.events.contains("bind.release"))
    }

    // -- B6: bridgeId filter selects the match, skips the foreign -------------

    @Test
    fun filterSelectsMatchingBridgeIdAndSkipsForeign() = runBlocking {
        val platform = RecordingPlatform(
            candidates = listOf(
                cand("10.0.0.5", 7860, "b-2"), // foreign: the TXT pre-filter skips it
                cand("10.0.0.6", 7861, "b-1"), // the pinned bridge
            ),
        )
        val confirm = RecordingConfirm(mapOf("10.0.0.6:7861" to "b-1"))
        val discovery = NsdBridgeDiscovery(platform, confirm.fn)

        val found = discovery.discover("b-1", 1_000)

        assertEquals(BridgeDiscovery.Discovered("10.0.0.6", 7861, "b-1"), found)
        // The foreign candidate was skipped by the cheap TXT pre-filter with no
        // network round-trip: only the matching candidate was ever confirmed.
        assertEquals(listOf("10.0.0.6:7861"), confirm.calls.toList())
    }

    // -- B7: null filter accepts any confirmed bridge (zero-typing pairing) ----

    @Test
    fun nullFilterAcceptsAnyConfirmedBridge() = runBlocking {
        val platform = RecordingPlatform(candidates = listOf(cand("10.0.0.5", 7860, "b-anything")))
        val confirm = RecordingConfirm(mapOf("10.0.0.5:7860" to "b-anything"))
        val discovery = NsdBridgeDiscovery(platform, confirm.fn)

        val found = discovery.discover(null, 1_000)

        assertEquals(BridgeDiscovery.Discovered("10.0.0.5", 7860, "b-anything"), found)
    }

    // -- B8: a candidate that fails the confirm gate is skipped ---------------

    @Test
    fun confirmFailureSkipsCandidate() = runBlocking {
        // TXT advertised the pin, but /v1/ping confirm fails (decoy, or the
        // advertisement was stale and the bridge is already gone).
        val platform = RecordingPlatform(candidates = listOf(cand("10.0.0.5", 7860, "b-1")))
        val confirm = RecordingConfirm(mapOf("10.0.0.5:7860" to null))
        val discovery = NsdBridgeDiscovery(platform, confirm.fn)

        val found = discovery.discover("b-1", 1_000)

        assertNull("a candidate that fails confirm must be skipped", found)
        assertTrue("confirm was attempted", confirm.calls.contains("10.0.0.5:7860"))
    }

    // -- B9: single-flight — a concurrent second scan is rejected, and the
    //        process-global Wi-Fi bind is taken exactly once -----------------

    @Test
    fun secondConcurrentScanIsRejectedWithoutASecondProcessBind() = runBlocking {
        // bindProcessToNetwork is process-GLOBAL. Without the inFlight
        // single-flight guard, discoverForPairing racing a self-heal would both
        // call bindWifiProcess — and the FIRST scan's close() would unbind the
        // whole process (the live SSE stream included) while the SECOND scan is
        // still relying on the Wi-Fi bind. That is exactly the process-strand
        // hazard acceptance criterion 3 targets. The guard admits one scan at a
        // time; the loser returns null and touches no platform resource.
        val platform = RecordingPlatform(
            candidates = listOf(cand("10.0.0.5", 7860, "b-1")),
            awaitDelayMs = 10_000, // hold the first scan inside the browse (guard held)
        )
        val discovery =
            NsdBridgeDiscovery(platform, RecordingConfirm(mapOf("10.0.0.5:7860" to "b-1")).fn)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val first = scope.launch { discovery.discover("b-1", 10_000) }
        // Wait until the first scan holds the guard (it has entered the browse).
        while (!platform.events.contains("browse.start")) Thread.sleep(10)

        // Second scan launched while the first is in flight. The guard rejects it
        // with null WITHOUT suspending; the timeout only stops a REMOVED guard
        // from hanging the test (it would otherwise proceed into the 10 s browse).
        val second = withTimeoutOrNull(2_000) { discovery.discover("b-1", 10_000) }
        assertNull("a concurrent second scan must be rejected", second)

        // The rejected scan acquired NOTHING: each process-global resource was
        // taken exactly once (by the first scan only). A broken guard would show
        // a second acquire of any of them.
        assertEquals("exactly one process Wi-Fi bind", 1, platform.events.count { it == "bind.acquire" })
        assertEquals("exactly one multicast lock", 1, platform.events.count { it == "lock.acquire" })
        assertEquals("exactly one browse", 1, platform.events.count { it == "browse.start" })

        first.cancelAndJoin()
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
