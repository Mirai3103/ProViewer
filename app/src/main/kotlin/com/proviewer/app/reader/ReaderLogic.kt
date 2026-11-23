package com.proviewer.app.reader

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
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
 * The on-screen size of a page fitted into the viewport for [mode] (rounded, at least 1), before
 * any memory cap. This is the size the page is laid out and drawn at; [baseSize] may be smaller.
 */
fun fitSize(pageW: Int, pageH: Int, viewW: Int, viewH: Int, mode: FitMode): IntSize {
    require(pageW > 0 && pageH > 0 && viewW > 0 && viewH > 0) { "dimensions must be positive" }

    val fit = when (mode) {
        FitMode.Screen -> min(viewW.toDouble() / pageW, viewH.toDouble() / pageH)
        FitMode.Width -> viewW.toDouble() / pageW
        FitMode.Height -> viewH.toDouble() / pageH
    }
    return IntSize(
        (pageW * fit).roundToInt().coerceAtLeast(1),
        (pageH * fit).roundToInt().coerceAtLeast(1),
    )
}

/**
 * The size of a page's base (fit) render: [fitSize], then scaled down uniformly until both dims
 * are `<= MAX_BASE_DIM` and `w*h*4 <= MAX_BASE_BYTES`, rounded, at least 1. A capped page gets a
 * lower-resolution base that is still drawn (upscaled) at [fitSize]; the detail render supplies
 * sharpness.
 */
fun baseSize(pageW: Int, pageH: Int, viewW: Int, viewH: Int, mode: FitMode): IntSize =
    capBase(fitSize(pageW, pageH, viewW, viewH, mode))

/**
 * Scales an already-fitted [fitted] size down uniformly until it satisfies the base-bitmap caps
 * (`MAX_BASE_DIM`, `MAX_BASE_BYTES`); see [baseSize]. Returns [fitted] unchanged when it is within
 * the caps.
 */
fun capBase(fitted: IntSize): IntSize {
    var w = fitted.width
    var h = fitted.height

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

/** The fraction of the viewport height one Webtoon tap/volume page-scroll moves (spec 5.2). */
const val WEBTOON_PAGE_SCROLL = 0.85f

/**
 * Maps a vertical tap position (Webtoon mode) to an action: top third -> Previous, middle third
 * -> ToggleOverlay, bottom third -> Next. The Previous/Next turns scroll by [WEBTOON_PAGE_SCROLL]
 * of the viewport height (spec 5.2's vertical thirds; horizontal thirds elsewhere).
 */
fun verticalTapAction(y: Float, height: Float): TapAction {
    val third = height / 3f
    return when {
        y < third -> TapAction.Previous
        y < 2 * third -> TapAction.ToggleOverlay
        else -> TapAction.Next
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
 * The page region visible on screen when the base (laid out at baseW x baseH screen pixels for the
 * full page at zoom 1) is drawn at [zoom] with its top-left at ([contentLeft], [contentTop]) in
 * screen pixels.
 *
 * Delegates to [visiblePartRegion] with the whole page as the source and the base layout as the
 * destination.
 *
 * Returns null when the base already provides the on-screen image: at fit scale (`zoom <= 1.001`)
 * and the base matches the viewport in the fitted dimension (`baseW == viewW || baseH == viewH`),
 * unless [baseCapped] says the base bitmap has fewer pixels than its laid-out size (then the fit
 * view is upscaled and still needs a detail render).
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
    baseCapped: Boolean = false,
): PageRegion? = visiblePartRegion(
    src = IntRect(0, 0, pageW, pageH),
    dst = IntRect(0, 0, baseW, baseH),
    base = IntSize(baseW, baseH),
    zoom = zoom,
    contentLeft = contentLeft,
    contentTop = contentTop,
    viewW = viewW,
    viewH = viewH,
    baseCapped = baseCapped,
)
