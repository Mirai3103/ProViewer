package com.proviewer.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.proviewer.app.bench.CorpusGenerator
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * Debug-only content provider (authority `com.proviewer.app.test.cbz`) that serves the corpus CBZ
 * files in three URI forms so [BookOpener]'s fd and copy-fallback paths are testable:
 *
 *  - `seekable/<name>`: a plain file [ParcelFileDescriptor]; the engine opens it directly, no copy.
 *  - `pipe/<name>`: the read end of a pipe whose write end is fed by a slow background thread, so
 *    `openFd` reports `NotSeekable` and the copy fallback runs.
 *  - `partial/<name>`: an [AssetFileDescriptor] with an explicit declared length, so
 *    `openFileDescriptor` throws ("Not a whole file") while `openInputStream` still returns the
 *    full stream — the same shape as asset-backed and some cloud providers.
 *  - `broken/<name>`: [openAssetFile] throws [FileNotFoundException], so both `openFileDescriptor`
 *    and `openInputStream` fail; the copy fallback must surface a `Failed` result rather than throw.
 *
 * The corpus is generated lazily into the provider's cache directory (the app cache) with
 * [CorpusGenerator.ensure] and read back from there.
 */
class TestCbzProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/zip"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val parsed = parse(uri) ?: return null
        val file = corpusFile(parsed.name)
        if (!file.isFile) return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(columns.size)
        columns.forEachIndexed { i, col ->
            row[i] = when (col) {
                OpenableColumns.DISPLAY_NAME -> parsed.name
                // A pipe has no usable declared length, so the size stays unknown there.
                OpenableColumns.SIZE -> if (parsed.form == "pipe") null else file.length()
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val parsed = parse(uri) ?: throw FileNotFoundException("bad uri $uri")
        val file = corpusFile(parsed.name)
        if (!file.isFile) throw FileNotFoundException("no such file ${parsed.name}")
        return when (parsed.form) {
            "seekable" -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            "pipe" -> pipeReadEnd(file)
            else -> throw FileNotFoundException("use openAssetFile for /partial/${parsed.name}")
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val parsed = parse(uri) ?: throw FileNotFoundException("bad uri $uri")
        if (parsed.form == "broken") throw FileNotFoundException("simulated unreadable stream for $uri")
        if (parsed.form != "partial") return super.openAssetFile(uri, mode)!!
        val file = corpusFile(parsed.name)
        if (!file.isFile) throw FileNotFoundException("no such file ${parsed.name}")
        // A non-negative declared length makes openFileDescriptor throw, openInputStream work.
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            file.length(),
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private data class Parsed(val form: String, val name: String)

    private fun parse(uri: Uri): Parsed? {
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        return Parsed(segments[0], segments[1])
    }

    private fun corpusFile(name: String): File {
        ensureCorpus()
        return File(requireNotNull(context).cacheDir, "corpus/$name")
    }

    @Volatile
    private var corpusReady = false

    private fun ensureCorpus() {
        if (corpusReady) return
        synchronized(corpusLock) {
            if (corpusReady) return
            val dir = File(requireNotNull(context).cacheDir, "corpus")
            runBlocking { CorpusGenerator.ensure(dir, SCALE, NAMES) }
            corpusReady = true
        }
    }

    /** Read end of a pipe fed, slowly, by a background thread so a copy can be cancelled mid-way. */
    private fun pipeReadEnd(file: File): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        thread(name = "test-cbz-pipe-${file.name}") {
            try {
                writeEnd.use { pfd ->
                    val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                    file.inputStream().use { input ->
                        val buffer = ByteArray(PIPE_CHUNK_BYTES)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            out.flush()
                            Thread.sleep(PIPE_FEED_DELAY_MS)
                        }
                    }
                }
            } catch (e: Exception) {
                // Broken pipe when the reader closes early (the fd-attempt path); nothing to do.
            }
        }
        return readEnd
    }

    companion object {
        const val AUTHORITY = "com.proviewer.app.test.cbz"

        private val NAMES = listOf("mixed-small.cbz", "huge-single.cbz")
        private const val SCALE = 0.05f
        private const val PIPE_CHUNK_BYTES = 4 * 1024
        private const val PIPE_FEED_DELAY_MS = 100L
        private val corpusLock = Any()
    }
}
