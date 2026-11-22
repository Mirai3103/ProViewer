package com.proviewer.app.reader

import android.graphics.Bitmap
import com.proviewer.app.engine.PageImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Hard cap on the bytes of bitmaps and tiles the reader holds at once (E3): 192 MiB. */
const val IMAGE_CAP_BYTES: Long = 192L * 1024 * 1024

/** The RGBA byte size of a [w] x [h] image. */
fun rgbaBytes(w: Int, h: Int): Long = w.toLong() * h * 4

/**
 * Accounting for the bytes of images the reader holds. A holder calls [tryReserve] BEFORE allocating
 * and [release]s the same amount when the image is gone, so [usedBytes] never exceeds [capBytes].
 * Thread-safe and lock-free.
 *
 * [onReleased], when given, is invoked after every successful [release] — including the releases
 * of [wrap]ped images — so the owner can react to bytes that were freed outside its own
 * accounting (e.g. detail images): whatever was skipped for lack of budget can retry. It may run
 * on any thread.
 */
class ImageBudget(
    val capBytes: Long = IMAGE_CAP_BYTES,
    private val onReleased: (() -> Unit)? = null,
) {
    private val used = AtomicLong(0)
    private val peak = AtomicLong(0)

    /** Bytes currently reserved. */
    val usedBytes: Long get() = used.get()

    /** The highest [usedBytes] since construction or the last [resetPeak]. */
    val peakBytes: Long get() = peak.get()

    /** Reserves [bytes] if that keeps [usedBytes] within [capBytes]; returns whether it did. */
    fun tryReserve(bytes: Long): Boolean {
        require(bytes >= 0) { "bytes must not be negative: $bytes" }
        while (true) {
            val current = used.get()
            val next = current + bytes
            if (next > capBytes) return false
            if (used.compareAndSet(current, next)) {
                raisePeak(next)
                return true
            }
        }
    }

    /** Returns [bytes] previously reserved with [tryReserve]. */
    fun release(bytes: Long) {
        require(bytes >= 0) { "bytes must not be negative: $bytes" }
        val now = used.addAndGet(-bytes)
        check(now >= 0) { "released more bytes than were reserved" }
        onReleased?.invoke()
    }

    /** Restarts peak tracking from the current [usedBytes]. */
    fun resetPeak() {
        peak.set(used.get())
    }

    /**
     * Wraps [image], whose [bytes] were already reserved: the wrapper's [PageImage.release] releases
     * the image and returns the bytes, exactly once however often it is called.
     */
    fun wrap(image: PageImage, bytes: Long): PageImage = BudgetedImage(image, bytes)

    private fun raisePeak(value: Long) {
        while (true) {
            val current = peak.get()
            if (value <= current || peak.compareAndSet(current, value)) return
        }
    }

    private inner class BudgetedImage(private val image: PageImage, private val bytes: Long) : PageImage {
        private val released = AtomicBoolean(false)

        override val bitmap: Bitmap? get() = image.bitmap

        override fun prepareToDraw() = image.prepareToDraw()

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            try {
                image.release()
            } finally {
                this@ImageBudget.release(bytes)
            }
        }
    }
}
