package com.proviewer.app

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proviewer.app.bench.CorpusGenerator
import com.proviewer.app.engine.NativeBook
import com.proviewer.app.engine.PageImage
import com.proviewer.app.engine.PageRenderer
import com.proviewer.app.engine.UploadStrategy
import com.proviewer.app.reader.FitMode
import com.proviewer.app.reader.PageRegion
import com.proviewer.app.reader.baseSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.proviewer_core.Engine
import uniffi.proviewer_core.defaultEngineConfig
import java.io.File
import kotlin.math.min

/**
 * Exercises [PageRenderer] end to end against the JNI pixel path, using `CorpusGenerator` (debug
 * variant) into the app cache and `engine.openPath`.
 */
@RunWith(AndroidJUnit4::class)
class PageRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Corpus archives at [scale]; each scale has its own directory so their stamps never clash. */
    private suspend fun corpusFile(name: String, scale: Float = 0.5f): File {
        val dir = File(context.cacheDir, if (scale == 0.5f) "page-renderer-corpus" else "page-renderer-corpus-$scale")
            .apply { mkdirs() }
        return CorpusGenerator.ensure(dir, scale, listOf(name)).single()
    }

    private suspend fun <T> withRenderer(
        strategy: UploadStrategy,
        corpus: String,
        scale: Float = 0.5f,
        block: suspend (NativeBook, PageRenderer) -> T,
    ): T {
        val engine = Engine(defaultEngineConfig())
        try {
            val book = NativeBook(engine.openPath(corpusFile(corpus, scale).absolutePath), copiedFile = null)
            try {
                return block(book, PageRenderer(book, strategy))
            } finally {
                book.close()
            }
        } finally {
            engine.destroy()
        }
    }

    @Test
    fun hardware_strategy_renders() = runBlocking {
        withRenderer(UploadStrategy.Hardware, "mixed-small.cbz") { book, renderer ->
            val info = book.pageInfo(0)
            val size = baseSize(info.width.toInt(), info.height.toInt(), 1080, 2378, FitMode.Screen)

            val image = renderer.renderRegion(0, PageRegion(0, 0, info.width.toInt(), info.height.toInt(), size.width, size.height))

            assertFalse("hardware render should not fall back", renderer.fellBack)
            val bitmap = checkNotNull(image.bitmap)
            assertEquals(size.width, bitmap.width)
            assertEquals(size.height, bitmap.height)
            assertEquals(1, renderer.openHardwareBuffers)
            image.release()
            assertEquals(0, renderer.openHardwareBuffers)
        }
    }

    @Test
    fun software_strategy_renders() = runBlocking {
        withRenderer(UploadStrategy.Software, "mixed-small.cbz") { book, renderer ->
            val info = book.pageInfo(0)
            val size = baseSize(info.width.toInt(), info.height.toInt(), 1080, 2378, FitMode.Screen)

            val image = renderer.renderRegion(0, PageRegion(0, 0, info.width.toInt(), info.height.toInt(), size.width, size.height))

            val bitmap = checkNotNull(image.bitmap)
            assertEquals(size.width, bitmap.width)
            assertEquals(size.height, bitmap.height)
            image.release()
        }
    }

    @Test
    fun cancel_releases_target() = runBlocking {
        withRenderer(UploadStrategy.Hardware, "huge-single.cbz") { book, renderer ->
            val info = book.pageInfo(0) // 6000x4000 at scale 0.5
            val w = info.width.toInt()
            val h = info.height.toInt()
            // Render at a size just over half the source, forcing a full (denom 1) decode that is
            // slow enough to cancel mid-flight after the hardware buffer has been allocated.
            val outW = min(w, 3200)
            val outH = (h.toLong() * outW / w).toInt()

            // Deterministic: wait on the allocation hook (fired right after the hardware target is
            // allocated, before the JNI render) instead of polling the counter.
            val allocated = CompletableDeferred<Unit>()
            renderer.onTargetAllocated = { allocated.complete(Unit) }

            var image: PageImage? = null
            val deferred = async(Dispatchers.Default) {
                renderer.render(0, PageRegion(0, 0, w, h, outW, outH)).also { image = it }
            }
            try {
                withTimeout(20_000) { allocated.await() }
                deferred.cancelAndJoin()
            } finally {
                // Defensive: if the render somehow completed before cancellation, release its image
                // so the buffer counter can never leak (the normal path cancels during the decode).
                image?.release()
            }
            assertEquals(0, renderer.openHardwareBuffers)
        }
    }

    /** The ARGB pixels of [image] (a hardware bitmap is copied to software first); releases the image. */
    private fun pixelsOf(image: PageImage): IntArray {
        try {
            val bitmap = checkNotNull(image.bitmap)
            val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            try {
                return IntArray(soft.width * soft.height).also { soft.getPixels(it, 0, soft.width, 0, 0, soft.width, soft.height) }
            } finally {
                if (soft !== bitmap) soft.recycle()
            }
        } finally {
            image.release()
        }
    }

    /** Largest per-channel difference between two equally sized ARGB arrays. */
    private fun maxChannelDiff(a: IntArray, b: IntArray): Int {
        assertEquals(a.size, b.size)
        var worst = 0
        for (i in a.indices) {
            for (shift in intArrayOf(0, 8, 16, 24)) {
                val d = kotlin.math.abs(((a[i] ushr shift) and 0xFF) - ((b[i] ushr shift) and 0xFF))
                if (d > worst) worst = d
            }
        }
        return worst
    }

    @Test
    fun scaled_tiles_match_one_render() = runBlocking {
        for (strategy in UploadStrategy.entries) {
            withRenderer(strategy, "webtoon-long.cbz", scale = 0.1f) { book, renderer ->
                val info = book.pageInfo(0)
                assertEquals(80, info.width.toInt())
                assertEquals(2400, info.height.toInt())
                val full = IntSize(216, 6480)
                val top = pixelsOf(renderer.renderScaled(0, full, IntRect(0, 0, 216, 1000)))
                val bottom = pixelsOf(renderer.renderScaled(0, full, IntRect(0, 1000, 216, 2000)))
                val both = pixelsOf(renderer.renderScaled(0, full, IntRect(0, 0, 216, 2000)))
                assertFalse("$strategy render should not fall back", renderer.fellBack)

                val half = 216 * 1000
                val worstTop = maxChannelDiff(top, both.copyOfRange(0, half))
                val worstBottom = maxChannelDiff(bottom, both.copyOfRange(half, 2 * half))
                // Within the spec's seam tolerance: at most 1 level per channel, no gap or row shift.
                assertTrue("$strategy top tile differs by $worstTop", worstTop <= 1)
                assertTrue("$strategy bottom tile differs by $worstBottom", worstBottom <= 1)
                assertEquals(0, renderer.openHardwareBuffers)
            }
        }
    }
}
