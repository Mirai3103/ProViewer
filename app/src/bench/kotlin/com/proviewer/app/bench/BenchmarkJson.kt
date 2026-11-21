package com.proviewer.app.bench

import com.proviewer.app.engine.UploadStrategy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Benchmark report document (Task 9): one [BenchmarkResult] per run, serialized by [toJson] with
 * `schemaVersion: 1`. The JSON is the only exchange format between the benchmark runner (Task 10)
 * and post-processing tools, so every field is pinned by JVM tests.
 */
data class ScenarioResult(
    val id: String, // "a2" | "a3-screentone" | "a3-huge" | "b"
    val strategy: UploadStrategy,
    val order: Int,
    val refreshBeforeHz: Float,
    val refreshAfterHz: Float,
    val thermalEnd: Int,
    val frames: JankSummary?,
    val cold: LatencySummary?,
    val prefetched: LatencySummary?,
    val fellBackToSoftware: Boolean,
    val aborted: String?,
)

data class BenchmarkResult(
    val conditions: Conditions,
    val appVersion: String,
    val gitSha: String,
    val corpusScale: Float,
    val scenarios: List<ScenarioResult>,
)

/** Serialize [r] into the schemaVersion-1 benchmark report JSON. */
fun toJson(r: BenchmarkResult): String {
    val root = JSONObject()
    root.put("schemaVersion", 1)
    root.put("generator", CorpusGenerator.MARKER)
    root.put("validForPerformance", isValidForPerformance(r))
    root.put("conditions", conditionsJson(r.conditions))
    root.put("appVersion", r.appVersion)
    root.put("gitSha", r.gitSha)
    root.put("corpusScale", r.corpusScale.toDouble())
    root.put("scenarios", JSONArray().apply {
        r.scenarios.forEach { put(scenarioJson(it, r.conditions.maxRefreshHz)) }
    })
    return root.toString()
}

/**
 * A run counts as valid for performance claims only when it happened on real hardware, with a
 * full-scale corpus, and every scenario kept its refresh rate and completed without aborting.
 */
private fun isValidForPerformance(r: BenchmarkResult): Boolean =
    !r.conditions.isEmulator &&
        r.corpusScale == 1f &&
        r.scenarios.none { it.refreshBeforeHz != it.refreshAfterHz || it.aborted != null }

private fun conditionsJson(c: Conditions): JSONObject = JSONObject().apply {
    put("model", c.model)
    put("manufacturer", c.manufacturer)
    put("sdkInt", c.sdkInt)
    put("release", c.release)
    put("isEmulator", c.isEmulator)
    put("refreshHz", c.refreshHz.toDouble())
    put("maxRefreshHz", c.maxRefreshHz.toDouble())
    put("thermalStatus", c.thermalStatus)
    put("batteryPct", c.batteryPct)
    put("charging", c.charging)
}

private fun scenarioJson(s: ScenarioResult, maxRefreshHz: Float): JSONObject = JSONObject().apply {
    put("id", s.id)
    put("strategy", s.strategy.name)
    put("order", s.order)
    put("refreshBeforeHz", s.refreshBeforeHz.toDouble())
    put("refreshAfterHz", s.refreshAfterHz.toDouble())
    put("thermalEnd", s.thermalEnd)
    put("frames", s.frames?.let { jankJson(it) } ?: JSONObject.NULL)
    put("cold", s.cold?.let { latencyJson(it) } ?: JSONObject.NULL)
    put("prefetched", s.prefetched?.let { latencyJson(it) } ?: JSONObject.NULL)
    put("fellBackToSoftware", s.fellBackToSoftware)
    put("aborted", s.aborted ?: JSONObject.NULL)
    // Derived flags for the post-processing tools.
    put("rateChanged", s.refreshBeforeHz != s.refreshAfterHz)
    put("belowMaxRate", s.refreshBeforeHz < maxRefreshHz - 0.5)
}

private fun jankJson(j: JankSummary): JSONObject = JSONObject().apply {
    put("frames", j.frames)
    put("janky", j.janky)
    put("jankyPct", j.jankyPct)
    put("p50Ms", j.p50Ms)
    put("p95Ms", j.p95Ms)
    put("p99Ms", j.p99Ms)
    if (j.deadlineFallbacks > 0) put("deadlineFallbacks", j.deadlineFallbacks)
    if (j.debugSamples.isNotEmpty()) {
        put("debugSampleFields", JSONArray(RAW_METRIC_NAMES))
        put("debugSamples", JSONArray().apply { j.debugSamples.forEach { put(JSONArray(it)) } })
    }
}

private fun latencyJson(l: LatencySummary): JSONObject = JSONObject().apply {
    put("n", l.n)
    put("p50Ms", l.p50Ms)
    put("p95Ms", l.p95Ms)
    put("maxMs", l.maxMs)
}
