package com.proviewer.app.reader

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for unit layout and the unit-aware visible-region mapping. */
class UnitGeometryTest {
    private fun whole(page: Int) = UnitPart(page, Part.Whole)

    @Test
    fun single_unit_fits_part_size() {
        // Right half of a 3200x2400 spread is 1600x2400; Screen-fit into 1000x1000 -> 667x1000.
        val unit = ReaderUnit(listOf(UnitPart(0, Part.RightHalf)))
        val geo = unitGeometry(unit, { IntSize(3200, 2400) }, 1000, 1000, FitMode.Screen, Direction.Ltr)!!
        assertEquals(IntSize(667, 1000), geo.content)
        val p = geo.placements.single()
        assertEquals(IntRect(1600, 0, 3200, 2400), p.src)
        assertEquals(IntRect(0, 0, 667, 1000), p.dst)
        assertEquals(IntSize(667, 1000), p.base)
    }

    @Test
    fun two_page_ltr_places_lower_page_left() {
        // Two 1600x2400 pages: combined 3200x2400, Screen-fit into 1000x1000 -> 1000x750.
        val unit = ReaderUnit(listOf(whole(0), whole(1)))
        val geo = unitGeometry(unit, { IntSize(1600, 2400) }, 1000, 1000, FitMode.Screen, Direction.Ltr)!!
        assertEquals(IntSize(1000, 750), geo.content)
        assertEquals(whole(0), geo.placements[0].part)
        assertEquals(IntRect(0, 0, 500, 750), geo.placements[0].dst)
        assertEquals(whole(1), geo.placements[1].part)
        assertEquals(IntRect(500, 0, 1000, 750), geo.placements[1].dst)
        assertEquals(IntRect(0, 0, 1600, 2400), geo.placements[0].src)
    }

    @Test
    fun two_page_rtl_places_lower_page_right() {
        val unit = ReaderUnit(listOf(whole(0), whole(1)))
        val geo = unitGeometry(unit, { IntSize(1600, 2400) }, 1000, 1000, FitMode.Screen, Direction.Rtl)!!
        assertEquals(IntSize(1000, 750), geo.content)
        assertEquals(whole(0), geo.placements[0].part)
        assertEquals(IntRect(500, 0, 1000, 750), geo.placements[0].dst)
        assertEquals(whole(1), geo.placements[1].part)
        assertEquals(IntRect(0, 0, 500, 750), geo.placements[1].dst)
    }

    @Test
    fun two_page_mixed_heights_common_height_centred() {
        // 1600x2400 and 1000x1000 -> common height 2400: widths 1600 and 2400, combined 4000x2400.
        // Screen-fit into 1000x1000 -> scale 0.25 -> 1000x600; parts 400 and 600 wide.
        val sizes = mapOf(0 to IntSize(1600, 2400), 1 to IntSize(1000, 1000))
        val unit = ReaderUnit(listOf(whole(0), whole(1)))
        val geo = unitGeometry(unit, { sizes[it] }, 1000, 1000, FitMode.Screen, Direction.Ltr)!!
        assertEquals(IntSize(1000, 600), geo.content)
        assertEquals(IntRect(0, 0, 400, 600), geo.placements[0].dst)
        assertEquals(IntRect(400, 0, 1000, 600), geo.placements[1].dst)
        // Every part is drawn at the common height and so is vertically centred (top = 0) in the content.
        for (p in geo.placements) {
            assertEquals(geo.content.height, p.dst.height)
            assertEquals((geo.content.height - p.dst.height) / 2, p.dst.top)
        }
        // Sources are the whole pages, unscaled.
        assertEquals(IntRect(0, 0, 1000, 1000), geo.placements[1].src)
    }

    @Test
    fun two_page_width_fit_and_base_cap() {
        // Fit width of two 1600x2400 pages into 1080 wide: 1080x810 combined, no cap.
        val unit = ReaderUnit(listOf(whole(0), whole(1)))
        val geo = unitGeometry(unit, { IntSize(1600, 2400) }, 1080, 2378, FitMode.Width, Direction.Ltr)!!
        assertEquals(IntSize(1080, 810), geo.content)
        assertEquals(IntSize(540, 810), geo.placements[0].base)

        // A very tall single part is base-capped but laid out at its fit size.
        val tallUnit = ReaderUnit(listOf(whole(0)))
        val tall = unitGeometry(tallUnit, { IntSize(800, 24000) }, 1080, 2378, FitMode.Width, Direction.Ltr)!!
        assertEquals(IntSize(1080, 32400), tall.content)
        assertEquals(IntRect(0, 0, 1080, 32400), tall.placements[0].dst)
        val base = tall.placements[0].base
        assertTrue(base.width in 1..MAX_BASE_DIM && base.height in 1..MAX_BASE_DIM)
        assertTrue(base.width.toLong() * base.height * 4 <= MAX_BASE_BYTES)
        assertEquals(baseSize(800, 24000, 1080, 2378, FitMode.Width), base)
    }

    @Test
    fun unknown_size_gives_null_geometry() {
        val unit = ReaderUnit(listOf(whole(0), whole(1)))
        val geo = unitGeometry(unit, { if (it == 0) IntSize(1600, 2400) else null }, 1000, 1000, FitMode.Screen, Direction.Ltr)
        assertNull(geo)
    }

    @Test
    fun split_half_src_rects() {
        val size = IntSize(3201, 2400)
        assertEquals(IntRect(0, 0, 3201, 2400), srcRect(Part.Whole, size))
        assertEquals(IntRect(0, 0, 1600, 2400), srcRect(Part.LeftHalf, size))
        assertEquals(IntRect(1600, 0, 3201, 2400), srcRect(Part.RightHalf, size))
    }

    @Test
    fun visible_part_region_maps_into_src() {
        // Right half of a 3200x2400 spread: src [1600,3200)x[0,2400), laid out 800x1200 (2 src px
        // per layout px). View 800x1200, zoom 2, content centred: left = (800-1600)/2 = -400,
        // top = (1200-2400)/2 = -600.
        val src = IntRect(1600, 0, 3200, 2400)
        val dst = IntRect(0, 0, 800, 1200)
        val r = visiblePartRegion(src, dst, IntSize(800, 1200), 2f, -400f, -600f, 800, 1200, false)
        assertNotNull(r)
        //   x = 1600 + floor((0+400)/2 * 2) = 2000, xEnd = 1600 + ceil((800+400)/2 * 2) = 2800
        //   y = floor((0+600)/2 * 2) = 600,        yEnd = ceil((1200+600)/2 * 2) = 1800
        assertEquals(PageRegion(2000, 600, 800, 1200, 800, 1200), r)
        assertTrue(r!!.x >= 1600 && r.x + r.w <= 3200)

        // At fit scale with a matching base, nothing extra is needed...
        assertNull(visiblePartRegion(src, dst, IntSize(800, 1200), 1f, 0f, 0f, 800, 1200, false))
        // ...unless the base was capped.
        val capped = visiblePartRegion(src, dst, IntSize(800, 1200), 1f, 0f, 0f, 800, 1200, true)
        assertEquals(PageRegion(1600, 0, 1600, 2400, 800, 1200), capped)
    }

    @Test
    fun visible_part_region_second_part_offset() {
        // Second part of a two-page unit sits at dst x [500,1000): at zoom 1 with the unit content
        // at screen x 0 and a capped base, only that part's columns are returned, in page coords.
        val src = IntRect(0, 0, 1600, 2400)
        val dst = IntRect(500, 0, 1000, 750)
        val r = visiblePartRegion(src, dst, IntSize(250, 375), 1f, 0f, 125f, 1000, 1000, true)
        assertEquals(PageRegion(0, 0, 1600, 2400, 500, 750), r)
        // Panned so the part is entirely off screen -> null.
        assertNull(visiblePartRegion(src, dst, IntSize(500, 750), 2f, -2100f, 0f, 1000, 1000, false))
    }

    @Test
    fun visible_region_unchanged_for_whole_page() {
        // visibleRegion delegates to visiblePartRegion with the whole page as src and (0,0,baseW,baseH)
        // as dst; the values pinned in ReaderLogicTest must hold through the new path.
        val viaWrapper = visibleRegion(1600, 2400, 1080, 1620, 2f, -540f, -431f, 1080, 2378)
        val direct = visiblePartRegion(
            IntRect(0, 0, 1600, 2400), IntRect(0, 0, 1080, 1620), IntSize(1080, 1620),
            2f, -540f, -431f, 1080, 2378, false,
        )
        assertEquals(PageRegion(400, 319, 800, 1762, 1080, 2378), viaWrapper)
        assertEquals(viaWrapper, direct)
    }
}
