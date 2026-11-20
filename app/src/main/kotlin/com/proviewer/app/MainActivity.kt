package com.proviewer.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.proviewer.app.bench.VariantFeatures
import com.proviewer.app.engine.NativeBook
import com.proviewer.app.home.HomeScreen
import com.proviewer.app.open.BookOpener
import com.proviewer.app.open.OpenResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the three Phase 2 screens with a plain screen state (no navigation library): [Home],
 * [Reader] (a placeholder until Task 7) and [Benchmark]. `ACTION_VIEW` intents open the reader.
 */
class MainActivity : ComponentActivity() {
    private enum class Screen { Home, Reader, Benchmark }

    private val app get() = application as ProViewerApp
    private val opener by lazy { BookOpener(applicationContext, app.engine) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var screen by mutableStateOf(Screen.Home)
    private var error by mutableStateOf<String?>(null)
    private var copyProgress by mutableStateOf<Float?>(null)
    private var copyJob: Job? = null
    private var openedBook: NativeBook? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewUri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (viewUri != null) {
            screen = Screen.Reader
            openBook(viewUri)
        }
        setContent {
            MaterialTheme {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        onOpen = { uri -> openBook(uri) },
                        error = error,
                        copyProgress = copyProgress,
                        onCancelCopy = { copyJob?.cancel() },
                        benchmarkAvailable = VariantFeatures.benchmarkAvailable,
                        onBenchmark = { screen = Screen.Benchmark },
                    )
                    Screen.Reader -> ReaderPlaceholder()
                    Screen.Benchmark -> VariantFeatures.BenchmarkScreen(onBack = { screen = Screen.Home })
                }
            }
        }
    }

    override fun onDestroy() {
        copyJob?.cancel()
        val book = openedBook
        openedBook = null
        if (book != null) {
            // Close off the main thread on a throwaway scope so it survives this scope's cancel.
            CoroutineScope(Dispatchers.IO).launch { book.closeAsync() }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun openBook(uri: android.net.Uri) {
        error = null
        copyProgress = null
        copyJob = scope.launch {
            val result = try {
                opener.open(uri) { copied, total ->
                    copyProgress = if (total != null && total > 0) copied.toFloat() / total else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OpenResult.Failed(e::class.simpleName ?: "open failed")
            } finally {
                copyProgress = null
            }
            when (result) {
                is OpenResult.Opened -> {
                    openedBook?.closeAsync()
                    openedBook = result.book
                    screen = Screen.Reader
                }
                is OpenResult.Failed -> {
                    screen = Screen.Home
                    error = result.reason
                }
            }
        }
    }
}

/** Placeholder reader node until Task 7; it just has to expose the `reader-root` tag. */
@Composable
private fun ReaderPlaceholder() {
    Box(Modifier.fillMaxSize().testTag("reader-root"), contentAlignment = Alignment.Center) {
        Text("Reader")
    }
}
