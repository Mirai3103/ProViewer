package com.proviewer.app.bench

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Bench-enabled variant features (compiled into the `debug` and `benchmark` build types). */
object VariantFeatures {
    val benchmarkAvailable: Boolean = true

    // Placeholder until Task 10 wires the real benchmark runner and results screen.
    @Composable
    fun BenchmarkScreen(onBack: () -> Unit) {
        Text("Benchmark (placeholder)")
    }
}
