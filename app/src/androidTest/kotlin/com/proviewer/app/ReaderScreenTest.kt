package com.proviewer.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proviewer.app.bench.CorpusGenerator
import com.proviewer.app.reader.FitMode
import com.proviewer.app.reader.PageSlot
import com.proviewer.app.reader.ReaderViewModel
import com.proviewer.app.reader.baseSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        (vm.window?.pages?.get(page) as? PageSlot.Ready)?.size

    private fun waitReadySize(vm: ReaderViewModel, page: Int, expected: IntSize, timeout: Long = 30_000) {
        composeRule.waitUntil(timeout) { readySize(vm, page) == expected }
    }

    private fun waitReady(vm: ReaderViewModel, page: Int): IntSize {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val slot = vm.window?.pages?.get(page)
            if (slot is PageSlot.Ready) return slot.size
            composeRule.waitForIdle()
            Thread.sleep(25)
        }
        val slot = vm.window?.pages?.get(page)
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
    fun corrupt_page_shows_placeholder_and_next_loads() {
        val cbz = buildCorruptCbz()
        launch(Uri.fromFile(cbz)).use { scenario ->
            val vm = grabVm(scenario)
            awaitAttached(vm)
            waitReady(vm, 0)

            // The corrupt page (1) is rendered as a Failed slot in the prefetch window.
            composeRule.waitUntil(10_000) { vm.window?.pages?.get(1) is PageSlot.Failed }

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
