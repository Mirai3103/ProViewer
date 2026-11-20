package com.proviewer.app.reader

import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure reader geometry and gesture logic. No Android types, no coroutines, no engine calls: this
 * file is exercised by JVM unit tests.
 */

/** How a page is fitted into the viewport. */
enum class FitMode { Screen, Width, Height }

/** Reading direction; mirrored tap-zone semantics and page-order direction. */
enum class Direction { Ltr, Rtl }

/**
 * A rectangle in **page** pixel coordinates (`x,y,w,h`) that must be rendered to exactly
 * `outW x outH` RGBA pixels on screen.
 */
data class PageRegion(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val outW: Int,
    val outH: Int,
)

/** Upper bound on either base-bitmap dimension. */
const val MAX_BASE_DIM = 4096

/** Upper bound on a base bitmap's RGBA byte size (32 MiB). */
const val MAX_BASE_BYTES = 32L * 1024 * 1024

/**
 * The size of a page's base (fit) render for [mode], then scaled down uniformly until both dims
 * are `<= MAX_BASE_DIM` and `w*h*4 <= MAX_BASE_BYTES`, rounded, at least 1. A capped page gets a
 * lower-resolution base; the detail render supplies sharpness while zoomed.
 */
fun baseSize(pageW: Int, pageH: Int, viewW: Int, viewH: Int, mode: FitMode): IntSize {
    require(pageW > 0 && pageH > 0 && viewW > 0 && viewH > 0) { "dimensions must be positive" }

    val fit = when (mode) {
        FitMode.Screen -> min(viewW.toDouble() / pageW, viewH.toDouble() / pageH)
        FitMode.Width -> viewW.toDouble() / pageW
        FitMode.Height -> viewH.toDouble() / pageH
    }

    var w = (pageW * fit).roundToInt().coerceAtLeast(1)
    var h = (pageH * fit).roundToInt().coerceAtLeast(1)

    // The same uniform factor applies to both dims.
    var scale = 1.0
    if (w > MAX_BASE_DIM) scale = min(scale, MAX_BASE_DIM.toDouble() / w)
    if (h > MAX_BASE_DIM) scale = min(scale, MAX_BASE_DIM.toDouble() / h)
    val maxPixels = MAX_BASE_BYTES / 4
    if (w.toLong() * h > maxPixels) scale = min(scale, sqrt(maxPixels.toDouble() / (w.toLong() * h)))
    if (scale < 1.0) {
        w = (w * scale).roundToInt().coerceAtLeast(1)
        h = (h * scale).roundToInt().coerceAtLeast(1)
        // Rounding can nudge a dimension back over a cap; trim the longer side once.
        while (w > MAX_BASE_DIM || h > MAX_BASE_DIM || w.toLong() * h * 4 > MAX_BASE_BYTES) {
            if (w >= h && w > 1) w -= 1 else if (h > 1) h -= 1 else break
        }
    }
    return IntSize(w, h)
}

/** Outcome of a tap or a volume-key press. */
enum class TapAction { Previous, Next, ToggleOverlay }

/**
 * Maps a horizontal tap position to an action. Left third -> Previous, right third -> Next, middle
 * -> ToggleOverlay; RTL mirrors the left/right meaning.
 */
fun tapAction(x: Float, width: Float, dir: Direction): TapAction {
    val third = width / 3f
    return when {
        x < third -> if (dir == Direction.Ltr) TapAction.Previous else TapAction.Next
        x < 2 * third -> TapAction.ToggleOverlay
        else -> if (dir == Direction.Ltr) TapAction.Next else TapAction.Previous
    }
}

/**
 * Maps a key code to a page-turn action: volume down -> Next, volume up -> Previous, anything else
 * -> null. Raw Android key codes are used so this file stays free of `android.view` imports.
 */
fun keyAction(keyCode: Int): TapAction? = when (keyCode) {
    KEYCODE_VOLUME_DOWN -> TapAction.Next
    KEYCODE_VOLUME_UP -> TapAction.Previous
    else -> null
}

private const val KEYCODE_VOLUME_UP = 24
private const val KEYCODE_VOLUME_DOWN = 25

/**
 * The render window around [current] in priority order: current, then ahead pages nearest first,
 * then behind pages nearest first, all in range. `forward = false` swaps which side counts as ahead.
 */
fun windowPages(
    current: Int,
    count: Int,
    ahead: Int = 3,
    behind: Int = 1,
    forward: Boolean = true,
): List<Int> {
    val out = ArrayList<Int>(1 + ahead + behind)
    fun add(p: Int) {
        if (p in 0 until count) out.add(p)
    }
    add(current)
    if (forward) {
        for (i in 1..ahead) add(current + i)
        for (i in 1..behind) add(current - i)
    } else {
        for (i in 1..ahead) add(current - i)
        for (i in 1..behind) add(current + i)
    }
    return out
}

/**
 * The page region visible on screen when the base render (baseW x baseH for the full page) is drawn
 * at [zoom] with its top-left at ([contentLeft], [contentTop]) in screen pixels.
 *
 * Returns null when the base already provides the on-screen image: at fit scale (`zoom <= 1.001`)
 * and the base matches the viewport in the fitted dimension (`baseW == viewW || baseH == viewH`).
 * Otherwise it returns the screen ∩ transformed-content rectangle mapped back to page pixels
 * (floor for the top-left, ceil for the bottom-right, clamped to the page), with `outW`/`outH`
 * equal to the on-screen size of that intersection. Returns null if the intersection is empty.
 */
fun visibleRegion(
    pageW: Int,
    pageH: Int,
    baseW: Int,
    baseH: Int,
    zoom: Float,
    contentLeft: Float,
    contentTop: Float,
    viewW: Int,
    viewH: Int,
): PageRegion? {
    if (zoom * baseW <= baseW * 1.001f && (baseW == viewW || baseH == viewH)) return null

    val x0 = max(0f, contentLeft)
    val y0 = max(0f, contentTop)
    val x1 = min(viewW.toFloat(), contentLeft + zoom * baseW)
    val y1 = min(viewH.toFloat(), contentTop + zoom * baseH)
    if (x1 <= x0 || y1 <= y0) return null

    // Page pixels per on-screen pixel of the (zoomed) base.
    val sx = pageW.toDouble() / baseW
    val sy = pageH.toDouble() / baseH
    val px0 = (x0 - contentLeft) / zoom * sx
    val py0 = (y0 - contentTop) / zoom * sy
    val px1 = (x1 - contentLeft) / zoom * sx
    val py1 = (y1 - contentTop) / zoom * sy

    val x = floor(px0).toInt().coerceIn(0, pageW)
    val y = floor(py0).toInt().coerceIn(0, pageH)
    val xEnd = ceil(px1).toInt().coerceIn(0, pageW)
    val yEnd = ceil(py1).toInt().coerceIn(0, pageH)

    return PageRegion(
        x = x,
        y = y,
        w = (xEnd - x).coerceAtLeast(0),
        h = (yEnd - y).coerceAtLeast(0),
        outW = (x1 - x0).roundToInt().coerceAtLeast(1),
        outH = (y1 - y0).roundToInt().coerceAtLeast(1),
    )
}
