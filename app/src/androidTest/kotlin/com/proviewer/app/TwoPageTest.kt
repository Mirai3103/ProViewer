package com.proviewer.app

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proviewer.app.bench.CorpusGenerator
import com.proviewer.app.engine.NativeBook
import com.proviewer.app.open.OpenResult
import com.proviewer.app.reader.Direction
import com.proviewer.app.reader.FitMode
import com.proviewer.app.reader.Layout
import com.proviewer.app.reader.LayoutChoice
import com.proviewer.app.reader.LocalReaderProbe
import com.proviewer.app.reader.PageRegion
import com.proviewer.app.reader.PageSlot
import com.proviewer.app.reader.Part
import com.proviewer.app.reader.ReaderAutomation
import com.proviewer.app.reader.ReaderProbe
import com.proviewer.app.reader.ReaderScreen
import com.proviewer.app.reader.ReaderViewModel
import com.proviewer.app.reader.SplitChoice
import com.proviewer.app.reader.UnitPart
import com.proviewer.app.reader.ZoomAutomation
import com.proviewer.app.reader.capBase
import com.proviewer.app.reader.fitSize
import com.proviewer.app.reader.unitGeometry
import com.proviewer.app.reader.visiblePartRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The unit pager (Task 5): two-page pairing, spread splitting, per-part zoom details and the
 * current-only sharp-draw probe, end to end. The reader is composed directly so the test can
 * provide [LocalReaderProbe] and capture the [ReaderAutomation]; layouts are set programmatically
 * (Task 6 adds the overlay controls).
 */
@RunWith(AndroidJUnit4::class)
class TwoPageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Automation calls are posted to the main thread; failures surface through [scopeErrors]. */
    private val mainScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> scopeErrors += e },
    )
    private val scopeErrors = mutableListOf<Throwable>()

    private val automation = CompletableDeferred<ReaderAutomation>()
    private var vm: ReaderViewModel? = null

    /** Records probe events; callbacks may arrive from render threads, so access is synchronized. */
    private class RecordingProbe : ReaderProbe {
        private val lock = Any()
        private val sharpPages = mutableListOf<Int>()
        private val detailImages = mutableListOf<Pair<Int, PageRegion?>>()

        override fun onSharpPageDrawn(page: Int, drawNanos: Long) = synchronized(lock) {
            sharpPages += page
        }

        override fun onDetailImage(page: Int, region: PageRegion?) = synchronized(lock) {
            detailImages += page to region
        }

        fun sharpPages(): List<Int> = synchronized(lock) { sharpPages.toList() }

        fun images(): List<Pair<Int, PageRegion?>> = synchronized(lock) { detailImages.toList() }

        /** How many non-null detail images were reported for [page]. */
        fun detailCount(page: Int): Int = synchronized(lock) {
            detailImages.count { it.first == page && it.second != null }
        }
    }

    // --- helpers ---------------------------------------------------------------

    private fun corpusFile(name: String): File {
        val dir = File(context.cacheDir, "two-page-corpus").apply { mkdirs() }
        return runBlocking { CorpusGenerator.ensure(dir, 0.25f, listOf(name)) }.single()
    }

    private fun launchReader(file: File, probe: RecordingProbe): ReaderViewModel {
        val app = context.applicationContext as ProViewerApp
        val book = NativeBook(app.engine.openPath(file.absolutePath), copiedFile = null)
        val readerVm = ReaderViewModel()
        composeRule.setContent {
            CompositionLocalProvider(LocalReaderProbe provides probe) {
                ReaderScreen(
                    vm = readerVm,
                    onBack = {},
                    onAutomation = { automation.complete(it) },
                )
            }
        }
        composeRule.runOnIdle { readerVm.attach(OpenResult.Opened(book, file.name)) }
        vm = readerVm
        return readerVm
    }

    private fun readerAutomation(): ReaderAutomation = runBlocking { automation.await() }

    private fun zoomAutomation(): ZoomAutomation {
        assertNoScopeErrors()
        return requireNotNull(readerAutomation().zoom) {
            "ZoomAutomation must be exposed through ReaderAutomation.zoom"
        }
    }

    private fun assertNoScopeErrors() {
        val errors = scopeErrors.toList()
        if (errors.isNotEmpty()) throw AssertionError("automation scope failed", errors.first())
    }

    /**
     * Runs [block] on the main scope, tolerating the reader's own animation driver: a
     * `goTo` triggered `animateScrollToPage` can interrupt the scripted pager mutation with a
     * `CancellationException` (both target the same unit), which must not fail the test.
     */
    private fun postAutomation(block: suspend () -> Unit) {
        mainScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
            }
        }
    }

    /**
     * Waits until the reader has attached and the viewport has stopped changing (the immersive-bars
     * transition resizes the window shortly after composition). About settled state, never timing.
     */
    private fun awaitViewportSettled(readerVm: ReaderViewModel): IntSize {
        composeRule.waitUntil(60_000) { readerVm.state != null && readerVm.viewport != null }
        var last: IntSize? = null
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            val view = readerVm.viewport!!
            if (view == last) return view
            last = view
            Thread.sleep(200)
        }
        throw AssertionError("viewport never settled; last=$last")
    }

    /** Waits until every listed [UnitPart] has a Ready window slot. */
    private fun awaitSlotsReady(readerVm: ReaderViewModel, parts: List<UnitPart>, timeout: Long = 30_000) {
        composeRule.waitUntil(timeout) {
            parts.all { readerVm.window?.slots?.get(it) is PageSlot.Ready }
        }
    }

    /** The prober/renderer combined page-size lookup the VM also builds its units from. */
    private fun sizeOf(readerVm: ReaderViewModel): (Int) -> IntSize? {
        val sizes = readerVm.sizes!!
        val renderer = readerVm.renderer!!
        return { page -> sizes.known(page) ?: renderer.knownPageSize(page) }
    }

    /** The page sizes of the unit's parts, as whole-page lists ("[0][1,2][3]..."). */
    private fun unitPages(readerVm: ReaderViewModel): List<List<Int>> =
        readerVm.units.map { unit -> unit.parts.map { it.page } }

    /**
     * The on-screen rectangle of the part showing [page] of unit [unitIndex]: the pure unit
     * geometry placed with the same alignment the reader draws it with (FitMode.Screen, centred).
     */
    private fun partScreenRect(readerVm: ReaderViewModel, unitIndex: Int, page: Int): android.graphics.Rect {
        val view = readerVm.viewport!!
        val direction = readerVm.state!!.direction
        val geometry = checkNotNull(
            unitGeometry(
                readerVm.units[unitIndex], sizeOf(readerVm),
                view.width, view.height, FitMode.Screen, direction,
            ),
        ) { "unit $unitIndex has no geometry; sizes=${sizeOf(readerVm).let { f -> (0 until readerVm.state!!.count).map(f) }}" }
        val placement = geometry.placements.first { it.part.page == page }
        val topLeft = Alignment.Center.align(geometry.content, IntSize(view.width, view.height), LayoutDirection.Ltr)
        return android.graphics.Rect(
            topLeft.x + placement.dst.left,
            topLeft.y + placement.dst.top,
            topLeft.x + placement.dst.right,
            topLeft.y + placement.dst.bottom,
        )
    }

    private fun baseBitmap(readerVm: ReaderViewModel, part: UnitPart): Bitmap {
        val slot = readerVm.window?.slots?.get(part) as? PageSlot.Ready
        return checkNotNull(slot?.image?.bitmap).copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun readBlock(image: ImageBitmap, x: Int, y: Int): IntArray {
        val out = IntArray(BLOCK * BLOCK)
        image.readPixels(out, startX = x, startY = y, width = BLOCK, height = BLOCK)
        return out
    }

    private fun baseBlock(base: Bitmap, x: Int, y: Int): IntArray {
        val out = IntArray(BLOCK * BLOCK)
        base.getPixels(out, 0, BLOCK, x, y, BLOCK, BLOCK)
        return out
    }

    /** Per-channel comparison of two packed ARGB blocks, tolerance [TOLERANCE]. */
    private fun assertBlocksMatch(what: String, screen: IntArray, base: IntArray) {
        for (i in screen.indices) {
            val a = screen[i]
            val b = base[i]
            val diff = maxOf(
                kotlin.math.abs(AndroidColor.red(a) - AndroidColor.red(b)),
                kotlin.math.abs(AndroidColor.green(a) - AndroidColor.green(b)),
                kotlin.math.abs(AndroidColor.blue(a) - AndroidColor.blue(b)),
            )
            if (diff > TOLERANCE) {
                fail(
                    "$what: pixel ${i % BLOCK},${i / BLOCK} differs by $diff " +
                        "(screen=${Integer.toHexString(a)} base=${Integer.toHexString(b)})",
                )
            }
        }
    }

    private fun assertBlocksDiffer(what: String, screen: IntArray, base: IntArray) {
        val maxDiff = screen.indices.maxOf { i ->
            val a = screen[i]
            val b = base[i]
            maxOf(
                kotlin.math.abs(AndroidColor.red(a) - AndroidColor.red(b)),
                kotlin.math.abs(AndroidColor.green(a) - AndroidColor.green(b)),
                kotlin.math.abs(AndroidColor.blue(a) - AndroidColor.blue(b)),
            )
        }
        assertTrue("$what: blocks must differ (max channel diff $maxDiff)", maxDiff > MISMATCH_DIFF)
    }

    @After
    fun tearDown() {
        mainScope.cancel(CancellationException("test done"))
        // Drop the reader from composition before releasing the VM: the release recycles the
        // base bitmaps, which must not happen while a frame could still draw them.
        runCatching {
            composeRule.setContent { Box(Modifier.fillMaxSize()) }
            composeRule.waitForIdle()
        }
        vm?.release()
        vm = null
    }

    // --- tests -----------------------------------------------------------------

    @Test
    fun two_page_units_follow_pairing() {
        val readerVm = launchReader(corpusFile("two-page.cbz"), RecordingProbe())
        awaitViewportSettled(readerVm)

        // two-page.cbz: portrait 1-4, spread 5, portrait 6-8. Cover alone, 2-3 pair, 4 alone
        // (its next page is a spread), the spread alone, pairing restarts with 6-7, 8 alone.
        composeRule.runOnIdle { readerVm.setLayoutChoice(LayoutChoice.TwoPage) }
        val expected = listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6), listOf(7))
        composeRule.waitUntil(30_000) { unitPages(readerVm) == expected }
        assertEquals(expected, unitPages(readerVm))

        // next() walks one unit at a time and the label page is the unit's first page.
        composeRule.runOnIdle { readerVm.next() }
        composeRule.waitUntil(10_000) { readerVm.state?.unit == 1 }
        assertEquals(1, readerVm.state!!.unit)
        assertEquals("the label page is the first page of unit [1,2]", 1, readerVm.state!!.page)

        repeat(4) { composeRule.runOnIdle { readerVm.next() } }
        composeRule.waitUntil(10_000) { readerVm.state?.unit == 5 }
        assertEquals(7, readerVm.state!!.page)

        composeRule.runOnIdle { readerVm.next() } // at the last unit: stays
        composeRule.waitForIdle()
        assertEquals(5, readerVm.state!!.unit)

        composeRule.runOnIdle { readerVm.previous() }
        composeRule.waitUntil(10_000) { readerVm.state?.unit == 4 }
        assertEquals("previous lands on the first page of unit [5,6]", 5, readerVm.state!!.page)
    }

    @Test
    fun two_page_shift() {
        val readerVm = launchReader(corpusFile("two-page.cbz"), RecordingProbe())
        awaitViewportSettled(readerVm)

        composeRule.runOnIdle {
            readerVm.setLayoutChoice(LayoutChoice.TwoPage)
            readerVm.goTo(5)
        }
        composeRule.waitUntil(30_000) { readerVm.state?.unit == 4 && readerVm.state?.page == 5 }

        // Shift pairs the cover with page 2: [1-2][3-4][5][6-7][8].
        composeRule.runOnIdle { readerVm.setShift(true) }
        val shifted = listOf(listOf(0, 1), listOf(2, 3), listOf(4), listOf(5, 6), listOf(7))
        composeRule.waitUntil(30_000) { unitPages(readerVm) == shifted }
        assertEquals("the same book page is kept", 5, readerVm.state!!.page)
        assertEquals("[5,6] is unit index 3 after the shift", 3, readerVm.state!!.unit)

        // Shifting back restores the normal pairing and the unit containing page 6.
        composeRule.runOnIdle { readerVm.setShift(false) }
        val normal = listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6), listOf(7))
        composeRule.waitUntil(30_000) { unitPages(readerVm) == normal }
        assertEquals(5, readerVm.state!!.page)
        assertEquals(4, readerVm.state!!.unit)
    }

    @Test
    fun two_page_rtl_draws_lower_page_right() {
        val readerVm = launchReader(corpusFile("two-page.cbz"), RecordingProbe())
        awaitViewportSettled(readerVm)

        composeRule.runOnIdle {
            readerVm.setLayoutChoice(LayoutChoice.TwoPage)
            readerVm.setDirection(Direction.Rtl)
        }
        composeRule.waitUntil(30_000) {
            readerVm.state?.layout == Layout.TwoPage && readerVm.state?.direction == Direction.Rtl
        }

        // Unit 1 shows pages 1 (lower) and 2; in RTL the lower page goes on the right.
        postAutomation { readerAutomation().turnTo(1, animate = false) }
        composeRule.waitUntil(30_000) { readerVm.state?.unit == 1 }
        awaitSlotsReady(readerVm, listOf(UnitPart(1, Part.Whole), UnitPart(2, Part.Whole)))
        composeRule.waitForIdle()
        Thread.sleep(300) // let the settled frame reach the screen
        composeRule.waitForIdle()

        val base = baseBitmap(readerVm, UnitPart(1, Part.Whole))
        val right = partScreenRect(readerVm, unitIndex = 1, page = 1)
        val left = partScreenRect(readerVm, unitIndex = 1, page = 2)
        val screen = composeRule.onRoot().captureToImage()

        // An interior block of page 1's base appears at its placement on the RIGHT half...
        val bx = base.width / 2
        val by = base.height / 2
        assertBlocksMatch(
            "page 1's base block must appear in the right half (RTL)",
            readBlock(screen, right.left + bx, right.top + by),
            baseBlock(base, bx, by),
        )
        // ...and the left half shows page 2, so page 1's base must not match there.
        assertBlocksDiffer(
            "the left half must not show page 1's content",
            readBlock(screen, left.left + bx, left.top + by),
            baseBlock(base, bx, by),
        )
    }

    @Test
    fun single_split_halves_in_order() {
        val readerVm = launchReader(corpusFile("double-spread.cbz"), RecordingProbe())
        val view = awaitViewportSettled(readerVm)

        composeRule.runOnIdle {
            readerVm.setLayoutChoice(LayoutChoice.Single)
            readerVm.setSplit(SplitChoice.On)
            readerVm.goTo(1) // the spread page
        }
        composeRule.waitUntil(30_000) {
            val unit = readerVm.units.getOrNull(readerVm.state?.unit ?: -1)
            unit?.parts == listOf(UnitPart(1, Part.LeftHalf))
        }
        awaitSlotsReady(readerVm, listOf(UnitPart(1, Part.LeftHalf)))
        composeRule.waitForIdle()

        // The base bitmap is the half's fit size: the left half (400x600 at scale 0.25) fitted
        // into the viewport, memory caps aside.
        val halfSrc = 400 to 600
        val expectedBase = capBase(fitSize(halfSrc.first, halfSrc.second, view.width, view.height, FitMode.Screen))
        val leftSlot = readerVm.window?.slots?.get(UnitPart(1, Part.LeftHalf)) as PageSlot.Ready
        assertEquals("the half's base bitmap is the half's fit", expectedBase, leftSlot.size)

        val leftBase = baseBitmap(readerVm, UnitPart(1, Part.LeftHalf))
        var screen = composeRule.onRoot().captureToImage()
        val bx = leftBase.width / 2
        val by = leftBase.height / 2
        // The content is centred, so its centre block sits at the viewport centre (LTR: left half).
        assertBlocksMatch(
            "LTR: the screen centre must show the LEFT half",
            readBlock(screen, view.width / 2, view.height / 2),
            baseBlock(leftBase, bx, by),
        )

        // next() moves to the right half of the same book page.
        composeRule.runOnIdle { readerVm.next() }
        composeRule.waitUntil(10_000) {
            readerVm.units.getOrNull(readerVm.state?.unit ?: -1)?.parts == listOf(UnitPart(1, Part.RightHalf))
        }
        assertEquals("both halves show book page 1", 1, readerVm.state!!.page)
        awaitSlotsReady(readerVm, listOf(UnitPart(1, Part.RightHalf)))
        composeRule.waitForIdle()
        val rightBase = baseBitmap(readerVm, UnitPart(1, Part.RightHalf))
        screen = composeRule.onRoot().captureToImage()
        assertBlocksMatch(
            "LTR: after next() the screen centre must show the RIGHT half",
            readBlock(screen, view.width / 2, view.height / 2),
            baseBlock(rightBase, rightBase.width / 2, rightBase.height / 2),
        )

        // RTL reverses the half order: page 1 starts with its right half.
        composeRule.runOnIdle { readerVm.setDirection(Direction.Rtl) }
        composeRule.waitUntil(30_000) {
            readerVm.units.getOrNull(readerVm.state?.unit ?: -1)?.parts == listOf(UnitPart(1, Part.RightHalf))
        }
        awaitSlotsReady(readerVm, listOf(UnitPart(1, Part.RightHalf)))
        composeRule.waitForIdle()
        Thread.sleep(300)
        composeRule.waitForIdle()
        screen = composeRule.onRoot().captureToImage()
        assertBlocksMatch(
            "RTL: the screen centre must show the RIGHT half first",
            readBlock(screen, view.width / 2, view.height / 2),
            baseBlock(rightBase, rightBase.width / 2, rightBase.height / 2),
        )

        composeRule.runOnIdle { readerVm.next() }
        composeRule.waitUntil(10_000) {
            readerVm.units.getOrNull(readerVm.state?.unit ?: -1)?.parts == listOf(UnitPart(1, Part.LeftHalf))
        }
        assertEquals(1, readerVm.state!!.page)
    }

    @Test
    fun zoom_two_page_renders_detail_per_visible_part() {
        val probe = RecordingProbe()
        val readerVm = launchReader(corpusFile("two-page.cbz"), probe)
        awaitViewportSettled(readerVm)

        composeRule.runOnIdle { readerVm.setLayoutChoice(LayoutChoice.TwoPage) }
        postAutomation { readerAutomation().turnTo(5, animate = false) }
        composeRule.waitUntil(30_000) { readerVm.state?.unit == 4 }
        awaitSlotsReady(readerVm, listOf(UnitPart(5, Part.Whole), UnitPart(6, Part.Whole)))
        composeRule.waitForIdle()

        // Zoom 2x at the viewport centre: unit [5,6] is width-constrained, so at 2x the visible
        // area intersects both parts and each gets exactly one detail render.
        val view = readerVm.viewport!!
        val zoom = zoomAutomation()
        postAutomation { zoom.zoomTo(2f, Offset(view.width / 2f, view.height / 2f), animate = false) }
        composeRule.waitUntil(20_000) { probe.detailCount(5) >= 1 && probe.detailCount(6) >= 1 }

        assertNoScopeErrors()
        assertEquals("exactly one detail for part page 5", 1, probe.detailCount(5))
        assertEquals("exactly one detail for part page 6", 1, probe.detailCount(6))

        // Each detail must be exactly the pure model's visible region for the settled
        // transformation (the content was zoomed about the viewport centre).
        val geometry = checkNotNull(
            unitGeometry(readerVm.units[4], sizeOf(readerVm), view.width, view.height, FitMode.Screen, Direction.Ltr),
        )
        val contentLeft = (view.width - 2f * geometry.content.width) / 2f
        val contentTop = (view.height - 2f * geometry.content.height) / 2f
        for (placement in geometry.placements) {
            val expected = checkNotNull(
                visiblePartRegion(
                    src = placement.src,
                    dst = placement.dst,
                    base = placement.base,
                    zoom = 2f,
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    viewW = view.width,
                    viewH = view.height,
                    baseCapped = false,
                ),
            ) { "part ${placement.part} must need a detail render at 2x" }
            val shown = probe.images().first { it.first == placement.part.page && it.second != null }.second!!
            assertEquals("part ${placement.part.page}'s detail region", expected, shown)
        }
    }

    @Test
    fun sharp_probe_only_for_current_unit() {
        val probe = RecordingProbe()
        val readerVm = launchReader(corpusFile("two-page.cbz"), probe)
        awaitViewportSettled(readerVm)

        composeRule.runOnIdle { readerVm.setLayoutChoice(LayoutChoice.TwoPage) }
        composeRule.waitUntil(30_000) { readerVm.state?.layout == Layout.TwoPage }

        // Let unit 1's pages render into the window and draw at rest: they are not current, so
        // their sharp-draw probe must not fire.
        awaitSlotsReady(readerVm, listOf(UnitPart(1, Part.Whole), UnitPart(2, Part.Whole)))
        composeRule.waitForIdle()
        Thread.sleep(500)
        composeRule.waitForIdle()
        assertTrue(
            "no sharp-draw event may fire for non-current unit 1 pages: ${probe.sharpPages()}",
            probe.sharpPages().none { it == 1 || it == 2 },
        )

        // A snap turn to unit 1 makes its pages current: their sharp draws now fire.
        postAutomation { readerAutomation().turnTo(1, animate = false) }
        composeRule.waitUntil(20_000) { probe.sharpPages().contains(1) && probe.sharpPages().contains(2) }
        assertNoScopeErrors()
    }

    private companion object {
        const val BLOCK = 8
        const val TOLERANCE = 3
        const val MISMATCH_DIFF = 10
    }
}
