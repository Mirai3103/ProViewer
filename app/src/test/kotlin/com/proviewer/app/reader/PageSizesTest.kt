package com.proviewer.app.reader

import androidx.compose.ui.unit.IntSize
import com.proviewer.app.engine.PageImage
import com.proviewer.app.engine.PageSourceRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [PageSizes] against a fake renderer, on a `StandardTestDispatcher` scope. */
@OptIn(ExperimentalCoroutinesApi::class)
class PageSizesTest {

    private class ProbeRenderer(override val pageCount: Int) : PageSourceRenderer {
        /** Pages whose probe started, in order. */
        val probeStarts = mutableListOf<Int>()
        var inFlight = 0
        var maxInFlight = 0
        var failPages: Set<Int> = emptySet()
        /** When set, every probe waits on it. */
        var gate: CompletableDeferred<Unit>? = null
        /** Per-page holds: the probe of the page waits on its deferred. */
        val holds = HashMap<Int, CompletableDeferred<Unit>>()

        override suspend fun probePageSize(page: Int): IntSize {
            probeStarts.add(page)
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                gate?.await()
                holds[page]?.await()
                if (page in failPages) throw IllegalStateException("bad-$page")
                return IntSize(100 + page, 150)
            } finally {
                inFlight--
            }
        }

        override suspend fun renderRegion(page: Int, region: PageRegion): PageImage =
            throw UnsupportedOperationException()

        override fun knownPageSize(page: Int): IntSize? = null

        override fun prefetchHint(current: Int, forward: Boolean, viewW: Int, viewH: Int, mode: FitMode) = Unit
    }

    @Test
    fun prioritized_pages_probe_first() = runTest {
        val renderer = ProbeRenderer(pageCount = 10).apply { gate = CompletableDeferred() }
        val sizes = PageSizes(this, renderer)

        sizes.prioritize(listOf(7, 5))
        sizes.start()
        runCurrent()
        // Two probes in flight, both prioritized.
        assertEquals(listOf(7, 5), renderer.probeStarts)

        renderer.gate!!.complete(Unit)
        advanceUntilIdle()

        // Then the rest in book order, each page probed exactly once, never more than two at once.
        assertEquals(listOf(7, 5, 0, 1, 2, 3, 4, 6, 8, 9), renderer.probeStarts)
        assertEquals(2, renderer.maxInFlight)
        assertEquals(IntSize(107, 150), sizes.known(7))
        assertEquals(IntSize(109, 150), sizes.known(9))
        sizes.close()
    }

    @Test
    fun await_first_n() = runTest {
        val renderer = ProbeRenderer(pageCount = 30).apply { gate = CompletableDeferred() }
        val sizes = PageSizes(this, renderer)
        sizes.start()

        var awaited = false
        val waiter = launch {
            sizes.awaitFirst(5)
            awaited = true
        }
        runCurrent()
        assertFalse("nothing is probed yet", awaited)

        renderer.gate!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(awaited)
        assertTrue((0 until 5).all { sizes.known(it) != null })
        waiter.join()
        sizes.close()
    }

    @Test
    fun await_first_n_is_bounded_by_the_page_count() = runTest {
        val renderer = ProbeRenderer(pageCount = 3)
        val sizes = PageSizes(this, renderer)
        sizes.start()
        sizes.awaitFirst(20) // must return once all 3 pages are resolved
        assertTrue((0 until 3).all { sizes.known(it) != null })
        sizes.close()
    }

    @Test
    fun failed_probe_recorded() = runTest {
        val renderer = ProbeRenderer(pageCount = 4).apply { failPages = setOf(2) }
        val sizes = PageSizes(this, renderer)
        sizes.start()
        advanceUntilIdle()

        assertTrue(sizes.failed(2))
        assertNull(sizes.known(2))
        assertFalse(sizes.failed(1))
        assertEquals(IntSize(101, 150), sizes.known(1))
        assertEquals("the sweep continues past a failure", IntSize(103, 150), sizes.known(3))
        // awaitFirst treats a failed probe as resolved.
        sizes.awaitFirst(4)
        sizes.close()
    }

    @Test
    fun version_bumps_batched() = runTest {
        val renderer = ProbeRenderer(pageCount = 40)
        val sizes = PageSizes(this, renderer)
        val v0 = sizes.version
        sizes.start()

        // 40 probes with nothing prioritized: bumps at probes 16 and 32, then once at the end.
        advanceUntilIdle()
        assertEquals(3, sizes.version - v0)
        assertEquals(40, (0 until 40).count { sizes.known(it) != null })
        sizes.close()
    }

    @Test
    fun version_bumps_when_a_prioritized_page_lands() = runTest {
        // Page 0 stays in flight, so the sweep never finishes; 9 probes is also below the batch size.
        val renderer = ProbeRenderer(pageCount = 10).apply { holds[0] = CompletableDeferred() }
        val sizes = PageSizes(this, renderer)
        val v0 = sizes.version
        sizes.prioritize(listOf(7))
        sizes.start()
        runCurrent()

        // Only the prioritized page's landing bumped the version.
        assertEquals(v0 + 1, sizes.version)
        assertEquals(IntSize(107, 150), sizes.known(7))
        assertEquals(9, (0 until 10).count { sizes.known(it) != null })

        renderer.holds[0]!!.complete(Unit)
        advanceUntilIdle()
        // The last probe finishing flushes the remaining sizes.
        assertEquals(v0 + 2, sizes.version)
        sizes.close()
    }

    @Test
    fun close_stops_probing() = runTest {
        val renderer = ProbeRenderer(pageCount = 100).apply { gate = CompletableDeferred() }
        val sizes = PageSizes(this, renderer)
        sizes.start()
        runCurrent()
        assertEquals(2, renderer.probeStarts.size)

        sizes.close()
        renderer.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals("no probe starts after close", 2, renderer.probeStarts.size)
    }
}
