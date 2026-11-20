package com.proviewer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proviewer.app.bench.CorpusGenerator
import com.proviewer.app.engine.NativeBook
import com.proviewer.app.engine.PageRenderer
import com.proviewer.app.engine.UploadStrategy
import com.proviewer.app.reader.FitMode
import com.proviewer.app.reader.PageRegion
import com.proviewer.app.reader.baseSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    private suspend fun corpusFile(name: String): File {
        val dir = File(context.cacheDir, "page-renderer-corpus").apply { mkdirs() }
        return CorpusGenerator.ensure(dir, 0.5f, listOf(name)).single()
    }

    private suspend fun <T> withRenderer(
        strategy: UploadStrategy,
        corpus: String,
        block: suspend (NativeBook, PageRenderer) -> T,
    ): T {
        val engine = Engine(defaultEngineConfig())
        try {
            val book = NativeBook(engine.openPath(corpusFile(corpus).absolutePath), copiedFile = null)
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

            val image = renderer.renderBase(0, size)

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

            val image = renderer.renderBase(0, size)

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

            // Launch on Default so the render is not starved by the poll loop below (which blocks
            // the runBlocking event-loop thread).
            val job = launch(Dispatchers.Default) { renderer.render(0, PageRegion(0, 0, w, h, outW, outH)) }
            val deadline = System.currentTimeMillis() + 20_000
            while (renderer.openHardwareBuffers == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(1)
            }
            assertTrue("render never allocated a hardware buffer", renderer.openHardwareBuffers > 0)

            job.cancelAndJoin()
            assertEquals(0, renderer.openHardwareBuffers)
        }
    }
}
