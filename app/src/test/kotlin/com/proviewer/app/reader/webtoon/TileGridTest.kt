package com.proviewer.app.reader.webtoon

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure tile grid and wanted-tile selection (grid geometry, prefetch order). */
class TileGridTest {

    /** Five square pages: 600x600 fit into a 600-wide viewport -> height 600, tops 0,600..2400. */
    private val smallPages = WebtoonLayout.build(5, 600, 500, { IntSize(600, 600) }, { false })

    /** Three tall pages: 600x3000 fit into a 600-wide viewport -> height 3000, tops 0,3000,6000. */
    private val tallPages = WebtoonLayout.build(3, 600, 500, { IntSize(600, 3000) }, { false })

    /**
     * The tiles exactly cover [full]: every rect lies inside it, the rects are pairwise disjoint
     * (touching edges allowed), and their areas sum to the full area (with integers this means the
     * union is exactly [full]).
     */
    private fun assertExactCover(full: IntSize, tiles: List<TileSpec>) {
        assertTrue("no tiles for $full", tiles.isNotEmpty())
        var area = 0
        for (t in tiles) {
            assertEquals("tile ${t.key} carries the wrong full size", full, t.full)
            val r = t.rect
            assertTrue(
                "rect $r outside full $full",
                r.left >= 0 && r.top >= 0 && r.right <= full.width && r.bottom <= full.height,
            )
            assertTrue("empty rect $r", r.width > 0 && r.height > 0)
            area += r.width * r.height
        }
        assertEquals("union of rects != full $full", full.width * full.height, area)
        for (i in tiles.indices) {
            for (j in i + 1 until tiles.size) {
                val a = tiles[i].rect
                val b = tiles[j].rect
                assertTrue(
                    "rects $i and $j overlap: $a vs $b",
                    a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top,
                )
            }
        }
    }

    @Test
    fun level_1000_single_column() {
        // The level is the zoom scaled to an integer: zoom * 1000.
        assertEquals(1000, zoomLevel(1f))
        assertEquals(2500, zoomLevel(2.5f))

        // A 500x3000 page in a 500-wide viewport: full size 500x3000 at 1x, cut into TILE_EDGE
        // rows in one full-width column (edges clamp at the page bottom).
        val layout = WebtoonLayout.build(1, 500, 2000, { IntSize(500, 3000) }, { false })
        val full = fullSize(layout, 0, 1000)
        assertEquals(IntSize(500, 3000), full)
        val tiles = pageTiles(0, full, 1000)
        assertEquals(3, tiles.size)
        assertEquals(
            listOf(TileKey(0, 1000, 0, 0), TileKey(0, 1000, 0, 1), TileKey(0, 1000, 0, 2)),
            tiles.map { it.key },
        )
        assertEquals(IntRect(0, 0, 500, 1024), tiles[0].rect)
        assertEquals(IntRect(0, 1024, 500, 2048), tiles[1].rect)
        assertEquals(IntRect(0, 2048, 500, 3000), tiles[2].rect)
        for (t in tiles) assertEquals(full, t.full)

        // At 1x the column spans the full width even when the viewport is wider than TILE_EDGE.
        val wide = WebtoonLayout.build(1, 1500, 2000, { IntSize(1500, 3000) }, { false })
        val wideTiles = pageTiles(0, fullSize(wide, 0, 1000), 1000)
        assertEquals(3, wideTiles.size)
        for (t in wideTiles) {
            assertEquals(0, t.key.col)
            assertEquals(IntRect(0, t.rect.top, 1500, t.rect.bottom), t.rect)
        }

        // A page shorter than TILE_EDGE is a single tile clamped to the page height.
        val short = pageTiles(0, IntSize(500, 500), 1000)
        assertEquals(listOf(TileKey(0, 1000, 0, 0)), short.map { it.key })
        assertEquals(IntRect(0, 0, 500, 500), short[0].rect)
    }

    @Test
    fun tiles_cover_page_exactly_no_overlap() {
        // Zoomed grid (level 2500) of a 500x3000 page: full 1250x7500 -> 2 columns x 8 rows, the
        // last column and row clamped to the page edges.
        val full = IntSize(1250, 7500)
        val tiles = pageTiles(2, full, 2500)
        assertEquals(2 * 8, tiles.size)
        assertExactCover(full, tiles)
        // Keys run top to bottom, then left to right (row-major).
        assertEquals(TileKey(2, 2500, 0, 7), tiles[14].key)
        assertEquals(TileKey(2, 2500, 1, 7), tiles[15].key)
        // Edges clamp: the last row is 7500 - 7*1024 = 332 tall, the last column 1250 - 1024 wide.
        assertEquals(IntRect(0, 7168, 1024, 7500), tiles[14].rect)
        assertEquals(IntRect(1024, 7168, 1250, 7500), tiles[15].rect)

        // The 1x column grid covers the page exactly, too.
        assertExactCover(IntSize(500, 3000), pageTiles(0, IntSize(500, 3000), 1000))
        // A page exactly TILE_EDGE wide and tall is one tile without a clamped tail.
        val exact = pageTiles(0, IntSize(1024, 1024), 2000)
        assertEquals(listOf(TileKey(0, 2000, 0, 0)), exact.map { it.key })
        assertEquals(IntRect(0, 0, 1024, 1024), exact[0].rect)
    }

    @Test
    fun visible_first_then_ahead_then_behind() {
        // Viewport [600, 1100) lies on page 1 (page 0 ends exactly at its top: not visible);
        // forward, the ahead margin reaches into page 2, the behind margin into page 0.
        // Level 1000: one full-width tile per 600-tall page.
        val w = wantedTiles(smallPages, WebtoonView(600.0, 1f, 0f), 1000, forward = true)
        assertEquals(
            listOf(
                TileKey(1, 1000, 0, 0), // visible
                TileKey(2, 1000, 0, 0), // ahead margin (below the viewport, scrolling forward)
                TileKey(0, 1000, 0, 0), // behind margin (above the viewport)
            ),
            w.ordered.map { it.key },
        )
        assertEquals(setOf(TileKey(1, 1000, 0, 0)), w.visible)
        // The visible tile is page 1's whole 1x render.
        assertEquals(IntSize(600, 600), w.ordered[0].full)
        assertEquals(IntRect(0, 0, 600, 600), w.ordered[0].rect)
        // No duplicates: every wanted tile is distinct.
        assertEquals(w.ordered.size, w.ordered.map { it.key }.toSet().size)

        // Visible tiles across pages run top to bottom: the viewport [400, 900) shows page 0's
        // tail, then page 1's head; the ahead margin [900, 1400) adds page 2's tile behind them,
        // and the behind margin [150, 400) adds nothing new.
        val straddle = wantedTiles(smallPages, WebtoonView(400.0, 1f, 0f), 1000, forward = true)
        assertEquals(
            listOf(TileKey(0, 1000, 0, 0), TileKey(1, 1000, 0, 0), TileKey(2, 1000, 0, 0)),
            straddle.ordered.map { it.key },
        )
        assertEquals(setOf(TileKey(0, 1000, 0, 0), TileKey(1, 1000, 0, 0)), straddle.visible)
    }

    @Test
    fun direction_flips_margins() {
        // Viewport [650, 1150) lies on page 1. Forward: page 2 below is the ahead margin and page
        // 0 above the behind margin. Backward: the same two margins swap roles and order.
        val view = WebtoonView(650.0, 1f, 0f)
        val forward = wantedTiles(smallPages, view, 1000, forward = true)
        assertEquals(
            listOf(TileKey(1, 1000, 0, 0), TileKey(2, 1000, 0, 0), TileKey(0, 1000, 0, 0)),
            forward.ordered.map { it.key },
        )
        val backward = wantedTiles(smallPages, view, 1000, forward = false)
        assertEquals(
            listOf(TileKey(1, 1000, 0, 0), TileKey(0, 1000, 0, 0), TileKey(2, 1000, 0, 0)),
            backward.ordered.map { it.key },
        )
        // The visible set does not depend on the scroll direction.
        assertEquals(setOf(TileKey(1, 1000, 0, 0)), forward.visible)
        assertEquals(forward.visible, backward.visible)
    }

    @Test
    fun zoomed_visible_tiles_follow_pan() {
        // Zoom 2 (level 2000): the full page is 1200x6000 level px -> two 1024-wide columns, six
        // 1024-tall rows; the viewport covers 600x250 1x px. At panX 0 it shows the left column.
        val left = wantedTiles(tallPages, WebtoonView(0.0, 2f, 0f), 2000, forward = true)
        assertEquals(
            listOf(TileKey(0, 2000, 0, 0), TileKey(0, 1000, 0, 0)),
            left.ordered.map { it.key },
        )

        // Panning right by 500 screen px moves the visible x range to [250, 550) content px =
        // [500, 1100) level px: column 1 becomes visible too, ordered left to right.
        val panned = wantedTiles(tallPages, WebtoonView(0.0, 2f, 500f), 2000, forward = true)
        assertEquals(
            listOf(TileKey(0, 2000, 0, 0), TileKey(0, 2000, 1, 0), TileKey(0, 1000, 0, 0)),
            panned.ordered.map { it.key },
        )

        // Scrolling moves the visible rows: content y [300, 550) = level y [600, 1100) spans
        // rows 0 and 1.
        val scrolled = wantedTiles(tallPages, WebtoonView(300.0, 2f, 0f), 2000, forward = true)
        assertEquals(
            listOf(TileKey(0, 2000, 0, 0), TileKey(0, 2000, 0, 1), TileKey(0, 1000, 0, 0)),
            scrolled.ordered.map { it.key },
        )
        assertEquals(
            setOf(TileKey(0, 2000, 0, 0), TileKey(0, 2000, 0, 1), TileKey(0, 1000, 0, 0)),
            scrolled.visible,
        )

        // Zoomed prefetch margins are horizontally clipped to the visible columns: at panX 0 only
        // column 0 is visible, so the ahead margin's second-row tile is wanted for column 0 only,
        // never for the off-screen column 1.
        val clipped = wantedTiles(tallPages, WebtoonView(100.0, 2f, 0f), 2000, forward = true)
        assertEquals(
            listOf(TileKey(0, 2000, 0, 0), TileKey(0, 2000, 0, 1), TileKey(0, 1000, 0, 0)),
            clipped.ordered.map { it.key },
        )
        val keys = clipped.ordered.map { it.key }.toSet()
        assertFalse(TileKey(0, 2000, 1, 0) in keys)
        assertFalse(TileKey(0, 2000, 1, 1) in keys)
    }

    @Test
    fun zoomed_appends_1x_fallback() {
        // Zoomed (level 2000), viewport content y [900, 1150): the settled rows 1-2 of column 0
        // come first, then the 1x tiles of the visible area (1x rows 0-1) as the fallback.
        val w = wantedTiles(tallPages, WebtoonView(900.0, 2f, 0f), 2000, forward = true)
        assertEquals(
            listOf(
                TileKey(0, 2000, 0, 1),
                TileKey(0, 2000, 0, 2),
                TileKey(0, 1000, 0, 0),
                TileKey(0, 1000, 0, 1),
            ),
            w.ordered.map { it.key },
        )
        // The fallback tiles are visible too: they are drawn underneath until the settled tiles
        // are ready, so they must not be evicted either.
        assertEquals(w.ordered.map { it.key }.toSet(), w.visible)
        // The appended tiles are the real 1x grid: full width, TILE_EDGE rows clamped to the page.
        assertEquals(IntSize(600, 3000), w.ordered[2].full)
        assertEquals(IntRect(0, 0, 600, 1024), w.ordered[2].rect)
        assertEquals(IntRect(0, 1024, 600, 2048), w.ordered[3].rect)

        // The fallback can span pages: content y [2900, 3150) touches page 0's tail and page 1's
        // head; each page contributes its settled tile and then its 1x fallback tile.
        val across = wantedTiles(tallPages, WebtoonView(2900.0, 2f, 0f), 2000, forward = true)
        assertEquals(
            listOf(
                TileKey(0, 2000, 0, 5),
                TileKey(1, 2000, 0, 0),
                TileKey(0, 1000, 0, 2),
                TileKey(1, 1000, 0, 0),
            ),
            across.ordered.map { it.key },
        )
        assertEquals(across.ordered.map { it.key }.toSet(), across.visible)

        // At settled level 1000 there is no separate fallback pass and no duplicates.
        val atOne = wantedTiles(tallPages, WebtoonView(900.0, 2f, 0f), 1000, forward = true)
        assertEquals(
            listOf(TileKey(0, 1000, 0, 0), TileKey(0, 1000, 0, 1)),
            atOne.ordered.map { it.key },
        )
        assertEquals(atOne.ordered.map { it.key }.toSet(), atOne.visible)
    }

    @Test
    fun estimated_and_failed_pages_have_no_tiles() {
        // Page 1's size is not probed yet (estimated); page 2's probe failed (error placeholder).
        val mixed = WebtoonLayout.build(4, 600, 1000, { if (it == 1) null else IntSize(600, 3000) }, { it == 2 })
        // Heights: 3000 known, 3000 median estimate, viewH * ERROR_PAGE_HEIGHT_FRACTION, 3000 known.
        assertEquals(3000, mixed.height(0))
        assertEquals(3000, mixed.height(1))
        assertEquals(500, mixed.height(2))
        assertEquals(3000, mixed.height(3))
        assertFalse(mixed.isEstimated(0))
        assertTrue(mixed.isEstimated(1))
        assertTrue(mixed.isEstimated(2))
        assertFalse(mixed.isEstimated(3))

        // A viewport fully inside the estimated page (page 1 spans [3000, 6000) and both margins
        // stay inside it too) produces nothing at all.
        val onEstimated = wantedTiles(mixed, WebtoonView(4000.0, 1f, 0f), 1000, forward = true)
        assertEquals(0, onEstimated.ordered.size)
        assertEquals(0, onEstimated.visible.size)

        // A viewport over the failed page: the placeholder contributes no tiles; the known page 3
        // after it does (visible row plus ahead margin), and the estimated page 1 behind the
        // viewport is skipped as well.
        val onFailed = wantedTiles(mixed, WebtoonView(6300.0, 1f, 0f), 1000, forward = true)
        assertEquals(
            listOf(TileKey(3, 1000, 0, 0), TileKey(3, 1000, 0, 1)),
            onFailed.ordered.map { it.key },
        )
        for (key in onFailed.ordered.map { it.key } + onFailed.visible) {
            assertTrue("unexpected tile $key", key.page != 1 && key.page != 2)
        }

        // Margins skip estimated pages too: the ahead margin reaches into page 1 and finds
        // nothing; only page 0's visible tile and its own behind margin are wanted.
        val marginSkip = wantedTiles(mixed, WebtoonView(2500.0, 1f, 0f), 1000, forward = true)
        assertEquals(
            listOf(TileKey(0, 1000, 0, 2), TileKey(0, 1000, 0, 1)),
            marginSkip.ordered.map { it.key },
        )
        assertEquals(setOf(TileKey(0, 1000, 0, 2)), marginSkip.visible)
    }
}
