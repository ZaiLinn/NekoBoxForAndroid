package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ConnectionResetterTest {

    private val now = AtomicLong(1_000_000L)

    /** Runs resets on the calling thread. */
    private fun direct(reset: () -> Unit) =
        ConnectionResetter(5_000L, now::get, { it.run() }, reset)

    @Test
    fun debounceSkipsNormalRequests() {
        val count = AtomicInteger()
        val resetter = direct { count.incrementAndGet() }
        assertTrue(resetter.request(false))
        now.addAndGet(1_000)
        assertFalse(resetter.request(false))
        now.addAndGet(4_000)
        assertTrue(resetter.request(false))
        assertEquals(2, count.get())
    }

    @Test
    fun forceIsNeverSkipped() {
        val count = AtomicInteger()
        val resetter = direct { count.incrementAndGet() }
        assertTrue(resetter.request(false))
        assertTrue(resetter.request(true))
        assertTrue(resetter.request(true))
        assertEquals(3, count.get())
        // a forced reset also restarts the debounce
        assertFalse(resetter.request(false))
    }

    @Test
    fun failingResetDoesNotBlockLaterOnes() {
        val count = AtomicInteger()
        val errors = AtomicInteger()
        val resetter = direct {
            if (count.incrementAndGet() == 1) throw IllegalStateException("core closed")
        }
        resetter.onError = { errors.incrementAndGet() }
        resetter.request(true)
        resetter.request(true)
        assertEquals(2, count.get())
        assertEquals(1, errors.get())
    }

    @Test
    fun requestsDuringResetAreMergedAndNeverParallel() {
        val pool = Executors.newFixedThreadPool(4)
        val inReset = AtomicInteger()
        val maxParallel = AtomicInteger()
        val count = AtomicInteger()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val resetter = ConnectionResetter(5_000L, now::get, { pool.execute(it) }) {
            val parallel = inReset.incrementAndGet()
            maxParallel.accumulateAndGet(parallel, ::maxOf)
            if (count.incrementAndGet() == 1) {
                firstStarted.countDown()
                releaseFirst.await()
            }
            inReset.decrementAndGet()
        }
        resetter.request(true)
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        // network flaps while the first reset runs
        repeat(10) { resetter.request(true) }
        releaseFirst.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, maxParallel.get())
        // the first reset, then one more for all requests made meanwhile
        assertEquals(2, count.get())
    }

    @Test
    fun concurrentRequestsAlwaysEndWithReset() {
        val threads = 8
        val requester = Executors.newFixedThreadPool(threads)
        val worker = Executors.newSingleThreadExecutor()
        repeat(100) {
            val requests = AtomicInteger()
            val resetsAfterLastRequest = AtomicInteger()
            val inReset = AtomicInteger()
            val maxParallel = AtomicInteger()
            val resetter = ConnectionResetter(5_000L, now::get, { worker.execute(it) }) {
                maxParallel.accumulateAndGet(inReset.incrementAndGet(), ::maxOf)
                resetsAfterLastRequest.set(requests.get())
                inReset.decrementAndGet()
            }
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)
            repeat(threads) {
                requester.execute {
                    start.await()
                    repeat(20) {
                        requests.incrementAndGet()
                        resetter.request(true)
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            // wait for the worker to finish everything queued so far
            worker.submit {}.get(10, TimeUnit.SECONDS)
            worker.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(1, maxParallel.get())
            assertEquals("a request was not followed by a reset", requests.get(), resetsAfterLastRequest.get())
        }
        requester.shutdown()
        worker.shutdown()
    }

    @Test
    fun concurrentNormalRequestsResetOnce() {
        val count = AtomicInteger()
        val resetter = direct { count.incrementAndGet() }
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                resetter.request(false)
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1, count.get())
    }
}
