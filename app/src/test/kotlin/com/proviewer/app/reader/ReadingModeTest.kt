package com.proviewer.app.reader

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for layout detection and resolution. */
class ReadingModeTest {
    private val tall = IntSize(800, 2400) // ratio 3.0 >= STRIP_RATIO
    private val normal = IntSize(1600, 2400) // ratio 1.5

    private fun sample(tallCount: Int, total: Int): List<IntSize?> =
        List(total) { if (it < tallCount) tall else normal }

    @Test
    fun detect_majority_tall_is_strip() {
        // 13/20 = 0.65 >= STRIP_SHARE (0.6); 11/20 = 0.55 < 0.6.
        assertTrue(detectStrip(sample(13, 20)))
        assertFalse(detectStrip(sample(11, 20)))
    }

    @Test
    fun detect_ignores_failed_probes() {
        // 10 tall + 10 failed probes: the share is over the 10 successful probes only (1.0).
        assertTrue(detectStrip(List<IntSize?>(10) { tall } + List<IntSize?>(10) { null }))
    }

    @Test
    fun detect_all_failed_is_not_strip() {
        assertFalse(detectStrip(List<IntSize?>(20) { null }))
        assertFalse(detectStrip(emptyList()))
    }

    @Test
    fun resolve_auto_rules() {
        // Strip wins in any orientation.
        assertEquals(Layout.Webtoon, resolveLayout(LayoutChoice.Auto, true, 1080, 2378))
        assertEquals(Layout.Webtoon, resolveLayout(LayoutChoice.Auto, true, 2378, 1080))
        // Not a strip: landscape -> TwoPage, portrait -> Single.
        assertEquals(Layout.TwoPage, resolveLayout(LayoutChoice.Auto, false, 2378, 1080))
        assertEquals(Layout.Single, resolveLayout(LayoutChoice.Auto, false, 1080, 2378))
        assertEquals(Layout.Single, resolveLayout(LayoutChoice.Auto, false, 1000, 1000)) // square is not landscape
    }

    @Test
    fun resolve_manual_wins() {
        for (strip in listOf(true, false)) {
            assertEquals(Layout.Single, resolveLayout(LayoutChoice.Single, strip, 2378, 1080))
            assertEquals(Layout.TwoPage, resolveLayout(LayoutChoice.TwoPage, strip, 1080, 2378))
            assertEquals(Layout.Webtoon, resolveLayout(LayoutChoice.Webtoon, strip, 2378, 1080))
        }
    }

    @Test
    fun split_auto_only_wide() {
        val wide = IntSize(3200, 2400)
        assertTrue(isWide(wide))
        assertFalse(isWide(IntSize(1600, 2400)))
        assertFalse(isWide(IntSize(2400, 2400)))
        assertTrue(shouldSplit(SplitChoice.Auto, wide))
        assertFalse(shouldSplit(SplitChoice.Auto, normal))
        assertTrue(shouldSplit(SplitChoice.On, normal))
        assertFalse(shouldSplit(SplitChoice.Off, wide))
        // Unknown size never splits.
        for (c in SplitChoice.values()) assertFalse(shouldSplit(c, null))
    }
}
