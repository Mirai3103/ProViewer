package com.proviewer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proviewer.app.bench.CorpusGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CorpusGeneratorTest {
    @Test
    fun generates_small_corpus() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "corpus-generator-test").apply { mkdirs() }
        val names = listOf("mixed-small.cbz", "screentone.cbz", "huge-single.cbz")

        val first = CorpusGenerator.ensure(dir, 0.05f, names)
        assertEquals(3, first.size)
        val stamps = HashMap<String, Long>()
        for (f in first) {
            assertTrue("${f.name} should exist", f.isFile)
            assertTrue("${f.name} should be non-empty", f.length() > 0)
            stamps[f.name] = f.lastModified()
        }

        val second = CorpusGenerator.ensure(dir, 0.05f, names)
        assertEquals(3, second.size)
        for (f in second) {
            assertEquals("${f.name} should not be rewritten", stamps[f.name], f.lastModified())
        }
    }
}
