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

    // Battery: a watch away from its bridge must not reconnect (and re-run the
    // NSD self-heal scan) every 30 s forever. Past the sustained threshold the
    // cap relaxes so wakeups become far less frequent.
    @Test
    fun theSustainedTierRelaxesTheCapOnceFailuresPersist() {
        val policy = BackoffPolicy(
            baseMs = 1_000, maxMs = 30_000,
            sustainedMaxMs = 120_000, sustainedAfterAttempt = 8,
            random = Random(99),
        )
        repeat(100) {
            // Fast tier (attempt < 8): the unchanged 1 s → 30 s schedule.
            assertInRange(policy.delayMsFor(1), 500, 1_000)
            assertInRange(policy.delayMsFor(6), 15_000, 30_000)
            assertInRange(policy.delayMsFor(7), 15_000, 30_000)
            // Sustained tier (attempt >= 8): the cap relaxes to [60 s, 120 s].
            assertInRange(policy.delayMsFor(8), 60_000, 120_000)
            assertInRange(policy.delayMsFor(10_000), 60_000, 120_000)
        }
    }

    // The default (and every non-production caller / test) keeps a SINGLE tier:
    // the cap is maxMs at every attempt, exactly the pre-battery contract.
    @Test
    fun theDefaultPolicyHasNoSustainedTier() {
        val policy = BackoffPolicy(baseMs = 1_000, maxMs = 30_000, random = Random(7))
        repeat(100) {
            assertInRange(policy.delayMsFor(8), 15_000, 30_000)
            assertInRange(policy.delayMsFor(10_000), 15_000, 30_000)
        }
    }

    private fun assertInRange(value: Long, lo: Long, hi: Long) {
        assertTrue("expected $value in [$lo, $hi]", value in lo..hi)
    }
}
