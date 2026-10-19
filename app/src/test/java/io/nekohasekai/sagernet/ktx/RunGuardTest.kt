package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RunGuardTest {

    @Test
    fun secondStartIsRejected() {
        val guard = RunGuard()
        val token = guard.tryAcquire()
        assertNotNull(token)
        assertTrue(guard.isRunning)
        // a repeated tap on "test" while one runs
        assertNull(guard.tryAcquire())
        assertTrue(guard.release(token!!))
        assertFalse(guard.isRunning)
        assertNotNull(guard.tryAcquire())
    }

    @Test
    fun releaseTwiceIsHarmless() {
        val guard = RunGuard()
        val token = guard.tryAcquire()!!
        assertTrue(guard.release(token))
        assertFalse(guard.release(token))
        assertFalse(guard.isRunning)
    }

    @Test
    fun staleCleanupKeepsNewerTest() {
        val guard = RunGuard()
        val old = guard.tryAcquire()!!
        guard.release(old)
        val new = guard.tryAcquire()!!
        // the old test's second cleanup runs late
        assertFalse(guard.release(old))
        assertTrue(guard.isRunning)
        assertTrue(guard.release(new))
    }

    @Test
    fun concurrentStartsAcquireOnce() {
        val guard = RunGuard()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        repeat(200) {
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)
            val acquired = AtomicInteger(0)
            val tokens = java.util.Collections.synchronizedList(mutableListOf<Any>())
            repeat(threads) {
                pool.execute {
                    start.await()
                    guard.tryAcquire()?.let {
                        acquired.incrementAndGet()
                        tokens.add(it)
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(1, acquired.get())
            assertTrue(guard.release(tokens.single()))
        }
        pool.shutdown()
    }
}
