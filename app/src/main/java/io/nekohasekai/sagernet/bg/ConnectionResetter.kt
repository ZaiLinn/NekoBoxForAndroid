package io.nekohasekai.sagernet.bg

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs [reset] for requests from any thread.
 *
 * A normal request is skipped within [debounceMs] of the last reset. A forced request (network
 * changed) is never skipped. Resets never run in parallel: requests made while one runs are
 * merged into one more run after it, so the last request is always followed by a reset.
 */
class ConnectionResetter(
    private val debounceMs: Long,
    private val clock: () -> Long,
    private val runAsync: (Runnable) -> Unit,
    private val reset: () -> Unit,
) {

    private val lastResetAt = AtomicLong(Long.MIN_VALUE)
    private val running = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)

    /** @return false if the request was skipped by the debounce. */
    fun request(force: Boolean): Boolean {
        val now = clock()
        if (force) {
            lastResetAt.set(now)
        } else {
            val last = lastResetAt.get()
            if (last != Long.MIN_VALUE && now - last < debounceMs) return false
            // another thread passed the debounce at the same time
            if (!lastResetAt.compareAndSet(last, now)) return false
        }
        pending.set(true)
        drain()
        return true
    }

    private fun drain() {
        if (!running.compareAndSet(false, true)) return // the running loop picks it up
        runAsync {
            try {
                while (pending.getAndSet(false)) {
                    try {
                        reset()
                    } catch (e: Exception) {
                        onError(e)
                    }
                }
            } finally {
                running.set(false)
            }
            // a request between the last check and the flag reset
            if (pending.get()) drain()
        }
    }

    var onError: (Exception) -> Unit = {}
}
