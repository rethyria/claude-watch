// Bounded, immutable append-only buffer for per-session terminal history.
// Appending past capacity drops the oldest entries — a long-running session
// must never grow watch memory unbounded (the watchOS app capped at the same
// 200 lines; see SessionState.TERMINAL_BUFFER_LINES for the capacity used).
package dev.claudewatch.shared.terminal

/**
 * Immutable ring buffer: [append]/[appendAll] return a new buffer, keeping at
 * most [capacity] newest items. Structural equality (it is a data class over
 * the item list) keeps reducer states comparable in tests.
 */
data class RingBuffer<T>(val capacity: Int, val items: List<T> = emptyList()) {

    init {
        require(capacity > 0) { "RingBuffer capacity must be positive, got $capacity" }
        require(items.size <= capacity) { "RingBuffer seeded over capacity: ${items.size} > $capacity" }
    }

    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    fun append(item: T): RingBuffer<T> = appendAll(listOf(item))

    fun appendAll(newItems: List<T>): RingBuffer<T> {
        if (newItems.isEmpty()) return this
        return copy(items = (items + newItems).takeLast(capacity))
    }
}
