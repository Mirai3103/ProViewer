package com.proviewer.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.proviewer.app.bench.VariantFeatures
import com.proviewer.app.home.HomeScreen
import com.proviewer.app.open.BookOpener
import com.proviewer.app.reader.ReaderScreen
import com.proviewer.app.reader.ReaderViewModel
import com.proviewer.app.reader.TapAction
import com.proviewer.app.reader.keyAction

/**
 * Hosts the three Phase 2 screens with a plain screen state (no navigation library): [Home],
 * [Reader] and [Benchmark]. `ACTION_VIEW` intents open the reader. The reader's [ReaderViewModel]
 * survives rotation and also runs the open (copy progress, errors), so an open in flight survives
 * rotation too; only the current screen name is saved so the reader is restored.
 */
class MainActivity : ComponentActivity() {
    private enum class Screen { Home, Reader, Benchmark }

    private val app get() = application as ProViewerApp
    private val opener by lazy { BookOpener(applicationContext, app.engine) }

    /** The reader ViewModel, exposed to instrumented tests (same module, `internal`). */
    internal val readerViewModel: ReaderViewModel by viewModels()

    private var screen by mutableStateOf(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewUri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (savedInstanceState == null) {
            if (viewUri != null) {
                readerViewModel.open(opener, viewUri)
            }
        } else {
            val restored = Screen.valueOf(savedInstanceState.getString(KEY_SCREEN) ?: Screen.Home.name)
            // After process death the reader ViewModel is fresh (state == null), so a restored
            // Reader would be a black screen with no BackHandler; fall back to Home unless the
            // state survived (ViewModel retained, e.g. a configuration change).
            screen = if (restored == Screen.Reader && readerViewModel.state == null) Screen.Home else restored
        }
        setContent {
            MaterialTheme {
                // A finished open switches to the reader, also when it finished mid-rotation.
                val openedPending = readerViewModel.openedPending
                LaunchedEffect(openedPending) {
                    if (readerViewModel.consumeOpened()) screen = Screen.Reader
                }
                when (screen) {
                    Screen.Home -> HomeScreen(
                        onOpen = { uri -> readerViewModel.open(opener, uri) },
                        error = readerViewModel.openError,
                        copyProgress = readerViewModel.copyProgress,
                        onCancelCopy = { readerViewModel.cancelOpen() },
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

    private fun exitReader() {
        screen = Screen.Home
        readerViewModel.release()
    }

    private companion object {
        const val KEY_SCREEN = "screen"
    }
}
