package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class PendingRequestsTest {

    @Test
    fun timeoutRemovesTheRequest() = runBlocking {
        val pending = PendingRequests<String>()
        // no network for a long time: every get() times out
        repeat(5) {
            val request = CompletableDeferred<String>()
            pending.add(request)
            val result = awaitOrAbandon(request, 10) { pending.remove(request) }
            assertNull(result)
        }
        assertEquals(0, pending.size)
    }

    @Test
    fun networkComesBack() = runBlocking {
        val pending = PendingRequests<String>()
        val request = CompletableDeferred<String>()
        pending.add(request)
        val waiting = async { awaitOrAbandon(request, 5_000) { pending.remove(request) } }
        yield()
        pending.completeAll("wlan0")
        assertEquals("wlan0", waiting.await())
        assertEquals(0, pending.size)
    }

    @Test
    fun cancelledCallerRemovesTheRequest() = runBlocking {
        val pending = PendingRequests<String>()
        val request = CompletableDeferred<String>()
        pending.add(request)
        val waiting = async { awaitOrAbandon(request, 5_000) { pending.remove(request) } }
        delay(10)
        waiting.cancel()
        runCatching { waiting.await() }
        assertEquals(0, pending.size)
    }

    @Test
    fun stopFailsWaitingRequests() = runBlocking {
        val pending = PendingRequests<String>()
        val request = CompletableDeferred<String>()
        pending.add(request)
        val waiting = async {
            runCatching { awaitOrAbandon(request, 5_000) { pending.remove(request) } }
        }
        yield()
        pending.failAll(UnknownHostException())
        assertTrue(waiting.await().exceptionOrNull() is UnknownHostException)
        assertEquals(0, pending.size)
    }

    @Test
    fun retryBackoff() {
        assertEquals(30_000L, listenRetryDelayMs(0))
        assertEquals(60_000L, listenRetryDelayMs(1))
        assertEquals(120_000L, listenRetryDelayMs(2))
        assertEquals(240_000L, listenRetryDelayMs(3))
        assertEquals(300_000L, listenRetryDelayMs(4))
        assertEquals(300_000L, listenRetryDelayMs(100))
    }
}
