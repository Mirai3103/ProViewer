package com.proviewer.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proviewer.app.bench.CorpusGenerator
import com.proviewer.app.engine.NativeBook
import com.proviewer.app.open.OpenResult
import com.proviewer.app.reader.FitMode
import com.proviewer.app.reader.PageSlot
import com.proviewer.app.reader.ReaderAutomation
import com.proviewer.app.reader.ReaderScreen
import com.proviewer.app.reader.ReaderViewModel
import com.proviewer.app.reader.ZoomAutomation
import com.proviewer.app.reader.baseSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.proviewer_core.releaseBookHandle
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end reader UI tests. Books are opened through [TestCbzProvider] (seekable content URIs)
 * or through a `file://` URI into the app cache (corrupt / tall / huge corpus), and the reader
 * state is observed through [MainActivity.readerViewModel] — the test-only seam this task exposes.
 */
@RunWith(AndroidJUnit4::class)
class ReaderScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // --- helpers ---------------------------------------------------------------

    private fun mixedSmallUri(): Uri =
        Uri.parse("content://${TestCbzProvider.AUTHORITY}/seekable/mixed-small.cbz")

    private fun intent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setDataAndType(uri, "application/zip")
    }

    private fun launch(uri: Uri): ActivityScenario<MainActivity> = ActivityScenario.launch(intent(uri))

    private fun grabVm(scenario: ActivityScenario<MainActivity>): ReaderViewModel {
        val holder = arrayOfNulls<ReaderViewModel>(1)
        scenario.onActivity { holder[0] = it.readerViewModel }
        return holder[0]!!
    }

    /**
     * Waits until the reader is attached (state published) and has laid out (onViewport recorded).
     * The 60 s budgets tolerate environment stalls observed on this emulator: Android's cached-app
     * freezer can hold the app process for tens of seconds during the rapid activity churn between
     * tests, and a frozen main thread makes no progress until the freezer releases it (the condition
     * is about correctness, never about timing).
     */
    private fun awaitAttached(vm: ReaderViewModel) {
        composeRule.waitUntil(60_000) { vm.state != null }
        composeRule.waitForIdle()
        composeRule.waitUntil(60_000) { vm.viewport != null }
    }

    private fun readySize(vm: ReaderViewModel, page: Int): IntSize? =
        (vm.window?.wholeSlot(page) as? PageSlot.Ready)?.size

    private fun waitReadySize(vm: ReaderViewModel, page: Int, expected: IntSize, timeout: Long = 30_000) {
        composeRule.waitUntil(timeout) { readySize(vm, page) == expected }
    }

    private fun waitReady(vm: ReaderViewModel, page: Int): IntSize {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val slot = vm.window?.wholeSlot(page)
            if (slot is PageSlot.Ready) return slot.size
            composeRule.waitForIdle()
            Thread.sleep(25)
        }
        val slot = vm.window?.wholeSlot(page)
        throw AssertionError("page $page never became Ready; slot=$slot, state=${vm.state}")
    }

    private fun tapPage(page: Int, fx: Float, fy: Float) {
        composeRule.onNodeWithTag("reader-page-$page").performTouchInput {
            click(Offset(visibleSize.width * fx, visibleSize.height * fy))
        }
    }

    private fun goTo(vm: ReaderViewModel, page: Int) {
        composeRule.runOnIdle { vm.goTo(page) }
    }

    private fun corpusFile(name: String, scale: Float): File {
        val dir = File(context.cacheDir, "reader-screen-corpus").apply { mkdirs() }
        return runBlocking { CorpusGenerator.ensure(dir, scale, listOf(name)) }.single()
    }

    // --- tests -----------------------------------------------------------------

    @Test
    fun tap_zones_ltr() {
        launch(mixedSmallUri()).use { scenario ->
            val vm = grabVm(scenario)
            awaitAttached(vm)
            waitReady(vm, 0)

            // Right third -> next (page 1).
            tapPage(0, 0.9f, 0.5f)
            composeRule.waitUntil(10_000) { vm.state?.page == 1 }
            assertEquals(1, vm.state!!.page)

            // Left third -> previous (page 0).
            tapPage(1, 0.1f, 0.5f)
            composeRule.waitUntil(10_000) { vm.state?.page == 0 }
            assertEquals(0, vm.state!!.page)
        }
    }

    @Test
    fun tap_zones_rtl() {
        launch(mixedSmallUri()).use { scenario ->
            val vm = grabVm(scenario)
            awaitAttached(vm)
            waitReady(vm, 0)

            // Switch to RTL through the overlay.
            tapPage(0, 0.5f, 0.5f)
            composeRule.waitUntil(10_000) { vm.state?.overlayVisible == true }
            composeRule.onNodeWithTag("reader-dir").performClick()
            composeRule.waitUntil(10_000) { vm.state?.direction == com.proviewer.app.reader.Direction.Rtl }
            tapPage(0, 0.5f, 0.5f) // close the overlay
            composeRule.waitUntil(10_000) { vm.state?.overlayVisible == false }

            // In RTL, left third -> next. Go to page 1.
            tapPage(0, 0.1f, 0.5f)
            composeRule.waitUntil(10_000) { vm.state?.page == 1 }
            assertEquals(1, vm.state!!.page)

            // In RTL, right third -> previous. Back to page 0.
            tapPage(1, 0.9f, 0.5f)
            composeRule.waitUntil(10_000) { vm.state?.page == 0 }
            assertEquals(0, vm.state!!.page)
        }
    }

    @Test
    fun volume_keys_turn_pages() {
        launch(mixedSmallUri()).use { scenario ->
            val vm = grabVm(scenario)
            awaitAttached(vm)
            waitReady(vm, 0)

            scenario.onActivity { activity ->
                val down = activity.onKeyDown(
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_DOWN),
                )
                assertTrue("volume down must be consumed while reading", down)
            }
            composeRule.waitUntil(10_000) { vm.state?.page == 1 }
            assertEquals(1, vm.state!!.page)

            scenario.onActivity { activity ->
                val up = activity.onKeyDown(
                    android.view.KeyEvent.KEYCODE_VOLUME_UP,
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP),
                )
                assertTrue("volume up must be consumed while reading", up)
            }
            composeRule.waitUntil(10_000) { vm.state?.page == 0 }
            assertEquals(0, vm.state!!.page)
        }
    }

    @Test
    fun swipe_turns_page_in_reading_direction() {
        launch(mixedSmallUri()).use { scenario ->
            val vm = grabVm(scenario)
            awaitAttached(vm)
            waitReady(vm, 0)

            // LTR: a swipe left (finger moves left) advances to the next page.
            composeRule.onNodeWithTag("reader-page-0").performTouchInput { swipeLeft() }
            composeRule.waitUntil(10_000) { vm.state?.page == 1 }
            assertEquals(1, vm.state!!.page)
        }
    }

    @Test
    fun centre_tap_toggles_overlay() {
        launch(mixedSmallUri()).use { scenario ->
            val vm = grabVm(scenario)
            awaitAttached(vm)
            waitReady(vm, 0)

            composeRule.onNodeWithTag("reader-overlay").assertDoesNotExist()

            tapPage(0, 0.5f, 0.5f)
            composeRule.waitUntil(10_000) { vm.state?.overlayVisible == true }
            composeRule.onNodeWithTag("reader-overlay").assertExists()

            tapPage(0, 0.5f, 0.5f)
            composeRule.waitUntil(10_000) { vm.state?.overlayVisible == false }
            composeRule.onNodeWithTag("reader-overlay").assertDoesNotExist()
        }
    }

    @Test
    fun slider_jumps() {
        launch(mixedSmallUri()).use { scenario ->
            val vm = grabVm(scenario)
            awaitAttached(vm)
            waitReady(vm, 0)

            tapPage(0, 0.5f, 0.5f)
            composeRule.waitUntil(10_000) { vm.state?.overlayVisible == true }

            composeRule.onNodeWithTag("reader-slider")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
            composeRule.waitUntil(10_000) { vm.state?.page == 10 }
            assertEquals(10, vm.state!!.page)
        }
    }

    @Test
    fun fit_mode_change_rerenders_base() {
        launch(mixedSmallUri()).use { scenario ->
            val vm = grabVm(scenario)
            awaitAttached(vm)
            val screenSize = waitReady(vm, 0)

            val viewport = vm.viewport!!
            val pageInfo = vm.book!!.pageInfo(0)
            val pageW = pageInfo.width.toInt()
            val pageH = pageInfo.height.toInt()

            assertEquals(baseSize(pageW, pageH, viewport.width, viewport.height, FitMode.Screen), screenSize)

            // Open the overlay and switch to Width.
            tapPage(0, 0.5f, 0.5f)
            composeRule.waitUntil(10_000) { vm.state?.overlayVisible == true }
            composeRule.onNodeWithTag("reader-fit").performClick()
            composeRule.waitUntil(10_000) { vm.state?.fitMode == FitMode.Width }
            waitReadySize(vm, 0, baseSize(pageW, pageH, viewport.width, viewport.height, FitMode.Width))

            // Switch to Height: for a portrait page on a portrait viewport this actually changes the
            // base size, proving the fit-mode change re-rendered.
            composeRule.onNodeWithTag("reader-fit").performClick()
            composeRule.waitUntil(10_000) { vm.state?.fitMode == FitMode.Height }
            val heightSize = baseSize(pageW, pageH, viewport.width, viewport.height, FitMode.Height)
            waitReadySize(vm, 0, heightSize)
            assertTrue("height base must differ from screen base", heightSize != screenSize)
        }
    }

    @Test
    fun fit_width_tall_page_starts_at_origin_and_pans_to_bottom() {
        // 1000x3000 -> fit width 1080x3240 (below the base caps, so display == size).
        withOverflowReader("fit-width-tall.cbz", 1000, 3000, FitMode.Width) { base, zoom ->
            assertBlocksMatch(
                "top-left of the screen vs the base's top-left",
                readBlock(composeRule.onRoot().captureToImage(), 0, 0),
                baseBlock(base, 0, 0),
            )

            // Pan far past the bottom: the page's bottom edge must come to rest at the viewport's.
            panAndSettle(zoom, Offset(0f, -10_000f))
            val image = composeRule.onRoot().captureToImage()
            assertBlocksMatch(
                "bottom-left of the screen vs the base's bottom-left",
                readBlock(image, 0, image.height - BLOCK),
                baseBlock(base, 0, base.height - BLOCK),
            )
        }
    }

    @Test
    fun fit_height_wide_page_starts_at_origin_and_pans_to_right() {
        // 1600x1200 -> fit height 3171x2378 on the 1080x2378 viewport (not capped).
        withOverflowReader("fit-height-wide.cbz", 1600, 1200, FitMode.Height) { base, zoom ->
            assertBlocksMatch(
                "top-left of the screen vs the base's top-left",
                readBlock(composeRule.onRoot().captureToImage(), 0, 0),
                baseBlock(base, 0, 0),
            )

            // Pan far past the right: the page's right edge must come to rest at the viewport's.
            panAndSettle(zoom, Offset(-10_000f, 0f))
            val image = composeRule.onRoot().captureToImage()
            assertBlocksMatch(
                "top-right of the screen vs the base's top-right",
                readBlock(image, image.width - BLOCK, 0),
                baseBlock(base, base.width - BLOCK, 0),
            )
        }
    }

    @Test
    fun corrupt_page_shows_placeholder_and_next_loads() {
        val cbz = buildCorruptCbz()
        launch(Uri.fromFile(cbz)).use { scenario ->
            val vm = grabVm(scenario)
            awaitAttached(vm)
            waitReady(vm, 0)

            // The corrupt page (1) is rendered as a Failed slot in the prefetch window.
            composeRule.waitUntil(10_000) { vm.window?.wholeSlot(1) is PageSlot.Failed }

            // Navigate to it: the placeholder must be shown.
            goTo(vm, 1)
            composeRule.waitUntil(10_000) { vm.state?.page == 1 }
            composeRule.onNodeWithTag("reader-error-1").assertExists()

            // The next page (2) still loads.
            goTo(vm, 2)
            waitReady(vm, 2)
        }
    }

    @Test
    fun probe_failed_page_shows_placeholder_and_next_loads() {
        val cbz = buildProbeFailCbz()
        launch(Uri.fromFile(cbz)).use { scenario ->
            val vm = grabVm(scenario)
            awaitAttached(vm)
            waitReady(vm, 0)

            // The size sweep fails on the header-broken page (1): no window target can be built
            // for it, so the reader must fall back to the error placeholder, not a blank box.
            composeRule.waitUntil(30_000) { vm.sizes?.failed(1) == true }
            assertNull("a probe-failed page can never get a window slot", vm.window?.wholeSlot(1))

            // Navigate to it: the placeholder must be shown.
            goTo(vm, 1)
            composeRule.waitUntil(10_000) { vm.state?.page == 1 }
            composeRule.onNodeWithTag("reader-error-1").assertExists()

            // The next page (2) still loads.
            goTo(vm, 2)
            waitReady(vm, 2)
        }
    }

    @Test
    fun rotation_keeps_page_and_rerenders() {
        launch(mixedSmallUri()).use { scenario ->
            var vm = grabVm(scenario)
            awaitAttached(vm)
            waitReady(vm, 0)

            goTo(vm, 3)
            composeRule.waitUntil(10_000) { vm.state?.page == 3 }
            waitReady(vm, 3)
            // Wait for the page-turn animation to settle so the pager's saved position is 3.
            composeRule.waitUntil(10_000) { vm.settledPage == 3 }

            // Rotate to landscape (viewport swap) and recreate.
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            composeRule.waitForIdle()
            scenario.recreate()
            composeRule.waitForIdle()

            vm = grabVm(scenario)

            // The page must be kept across the rotation.
            composeRule.waitUntil(20_000) { vm.state?.page == 3 }
            assertEquals(3, vm.state!!.page)

            // The base must be re-rendered for the new (landscape) viewport.
            composeRule.waitUntil(20_000) { vm.viewport?.let { it.width > it.height } == true }
            val viewport = vm.viewport!!
            assertTrue("viewport must be landscape after rotation", viewport.width > viewport.height)
            val pageInfo = vm.book!!.pageInfo(3)
            val expected = baseSize(
                pageInfo.width.toInt(), pageInfo.height.toInt(),
                viewport.width, viewport.height, FitMode.Screen,
            )
            waitReadySize(vm, 3, expected)
        }
    }

    @Test
    fun tall_page_fit_width_displays() {
        val file = corpusFile("webtoon-long.cbz", 0.1f)
        launch(Uri.fromFile(file)).use { scenario ->
            val vm = grabVm(scenario)
            awaitAttached(vm)
            waitReady(vm, 0)

            // Switch to fit-width.
            tapPage(0, 0.5f, 0.5f)
            composeRule.waitUntil(10_000) { vm.state?.overlayVisible == true }
            composeRule.onNodeWithTag("reader-fit").performClick()
            composeRule.waitUntil(10_000) { vm.state?.fitMode == FitMode.Width }

            val viewport = vm.viewport!!
            val info = vm.book!!.pageInfo(0)
            val expected = baseSize(info.width.toInt(), info.height.toInt(), viewport.width, viewport.height, FitMode.Width)
            waitReadySize(vm, 0, expected)
        }
    }

    @Test
    fun back_during_render_releases_cleanly() {
        val file = corpusFile("huge-single.cbz", 0.25f)
        launch(Uri.fromFile(file)).use { scenario ->
            val vm = grabVm(scenario)

            // Wait only for attach (the render may still be in flight).
            composeRule.waitUntil(10_000) { vm.state != null }
            composeRule.onNodeWithTag("reader-root").assertExists()

            val book = vm.book!!
            val handle = book.handle

            // Press back immediately while the render is cold.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            // The book must close (handle released and Book destroyed).
            composeRule.waitUntil(10_000) { book.book.uniffiIsDestroyed }

            // The handle registry no longer holds this handle.
            assertFalse(releaseBookHandle(handle.toULong()))

            // The reader is fully torn down.
            assertTrue(vm.state == null)
            assertTrue(vm.window == null)
        }
    }

    // --- fit overflow helpers ----------------------------------------------------

    /**
     * Opens a one-page CBZ whose single JPEG is [w] x [h] in a plain [ComponentActivity] hosting
     * [ReaderScreen] (so the test can capture the [ReaderAutomation]), switches to [mode], waits
     * until the base for the settled viewport is Ready, then runs [block] with the base bitmap
     * (a software copy) and the page's [ZoomAutomation].
     */
    private fun withOverflowReader(
        fileName: String,
        w: Int,
        h: Int,
        mode: FitMode,
        block: (base: Bitmap, zoom: ZoomAutomation) -> Unit,
    ) {
        val file = buildPatternCbz(fileName, w, h)
        val app = context.applicationContext as ProViewerApp
        val readerVm = ReaderViewModel()
        val automation = CompletableDeferred<ReaderAutomation>()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    ReaderScreen(vm = readerVm, onBack = {}, onAutomation = { automation.complete(it) })
                }
            }
            val book = NativeBook(app.engine.openPath(file.absolutePath), copiedFile = null)
            composeRule.runOnIdle { readerVm.attach(OpenResult.Opened(book, file.name)) }
            composeRule.waitUntil(60_000) { readerVm.state != null && readerVm.viewport != null }
            composeRule.runOnIdle { readerVm.setFitMode(mode) }

            // Wait for the base of the settled viewport (the immersive-bars transition resizes the
            // window shortly after composition): a stable viewport and a Ready slot of its size.
            var lastViewport: IntSize? = null
            var ready: PageSlot.Ready? = null
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline && ready == null) {
                composeRule.waitForIdle()
                val view = readerVm.viewport!!
                val slot = readerVm.window?.wholeSlot(0) as? PageSlot.Ready
                if (view == lastViewport && readerVm.state?.fitMode == mode && slot != null &&
                    slot.size == baseSize(w, h, view.width, view.height, mode)
                ) {
                    ready = slot
                }
                lastViewport = view
                if (ready == null) Thread.sleep(200)
            }
            val slot = checkNotNull(ready) {
                "page never became Ready for $mode; viewport=$lastViewport slot=${readerVm.window?.wholeSlot(0)}"
            }
            assertEquals("the fit must not be memory-capped for this test", slot.size, slot.display)
            composeRule.waitForIdle()

            val base = checkNotNull(slot.image.bitmap).copy(Bitmap.Config.ARGB_8888, false)
            val readerAutomation = runBlocking { withTimeout(30_000) { automation.await() } }
            var zoom: ZoomAutomation? = null
            composeRule.waitUntil(30_000) {
                zoom = readerAutomation.zoom
                zoom != null
            }
            block(base, checkNotNull(zoom))
        } finally {
            scenario.close()
            readerVm.release()
        }
    }

    /** Pans by [delta] without animation and waits until the composition has drawn the result. */
    private fun panAndSettle(zoom: ZoomAutomation, delta: Offset) {
        // Dispatched from a main-thread scope and awaited by polling, so a pan that never
        // completes fails the test with a message instead of blocking the instrumentation thread.
        val failure = arrayOfNulls<Throwable>(1)
        val job = CoroutineScope(Dispatchers.Main).launch {
            try {
                zoom.panBy(delta, animate = false)
            } catch (e: Throwable) {
                failure[0] = e
            }
        }
        composeRule.waitUntil(15_000) { job.isCompleted }
        failure[0]?.let { throw AssertionError("panBy failed", it) }
        composeRule.waitForIdle()
        Thread.sleep(300) // let the frame (and any detail render) settle
        composeRule.waitForIdle()
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

    /** Per-channel comparison of two packed ARGB blocks (screen vs base), tolerance [TOLERANCE]. */
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

    /**
     * A one-page CBZ of a [w] x [h] JPEG made of 4 px cells with pseudo-random colours, so any
     * placement offset of the drawn page shows up as a pixel mismatch in a small block.
     */
    private fun buildPatternCbz(name: String, w: Int, h: Int): File {
        val dir = File(context.cacheDir, "reader-screen-corpus").apply { mkdirs() }
        val file = File(dir, name)
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
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.jpg")); zip.write(jpeg); zip.closeEntry()
        }
        return file
    }

    private companion object {
        const val BLOCK = 8
        const val TOLERANCE = 2
    }

    // --- corrupt CBZ builder ---------------------------------------------------

    /** A CBZ with a valid `001.jpg`, a fatally-corrupt `002.jpg`, and a valid `003.jpg`. */
    private fun buildCorruptCbz(): File {
        val dir = File(context.cacheDir, "reader-screen-corpus").apply { mkdirs() }
        val file = File(dir, "corrupt.cbz")
        val valid = validJpegBytes()
        val corrupt = corruptJpeg(valid)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.jpg")); zip.write(valid); zip.closeEntry()
            zip.putNextEntry(ZipEntry("002.jpg")); zip.write(corrupt); zip.closeEntry()
            zip.putNextEntry(ZipEntry("003.jpg")); zip.write(valid); zip.closeEntry()
        }
        return file
    }

    /** A CBZ with a valid `001.jpg`, a header-broken `002.jpg` (its size probe fails), and a valid `003.jpg`. */
    private fun buildProbeFailCbz(): File {
        val dir = File(context.cacheDir, "reader-screen-corpus").apply { mkdirs() }
        val file = File(dir, "probe-fail.cbz")
        val valid = validJpegBytes()
        val headerBroken = ByteArray(64) // not an image: the size probe itself fails
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.jpg")); zip.write(valid); zip.closeEntry()
            zip.putNextEntry(ZipEntry("002.jpg")); zip.write(headerBroken); zip.closeEntry()
            zip.putNextEntry(ZipEntry("003.jpg")); zip.write(valid); zip.closeEntry()
        }
        return file
    }

    private fun validJpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF808080.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /**
     * Corrupts a valid JPEG so its header still parses (page size probe succeeds) but its decode
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
}
