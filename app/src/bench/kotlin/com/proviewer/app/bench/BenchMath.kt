package com.proviewer.app.bench

import kotlin.math.ceil

/**
 * Pure benchmark measurement math (Task 9). No Android types: exercised by JVM unit tests and
 * consumed by the Task 10 benchmark runner.
 */

/** One frame as reported by `Window.addOnFrameMetricsAvailableListener` (API 31+ metrics). */
data class FrameSample(
    val intendedVsyncNs: Long,
    val totalNs: Long,
    val deadlineNs: Long,
    val firstDraw: Boolean,
    /** Diagnostic: every other FrameMetrics value, in [RAW_METRIC_NAMES] order. */
    val raw: List<Long> = emptyList(),
)

/** Names of [FrameSample.raw] entries, emitted next to the samples in the JSON. */
val RAW_METRIC_NAMES = listOf(
    "INTENDED_VSYNC_TIMESTAMP", "VSYNC_TIMESTAMP", "TOTAL_DURATION", "DEADLINE",
    "INPUT_HANDLING_DURATION", "ANIMATION_DURATION", "LAYOUT_MEASURE_DURATION", "DRAW_DURATION",
    "SYNC_DURATION", "COMMAND_ISSUE_DURATION", "SWAP_BUFFERS_DURATION", "GPU_DURATION",
    "UNKNOWN_DELAY_DURATION", "FIRST_DRAW_FRAME",
)

/** Jank statistics over the considered (non-first-draw) frames of a scenario run. */
data class JankSummary(
    val frames: Int,
    val janky: Int,
    val jankyPct: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    /** Diagnostic: raw metrics of the first few considered frames (see [RAW_METRIC_NAMES]). */
    val debugSamples: List<List<Long>> = emptyList(),
    /** Frames whose reported deadline was not positive and was replaced (see [jankSummary]). */
    val deadlineFallbacks: Int = 0,
)

private const val IMPLAUSIBLE_TOTAL_NS = 1_000_000_000_000L // 1000 s: no real frame takes this long
private const val OFFSET_QUANTUM_NS = 1_000_000_000L

/**
 * Works around a vendor bug seen on a vivo V2364A (Android 15): `TOTAL_DURATION` and `DEADLINE`
 * both carry the same constant offset of ~1.4469e15 ns (whole seconds plus well under 1 ms of
 * noise). When [FrameSample.totalNs] is implausibly large, the whole-second part of it is
 * subtracted from both fields; the remainder is the real duration to within ~1 ms. Samples from
 * healthy devices pass through untouched.
 */
fun normalizeFrameSample(s: FrameSample): FrameSample {
    if (s.totalNs < IMPLAUSIBLE_TOTAL_NS) return s
    val offset = s.totalNs / OFFSET_QUANTUM_NS * OFFSET_QUANTUM_NS
    return s.copy(totalNs = s.totalNs - offset, deadlineNs = s.deadlineNs - offset)
}

/** Frame-load latency statistics in milliseconds (Task 10 feeds it the measured waits). */
data class LatencySummary(
    val n: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Double,
)

/**
 * Summarize [samples]: first-draw frames are excluded, a frame is janky when
 * `totalNs > deadlineNs`, and the percentiles are nearest-rank on `totalNs`. An empty list
 * (or one holding only first-draw frames) yields an all-zero summary.
 */
fun jankSummary(samples: List<FrameSample>): JankSummary {
    val considered = samples.filterNot { it.firstDraw }
    if (considered.isEmpty()) {
        return JankSummary(frames = 0, janky = 0, jankyPct = 0.0, p50Ms = 0.0, p95Ms = 0.0, p99Ms = 0.0)
    }
    // Some devices report a negative/zero deadline for frames that follow an idle gap, which would
    // count a 3 ms frame as janky. Such frames use the median of the run's valid deadlines instead.
    val validDeadlines = considered.map { it.deadlineNs }.filter { it > 0 }.sorted()
    val fallbackDeadline = validDeadlines.getOrNull(validDeadlines.size / 2) ?: DEFAULT_DEADLINE_NS
    val fallbacks = considered.count { it.deadlineNs <= 0 }
    val janky = considered.count { it.totalNs > (if (it.deadlineNs > 0) it.deadlineNs else fallbackDeadline) }
    val totalsMs = considered.map { it.totalNs / 1_000_000.0 }
    return JankSummary(
        frames = considered.size,
        janky = janky,
        jankyPct = janky * 100.0 / considered.size,
        p50Ms = nearestRankPercentile(totalsMs, 50.0),
        p95Ms = nearestRankPercentile(totalsMs, 95.0),
        p99Ms = nearestRankPercentile(totalsMs, 99.0),
        debugSamples = (considered.take(4) + considered.takeLast(4)).map { it.raw },
        deadlineFallbacks = fallbacks,
    )
}

/** 60 Hz frame budget, used only when a run has no valid deadline at all. */
private const val DEFAULT_DEADLINE_NS = 16_666_667L

/**
 * The end timestamp (`intendedVsyncNs + totalNs`) of the first frame in [frames] whose interval
 * `intendedVsyncNs..intendedVsyncNs + totalNs` contains [drawNs] (both ends inclusive), or
 * null when no frame covers it.
 */
fun frameEndContaining(drawNs: Long, frames: List<FrameSample>): Long? {
    for (frame in frames) {
        val end = frame.intendedVsyncNs + frame.totalNs
        if (drawNs >= frame.intendedVsyncNs && drawNs <= end) return end
    }
    return null
}

/**
 * Summarize [ms] (frame-load latencies in milliseconds): count, nearest-rank p50/p95, and max.
 * An empty list yields an all-zero summary.
 */
fun latencySummary(ms: List<Double>): LatencySummary {
    if (ms.isEmpty()) {
        return LatencySummary(n = 0, p50Ms = 0.0, p95Ms = 0.0, maxMs = 0.0)
    }
    return LatencySummary(
        n = ms.size,
        p50Ms = nearestRankPercentile(ms, 50.0),
        p95Ms = nearestRankPercentile(ms, 95.0),
        maxMs = ms.max(),
    )
}

/** Nearest-rank percentile [percent] (0..100) of [values]: rank = ceil(percent/100 * n). */
private fun nearestRankPercentile(values: List<Double>, percent: Double): Double {
    val sorted = values.sorted()
    val rank = ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}
