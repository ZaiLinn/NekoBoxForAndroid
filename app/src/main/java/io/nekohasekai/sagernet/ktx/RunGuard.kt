package io.nekohasekai.sagernet.ktx

import java.util.concurrent.atomic.AtomicReference

/**
 * Lets one task run at a time. Only the task that acquired the guard can release it, so the
 * late cleanup of an old task never releases the guard held by a newer one.
 */
class RunGuard {

    private val owner = AtomicReference<Any?>(null)

    val isRunning get() = owner.get() != null

    /** @return a token to pass to [release], or null if a task is already running. */
    fun tryAcquire(): Any? {
        val token = Any()
        return if (owner.compareAndSet(null, token)) token else null
    }

    /** @return true if [token] held the guard and released it. */
    fun release(token: Any): Boolean = owner.compareAndSet(token, null)
}
