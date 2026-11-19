package com.proviewer.codecbench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Platform JPEG decoder baseline for the Phase 1 codec comparison (docs/perf/codec-comparison.md).
 *
 * The inputs are the files `codec-bench --export` writes (the same bytes the Rust candidates decode).
 * Each test is one (input x method x sample size) cell. The encoded bytes are loaded once, outside
 * the measured block; each iteration recycles its bitmap. Output is ARGB_8888 (4 B/px), unlike the
 * Gray8/RGB8 the Rust candidates produce.
 */
@RunWith(AndroidJUnit4::class)
class PlatformJpegDecodeBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private fun load(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    private fun expected(len: Int, sample: Int) = (len + sample - 1) / sample

    private fun decodeBitmapFactory(bytes: ByteArray, sample: Int): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)) {
            "BitmapFactory returned null"
        }
    }

    private fun decodeImageDecoder(buffer: ByteBuffer, sample: Int): Bitmap {
        val source = ImageDecoder.createSource(buffer)
        return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /**
     * A full-size ARGB_8888 decode of the 96 MP input is a 384 MB bitmap. On the reference device that
     * made lmkd kill the benchmark process (and many cached apps) on the first attempt, so those two
     * cells run only when asked: `-Pandroid.testInstrumentationRunnerArguments.hugeFull=true`.
     */
    private fun assumeHugeFullRequested(sample: Int) {
        if (sample == 1) {
            val requested = InstrumentationRegistry.getArguments().getString("hugeFull") == "true"
            assumeTrue("96 MP full-size decode runs only with hugeFull=true", requested)
        }
    }

    private fun bitmapFactory(name: String, width: Int, height: Int, sample: Int) {
        if (name == HUGE) assumeHugeFullRequested(sample)
        val bytes = load(name)
        // Sanity check outside the measured block: the decode is real and scaled as asked.
        decodeBitmapFactory(bytes, sample).also {
            assertEquals(expected(width, sample), it.width)
            assertEquals(expected(height, sample), it.height)
            it.recycle()
        }
        benchmarkRule.measureRepeated {
            val bitmap = decodeBitmapFactory(bytes, sample)
            assertNotNull(bitmap)
            bitmap.recycle()
        }
    }

    private fun imageDecoder(name: String, width: Int, height: Int, sample: Int) {
        if (name == HUGE) assumeHugeFullRequested(sample)
        val bytes = load(name)
        val buffer = ByteBuffer.wrap(bytes)
        decodeImageDecoder(buffer, sample).also {
            assertEquals(expected(width, sample), it.width)
            assertEquals(expected(height, sample), it.height)
            it.recycle()
        }
        benchmarkRule.measureRepeated {
            val bitmap = decodeImageDecoder(buffer, sample)
            assertNotNull(bitmap)
            bitmap.recycle()
        }
    }

    // gray-1600x2400.jpg
    @Test fun bitmapFactory_gray1600_s1() = bitmapFactory(GRAY, 1600, 2400, 1)
    @Test fun bitmapFactory_gray1600_s2() = bitmapFactory(GRAY, 1600, 2400, 2)
    @Test fun bitmapFactory_gray1600_s4() = bitmapFactory(GRAY, 1600, 2400, 4)
    @Test fun imageDecoder_gray1600_s1() = imageDecoder(GRAY, 1600, 2400, 1)
    @Test fun imageDecoder_gray1600_s2() = imageDecoder(GRAY, 1600, 2400, 2)
    @Test fun imageDecoder_gray1600_s4() = imageDecoder(GRAY, 1600, 2400, 4)

    // color-1600x2400.jpg
    @Test fun bitmapFactory_color1600_s1() = bitmapFactory(COLOR, 1600, 2400, 1)
    @Test fun bitmapFactory_color1600_s2() = bitmapFactory(COLOR, 1600, 2400, 2)
    @Test fun bitmapFactory_color1600_s4() = bitmapFactory(COLOR, 1600, 2400, 4)
    @Test fun imageDecoder_color1600_s1() = imageDecoder(COLOR, 1600, 2400, 1)
    @Test fun imageDecoder_color1600_s2() = imageDecoder(COLOR, 1600, 2400, 2)
    @Test fun imageDecoder_color1600_s4() = imageDecoder(COLOR, 1600, 2400, 4)

    // progressive-1600x2400.jpg
    @Test fun bitmapFactory_progressive1600_s1() = bitmapFactory(PROGRESSIVE, 1600, 2400, 1)
    @Test fun bitmapFactory_progressive1600_s2() = bitmapFactory(PROGRESSIVE, 1600, 2400, 2)
    @Test fun bitmapFactory_progressive1600_s4() = bitmapFactory(PROGRESSIVE, 1600, 2400, 4)
    @Test fun imageDecoder_progressive1600_s1() = imageDecoder(PROGRESSIVE, 1600, 2400, 1)
    @Test fun imageDecoder_progressive1600_s2() = imageDecoder(PROGRESSIVE, 1600, 2400, 2)
    @Test fun imageDecoder_progressive1600_s4() = imageDecoder(PROGRESSIVE, 1600, 2400, 4)

    // gray-12000x8000.jpg (96 MP; a full-size ARGB_8888 output is 384 MB)
    @Test fun bitmapFactory_gray12000_s1() = bitmapFactory(HUGE, 12000, 8000, 1)
    @Test fun bitmapFactory_gray12000_s2() = bitmapFactory(HUGE, 12000, 8000, 2)
    @Test fun bitmapFactory_gray12000_s4() = bitmapFactory(HUGE, 12000, 8000, 4)
    @Test fun imageDecoder_gray12000_s1() = imageDecoder(HUGE, 12000, 8000, 1)
    @Test fun imageDecoder_gray12000_s2() = imageDecoder(HUGE, 12000, 8000, 2)
    @Test fun imageDecoder_gray12000_s4() = imageDecoder(HUGE, 12000, 8000, 4)

    private companion object {
        const val GRAY = "gray-1600x2400.jpg"
        const val COLOR = "color-1600x2400.jpg"
        const val PROGRESSIVE = "progressive-1600x2400.jpg"
        const val HUGE = "gray-12000x8000.jpg"
    }
}
