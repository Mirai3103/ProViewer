package com.proviewer.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proviewer.app.bench.CorpusGenerator
import com.proviewer.app.engine.NativeBook
import com.proviewer.app.open.OpenResult
import com.proviewer.app.reader.FitMode
import com.proviewer.app.reader.LocalReaderProbe
import com.proviewer.app.reader.PageRegion
import com.proviewer.app.reader.PageSlot
import com.proviewer.app.reader.ReaderAutomation
import com.proviewer.app.reader.ReaderProbe
import com.proviewer.app.reader.ReaderScreen
import com.proviewer.app.reader.ReaderViewModel
import com.proviewer.app.reader.ZoomAutomation
import com.proviewer.app.reader.baseSize
import com.proviewer.app.reader.visibleRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max

/**
 * Zoom and detail re-render, end to end (spec D5 approach A): the base bitmap is scaled while a
 * gesture runs, and after a 150 ms settle the visible region is re-rendered sharp into one detail
 * bitmap. The reader is composed directly so the test can provide [LocalReaderProbe] and capture
 * the [ReaderAutomation] delivered through `ReaderScreen(onAutomation)`.
 */
@RunWith(AndroidJUnit4::class)
class ZoomTest {
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
        private val renderEvents = mutableListOf<Pair<Int, String>>()
        private val detailImages = mutableListOf<Pair<Int, PageRegion?>>()

        override fun onDetailRender(page: Int, event: String) = synchronized(lock) {
            renderEvents += page to event
        }

        override fun onDetailImage(page: Int, region: PageRegion?) = synchronized(lock) {
            detailImages += page to region
        }

        fun events(): List<String> = synchronized(lock) { renderEvents.map { it.second } }
        fun images(): List<PageRegion?> = synchronized(lock) { detailImages.map { it.second } }
        fun eventCount(name: String) = events().count { it == name }
    }

    // --- helpers ---------------------------------------------------------------

    private fun corpusFile(name: String): File {
        val dir = File(context.cacheDir, "zoom-corpus").apply { mkdirs() }
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
     * Waits until the reader has attached, laid out, rendered the page for the current viewport,
     * and the viewport has stopped changing (the immersive-bars transition resizes the window
     * shortly after composition). All conditions are about settled state, never about timing.
     */
    private fun awaitReadyAndSettled(readerVm: ReaderViewModel, page: Int = 0): IntSize {
        composeRule.waitUntil(60_000) { readerVm.state != null && readerVm.viewport != null }
        composeRule.waitForIdle()
        val info = readerVm.book!!.pageInfo(page)
        val pageW = info.width.toInt()
        val pageH = info.height.toInt()
        var lastViewport: IntSize? = null
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            val view = readerVm.viewport!!
            val ready = (readerVm.window?.wholeSlot(page) as? PageSlot.Ready)?.size
            if (view == lastViewport && ready == baseSize(pageW, pageH, view.width, view.height, FitMode.Screen)) {
                return checkNotNull(ready)
            }
            lastViewport = view
            Thread.sleep(200)
        }
        throw AssertionError(
            "page $page never settled; viewport=$lastViewport slot=${readerVm.window?.wholeSlot(page)}",
        )
    }

    private fun viewportCentre(readerVm: ReaderViewModel): Offset {
        val view = readerVm.viewport!!
        return Offset(view.width / 2f, view.height / 2f)
    }

    /** The page region the detail render must produce for the given transformation. */
    private fun expectedRegion(
        readerVm: ReaderViewModel,
        page: Int,
        zoom: Float,
        contentLeft: Float,
        contentTop: Float,
    ): PageRegion {
        val slot = readerVm.window!!.wholeSlot(page) as PageSlot.Ready
        val info = readerVm.book!!.pageInfo(page)
        val view = readerVm.viewport!!
        return checkNotNull(
            visibleRegion(
                pageW = info.width.toInt(),
                pageH = info.height.toInt(),
                baseW = slot.size.width,
                baseH = slot.size.height,
                zoom = zoom,
                contentLeft = contentLeft,
                contentTop = contentTop,
                viewW = view.width,
                viewH = view.height,
            ),
        )
    }

    @After
    fun tearDown() {
        mainScope.cancel(CancellationException("test done"))
        // Drop the reader from composition before releasing the VM: the release recycles the
        // base bitmaps, which must not happen while a frame could still draw them (the test
        // rule's recomposition is scheduler-driven and may lag the real frame clock).
        runCatching {
            composeRule.setContent { Box(Modifier.fillMaxSize()) }
            composeRule.waitForIdle()
        }
        vm?.release()
        vm = null
    }

    // --- tests -----------------------------------------------------------------

    @Test
    fun fit_scale_has_no_detail_render() {
        val probe = RecordingProbe()
        val readerVm = launchReader(corpusFile("screentone.cbz"), probe)
        awaitReadyAndSettled(readerVm)
        zoomAutomation() // the zoom system must be present...

        // ...and while the page sits at fit scale, no detail render may run, even well past the
        // 150 ms settle.
        Thread.sleep(1_000)
        composeRule.waitForIdle()
        assertTrue(
            "no detail render may start at fit scale, events=${probe.events()}",
            probe.events().isEmpty(),
        )
        assertTrue(
            "no detail image may be shown at fit scale, images=${probe.images()}",
            probe.images().isEmpty(),
        )
    }

    @Test
    fun double_tap_zooms_and_renders_one_detail() {
        val probe = RecordingProbe()
        val readerVm = launchReader(corpusFile("screentone.cbz"), probe)
        val base = awaitReadyAndSettled(readerVm)

        // Double-tap the centre: zoom to 2x around the tap point.
        composeRule.onNodeWithTag("reader-page-0").performTouchInput { doubleClick(center) }
        composeRule.waitUntil(20_000) { probe.eventCount("done") == 1 }

        assertNoScopeErrors()
        assertEquals("exactly one start/done pair after the settle: ${probe.events()}", listOf("start", "done"), probe.events())

        // The content is centred around the tapped centre, so the visible region's on-screen size
        // is the full viewport, and the detail bitmap must be rendered at exactly that size.
        val view = readerVm.viewport!!
        val expected = expectedRegion(
            readerVm,
            page = 0,
            zoom = 2f,
            contentLeft = (view.width - 2f * base.width) / 2f,
            contentTop = (view.height - 2f * base.height) / 2f,
        )
        val shown = probe.images().single()
        assertEquals("detail bitmap width", expected.outW, checkNotNull(shown).outW)
        assertEquals("detail bitmap height", expected.outH, shown.outH)
    }

    @Test
    fun new_gesture_cancels_pending_detail() {
        val probe = RecordingProbe()
        val readerVm = launchReader(corpusFile("screentone.cbz"), probe)
        awaitReadyAndSettled(readerVm)
        val zoom = zoomAutomation()

        // Two zoom gestures 50 ms apart: the first detail is still pending inside the 150 ms
        // debounce, so it must never render - or be cancelled if it had already started.
        mainScope.launch {
            zoom.zoomTo(2.5f, viewportCentre(readerVm), animate = false)
            delay(50)
            zoom.zoomTo(3f, viewportCentre(readerVm), animate = false)
        }
        composeRule.waitUntil(20_000) { probe.eventCount("done") == 1 }

        assertNoScopeErrors()
        val events = probe.events()
        assertTrue(
            "exactly one detail render must complete and the first must be cancelled or never " +
                "started: $events",
            events == listOf("start", "done") || events == listOf("start", "cancelled", "start", "done"),
        )
    }

    @Test
    fun double_tap_again_returns_to_fit() {
        val probe = RecordingProbe()
        val readerVm = launchReader(corpusFile("screentone.cbz"), probe)
        awaitReadyAndSettled(readerVm)

        composeRule.onNodeWithTag("reader-page-0").performTouchInput { doubleClick(center) }
        composeRule.waitUntil(20_000) { probe.eventCount("done") == 1 }

        // Already zoomed: the second double-tap returns to fit and the detail is cleared.
        composeRule.onNodeWithTag("reader-page-0").performTouchInput { doubleClick(center) }
        composeRule.waitUntil(20_000) { probe.images().lastOrNull() == null }

        assertNoScopeErrors()
        assertEquals("no new detail render at fit scale: ${probe.events()}", 1, probe.eventCount("start"))
        assertEquals(1, probe.eventCount("done"))
        val images = probe.images()
        assertEquals("the detail must have been shown and then cleared: $images", 2, images.size)
        assertTrue("a detail was shown while zoomed", images[0] != null)
    }

    @Test
    fun pan_at_edge_turns_page() {
        val probe = RecordingProbe()
        val readerVm = launchReader(corpusFile("screentone.cbz"), probe)
        val base = awaitReadyAndSettled(readerVm)
        val zoom = zoomAutomation()

        // Zoom to 2x and pan until the content's right edge sits at the viewport's right edge.
        mainScope.launch {
            zoom.zoomTo(2f, viewportCentre(readerVm), animate = false)
            zoom.panBy(Offset(-10_000f, 0f), animate = false)
        }
        // The panned view settled: its detail render covers the right edge of the page.
        val view = readerVm.viewport!!
        val expected = expectedRegion(
            readerVm,
            page = 0,
            zoom = 2f,
            contentLeft = (view.width - 2f * base.width),
            contentTop = (view.height - 2f * base.height) / 2f,
        )
        composeRule.waitUntil(20_000) {
            probe.images().any {
                it != null && it.x == expected.x && it.w == expected.w && it.outW == expected.outW
            }
        }

        // A further swipe must hand the gesture to the pager and turn the page.
        composeRule.onNodeWithTag("reader-page-0").performTouchInput { swipeLeft() }
        composeRule.waitUntil(10_000) { readerVm.state?.page == 1 }
        assertEquals(1, readerVm.state!!.page)
    }

    @Test
    fun zoomed_pan_to_edge_in_one_drag_does_not_turn() {
        val probe = RecordingProbe()
        val readerVm = launchReader(corpusFile("screentone.cbz"), probe)
        awaitReadyAndSettled(readerVm)
        val zoom = zoomAutomation()
        val pageW = readerVm.book!!.pageInfo(0).width.toInt()

        // Zoom to 2x at the centre and let the centred detail settle: the content now overflows
        // the viewport by about 540 px on each side.
        mainScope.launch { zoom.zoomTo(2f, viewportCentre(readerVm), animate = false) }
        composeRule.waitUntil(20_000) { probe.eventCount("done") == 1 }

        // ONE drag of about 920 px, more than the pan room to the right edge: the part past the
        // edge must be absorbed, never handed to the pager within the same gesture.
        composeRule.onNodeWithTag("reader-page-0").performTouchInput {
            swipe(
                start = Offset(0.9f * width, centerY),
                end = Offset(0.05f * width, centerY),
                durationMillis = 400,
            )
        }
        composeRule.waitForIdle()

        // The panned view settled: its detail render touches the page's right edge.
        composeRule.waitUntil(20_000) {
            probe.images().any { it != null && it.x + it.w == pageW }
        }
        // Give a (wrongly) started pager turn time to settle before asserting it did not happen.
        Thread.sleep(1_000)
        composeRule.waitForIdle()
        assertNoScopeErrors()
        assertEquals("one drag must pan to the edge, not turn the page", 0, readerVm.state!!.page)
    }

    @Test
    fun max_zoom_reaches_source_pixels() {
        val probe = RecordingProbe()
        val readerVm = launchReader(corpusFile("huge-single.cbz"), probe)
        awaitReadyAndSettled(readerVm)
        val zoom = zoomAutomation()

        val info = readerVm.book!!.pageInfo(0)
        val pageW = info.width.toInt()
        val pageH = info.height.toInt()
        val base = (readerVm.window!!.wholeSlot(0) as PageSlot.Ready).size
        val maxZoom = max(4f, pageW / base.width.toFloat())

        // Zoom far past the limit; the state coerces to the spec maximum.
        mainScope.launch { zoom.zoomTo(100f, viewportCentre(readerVm), animate = false) }
        composeRule.waitUntil(20_000) { probe.eventCount("done") == 1 }

        assertNoScopeErrors()
        val region = checkNotNull(probe.images().single()) { "a detail must be rendered at max zoom" }
        // At the maximum zoom every screen pixel shows at most one source pixel: the detail is
        // rendered no larger than the source pixels it covers, i.e. source pixels are reached.
        assertTrue("w=${region.w} must be <= outW=${region.outW}", region.w <= region.outW)
        assertTrue("h=${region.h} must be <= outH=${region.outH}", region.h <= region.outH)
        val sx = pageW.toFloat() / base.width
        val achieved = sx * region.outW / region.w
        assertEquals("achieved zoom must equal the spec maximum", maxZoom, achieved, 0.15f)
    }

    @Test
    fun failed_detail_render_degrades_instead_of_crashing() {
        val probe = RecordingProbe()
        val readerVm = launchReader(corpusFile("screentone.cbz"), probe)
        awaitReadyAndSettled(readerVm)
        val zoom = zoomAutomation()

        // Fail a zoomed detail render deterministically: close the book underneath the reader as
        // soon as the detail render has allocated its hardware target, so the in-flight render
        // fails with InvalidHandle - the same non-cancellation failure the base pipeline degrades
        // to PageSlot.Failed for. The reader must degrade instead of crashing.
        val allocated = CompletableDeferred<Unit>()
        val bookClosed = CompletableDeferred<Unit>()
        readerVm.renderer!!.onTargetAllocated = {
            allocated.complete(Unit)
            runBlocking { bookClosed.await() }
        }
        mainScope.launch { zoom.zoomTo(2f, viewportCentre(readerVm), animate = false) }
        composeRule.waitUntil(20_000) { allocated.isCompleted }
        readerVm.book!!.close()
        bookClosed.complete(Unit) // let the render proceed against the closed book
        composeRule.waitUntil(20_000) { probe.eventCount("cancelled") >= 1 }

        assertNoScopeErrors()
        // The failed render was surfaced through the pinned probe vocabulary...
        assertEquals(1, probe.eventCount("start"))
        // ...and the process survived: the reader is still composed and interactive.
        composeRule.onNodeWithTag("reader-root").assertExists()
    }
}
