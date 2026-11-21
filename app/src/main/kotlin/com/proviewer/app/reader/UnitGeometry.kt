package com.proviewer.app.reader

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure layout of a [ReaderUnit] on screen and the unit-aware visible-region mapping. No Android
 * runtime types and no engine calls.
 */

/**
 * Where one part of a unit goes: [src] is the page-pixel rectangle it shows, [dst] the rectangle it
 * occupies in the unit's content (zoom 1, origin at the content's top-left, so it abuts the
 * neighbouring part exactly) and [base] the size of its base bitmap (`dst` size under the memory
 * caps).
 */
data class PartPlacement(val part: UnitPart, val src: IntRect, val dst: IntRect, val base: IntSize)

/** A unit laid out at zoom 1: [content] is its fitted size; [placements] are in reading order. */
data class UnitGeometry(val content: IntSize, val placements: List<PartPlacement>)

/**
 * Lays out [unit] for the viewport and fit [mode]. Returns null if any part's page size is unknown.
 *
 * Each part's source rectangle is [srcRect] of its page. The parts are scaled (aspect kept) to the
 * common height `max(partH)`, the combined size (sum of the scaled widths x common height) is fitted
 * with [fitSize], and the parts are placed edge to edge: first part on the left for LTR, on the right
 * for RTL, each vertically centred in the content. A single-part unit is just that part fitted with
 * [fitSize]. Each part's `base` is its `dst` size under the base-bitmap caps ([capBase]).
 */
fun unitGeometry(
    unit: ReaderUnit,
    sizeOf: (Int) -> IntSize?,
    viewW: Int,
    viewH: Int,
    mode: FitMode,
    direction: Direction,
): UnitGeometry? {
    if (unit.parts.isEmpty()) return null
    val srcs = unit.parts.map { part ->
        val size = sizeOf(part.page) ?: return null
        srcRect(part.part, size)
    }

    // Common height: every part is scaled to the tallest part's height, keeping its aspect.
    val commonH = srcs.maxOf { it.height }
    val scaledW = srcs.map { (it.width.toDouble() * commonH / it.height).roundToInt().coerceAtLeast(1) }
    val totalW = scaledW.sum()
    val content = fitSize(totalW, commonH, viewW, viewH, mode)

    // Cumulative edges in reading order, rounded once so adjacent parts share an exact edge.
    val factor = content.width.toDouble() / totalW
    val edges = IntArray(srcs.size + 1)
    var acc = 0
    for (i in srcs.indices) {
        acc += scaledW[i]
        edges[i + 1] = if (i == srcs.lastIndex) content.width else (acc * factor).roundToInt()
    }

    val placements = unit.parts.mapIndexed { i, part ->
        val w = edges[i + 1] - edges[i]
        val h = content.height // scaled to the common height, which is the content height
        val left = if (direction == Direction.Ltr) edges[i] else content.width - edges[i + 1]
        val top = (content.height - h) / 2
        val dst = IntRect(left, top, left + w, top + h)
        PartPlacement(part, srcs[i], dst, capBase(IntSize(w.coerceAtLeast(1), h.coerceAtLeast(1))))
    }
    return UnitGeometry(content, placements)
}

/**
 * The source rectangle (page pixels) of one part that is visible on screen when the unit's content
 * is drawn at [zoom] with its top-left at ([contentLeft], [contentTop]) in screen pixels. [dst] is
 * the part's rectangle in the content at zoom 1, [src] the page-pixel rectangle it shows and [base]
 * its base bitmap size.
 *
 * Returns null when the base already provides the on-screen image: at fit scale (`zoom <= 1.001`)
 * and the part matches the viewport in the fitted dimension (`dst.width == viewW || dst.height ==
 * viewH`), unless the base has fewer pixels than its laid-out size ([baseCapped] or `base` smaller
 * than `dst`); then the fit view is upscaled and still needs a detail render.
 * Otherwise it returns the screen ∩ transformed-part rectangle mapped back into [src] (floor for
 * the top-left, ceil for the bottom-right, clamped to [src]), with `outW`/`outH` equal to the
 * on-screen size of that intersection. Returns null if the intersection is empty.
 */
fun visiblePartRegion(
    src: IntRect,
    dst: IntRect,
    base: IntSize,
    zoom: Float,
    contentLeft: Float,
    contentTop: Float,
    viewW: Int,
    viewH: Int,
    baseCapped: Boolean,
): PageRegion? {
    val dstW = dst.width
    val dstH = dst.height
    if (dstW <= 0 || dstH <= 0 || src.width <= 0 || src.height <= 0) return null

    val capped = baseCapped || base.width.toLong() * base.height < dstW.toLong() * dstH
    if (!capped && zoom * dstW <= dstW * 1.001f && (dstW == viewW || dstH == viewH)) return null

    // The part's on-screen rectangle.
    val partLeft = contentLeft + zoom * dst.left
    val partTop = contentTop + zoom * dst.top
    val x0 = max(0f, partLeft)
    val y0 = max(0f, partTop)
    val x1 = min(viewW.toFloat(), partLeft + zoom * dstW)
    val y1 = min(viewH.toFloat(), partTop + zoom * dstH)
    if (x1 <= x0 || y1 <= y0) return null

    // Source pixels per on-screen pixel of the (zoomed) part layout.
    val sx = src.width.toDouble() / dstW
    val sy = src.height.toDouble() / dstH
    val px0 = src.left + (x0 - partLeft) / zoom * sx
    val py0 = src.top + (y0 - partTop) / zoom * sy
    val px1 = src.left + (x1 - partLeft) / zoom * sx
    val py1 = src.top + (y1 - partTop) / zoom * sy

    val x = floor(px0).toInt().coerceIn(src.left, src.right)
    val y = floor(py0).toInt().coerceIn(src.top, src.bottom)
    val xEnd = ceil(px1).toInt().coerceIn(src.left, src.right)
    val yEnd = ceil(py1).toInt().coerceIn(src.top, src.bottom)

    return PageRegion(
        x = x,
        y = y,
        w = (xEnd - x).coerceAtLeast(0),
        h = (yEnd - y).coerceAtLeast(0),
        outW = (x1 - x0).roundToInt().coerceAtLeast(1),
        outH = (y1 - y0).roundToInt().coerceAtLeast(1),
    )
}
