package com.proviewer.app.reader

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntRect
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
        /** renderRegion start order, with the output size each render was asked for. */
        val started = mutableListOf<Pair<Int, IntSize>>()
        /** renders that completed (returned an image). */
        val completed = mutableListOf<Pair<Int, IntSize>>()
        /** pages whose renderRegion was cancelled mid-flight. */
        val cancelled = mutableListOf<Int>()
        /** pages whose Ready image was released. */
        val released = mutableListOf<Int>()
        /** prefetchHint arguments. */
        val prefetches = mutableListOf<List<Any>>()
        /** pages that should fail. */
        var failPages: Set<Int> = emptySet()
        /** when set, renderRegion waits on this deferred (controllable completion). */
        var hold: CompletableDeferred<Unit>? = null
        /** every region asked for, in start order. */
        val regions = mutableListOf<Pair<Int, PageRegion>>()

        override suspend fun renderRegion(page: Int, region: PageRegion): PageImage {
            val size = IntSize(region.outW, region.outH)
            regions.add(page to region)
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
        /** pages probed through probePageSize: the window must never probe. */
        val probed = mutableListOf<Int>()

        override fun knownPageSize(page: Int): IntSize? = natural(page)

        override suspend fun probePageSize(page: Int): IntSize {
            probed.add(page)
            return natural(page)
        }

        override fun prefetchHint(current: Int, forward: Boolean, viewW: Int, viewH: Int, mode: FitMode) {
            prefetches.add(listOf(current, forward, viewW, viewH, mode))
        }
    }

    private fun whole(page: Int) = UnitPart(page, Part.Whole)

    private fun wholes(vararg pages: Int): Set<UnitPart> = pages.map(::whole).toSet()

    /** The Single/Whole targets the ViewModel builds around [current]; the fake's page sizes are known. */
    private fun targets(
        renderer: FakeRenderer,
        current: Int,
        forward: Boolean = true,
        viewW: Int = 1080,
        viewH: Int = 2378,
        mode: FitMode = FitMode.Screen,
        ahead: Int = 3,
        behind: Int = 1,
        split: SplitChoice = SplitChoice.Off,
    ): List<PartTarget> {
        val units = buildUnits(renderer.pageCount, renderer.natural, Layout.Single, split, Direction.Ltr, false)
        return windowTargets(units, current, ahead, behind, forward) {
            unitGeometry(it, renderer.natural, viewW, viewH, mode, Direction.Ltr)
        }
    }

    private fun PageWindow.updateTo(
        renderer: FakeRenderer,
        current: Int,
        forward: Boolean = true,
        viewW: Int = 1080,
        viewH: Int = 2378,
        mode: FitMode = FitMode.Screen,
        ahead: Int = 3,
        behind: Int = 1,
    ) = update(
        targets(renderer, current, forward, viewW, viewH, mode, ahead, behind),
        PrefetchRequest(current, forward, viewW, viewH, mode),
    )

    /** The byte size of the default fake page's base at the default viewport. */
    private val defaultBaseBytes = baseSize(100, 150, 1080, 2378, FitMode.Screen)
        .let { rgbaBytes(it.width, it.height) }

    @Test
    fun renders_window_in_priority_order() = runTest {
        val renderer = FakeRenderer(pageCount = 20)
        val budget = ImageBudget()
        val window = PageWindow(this, renderer, budget)

        window.updateTo(renderer, 5)
        advanceUntilIdle()

        assertEquals(listOf(5, 6, 7, 8, 4), renderer.started.map { it.first })
        assertEquals(5, window.slots.size)
        assertTrue(window.slots.values.all { it is PageSlot.Ready })
        assertEquals(listOf(5, true, 1080, 2378, FitMode.Screen), renderer.prefetches.single())
        // Each render asks for the whole page (natural 100x150) at its base size.
        assertTrue(renderer.regions.all { (_, r) -> r.x == 0 && r.y == 0 && r.w == 100 && r.h == 150 })
        assertEquals(5 * defaultBaseBytes, budget.usedBytes)
    }

    @Test
    fun rapid_jumps_render_only_final_window() = runTest {
        val renderer = FakeRenderer(pageCount = 500)
        val budget = ImageBudget()
        val window = PageWindow(this, renderer, budget)

        for (i in 0..99) window.updateTo(renderer, i)
        advanceUntilIdle()

        // Only the final window [98,99,100,101,102] ever started a render; every intermediate job
        // was cancelled while still queued.
        assertEquals(setOf(98, 99, 100, 101, 102), renderer.started.map { it.first }.toSet())
        assertTrue("at most 5 slots held, had ${window.slots.size}", window.slots.size <= 5)
        assertEquals(wholes(98, 99, 100, 101, 102), window.slots.keys)
        // Jobs cancelled before they ever ran returned their reservation.
        assertEquals(5 * defaultBaseBytes, budget.usedBytes)
    }

    @Test
    fun viewport_change_cancels_and_rerenders() = runTest {
        val renderer = FakeRenderer(pageCount = 20).apply { hold = CompletableDeferred() }
        val budget = ImageBudget()
        val window = PageWindow(this, renderer, budget)

        // First viewport: renders start and suspend on the gate (in-flight).
        window.updateTo(renderer, 5)
        runCurrent()
        assertEquals(listOf(5, 6, 7, 8, 4), renderer.started.map { it.first })

        // Second viewport: cancels the in-flight old-size jobs and starts new ones.
        window.updateTo(renderer, 5, viewW = 500, viewH = 800)
        renderer.hold!!.complete(Unit)
        advanceUntilIdle()

        val oldSize = baseSize(100, 150, 1080, 2378, FitMode.Screen)
        val newSize = baseSize(100, 150, 500, 800, FitMode.Screen)

        // The five in-flight old-size renders were cancelled, and the new renders completed.
        assertEquals(setOf(4, 5, 6, 7, 8), renderer.cancelled.toSet())
        assertEquals(5, renderer.completed.size)
        assertTrue("every completed render used the new size", renderer.completed.all { it.second == newSize })
        // No Ready slot holds the old size.
        window.slots.values.forEach { slot ->
            if (slot is PageSlot.Ready) assertTrue("stale size $oldSize", slot.size != oldSize)
        }
        assertEquals(5 * rgbaBytes(newSize.width, newSize.height), budget.usedBytes)
    }

    @Test
    fun evicted_images_are_released() = runTest {
        val renderer = FakeRenderer(pageCount = 20)
        val budget = ImageBudget()
        val window = PageWindow(this, renderer, budget)

        window.updateTo(renderer, 5)
        advanceUntilIdle()
        assertTrue(renderer.released.isEmpty())

        // Jump far away: pages 4..8 leave the window and their images must be released.
        window.updateTo(renderer, 10)
        advanceUntilIdle()

        assertEquals(setOf(4, 5, 6, 7, 8), renderer.released.toSet())
        assertEquals(wholes(9, 10, 11, 12, 13), window.slots.keys)
        assertEquals(5 * defaultBaseBytes, budget.usedBytes)
    }

    @Test
    fun failed_page_is_failed_slot() = runTest {
        val renderer = FakeRenderer(pageCount = 20).apply { failPages = setOf(6) }
        val budget = ImageBudget()
        val window = PageWindow(this, renderer, budget)

        window.updateTo(renderer, 5)
        advanceUntilIdle()

        val slot = window.slots[whole(6)]
        assertTrue("page 6 should be Failed, was $slot", slot is PageSlot.Failed)
        assertTrue((slot as PageSlot.Failed).reason.contains("IllegalStateException"))
        assertTrue("page 5 should still render", window.slots[whole(5)] is PageSlot.Ready)
        // The failed render's reservation was returned.
        assertEquals(4 * defaultBaseBytes, budget.usedBytes)

        // Failed is kept until the part is evicted: the same view does not re-render it.
        window.updateTo(renderer, 5)
        advanceUntilIdle()
        assertEquals(1, renderer.started.count { it.first == 6 })
    }

    @Test
    fun close_releases_ready_images_and_blocks_update() = runTest {
        val renderer = FakeRenderer(pageCount = 20)
        val budget = ImageBudget()
        val window = PageWindow(this, renderer, budget)

        window.updateTo(renderer, 5)
        advanceUntilIdle()
        assertEquals(5, window.slots.size)
        assertTrue(renderer.released.isEmpty())

        // close() releases every Ready image and clears the slots.
        window.close()
        assertEquals(setOf(4, 5, 6, 7, 8), renderer.released.toSet())
        assertTrue(window.slots.isEmpty())
        assertEquals(0, budget.usedBytes)

        // update() after close() must not start new renders.
        window.updateTo(renderer, 9)
        advanceUntilIdle()
        assertEquals("no new render may start after close", 5, renderer.started.size)
        assertTrue(window.slots.isEmpty())
        assertEquals(0, budget.usedBytes)
    }

    @Test
    fun close_during_inflight_render_publishes_nothing() = runTest {
        val renderer = FakeRenderer(pageCount = 20).apply { hold = CompletableDeferred() }
        val budget = ImageBudget()
        val window = PageWindow(this, renderer, budget)

        window.updateTo(renderer, 5)
        runCurrent() // all five renders are now in-flight (suspended on the gate)
        assertEquals(listOf(5, 6, 7, 8, 4), renderer.started.map { it.first })

        // Close while the renders are completing, then let them finish: the cancelled jobs must
        // not publish a Ready slot.
        window.close()
        renderer.hold!!.complete(Unit)
        advanceUntilIdle()

        assertTrue("no slot may be published after close", window.slots.isEmpty())
        assertEquals(setOf(4, 5, 6, 7, 8), renderer.cancelled.toSet())
        assertEquals(0, budget.usedBytes)
    }

    @Test
    fun window_is_bounded_by_page_count_and_update_never_probes() = runTest {
        val renderer = FakeRenderer(pageCount = 3)
        val window = PageWindow(this, renderer, ImageBudget())

        window.updateTo(renderer, 1)
        advanceUntilIdle()

        assertEquals(wholes(0, 1, 2), window.slots.keys)
        // The window renders the sizes it is given; it never asks the renderer to probe.
        assertTrue(renderer.probed.isEmpty())
    }

    @Test
    fun capped_base_keeps_fit_display_size() = runTest {
        // A webtoon strip: Width fit is 1080 x 32400, far over the base caps.
        val renderer = FakeRenderer(pageCount = 1).apply { natural = { IntSize(800, 24000) } }
        val budget = ImageBudget()
        val window = PageWindow(this, renderer, budget)

        window.updateTo(renderer, 0, mode = FitMode.Width, ahead = 0, behind = 0)
        advanceUntilIdle()

        val slot = window.slots[whole(0)] as PageSlot.Ready
        assertEquals(IntSize(1080, 32400), slot.display)
        assertEquals(baseSize(800, 24000, 1080, 2378, FitMode.Width), slot.size)
        assertTrue("base must be capped below the display size", slot.size.width < slot.display.width)
        assertEquals("the reservation is the capped base's bytes", rgbaBytes(slot.size.width, slot.size.height), budget.usedBytes)

        // Same view again: the Ready slot is up to date and is not re-rendered.
        window.updateTo(renderer, 0, mode = FitMode.Width, ahead = 0, behind = 0)
        advanceUntilIdle()
        assertEquals(1, renderer.started.size)
    }

    @Test
    fun prefetch_hint_carries_the_fit_mode() = runTest {
        val renderer = FakeRenderer(pageCount = 20)
        val window = PageWindow(this, renderer, ImageBudget())

        window.updateTo(renderer, 5, forward = false, viewW = 540, viewH = 1189, mode = FitMode.Width)
        window.updateTo(renderer, 5, forward = false, viewW = 540, viewH = 1189, mode = FitMode.Height)
        advanceUntilIdle()

        assertEquals(
            listOf(listOf(5, false, 540, 1189, FitMode.Width), listOf(5, false, 540, 1189, FitMode.Height)),
            renderer.prefetches,
        )
    }

    @Test
    fun half_parts_render_their_src_region() = runTest {
        // Page 3 is a 200 x 100 spread split in two halves; the other pages are not split.
        val renderer = FakeRenderer(pageCount = 6).apply {
            natural = { if (it == 3) IntSize(200, 100) else IntSize(100, 150) }
        }
        val budget = ImageBudget()
        val window = PageWindow(this, renderer, budget)
        val units = buildUnits(6, renderer.natural, Layout.Single, SplitChoice.Auto, Direction.Ltr, false)
        // Units: 0, 1, 2, 3-left, 3-right, 4, 5. Current is unit 3 (3-left), one unit ahead.
        assertEquals(7, units.size)
        val wanted = windowTargets(units, 3, 1, 0, true) {
            unitGeometry(it, renderer.natural, 1080, 2378, FitMode.Screen, Direction.Ltr)
        }
        assertEquals(
            listOf(UnitPart(3, Part.LeftHalf), UnitPart(3, Part.RightHalf)),
            wanted.map { it.part },
        )

        window.update(wanted, PrefetchRequest(3, true, 1080, 2378, FitMode.Screen))
        advanceUntilIdle()

        val base = wanted[0].base
        val byPart = renderer.regions.associate { (_, r) -> r.x to r }
        assertEquals(PageRegion(0, 0, 100, 100, base.width, base.height), byPart[0])
        assertEquals(PageRegion(100, 0, 100, 100, base.width, base.height), byPart[100])
        assertEquals(wanted.map { it.part }.toSet(), window.slots.keys)
        assertTrue(window.slots.values.all { it is PageSlot.Ready })
        assertEquals(2 * rgbaBytes(base.width, base.height), budget.usedBytes)
    }

    @Test
    fun budget_exhaustion_skips_low_priority() = runTest {
        val renderer = FakeRenderer(pageCount = 20)
        // Room for two default bases, not three.
        val budget = ImageBudget(capBytes = 2 * defaultBaseBytes + defaultBaseBytes / 2)
        val window = PageWindow(this, renderer, budget)

        window.updateTo(renderer, 5)
        advanceUntilIdle()

        // 5 and 6 fit; 7 does not, and neither 8 nor 4 is tried after it.
        assertEquals(listOf(5, 6), renderer.started.map { it.first })
        assertEquals(wholes(5, 6), window.slots.keys)
        assertEquals(2 * defaultBaseBytes, budget.usedBytes)

        // The next update retries: 5 leaves the window, freeing room for the new current page 7.
        window.updateTo(renderer, 7)
        advanceUntilIdle()
        assertEquals(wholes(6, 7), window.slots.keys)
        assertEquals(listOf(5, 6, 7), renderer.started.map { it.first })
        assertTrue(budget.peakBytes <= budget.capBytes)
    }

    @Test
    fun budget_exhaustion_skips_smaller_lower_priority_targets_too() = runTest {
        val renderer = FakeRenderer(pageCount = 5)
        val budget = ImageBudget(capBytes = 1_000)
        val window = PageWindow(this, renderer, budget)
        fun target(page: Int, side: Int) = PartTarget(
            part = whole(page),
            src = IntRect(0, 0, 100, 150),
            base = IntSize(side, side),
            display = IntSize(side, side),
        )

        // 20x20 = 1600 bytes does not fit; the 5x5 = 100 byte target after it would, but is lower priority.
        window.update(listOf(target(0, 20), target(1, 5)), PrefetchRequest(0, true, 100, 100, FitMode.Screen))
        advanceUntilIdle()

        assertTrue(renderer.started.isEmpty())
        assertTrue(window.slots.isEmpty())
        assertEquals(0, budget.usedBytes)
    }

    @Test
    fun budget_break_releases_lower_priority_stale_ready_images() = runTest {
        val renderer = FakeRenderer(pageCount = 5)
        // Exactly one 100x100 base fits.
        val budget = ImageBudget(capBytes = rgbaBytes(100, 100))
        val window = PageWindow(this, renderer, budget)
        fun target(page: Int, side: Int) = PartTarget(
            part = whole(page),
            src = IntRect(0, 0, 100, 150),
            base = IntSize(side, side),
            display = IntSize(side, side),
        )

        // Both 50x50 targets fit and render.
        window.update(listOf(target(0, 50), target(1, 50)), PrefetchRequest(0, true, 100, 100, FitMode.Screen))
        advanceUntilIdle()
        assertEquals(wholes(0, 1), window.slots.keys)
        assertEquals(2 * rgbaBytes(50, 50), budget.usedBytes)

        // Both targets change spec: page 0 re-renders at 100x100 (exactly the cap) and page 1 at
        // 60x60 (which no longer fits once page 0 is reserved). Page 1's reservation fails and the
        // update breaks there -- but page 1's stale image must already have been released by then,
        // never held spec-mismatched past the break.
        window.update(listOf(target(0, 100), target(1, 60)), PrefetchRequest(0, true, 100, 100, FitMode.Screen))
        advanceUntilIdle()

        assertEquals("page 1 must not keep its stale Ready slot", wholes(0), window.slots.keys)
        assertEquals("both old images released, only page 0's new base held", rgbaBytes(100, 100), budget.usedBytes)
        assertEquals(setOf(0, 1), renderer.released.toSet())
    }

    @Test
    fun budget_break_cancels_lower_priority_stale_loading_jobs() = runTest {
        val renderer = FakeRenderer(pageCount = 5).apply { hold = CompletableDeferred() }
        // Two 50x50 bases fit; a 100x100 one does not.
        val budget = ImageBudget(capBytes = 2 * rgbaBytes(50, 50))
        val window = PageWindow(this, renderer, budget)
        fun target(page: Int, side: Int) = PartTarget(
            part = whole(page),
            src = IntRect(0, 0, 100, 150),
            base = IntSize(side, side),
            display = IntSize(side, side),
        )

        // Both renders start and suspend on the gate: two Loading slots, both reservations held.
        window.update(listOf(target(0, 50), target(1, 50)), PrefetchRequest(0, true, 100, 100, FitMode.Screen))
        runCurrent()
        assertEquals(listOf(0, 1), renderer.started.map { it.first })
        assertEquals(2 * rgbaBytes(50, 50), budget.usedBytes)

        // Both targets change spec; page 0's new 100x100 base does not fit, so the update breaks
        // at it. Page 1's stale in-flight render must still have been cancelled (never left
        // running spec-mismatched), and its reservation returned.
        window.update(listOf(target(0, 100), target(1, 60)), PrefetchRequest(0, true, 100, 100, FitMode.Screen))
        advanceUntilIdle()

        assertTrue("page 1's stale Loading slot must be gone", window.slots.isEmpty())
        assertEquals(setOf(0, 1), renderer.cancelled.toSet())
        assertEquals("every reservation returned", 0, budget.usedBytes)

        // Nothing publishes even once the gate opens.
        renderer.hold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(window.slots.isEmpty())
    }

    @Test
    fun released_images_return_bytes() = runTest {
        val renderer = FakeRenderer(pageCount = 20)
        val budget = ImageBudget()
        val window = PageWindow(this, renderer, budget)

        window.updateTo(renderer, 5)
        advanceUntilIdle()
        assertEquals(5 * defaultBaseBytes, budget.usedBytes)

        // Eviction returns the evicted images' bytes (page 4 leaves; 9 arrives).
        window.updateTo(renderer, 6)
        advanceUntilIdle()
        assertEquals(setOf(5, 6, 7, 8, 9), window.slots.keys.map { it.page }.toSet())
        assertEquals(5 * defaultBaseBytes, budget.usedBytes)

        // A view change re-renders: the old images are released before the new ones are reserved.
        window.updateTo(renderer, 6, viewW = 500, viewH = 800)
        advanceUntilIdle()
        val newSize = baseSize(100, 150, 500, 800, FitMode.Screen)
        assertEquals(5 * rgbaBytes(newSize.width, newSize.height), budget.usedBytes)

        window.close()
        assertEquals(0, budget.usedBytes)
        assertTrue("peak stayed within the cap", budget.peakBytes <= budget.capBytes)
    }
}
