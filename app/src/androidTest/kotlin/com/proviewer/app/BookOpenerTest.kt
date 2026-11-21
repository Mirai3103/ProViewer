package com.proviewer.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proviewer.app.open.BookOpener
import com.proviewer.app.open.OpenResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.proviewer_core.Engine
import uniffi.proviewer_core.defaultEngineConfig
import java.io.File

@RunWith(AndroidJUnit4::class)
class BookOpenerTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Engine
    private lateinit var opener: BookOpener

    @Before
    fun setUp() {
        engine = Engine(defaultEngineConfig())
        opener = BookOpener(context, engine)
    }

    @After
    fun tearDown() {
        engine.destroy()
    }

    private fun uri(form: String, name: String): Uri =
        Uri.parse("content://${TestCbzProvider.AUTHORITY}/$form/$name")

    @Test
    fun seekable_opens_without_copy() = runBlocking {
        val result = opener.open(uri("seekable", "mixed-small.cbz")) { _, _ -> }
        val opened = result as OpenResult.Opened
        assertEquals(20, opened.book.pageCount)
        assertNull("a seekable file must not be copied", opened.book.copiedFile)
        assertEquals("mixed-small.cbz", opened.title)
        opened.book.close()
    }

    @Test
    fun pipe_falls_back_to_copy() = runBlocking {
        val result = opener.open(uri("pipe", "mixed-small.cbz")) { _, _ -> }
        val opened = result as OpenResult.Opened
        assertEquals(20, opened.book.pageCount)
        val copied = opened.book.copiedFile
        assertNotNull("a non-seekable pipe must be copied", copied)
        assertTrue("copied file must exist", copied!!.exists())
        opened.book.closeAsync()
        assertTrue("closeAsync must destroy the Book", opened.book.book.uniffiIsDestroyed)
        assertFalse("copied file must be deleted after close", copied.exists())
    }

    @Test
    fun close_is_idempotent() = runBlocking {
        val result = opener.open(uri("pipe", "mixed-small.cbz")) { _, _ -> }
        val opened = result as OpenResult.Opened
        val copied = opened.book.copiedFile
        assertNotNull(copied)
        opened.book.closeAsync()
        assertTrue("first closeAsync must destroy the Book", opened.book.book.uniffiIsDestroyed)
        assertFalse("copied file must be deleted after first close", copied!!.exists())
        opened.book.closeAsync() // idempotent: no throw, still destroyed
        opened.book.close()      // AutoCloseable close is idempotent too
        assertFalse("copied file stays deleted after repeated close", copied.exists())
    }

    @Test
    fun broken_stream_returns_failed() = runBlocking {
        val result = opener.open(uri("broken", "mixed-small.cbz")) { _, _ -> }
        assertTrue("a throwing stream must map to Failed, got: $result", result is OpenResult.Failed)
        val failed = result as OpenResult.Failed
        assertTrue("reason must name the failure, was: ${failed.reason}", failed.reason.contains("FileNotFound"))
    }

    @Test
    fun provider_without_fd_falls_back_to_copy() = runBlocking {
        val result = opener.open(uri("partial", "mixed-small.cbz")) { _, _ -> }
        val opened = result as OpenResult.Opened
        assertEquals(20, opened.book.pageCount)
        assertNotNull("a provider without a whole-file fd must be copied", opened.book.copiedFile)
        opened.book.close()
    }

    @Test
    fun text_file_fails_with_reason() = runBlocking {
        val file = File(context.cacheDir, "note.txt")
        file.writeText("this is definitely not a zip archive")
        val result = opener.open(Uri.fromFile(file)) { _, _ -> }
        val failed = result as OpenResult.Failed
        assertTrue("reason must name the variant, was: ${failed.reason}", failed.reason.contains("UnsupportedFormat"))
    }

    @Test
    fun cancel_during_copy_deletes_partial() = runBlocking {
        val openedDir = File(context.cacheDir, "opened").apply { mkdirs() }
        val firstProgress = CompletableDeferred<Unit>()
        val job = launch {
            opener.open(uri("pipe", "huge-single.cbz")) { copied, _ ->
                if (copied > 0) firstProgress.complete(Unit)
            }
        }
        firstProgress.await()
        job.cancelAndJoin()
        val leftovers = openedDir.listFiles().orEmpty().filter { it.extension == "cbz" }
        assertTrue("partial copy must be deleted, found: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun view_intent_opens_reader() {
        val target = uri("seekable", "mixed-small.cbz")
        val intent = Intent(Intent.ACTION_VIEW, target).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(target, "application/zip")
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("reader-root").assertExists()
        }
    }
}
