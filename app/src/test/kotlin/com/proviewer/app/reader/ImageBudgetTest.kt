package com.proviewer.app.reader

import android.graphics.Bitmap
import com.proviewer.app.engine.PageImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** JVM tests for [ImageBudget]. */
class ImageBudgetTest {

    @Test
    fun cap_constant_and_rgba_bytes() {
        assertEquals(192L * 1024 * 1024, IMAGE_CAP_BYTES)
        assertEquals(IMAGE_CAP_BYTES, ImageBudget().capBytes)
        assertEquals(4L * 100 * 150, rgbaBytes(100, 150))
        // No Int overflow for a 32 MiB base: 4096 x 2048 x 4.
        assertEquals(32L * 1024 * 1024, rgbaBytes(4096, 2048))
    }

    @Test
    fun reserve_until_cap() {
        val budget = ImageBudget(capBytes = 100)
        assertTrue(budget.tryReserve(60))
        assertTrue(budget.tryReserve(40))
        assertEquals(100, budget.usedBytes)
        assertFalse("over the cap", budget.tryReserve(1))
        assertEquals("a failed reservation changes nothing", 100, budget.usedBytes)
    }

    @Test
    fun release_restores() {
        val budget = ImageBudget(capBytes = 100)
        assertTrue(budget.tryReserve(100))
        assertFalse(budget.tryReserve(1))
        budget.release(30)
        assertEquals(70, budget.usedBytes)
        assertTrue(budget.tryReserve(30))
        assertEquals(100, budget.usedBytes)
    }

    @Test
    fun peak_tracks_max() {
        val budget = ImageBudget(capBytes = 1000)
        budget.tryReserve(300)
        budget.tryReserve(400)
        budget.release(500)
        assertEquals(200, budget.usedBytes)
        assertEquals(700, budget.peakBytes)

        budget.resetPeak()
        assertEquals("reset restarts from the current use", 200, budget.peakBytes)
        budget.tryReserve(100)
        assertEquals(300, budget.peakBytes)
    }

    @Test
    fun wrap_releases_once() {
        val budget = ImageBudget(capBytes = 1000)
        assertTrue(budget.tryReserve(400))
        val releases = AtomicInteger(0)
        val inner = object : PageImage {
            override val bitmap: Bitmap? get() = null
            override fun prepareToDraw() = Unit
            override fun release() { releases.incrementAndGet() }
        }
        val wrapped = budget.wrap(inner, 400)
        assertEquals(400, budget.usedBytes)

        wrapped.release()
        wrapped.release()
        wrapped.release()

        assertEquals("the image is released once", 1, releases.get())
        assertEquals("the bytes are returned once", 0, budget.usedBytes)
    }

    @Test
    fun concurrent_reserve_never_exceeds_cap() {
        val cap = 10_000L
        val budget = ImageBudget(capBytes = cap)
        val threads = 8
        val iterations = 10_000
        val maxSeen = AtomicLong(0)
        val failures = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            Thread {
                val rnd = Random(t)
                val mine = ArrayDeque<Long>()
                start.await()
                try {
                    repeat(iterations) {
                        if (mine.isEmpty() || rnd.nextBoolean()) {
                            val bytes = rnd.nextLong(1, 2000)
                            if (budget.tryReserve(bytes)) mine.addLast(bytes)
                        } else {
                            budget.release(mine.removeFirst())
                        }
                        val used = budget.usedBytes
                        maxSeen.accumulateAndGet(used, ::maxOf)
                        if (used > cap || used < 0) failures.incrementAndGet()
                    }
                    while (mine.isNotEmpty()) budget.release(mine.removeFirst())
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        done.await()

        assertEquals("used was out of range", 0, failures.get())
        assertTrue("max seen ${maxSeen.get()} exceeds the cap", maxSeen.get() <= cap)
        assertTrue("peak ${budget.peakBytes} exceeds the cap", budget.peakBytes <= cap)
        assertEquals(0, budget.usedBytes)
    }
    @Test
    fun releases_emit_after_release() = kotlinx.coroutines.test.runTest {
        val budget = ImageBudget(capBytes = 100)
        assertTrue(budget.tryReserve(100))
        assertFalse(budget.tryReserve(1))
        val seen = AtomicInteger(0)
        val job = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            budget.releases.collect { seen.incrementAndGet() }
        }
        budget.release(40)
        assertEquals(1, seen.get())
        assertTrue(budget.tryReserve(40))
        job.cancel()
    }
}
