package com.proviewer.app

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proviewer.app.bench.CorpusGenerator
import com.proviewer.app.engine.NativePixels
import com.proviewer.app.engine.NativeRenderException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.proviewer_core.Book
import uniffi.proviewer_core.Engine
import uniffi.proviewer_core.defaultEngineConfig
import uniffi.proviewer_core.releaseBookHandle
import java.io.File

/**
 * Exercises the JNI pixel path (`NativePixels`) against `formats-and-names.cbz` generated at scale
 * 0.5 (pages 200×300), so the brief's region (10,10,50,50) fits the page.
 */
@RunWith(AndroidJUnit4::class)
class NativePixelsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun corpusFile(): File {
        val dir = File(context.cacheDir, "native-pixels-corpus").apply { mkdirs() }
        return runBlocking { CorpusGenerator.ensure(dir, 0.5f, listOf("formats-and-names.cbz")) }
            .single()
    }

    private fun <T> withBook(block: (book: Book, handle: Long) -> T): T {
        val engine = Engine(defaultEngineConfig())
        return try {
            val book = engine.openPath(corpusFile().absolutePath)
            try {
                val handle = book.nativeHandle().toLong()
                try {
                    block(book, handle)
                } finally {
                    releaseBookHandle(handle.toULong())
                }
            } finally {
                book.destroy()
            }
        } finally {
            engine.destroy()
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val out = IntArray(w * h)
        bitmap.getPixels(out, 0, w, 0, 0, w, h)
        return out
    }

    private fun channelDiff(a: Int, b: Int): Int {
        var d = 0
        for (shift in intArrayOf(16, 8, 0)) { // R, G, B
            d = maxOf(d, kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)))
        }
        return d
    }

    // Step 1 gate: an emulator that cannot allocate and wrap a CPU-writable, GPU-sampled
    // HardwareBuffer means the H-path tests would have to be skipped.
    @Test
    fun hardware_buffer_is_writable_and_wrappable() {
        val buffer = HardwareBuffer.create(
            64,
            64,
            HardwareBuffer.RGBA_8888,
            1,
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
        assertNotNull("HardwareBuffer.create returned null", buffer)
        try {
            val bitmap = checkNotNull(
                Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)),
            ) { "Bitmap.wrapHardwareBuffer returned null" }
            bitmap.recycle()
        } finally {
            buffer.close()
        }
    }

    @Test
    fun software_and_hardware_outputs_match() = withBook { book, handle ->
        val info = book.pageInfo(0u)
        assertEquals(200u, info.width)
        assertEquals(300u, info.height)
        val w = info.width.toInt()
        val h = info.height.toInt()

        val software = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        NativePixels.renderToBitmap(handle, 0, 0, 0, w, h, software, 0L)

        val buffer = HardwareBuffer.create(
            w,
            h,
            HardwareBuffer.RGBA_8888,
            1,
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
        assertNotNull(buffer)
        try {
            NativePixels.renderToHardwareBuffer(handle, 0, 0, 0, w, h, buffer, 0L)
            val hwBitmap = checkNotNull(
                Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)),
            ) { "Bitmap.wrapHardwareBuffer returned null" }
            val copied = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
            assertArrayEquals(pixels(software), pixels(copied))
            copied.recycle()
            hwBitmap.recycle()
        } finally {
            buffer.close()
        }
        software.recycle()
    }

    @Test
    fun output_is_not_blank() = withBook { book, handle ->
        val info = book.pageInfo(0u)
        val w = info.width.toInt()
        val h = info.height.toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        NativePixels.renderToBitmap(handle, 0, 0, 0, w, h, bitmap, 0L)
        val px = pixels(bitmap)
        val minLuma = px.minOf { it and 0xFF }
        val maxLuma = px.maxOf { it and 0xFF }
        assertTrue("output is blank (min luma $minLuma)", minLuma < 100)
        assertTrue("output is blank (max luma $maxLuma)", maxLuma > 100)
        bitmap.recycle()
    }

    @Test
    fun region_at_source_scale_matches_crop() = withBook { book, handle ->
        val info = book.pageInfo(0u)
        val w = info.width.toInt() // 200
        val h = info.height.toInt() // 300

        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        NativePixels.renderToBitmap(handle, 0, 0, 0, w, h, full, 0L)
        val fullPixels = pixels(full)

        val region = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        NativePixels.renderToBitmap(handle, 0, 10, 10, 50, 50, region, 0L)
        val regionPixels = pixels(region)

        var maxDiff = 0
        for (yy in 0 until 50) {
            for (xx in 0 until 50) {
                val src = fullPixels[(10 + yy) * w + (10 + xx)]
                val dst = regionPixels[yy * 50 + xx]
                maxDiff = maxOf(maxDiff, channelDiff(src, dst))
            }
        }
        assertTrue("max per-channel diff $maxDiff > 2", maxDiff <= 2)
        full.recycle()
        region.recycle()
    }

    @Test
    fun rgb565_bitmap_is_invalid_request() = withBook { book, handle ->
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.RGB_565)
        try {
            val e = org.junit.Assert.assertThrows(NativeRenderException::class.java) {
                NativePixels.renderToBitmap(handle, 0, 0, 0, 200, 300, bitmap, 0L)
            }
            assertEquals("InvalidRequest", e.kind)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun released_handle_is_invalid_handle() {
        val engine = Engine(defaultEngineConfig())
        try {
            val book = engine.openPath(corpusFile().absolutePath)
            try {
                val handle = book.nativeHandle().toLong()
                assertTrue(releaseBookHandle(handle.toULong()))
                val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
                try {
                    val e = org.junit.Assert.assertThrows(NativeRenderException::class.java) {
                        NativePixels.renderToBitmap(handle, 0, 0, 0, 200, 300, bitmap, 0L)
                    }
                    assertEquals("InvalidHandle", e.kind)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                book.destroy()
            }
        } finally {
            engine.destroy()
        }
    }

    @Test
    fun cancelled_token_is_cancelled() = withBook { book, handle ->
        val token = NativePixels.newCancelToken()
        assertTrue(token != 0L)
        try {
            NativePixels.cancel(token)
            val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
            try {
                val e = org.junit.Assert.assertThrows(NativeRenderException::class.java) {
                    NativePixels.renderToBitmap(handle, 0, 0, 0, 200, 300, bitmap, token)
                }
                assertEquals("Cancelled", e.kind)
            } finally {
                bitmap.recycle()
            }
        } finally {
            NativePixels.releaseCancelToken(token)
        }
    }
}
