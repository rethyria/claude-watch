package dev.claudewatch.wear.net

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The reconnect schedule the iOS client never had: exponential 1 s → 30 s
 * with jitter, never a fixed-cadence hammer and never an unbounded delay.
 */
class BackoffPolicyTest {

    @Test
    fun growsExponentiallyWithJitterInsideTheCap() {
        val policy = BackoffPolicy(baseMs = 1_000, maxMs = 30_000, random = Random(1234))
        repeat(100) {
            assertInRange(policy.delayMsFor(1), 500, 1_000)
            assertInRange(policy.delayMsFor(2), 1_000, 2_000)
            assertInRange(policy.delayMsFor(3), 2_000, 4_000)
            assertInRange(policy.delayMsFor(5), 8_000, 16_000)
            // 1s << 5 = 32s exceeds the cap: clamped to [15s, 30s].
            assertInRange(policy.delayMsFor(6), 15_000, 30_000)
            // Huge attempt counts must neither overflow nor exceed the cap.
            assertInRange(policy.delayMsFor(10_000), 15_000, 30_000)
        }
    }

    @Test
    fun jitterActuallyVaries() {
        val policy = BackoffPolicy(baseMs = 1_000, maxMs = 30_000, random = Random(42))
        val samples = (1..50).map { policy.delayMsFor(6) }.toSet()
        assertTrue("expected jittered delays, got a fixed cadence: $samples", samples.size > 1)
    }

    private fun assertInRange(value: Long, lo: Long, hi: Long) {
        assertTrue("expected $value in [$lo, $hi]", value in lo..hi)
    }
}
