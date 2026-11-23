package com.proviewer.app.reader.webtoon

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** JVM tests for the pure Webtoon column layout (tops, estimates, anchors, visibility). */
class WebtoonLayoutTest {
    /** Sizes for a three-page book with fit-width heights 1000, 1500, 2000 in a 500-wide viewport. */
    private val threePages = mapOf(
        0 to IntSize(500, 1000),
        1 to IntSize(500, 1500),
        2 to IntSize(500, 2000),
    )

    private fun threePageLayout(viewW: Int = 500, viewH: Int = 2000) =
        WebtoonLayout.build(3, viewW, viewH, { threePages[it] }, { false })

    @Test
    fun tops_are_cumulative_no_gap() {
        // Fit-width heights in a 500-wide viewport: 1500, 2000 (250x1000 page), 750, 1000.
        val sizes = mapOf(
            0 to IntSize(500, 1500),
            1 to IntSize(250, 1000),
            2 to IntSize(500, 750),
            3 to IntSize(1000, 2000),
        )
        val l = WebtoonLayout.build(4, 500, 2000, { sizes[it] }, { false })
        assertEquals(0.0, l.top(0), 0.0)
        assertEquals(1500.0, l.top(1), 0.0)
        assertEquals(3500.0, l.top(2), 0.0)
        assertEquals(4250.0, l.top(3), 0.0)
        assertEquals(5250.0, l.totalHeight, 0.0)
        assertEquals(1500, l.height(0))
        assertEquals(2000, l.height(1))
        assertEquals(750, l.height(2))
        assertEquals(1000, l.height(3))
        // Stacked with no gap: every page starts exactly where the previous one ends.
        for (p in 0..2) assertEquals(l.top(p) + l.height(p), l.top(p + 1), 0.0)
        // All sizes known, none failed: nothing is estimated.
        for (p in 0..3) assertFalse(l.isEstimated(p))
    }

    @Test
    fun unknown_uses_median_then_viewport() {
        // viewW 400: p0 400x2000 -> 2000, p2 200x600 -> 1200; p1 and p3 unknown.
        val sizes = mapOf(0 to IntSize(400, 2000), 2 to IntSize(200, 600))
        val l = WebtoonLayout.build(4, 400, 1000, { sizes[it] }, { false })
        // Median of the known heights [1200, 2000] is 1600.
        assertEquals(1600, l.height(1))
        assertEquals(1600, l.height(3))
        assertTrue(l.isEstimated(1))
        assertTrue(l.isEstimated(3))
        assertFalse(l.isEstimated(0))
        assertFalse(l.isEstimated(2))

        // Odd number of known heights: p0 -> 1000, p2 -> 2000, p4 -> 4000; median is 2000.
        val sizes3 = mapOf(
            0 to IntSize(400, 1000),
            2 to IntSize(400, 2000),
            4 to IntSize(400, 4000),
        )
        val l3 = WebtoonLayout.build(5, 400, 1000, { sizes3[it] }, { false })
        assertEquals(2000, l3.height(1))
        assertEquals(2000, l3.height(3))

        // No known page at all: every page falls back to the viewport height.
        val l2 = WebtoonLayout.build(2, 400, 1000, { null }, { false })
        assertEquals(1000, l2.height(0))
        assertEquals(1000, l2.height(1))
        assertTrue(l2.isEstimated(0))
        assertTrue(l2.isEstimated(1))
        assertEquals(2000.0, l2.totalHeight, 0.0)
    }

    @Test
    fun failed_page_fixed_height() {
        // viewW 600, viewH 2000: p0 -> 3000, p2 300x1500 -> 3000; page 1 failed to probe.
        val sizes = mapOf(0 to IntSize(600, 3000), 2 to IntSize(300, 1500))
        val l = WebtoonLayout.build(3, 600, 2000, { sizes[it] }, { it == 1 })
        // Error placeholder height: viewH * ERROR_PAGE_HEIGHT_FRACTION.
        assertEquals((2000 * ERROR_PAGE_HEIGHT_FRACTION).roundToInt(), l.height(1))
        assertEquals(1000, l.height(1))
        // The placeholder occupies a regular slot in the stack: tops 0, 3000, 4000; total 7000.
        assertEquals(3000.0, l.top(1), 0.0)
        assertEquals(4000.0, l.top(2), 0.0)
        assertEquals(7000.0, l.totalHeight, 0.0)
        // A placeholder height is provisional too (the page may still become known).
        assertTrue(l.isEstimated(1))
        assertFalse(l.isEstimated(0))
        assertFalse(l.isEstimated(2))
        // Failed wins over a size the caller might still return.
        val l2 = WebtoonLayout.build(1, 600, 2000, { IntSize(600, 3000) }, { true })
        assertEquals(1000, l2.height(0))
    }

    @Test
    fun page_at_boundaries() {
        val l = threePageLayout()
        // tops: 0, 1000, 2500; total 4500.
        assertEquals(0, l.pageAt(0.0))
        assertEquals(0, l.pageAt(999.5))
        // y exactly at a top belongs to that page.
        assertEquals(1, l.pageAt(1000.0))
        assertEquals(1, l.pageAt(2499.5))
        assertEquals(2, l.pageAt(2500.0))
        assertEquals(2, l.pageAt(4499.5))
        // Clamped outside the content.
        assertEquals(2, l.pageAt(4500.0))
        assertEquals(2, l.pageAt(100000.0))
        assertEquals(0, l.pageAt(-1.0))
        // The anchor round trip is exact inside the content.
        assertEquals(1234.0, l.yOf(l.anchorAt(1234.0)), 0.0)
        assertEquals(Anchor(1, 0.0), l.anchorAt(1000.0))
    }

    @Test
    fun visible_pages_range() {
        val l = threePageLayout()
        // tops: 0, 1000, 2500; total 4500. Pages touching the viewport edges with zero overlap
        // are not visible (a page ending exactly at y0, or starting exactly at y1).
        assertEquals(0..0, l.visiblePages(0.0, 1000.0))
        assertEquals(0..1, l.visiblePages(0.0, 1000.5))
        assertEquals(1..1, l.visiblePages(1000.0, 2500.0))
        assertEquals(0..2, l.visiblePages(500.0, 3000.0))
        assertEquals(2..2, l.visiblePages(2600.0, 4500.0))
        assertEquals(0..0, l.visiblePages(-2000.0, 500.0))
        assertEquals(IntRange.EMPTY, l.visiblePages(4500.0, 5000.0))
        assertEquals(IntRange.EMPTY, l.visiblePages(-2000.0, 0.0))
        // An empty book has no visible pages.
        assertEquals(
            IntRange.EMPTY,
            WebtoonLayout.build(0, 500, 2000, { null }, { false }).visiblePages(0.0, 1000.0),
        )
    }

    @Test
    fun correction_above_anchor_keeps_content_fixed() {
        // Page 0 unknown (estimated), pages 1..5 known at 1000 each; the estimate is the median.
        val beforeSizes: (Int) -> IntSize? = { if (it == 0) null else IntSize(500, 1000) }
        val before = WebtoonLayout.build(6, 500, 2000, beforeSizes, { false })
        assertTrue(before.isEstimated(0))
        assertEquals(1000, before.height(0))

        val anchor = Anchor(3, 120.0)
        val yBefore = before.yOf(anchor) // 3000 + 120
        assertEquals(3120.0, yBefore, 0.0)

        // Rebuild with page 0 known and taller (500x2000 -> 2000): +1000 above the anchor.
        val afterSizes: (Int) -> IntSize? = { if (it == 0) IntSize(500, 2000) else IntSize(500, 1000) }
        val after = WebtoonLayout.build(6, 500, 2000, afterSizes, { false })
        assertFalse(after.isEstimated(0))
        assertEquals(2000, after.height(0))
        val delta = (after.height(0) - before.height(0)).toDouble()

        // yOf(anchor) moved by exactly the height delta: resetting the scroll to the new
        // yOf(anchor) puts the same content (page 3, offset 120) back at the viewport top.
        val yAfter = after.yOf(anchor) // 4000 + 120
        assertEquals(4120.0, yAfter, 0.0)
        assertEquals(delta, yAfter - yBefore, 0.0)
        assertEquals(1000.0, yAfter - yBefore, 0.0)
        // The anchor itself is untouched and still names the same content.
        assertEquals(anchor, after.anchorAt(yAfter))
    }

    @Test
    fun rescale_anchor_on_rotation() {
        // Portrait 800 wide -> landscape 1200 wide: fit-width heights scale by 1200/800.
        val rotationPages = mapOf(
            0 to IntSize(800, 2000),
            1 to IntSize(800, 3000),
            2 to IntSize(800, 4000),
        )
        val portrait = WebtoonLayout.build(3, 800, 2000, { rotationPages[it] }, { false })
        val a = portrait.anchorAt(portrait.top(2) + 1000.0)
        assertEquals(Anchor(2, 1000.0), a)

        val a2 = rescaleAnchor(a, 800, 1200)
        assertEquals(Anchor(2, 1500.0), a2)

        // The same relative position inside the page in the rotated layout: heights 3000/4500/6000,
        // top(2) = 7500.
        val landscape = WebtoonLayout.build(3, 1200, 2000, { rotationPages[it] }, { false })
        assertEquals(3000, landscape.height(0))
        assertEquals(landscape.top(2) + 1500.0, landscape.yOf(a2), 0.0)
        assertEquals(
            a.offsetPx / portrait.height(2),
            a2.offsetPx / landscape.height(2),
            1e-12,
        )
        // Rotating back restores the original anchor.
        assertEquals(a, rescaleAnchor(a2, 1200, 800))
    }

    @Test
    fun long_book_precision() {
        // 500 pages x 30 000 px: totals beyond everyday Float arithmetic; positions are Double.
        val l = WebtoonLayout.build(500, 800, 2000, { IntSize(800, 30000) }, { false })
        assertEquals(500 * 30000.0, l.totalHeight, 0.0)
        assertEquals(499 * 30000.0, l.top(499), 0.0)
        assertEquals(499, l.pageAt(l.top(499) + 1.0))
        assertEquals(499, l.pageAt(l.totalHeight - 1.0))
        assertEquals(250, l.pageAt(l.top(250)))
        assertEquals(0, l.pageAt(0.0))
    }

    @Test
    fun max_scroll_is_total_minus_zoomed_viewport_clamped_at_zero() {
        val l = threePageLayout(viewH = 2000) // totalHeight 4500
        assertEquals(2500.0, l.maxScroll(1f), 0.0)
        assertEquals(4500.0 - 2000.0 / 3.0, l.maxScroll(3f), 0.0)
        // A book shorter than the zoomed viewport never scrolls: 500 px of content vs a
        // 2000-px viewport showing 666.67 content px at zoom 3.
        val short = WebtoonLayout.build(1, 500, 2000, { IntSize(500, 500) }, { false })
        assertEquals(0.0, short.maxScroll(1f), 0.0)
        assertEquals(0.0, short.maxScroll(3f), 0.0)
        // A book taller than the zoomed viewport scrolls by the formula at any zoom.
        val mid = WebtoonLayout.build(1, 500, 2000, { IntSize(500, 1000) }, { false })
        assertEquals(1000.0 - 2000.0 / 3.0, mid.maxScroll(3f), 0.0)
    }
}
