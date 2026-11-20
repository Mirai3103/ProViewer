package com.proviewer.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.proviewer.app.bench.VariantFeatures
import com.proviewer.app.home.HomeScreen
import com.proviewer.app.open.BookOpener
import com.proviewer.app.open.OpenResult
import com.proviewer.app.reader.ReaderScreen
import com.proviewer.app.reader.ReaderViewModel
import com.proviewer.app.reader.TapAction
import com.proviewer.app.reader.keyAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the three Phase 2 screens with a plain screen state (no navigation library): [Home],
 * [Reader] and [Benchmark]. `ACTION_VIEW` intents open the reader. The reader's [ReaderViewModel]
 * survives rotation; only the current screen name is saved so the reader is restored.
 */
class MainActivity : ComponentActivity() {
    private enum class Screen { Home, Reader, Benchmark }

    private val app get() = application as ProViewerApp
    private val opener by lazy { BookOpener(applicationContext, app.engine) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The reader ViewModel, exposed to instrumented tests (same module, `internal`). */
    internal val readerViewModel: ReaderViewModel by viewModels()

    private var screen by mutableStateOf(Screen.Home)
    private var error by mutableStateOf<String?>(null)
    private var copyProgress by mutableStateOf<Float?>(null)
    private var copyJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewUri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (savedInstanceState == null) {
            if (viewUri != null) {
                openBook(viewUri)
            }
        } else {
            screen = Screen.valueOf(savedInstanceState.getString(KEY_SCREEN) ?: Screen.Home.name)
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
                    Screen.Reader -> ReaderScreen(
                        vm = readerViewModel,
                        onBack = { exitReader() },
                    )
                    Screen.Benchmark -> VariantFeatures.BenchmarkScreen(onBack = { screen = Screen.Home })
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
    }

    /** Volume keys turn pages only while the reader is shown, and are consumed there. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (screen == Screen.Reader) {
            when (keyAction(keyCode)) {
                TapAction.Next -> {
                    readerViewModel.next()
                    return true
                }
                TapAction.Previous -> {
                    readerViewModel.previous()
                    return true
                }
                TapAction.ToggleOverlay -> {
                    readerViewModel.toggleOverlay()
                    return true
                }
                null -> Unit
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        copyJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun exitReader() {
        screen = Screen.Home
        readerViewModel.release()
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
                    readerViewModel.attach(result)
                    screen = Screen.Reader
                }
                is OpenResult.Failed -> {
                    screen = Screen.Home
                    error = result.reason
                }
            }
        }
    }

    private companion object {
        const val KEY_SCREEN = "screen"
    }
}
