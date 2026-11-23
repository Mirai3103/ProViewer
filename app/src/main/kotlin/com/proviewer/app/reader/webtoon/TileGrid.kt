package com.proviewer.app.reader.webtoon

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Pure tile grid and wanted-tile selection for the Webtoon viewport: which page regions the tile
 * manager should hold and render, in which order. No Android runtime types and no engine calls;
 * exercised by JVM unit tests.
 *
 * Tiles are rectangles of a page's **full-size render at a zoom level** ([fullSize]): at 1x a page
 * is `[viewW x height(page)]` content px, at level `L = zoom*1000` it is
 * `[round(viewW*L/1000) x round(height(page)*L/1000)]` px. Tile boundaries are fixed in that
 * full-size space ([TILE_EDGE] steps, edges clamped to the page bounds), so neighbouring tiles
 * abut exactly — no seam, gap or overlap — by construction ([pageTiles]).
 */

/** Tile edge in full-size page px; at most the GPU max texture size. */
const val TILE_EDGE = 1024

/** Prefetch distances in viewport heights: one viewport ahead, half a viewport behind. */
const val PREFETCH_AHEAD_VIEWPORTS = 1.0
const val PREFETCH_BEHIND_VIEWPORTS = 0.5

/** Identifies a tile: the book [page], the zoom [level] (`zoom*1000`), and the grid cell. */
data class TileKey(val page: Int, val level: Int, val col: Int, val row: Int)

/**
 * One tile: its [TileKey], the page's full size at the level ([full]), and the tile's rect
 * ([rect]) in that full-size page px.
 */
data class TileSpec(val key: TileKey, val full: IntSize, val rect: IntRect)

/**
 * A point of view onto the Webtoon column: [scrollY] is the 1x content px shown at the viewport
 * top, [zoom] the zoom factor, [panX] the horizontal pan in screen px. Screen mapping:
 * screen y = `(Y - scrollY) * zoom`, screen x = `X * zoom - panX` for content point `(X, Y)`.
 */
data class WebtoonView(val scrollY: Double, val zoom: Float, val panX: Float)

/** The tiles the tile manager wants, in render-priority order, plus the keys of the on-screen ones. */
data class Wanted(val ordered: List<TileSpec>, val visible: Set<TileKey>)

/** The zoom [zoom] as a scale level: `zoom * 1000` (1x -> 1000, 2.5x -> 2500). */
fun zoomLevel(zoom: Float): Int = (zoom.toDouble() * 1000.0).roundToInt()

/**
 * A page's full size at scale [level]: `round(viewW*level/1000) x round(height(page)*level/1000)`
 * (the page is fit to [WebtoonLayout.viewW] at 1x).
 */
fun fullSize(layout: WebtoonLayout, page: Int, level: Int): IntSize = IntSize(
    (layout.viewW.toDouble() * level / 1000.0).roundToInt(),
    (layout.height(page).toDouble() * level / 1000.0).roundToInt(),
)

/**
 * The tile grid of a page whose full size at [level] is [full]: at level 1000 one column of full
 * width cut into [TILE_EDGE]-tall rows, otherwise a [TILE_EDGE] grid in both directions. Edges
 * clamp to the page bounds, so every tile is 1..[TILE_EDGE] px wide and tall (a degenerate `full`
 * yields no tiles) and neighbouring tiles abut exactly: the union of the rects is `full`, the
 * rects are pairwise disjoint, ordered top to bottom, left to right.
 */
fun pageTiles(page: Int, full: IntSize, level: Int): List<TileSpec> {
    if (full.width <= 0 || full.height <= 0) return emptyList()
    val tiles = ArrayList<TileSpec>()
    var row = 0
    var y = 0
    while (y < full.height) {
        val bottom = minOf(y + TILE_EDGE, full.height)
        if (level == 1000) {
            tiles += TileSpec(TileKey(page, level, 0, row), full, IntRect(0, y, full.width, bottom))
        } else {
            var col = 0
            var x = 0
            while (x < full.width) {
                val right = minOf(x + TILE_EDGE, full.width)
                tiles += TileSpec(TileKey(page, level, col, row), full, IntRect(x, y, right, bottom))
                x += TILE_EDGE
                col++
            }
        }
        y += TILE_EDGE
        row++
    }
    return tiles
}

/**
 * The tiles the viewport wants at [settledLevel], in render-priority order ([Wanted.ordered]):
 * the tiles intersecting the viewport (top to bottom, left to right), then the settled-level tiles
 * within the prefetch margin in the scroll direction ([forward]: below the viewport; otherwise
 * above), then the tiles within the opposite margin; when [settledLevel] is not 1000, the 1x tiles
 * of the visible area are appended as fallback (drawn underneath until the settled tiles are
 * ready). Pages that are estimated or failed produce no tiles. Zoomed margins are horizontally
 * clipped to the visible columns.
 *
 * [Wanted.visible] holds the keys of the on-screen tiles (settled-level plus fallback) — the set
 * the tile manager must never evict. Tiles are deduplicated: a tile straddling the viewport edge
 * appears once, at its visible position.
 *
 * A degenerate view (`zoom <= 0`, non-positive viewport) or an empty book wants no tiles.
 */
fun wantedTiles(
    layout: WebtoonLayout,
    view: WebtoonView,
    settledLevel: Int,
    forward: Boolean,
): Wanted {
    val zoom = view.zoom.toDouble()
    if (layout.count == 0 || zoom <= 0.0 || layout.viewW <= 0 || layout.viewH <= 0) {
        return Wanted(emptyList(), emptySet())
    }
    // The viewport rectangle in 1x content px: inverting screen y = (Y - scrollY) * zoom and
    // screen x = X * zoom - panX for the screen rect [0, viewW] x [0, viewH].
    val y0 = view.scrollY
    val y1 = y0 + layout.viewH / zoom
    val x0 = view.panX / zoom
    val x1 = x0 + layout.viewW / zoom
    // Prefetch margins: viewports of content beyond the viewport edges in the scroll direction.
    val aheadPx = layout.viewH / zoom * PREFETCH_AHEAD_VIEWPORTS
    val behindPx = layout.viewH / zoom * PREFETCH_BEHIND_VIEWPORTS
    val (aheadLo, aheadHi) = if (forward) y1 to y1 + aheadPx else (y0 - aheadPx) to y0
    val (behindLo, behindHi) = if (forward) (y0 - behindPx) to y0 else y1 to y1 + behindPx

    val ordered = ArrayList<TileSpec>()
    val seen = HashSet<TileKey>()
    val visible = LinkedHashSet<TileKey>()

    // Adds the tiles of [page] at [level] whose rects intersect the content-Y band [lo, hi),
    // optionally clipped horizontally to the viewport's x range. Tiles added by a marked call are
    // on-screen and join [visible].
    fun collect(page: Int, level: Int, lo: Double, hi: Double, clipX: Boolean, markVisible: Boolean) {
        if (layout.isEstimated(page)) return // estimated or failed pages produce no tiles
        val full = fullSize(layout, page, level)
        if (full.width <= 0 || full.height <= 0) return
        val h = layout.height(page)
        val top = layout.top(page)
        // Exact page-relative scales: the full-size page at the level maps onto the page's 1x
        // footprint ([0, viewW] x [top, top + h]), so tile edges and screen edges agree exactly.
        val relYLo = (maxOf(lo, top) - top) * full.height.toDouble() / h
        val relYHi = (minOf(hi, top + h) - top) * full.height.toDouble() / h
        if (relYHi <= relYLo) return
        val relXLo: Double
        val relXHi: Double
        if (clipX) {
            relXLo = maxOf(x0, 0.0) * full.width.toDouble() / layout.viewW
            relXHi = minOf(x1, layout.viewW.toDouble()) * full.width.toDouble() / layout.viewW
            if (relXHi <= relXLo) return
        } else {
            relXLo = 0.0
            relXHi = full.width.toDouble()
        }
        for (tile in pageTiles(page, full, level)) {
            val r = tile.rect
            if (r.left < relXHi && relXLo < r.right && r.top < relYHi && relYLo < r.bottom) {
                if (seen.add(tile.key)) ordered += tile
                if (markVisible) visible += tile.key
            }
        }
    }

    // 1. Visible tiles at the settled level, top to bottom, left to right.
    for (page in layout.visiblePages(y0, y1)) {
        collect(page, settledLevel, y0, y1, clipX = true, markVisible = true)
    }
    // 2. Prefetch margin in the scroll direction, then the opposite margin. Zoomed margins are
    //    horizontally clipped to the visible columns; at 1x the single full-width column needs no
    //    clip.
    val marginClipX = settledLevel != 1000
    for (page in layout.visiblePages(aheadLo, aheadHi)) {
        collect(page, settledLevel, aheadLo, aheadHi, clipX = marginClipX, markVisible = false)
    }
    for (page in layout.visiblePages(behindLo, behindHi)) {
        collect(page, settledLevel, behindLo, behindHi, clipX = marginClipX, markVisible = false)
    }
    // 3. The 1x tiles of the visible area, kept as the fallback underneath while zoomed.
    if (settledLevel != 1000) {
        for (page in layout.visiblePages(y0, y1)) {
            collect(page, 1000, y0, y1, clipX = true, markVisible = true)
        }
    }
    return Wanted(ordered, visible)
}
