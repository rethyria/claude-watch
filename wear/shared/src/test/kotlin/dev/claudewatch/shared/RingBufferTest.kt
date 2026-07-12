package dev.claudewatch.shared

import dev.claudewatch.shared.terminal.RingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBufferTest {

    @Test
    fun appendBelowCapacityKeepsEverythingInOrder() {
        val buffer = RingBuffer<Int>(3).append(1).append(2)
        assertEquals(listOf(1, 2), buffer.items)
        assertEquals(2, buffer.size)
    }

    @Test
    fun appendPastCapacityWrapsByDroppingTheOldest() {
        var buffer = RingBuffer<Int>(200)
        repeat(250) { buffer = buffer.append(it) }
        assertEquals(200, buffer.size)
        assertEquals(50, buffer.items.first())
        assertEquals(249, buffer.items.last())
    }

    @Test
    fun appendAllLargerThanCapacityKeepsOnlyTheNewestTail() {
        val buffer = RingBuffer<Int>(3, listOf(0)).appendAll((1..10).toList())
        assertEquals(listOf(8, 9, 10), buffer.items)
    }

    @Test
    fun appendAllOfNothingIsTheSameBuffer() {
        val buffer = RingBuffer<Int>(3, listOf(1, 2))
        assertSame(buffer, buffer.appendAll(emptyList()))
    }

    @Test
    fun immutabilityAndStructuralEquality() {
        val before = RingBuffer<Int>(2, listOf(1, 2))
        val after = before.append(3)
        assertEquals(listOf(1, 2), before.items) // original untouched
        assertEquals(listOf(2, 3), after.items)
        assertEquals(RingBuffer(2, listOf(2, 3)), after)
    }

    @Test
    fun rejectsNonPositiveCapacityAndOverfullSeed() {
        assertTrue(runCatching { RingBuffer<Int>(0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { RingBuffer(1, listOf(1, 2)) }.exceptionOrNull() is IllegalArgumentException,
        )
    }
}
