package com.proviewer.app.bench

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Window
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proviewer.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Device-only coverage for FrameRecorder's restart contract: a real Window is required for frame
 * metrics, so this cannot run on the JVM. The benchmark runner holds one recorder and does a
 * start/stop cycle per scenario, so a stop() must not poison the next start(). Pinned after
 * review: the pre-fix implementation reused one HandlerThread and crashed with
 * IllegalThreadStateException on the second start().
 */
@RunWith(AndroidJUnit4::class)
class FrameRecorderTest {

    @get:Rule
    val scenario = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun repeated_start_stop_cycles_do_not_throw() {
        val windowRef = AtomicReference<Window>()
        scenario.scenario.onActivity { windowRef.set(it.window) }
        val recorder = FrameRecorder(windowRef.get())
        assumeTrue(recorder.supported)

        // Pre-fix, the second start() threw IllegalThreadStateException (a terminated thread
        // cannot be restarted); the fix recreates the thread per cycle.
        repeat(3) { cycle ->
            recorder.start()
            recorder.stop()
        }

        // stop() returns and clears only the samples of its own cycle.
        recorder.start()
        recorder.stop()
        assertTrue("stop() without start() must return an empty list", recorder.stop().isEmpty())
    }

    @Test
    fun second_cycle_still_collects_frames() {
        val windowRef = AtomicReference<Window>()
        scenario.scenario.onActivity { windowRef.set(it.window) }
        val window = windowRef.get()
        val recorder = FrameRecorder(window)
        assumeTrue(recorder.supported)

        recorder.start()
        assumeTrue("window rendered no frame; cannot verify collection", awaitFrame(window))
        Thread.sleep(SETTLE_MS)
        val first = recorder.stop()
        assertTrue("first cycle collected no frames", first.isNotEmpty())
        assertTrue(first.all { it.totalNs > 0 })

        // The restart under test: a fresh handler thread must deliver frames again. The probe
        // latch only proves a notification was posted to the recorder's handler thread, and the
        // test thread's stop() can in principle outrun its dispatch, so allow a bounded retry.
        // Each retry is itself a full restart cycle, so it adds restart coverage.
        var second: List<FrameSample> = emptyList()
        var anyFrameRendered = false
        repeat(RESTART_RETRIES) {
            recorder.start()
            val rendered = awaitFrame(window)
            anyFrameRendered = anyFrameRendered || rendered
            if (rendered) Thread.sleep(SETTLE_MS)
            second = recorder.stop()
            if (second.isNotEmpty()) return
        }
        assumeTrue("window rendered no frame in cycle 2; cannot verify collection", anyFrameRendered)
        assertTrue("second cycle collected no frames after restart", second.isNotEmpty())
    }

    /** Forces traversals until the window reports one frame to a temporary probe listener. */
    private fun awaitFrame(window: Window): Boolean {
        val got = CountDownLatch(1)
        val probe = Window.OnFrameMetricsAvailableListener { _, _, _ -> got.countDown() }
        val handler = Handler(Looper.getMainLooper())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            window.addOnFrameMetricsAvailableListener(probe, handler)
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + FRAME_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (got.await(100, TimeUnit.MILLISECONDS)) return true
                window.decorView.postInvalidate()
            }
            return false
        } finally {
            instrumentation.runOnMainSync {
                window.removeOnFrameMetricsAvailableListener(probe)
            }
        }
    }

    private companion object {
        const val FRAME_TIMEOUT_MS = 10_000L

        /** Margin so the recorder's handler thread dispatches a posted notification before stop(). */
        const val SETTLE_MS = 100L

        const val RESTART_RETRIES = 3
    }
}
