package com.proviewer.app.engine

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import androidx.compose.ui.unit.IntSize
import com.proviewer.app.reader.PageRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import uniffi.proviewer_core.ReadDirection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** How a rendered page's pixels reach the GPU. */
enum class UploadStrategy { Software, Hardware }

/**
 * A rendered page image. Production implementations always have a non-null [bitmap]; JVM test fakes
 * may return null. [release] must be called exactly once when the image is no longer needed.
 */
interface PageImage {
    val bitmap: Bitmap?
    fun prepareToDraw()
    fun release()
}

/** A software `Bitmap` render; [release] recycles it. */
class SoftwarePageImage(override val bitmap: Bitmap) : PageImage {
    private val released = AtomicBoolean(false)

    override fun prepareToDraw() {
        bitmap.prepareToDraw()
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        bitmap.recycle()
    }
}

/** A hardware-buffer render; [release] recycles the wrapped bitmap and closes the buffer. */
class HardwarePageImage(
    val buffer: HardwareBuffer,
    override val bitmap: Bitmap,
    private val onClosed: () -> Unit,
) : PageImage {
    private val released = AtomicBoolean(false)

    override fun prepareToDraw() = Unit // no separate upload for a hardware buffer

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { bitmap.recycle() }
        runCatching { buffer.close() }
        onClosed()
    }
}

/**
 * The seam [com.proviewer.app.reader.PageWindow] renders through, faked in JVM tests. [PageRenderer]
 * is the production implementation.
 */
interface PageSourceRenderer {
    /** Number of pages in the book. */
    val pageCount: Int

    suspend fun renderBase(page: Int, size: IntSize): PageImage

    /** The natural size of [page] if it was already probed, else null. Never blocks; main-safe. */
    fun knownPageSize(page: Int): IntSize?

    /**
     * Probes the natural size of [page] off the caller's thread (the probe may read and inflate
     * the whole archive entry). Throws for an out-of-range page or a probe failure.
     */
    suspend fun probePageSize(page: Int): IntSize

    fun prefetchHint(current: Int, forward: Boolean, viewW: Int, viewH: Int)
}

/**
 * Renders pages through [NativePixels] into software bitmaps or hardware buffers. Renders run on a
 * dispatcher of parallelism 2; cancellation requests `NativePixels.cancel` on the render's token
 * and releases any half-made target, and the token is released in `finally`.
 */
class PageRenderer(
    private val book: NativeBook,
    @Volatile var strategy: UploadStrategy,
) : PageSourceRenderer {

    /** True once a hardware render has fallen back to software (alloc/wrap failure). */
    @Volatile var fellBack: Boolean = false
        private set

    /** Debug counter of currently open hardware buffers (incremented on allocation, decremented on release). */
    val openHardwareBuffers: Int get() = openBuffers.get()

    /**
     * Test-only hook invoked once, right after a hardware target is allocated and before the JNI
     * render call. Lets the instrumented cancel test wait deterministically for allocation instead
     * of polling [openHardwareBuffers].
     */
    internal var onTargetAllocated: (() -> Unit)? = null

    private val dispatcher = Dispatchers.Default.limitedParallelism(2)
    private val openBuffers = AtomicInteger(0)

    /**
     * Renders [region] of [page] to a target of exactly `region.outW x region.outH`. Runs off the
     * caller's dispatcher; the caller (PageWindow) is responsible for releasing the returned image.
     *
     * `withContext` has a prompt cancellation guarantee: if the caller is cancelled after the
     * render finished but before the result is handed back, `withContext` throws and drops the
     * value. The finished image is therefore tracked in [produced] and released on that path, so
     * no bitmap or hardware buffer is ever orphaned.
     */
    suspend fun render(page: Int, region: PageRegion): PageImage {
        var produced: PageImage? = null
        try {
            return withContext(dispatcher) {
                val token = NativePixels.newCancelToken()
                val image = try {
                    when (strategy) {
                        UploadStrategy.Software -> renderSoftware(page, region, token)
                        UploadStrategy.Hardware -> renderHardware(page, region, token) ?: run {
                            fellBack = true
                            renderSoftware(page, region, token)
                        }
                    }
                } finally {
                    NativePixels.releaseCancelToken(token)
                }
                produced = image
                image
            }
        } catch (e: CancellationException) {
            produced?.release()
            throw e
        }
    }

    override val pageCount: Int get() = book.pageCount

    override suspend fun renderBase(page: Int, size: IntSize): PageImage {
        val natural = probePageSize(page)
        return render(page, PageRegion(0, 0, natural.width, natural.height, size.width, size.height))
    }

    override fun knownPageSize(page: Int): IntSize? =
        book.cachedPageInfo(page)?.let { IntSize(it.width.toInt(), it.height.toInt()) }

    override suspend fun probePageSize(page: Int): IntSize = knownPageSize(page)
        ?: withContext(Dispatchers.IO) {
            val info = book.pageInfo(page)
            IntSize(info.width.toInt(), info.height.toInt())
        }

    override fun prefetchHint(current: Int, forward: Boolean, viewW: Int, viewH: Int) {
        book.book.prefetchHint(
            current.toUInt(),
            if (forward) ReadDirection.FORWARD else ReadDirection.BACKWARD,
            viewW.toUInt(),
            viewH.toUInt(),
        )
    }

    private suspend fun renderSoftware(page: Int, region: PageRegion, token: Long): PageImage {
        val bitmap = Bitmap.createBitmap(region.outW, region.outH, Bitmap.Config.ARGB_8888)
        try {
            cancellableRender(token) {
                NativePixels.renderToBitmap(book.handle, page, region.x, region.y, region.w, region.h, bitmap, token)
            }
            return SoftwarePageImage(bitmap)
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
    }

    /**
     * Allocates a hardware target and renders into it. Returns null (and releases the buffer) when
     * allocation or wrapping fails, so the caller can fall back to software. A render failure is
     * rethrown (never silently downgraded).
     */
    private suspend fun renderHardware(page: Int, region: PageRegion, token: Long): PageImage? {
        // create() is @NonNull: it throws (IllegalArgumentException) rather than returning null
        // when the buffer cannot be allocated.
        val buffer = try {
            HardwareBuffer.create(
                region.outW,
                region.outH,
                HardwareBuffer.RGBA_8888,
                1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
            )
        } catch (e: Exception) {
            return null // allocation failed -> caller falls back to software
        }
        openBuffers.incrementAndGet()

        var bitmap: Bitmap? = null
        var delivered = false
        try {
            val wrapped = try {
                Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
            } catch (e: Exception) {
                null
            } ?: return null // wrap failed; finally closes the buffer
            bitmap = wrapped
            onTargetAllocated?.invoke()
            cancellableRender(token) {
                NativePixels.renderToHardwareBuffer(
                    book.handle, page, region.x, region.y, region.w, region.h, buffer, token,
                )
            }
            delivered = true
            return HardwarePageImage(buffer, wrapped) { openBuffers.decrementAndGet() }
        } finally {
            if (!delivered) {
                bitmap?.recycle()
                buffer.close()
                openBuffers.decrementAndGet()
            }
        }
    }

    /**
     * Runs [block] (a blocking native render) on the current dispatcher thread, cancelling the
     * render's token as soon as this coroutine is cancelled so the native call returns promptly.
     */
    private suspend fun cancellableRender(token: Long, block: () -> Unit) {
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                // May run after the finally released the token (cancel-vs-resume race); the
                // resulting InvalidHandle is harmless, so swallow it (must not escape this handler).
                runCatching { NativePixels.cancel(token) }
            }
            try {
                block()
                cont.resumeWith(Result.success(Unit))
            } catch (e: Throwable) {
                cont.resumeWith(Result.failure(e))
            }
        }
    }
}
