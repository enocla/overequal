package dev.overequal.scrape

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A simple coroutine rate limiter: paces `acquire()` calls to at most
 * [permitsPerSecond], spacing them evenly (a steady 1/interval cadence rather than
 * a burst-then-idle bucket). A non-positive rate means **unlimited** — `acquire()`
 * returns immediately.
 *
 * The scraper acquires one permit per page of history fetched (Discord4J pages at
 * [Scraper.PAGE_SIZE] messages per REST request), so the rate is expressed in
 * *requests* per second.
 */
class RateLimiter(
    permitsPerSecond: Double,
) {
    private val intervalNanos: Long =
        if (permitsPerSecond <= 0.0) 0L else (1_000_000_000.0 / permitsPerSecond).toLong()
    private val mutex = Mutex()
    private var nextSlot = Long.MIN_VALUE

    val unlimited: Boolean get() = intervalNanos == 0L

    /** Suspend until the next evenly-spaced slot is due, then reserve it. */
    suspend fun acquire() {
        if (intervalNanos == 0L) return
        val waitNanos =
            mutex.withLock {
                val now = System.nanoTime()
                val slot = if (nextSlot == Long.MIN_VALUE) now else maxOf(now, nextSlot)
                nextSlot = slot + intervalNanos
                slot - now
            }
        if (waitNanos > 0) delay(waitNanos / 1_000_000)
    }
}
