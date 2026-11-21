package com.proviewer.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.proviewer.app.bench.BenchmarkRunner
import com.proviewer.app.bench.BenchmarkScreen
import com.proviewer.app.bench.ThermalGate
import com.proviewer.app.bench.ThermalRefusalException
import com.proviewer.app.bench.toJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end benchmark coverage (Task 10): the whole [BenchmarkRunner] runs every scenario once per
 * upload strategy through the real [BenchmarkScreen] (real ReaderScreen hosting included) at
 * corpus scale 0.25, and the produced JSON must parse with the pinned schema. The run itself is
 * driven through the screen's Start button, exactly as the owner drives it.
 *
 * Emulator numbers are ignored by design (`validForPerformance == false`); only structure, counts
 * and refusal semantics are asserted. The 60 s/600 s budgets tolerate the documented cached-app
 * freezer stalls: they assert correctness, never timing.
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkRunTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // --- tests -----------------------------------------------------------------

    @Test
    fun full_run_produces_valid_json() {
        val runner = BenchmarkRunner(composeRule.activity, scale = 0.25f)
        composeRule.setContent { BenchmarkScreen(onBack = {}, runner = runner) }

        composeRule.onNodeWithTag("bench-start").performClick()
        // Start is confirmed by the runner flipping to running; the end by it flipping back.
        composeRule.waitUntil(60_000) { runner.running.value }
        composeRule.waitUntil(RUN_TIMEOUT_MS) { !runner.running.value }

        val result = runner.result.value
        assertNotNull("run produced no result (check bench-progress for the failure)", result)

        val json = JSONObject(toJson(result!!))
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals(0.25, json.getDouble("corpusScale"), 1e-9)
        assertTrue("emulator must flag isEmulator", json.getJSONObject("conditions").getBoolean("isEmulator"))
        assertFalse("emulator results are never valid for performance", json.getBoolean("validForPerformance"))

        val scenarios = json.getJSONArray("scenarios")
        assertEquals("all 4 scenario ids x 2 strategies must be present", 8, scenarios.length())
        val seen = mutableSetOf<String>()
        for (i in 0 until scenarios.length()) {
            val s = scenarios.getJSONObject(i)
            val label = "${s.getString("id")}/${s.getString("strategy")}"
            seen += label
            assertTrue("$label must not abort on a clean run", s.isNull("aborted"))
            when (s.getString("id")) {
                "a2", "a3-screentone", "a3-huge" -> assertTrue(
                    "$label must record frames on API 37",
                    s.getJSONObject("frames").getInt("frames") > 0,
                )
                "b" -> {
                    assertEquals("$label cold sample count", 10, s.getJSONObject("cold").getInt("n"))
                    assertEquals("$label prefetched sample count", 19, s.getJSONObject("prefetched").getInt("n"))
                }
            }
        }
        val expected = setOf("a2", "a3-screentone", "a3-huge", "b")
            .flatMap { id -> listOf("Software", "Hardware").map { "$id/$it" } }
            .toSet()
        assertEquals(expected, seen)
    }

    @Test
    fun thermal_refusal_blocks_start() {
        val runner = BenchmarkRunner(composeRule.activity, scale = 0.25f, guard = FakeGate(canStart = false))

        // Runner contract: run() refuses before doing any work, touching nothing.
        val refusal = runCatching { runBlocking { runner.run { } } }
        assertTrue(
            "run() must refuse with ThermalRefusalException when !canStart(), got ${refusal.exceptionOrNull()}",
            refusal.exceptionOrNull() is ThermalRefusalException,
        )
        assertNull("a refused run must not produce a result", runner.result.value)

        // Screen: the Start button is disabled, the reason is shown, and no results appear.
        composeRule.setContent { BenchmarkScreen(onBack = {}, runner = runner) }
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithTag("bench-start").fetchSemanticsNodes().any {
                it.config.contains(SemanticsProperties.Disabled)
            }
        }
        composeRule.onNodeWithTag("bench-start").assertIsNotEnabled()
        composeRule.onNodeWithText("too warm", substring = true).assertExists()
        composeRule.onNodeWithTag("bench-results").assertDoesNotExist()
    }

    /** Injectable thermal fake for the refusal test (the brief's "ThermalGuard fake"). */
    private class FakeGate(private val canStart: Boolean) : ThermalGate {
        override fun canStart(): Boolean = canStart
        override val tripped: MutableStateFlow<Boolean> = MutableStateFlow(false)
    }

    private companion object {
        /** Generous end-to-end budget; the whole suite targets a few minutes at scale 0.25. */
        const val RUN_TIMEOUT_MS = 600_000L
    }
}
