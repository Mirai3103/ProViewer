package com.proviewer.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start benchmark of the minified `benchmark` build. Also the guard against R8 stripping
 * uniffi/JNA: the engine version text must be on screen after launch (plan Review Focus 2).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startup() = benchmarkRule.measureRepeated(
        packageName = "com.proviewer.app",
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.None(),
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        val shown = device.wait(Until.hasObject(By.textStartsWith("ProViewer core ")), 5_000)
        check(shown) { "Engine version text not shown: R8 may have stripped uniffi/JNA" }
    }
}
