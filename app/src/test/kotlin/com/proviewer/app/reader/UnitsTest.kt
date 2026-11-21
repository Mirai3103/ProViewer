package com.proviewer.app.reader

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for unit building and the render-window target order. */
class UnitsTest {
    private val portrait = IntSize(1600, 2400)
    private val wide = IntSize(3200, 2400)

    // 0-based: pages 0-3 portrait, page 4 wide, pages 5-7 portrait.
    private val sizes = mapOf(4 to wide)
    private val sizeOf: (Int) -> IntSize? = { sizes[it] ?: portrait }

    private fun pages(units: List<ReaderUnit>): List<List<Int>> = units.map { u -> u.parts.map { it.page } }

    @Test
    fun two_page_pairing() {
        val units = buildUnits(8, sizeOf, Layout.TwoPage, SplitChoice.Auto, Direction.Ltr, shift = false)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6), listOf(7)), pages(units))
        assertTrue(units.all { u -> u.parts.all { it.part == Part.Whole } })
    }

    @Test
    fun two_page_shift() {
        val units = buildUnits(8, sizeOf, Layout.TwoPage, SplitChoice.Auto, Direction.Ltr, shift = true)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4), listOf(5, 6), listOf(7)), pages(units))
    }

    @Test
    fun two_page_rtl_same_units() {
        // Direction changes placement (UnitGeometry), not which pages form a unit or their order.
        for (shift in listOf(false, true)) {
            val ltr = buildUnits(8, sizeOf, Layout.TwoPage, SplitChoice.Auto, Direction.Ltr, shift)
            val rtl = buildUnits(8, sizeOf, Layout.TwoPage, SplitChoice.Auto, Direction.Rtl, shift)
            assertEquals(ltr, rtl)
        }
    }

    @Test
    fun single_split_ltr_order() {
        val units = buildUnits(6, sizeOf, Layout.Single, SplitChoice.Auto, Direction.Ltr, shift = false)
        assertEquals(7, units.size)
        assertEquals(ReaderUnit(listOf(UnitPart(3, Part.Whole))), units[3])
        assertEquals(ReaderUnit(listOf(UnitPart(4, Part.LeftHalf))), units[4])
        assertEquals(ReaderUnit(listOf(UnitPart(4, Part.RightHalf))), units[5])
        assertEquals(ReaderUnit(listOf(UnitPart(5, Part.Whole))), units[6])
    }

    @Test
    fun single_split_rtl_order() {
        val units = buildUnits(6, sizeOf, Layout.Single, SplitChoice.Auto, Direction.Rtl, shift = false)
        assertEquals(ReaderUnit(listOf(UnitPart(4, Part.RightHalf))), units[4])
        assertEquals(ReaderUnit(listOf(UnitPart(4, Part.LeftHalf))), units[5])
    }

    @Test
    fun split_off_keeps_whole() {
        val units = buildUnits(8, sizeOf, Layout.Single, SplitChoice.Off, Direction.Ltr, shift = false)
        assertEquals(8, units.size)
        assertTrue(units.all { it.parts.size == 1 && it.parts[0].part == Part.Whole })
        assertEquals(List(8) { listOf(it) }, pages(units))
        // Webtoon is one Whole unit per page regardless of the split setting.
        val webtoon = buildUnits(8, sizeOf, Layout.Webtoon, SplitChoice.On, Direction.Ltr, shift = false)
        assertEquals(units, webtoon)
    }

    @Test
    fun unknown_size_counts_as_portrait() {
        val unknown: (Int) -> IntSize? = { null }
        val units = buildUnits(5, unknown, Layout.TwoPage, SplitChoice.Auto, Direction.Ltr, shift = false)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), pages(units))
        // And an unknown size never splits.
        val single = buildUnits(3, unknown, Layout.Single, SplitChoice.On, Direction.Ltr, shift = false)
        assertEquals(3, single.size)
    }

    @Test
    fun unit_index_of_half_and_pair() {
        val split = buildUnits(6, sizeOf, Layout.Single, SplitChoice.Auto, Direction.Ltr, shift = false)
        assertEquals(4, unitIndexOf(split, 4)) // first of the two half units
        assertEquals(6, unitIndexOf(split, 5))
        val pairs = buildUnits(8, sizeOf, Layout.TwoPage, SplitChoice.Auto, Direction.Ltr, shift = false)
        assertEquals(1, unitIndexOf(pairs, 1))
        assertEquals(1, unitIndexOf(pairs, 2))
        assertEquals(5, unitIndexOf(pairs, 7))
    }

    @Test
    fun window_targets_priority() {
        val view = IntSize(1080, 2378)
        val geometryOf: (ReaderUnit) -> UnitGeometry? = {
            unitGeometry(it, sizeOf, view.width, view.height, FitMode.Screen, Direction.Ltr)
        }
        // Units: [0][1,2][3][4][5,6][7]. Current = unit 1 (a pair), ahead 2, behind 1.
        val units = buildUnits(8, sizeOf, Layout.TwoPage, SplitChoice.Auto, Direction.Ltr, shift = false)
        val targets = windowTargets(units, current = 1, ahead = 2, behind = 1, forward = true, geometryOf = geometryOf)
        // Current unit's parts first, then ahead nearest first, then behind.
        assertEquals(listOf(1, 2, 3, 4, 0), targets.map { it.part.page })

        // Backward reading swaps which side is ahead.
        val back = windowTargets(units, current = 1, ahead = 1, behind = 1, forward = false, geometryOf = geometryOf)
        assertEquals(listOf(1, 2, 0, 3), back.map { it.part.page })

        // Each target carries its source rect, base and display size from the geometry.
        val geo = geometryOf(units[1])!!
        assertEquals(geo.placements.map { it.part }, targets.take(2).map { it.part })
        assertEquals(geo.placements.map { it.src }, targets.take(2).map { it.src })
        assertEquals(geo.placements.map { it.base }, targets.take(2).map { it.base })
        assertEquals(
            geo.placements.map { IntSize(it.dst.width, it.dst.height) },
            targets.take(2).map { it.display },
        )
        assertEquals(IntRect(0, 0, 1600, 2400), targets.first().src)

        // A unit whose geometry is unknown contributes nothing.
        val skipping = windowTargets(units, 1, 2, 1, true) { u -> if (u === units[2]) null else geometryOf(u) }
        assertEquals(listOf(1, 2, 4, 0), skipping.map { it.part.page })
    }
}
