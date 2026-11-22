package com.proviewer.app.reader.webtoon

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.proviewer.app.engine.NativeRenderException
import com.proviewer.app.engine.PageImage
import com.proviewer.app.engine.TileSource
import com.proviewer.app.reader.ImageBudget
import com.proviewer.app.reader.rgbaBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * JVM tests for [TileManager] against a fake [TileSource] whose completion the test controls, on a
 * `StandardTestDispatcher` scope (the default for [runTest]).
 *
 * The tests pin the manager side of the scale convention (plan review focus): tile placement is
 * owned by [wantedTiles]/[pageTiles], so the manager must pass each [TileSpec]'s `full` and `rect`
 * verbatim to the renderer — never recomputing a size or an edge, which is what keeps neighbouring
 * tiles' page-space boundaries identical.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TileManagerTest {

    /** A fake [TileSource]: records every call, can hold renders in-flight and fail pages. */
    private class FakeSource : TileSource {
        /** Every renderScaled call, in start order: (page, full, rect). */
        val calls = mutableListOf<Triple<Int, IntSize, IntRect>>()
        /** Pages whose render completed (one entry per completed render). */
        val completed = mutableListOf<Int>()
        /** Pages whose in-flight render was cancelled mid-flight. */
        val cancelled = mutableListOf<Int>()
        /** Pages whose image was released (one entry per release). */
        val released = mutableListOf<Int>()

        /** When set, every render suspends on this gate before completing. */
        var hold: CompletableDeferred<Unit>? = null

        /** Pages whose render throws (the manager must read the exception's detail). */
        var failPages: Set<Int> = emptySet()

        override suspend fun renderScaled(page: Int, full: IntSize, rect: IntRect): PageImage {
            calls.add(Triple(page, full, rect))
            try {
                hold?.await()
            } catch (e: CancellationException) {
                cancelled.add(page)
                throw e
            }
            if (page in failPages) throw NativeRenderException("Panic", "boom-$page")
            completed.add(page)
            return object : PageImage {
                override val bitmap: Bitmap? get() = null
                override fun prepareToDraw() = Unit
                override fun release() { released.add(page) }
            }
        }
    }

    /** Five square pages: 600x600 fit into a 600-wide viewport, tops 0,600..2400. */
    private val smallPages = WebtoonLayout.build(5, 600, 500, { IntSize(600, 600) }, { false })

    /** The bytes of one 100x100 tile. */
    private val tileBytes = rgbaBytes(100, 100)

    /** A 100x100 tile of [page] at [level]; all tiles share the rect, keys differ by page/level. */
    private fun tile(page: Int, level: Int = 1000) = TileSpec(
        TileKey(page, level, 0, 0),
        IntSize(100, 100),
        IntRect(0, 0, 100, 100),
    )

    @Test
    fun renders_in_wanted_order() = runTest {
        // The view at scrollY 600 wants page 1 (visible), then page 2 (ahead margin), then page 0
        // (behind margin) at level 1000 (the TileGrid contract).
        val wanted = wantedTiles(smallPages, WebtoonView(600.0, 1f, 0f), 1000, forward = true)
        assertEquals(listOf(1, 2, 0), wanted.ordered.map { it.key.page })

        val source = FakeSource().apply { hold = CompletableDeferred() }
        val budget = ImageBudget()
        val manager = TileManager(this, source, budget)

        manager.update(wanted)
        runCurrent()

        // Renders started in wanted order, each with its spec's full size and rect verbatim: the
        // manager never recomputes tile placement, so TileGrid's page-relative scales and shared
        // tile edges reach the engine unchanged (no seam between neighbours).
        assertEquals(wanted.ordered.map { it.key.page }, source.calls.map { it.first })
        assertEquals(
            wanted.ordered.map { Triple(it.key.page, it.full, it.rect) },
            source.calls,
        )
        assertTrue(manager.tiles.values.all { it is TileSlot.Loading })
        assertEquals(wanted.ordered.sumOf { rgbaBytes(it.rect.width, it.rect.height) }, budget.usedBytes)

        // Renders complete into Ready slots that carry the reserved bytes; awaitIdle returns once
        // they do (the gate opens from a scheduled task while awaitIdle suspends).
        launch { delay(100); source.hold!!.complete(Unit) }
        manager.awaitIdle()
        for (spec in wanted.ordered) {
            val slot = manager.tiles[spec.key]
            assertTrue("tile ${spec.key} should be Ready, was $slot", slot is TileSlot.Ready)
            assertEquals(spec, (slot as TileSlot.Ready).spec)
        }
        assertEquals(wanted.ordered.sumOf { rgbaBytes(it.rect.width, it.rect.height) }, budget.usedBytes)

        // A repeated identical update re-renders nothing.
        manager.update(wanted)
        advanceUntilIdle()
        assertEquals(wanted.ordered.size, source.calls.size)

        manager.close()
        assertEquals(0, budget.usedBytes)
        assertTrue(manager.tiles.isEmpty())
    }

    @Test
    fun cancels_tiles_leaving_wanted() = runTest {
        val source = FakeSource().apply { hold = CompletableDeferred() }
        val budget = ImageBudget()
        val manager = TileManager(this, source, budget)

        // View on pages 0..2: three tiles go in-flight on the gate.
        val first = wantedTiles(smallPages, WebtoonView(600.0, 1f, 0f), 1000, forward = true)
        manager.update(first)
        runCurrent()
        assertEquals(listOf(1, 2, 0), source.calls.map { it.first })

        // A view on pages 3..4 wants none of them: the in-flight renders are cancelled, their
        // reservations returned, and their Loading slots dropped.
        val second = wantedTiles(smallPages, WebtoonView(2400.0, 1f, 0f), 1000, forward = true)
        assertEquals(listOf(4, 3), second.ordered.map { it.key.page })
        manager.update(second)
        advanceUntilIdle()

        assertEquals(setOf(1, 2, 0), source.cancelled.toSet())
        assertEquals(listOf(4, 3), source.calls.drop(3).map { it.first })
        assertEquals(
            "no stale tile entry may survive",
            second.ordered.map { it.key }.toSet(),
            manager.tiles.keys.toSet(),
        )
        assertEquals(second.ordered.sumOf { rgbaBytes(it.rect.width, it.rect.height) }, budget.usedBytes)

        // The new tiles finish once the gate opens.
        source.hold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.tiles.values.all { it is TileSlot.Ready })

        manager.close()
        assertEquals(0, budget.usedBytes)
        assertTrue(manager.tiles.isEmpty())
    }

    @Test
    fun rapid_view_changes_cancel_stale_tiles() = runTest {
        // 40 tall pages (three 1x tiles each) and 100 updates scrolling down, faster than any
        // render completes: the dispatcher never runs between updates, so every render a later
        // update cancels must be cancelled before it can occupy a render slot (the production
        // PageRenderer renders on a limitedParallelism(2) dispatcher) and must never leak its
        // reservation.
        val layout = WebtoonLayout.build(40, 600, 500, { IntSize(600, 3000) }, { false })
        val source = FakeSource().apply { hold = CompletableDeferred() }
        val budget = ImageBudget()
        val manager = TileManager(this, source, budget)

        var scrollY = 0.0
        var last: Wanted? = null
        for (i in 0 until 100) {
            scrollY += 25.0
            val wanted = wantedTiles(layout, WebtoonView(scrollY, 1f, 0f), 1000, forward = true)
            manager.update(wanted)
            last = wanted
            assertTrue("step $i: ${budget.usedBytes} over the cap", budget.usedBytes <= budget.capBytes)
        }
        val final = last!!
        val finalSpecs = final.ordered.map { Triple(it.key.page, it.full, it.rect) }.toSet()

        advanceUntilIdle()

        // At most limitedParallelism (the renderer's 2) stale renders may have started beyond the
        // final wanted set; this manager starts none at all.
        val stale = source.calls.count { it !in finalSpecs }
        assertTrue("$stale stale renders started beyond the final wanted set", stale <= 2)
        assertEquals("only final wanted tiles may render", finalSpecs, source.calls.toSet())
        // No leaked bytes: the budget holds exactly the final wanted set's reservations.
        assertEquals(final.ordered.sumOf { rgbaBytes(it.rect.width, it.rect.height) }, budget.usedBytes)
        assertEquals(final.ordered.map { it.key }.toSet(), manager.tiles.keys.toSet())

        source.hold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.tiles.values.all { it is TileSlot.Ready })

        manager.close()
        assertEquals(0, budget.usedBytes)
    }

    @Test
    fun evicts_unwanted_before_other_levels_never_visible() = runTest {
        // Tiles are 100x100 (tileBytes each); the cap holds exactly four. Pages are chosen so that
        // page distances from the first visible tile (page 10) are distinct.
        val budget = ImageBudget(capBytes = 4 * tileBytes)
        val source = FakeSource()
        val manager = TileManager(this, source, budget)

        val v1 = tile(10)
        val v2 = tile(11)
        val p1 = tile(12)
        val p2 = tile(14)
        val n = tile(13)
        val w = tile(15, level = 2000)
        val x = tile(16)

        // Four tiles render: v1, v2 visible; p1, p2 wanted prefetch.
        manager.update(Wanted(listOf(v1, v2, p1, p2), setOf(v1.key, v2.key)))
        advanceUntilIdle()
        assertEquals(4 * tileBytes, budget.usedBytes)
        assertTrue(source.released.isEmpty())

        // The view moves: v1, v2 stay visible; n (page 13) and the zoomed w (page 15) become
        // wanted. Reserving n fails, so eviction takes Ready tiles not wanted, farthest page
        // distance first: p2 (page 14) before p1 (page 12). Reserving w then evicts p1. The
        // visible v1 and v2 are never touched.
        manager.update(Wanted(listOf(v1, v2, n, w), setOf(v1.key, v2.key, n.key, w.key)))
        advanceUntilIdle()

        assertEquals(listOf(14, 12), source.released)
        assertEquals(listOf(10, 11, 12, 14, 13, 15), source.calls.map { it.first })
        assertEquals(setOf(10, 11, 13, 15), manager.tiles.keys.map { it.page }.toSet())
        assertEquals(4 * tileBytes, budget.usedBytes)

        // Now w (level 2000) is wanted but no longer visible, and the new visible tile x needs its
        // bytes. There is no not-wanted Ready tile left, so the not-visible other-level tile w is
        // evicted; visible tiles and wanted same-level tiles are never touched.
        manager.update(Wanted(listOf(v1, v2, n, w, x), setOf(v1.key, v2.key, n.key, x.key)))
        advanceUntilIdle()

        assertEquals(listOf(14, 12, 15), source.released)
        assertEquals(listOf(10, 11, 12, 14, 13, 15, 16), source.calls.map { it.first })
        assertEquals(setOf(10, 11, 13, 16), manager.tiles.keys.map { it.page }.toSet())
        assertEquals(4 * tileBytes, budget.usedBytes)

        manager.close()
        assertEquals(0, budget.usedBytes)
    }

    @Test
    fun random_sequence_never_exceeds_cap() = runTest {
        // 24 tall pages and a cap of 64 tiles' worth of bytes (64 one-x tiles): zooming and
        // scrolling accumulate held tiles beyond that, so eviction churns throughout.
        val layout = WebtoonLayout.build(24, 600, 500, { IntSize(600, 3000) }, { false })
        val cap = 64 * rgbaBytes(600, 1024)
        val budget = ImageBudget(capBytes = cap)
        val source = FakeSource()
        val manager = TileManager(this, source, budget)

        val rng = Random(20261122L)
        val maxScroll = layout.totalHeight - layout.viewH
        var scrollY = 0.0
        var zoom = 1f
        var forward = true

        repeat(2000) { step ->
            when (rng.nextInt(100)) {
                in 0 until 55 -> scrollY += (if (forward) 1 else -1) * rng.nextDouble(10.0, 400.0)
                in 55 until 65 -> zoom = listOf(1f, 1.5f, 2f, 3f)[rng.nextInt(4)]
                in 65 until 70 -> forward = !forward
                in 70 until 80 -> scrollY = rng.nextDouble(0.0, maxScroll)
                else -> Unit // stay still
            }
            scrollY = scrollY.coerceIn(0.0, maxScroll)
            val wanted = wantedTiles(layout, WebtoonView(scrollY, zoom, 0f), zoomLevel(zoom), forward)
            manager.update(wanted)
            assertTrue(
                "step $step: ${budget.usedBytes} over the cap $cap",
                budget.usedBytes <= cap,
            )
            // A completion step lets every render finish; most steps do not, so updates outrun
            // rendering and stale work must be cancelled before it leaks bytes.
            if (rng.nextInt(100) < 25) {
                advanceUntilIdle()
                assertTrue(
                    "step $step (after completions): ${budget.usedBytes} over the cap $cap",
                    budget.usedBytes <= cap,
                )
            }
        }

        // Drain whatever is left, then close: nothing is held and every completed image was
        // released exactly once.
        advanceUntilIdle()
        manager.close()
        assertEquals(0, budget.usedBytes)
        assertTrue(manager.tiles.isEmpty())
        assertTrue(manager.failedPages.isEmpty())
        assertEquals(
            "every completed image must have been released",
            source.completed.size,
            source.released.size,
        )
        assertTrue("peak ${budget.peakBytes} over the cap $cap", budget.peakBytes <= cap)
    }

    @Test
    fun failed_tile_marks_page() = runTest {
        val source = FakeSource().apply { failPages = setOf(2) }
        val budget = ImageBudget()
        val manager = TileManager(this, source, budget)
        val wanted = wantedTiles(smallPages, WebtoonView(600.0, 1f, 0f), 1000, forward = true)

        manager.update(wanted)
        advanceUntilIdle()

        // The failed tile's slot is Failed and the page is marked with the engine detail — read
        // from NativeRenderException.detail, not message ("Panic: boom-2").
        val failedKey = TileKey(2, 1000, 0, 0)
        val slot = manager.tiles[failedKey]
        assertTrue("page 2's tile should be Failed, was $slot", slot is TileSlot.Failed)
        assertEquals("boom-2", (slot as TileSlot.Failed).reason)
        assertEquals("boom-2", manager.failedPages[2])
        assertEquals(setOf(2), manager.failedPages.keys)
        // The other tiles are unaffected and the failed render's reservation was returned.
        assertTrue(manager.tiles[TileKey(1, 1000, 0, 0)] is TileSlot.Ready)
        assertTrue(manager.tiles[TileKey(0, 1000, 0, 0)] is TileSlot.Ready)
        assertEquals(2 * rgbaBytes(600, 600), budget.usedBytes)

        // The failure sticks: a repeated update neither re-renders the failed tile nor renders any
        // other tile of the failed page (the viewport draws the page's error placeholder).
        manager.update(wanted)
        advanceUntilIdle()
        assertEquals(1, source.calls.count { it.first == 2 })
        assertEquals(3, source.calls.size)

        manager.close()
        assertEquals(0, budget.usedBytes)
    }
}
