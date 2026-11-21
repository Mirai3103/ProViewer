package com.proviewer.app.reader

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import com.proviewer.app.engine.PageImage
import com.proviewer.app.engine.PageSourceRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [PageWindow] against a fake [PageSourceRenderer] whose completion the test
 * controls, on a `StandardTestDispatcher` scope (the default for [runTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageWindowTest {

    private class FakeRenderer(override val pageCount: Int) : PageSourceRenderer {
        /** renderBase start order, with the size each render was asked for. */
        val started = mutableListOf<Pair<Int, IntSize>>()
        /** renders that completed (returned an image). */
        val completed = mutableListOf<Pair<Int, IntSize>>()
        /** pages whose renderBase was cancelled mid-flight. */
        val cancelled = mutableListOf<Int>()
        /** pages whose Ready image was released. */
        val released = mutableListOf<Int>()
        /** prefetchHint arguments. */
        val prefetches = mutableListOf<List<Any>>()
        /** pages that should fail. */
        var failPages: Set<Int> = emptySet()
        /** when set, renderBase waits on this deferred (controllable completion). */
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun renderBase(page: Int, size: IntSize): PageImage {
            started.add(page to size)
            try {
                hold?.await()
            } catch (e: CancellationException) {
                cancelled.add(page)
                throw e
            }
            if (page in failPages) throw IllegalStateException("fail-$page")
            completed.add(page to size)
            return object : PageImage {
                override val bitmap: Bitmap? get() = null
                override fun prepareToDraw() = Unit
                override fun release() { released.add(page) }
            }
        }

        /** Natural page size; tests may override pages. */
        var natural: (Int) -> IntSize = { IntSize(100, 150) }
        /** pages probed through probePageSize (i.e. not already known). */
        val probed = mutableListOf<Int>()
        private val known = HashSet<Int>()

        override fun knownPageSize(page: Int): IntSize? = if (page in known) natural(page) else null

        override suspend fun probePageSize(page: Int): IntSize {
            require(page in 0 until pageCount) { "page $page out of range" }
            probed.add(page)
            known.add(page)
            return natural(page)
        }

        override fun prefetchHint(current: Int, forward: Boolean, viewW: Int, viewH: Int) {
            prefetches.add(listOf(current, forward, viewW, viewH))
        }
    }

    @Test
    fun renders_window_in_priority_order() = runTest {
        val renderer = FakeRenderer(pageCount = 20)
        val window = PageWindow(this, renderer, ahead = 3, behind = 1)

        window.update(5, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Screen)
        advanceUntilIdle()

        assertEquals(listOf(5, 6, 7, 8, 4), renderer.started.map { it.first })
        assertEquals(5, window.pages.size)
        assertTrue(window.pages.values.all { it is PageSlot.Ready })
        assertEquals(listOf(5, true, 1080, 2378), renderer.prefetches.single())
    }

    @Test
    fun rapid_jumps_render_only_final_window() = runTest {
        val renderer = FakeRenderer(pageCount = 500)
        val window = PageWindow(this, renderer, ahead = 3, behind = 1)

        for (i in 0..99) window.update(i, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Screen)
        advanceUntilIdle()

        // Only the final window [98,99,100,101,102] ever started a render; every intermediate job
        // was cancelled while still queued.
        assertEquals(setOf(98, 99, 100, 101, 102), renderer.started.map { it.first }.toSet())
        assertTrue("at most 5 slots held, had ${window.pages.size}", window.pages.size <= 5)
        assertEquals(setOf(98, 99, 100, 101, 102), window.pages.keys)
    }

    @Test
    fun viewport_change_cancels_and_rerenders() = runTest {
        val renderer = FakeRenderer(pageCount = 20).apply { hold = CompletableDeferred() }
        val window = PageWindow(this, renderer, ahead = 3, behind = 1)

        // First viewport: renders start and suspend on the gate (in-flight).
        window.update(5, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Screen)
        runCurrent()
        assertEquals(listOf(5, 6, 7, 8, 4), renderer.started.map { it.first })

        // Second viewport: cancels the in-flight old-size jobs and starts new ones.
        window.update(5, forward = true, viewW = 500, viewH = 800, mode = FitMode.Screen)
        renderer.hold!!.complete(Unit)
        advanceUntilIdle()

        val oldSize = baseSize(100, 150, 1080, 2378, FitMode.Screen)
        val newSize = baseSize(100, 150, 500, 800, FitMode.Screen)

        // The five in-flight old-size renders were cancelled, and the new renders completed.
        assertEquals(setOf(4, 5, 6, 7, 8), renderer.cancelled.toSet())
        assertEquals(5, renderer.completed.size)
        assertTrue("every completed render used the new size", renderer.completed.all { it.second == newSize })
        // No Ready slot holds the old size.
        window.pages.values.forEach { slot ->
            if (slot is PageSlot.Ready) assertTrue("stale size $oldSize", slot.size != oldSize)
        }
    }

    @Test
    fun evicted_images_are_released() = runTest {
        val renderer = FakeRenderer(pageCount = 20)
        val window = PageWindow(this, renderer, ahead = 3, behind = 1)

        window.update(5, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Screen)
        advanceUntilIdle()
        assertTrue(renderer.released.isEmpty())

        // Jump far away: pages 4..8 leave the window and their images must be released.
        window.update(10, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Screen)
        advanceUntilIdle()

        assertEquals(setOf(4, 5, 6, 7, 8), renderer.released.toSet())
        assertEquals(setOf(9, 10, 11, 12, 13), window.pages.keys)
    }

    @Test
    fun failed_page_is_failed_slot() = runTest {
        val renderer = FakeRenderer(pageCount = 20).apply { failPages = setOf(6) }
        val window = PageWindow(this, renderer, ahead = 3, behind = 1)

        window.update(5, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Screen)
        advanceUntilIdle()

        val slot = window.pages[6]
        assertTrue("page 6 should be Failed, was $slot", slot is PageSlot.Failed)
        assertTrue((slot as PageSlot.Failed).reason.contains("IllegalStateException"))
        assertTrue("page 5 should still render", window.pages[5] is PageSlot.Ready)
    }

    @Test
    fun close_releases_ready_images_and_blocks_update() = runTest {
        val renderer = FakeRenderer(pageCount = 20)
        val window = PageWindow(this, renderer, ahead = 3, behind = 1)

        window.update(5, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Screen)
        advanceUntilIdle()
        assertEquals(5, window.pages.size)
        assertTrue(renderer.released.isEmpty())

        // close() releases every Ready image and clears the slots.
        window.close()
        assertEquals(setOf(4, 5, 6, 7, 8), renderer.released.toSet())
        assertTrue(window.pages.isEmpty())

        // update() after close() must not start new renders.
        window.update(9, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Screen)
        advanceUntilIdle()
        assertEquals("no new render may start after close", 5, renderer.started.size)
        assertTrue(window.pages.isEmpty())
    }

    @Test
    fun close_during_inflight_render_publishes_nothing() = runTest {
        val renderer = FakeRenderer(pageCount = 20).apply { hold = CompletableDeferred() }
        val window = PageWindow(this, renderer, ahead = 3, behind = 1)

        window.update(5, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Screen)
        runCurrent() // all five renders are now in-flight (suspended on the gate)
        assertEquals(listOf(5, 6, 7, 8, 4), renderer.started.map { it.first })

        // Close while the renders are completing, then let them finish: the cancelled jobs must
        // not publish a Ready slot.
        window.close()
        renderer.hold!!.complete(Unit)
        advanceUntilIdle()

        assertTrue("no slot may be published after close", window.pages.isEmpty())
        assertEquals(setOf(4, 5, 6, 7, 8), renderer.cancelled.toSet())
    }
    @Test
    fun window_is_bounded_by_page_count_and_update_never_probes() = runTest {
        val renderer = FakeRenderer(pageCount = 3)
        val window = PageWindow(this, renderer, ahead = 3, behind = 1)

        window.update(1, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Screen)
        // update itself only queued jobs: no size probe ran on the caller.
        assertTrue("update must not probe synchronously", renderer.probed.isEmpty())
        advanceUntilIdle()

        assertEquals(setOf(0, 1, 2), window.pages.keys)
        assertEquals(setOf(0, 1, 2), renderer.probed.toSet())
    }

    @Test
    fun capped_base_keeps_fit_display_size() = runTest {
        // A webtoon strip: Width fit is 1080 x 32400, far over the base caps.
        val renderer = FakeRenderer(pageCount = 1).apply { natural = { IntSize(800, 24000) } }
        val window = PageWindow(this, renderer, ahead = 0, behind = 0)

        window.update(0, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Width)
        advanceUntilIdle()

        val slot = window.pages[0] as PageSlot.Ready
        assertEquals(IntSize(1080, 32400), slot.display)
        assertEquals(baseSize(800, 24000, 1080, 2378, FitMode.Width), slot.size)
        assertTrue("base must be capped below the display size", slot.size.width < slot.display.width)

        // Same view again: the Ready slot is up to date and is not re-rendered.
        window.update(0, forward = true, viewW = 1080, viewH = 2378, mode = FitMode.Width)
        advanceUntilIdle()
        assertEquals(1, renderer.started.size)
    }
}
