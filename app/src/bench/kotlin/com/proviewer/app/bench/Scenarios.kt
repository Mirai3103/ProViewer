package com.proviewer.app.bench

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import com.proviewer.app.engine.UploadStrategy
import com.proviewer.app.reader.ReaderAutomation
import com.proviewer.app.reader.ReaderProbe
import com.proviewer.app.reader.ReaderViewModel
import com.proviewer.app.reader.WebtoonAutomation
import com.proviewer.app.reader.ZoomAutomation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/** The corpus archives one full benchmark run needs, by name. */
internal val BENCH_CORPUS_FILES = listOf("mixed-small.cbz", "screentone.cbz", "huge-single.cbz", "webtoon-long.cbz")

/** Scenario ids in execution order; every scenario runs once per upload strategy. */
internal val BENCH_SCENARIO_IDS = listOf("a1", "a2", "a3-screentone", "a3-huge", "b")

/**
 * Everything a scenario needs from the runner. The runner implements it per scenario invocation
 * (the strategy changes between scenario groups), so scenario bodies stay pure scripts.
 */
internal interface ScenarioContext {
    /** The upload strategy the scenario's books are rendered with. */
    val strategy: UploadStrategy

    /** The bench probe fed by the hosted reader (sharp-draw events). */
    val probe: BenchProbe

    /** The runner's reusable frame recorder (one start/stop cycle per measurement window). */
    val recorder: FrameRecorder

    /** Throws [BenchmarkAbort] when the thermal guard has tripped; called between steps. */
    fun abortCheckpoint()

    /**
     * Hosts the real ReaderScreen with a freshly opened [file] for the duration of [block],
     * waits until page 0 is rendered and idle (or, with [webtoon], until the auto-resolved
     * Webtoon session has its first tiles ready), and tears the reader down afterwards. With
     * [webtoon] the layout choice stays at Auto so a strip book resolves Webtoon (a1's "auto
     * Webtoon"); otherwise the Single layout is pinned as every pager scenario assumes.
     */
    suspend fun <T> withFreshReader(
        file: File,
        webtoon: Boolean = false,
        block: suspend (ReaderViewModel, ReaderAutomation) -> T,
    ): T
}

/** Raw scenario data, before the runner turns it into a [ScenarioResult]. */
internal data class ScenarioOutcome(
    val frames: JankSummary? = null,
    val coldMs: List<Double>? = null,
    val prefetchedMs: List<Double>? = null,
    val fellBackToSoftware: Boolean = false,
    val aborted: String? = null,
    /** a1 only: frames that drew a placeholder in the Webtoon viewport's visible area. */
    val placeholderFrames: Int? = null,
    /** a1 only: the image budget's highest held bytes during the measurement window. */
    val peakImageBytes: Long? = null,
)

/**
 * Observability seam over the reader for the benchmark: records the absolute
 * [ReaderProbe.onSharpPageDrawn] timestamps the b scenario needs for its t1 computation.
 */
class BenchProbe : ReaderProbe {
    private val lock = Any()
    private val sharpDraws = mutableListOf<Pair<Int, Long>>()
    private var placeholderFrames = 0

    override fun onSharpPageDrawn(page: Int, drawNanos: Long) {
        synchronized(lock) { sharpDraws += page to drawNanos }
    }

    override fun onPlaceholderDrawn() {
        synchronized(lock) { placeholderFrames += 1 }
    }

    /** Forgets all recorded events; called right before a new book is attached. */
    fun clear() = synchronized(lock) {
        sharpDraws.clear()
        placeholderFrames = 0
    }

    /** Forgets the placeholder count; a1 calls it at its measurement window's start. */
    fun clearPlaceholderCount() = synchronized(lock) { placeholderFrames = 0 }

    /** The [ReaderProbe.onPlaceholderDrawn] count since the last [clearPlaceholderCount]. */
    fun placeholderCount(): Int = synchronized(lock) { placeholderFrames }

    /** True when page [page]'s sharp bitmap has been drawn since the last [clear]. */
    fun hasSharpDraw(page: Int): Boolean = synchronized(lock) { sharpDraws.any { it.first == page } }

    /**
     * The first recorded sharp-draw timestamp of [page] at or after [afterNs], or null. Note the
     * probe fires once per bitmap: a page that sat adjacent to the current one has already drawn
     * (and fired) at rest before any turn to it, so a null here is expected for prefetched turns.
     */
    fun sharpDrawAtOrAfter(page: Int, afterNs: Long): Long? = synchronized(lock) {
        sharpDraws.firstOrNull { it.first == page && it.second >= afterNs }?.second
    }

    /**
     * Waits (real-time deadline) for the first sharp-draw event of [page] at or after [afterNs];
     * null when none appears within [timeoutMs].
     */
    suspend fun awaitSharpDraw(page: Int, afterNs: Long, timeoutMs: Long): Long? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            sharpDrawAtOrAfter(page, afterNs)?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            delay(SHARP_DRAW_POLL_MS)
        }
    }

    private companion object {
        const val SHARP_DRAW_POLL_MS = 10L
    }
}

// --- scenarios ----------------------------------------------------------------

/**
 * Runs a scripted pager action, tolerating the reader's own animation driver: `ReaderScreen`'s
 * `LaunchedEffect(state.page)` races the automation on the same `animateScrollToPage`, and the
 * newer mutation cancels the older one with a `CancellationException` (the internal
 * `MutationInterruptedException`). The interrupted caller's own job is NOT cancelled — the
 * effect's animation continues to the same target page — so the interruption is swallowed when
 * the current coroutine is still active, and a genuine run cancellation is rethrown.
 */
internal suspend fun tolerateInterruption(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        Log.d(TAG, "pager mutation interrupted by the reader's own animation; tolerated")
    }
}

/**
 * a2: scripted animated page turns through the real pager on `mixed-small.cbz`, one turn per
 * 450 ms, forward through the book then back; frames are recorded across the whole sequence.
 */
internal suspend fun scenarioA2(ctx: ScenarioContext, file: File): ScenarioOutcome =
    ctx.withFreshReader(file) { vm, automation ->
        val count = vm.state?.count ?: throw IllegalStateException("reader not attached")
        automation.awaitIdle()
        ctx.recorder.start()
        for (p in 0 until count - 1) {
            ctx.abortCheckpoint()
            tolerateInterruption { automation.turnTo(p + 1, animate = true) }
            delay(A2_TURN_INTERVAL_MS)
        }
        for (p in count - 1 downTo 1) {
            ctx.abortCheckpoint()
            tolerateInterruption { automation.turnTo(p - 1, animate = true) }
            delay(A2_TURN_INTERVAL_MS)
        }
        delay(METRICS_SETTLE_MS)
        val frames = ctx.recorder.stop()
        ScenarioOutcome(frames = jankSummary(frames), fellBackToSoftware = vm.renderer?.fellBack == true)
    }

/**
 * a3 (`id` only serves the progress log): three cycles of animated zoom to 3x around the viewport
 * centre (then 300 ms), three animated pans (300 ms each) and a reset, on one book; frames are
 * recorded across the whole run so every settle's detail render is included.
 */
internal suspend fun scenarioA3(ctx: ScenarioContext, file: File, id: String): ScenarioOutcome =
    ctx.withFreshReader(file) { vm, automation ->
        val zoom = awaitZoomAutomation(automation, id)
        val view = vm.viewport ?: throw IllegalStateException("no viewport laid out")
        val centre = Offset(view.width / 2f, view.height / 2f)
        val pans = listOf(
            Offset(-view.width / 3f, 0f),
            Offset(view.width / 2f, view.height / 4f),
            Offset(-view.width / 6f, -view.height / 4f),
        )
        ctx.recorder.start()
        repeat(A3_CYCLES) {
            ctx.abortCheckpoint()
            zoom.zoomTo(3f, centre, animate = true)
            delay(A3_STEP_WAIT_MS)
            for (delta in pans) {
                ctx.abortCheckpoint()
                zoom.panBy(delta, animate = true)
                delay(A3_STEP_WAIT_MS)
            }
            zoom.reset()
            delay(A3_STEP_WAIT_MS)
        }
        delay(METRICS_SETTLE_MS)
        val frames = ctx.recorder.stop()
        ScenarioOutcome(frames = jankSummary(frames), fellBackToSoftware = vm.renderer?.fellBack == true)
    }

/** Waits until the current page has published its [ZoomAutomation]. */
private suspend fun awaitZoomAutomation(automation: ReaderAutomation, id: String): ZoomAutomation {
    val deadline = SystemClock.elapsedRealtime() + ACTION_TIMEOUT_MS
    while (true) {
        automation.zoom?.let { return it }
        if (SystemClock.elapsedRealtime() >= deadline) {
            throw IllegalStateException("$id: zoom automation never published")
        }
        delay(STEP_POLL_MS)
    }
}

/**
 * a1: Webtoon scroll on `webtoon-long.cbz` (auto-resolved Webtoon at 1×; spec 6.6): a steady
 * scripted descent at a constant speed — one [WebtoonAutomation.scrollBy] per composed frame —
 * to the column's end, then [A1_FLING_COUNT] flings back up the book, each followed by a settle.
 * Frames are recorded across the whole window; the window's [ReaderProbe.onPlaceholderDrawn]
 * count and the image budget's peak bytes are reported for information only.
 */
internal suspend fun scenarioA1(ctx: ScenarioContext, file: File): ScenarioOutcome =
    ctx.withFreshReader(file, webtoon = true) { vm, automation ->
        val webtoon = awaitWebtoonAutomation(automation)
        vm.budget?.resetPeak()
        ctx.probe.clearPlaceholderCount()
        ctx.recorder.start()
        // Steady descent: one scrollBy per composed frame, scaled by that frame's duration so the
        // scroll speed stays constant regardless of the refresh rate.
        var lastNs = withFrameNanos { it }
        while (webtoon.scrollY < webtoon.maxScroll) {
            ctx.abortCheckpoint()
            val ns = withFrameNanos { it }
            val dy = A1_SCROLL_SPEED_PX_PER_S * (ns - lastNs) / 1_000_000_000.0
            lastNs = ns
            webtoon.scrollBy(dy.toFloat())
        }
        // The flings return up the book (spec 6.6 "flings back up"): the plan's -8000 px/s is the
        // scroll-progress velocity (scrollY decreasing, like the +4000 above is scrollY
        // increasing), while the automation's velocity is the release velocity whose sign is
        // negated ("positive scrolls up" — scrollY decreases), so the API value is +8000.
        repeat(A1_FLING_COUNT) {
            ctx.abortCheckpoint()
            webtoon.fling(A1_FLING_VELOCITY_PX_PER_S)
            delay(A1_FLING_SETTLE_MS)
        }
        // Correctness guard: at maxScroll an upward fling must move the viewport; a fling that
        // stayed clamped means the scripted direction was wrong (never timing).
        if (webtoon.scrollY >= webtoon.maxScroll) {
            throw IllegalStateException("a1: the upward flings never moved the viewport")
        }
        delay(METRICS_SETTLE_MS)
        val frames = ctx.recorder.stop()
        ScenarioOutcome(
            frames = jankSummary(frames),
            fellBackToSoftware = vm.renderer?.fellBack == true,
            placeholderFrames = ctx.probe.placeholderCount(),
            peakImageBytes = vm.budget?.peakBytes,
        )
    }

/** Waits until the Webtoon session's automation is published (auto-resolved Webtoon). */
private suspend fun awaitWebtoonAutomation(automation: ReaderAutomation): WebtoonAutomation {
    val deadline = SystemClock.elapsedRealtime() + ACTION_TIMEOUT_MS
    while (true) {
        automation.webtoon?.let { return it }
        if (SystemClock.elapsedRealtime() >= deadline) {
            throw IllegalStateException("webtoon automation never published")
        }
        delay(STEP_POLL_MS)
    }
}

/**
 * b: page-turn latency on `mixed-small.cbz`. Cold samples (10) each open a fresh book, wait until
 * page 0 is idle, then snap-turn to a far page (5, 10, 15 cycling); prefetched samples (19) also
 * snap-turn (`animate = false`; E5) to the next page after `awaitIdle` on one book, so t1 measures
 * what the budget is for: the bitmap is ready and its upload must not stall the frame. t0 =
 * `System.nanoTime()` just before the turn; t1 = the end of the frame containing the page's sharp
 * draw (the draw time itself, flagged, when no recorded frame covers it). The probe fires only
 * while the drawn page is the current one, and a page drawn at rest as a neighbour never fired,
 * so every turn produces a fresh event; a missing event is a correctness failure like cold.
 */
internal suspend fun scenarioB(ctx: ScenarioContext, file: File, onProgress: (String) -> Unit): ScenarioOutcome {
    val cold = mutableListOf<Double>()
    val prefetched = mutableListOf<Double>()
    var fellBack = false

    repeat(B_COLD_SAMPLES) { i ->
        ctx.abortCheckpoint()
        onProgress("b cold ${i + 1}/$B_COLD_SAMPLES")
        val k = B_COLD_TARGETS[i % B_COLD_TARGETS.size]
        cold += ctx.withFreshReader(file) { vm, automation ->
            ctx.recorder.start()
            val t0 = System.nanoTime()
            tolerateInterruption { automation.turnTo(k, animate = false) }
            // A cold target is never adjacent to page 0, so its once-per-bitmap probe is still
            // unconsumed and must fire when the fresh render draws. A missing event is a
            // correctness failure, not a flaggable sample.
            val drawNs = ctx.probe.awaitSharpDraw(k, t0, ACTION_TIMEOUT_MS)
                ?: throw IllegalStateException("no sharp page draw for cold target page $k within ${ACTION_TIMEOUT_MS}ms")
            delay(METRICS_SETTLE_MS)
            val frames = ctx.recorder.stop()
            fellBack = fellBack || vm.renderer?.fellBack == true
            latencyMs(t0, drawNs, frames)
        }
    }

    prefetched += ctx.withFreshReader(file) { vm, automation ->
        val count = vm.state?.count ?: throw IllegalStateException("reader not attached")
        val samples = mutableListOf<Double>()
        for (p in 0 until count - 1) {
            ctx.abortCheckpoint()
            onProgress("b prefetched ${p + 1}/${count - 1}")
            automation.awaitIdle()
            ctx.recorder.start()
            val t0 = System.nanoTime()
            // E5: the prefetched turns snap, so no settle wait is needed before referencing time.
            tolerateInterruption { automation.turnTo(p + 1, animate = false) }
            // The probe fires only while the drawn page is the current one, and a page drawn at
            // rest as a neighbour never fired, so the snap turn always produces a fresh event.
            // A missing event is a correctness failure, exactly like the cold samples'.
            val drawNs = ctx.probe.awaitSharpDraw(p + 1, t0, ACTION_TIMEOUT_MS)
                ?: throw IllegalStateException(
                    "no sharp page draw for prefetched page ${p + 1} within ${ACTION_TIMEOUT_MS}ms",
                )
            delay(METRICS_SETTLE_MS)
            val frames = ctx.recorder.stop()
            samples += latencyMs(t0, drawNs, frames)
        }
        fellBack = fellBack || vm.renderer?.fellBack == true
        samples
    }

    return ScenarioOutcome(coldMs = cold, prefetchedMs = prefetched, fellBackToSoftware = fellBack)
}

/**
 * b's t1: the end of the first recorded frame whose interval contains [drawNs]; when no recorded
 * frame covers the draw the draw time itself is used and the sample is flagged in the run log.
 */
private fun latencyMs(t0: Long, drawNs: Long, frames: List<FrameSample>): Double {
    val end = frameEndContaining(drawNs, frames)
    if (end == null) {
        Log.w(TAG, "b sample: no recorded frame contains the sharp draw; using the draw time (flagged)")
    }
    return ((end ?: drawNs) - t0) / 1_000_000.0
}

private const val TAG = "BenchmarkRunner"

/** a2 cadence: one turn per 450 ms (the old perf-capture pacing, pinned by the spec). */
private const val A2_TURN_INTERVAL_MS = 450L

/** a3: cycles, and the wait between zoom/pan steps (long enough for a settle + detail render). */
private const val A3_CYCLES = 3
private const val A3_STEP_WAIT_MS = 300L

/** a1: the steady descent's scroll speed (content px/s at 1x; pinned by the plan's script). */
private const val A1_SCROLL_SPEED_PX_PER_S = 4000.0

/**
 * a1: the flings back up the book after the descent (spec 6.6), and the settle between them. The
 * velocity is in the automation's release-velocity units ("positive scrolls up"); the plan's
 * -8000 px/s is the same fling expressed as scroll-progress velocity (scrollY decreasing).
 */
private const val A1_FLING_COUNT = 4
private const val A1_FLING_VELOCITY_PX_PER_S = 8000f
private const val A1_FLING_SETTLE_MS = 300L

/** b cold: 10 fresh-book samples turning to 5, 10, 15 cyclically. */
private const val B_COLD_SAMPLES = 10
private val B_COLD_TARGETS = intArrayOf(5, 10, 15)

/** Real-time margin so the metrics of a just-drawn frame reach the recorder before stop(). */
private const val METRICS_SETTLE_MS = 150L

/** Generous timeouts: correctness, never timing (documented cached-app-freezer stalls). */
private const val ACTION_TIMEOUT_MS = 60_000L
private const val STEP_POLL_MS = 25L
