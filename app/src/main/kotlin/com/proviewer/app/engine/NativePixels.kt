package com.proviewer.app.engine

import android.graphics.Bitmap
import android.hardware.HardwareBuffer

/**
 * JNI pixel path into the Rust engine. Every render is validated and decoded in Rust and written
 * straight into the pixels of the provided [Bitmap] or [HardwareBuffer], so pixels never cross the
 * FFI boundary.
 *
 * A `book` is the non-zero handle returned by `Book.nativeHandle()`; `cancel` is a [CancelToken]
 * handle from [newCancelToken], or 0 for "no token". Failures throw [NativeRenderException].
 */
object NativePixels {
    init {
        System.loadLibrary("proviewer_core")
    }

    external fun renderToBitmap(
        book: Long,
        page: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        bitmap: Bitmap,
        cancel: Long,
    )

    external fun renderToHardwareBuffer(
        book: Long,
        page: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        buffer: HardwareBuffer,
        cancel: Long,
    )

    external fun newCancelToken(): Long

    external fun cancel(token: Long)

    external fun releaseCancelToken(token: Long)
}

/**
 * Error thrown by [NativePixels]. `kind` is the `EngineError` variant name (for example
 * `"InvalidRequest"`, `"Cancelled"`), or `"InvalidHandle"` or `"Panic"`; `detail` is the Display
 * text (for `"Panic"`, the panic message).
 */
class NativeRenderException(val kind: String, val detail: String) :
    RuntimeException("$kind: $detail")
