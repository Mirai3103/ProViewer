package com.proviewer.app.reader

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure reader geometry and gesture logic. Every expected value here is derived
 * from the definition in the brief; nothing is copied from an illustration.
 */
class ReaderLogicTest {

    // --- baseSize -------------------------------------------------------------

    @Test
    fun base_size_screen_fits_page() {
        // 800x1200 page in a 1080x2378 view: scale = min(1080/800, 2378/1200) = 1.35.
        assertEquals(IntSize(1080, 1620), baseSize(800, 1200, 1080, 2378, FitMode.Screen))
    }

    @Test
    fun base_size_caps_tall_and_wide_pages() {
        // A fit-width render of an 800x24000 strip would be 1080x32400 (~140 MB); it must be
        // scaled down uniformly so both dims are <= MAX_BASE_DIM and bytes <= MAX_BASE_BYTES.
        val tall = baseSize(800, 24000, 1080, 2378, FitMode.Width)
        assertTrue("width within caps, was $tall", tall.width in 1..MAX_BASE_DIM)
        assertTrue("height within caps, was $tall", tall.height in 1..MAX_BASE_DIM)
        assertTrue("bytes within cap", tall.width.toLong() * tall.height * 4 <= MAX_BASE_BYTES)
        assertTrue("tall page must be capped below its uncapped 32400 height", tall.height < 32400)

        // A 3200x2400 spread in fit-height on a 2378x1080 viewport: scale = 1080/2400 = 0.45,
        // giving 1440x1080, already inside every cap.
        val wide = baseSize(3200, 2400, 2378, 1080, FitMode.Height)
        assertEquals(IntSize(1440, 1080), wide)
        assertTrue(wide.width in 1..MAX_BASE_DIM && wide.height in 1..MAX_BASE_DIM)
        assertTrue(wide.width.toLong() * wide.height * 4 <= MAX_BASE_BYTES)
    }

    // --- tapAction ------------------------------------------------------------

    @Test
    fun tap_action_covers_all_zones_in_both_directions() {
        val width = 300f
        fun assertZones(dir: Direction, left: TapAction, right: TapAction) {
            assertEquals("left third ($dir)", left, tapAction(0f, width, dir))
            assertEquals("left third ($dir)", left, tapAction(99.9f, width, dir))
            assertEquals("middle ($dir)", TapAction.ToggleOverlay, tapAction(100f, width, dir))
            assertEquals("middle ($dir)", TapAction.ToggleOverlay, tapAction(150f, width, dir))
            assertEquals("right third ($dir)", right, tapAction(200f, width, dir))
            assertEquals("right third ($dir)", right, tapAction(299.9f, width, dir))
        }
        assertZones(Direction.Ltr, TapAction.Previous, TapAction.Next)
        assertZones(Direction.Rtl, TapAction.Next, TapAction.Previous)
    }

    // --- verticalTapAction / WEBTOON_PAGE_SCROLL (Webtoon tap zones) ----------

    @Test
    fun vertical_tap_action_covers_all_zones() {
        val height = 300f
        assertEquals("top third", TapAction.Previous, verticalTapAction(0f, height))
        assertEquals("top third", TapAction.Previous, verticalTapAction(99.9f, height))
        assertEquals("middle third", TapAction.ToggleOverlay, verticalTapAction(100f, height))
        assertEquals("middle third", TapAction.ToggleOverlay, verticalTapAction(150f, height))
        assertEquals("bottom third", TapAction.Next, verticalTapAction(200f, height))
        assertEquals("bottom third", TapAction.Next, verticalTapAction(299.9f, height))
    }

    @Test
    fun webtoon_page_scroll_is_85_percent() {
        assertEquals(0.85f, WEBTOON_PAGE_SCROLL)
    }

    // --- keyAction ------------------------------------------------------------

    @Test
    fun key_action_maps_volume_keys() {
        assertEquals(TapAction.Next, keyAction(25))     // KEYCODE_VOLUME_DOWN
        assertEquals(TapAction.Previous, keyAction(24)) // KEYCODE_VOLUME_UP
        assertNull(keyAction(0))
        assertNull(keyAction(4))                        // KEYCODE_BACK
    }

    // --- windowPages ----------------------------------------------------------

    @Test
    fun window_pages_orders_priority() {
        assertEquals(listOf(5, 6, 7, 8, 4), windowPages(5, 20))
        assertEquals(listOf(5, 4, 3, 2, 6), windowPages(5, 20, forward = false))
        assertEquals(listOf(0, 1), windowPages(0, 2))
    }

    // --- visibleRegion --------------------------------------------------------

    @Test
    fun visible_region_is_null_at_fit_scale() {
        // Base 1080x1620 in a 1080x2378 view, content centred at zoom 1
        // (contentLeft = (1080-1080)/2 = 0, contentTop = (2378-1620)/2 = 379).
        assertNull(visibleRegion(1600, 2400, 1080, 1620, 1f, 0f, 379f, 1080, 2378))
    }

    @Test
    fun visible_region_zoomed_centred() {
        // Page 1600x2400, base 1080x1620, view 1080x2378, zoom 2, content centred:
        //   contentLeft = (1080 - 2*1080)/2 = -540
        //   contentTop  = (2378 - 2*1620)/2 = -431
        // Screen [0,1080]x[0,2378] intersects the full content (2160x3240), so out is 1080x2378.
        // Mapped to page pixels (page px = (screen - content)/zoom * page/base):
        //   x  = floor((0 - (-540))/2 * 1600/1080) = floor(400.0) = 400
        //   xE = ceil((1080 - (-540))/2 * 1600/1080) = ceil(1200.0) = 1200 -> w = 800
        //   y  = floor((0 - (-431))/2 * 2400/1620) = floor(319.259) = 319
        //   yE = ceil((2378 - (-431))/2 * 2400/1620) = ceil(2080.741) = 2081 -> h = 1762
        val region = visibleRegion(1600, 2400, 1080, 1620, 2f, -540f, -431f, 1080, 2378)
        assertEquals(400, region?.x)
        assertEquals(319, region?.y)
        assertEquals(800, region?.w)
        assertEquals(1762, region?.h)
        assertEquals(1080, region?.outW)
        assertEquals(2378, region?.outH)
    }

    @Test
    fun visible_region_is_null_when_content_is_off_screen() {
        // Content panned entirely to the right of the viewport: no intersection.
        assertNull(visibleRegion(1600, 2400, 1080, 1620, 2f, 2000f, 0f, 1080, 2378))
    }
    @Test
    fun fitSize_is_uncapped_fit() {
        assertEquals(IntSize(1080, 32400), fitSize(800, 24000, 1080, 2378, FitMode.Width))
        assertEquals(IntSize(1080, 1620), fitSize(1600, 2400, 1080, 2378, FitMode.Screen))
    }

    @Test
    fun visibleRegion_capped_base_needs_detail_at_fit() {
        // Laid out at the 1080 x 32400 fit size, but the base bitmap was capped: the fit view is
        // upscaled, so a detail of the visible part (the whole viewport) is still needed.
        assertNull(visibleRegion(800, 24000, 1080, 32400, 1f, 0f, 0f, 1080, 2378))
        val region = visibleRegion(800, 24000, 1080, 32400, 1f, 0f, 0f, 1080, 2378, baseCapped = true)
        assertNotNull(region)
        assertEquals(1080, region!!.outW)
        assertEquals(2378, region.outH)
        assertEquals(800, region.w)
    }
}
