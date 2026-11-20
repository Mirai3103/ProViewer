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
)

/** Jank statistics over the considered (non-first-draw) frames of a scenario run. */
data class JankSummary(
    val frames: Int,
    val janky: Int,
    val jankyPct: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
)

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
    val janky = considered.count { it.totalNs > it.deadlineNs }
    val totalsMs = considered.map { it.totalNs / 1_000_000.0 }
    return JankSummary(
        frames = considered.size,
        janky = janky,
        jankyPct = janky * 100.0 / considered.size,
        p50Ms = nearestRankPercentile(totalsMs, 50.0),
        p95Ms = nearestRankPercentile(totalsMs, 95.0),
        p99Ms = nearestRankPercentile(totalsMs, 99.0),
    )
}

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
