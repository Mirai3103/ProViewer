package com.proviewer.app.bench

import androidx.compose.runtime.Composable
import com.proviewer.app.bench.BenchmarkScreen as RealBenchmarkScreen

/** Bench-enabled variant features (compiled into the `debug` and `benchmark` build types). */
object VariantFeatures {
    val benchmarkAvailable: Boolean = true

    /** The real benchmark screen (Task 10): runner, scenarios, results and JSON share. */
    @Composable
    fun BenchmarkScreen(onBack: () -> Unit) {
        RealBenchmarkScreen(onBack = onBack)
    }
}
