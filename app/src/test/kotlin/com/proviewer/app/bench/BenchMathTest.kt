package com.proviewer.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the pure benchmark math. Every expected value is derived from the definitions in
 * the Task 9 brief: janky means `totalNs > deadlineNs`, first-draw frames are excluded from the
 * jank summary, and percentiles are nearest-rank on `totalNs`.
 */
class BenchMathTest {

    private fun ms(value: Double): Long = (value * 1_000_000).toLong()

    private fun sample(
        totalMs: Double,
        deadlineMs: Double = 16.0,
        intendedVsyncNs: Long = 0L,
        firstDraw: Boolean = false,
    ) = FrameSample(
        intendedVsyncNs = intendedVsyncNs,
        totalNs = ms(totalMs),
        deadlineNs = ms(deadlineMs),
        firstDraw = firstDraw,
    )

    // --- jankSummary ----------------------------------------------------------

    @Test
    fun jank_summary_counts_one_over_deadline_out_of_100_as_1_pct_and_excludes_first_draw() {
        val samples = buildList {
            add(sample(totalMs = 1.0, deadlineMs = 0.5)) // the single janky frame
            repeat(99) { add(sample(totalMs = 1.0, deadlineMs = 16.0)) } // on-time frames
            // Would be janky (10 > 1) but must not be counted: first-draw frames are excluded.
            add(sample(totalMs = 10.0, deadlineMs = 1.0, firstDraw = true))
        }

        val s = jankSummary(samples)

        assertEquals(100, s.frames)
        assertEquals(1, s.janky)
        assertEquals(1.0, s.jankyPct, 1e-9)
    }

    @Test
    fun jank_summary_percentiles_are_nearest_rank_on_total_ns() {
        // Nearest-rank on [10, 20, 30, 40]: p50 = ceil(4*0.50) = 2nd value = 20,
        // p95 = ceil(4*0.95) = 4th value = 40, p99 = 40.
        val samples = listOf(10.0, 20.0, 30.0, 40.0).map { sample(totalMs = it, deadlineMs = 15.0) }

        val s = jankSummary(samples)

        assertEquals(4, s.frames)
        assertEquals(3, s.janky) // 20, 30 and 40 all exceed the 15 ms deadline
        assertEquals(75.0, s.jankyPct, 1e-9)
        assertEquals(20.0, s.p50Ms, 1e-9)
        assertEquals(40.0, s.p95Ms, 1e-9)
        assertEquals(40.0, s.p99Ms, 1e-9)
    }

    @Test
    fun jank_summary_is_all_zeros_without_considered_frames() {
        val empty = jankSummary(emptyList())
        assertEquals(0, empty.frames)
        assertEquals(0, empty.janky)
        assertEquals(0.0, empty.jankyPct, 1e-9)

        val onlyFirstDraw = jankSummary(listOf(sample(totalMs = 5.0, firstDraw = true)))
        assertEquals(0, onlyFirstDraw.frames)
        assertEquals(0.0, onlyFirstDraw.p50Ms, 1e-9)
    }

    // --- frameEndContaining ---------------------------------------------------

    @Test
    fun frame_end_containing_matches_inside_and_inclusive_boundaries() {
        val frames = listOf(
            sample(totalMs = 50.0, intendedVsyncNs = ms(100.0)), // covers [100, 150] ms
            sample(totalMs = 50.0, intendedVsyncNs = ms(200.0)), // covers [200, 250] ms
        )

        // Inside the first frame returns intendedVsyncNs + totalNs.
        assertEquals(ms(150.0), frameEndContaining(ms(120.0), frames))
        // Both boundaries are inclusive.
        assertEquals(ms(150.0), frameEndContaining(ms(100.0), frames))
        assertEquals(ms(150.0), frameEndContaining(ms(150.0), frames))
        assertEquals(ms(250.0), frameEndContaining(ms(200.0), frames))
        assertEquals(ms(250.0), frameEndContaining(ms(250.0), frames))
    }

    @Test
    fun frame_end_containing_is_null_when_no_frame_covers_draw_ns() {
        val frames = listOf(sample(totalMs = 50.0, intendedVsyncNs = ms(100.0)))

        assertNull(frameEndContaining(ms(99.0), frames)) // before the start
        assertNull(frameEndContaining(ms(151.0), frames)) // in the gap after the end
        assertNull(frameEndContaining(ms(1000.0), frames)) // beyond every frame
    }

    // --- latencySummary -------------------------------------------------------

    @Test
    fun latency_summary_gives_n_p50_and_max_on_known_list() {
        // Nearest-rank on [10, 20, 30, 40]: p50 = 2nd value = 20, p95 = 4th value = 40.
        val s = latencySummary(listOf(10.0, 20.0, 30.0, 40.0))

        assertEquals(4, s.n)
        assertEquals(20.0, s.p50Ms, 1e-9)
        assertEquals(40.0, s.p95Ms, 1e-9)
        assertEquals(40.0, s.maxMs, 1e-9)
    }
}
