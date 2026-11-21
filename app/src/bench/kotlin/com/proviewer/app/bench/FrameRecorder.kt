package com.proviewer.app.bench

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * Collects per-frame timing samples from a [Window] for the benchmark (Task 9). Start before the
 * frames of interest and stop to get the collected samples. The recorder is reusable across
 * start/stop cycles (the runner holds one recorder and cycles it per scenario): each [start]
 * creates a fresh private [HandlerThread] and [stop] tears it down, and [stop] returns only the
 * samples collected since its own [start]. The [FrameMetrics.INTENDED_VSYNC_TIMESTAMP] and
 * [FrameMetrics.DEADLINE] metrics only exist since API 31, so on older devices nothing is
 * registered and [stop] returns an empty list.
 */
class FrameRecorder(private val window: Window) {

    /** Frame metrics needed by the benchmark exist only since Android 12 (API 31). */
    val supported: Boolean = Build.VERSION.SDK_INT >= 31

    private var thread: HandlerThread? = null
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

    /**
     * Registers the frame metrics listener on a private handler thread; no-op when unsupported
     * or already running. A Java thread cannot be restarted after termination, so a fresh thread
     * is created per start rather than checking the previous one's isAlive: right after [stop]
     * that thread is quitting but may still report alive, and posting frame callbacks to a
     * quitting looper would silently drop them all.
     */
    fun start() {
        if (!supported || running) return
        thread?.quitSafely() // no-op unless a previous start() failed midway and left one idle
        val thread = HandlerThread("FrameRecorder").also { it.start() }
        this.thread = thread
        handler = Handler(thread.looper)
        window.addOnFrameMetricsAvailableListener(listener, handler!!)
        running = true
    }

    /**
     * Unregisters the listener, tears down the handler thread, and returns the samples collected
     * since [start] in arrival order, clearing them so the next cycle starts empty.
     */
    fun stop(): List<FrameSample> {
        if (running) {
            window.removeOnFrameMetricsAvailableListener(listener)
            handler = null
            thread?.quitSafely()
            thread = null
            running = false
        }
        return synchronized(collected) {
            val out = collected.toList()
            collected.clear()
            out
        }
    }
}
