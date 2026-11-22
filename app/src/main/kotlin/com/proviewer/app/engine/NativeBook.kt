package com.proviewer.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.proviewer_core.Book
import uniffi.proviewer_core.PageInfo
import uniffi.proviewer_core.releaseBookHandle
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [Book] plus the single native handle used by [NativePixels], kept together so the handle is
 * derived once and released exactly once (`nativeHandle()` is not idempotent, so it must never be
 * called a second time).
 *
 * [close] is idempotent and releases the handle, destroys the uniffi [Book] and deletes
 * [copiedFile]. [closeAsync] runs [close] on `Dispatchers.IO` (the brief requires `close()` to run
 * off the main thread); production callers go through [closeAsync].
 *
 * If the constructor fails after the [Book] has been created (i.e. `nativeHandle()` or `pageCount()`
 * throws), the already-acquired [Book] (and any derived handle) is released before rethrowing so it
 * is never orphaned.
 */
class NativeBook(
    val book: Book,
    val copiedFile: File?,
) : AutoCloseable {

    /** The single handle returned by [Book.nativeHandle]. Never re-derived. */
    val handle: Long = try {
        book.nativeHandle().toLong()
    } catch (t: Throwable) {
        runCatching { book.destroy() }
        throw t
    }

    val pageCount: Int = try {
        book.pageCount().toInt()
    } catch (t: Throwable) {
        runCatching { releaseBookHandle(handle.toULong()) }
        runCatching { book.destroy() }
        throw t
    }

    private val infoCache = ConcurrentHashMap<Int, PageInfo>()
    private val closed = AtomicBoolean(false)

    /** True once [close] has run (or started): every further native call would fail. */
    val isClosed: Boolean get() = closed.get()

    /**
     * Page size and format, probed once per page and memoized. The probe may read and inflate the
     * whole entry, so call it off the main thread unless [cachedPageInfo] already has the page. No
     * lock is held while probing (two threads probing one page both do the work, harmlessly), so a
     * slow probe never blocks a [cachedPageInfo] read.
     */
    fun pageInfo(i: Int): PageInfo = infoCache[i] ?: book.pageInfo(i.toUInt()).also { infoCache[i] = it }

    /** The memoized [pageInfo] of page [i], or null if it was never probed. Never blocks. */
    fun cachedPageInfo(i: Int): PageInfo? = infoCache[i]

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

    /** Runs [close] on [Dispatchers.IO]; the brief requires close to run off the main thread. */
    suspend fun closeAsync() = withContext(Dispatchers.IO) { close() }
}
