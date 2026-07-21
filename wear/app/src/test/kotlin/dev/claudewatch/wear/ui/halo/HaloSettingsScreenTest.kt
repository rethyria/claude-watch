package dev.claudewatch.wear.ui.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings page's Unpair confirm gate (the pure [unpairTap] step the
 * composable defers its two-tap logic to). Unpair is DESTRUCTIVE — it wipes the
 * paired credentials and forces a re-pair — so the contract these tests pin is
 * that a SINGLE tap can never fire it: the first tap only arms the control, and
 * only a second tap on the armed control unpairs (and disarms). Plain JVM, no
 * emulator; the instrumented HaloSettingsPageTest exercises the same contract
 * end-to-end through the real composable.
 */
class HaloSettingsScreenTest {

    @Test
    fun aSingleTapArmsButDoesNotUnpair() {
        // From rest, a lone tap ARMS and fires nothing — a stray swipe-tap onto
        // the leftmost page must not wipe the pairing.
        val step = unpairTap(armed = false)
        assertTrue("the first tap arms the control", step.armed)
        assertFalse("the first tap never fires the destructive unpair", step.fire)
    }

    @Test
    fun aSecondTapWhileArmedFiresUnpairAndDisarms() {
        // Confirm-then-tap: a tap on the ALREADY-armed control fires the unpair
        // and drops back to disarmed.
        val step = unpairTap(armed = true)
        assertTrue("the confirm tap fires onUnpair", step.fire)
        assertFalse("firing disarms the control", step.armed)
    }

    @Test
    fun twoTapsFireExactlyOnce() {
        // Walk the gate the way the composable does, counting fires: tap one
        // (rest → armed, no fire), tap two (armed → fire). Exactly one wipe for
        // the deliberate two-tap sequence.
        var armed = false
        var fires = 0
        repeat(2) {
            val step = unpairTap(armed)
            armed = step.armed
            if (step.fire) fires++
        }
        assertEquals("a full confirm sequence fires unpair exactly once", 1, fires)
        assertFalse("and lands back at disarmed", armed)
    }
}
