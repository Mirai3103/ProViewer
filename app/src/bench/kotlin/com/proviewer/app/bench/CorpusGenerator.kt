package com.proviewer.app.bench

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device synthetic corpus generator (debug + benchmark build types only). It loads the
 * benchmark-only native library `proviewer_benchgen`, which wraps `tools/gen-corpus`, and
 * regenerates the requested archives only when the generator version or scale changed.
 */
object CorpusGenerator {
    // Written into the benchmark JSON (Task 9); R8 keeps the string for the bench variant.
    const val MARKER = "PV_BENCHMARK_MARKER"

    init {
        System.loadLibrary("proviewer_benchgen")
    }

    private external fun nativeGenerate(dir: String, scale: Float, names: Array<String>): String?
    private external fun nativeVersion(): String

    /**
     * Generate [names] at [scale] under [dir], skipping the work when `dir/corpus.stamp` already
     * matches `"${nativeVersion()}@$scale"`. Runs on [Dispatchers.IO].
     */
    suspend fun ensure(dir: File, scale: Float, names: List<String>): List<File> =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val stampFile = File(dir, "corpus.stamp")
            val stamp = "${nativeVersion()}@$scale"
            val files = names.map { File(dir, it) }
            if (stampFile.isFile && stampFile.readText() == stamp && files.all { it.isFile }) {
                return@withContext files
            }
            val error = nativeGenerate(dir.absolutePath, scale, names.toTypedArray())
            if (error != null) {
                throw IllegalStateException("corpus generation failed: $error")
            }
            stampFile.writeText(stamp)
            files
        }
}
