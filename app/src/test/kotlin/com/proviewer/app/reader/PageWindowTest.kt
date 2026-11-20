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

    private class FakeRenderer(val pageCount: Int) : PageSourceRenderer {
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

        override fun pageSize(page: Int): IntSize? =
            if (page in 0 until pageCount) IntSize(100, 150) else null

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
}
