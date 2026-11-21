package com.proviewer.app

import android.app.Application
import uniffi.proviewer_core.Engine
import uniffi.proviewer_core.defaultEngineConfig
import java.io.File

/**
 * Process-wide application state. Owns the single [Engine] used by every screen and sweeps the
 * opened-copy cache directory at start (copies are also deleted when their book closes).
 */
class ProViewerApp : Application() {

    /** One engine per process, shared by the reader and the benchmark screen. */
    val engine: Engine = Engine(defaultEngineConfig())

    override fun onCreate() {
        super.onCreate()
        // Delete any leftover copies from a previous process.
        File(cacheDir, "opened").deleteRecursively()
    }
}
