package com.proviewer.app.reader.webtoon

import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure Webtoon column layout: pages fitted to the viewport width and stacked top to bottom with no
 * gap. No Android runtime types and no engine calls; exercised by JVM unit tests.
 */

/** A failed page shows an error placeholder this fraction of the viewport height tall. */
const val ERROR_PAGE_HEIGHT_FRACTION = 0.5f

/**
 * A position in the Webtoon column: [page], and [offsetPx] — pixels below the page's top at 1x
 * (the fit-width scale of the layout the anchor was taken in). The offset is relative to content
 * that scales with the viewport width, so [rescaleAnchor] scales it by the width ratio on rotation.
 */
data class Anchor(val page: Int, val offsetPx: Double)

/**
 * The stacked geometry of a whole Webtoon book: per-page fit-width heights (with provisional
 * heights for pages whose size is not known yet or which failed to probe), cumulative page tops,
 * content-Y lookups, anchor round trips and the visible page range.
 *
 * All positions are [Double] (500 pages x 30 000 px exceeds Float precision). Page heights are
 * always at least 1 px, so [top]s strictly increase.
 *
 * Height of page `i` as built by [build]:
 * - `failed(i)` — the fixed error placeholder, `viewH * [ERROR_PAGE_HEIGHT_FRACTION]`;
 * - `sizeOf(i) == (w, h)` — `round(h * viewW / w)` (fit width);
 * - otherwise (size unknown) — the median of the known pages' fit-width heights, or `viewH` when
 *   no page is known.
 *
 * [isEstimated] marks the provisional pages (unknown and failed): their height may change when the
 * page's real size lands, which is when the caller rebuilds and re-anchors (see [yOf]).
 */
class WebtoonLayout private constructor(
    val count: Int,
    val viewW: Int,
    val viewH: Int,
    private val heights: IntArray,
    private val estimated: BooleanArray,
) {
    /** `tops[i]` is the content Y of page `i`'s top; `tops[count]` is [totalHeight]. */
    private val tops = DoubleArray(count + 1).also { arr ->
        var acc = 0.0
        for (i in 0 until count) {
            arr[i] = acc
            acc += heights[i]
        }
        arr[count] = acc
    }

    /** Total column height in content pixels at 1x. */
    val totalHeight: Double = tops[count]

    companion object {
        /**
         * Builds the layout of a `count`-page book in a `viewW` x `viewH` viewport. [sizeOf]
         * returns a page's natural size (null when not probed yet); [failed] marks pages whose
         * probe (or decode) failed, which get the error placeholder height even if [sizeOf]
         * returns a size. A degenerate size (non-positive width or height) counts as unknown.
         */
        fun build(
            count: Int,
            viewW: Int,
            viewH: Int,
            sizeOf: (Int) -> IntSize?,
            failed: (Int) -> Boolean,
        ): WebtoonLayout {
            val n = count.coerceAtLeast(0)
            val heights = IntArray(n)
            val estimated = BooleanArray(n)
            val knownHeights = ArrayList<Int>(n)
            for (i in 0 until n) {
                when {
                    failed(i) -> {
                        heights[i] =
                            (viewH * ERROR_PAGE_HEIGHT_FRACTION).roundToInt().coerceAtLeast(1)
                        estimated[i] = true
                    }
                    else -> {
                        val size = sizeOf(i)
                        if (size == null || size.width <= 0 || size.height <= 0) {
                            estimated[i] = true // height filled in below, once the median is known
                        } else {
                            heights[i] = (size.height.toDouble() * viewW / size.width)
                                .roundToInt().coerceAtLeast(1)
                            knownHeights += heights[i]
                        }
                    }
                }
            }
            val estimate = (median(knownHeights) ?: viewH).coerceAtLeast(1)
            for (i in 0 until n) {
                if (heights[i] == 0) heights[i] = estimate
            }
            return WebtoonLayout(n, viewW, viewH, heights, estimated)
        }

        /** Middle value for an odd count, mean of the two middle values for an even count. */
        private fun median(values: List<Int>): Int? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                ((sorted[mid - 1].toDouble() + sorted[mid]) / 2.0).roundToInt()
            }
        }
    }

    /**
     * Content Y of [page]'s top at 1x. `top(0)` is 0; `top(count)` is [totalHeight]; out-of-range
     * pages clamp into `0..count`.
     */
    fun top(page: Int): Double = tops[page.coerceIn(0, count)]

    /** Page [page]'s stacked height in content pixels at 1x (out of range for an empty book: 0). */
    fun height(page: Int): Int = if (count == 0) 0 else heights[page.coerceIn(0, count - 1)]

    /**
     * Whether page [page]'s height is provisional — not derived from its real size (size unknown,
     * or the page failed and shows the error placeholder). Its height may change on a rebuild.
     */
    fun isEstimated(page: Int): Boolean =
        count != 0 && estimated[page.coerceIn(0, count - 1)]

    /**
     * The page containing content Y [y]: the page whose `[top, top + height)` range contains it,
     * with a Y exactly at a top belonging to that page. Ys outside the content clamp to the first
     * or last page (0 for an empty book).
     */
    fun pageAt(y: Double): Int {
        if (count == 0) return 0
        val cy = y.coerceIn(0.0, totalHeight)
        // Largest page index whose top is <= cy (tops strictly increase; heights >= 1).
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= cy) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * The [Anchor] at content Y [y]: [pageAt] plus the offset below that page's top. For Y inside
     * the content the round trip is exact: `yOf(anchorAt(y)) == y`.
     */
    fun anchorAt(y: Double): Anchor {
        val page = pageAt(y)
        return Anchor(page, y - tops[page])
    }

    /** The content Y of [anchor]'s position at 1x: the page's top plus the offset below it. */
    fun yOf(anchor: Anchor): Double = tops[anchor.page.coerceIn(0, count)] + anchor.offsetPx

    /**
     * The pages intersecting the viewport `[y0, y1]` in content pixels at 1x. A page that only
     * touches the range's edge with zero overlap (it ends exactly at `y0`, or starts exactly at
     * `y1`) is not visible; `y1 <= y0` or a range outside the content gives an empty range.
     */
    fun visiblePages(y0: Double, y1: Double): IntRange {
        if (count == 0 || y1 <= y0 || y0 >= totalHeight || y1 <= 0.0) return IntRange.EMPTY
        // First page whose bottom edge is below y0 (a suffix of the pages; existence is
        // guaranteed by y0 < totalHeight).
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (tops[mid + 1] > y0) hi = mid else lo = mid + 1
        }
        val first = lo
        // Last page whose top edge is above y1 (a prefix of the pages; existence by y1 > 0).
        lo = 0
        hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] < y1) lo = mid else hi = mid - 1
        }
        return first..lo
    }

    /**
     * The largest scroll offset at [zoom] (`> 0`): the column height minus the zoomed viewport,
     * never negative (a book shorter than the viewport does not scroll).
     */
    fun maxScroll(zoom: Float): Double = max(0.0, totalHeight - viewH / zoom.toDouble())
}

/**
 * Re-scales [a]'s offset for a viewport-width change (rotation): the page stays, and the offset
 * scales by `newViewW / oldViewW`, exactly like the page heights do — so the anchor keeps pointing
 * at the same relative position inside its page.
 */
fun rescaleAnchor(a: Anchor, oldViewW: Int, newViewW: Int): Anchor =
    if (oldViewW <= 0 || oldViewW == newViewW) {
        a
    } else {
        a.copy(offsetPx = a.offsetPx * newViewW.toDouble() / oldViewW)
    }
