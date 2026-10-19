package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Requests waiting for a value. Not thread-safe: use it from one actor. */
class PendingRequests<T> {

    private val requests = ArrayList<CompletableDeferred<T>>()

    val size get() = requests.size

    fun add(request: CompletableDeferred<T>) {
        requests += request
    }

    fun remove(request: CompletableDeferred<T>) {
        requests.remove(request)
    }

    fun completeAll(value: T) {
        requests.forEach { it.complete(value) }
        requests.clear()
    }

    fun failAll(cause: Throwable) {
        requests.forEach { it.completeExceptionally(cause) }
        requests.clear()
    }
}

/**
 * Waits up to [timeoutMs] for [request]. If it is not completed by then, or the caller is
 * cancelled, [onAbandon] runs so the request can be removed from where it waits.
 *
 * @return the value, or null on timeout
 */
suspend fun <T> awaitOrAbandon(
    request: CompletableDeferred<T>, timeoutMs: Long, onAbandon: () -> Unit,
): T? {
    try {
        return withTimeoutOrNull(timeoutMs) { request.await() }
    } finally {
        if (!request.isCompleted) onAbandon()
    }
}

/** Delay before the [attempt]th retry (0 based): 30 s, doubled each time, at most 5 min. */
fun listenRetryDelayMs(attempt: Int): Long {
    val first = 30_000L
    val max = 300_000L
    if (attempt >= 4) return max
    return minOf(first shl attempt.coerceAtLeast(0), max)
}
