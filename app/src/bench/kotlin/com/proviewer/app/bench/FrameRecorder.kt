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
 * creates a fresh private [HandlerThread], listener and sample list (a session) and [stop] tears
 * them down, and [stop] returns only the samples collected since its own [start]. Callbacks that
 * were already queued on the old handler when [stop] ran are dropped by their closed session, so
 * they can never leak into the next scenario. The [FrameMetrics.INTENDED_VSYNC_TIMESTAMP] and
 * [FrameMetrics.DEADLINE] metrics only exist since API 31, so on older devices nothing is
 * registered and [stop] returns an empty list.
 */
class FrameRecorder(private val window: Window) {

    /** Frame metrics needed by the benchmark exist only since Android 12 (API 31). */
    val supported: Boolean = Build.VERSION.SDK_INT >= 31

    /** One start/stop cycle: its own listener and samples; closed sessions drop late callbacks. */
    private class Session {
        val samples = mutableListOf<FrameSample>()
        var open = true

        // The FrameMetrics argument is only valid inside the callback, so the values are copied
        // into a FrameSample immediately. Calls arrive on the handler; the session is guarded for
        // stop().
        val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            val sample = normalizeFrameSample(
                FrameSample(
                    intendedVsyncNs = frameMetrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                    totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION),
                    deadlineNs = frameMetrics.getMetric(FrameMetrics.DEADLINE),
                    firstDraw = frameMetrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 0L,
                    raw = RAW_METRICS.map { frameMetrics.getMetric(it) },
                ),
            )
            synchronized(this) {
                if (open) samples += sample
            }
        }
    }

    private var thread: HandlerThread? = null
    private var session: Session? = null

    /**
     * Registers the frame metrics listener on a private handler thread; no-op when unsupported
     * or already running. A Java thread cannot be restarted after termination, so a fresh thread
     * is created per start rather than checking the previous one's isAlive: right after [stop]
     * that thread is quitting but may still report alive, and posting frame callbacks to a
     * quitting looper would silently drop them all.
     */
    fun start() {
        if (!supported || session != null) return
        thread?.quitSafely() // no-op unless a previous start() failed midway and left one idle
        val thread = HandlerThread("FrameRecorder").also { it.start() }
        this.thread = thread
        val session = Session()
        window.addOnFrameMetricsAvailableListener(session.listener, Handler(thread.looper))
        this.session = session
    }

    /**
     * Unregisters the listener, tears down the handler thread, and returns the samples collected
     * since [start] in arrival order. The session is closed first, so callbacks still queued on
     * the quitting handler are dropped; the next cycle starts with a fresh, empty session.
     */
    fun stop(): List<FrameSample> {
        val session = this.session ?: return emptyList()
        this.session = null
        window.removeOnFrameMetricsAvailableListener(session.listener)
        thread?.quitSafely()
        thread = null
        return synchronized(session) {
            session.open = false
            session.samples.toList()
        }
    }

    private companion object {
        /** Same order as [RAW_METRIC_NAMES]. */
        val RAW_METRICS = listOf(
            FrameMetrics.INTENDED_VSYNC_TIMESTAMP, FrameMetrics.VSYNC_TIMESTAMP,
            FrameMetrics.TOTAL_DURATION, FrameMetrics.DEADLINE,
            FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION,
            FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION,
            FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION,
            FrameMetrics.SWAP_BUFFERS_DURATION, FrameMetrics.GPU_DURATION,
            FrameMetrics.UNKNOWN_DELAY_DURATION, FrameMetrics.FIRST_DRAW_FRAME,
        )
    }
}
