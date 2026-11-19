package com.proviewer.app.engine

import uniffi.proviewer_core.Book
import uniffi.proviewer_core.PageInfo
import uniffi.proviewer_core.releaseBookHandle
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [Book] plus the single native handle used by [NativePixels], kept together so the handle is
 * derived once and released exactly once (`nativeHandle()` is not idempotent, so it must never be
 * called a second time).
 *
 * [close] is idempotent and releases the handle, destroys the uniffi [Book] and deletes
 * [copiedFile]. It must be called from a background thread — the reader dispatches it on
 * `Dispatchers.IO`; none of it is a decode or render.
 */
class NativeBook(
    val book: Book,
    val copiedFile: File?,
) : AutoCloseable {

    /** The single handle returned by [Book.nativeHandle]. Never re-derived. */
    val handle: Long = book.nativeHandle().toLong()

    val pageCount: Int = book.pageCount().toInt()

    private val infoCache = HashMap<Int, PageInfo>()
    private val closed = AtomicBoolean(false)

    /** Page size and format, probed once per page and memoized. */
    @Synchronized
    fun pageInfo(i: Int): PageInfo = infoCache.getOrPut(i) { book.pageInfo(i.toUInt()) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            releaseBookHandle(handle.toULong())
        } finally {
            try {
                book.destroy()
            } finally {
                copiedFile?.delete()
            }
        }
    }
}
