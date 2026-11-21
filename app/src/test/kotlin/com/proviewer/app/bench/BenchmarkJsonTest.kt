package com.proviewer.app.bench

import com.proviewer.app.engine.UploadStrategy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the benchmark JSON schema. The result is parsed back through [JSONObject] and every
 * field defined in the Task 9 brief is checked, plus the derived `validForPerformance` rule.
 */
class BenchmarkJsonTest {

    private fun conditions(isEmulator: Boolean = false) = Conditions(
        model = "Pixel 8",
        manufacturer = "Google",
        sdkInt = 34,
        release = "14",
        isEmulator = isEmulator,
        refreshHz = 60f,
        maxRefreshHz = 120f,
        thermalStatus = 0,
        batteryPct = 80,
        charging = false,
    )

    private fun scenario(
        id: String = "a2",
        order: Int = 0,
        before: Float = 120f,
        after: Float = 120f,
        aborted: String? = null,
    ) = ScenarioResult(
        id = id,
        strategy = UploadStrategy.Hardware,
        order = order,
        refreshBeforeHz = before,
        refreshAfterHz = after,
        thermalEnd = 0,
        frames = JankSummary(100, 1, 1.0, p50Ms = 8.0, p95Ms = 16.0, p99Ms = 20.0),
        cold = LatencySummary(4, p50Ms = 20.0, p95Ms = 40.0, maxMs = 40.0),
        prefetched = LatencySummary(3, p50Ms = 5.0, p95Ms = 9.0, maxMs = 9.0),
        fellBackToSoftware = false,
        aborted = aborted,
    )

    private fun result(
        conditions: Conditions = conditions(),
        corpusScale: Float = 1f,
        scenarios: List<ScenarioResult> = listOf(scenario()),
    ) = BenchmarkResult(conditions, appVersion = "0.1.0", gitSha = "19584e0", corpusScale, scenarios)

    @Test
    fun round_trip_through_json_object_has_every_field() {
        val r = result(
            scenarios = listOf(scenario(order = 0), scenario(id = "b", order = 1)),
        )
        val json = JSONObject(toJson(r))

        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals(CorpusGenerator.MARKER, json.getString("generator"))
        assertTrue(json.getBoolean("validForPerformance"))
        assertEquals("0.1.0", json.getString("appVersion"))
        assertEquals("19584e0", json.getString("gitSha"))
        assertEquals(1.0, json.getDouble("corpusScale"), 1e-9)

        val c = json.getJSONObject("conditions")
        assertEquals("Pixel 8", c.getString("model"))
        assertEquals("Google", c.getString("manufacturer"))
        assertEquals(34, c.getInt("sdkInt"))
        assertEquals("14", c.getString("release"))
        assertFalse(c.getBoolean("isEmulator"))
        assertEquals(60.0, c.getDouble("refreshHz"), 1e-6)
        assertEquals(120.0, c.getDouble("maxRefreshHz"), 1e-6)
        assertEquals(0, c.getInt("thermalStatus"))
        assertEquals(80, c.getInt("batteryPct"))
        assertFalse(c.getBoolean("charging"))

        val scenarios = json.getJSONArray("scenarios")
        assertEquals(2, scenarios.length())
        val s = scenarios.getJSONObject(0)
        assertEquals("a2", s.getString("id"))
        assertEquals("Hardware", s.getString("strategy"))
        assertEquals(0, s.getInt("order"))
        assertEquals(120.0, s.getDouble("refreshBeforeHz"), 1e-6)
        assertEquals(120.0, s.getDouble("refreshAfterHz"), 1e-6)
        assertEquals(0, s.getInt("thermalEnd"))
        assertFalse(s.getBoolean("fellBackToSoftware"))
        assertTrue(s.isNull("aborted"))
        assertFalse(s.getBoolean("rateChanged"))
        assertFalse(s.getBoolean("belowMaxRate")) // 120 is not below 120 - 0.5

        val f = s.getJSONObject("frames")
        assertEquals(100, f.getInt("frames"))
        assertEquals(1, f.getInt("janky"))
        assertEquals(1.0, f.getDouble("jankyPct"), 1e-9)
        assertEquals(8.0, f.getDouble("p50Ms"), 1e-9)
        assertEquals(16.0, f.getDouble("p95Ms"), 1e-9)
        assertEquals(20.0, f.getDouble("p99Ms"), 1e-9)

        val cold = s.getJSONObject("cold")
        assertEquals(4, cold.getInt("n"))
        assertEquals(20.0, cold.getDouble("p50Ms"), 1e-9)
        assertEquals(40.0, cold.getDouble("p95Ms"), 1e-9)
        assertEquals(40.0, cold.getDouble("maxMs"), 1e-9)

        val prefetched = s.getJSONObject("prefetched")
        assertEquals(3, prefetched.getInt("n"))
        assertEquals(5.0, prefetched.getDouble("p50Ms"), 1e-9)
        assertEquals(9.0, prefetched.getDouble("maxMs"), 1e-9)

        assertEquals("b", scenarios.getJSONObject(1).getString("id"))
    }

    @Test
    fun valid_for_performance_is_false_on_an_emulator() {
        val json = JSONObject(toJson(result(conditions = conditions(isEmulator = true))))

        assertFalse(json.getBoolean("validForPerformance"))
        assertTrue(json.getJSONObject("conditions").getBoolean("isEmulator"))
    }

    @Test
    fun valid_for_performance_is_false_at_partial_corpus_scale() {
        val json = JSONObject(toJson(result(corpusScale = 0.25f)))

        assertFalse(json.getBoolean("validForPerformance"))
        assertEquals(0.25, json.getDouble("corpusScale"), 1e-9)
    }

    @Test
    fun valid_for_performance_is_false_when_a_scenario_rate_changed() {
        val json = JSONObject(toJson(result(scenarios = listOf(scenario(before = 60f, after = 120f)))))

        val s = json.getJSONArray("scenarios").getJSONObject(0)
        assertTrue(s.getBoolean("rateChanged"))
        assertFalse(json.getBoolean("validForPerformance"))
    }

    @Test
    fun valid_for_performance_is_false_when_a_scenario_aborted() {
        val json = JSONObject(toJson(result(scenarios = listOf(scenario(aborted = "thermal")))))

        val s = json.getJSONArray("scenarios").getJSONObject(0)
        assertEquals("thermal", s.getString("aborted"))
        assertFalse(json.getBoolean("validForPerformance"))
    }

    @Test
    fun below_max_rate_flag_is_true_when_before_hz_is_under_max() {
        val json = JSONObject(toJson(result(scenarios = listOf(scenario(before = 60f, after = 60f)))))

        val s = json.getJSONArray("scenarios").getJSONObject(0)
        assertTrue(s.getBoolean("belowMaxRate")) // 60 < 120 - 0.5
        assertFalse(s.getBoolean("rateChanged"))
    }

    @Test
    fun null_summaries_serialize_as_json_null() {
        val aborted = ScenarioResult(
            id = "a3-huge",
            strategy = UploadStrategy.Software,
            order = 2,
            refreshBeforeHz = 60f,
            refreshAfterHz = 60f,
            thermalEnd = 1,
            frames = null,
            cold = null,
            prefetched = null,
            fellBackToSoftware = true,
            aborted = "thermal",
        )
        val json = JSONObject(toJson(result(scenarios = listOf(aborted))))
        val s = json.getJSONArray("scenarios").getJSONObject(0)

        assertTrue(s.isNull("frames"))
        assertTrue(s.isNull("cold"))
        assertTrue(s.isNull("prefetched"))
        assertEquals("Software", s.getString("strategy"))
        assertTrue(s.getBoolean("fellBackToSoftware"))
        assertEquals(1, s.getInt("thermalEnd"))
    }
}
