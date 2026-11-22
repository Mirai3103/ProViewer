package com.proviewer.app.reader

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset

/**
 * Test/benchmark observability seam for the reader. [onSharpPageDrawn] fires once per drawn part
 * bitmap (never for a placeholder, never per frame while the image is static) and only while the
 * unit showing that bitmap is the current one (E5): a page drawn at rest as a neighbour does not
 * fire, so a later turn to it still produces a fresh event. [onDetailRender] fires on the detail
 * re-render lifecycle; [onDetailImage] reports the detail region currently drawn over a part's
 * base.
 */
interface ReaderProbe {
    fun onSharpPageDrawn(page: Int, drawNanos: Long) {}
    fun onDetailRender(page: Int, event: String) {}

    /**
     * The detail [PageRegion] now drawn over a part's base bitmap, or null when a previously
     * shown detail has been cleared. The rendered bitmap's size is `region.outW x region.outH`
     * (`PageRenderer.render` produces exactly that target), so this pins what the reader
     * displays while zoomed.
     */
    fun onDetailImage(page: Int, region: PageRegion?) {}
}

/** Composition-local probe; a no-op by default so production code never has to null-check. */
val LocalReaderProbe = staticCompositionLocalOf<ReaderProbe> { object : ReaderProbe {} }

/** Zoom automation surface; Task 8 implements it. `ReaderAutomation.zoom` is `null` in this task. */
interface ZoomAutomation {
    suspend fun zoomTo(factor: Float, centroid: Offset, animate: Boolean)
    suspend fun panBy(delta: Offset, animate: Boolean)
    suspend fun reset()
}

/**
 * Scripted control of the reader for the benchmark screen (Task 10) and tests. Delivered through
 * `ReaderScreen(onAutomation)`.
 */
interface ReaderAutomation {
    suspend fun turnTo(page: Int, animate: Boolean)
    suspend fun awaitIdle()
    val zoom: ZoomAutomation?
}
