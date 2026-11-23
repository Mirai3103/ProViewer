package com.proviewer.app.reader

import androidx.compose.ui.unit.IntSize

/**
 * Pure reading-mode logic: layout detection and resolution, and the page-split decision. No Android
 * runtime types and no engine calls; exercised by JVM unit tests.
 */

/** How pages are arranged on screen. */
enum class Layout { Single, TwoPage, Webtoon }

/** The user's layout setting; [Auto] resolves to a [Layout] via [resolveLayout]. */
enum class LayoutChoice { Auto, Single, TwoPage, Webtoon }

/** The user's "split wide pages" setting. */
enum class SplitChoice { Auto, On, Off }

/** How many pages (at most) are probed to decide whether a book is a vertical strip. */
const val DETECT_SAMPLE_PAGES = 20

/** A page counts as strip-like when `height / width` is at least this. */
const val STRIP_RATIO = 2.5f

/** A book is a strip when at least this share of the successfully probed pages are strip-like. */
const val STRIP_SHARE = 0.6f

/**
 * Whether the sampled page sizes describe a vertical-scroll strip (webtoon). A `null` entry is a
 * failed probe and is ignored: the share is taken over the successful probes only. With no
 * successful probe the answer is false.
 */
fun detectStrip(sampled: List<IntSize?>): Boolean {
    val known = sampled.filterNotNull()
    if (known.isEmpty()) return false
    val strips = known.count { it.width > 0 && it.height.toFloat() / it.width >= STRIP_RATIO }
    return strips.toFloat() / known.size >= STRIP_SHARE
}

/**
 * The layout to use. A manual [choice] always wins. [LayoutChoice.Auto] picks Webtoon for a strip
 * (in any orientation), otherwise TwoPage in landscape (`viewW > viewH`) and Single in portrait.
 */
fun resolveLayout(choice: LayoutChoice, isStrip: Boolean, viewW: Int, viewH: Int): Layout = when (choice) {
    LayoutChoice.Single -> Layout.Single
    LayoutChoice.TwoPage -> Layout.TwoPage
    LayoutChoice.Webtoon -> Layout.Webtoon
    LayoutChoice.Auto -> when {
        isStrip -> Layout.Webtoon
        viewW > viewH -> Layout.TwoPage
        else -> Layout.Single
    }
}

/** Whether a page is landscape (a spread): `width > height`. */
fun isWide(size: IntSize): Boolean = size.width > size.height

/** Whether a page is split into two halves under [choice]. An unknown ([size] null) page never splits. */
fun shouldSplit(choice: SplitChoice, size: IntSize?): Boolean {
    if (size == null) return false
    return when (choice) {
        SplitChoice.Auto -> isWide(size)
        SplitChoice.On -> true
        SplitChoice.Off -> false
    }
}
