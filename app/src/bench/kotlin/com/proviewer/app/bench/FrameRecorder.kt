package com.proviewer.app.bench

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * Collects per-frame timing samples from a [Window] for the benchmark (Task 9). Start before the
 * frames of interest and stop to get the collected samples. The `INTENDED_VSYNC_TIME_NS` and
 * `DEADLINE_TIME_NS` metrics only exist since API 31, so on older devices nothing is registered
 * and [stop] returns an empty list.
 */
class FrameRecorder(private val window: Window) {

    /** Frame metrics needed by the benchmark exist only since Android 12 (API 31). */
    val supported: Boolean = Build.VERSION.SDK_INT >= 31

    private val thread = HandlerThread("FrameRecorder")
    private val collected = mutableListOf<FrameSample>()
    private var handler: Handler? = null
    private var running = false

    // The FrameMetrics argument is only valid inside the callback, so the values are copied into a
    // FrameSample immediately. Calls arrive on [handler]; [collected] is guarded for stop().
    private val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
        synchronized(collected) {
            collected += FrameSample(
                intendedVsyncNs = frameMetrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION),
                deadlineNs = frameMetrics.getMetric(FrameMetrics.DEADLINE),
                firstDraw = frameMetrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 0L,
            )
        }
    }

    /** Registers the frame metrics listener on a private handler thread; no-op when unsupported. */
    fun start() {
        if (!supported || running) return
        thread.start()
        handler = Handler(thread.looper)
        window.addOnFrameMetricsAvailableListener(listener, handler!!)
        running = true
    }

    /** Unregisters the listener and returns the collected samples in arrival order. */
    fun stop(): List<FrameSample> {
        if (running) {
            window.removeOnFrameMetricsAvailableListener(listener)
            handler = null
            thread.quitSafely()
            running = false
        }
        return synchronized(collected) {
            val out = collected.toList()
            collected.clear()
            out
        }
    }
}
