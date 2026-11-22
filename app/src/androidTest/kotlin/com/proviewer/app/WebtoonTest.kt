package com.proviewer.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proviewer.app.bench.CorpusGenerator
import com.proviewer.app.engine.NativeBook
import com.proviewer.app.engine.PageImage
import com.proviewer.app.engine.TileSource
import com.proviewer.app.open.OpenResult
import com.proviewer.app.reader.IMAGE_CAP_BYTES
import com.proviewer.app.reader.Layout
import com.proviewer.app.reader.LayoutChoice
import com.proviewer.app.reader.LocalReaderProbe
import com.proviewer.app.reader.ReaderAutomation
import com.proviewer.app.reader.ReaderProbe
import com.proviewer.app.reader.ReaderScreen
import com.proviewer.app.reader.PageSlot
import com.proviewer.app.reader.ReaderViewModel
import com.proviewer.app.reader.WEBTOON_PAGE_SCROLL
import com.proviewer.app.reader.WebtoonAutomation
import com.proviewer.app.reader.rgbaBytes
import com.proviewer.app.reader.webtoon.TileSlot
import com.proviewer.app.reader.webtoon.TileSpec
import com.proviewer.app.reader.webtoon.WEBTOON_MAX_ZOOM
import com.proviewer.app.reader.webtoon.WebtoonScrollYKey
import com.proviewer.app.reader.webtoon.WebtoonSession
import com.proviewer.app.reader.webtoon.WebtoonTopPageKey
import com.proviewer.app.reader.webtoon.WebtoonView
import com.proviewer.app.reader.webtoon.fullSize
import com.proviewer.app.reader.webtoon.wantedTiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Webtoon viewport (Tasks 10-11): a self-drawn tiled scroller at 1x with whole-column zoom
 * and sharp tile re-render. The reader is composed directly (as in TwoPageTest) so the test can
 * provide [LocalReaderProbe] and capture the [ReaderAutomation]; layouts are set programmatically.
 * The tile placement and byte-cap plumbing behind the viewport are covered by the JVM
 * TileGrid/TileManager tests; these tests pin the end-to-end behaviour: opening, scrolling under
 * the cap, seam-exact drawing, tap zones and volume keys, the far slider jump, rotation, the
 * corrupt-page placeholder, double-tap/pinch zoom, pan bounds, the settle re-render and its
 * cancellation.
 */
@RunWith(AndroidJUnit4::class)
class WebtoonTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Automation calls are posted to the main thread; failures surface through [scopeErrors]. */
    private val mainScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> scopeErrors += e },
    )
    private val scopeErrors = mutableListOf<Throwable>()

    private val automationDeferred = CompletableDeferred<ReaderAutomation>()
    private val latestAutomation = AtomicReference<ReaderAutomation?>(null)
    private var vm: ReaderViewModel? = null

    /** Activity scenarios; closed in [tearDown]. */
    private val scenarios = mutableListOf<ActivityScenario<ComponentActivity>>()

    /** Records probe events; draw callbacks run on the UI thread, others may not. */
    private class RecordingProbe : ReaderProbe {
        private val placeholders = AtomicInteger(0)
        private val detailEvents = ConcurrentLinkedQueue<Pair<Int, String>>()

        override fun onPlaceholderDrawn() {
            placeholders.incrementAndGet()
        }

        override fun onDetailRender(page: Int, event: String) {
            detailEvents += page to event
        }

        fun placeholderFrames(): Int = placeholders.get()

        /** The recorded [ReaderProbe.onDetailRender] events for [page], in order. */
        fun detailEventsFor(page: Int): List<String> =
            detailEvents.filter { it.first == page }.map { it.second }
    }

    /**
     * Counts every render [TileSource.renderScaled] start, per page. The wrapped source resolves
     * lazily (the test's ViewModel only gains its renderer at attach), so the counting wrapper can
     * be installed before the session exists without racing its creation.
     */
    private class CountingSource(private val inner: () -> TileSource?) : TileSource {
        private val startedByPage = ConcurrentHashMap<Int, AtomicInteger>()

        override suspend fun renderScaled(page: Int, full: IntSize, rect: IntRect): PageImage {
            startedByPage.computeIfAbsent(page) { AtomicInteger() }.incrementAndGet()
            return checkNotNull(inner()) { "tile source not wired" }.renderScaled(page, full, rect)
        }

        fun startedFor(page: Int): Int = startedByPage[page]?.get() ?: 0
    }

    /**
     * Counts render starts and can hold every render that starts from a chosen count on [gate]:
     * the test arms [gateFromCount] once the 1x renders have settled, so the pending level's
     * tiles can never complete before the gate is released (a cancellation then surfaces as the
     * render never delivering, exactly as under a real gesture racing the render).
     */
    private class GatingSource(private val inner: () -> TileSource?) : TileSource {
        private val started = AtomicInteger(0)

        /** Renders starting as start-count >= this hold on [gate] until it completes. */
        @Volatile var gateFromCount: Int = Int.MAX_VALUE

        val gate = CompletableDeferred<Unit>()

        override suspend fun renderScaled(page: Int, full: IntSize, rect: IntRect): PageImage {
            val count = started.incrementAndGet()
            if (count >= gateFromCount) gate.await()
            return checkNotNull(inner()) { "tile source not wired" }.renderScaled(page, full, rect)
        }

        fun startedCount(): Int = started.get()
    }

    // --- helpers ---------------------------------------------------------------

    private fun corpusFile(name: String, scale: Float = 0.25f): File {
        val dir = File(context.cacheDir, "webtoon-corpus").apply { mkdirs() }
        return runBlocking { CorpusGenerator.ensure(dir, scale, listOf(name)) }.single()
    }

    /** Opens [file] with the app engine (no copy; the corpus file already lives in the cache). */
    private fun openNativeBook(file: File): NativeBook {
        val app = context.applicationContext as ProViewerApp
        return NativeBook(app.engine.openPath(file.absolutePath), copiedFile = null)
    }

    /** Composes [ReaderScreen] for [readerVm] and captures the automation. */
    private fun showReader(
        scenario: ActivityScenario<ComponentActivity>,
        readerVm: ReaderViewModel,
        probe: RecordingProbe,
    ) {
        scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalReaderProbe provides probe) {
                    ReaderScreen(
                        vm = readerVm,
                        onBack = {},
                        onAutomation = { auto ->
                            latestAutomation.set(auto)
                            automationDeferred.complete(auto)
                        },
                    )
                }
            }
        }
    }

    /** Launches [ReaderScreen] in its own [ComponentActivity] and attaches [file]'s book. */
    private fun launchReader(
        file: File,
        probe: RecordingProbe,
        tileSource: TileSource? = null,
    ): ReaderViewModel {
        val readerVm = ReaderViewModel()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenarios += scenario
        // Installed before attach so the session (created whenever the layout becomes Webtoon,
        // including through auto-detection) is guaranteed to render through it.
        readerVm.webtoonTileSource = tileSource
        showReader(scenario, readerVm, probe)
        composeRule.runOnIdle { readerVm.attach(OpenResult.Opened(openNativeBook(file), file.name)) }
        vm = readerVm
        return readerVm
    }

    /** Like [launchReader], but also returns the scenario (for rotation tests). */
    private fun launchReaderWithScenario(
        file: File,
        probe: RecordingProbe,
        tileSource: TileSource? = null,
    ): Pair<ActivityScenario<ComponentActivity>, ReaderViewModel> {
        val readerVm = ReaderViewModel()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenarios += scenario
        readerVm.webtoonTileSource = tileSource
        showReader(scenario, readerVm, probe)
        composeRule.runOnIdle { readerVm.attach(OpenResult.Opened(openNativeBook(file), file.name)) }
        vm = readerVm
        return scenario to readerVm
    }

    /** Opens [file] through [MainActivity] (the volume-key path), as a real ACTION_VIEW open. */
    private fun launchMainActivity(file: File): Pair<ActivityScenario<MainActivity>, ReaderViewModel> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(file)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(Uri.fromFile(file), "application/zip")
        }
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        val holder = arrayOfNulls<ReaderViewModel>(1)
        scenario.onActivity { holder[0] = it.readerViewModel }
        return scenario to holder[0]!!
    }

    private fun assertNoScopeErrors() {
        val errors = scopeErrors.toList()
        if (errors.isNotEmpty()) throw AssertionError("automation scope failed", errors.first())
    }

    /** Waits until the reader attached, auto-resolved Webtoon and created its session. */
    private fun awaitWebtoon(readerVm: ReaderViewModel) {
        composeRule.waitUntil(60_000) { readerVm.state != null && readerVm.viewport != null }
        composeRule.waitUntil(60_000) {
            readerVm.state?.layout == Layout.Webtoon && readerVm.webtoon != null
        }
        composeRule.waitForIdle()
    }

    /** Waits until at least one tile of [page] is Ready. */
    private fun awaitTileReady(readerVm: ReaderViewModel, page: Int) {
        composeRule.waitUntil(60_000) {
            readerVm.webtoon?.tiles?.tiles?.values
                ?.any { it is TileSlot.Ready && it.spec.key.page == page } == true
        }
        composeRule.waitForIdle()
    }

    /** Waits until no tile render is in flight, then past the settle trim, then idle again. */
    private fun awaitTilesIdle(readerVm: ReaderViewModel) {
        composeRule.waitUntil(60_000) { noneLoading(readerVm) }
        composeRule.waitForIdle()
        Thread.sleep(400) // the settle trim's debounce; a trim may drop and retry nothing
        composeRule.waitForIdle()
        composeRule.waitUntil(60_000) { noneLoading(readerVm) }
        composeRule.waitForIdle()
    }

    private fun noneLoading(readerVm: ReaderViewModel): Boolean =
        readerVm.webtoon?.tiles?.tiles?.values?.none { it is TileSlot.Loading } == true

    /** The Webtoon automation captured through [ReaderScreen]'s onAutomation. */
    private fun webtoonAutomation(): WebtoonAutomation {
        assertNoScopeErrors()
        val auto = runBlocking { withTimeout(30_000) { automationDeferred.await() } }
        composeRule.waitUntil(30_000) { auto.webtoon != null }
        return auto.webtoon!!
    }

    /** Scrolls the live viewport by [dy] through the automation (no animation). */
    private fun scrollBy(readerVm: ReaderViewModel, dy: Float) {
        val auto = checkNotNull(latestAutomation.get()?.webtoon) {
            "WebtoonAutomation must be exposed through ReaderAutomation.webtoon"
        }
        mainScope.launch { auto.scrollBy(dy) }
    }

    /** Scrolls to the 1x content position [target] and waits until the viewport rests there. */
    private fun scrollTo(readerVm: ReaderViewModel, target: Double) {
        val current = readerVm.webtoon!!.scrollY
        scrollBy(readerVm, (target - current).toFloat())
        composeRule.waitUntil(15_000) { abs(readerVm.webtoon!!.scrollY - target) <= 1.0 }
        composeRule.waitForIdle()
    }

    /** The viewport node's semantics-reported scroll position and top page. */
    private fun semanticsScrollY(): Double =
        composeRule.onNodeWithTag("webtoon-viewport").fetchSemanticsNode()
            .config.getOrNull(WebtoonScrollYKey) ?: Double.NaN

    private fun semanticsTopPage(): Int =
        composeRule.onNodeWithTag("webtoon-viewport").fetchSemanticsNode()
            .config.getOrNull(WebtoonTopPageKey) ?: -1

    /** Waits until every tile covering the on-screen band is Ready and the frame has settled. */
    private fun awaitBandReady(readerVm: ReaderViewModel, scrollY: Double, rowEnd: Int) {
        val session = checkNotNull(readerVm.webtoon)
        val wanted = wantedTiles(
            session.layout,
            WebtoonView(scrollY, 1f, 0f),
            1000,
            forward = true,
        )
        composeRule.waitUntil(60_000) {
            wanted.visible.all { session.tiles.tiles[it] is TileSlot.Ready }
        }
        composeRule.waitForIdle()
        Thread.sleep(400) // let the settle trim run; wanted tiles are never evicted
        composeRule.waitForIdle()
    }

    /**
     * Captures the viewport and compares screen rows `[rowStart, rowEnd)` against a fresh single
     * `renderScaled` reference of the same band per page (the same full size the tiles use), per
     * channel tolerance 2: any seam, gap or placeholder row between tiles or pages mismatches.
     */
    private fun compareScreenBand(
        readerVm: ReaderViewModel,
        scrollY: Double,
        rowStart: Int,
        rowEnd: Int,
        what: String,
    ) {
        val session = checkNotNull(readerVm.webtoon)
        val layout = session.layout
        val viewW = layout.viewW
        val screen = composeRule.onNodeWithTag("webtoon-viewport").captureToImage()
        val c0 = scrollY + rowStart
        val c1 = scrollY + rowEnd
        for (page in layout.visiblePages(c0, c1)) {
            val top = layout.top(page)
            val h = layout.height(page)
            val bandStart = maxOf(c0, top)
            val bandEnd = minOf(c1, top + h)
            if (bandEnd <= bandStart) continue
            val rectTop = (bandStart - top).roundToInt()
            val rectBottom = (bandEnd - top).roundToInt()
            val full = fullSize(layout, page, 1000)
            val image = runBlocking {
                readerVm.renderer!!.renderScaled(page, full, IntRect(0, rectTop, viewW, rectBottom))
            }
            try {
                val ref = checkNotNull(image.bitmap).copy(Bitmap.Config.ARGB_8888, false)
                val refPx = IntArray(viewW)
                val screenPx = IntArray(viewW)
                for (r in 0 until rectBottom - rectTop) {
                    ref.getPixels(refPx, 0, viewW, 0, r, viewW, 1)
                    val screenRow = (top + rectTop + r - scrollY).roundToInt()
                    screen.readPixels(screenPx, startX = 0, startY = screenRow, width = viewW, height = 1)
                    for (x in 0 until viewW) {
                        val a = screenPx[x]
                        val b = refPx[x]
                        val diff = maxOf(
                            abs(AndroidColor.red(a) - AndroidColor.red(b)),
                            abs(AndroidColor.green(a) - AndroidColor.green(b)),
                            abs(AndroidColor.blue(a) - AndroidColor.blue(b)),
                        )
                        if (diff > TOLERANCE) {
                            fail(
                                "$what: page $page screen row $screenRow x=$x differs by $diff " +
                                    "(screen=${Integer.toHexString(a)} ref=${Integer.toHexString(b)})",
                            )
                        }
                    }
                }
            } finally {
                image.release()
            }
        }
    }

    /** Rotates the scenario's activity to [orientation], re-applying the reader content. */
    private fun rotate(
        scenario: ActivityScenario<ComponentActivity>,
        readerVm: ReaderViewModel,
        probe: RecordingProbe,
        orientation: Int,
    ) {
        scenario.onActivity { it.requestedOrientation = orientation }
        val wantLandscape = orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val deadline = System.currentTimeMillis() + 60_000
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                showReader(scenario, readerVm, probe)
                var matches = false
                scenario.onActivity { activity ->
                    matches = (activity.resources.configuration.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE) == wantLandscape
                }
                if (matches) return
            } catch (e: Throwable) {
                lastError = e
            }
            Thread.sleep(250)
        }
        throw AssertionError(
            "activity never reached orientation $orientation; lastError=$lastError",
        )
    }

    // --- corrupt strip CBZ (strip, corrupt JPEG, strip) -------------------------

    /** A CBZ of three 200x1200 strips where page 1's JPEG decodes fatally (probe still succeeds). */
    private fun buildCorruptStripCbz(): File {
        val dir = File(context.cacheDir, "webtoon-corpus").apply { mkdirs() }
        val file = File(dir, "corrupt-strip.cbz")
        val valid = stripJpegBytes(200, 1200)
        val corrupt = corruptJpeg(valid)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.jpg")); zip.write(valid); zip.closeEntry()
            zip.putNextEntry(ZipEntry("002.jpg")); zip.write(corrupt); zip.closeEntry()
            zip.putNextEntry(ZipEntry("003.jpg")); zip.write(valid); zip.closeEntry()
        }
        return file
    }

    /** A [w] x [h] strip JPEG of 4 px cells with pseudo-random colours (placement-sensitive). */
    private fun stripJpegBytes(w: Int, h: Int): ByteArray {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val cell = (x / 4) * 73856093 xor (y / 4) * 19349663
                val v = (cell xor (cell ushr 13)) * 0x2545F491
                pixels[y * w + x] = AndroidColor.rgb((v ushr 16) and 0xFF, (v ushr 8) and 0xFF, v and 0xFF)
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        bitmap.recycle()
        return jpeg
    }

    /**
     * Corrupts a valid JPEG so its header still parses (the size probe succeeds) but its decode
     * fails fatally: the DHT (Huffman) segment's counts are zeroed, which libjpeg reports as
     * "bogus Huffman table definition" (a fatal, not warning, error).
     */
    private fun corruptJpeg(jpeg: ByteArray): ByteArray {
        val out = jpeg.copyOf()
        var i = 2 // skip SOI
        while (i + 3 < out.size) {
            val b = out[i].toInt() and 0xFF
            if (b != 0xFF) {
                i++
                continue
            }
            val marker = out[i + 1].toInt() and 0xFF
            if (marker == 0xC4) { // DHT
                val counts = i + 5 // FF C4 len_hi len_lo Tc/Th <16 counts>
                for (k in 0 until 16) if (counts + k < out.size) out[counts + k] = 0
                return out
            }
            if (marker == 0xDA || marker == 0xD9) break // SOS / EOI before any DHT
            val len = ((out[i + 2].toInt() and 0xFF) shl 8) or (out[i + 3].toInt() and 0xFF)
            if (len < 2) break
            i += 2 + len
        }
        throw IllegalStateException("no DHT marker found in the generated JPEG; cannot corrupt it")
    }

    // --- tests -----------------------------------------------------------------

    @Test
    fun opens_in_webtoon_and_draws_first_tiles() {
        val readerVm = launchReader(corpusFile("webtoon-long.cbz"), RecordingProbe())
        awaitWebtoon(readerVm)

        // The pager's window is closed while the session owns the tiles.
        assertNull("PageWindow must be closed while in Webtoon", readerVm.window)
        val session = checkNotNull(readerVm.webtoon)

        // The first visible tile of page 0 renders; the sharp-page probe is not part of webtoon.
        awaitTileReady(readerVm, page = 0)

        // The viewport is shown, opens at the book's first page, and reports it.
        composeRule.onNodeWithTag("webtoon-viewport").assertExists()
        assertEquals(0.0, session.scrollY, 0.5)
        composeRule.waitUntil(15_000) { semanticsTopPage() == 0 }
        assertEquals(0, semanticsTopPage())
    }

    @Test
    fun scroll_to_bottom_and_back_under_cap() {
        val readerVm = launchReader(corpusFile("webtoon-long.cbz"), RecordingProbe())
        awaitWebtoon(readerVm)
        val budget = checkNotNull(readerVm.budget)
        awaitTilesIdle(readerVm)

        // The working-set size at the top (after the settle trim), and the peak to measure.
        val workingSet = budget.usedBytes
        assertTrue("the initial working set must hold tiles", workingSet > 0)
        composeRule.runOnIdle { budget.resetPeak() }

        // Down to maxScroll in 30 steps.
        val auto = webtoonAutomation()
        val max = auto.maxScroll
        assertTrue(max > 0.0)
        val step = (max / 30).toFloat()
        mainScope.launch {
            repeat(30) {
                auto.scrollBy(step)
                delay(40) // let renders and evictions keep up with the scroll
            }
        }
        composeRule.waitUntil(60_000) { abs(readerVm.webtoon!!.scrollY - max) <= 1.0 }
        awaitTilesIdle(readerVm)
        assertTrue(
            "peak held bytes ${budget.peakBytes} must stay within the cap",
            budget.peakBytes <= IMAGE_CAP_BYTES,
        )

        // And back to the top in 30 steps.
        mainScope.launch {
            repeat(30) {
                auto.scrollBy(-step)
                delay(40)
            }
        }
        composeRule.waitUntil(60_000) { abs(readerVm.webtoon!!.scrollY) <= 1.0 }
        awaitTilesIdle(readerVm)
        // After the settle trim the held bytes return to the working-set size: exactly the tiles
        // the wanted set asks for (the prefetch margin trails the scroll direction, and the last
        // direction was up, so the working set here carries half a viewport behind the viewport).
        val sessionNow = checkNotNull(readerVm.webtoon)
        val expectedWorkingSet = wantedTiles(
            sessionNow.layout,
            WebtoonView(sessionNow.scrollY, sessionNow.zoom, sessionNow.panX),
            1000,
            forward = false,
        ).ordered.sumOf { rgbaBytes(it.rect.width, it.rect.height) }
        composeRule.waitUntil(30_000) { budget.usedBytes == expectedWorkingSet }
        assertEquals(expectedWorkingSet, budget.usedBytes)
        assertNoScopeErrors()

        // Leaving Webtoon closes the session and releases every tile. The recreated pager window
        // immediately starts rendering its own bases, so the budget as a whole does not drop to
        // zero: after its first renders settle, every held byte belongs to the window.
        val sessionAtEnd = checkNotNull(readerVm.webtoon)
        composeRule.runOnIdle { readerVm.setLayoutChoice(LayoutChoice.Single) }
        composeRule.waitUntil(15_000) { readerVm.webtoon == null && readerVm.window != null }
        assertTrue("close() must release every tile slot", sessionAtEnd.tiles.tiles.isEmpty())
        assertTrue("close() must clear the failure marks", sessionAtEnd.tiles.failedPages.isEmpty())
        composeRule.waitUntil(15_000) {
            val window = readerVm.window!!
            window.slots.isNotEmpty() && window.slots.values.none { it is PageSlot.Loading }
        }
        composeRule.waitForIdle()
        val windowBytes = readerVm.window!!.slots.values.sumOf { slot ->
            (slot as? PageSlot.Ready)?.let { rgbaBytes(it.size.width, it.size.height) } ?: 0L
        }
        assertEquals(
            "after leaving Webtoon only the pager window's renders hold bytes",
            windowBytes,
            budget.usedBytes,
        )
    }

    @Test
    fun tile_and_page_boundaries_match_reference() {
        val readerVm = launchReader(corpusFile("webtoon-long.cbz"), RecordingProbe())
        awaitWebtoon(readerVm)
        val session = checkNotNull(readerVm.webtoon)
        val pageH = session.layout.height(0)

        // Across a tile boundary inside page 0 (content row 1024 = tile row 0/1 seam).
        scrollTo(readerVm, 800.0)
        awaitBandReady(readerVm, scrollY = 800.0, rowEnd = 1044)
        compareScreenBand(readerVm, scrollY = 800.0, rowStart = 1004, rowEnd = 1044, what = "tile boundary")

        // Across the page boundary (content row pageH = page 0/1 seam).
        val atBoundary = pageH - 1000.0
        scrollTo(readerVm, atBoundary)
        awaitBandReady(readerVm, scrollY = atBoundary, rowEnd = 1020)
        compareScreenBand(
            readerVm,
            scrollY = atBoundary,
            rowStart = 980,
            rowEnd = 1020,
            what = "page boundary",
        )
    }

    @Test
    fun tap_zones_and_volume_scroll_85_percent() {
        val (scenario, readerVm) = launchMainActivity(corpusFile("webtoon-long.cbz"))
        try {
            composeRule.waitUntil(60_000) { readerVm.state != null && readerVm.viewport != null }
            composeRule.waitUntil(60_000) {
                readerVm.state?.layout == Layout.Webtoon && readerVm.webtoon != null
            }
            awaitTileReady(readerVm, page = 0)
            val viewH = readerVm.viewport!!.height
            val pageScroll = (WEBTOON_PAGE_SCROLL * viewH).toDouble()
            assertTrue(pageScroll > 0)

            // Volume down scrolls down by 85% of the viewport height.
            scenario.onActivity { activity ->
                activity.onKeyDown(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN),
                )
            }
            composeRule.waitUntil(15_000) { abs(readerVm.webtoon!!.scrollY - pageScroll) <= 1.0 }
            assertEquals(pageScroll, readerVm.webtoon!!.scrollY, 1.0)

            // Bottom-third tap: another 85% down. Top-third tap: 85% back up.
            composeRule.onNodeWithTag("webtoon-viewport").performTouchInput {
                click(Offset(visibleSize.width * 0.5f, visibleSize.height * 0.9f))
            }
            composeRule.waitUntil(15_000) { abs(readerVm.webtoon!!.scrollY - 2 * pageScroll) <= 1.0 }
            composeRule.onNodeWithTag("webtoon-viewport").performTouchInput {
                click(Offset(visibleSize.width * 0.5f, visibleSize.height * 0.1f))
            }
            composeRule.waitUntil(15_000) { abs(readerVm.webtoon!!.scrollY - pageScroll) <= 1.0 }
            assertEquals(pageScroll, readerVm.webtoon!!.scrollY, 1.0)

            // Middle third toggles the overlay, both ways.
            composeRule.onNodeWithTag("webtoon-viewport").performTouchInput {
                click(Offset(visibleSize.width * 0.5f, visibleSize.height * 0.5f))
            }
            composeRule.waitUntil(15_000) { readerVm.state?.overlayVisible == true }
            composeRule.onNodeWithTag("webtoon-viewport").performTouchInput {
                click(Offset(visibleSize.width * 0.5f, visibleSize.height * 0.5f))
            }
            composeRule.waitUntil(15_000) { readerVm.state?.overlayVisible == false }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun fling_direction_matches_documented_contract() {
        val readerVm = launchReader(corpusFile("webtoon-long.cbz"), RecordingProbe())
        awaitWebtoon(readerVm)
        awaitTileReady(readerVm, page = 0)
        val session = checkNotNull(readerVm.webtoon)
        val auto = webtoonAutomation()

        // Start mid-book, with room to fling in both directions.
        scrollTo(readerVm, auto.maxScroll / 2)

        // fling(+velocity) is the release of a downward swipe: the content follows the finger's
        // momentum, so the viewport scrolls UP (scrollY decreases) — the documented contract.
        val before = session.scrollY
        mainScope.launch { auto.fling(4000f) }
        composeRule.waitUntil(15_000) { session.scrollY <= before - 50.0 }
        val afterUpFling = session.scrollY
        assertTrue(
            "fling(+4000) must scroll up: before=$before after=$afterUpFling",
            afterUpFling < before - 50.0,
        )

        // fling(-velocity) scrolls DOWN (scrollY increases).
        mainScope.launch { auto.fling(-4000f) }
        composeRule.waitUntil(15_000) { session.scrollY >= afterUpFling + 50.0 }
        assertTrue(
            "fling(-4000) must scroll down: afterUpFling=$afterUpFling after=${session.scrollY}",
            session.scrollY > afterUpFling + 50.0,
        )
    }

    @Test
    fun slider_far_jump_lands_on_page_top() {
        // The counting wrapper resolves the VM's renderer lazily and is installed before attach,
        // so it is in the render path however the session is created.
        val counting = CountingSource { vm?.renderer }
        val readerVm = launchReader(corpusFile("many-pages.cbz"), RecordingProbe(), tileSource = counting)
        composeRule.waitUntil(60_000) { readerVm.state != null && readerVm.viewport != null }
        // many-pages is not a strip, so auto keeps Single: pin the Webtoon layout this test reads.
        composeRule.runOnIdle { readerVm.setLayoutChoice(LayoutChoice.Webtoon) }
        composeRule.waitUntil(60_000) { readerVm.webtoon != null }
        val session = checkNotNull(readerVm.webtoon)

        // Land the sizes of the jump target's neighbourhood first: the wanted set there then never
        // changes spec, so any later size landing cannot re-render its tiles (the render count
        // below would otherwise exceed the bound through no fault of the jump).
        composeRule.runOnIdle { readerVm.sizes?.prioritize((385..415).toList()) }
        composeRule.waitUntil(60_000) {
            (385..415).all { readerVm.sizes?.known(it) != null || readerVm.sizes?.failed(it) == true }
        }
        awaitTilesIdle(readerVm)
        val startedBefore = (385..415).sumOf { counting.startedFor(it) }

        // The far jump: once idle, page 400's top is at the viewport top.
        composeRule.runOnIdle { readerVm.goTo(400) }
        composeRule.waitUntil(60_000) {
            val s = readerVm.webtoon!!
            noneLoading(readerVm) &&
                s.layout.pageAt(s.scrollY) == 400 &&
                abs(s.scrollY - s.layout.top(400)) <= 1.0
        }
        composeRule.waitForIdle()

        // The viewport's test semantics report the landed state.
        composeRule.waitUntil(30_000) {
            abs(semanticsScrollY() - session.layout.top(400)) <= 1.0 && semanticsTopPage() == 400
        }
        assertEquals(400, semanticsTopPage())
        assertEquals(400, readerVm.state!!.page)

        // The jump must not queue renders for passed pages: everything rendered after the jump is
        // bounded by the final wanted set (in-flight at the jump was zero: the reader was idle).
        val wanted = wantedTiles(
            session.layout,
            WebtoonView(session.scrollY, session.zoom, session.panX),
            1000,
            forward = false,
        )
        val started = (385..415).sumOf { counting.startedFor(it) } - startedBefore
        assertTrue(
            "renders after the jump ($started) must be bounded by the final wanted set " +
                "(${wanted.ordered.size})",
            started <= wanted.ordered.size,
        )
    }

    @Test
    fun rotation_keeps_anchor_page() {
        val probe = RecordingProbe()
        val (scenario, readerVm) = launchReaderWithScenario(corpusFile("webtoon-long.cbz"), probe)
        awaitWebtoon(readerVm)
        val session = checkNotNull(readerVm.webtoon)
        val page1 = 1

        // Zoom to 2x via double-tap, then scroll into page 1.
        composeRule.onNodeWithTag("webtoon-viewport").performTouchInput { doubleClick(center) }
        composeRule.waitUntil(15_000) { session.zoom == 2f }
        assertEquals(2f, session.zoom)

        val layoutBefore = session.layout
        val offsetBefore = 500.0
        scrollTo(readerVm, layoutBefore.top(page1) + offsetBefore)
        val relBefore = (session.scrollY - layoutBefore.top(page1)) / layoutBefore.height(page1)

        // Rotate: the activity recreates, the session survives, zoom resets, the anchor holds.
        rotate(scenario, readerVm, probe, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        composeRule.waitUntil(60_000) { readerVm.webtoon != null && readerVm.webtoon!!.zoom == 1f }
        assertEquals("rotation resets zoom to 1x", 1f, session.zoom)
        composeRule.waitUntil(60_000) { session.layout.viewW > session.layout.viewH }

        val layoutAfter = session.layout
        assertEquals("the anchor page is kept", page1, layoutAfter.pageAt(session.scrollY))
        val relAfter = (session.scrollY - layoutAfter.top(page1)) / layoutAfter.height(page1)
        assertTrue(
            "relative offset must be kept: before=$relBefore after=$relAfter",
            abs(relAfter - relBefore) <= 0.02,
        )
    }

    @Test
    fun corrupt_page_shows_placeholder_and_scroll_continues() {
        val probe = RecordingProbe()
        val counting = CountingSource { vm?.renderer }
        val readerVm = launchReader(buildCorruptStripCbz(), probe, tileSource = counting)
        composeRule.waitUntil(60_000) { readerVm.state != null && readerVm.viewport != null }
        awaitTileReady(readerVm, page = 0)
        val session = checkNotNull(readerVm.webtoon)

        // Scroll into page 1: its tile render fails (corrupt JPEG, sticky for the session).
        scrollTo(readerVm, session.layout.top(1) + 200.0)
        composeRule.waitUntil(30_000) { session.tiles.failedPages.containsKey(1) }

        // The error placeholder is drawn in the visible area (probe) and exposed as a tag node.
        composeRule.waitUntil(30_000) { probe.placeholderFrames() > 0 }
        composeRule.onNodeWithTag("reader-error-1").assertExists()

        // The placeholder area is the dark placeholder colour, with "Page 2" + reason drawn.
        // (The capture's alpha channel is undefined; compare RGB.)
        val dark = sampleViewportPixel(readerVm, x = 135, y = 100)
        assertEquals("placeholder area must be dark", 0x111111, dark and 0xFFFFFF)

        // Scrolling continues past it: page 2's tiles render.
        scrollTo(readerVm, session.layout.top(2) + 200.0)
        composeRule.waitUntil(30_000) {
            session.tiles.tiles.values.any { it is TileSlot.Ready && it.spec.key.page == 2 }
        }

        // The failure is sticky: coming back shows the placeholder again, never a re-render.
        val started1 = counting.startedFor(1)
        assertTrue("the corrupt page's tiles must have been attempted", started1 >= 1)
        scrollTo(readerVm, session.layout.top(1) + 200.0)
        composeRule.waitUntil(30_000) { noneLoading(readerVm) }
        assertEquals("a failed page must never re-render", started1, counting.startedFor(1))
        val darkAgain = sampleViewportPixel(readerVm, x = 135, y = 100)
        assertEquals(0x111111, darkAgain and 0xFFFFFF)
    }

    // --- zoom and sharp re-render (Task 11) --------------------------------------

    @Test
    fun double_tap_zooms_and_rerenders_visible_tiles_only() {
        val probe = RecordingProbe()
        val readerVm = launchReader(corpusFile("webtoon-long.cbz"), probe)
        awaitWebtoon(readerVm)
        val session = checkNotNull(readerVm.webtoon)
        awaitTileReady(readerVm, page = 0)
        awaitTilesIdle(readerVm)

        // Double-tap the centre: zoom to 2x about the tap.
        composeRule.onNodeWithTag("webtoon-viewport").performTouchInput { doubleClick(center) }
        composeRule.waitUntil(15_000) { session.zoom == 2f }
        assertEquals(2f, session.zoom)

        // After the settle the visible area's level-2000 tiles are requested and render.
        composeRule.waitUntil(60_000) {
            val view = WebtoonView(session.scrollY, session.zoom, session.panX)
            val visible2000 = wantedTiles(session.layout, view, 2000, forward = true)
                .visible.filter { it.level == 2000 }
            visible2000.isNotEmpty() && visible2000.all { session.tiles.tiles[it] is TileSlot.Ready }
        }
        composeRule.waitForIdle()

        // Every Ready tile at level 2000 intersects the visible area ± the prefetch margin (one
        // viewport of content): the re-render covered only the visible area, never the whole book.
        val layout = session.layout
        val y0 = session.scrollY
        val y1 = y0 + layout.viewH / session.zoom
        val x0 = session.panX / session.zoom
        val x1 = x0 + layout.viewW / session.zoom
        val margin = layout.viewH / session.zoom
        val ready2000 = session.tiles.tiles.filter { it.key.level == 2000 && it.value is TileSlot.Ready }
        assertTrue("the settle must have rendered level-2000 tiles", ready2000.isNotEmpty())
        for ((key, slot) in ready2000) {
            val spec = (slot as TileSlot.Ready).spec
            val top = layout.top(key.page)
            val scaleY = layout.height(key.page).toDouble() / spec.full.height
            val scaleX = layout.viewW.toDouble() / spec.full.width
            val left = spec.rect.left * scaleX
            val right = spec.rect.right * scaleX
            val tileTop = top + spec.rect.top * scaleY
            val tileBottom = top + spec.rect.bottom * scaleY
            assertTrue(
                "level-2000 tile $key must intersect the visible area ± margin " +
                    "(tile x [$left, $right] y [$tileTop, $tileBottom], view x [$x0, $x1] y [$y0, $y1])",
                left < x1 && x0 < right && tileTop < y1 + margin && y0 - margin < tileBottom,
            )
        }

        // And nothing beyond the wanted set (both scroll directions' prefetch margins): the
        // re-render never renders past the working set.
        val view = WebtoonView(session.scrollY, session.zoom, session.panX)
        val wantedKeys = wantedTiles(layout, view, 2000, forward = true).ordered.map { it.key }.toSet() +
            wantedTiles(layout, view, 2000, forward = false).ordered.map { it.key }.toSet()
        assertTrue(
            "ready level-2000 tiles must be within the wanted set: ${ready2000.keys - wantedKeys}",
            ready2000.keys.all { it in wantedKeys },
        )

        // The first wanted level-2000 tile is drawn: its on-screen pixels equal a fresh render of
        // the same tile (at 2x a level-2000 tile maps 1:1 onto the screen).
        val first = wantedTiles(layout, view, 2000, forward = true).ordered.first { it.key.level == 2000 }
        assertTrue("the first level-2000 tile must be ready", session.tiles.tiles[first.key] is TileSlot.Ready)
        compareTileRow(readerVm, session, first, "first level-2000 tile")

        // The probe reports the settled level's lifecycle: every started tile lands done,
        // nothing is cancelled at rest.
        val page0 = probe.detailEventsFor(0)
        assertTrue(
            "tiles-start must fire for the settled level: $page0",
            page0.count { it == "tiles-start" } >= 1,
        )
        assertEquals(
            "every started tile must land done: $page0",
            page0.count { it == "tiles-start" },
            page0.count { it == "tiles-done" },
        )
        assertEquals(
            "nothing may be cancelled while the view rests: $page0",
            0,
            page0.count { it == "tiles-cancelled" },
        )
        assertNoScopeErrors()
    }

    @Test
    fun new_gesture_cancels_pending_level() {
        val probe = RecordingProbe()
        val gating = GatingSource { vm?.renderer }
        val readerVm = launchReader(corpusFile("webtoon-long.cbz"), probe, tileSource = gating)
        awaitWebtoon(readerVm)
        val session = checkNotNull(readerVm.webtoon)
        awaitTileReady(readerVm, page = 0)
        awaitTilesIdle(readerVm)

        // From now on every render that starts holds on the gate: the pending level's tiles can
        // never complete before the test releases them.
        gating.gateFromCount = gating.startedCount()

        // Zoom to 2x (no animation): after the settle the visible level-2000 tiles start.
        val auto = webtoonAutomation()
        val focal = Offset(session.layout.viewW / 2f, readerVm.viewport!!.height / 2f)
        mainScope.launch { auto.zoomTo(2f, focal, animate = false) }
        composeRule.waitUntil(15_000) { session.zoom == 2f }
        composeRule.waitUntil(60_000) {
            probe.detailEventsFor(0).contains("tiles-start") &&
                session.tiles.tiles.any { it.key.level == 2000 && it.value is TileSlot.Loading }
        }
        assertTrue(
            "no tile may complete while gated: ${probe.detailEventsFor(0)}",
            probe.detailEventsFor(0).none { it == "tiles-done" },
        )

        // A new gesture (scrolling the zoomed view) before completion cancels the pending level:
        // the scrolled view's wanted set no longer asks for the in-flight tiles, so TileManager's
        // update cancels them (the scroll is far enough that the old and new visible bands
        // cannot share a tile).
        mainScope.launch { auto.scrollBy(readerVm.viewport!!.height * 2f) }
        composeRule.waitUntil(30_000) { probe.detailEventsFor(0).contains("tiles-cancelled") }
        val events = probe.detailEventsFor(0)
        assertTrue(
            "the pending level must be cancelled before anything completes: $events",
            events.takeWhile { it != "tiles-cancelled" }.contains("tiles-start") &&
                events.takeWhile { it != "tiles-cancelled" }.none { it == "tiles-done" },
        )

        // Released, the re-render for the new position completes.
        gating.gate.complete(Unit)
        composeRule.waitUntil(60_000) { probe.detailEventsFor(0).contains("tiles-done") }
        awaitTilesIdle(readerVm)
        assertNoScopeErrors()
    }

    @Test
    fun pan_stays_in_bounds() {
        val readerVm = launchReader(corpusFile("webtoon-long.cbz"), RecordingProbe())
        awaitWebtoon(readerVm)
        val session = checkNotNull(readerVm.webtoon)
        awaitTileReady(readerVm, page = 0)
        val viewH = readerVm.viewport!!.height

        // Zoom to 2x about the viewport centre: panX = viewW/2, scrollY = viewH/4.
        val auto = webtoonAutomation()
        mainScope.launch {
            auto.zoomTo(2f, Offset(session.layout.viewW / 2f, viewH / 2f), animate = false)
        }
        composeRule.waitUntil(15_000) { session.zoom == 2f }
        assertEquals(session.layout.viewW / 2f, session.panX, 0.5f)

        // panBy -1e5 (the finger sweeps far left): the pan clamps at the zoomed column's right
        // edge, (zoom - 1) x viewW = viewW at 2x, and never scrolls the list.
        panBy(-100_000f)
        composeRule.waitForIdle()
        assertEquals(
            "panning far left must clamp to (zoom-1) x viewW at 2x",
            session.layout.viewW.toFloat(),
            session.panX,
            0.01f,
        )
        assertEquals("a horizontal pan must not scroll", viewH / 4.0, session.scrollY, 0.5)

        // panBy +1e5 (the finger sweeps far right) clamps back to 0.
        panBy(100_000f)
        composeRule.waitForIdle()
        assertEquals("panning far right must clamp to 0", 0f, session.panX, 0.01f)
        assertEquals(viewH / 4.0, session.scrollY, 0.5)
        assertNoScopeErrors()
    }

    @Test
    fun pinch_keeps_focal_point() {
        val readerVm = launchReader(corpusFile("webtoon-long.cbz"), RecordingProbe())
        awaitWebtoon(readerVm)
        val session = checkNotNull(readerVm.webtoon)
        awaitTileReady(readerVm, page = 0)
        // Mid-page with room in every direction, so no range clamp interferes with the focal point.
        scrollTo(readerVm, session.layout.top(0) + 5000.0)
        val focal = Offset(session.layout.viewW / 2f, readerVm.viewport!!.height / 2f)
        val auto = webtoonAutomation()

        // zoomTo(2f, focal): the content point under the focal before is under it after.
        val before = contentUnderFocal(readerVm, focal)
        mainScope.launch { auto.zoomTo(2f, focal, animate = false) }
        composeRule.waitUntil(15_000) { session.zoom == 2f }
        var after = contentUnderFocal(readerVm, focal)
        assertEquals("x under the focal is kept", before.first, after.first, 1.0)
        assertEquals("y under the focal is kept", before.second, after.second, 1.0)

        // The zoom clamps to WEBTOON_MAX_ZOOM; the focal point still stays put.
        mainScope.launch { auto.zoomTo(10f, focal, animate = false) }
        composeRule.waitUntil(15_000) { session.zoom == WEBTOON_MAX_ZOOM }
        after = contentUnderFocal(readerVm, focal)
        assertEquals("x under the focal is kept at max zoom", before.first, after.first, 1.0)
        assertEquals("y under the focal is kept at max zoom", before.second, after.second, 1.0)

        // An animated zoom back to 1x keeps the focal point and restores the initial transform.
        mainScope.launch { auto.zoomTo(1f, focal, animate = true) }
        composeRule.waitUntil(15_000) { session.zoom == 1f }
        composeRule.waitForIdle()
        after = contentUnderFocal(readerVm, focal)
        assertEquals("x under the focal is kept at 1x", before.first, after.first, 1.0)
        assertEquals("y under the focal is kept at 1x", before.second, after.second, 1.0)
        assertEquals(
            "zooming back to 1x restores the scroll",
            session.layout.top(0) + 5000.0,
            session.scrollY,
            1.0,
        )
        assertEquals("zooming back to 1x clears the pan", 0f, session.panX, 0.01f)
        assertNoScopeErrors()
    }

    @Test
    fun zoomed_scroll_under_cap() {
        val readerVm = launchReader(corpusFile("webtoon-long.cbz"), RecordingProbe())
        awaitWebtoon(readerVm)
        val budget = checkNotNull(readerVm.budget)
        awaitTilesIdle(readerVm)
        val session = checkNotNull(readerVm.webtoon)

        // Zoom to 2x at the centre (the peak counts the zoom's own renders too), then scroll
        // through the whole book at that zoom: eviction must keep the held bytes within the cap.
        val auto = webtoonAutomation()
        composeRule.runOnIdle { budget.resetPeak() }
        mainScope.launch {
            auto.zoomTo(2f, Offset(session.layout.viewW / 2f, readerVm.viewport!!.height / 2f), animate = false)
        }
        composeRule.waitUntil(15_000) { session.zoom == 2f }
        val max = auto.maxScroll
        assertTrue(max > 0.0)
        val step = (max / 30).toFloat()
        mainScope.launch {
            repeat(30) {
                auto.scrollBy(step)
                delay(40) // let renders and evictions keep up with the scroll
            }
        }
        composeRule.waitUntil(60_000) { abs(readerVm.webtoon!!.scrollY - max) <= 1.0 }
        awaitTilesIdle(readerVm)
        assertTrue(
            "peak held bytes ${budget.peakBytes} must stay within the cap",
            budget.peakBytes <= IMAGE_CAP_BYTES,
        )
        assertNoScopeErrors()
    }

    /** Reads one viewport pixel (or fails the test when the node cannot be captured). */
    private fun sampleViewportPixel(readerVm: ReaderViewModel, x: Int, y: Int): Int {
        composeRule.waitForIdle()
        val screen = composeRule.onNodeWithTag("webtoon-viewport").captureToImage()
        val px = IntArray(1)
        screen.readPixels(px, startX = x, startY = y, width = 1, height = 1)
        return px[0]
    }

    /** Performs a horizontal pan drag of [dx] screen px (positive = the finger moves right). */
    private fun panBy(dx: Float) {
        composeRule.onNodeWithTag("webtoon-viewport").performTouchInput {
            val cy = visibleSize.height / 2f
            swipe(
                start = Offset(visibleSize.width / 2f, cy),
                end = Offset(visibleSize.width / 2f + dx, cy),
            )
        }
    }

    /** The content point (1x px) currently under the viewport position [focal] (screen px). */
    private fun contentUnderFocal(readerVm: ReaderViewModel, focal: Offset): Pair<Double, Double> {
        val s = checkNotNull(readerVm.webtoon)
        return Pair((focal.x + s.panX) / s.zoom.toDouble(), focal.y / s.zoom.toDouble() + s.scrollY)
    }

    /**
     * Compares the on-screen part of [spec]'s tile against a fresh `renderScaled` of the same
     * tile spec (the exact render the tile slot holds), per channel tolerance [TOLERANCE]. When
     * `zoom == level/1000` a settled-level tile maps 1:1 onto the screen, so any pixel mismatch
     * means the tile was not drawn sharp.
     */
    private fun compareTileRow(
        readerVm: ReaderViewModel,
        session: WebtoonSession,
        spec: TileSpec,
        what: String,
    ) {
        val layout = session.layout
        assertEquals("the tile must be drawn 1:1 at its level", spec.key.level / 1000f, session.zoom, 0.01f)
        val screen = composeRule.onNodeWithTag("webtoon-viewport").captureToImage()
        val scaleY = layout.height(spec.key.page).toDouble() / spec.full.height
        val scaleX = layout.viewW.toDouble() / spec.full.width
        val top = layout.top(spec.key.page)
        val screenTop = ((top + spec.rect.top * scaleY - session.scrollY) * session.zoom).roundToInt()
        val screenBottom = ((top + spec.rect.bottom * scaleY - session.scrollY) * session.zoom).roundToInt()
        val x0 = (spec.rect.left * scaleX * session.zoom - session.panX).roundToInt()
        val onScreenFrom = maxOf(0, -x0)
        val onScreenTo = minOf(spec.rect.width, layout.viewW - x0)
        assertTrue("$what: not enough of the tile is on screen to compare", onScreenTo - onScreenFrom >= 8)
        val screenY = ((screenTop + screenBottom) / 2).coerceIn(0, layout.viewH - 1)
        // The screen row's full-size y (1:1 mapping: one screen px is one full-size px).
        val contentY = session.scrollY + screenY / session.zoom.toDouble()
        val fullY = ((contentY - top) / scaleY).roundToInt().coerceIn(spec.rect.top, spec.rect.bottom - 1)
        val image = runBlocking {
            readerVm.renderer!!.renderScaled(spec.key.page, spec.full, spec.rect)
        }
        try {
            val ref = checkNotNull(image.bitmap).copy(Bitmap.Config.ARGB_8888, false)
            val w = spec.rect.width
            val refPx = IntArray(w)
            ref.getPixels(refPx, 0, w, 0, fullY - spec.rect.top, w, 1)
            val count = onScreenTo - onScreenFrom
            val screenPx = IntArray(count)
            screen.readPixels(screenPx, startX = x0 + onScreenFrom, startY = screenY, width = count, height = 1)
            for (j in onScreenFrom until onScreenTo) {
                val a = screenPx[j - onScreenFrom]
                val b = refPx[j]
                val diff = maxOf(
                    abs(AndroidColor.red(a) - AndroidColor.red(b)),
                    abs(AndroidColor.green(a) - AndroidColor.green(b)),
                    abs(AndroidColor.blue(a) - AndroidColor.blue(b)),
                )
                if (diff > TOLERANCE) {
                    fail("$what: tile ${spec.key} screen x=${x0 + j} y=$screenY differs by $diff")
                }
            }
        } finally {
            image.release()
        }
    }

    private companion object {
        const val TOLERANCE = 2
    }
}
