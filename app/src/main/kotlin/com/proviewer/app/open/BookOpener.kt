package com.proviewer.app.open

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.proviewer.app.engine.NativeBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import uniffi.proviewer_core.Engine
import uniffi.proviewer_core.EngineException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

sealed interface OpenResult {
    data class Opened(val book: NativeBook, val title: String) : OpenResult
    data class Failed(val reason: String) : OpenResult
}

/**
 * Opens a CBZ/ZIP through the engine. A seekable file descriptor is opened directly (`openFd`);
 * a non-seekable descriptor, a provider that cannot offer a whole-file fd, or a plain `file://`
 * URI falls back to copying the stream into `cacheDir/opened/<uuid>.cbz` and opening it by path.
 *
 * Every failure path returns [OpenResult.Failed] rather than throwing; only coroutine cancellation
 * propagates. Safe to call from the main thread: all resolver and engine work runs on
 * `Dispatchers.IO`.
 */
class BookOpener(private val context: Context, private val engine: Engine) {

    /**
     * Opens [uri]. The whole open runs inside one `withContext(Dispatchers.IO)`. That call has a
     * prompt cancellation guarantee: a cancel that lands after the block produced [OpenResult.Opened]
     * makes it throw and drop the value, so the opened book is tracked and closed on that path
     * instead of being orphaned (fd, registry handle and copied file).
     */
    suspend fun open(uri: Uri, onCopyProgress: (copied: Long, total: Long?) -> Unit): OpenResult {
        var produced: NativeBook? = null
        try {
            return withContext(Dispatchers.IO) {
                val result = openOnIo(uri, onCopyProgress)
                if (result is OpenResult.Opened) produced = result.book
                result
            }
        } catch (e: CancellationException) {
            produced?.close()
            throw e
        }
    }

    /** The open itself, on the IO dispatcher. Must not suspend after producing its result. */
    private suspend fun openOnIo(uri: Uri, onCopyProgress: (Long, Long?) -> Unit): OpenResult {
        val meta = metaOf(uri)

        // A file:// URI names a local path; the engine opens it by path directly.
        if (uri.scheme == "file") return openByPath(uri, meta.title)

        val opened = tryOpenFd(uri, meta.title)
        if (opened != null) return opened

        return copyToCacheAndOpen(uri, meta, onCopyProgress)
    }

    /**
     * Attempts the direct fd path. Returns null when the fallback copy path should run instead
     * (`openFileDescriptor` threw or returned null, or the engine reported [EngineException.NotSeekable]).
     * Has no suspension point, so a cancellation can never land between `detachFd` and `openFd`
     * and orphan the raw fd.
     */
    private fun tryOpenFd(uri: Uri, title: String): OpenResult? {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return null
        val fd = pfd.detachFd()
        val book = try {
            engine.openFd(fd) // takes ownership of the fd
        } catch (e: EngineException.NotSeekable) {
            return null // the engine closed the fd; fall back to the copy path
        } catch (e: EngineException) {
            // Rust dropped the fd on every EngineException return path.
            return OpenResult.Failed(reasonOf(e))
        } catch (e: Throwable) {
            // Rust open_fd adopts the fd (File::from_raw_fd) before doing anything else, so a
            // panic (surfaced as an uniffi InternalException) already closed it while unwinding.
            // Closing it again here could close an unrelated descriptor that reused the number.
            return OpenResult.Failed(reasonOfUnexpected(e))
        }

        return try {
            takePersistablePermission(uri)
            OpenResult.Opened(NativeBook(book, copiedFile = null), title)
        } catch (e: Exception) {
            // NativeBook's constructor already released the book (and its fd) if it threw.
            OpenResult.Failed(reasonOfFailure(e))
        }
    }

    private fun openByPath(uri: Uri, title: String): OpenResult {
        val path = uri.path ?: return OpenResult.Failed("no path for $uri")
        return try {
            val book = engine.openPath(path)
            takePersistablePermission(uri)
            OpenResult.Opened(NativeBook(book, copiedFile = null), title)
        } catch (e: EngineException) {
            OpenResult.Failed(reasonOf(e))
        } catch (e: Throwable) {
            OpenResult.Failed(reasonOfUnexpected(e))
        }
    }

    private suspend fun copyToCacheAndOpen(
        uri: Uri,
        meta: UriMeta,
        onCopyProgress: (Long, Long?) -> Unit,
    ): OpenResult {
        val dir = File(context.cacheDir, "opened").apply { mkdirs() }
        val dest = File(dir, "${UUID.randomUUID()}.cbz")

        var input: InputStream? = null
        var output: FileOutputStream? = null
        var succeeded = false
        try {
            try {
                input = context.contentResolver.openInputStream(uri)
                    ?: return OpenResult.Failed("no readable stream for $uri")

                // Check free space before creating the output file, so a refusal never leaves
                // an empty destination behind.
                if (meta.size != null) {
                    val free = StatFs(dir.absolutePath).availableBytes
                    if (free < meta.size + FREE_SPACE_MARGIN) {
                        return OpenResult.Failed("not enough free space to copy $uri")
                    }
                }

                output = FileOutputStream(dest)

                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    onCopyProgress(copied, meta.size)
                }
                output.flush()
                output.close()
                output = null
                input.close()
                input = null

                val book = engine.openPath(dest.absolutePath)
                takePersistablePermission(uri)
                // NativeBook releases the book itself if its constructor throws; the copy is then
                // deleted by the finally below.
                val opened = NativeBook(book, dest)
                succeeded = true
                return OpenResult.Opened(opened, meta.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return OpenResult.Failed(reasonOfFailure(e))
            }
        } finally {
            runCatching { input?.close() }
            runCatching { output?.close() }
            // A partial copy is deleted on cancellation and on any failure before a successful open.
            if (!succeeded) runCatching { dest.delete() }
        }
    }

    private fun takePersistablePermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Nothing in Phase 2 depends on it.
        }
    }

    private data class UriMeta(val title: String, val size: Long?)

    private fun metaOf(uri: Uri): UriMeta {
        val fallback = uri.lastPathSegment ?: ""
        val (name, size) = try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (!c.moveToFirst()) return@use (null to null)
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (ni >= 0) c.getString(ni) else null
                    val size = if (si >= 0 && !c.isNull(si)) c.getLong(si) else null
                    name to size
                } ?: (null to null)
        } catch (e: Exception) {
            null to null
        }
        return UriMeta(name ?: fallback, size)
    }

    private fun reasonOf(e: EngineException): String {
        val kind = e::class.simpleName ?: "EngineException"
        val detail = when (e) {
            is EngineException.Io -> e.detail
            is EngineException.UnsupportedFormat -> e.detail
            is EngineException.Corrupt -> e.detail
            is EngineException.InvalidRequest -> e.detail
            is EngineException.LimitExceeded -> "limit=${e.limit}, value=${e.value}, max=${e.max}"
            is EngineException.PageOutOfRange -> "index=${e.index}, count=${e.count}"
            is EngineException.NoPages -> "no pages"
            is EngineException.Cancelled -> "cancelled"
            is EngineException.NotSeekable -> "not seekable"
        }
        return if (detail.isBlank()) kind else "$kind: $detail"
    }

    /** Reason for a copy-path failure: the [EngineException] detail, else the exception type name. */
    private fun reasonOfFailure(e: Exception): String = when (e) {
        is EngineException -> reasonOf(e)
        else -> reasonOfUnexpected(e)
    }

    /**
     * Reason for a non-[EngineException] failure (e.g. an uniffi `InternalException`). These carry
     * no `detail` field; use the type name, never `message`.
     */
    private fun reasonOfUnexpected(e: Throwable): String = e::class.simpleName ?: "OpenError"

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val FREE_SPACE_MARGIN = 64L * 1024 * 1024 // 64 MiB
    }
}
