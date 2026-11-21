package com.proviewer.app.reader

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

/**
 * Pure reader units: what one "page turn" shows. A unit is one whole page, one half of a split
 * spread, or two pages side by side. No Android runtime types and no engine calls.
 */

/** Which part of a page a [UnitPart] shows. */
enum class Part { Whole, LeftHalf, RightHalf }

/** One page (or half page) shown by a unit. */
data class UnitPart(val page: Int, val part: Part)

/** What is on screen for one page turn: 1..2 parts, in reading order. */
data class ReaderUnit(val parts: List<UnitPart>)

/**
 * A part to keep rendered: [src] is the page-pixel rectangle, [base] the bitmap size to render it
 * at (memory-capped) and [display] the size it is laid out at on screen.
 */
data class PartTarget(val part: UnitPart, val src: IntRect, val base: IntSize, val display: IntSize)

/**
 * Builds the reader units for [count] pages. [sizeOf] returns a page's natural size, or null when
 * not yet known (unknown counts as portrait and never splits).
 *
 * - Webtoon: one Whole unit per page (the vertical scroller does not use units, but this keeps a
 *   single code path).
 * - Single: one Whole unit per page, or two half units when [shouldSplit]; the first unit in
 *   reading order is the left half for LTR and the right half for RTL.
 * - TwoPage: page 0 is alone unless [shift]; wide pages are alone; otherwise consecutive portrait
 *   pages pair; pairing restarts after a wide page; a trailing unpaired page is alone. [direction]
 *   does not change which pages pair, only where they are placed (see [unitGeometry]).
 */
fun buildUnits(
    count: Int,
    sizeOf: (Int) -> IntSize?,
    layout: Layout,
    split: SplitChoice,
    direction: Direction,
    shift: Boolean,
): List<ReaderUnit> {
    val units = ArrayList<ReaderUnit>(count)
    fun whole(page: Int) = ReaderUnit(listOf(UnitPart(page, Part.Whole)))

    when (layout) {
        Layout.Webtoon -> for (p in 0 until count) units.add(whole(p))
        Layout.Single -> for (p in 0 until count) {
            if (shouldSplit(split, sizeOf(p))) {
                val (first, second) = if (direction == Direction.Ltr) {
                    Part.LeftHalf to Part.RightHalf
                } else {
                    Part.RightHalf to Part.LeftHalf
                }
                units.add(ReaderUnit(listOf(UnitPart(p, first))))
                units.add(ReaderUnit(listOf(UnitPart(p, second))))
            } else {
                units.add(whole(p))
            }
        }
        Layout.TwoPage -> {
            fun wide(p: Int): Boolean = sizeOf(p)?.let(::isWide) ?: false
            var p = 0
            if (!shift && count > 0) {
                units.add(whole(0))
                p = 1
            }
            while (p < count) {
                if (!wide(p) && p + 1 < count && !wide(p + 1)) {
                    units.add(ReaderUnit(listOf(UnitPart(p, Part.Whole), UnitPart(p + 1, Part.Whole))))
                    p += 2
                } else {
                    units.add(whole(p))
                    p += 1
                }
            }
        }
    }
    return units
}

/** The index of the first unit that contains [page], or -1 if none does. */
fun unitIndexOf(units: List<ReaderUnit>, page: Int): Int =
    units.indexOfFirst { u -> u.parts.any { it.page == page } }

/** The page-pixel rectangle of [part] in a page of [size]: halves are `[0, w/2)` and `[w/2, w)`. */
fun srcRect(part: Part, size: IntSize): IntRect = when (part) {
    Part.Whole -> IntRect(0, 0, size.width, size.height)
    Part.LeftHalf -> IntRect(0, 0, size.width / 2, size.height)
    Part.RightHalf -> IntRect(size.width / 2, 0, size.width, size.height)
}

/**
 * The parts to keep rendered around unit [current], in priority order: the current unit's parts
 * first, then [ahead] units nearest first, then [behind] units nearest first (swapped when
 * [forward] is false). A unit whose geometry is unknown ([geometryOf] returns null) contributes
 * nothing.
 */
fun windowTargets(
    units: List<ReaderUnit>,
    current: Int,
    ahead: Int,
    behind: Int,
    forward: Boolean,
    geometryOf: (ReaderUnit) -> UnitGeometry?,
): List<PartTarget> {
    val out = ArrayList<PartTarget>()
    for (index in windowPages(current, units.size, ahead, behind, forward)) {
        val geometry = geometryOf(units[index]) ?: continue
        for (placement in geometry.placements) {
            out.add(
                PartTarget(
                    part = placement.part,
                    src = placement.src,
                    base = placement.base,
                    display = IntSize(placement.dst.width, placement.dst.height),
                ),
            )
        }
    }
    return out
}
