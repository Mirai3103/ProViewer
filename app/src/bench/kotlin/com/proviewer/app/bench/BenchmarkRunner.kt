package com.proviewer.app.bench

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewModelScope
import com.proviewer.app.BuildConfig
import com.proviewer.app.ProViewerApp
import com.proviewer.app.engine.UploadStrategy
import com.proviewer.app.open.BookOpener
import com.proviewer.app.open.OpenResult
import com.proviewer.app.reader.PageSlot
import com.proviewer.app.reader.Part
import com.proviewer.app.reader.ReaderAutomation
import com.proviewer.app.reader.ReaderViewModel
import com.proviewer.app.reader.Layout
import com.proviewer.app.reader.LayoutChoice
import com.proviewer.app.reader.SplitChoice
import com.proviewer.app.reader.UnitPart
import com.proviewer.app.reader.webtoon.TileSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Thermal gate seam over [ThermalGuard] so callers (tests) can inject the thermal state instead of
 * reading the device's. The real guard registers its listener in its constructor and unregisters
 * it in [ThermalGuard.close].
 */
interface ThermalGate {
    /** True when the device is cool enough to start a benchmark run. */
    fun canStart(): Boolean

    /** Emits true once the device reaches `THERMAL_STATUS_MODERATE` or worse. */
    val tripped: StateFlow<Boolean>
}

/**
 * Thrown by [BenchmarkRunner.run] before any work happens when the device is too warm to start;
 * the screen shows the reason and keeps the Start button disabled.
 */
class ThermalRefusalException(
    message: String = "device is too warm (THERMAL_STATUS_MODERATE or worse) to start a benchmark run",
) : IllegalStateException(message)

/**
 * Thrown at a scenario's abort checkpoint when the thermal guard trips mid-run; caught per
 * scenario and recorded as `aborted` in the [ScenarioResult]. A [CancellationException] so it
 * unwinds cleanly through animation suspends without cancelling the run's own job.
 */
internal class BenchmarkAbort(reason: String) : CancellationException("benchmark aborted: $reason")

/** What the host composable shows: the reader of [vm], or nothing when `vm == null`. */
data class BenchmarkHostState(val generation: Int, val vm: ReaderViewModel?)

/**
 * Drives the whole benchmark (Task 10): generates the corpus at [scale], runs every scenario once
 * per upload strategy (Software then Hardware on odd runs, reversed on even runs, counter in
 * `SharedPreferences`), and returns the run's [BenchmarkResult].
 *
 * Each scenario hosts the REAL `ReaderScreen` full screen via [hostState] — a host composable
 * (BenchmarkScreen's [com.proviewer.app.bench.BenchmarkReaderHost]) renders the reader for the
 * current [BenchmarkHostState.vm] and reports its `ReaderAutomation` back through
 * [onAutomationDelivered]. `run` must be called from a Compose composition scope: the scripted
 * animated page turns need the composition's `MonotonicFrameClock`.
 *
 * The runner does not own [guard]: whoever created it closes it (BenchmarkScreen closes the one it
 * creates when it leaves composition). `run` refuses to start when [ThermalGuard.canStart] is false and aborts
 * the current scenario at the next checkpoint when the guard trips. Scenario durations are logged
 * so the owner's real-device run can check the 3-minute total target (spec 6.4).
 */
class BenchmarkRunner(
    private val activity: ComponentActivity,
    private val scale: Float = 1f,
    private val guard: ThermalGate,
) {
    private val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val engine get() = (activity.application as ProViewerApp).engine
    private val corpusDir = File(activity.filesDir, "bench/corpus")

    /** The bench probe the hosted reader feeds; the scenarios read its sharp-draw events. */
    val probe = BenchProbe()

    /** One reusable recorder for every scenario (fresh handler thread per start/stop cycle). */
    private val recorder = FrameRecorder(activity.window)

    /** The reader currently hosted by the host composable, or null between scenarios. */
    private val _hostState = MutableStateFlow(BenchmarkHostState(0, null))
    val hostState: StateFlow<BenchmarkHostState> = _hostState.asStateFlow()

    private var nextGeneration = 1

    internal val readerActive = MutableStateFlow(false)
    private val _running = MutableStateFlow(false)

    /** True while [run] is executing. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _result = MutableStateFlow<BenchmarkResult?>(null)

    /** The result of the last completed run, or null before the first run. */
    val result: StateFlow<BenchmarkResult?> = _result.asStateFlow()

    private var automationGate: CompletableDeferred<ReaderAutomation>? = null

    /** True when the device is cool enough to start a run (Start button state). */
    fun canStart(): Boolean = guard.canStart()

    /** The device's current thermal status, recorded as `thermalEnd` per scenario. */
    private fun thermalStatus(): Int = powerManager.currentThermalStatus

    /** Called by the host composable when the hosted ReaderScreen reports its automation. */
    internal fun onAutomationDelivered(automation: ReaderAutomation) {
        automationGate?.complete(automation)
    }

    /**
     * Runs the whole suite. Throws [ThermalRefusalException] before doing any work when
     * [canStart] is false; a mid-run thermal trip aborts the current scenario at its next
     * checkpoint and skips the rest (each skipped scenario is simply absent from the result).
     */
    suspend fun run(onProgress: (String) -> Unit): BenchmarkResult {
        check(!_running.value) { "a benchmark run is already in progress" }
        if (!guard.canStart()) throw ThermalRefusalException()
        _running.value = true
        try {
            return executeRun(onProgress)
        } finally {
            _running.value = false
        }
    }

    private suspend fun executeRun(onProgress: (String) -> Unit): BenchmarkResult {
        val runNumber = nextRunNumber()
        val runStarted = SystemClock.elapsedRealtime()
        val strategies = if (runNumber % 2 == 1) {
            listOf(UploadStrategy.Software, UploadStrategy.Hardware)
        } else {
            listOf(UploadStrategy.Hardware, UploadStrategy.Software)
        }

        onProgress("Capturing device conditions")
        val conditions = captureConditions(activity)

        onProgress("Generating corpus (scale $scale)")
        val generateStart = SystemClock.elapsedRealtime()
        val files = CorpusGenerator.ensure(corpusDir, scale, BENCH_CORPUS_FILES)
        val corpus = BENCH_CORPUS_FILES.zip(files).toMap()
        onProgress("Corpus ready in ${SystemClock.elapsedRealtime() - generateStart} ms")

        val scenarioResults = mutableListOf<ScenarioResult>()
        var order = 0
        outer@ for (strategy in strategies) {
            for (id in BENCH_SCENARIO_IDS) {
                if (guard.tripped.value) {
                    onProgress("Device reached the thermal limit — run aborted")
                    break@outer
                }
                val started = SystemClock.elapsedRealtime()
                onProgress("$id [$strategy] running")
                val beforeHz = currentRefreshHz()
                val outcome = runScenarioGuarded(strategy, id, corpus, onProgress)
                val durationMs = SystemClock.elapsedRealtime() - started
                val scenario = ScenarioResult(
                    id = id,
                    strategy = strategy,
                    order = order,
                    refreshBeforeHz = beforeHz,
                    refreshAfterHz = currentRefreshHz(),
                    thermalEnd = thermalStatus(),
                    frames = outcome.frames,
                    cold = outcome.coldMs?.let(::latencySummary),
                    prefetched = outcome.prefetchedMs?.let(::latencySummary),
                    fellBackToSoftware = outcome.fellBackToSoftware,
                    aborted = outcome.aborted,
                    placeholderFrames = outcome.placeholderFrames,
                    peakImageBytes = outcome.peakImageBytes,
                )
                scenarioResults += scenario
                Log.i(
                    TAG,
                    "scenario $id [$strategy] order=$order: ${durationMs} ms, " +
                        "frames=${scenario.frames?.frames ?: 0}, cold=${scenario.cold?.n ?: 0}, " +
                        "prefetched=${scenario.prefetched?.n ?: 0}, fellBack=${scenario.fellBackToSoftware}, " +
                        "placeholders=${scenario.placeholderFrames ?: "-"}, " +
                        "peakBytes=${scenario.peakImageBytes ?: "-"}, " +
                        "aborted=${scenario.aborted}",
                )
                val total = strategies.size * BENCH_SCENARIO_IDS.size
                onProgress("$id [$strategy] done in ${durationMs} ms (${order + 1}/$total)")
                order++
            }
        }
        return BenchmarkResult(
            conditions = conditions,
            appVersion = BuildConfig.VERSION_NAME,
            gitSha = BuildConfig.GIT_SHA,
            corpusScale = scale,
            scenarios = scenarioResults,
        ).also {
            _result.value = it
            // The ≤ 4-minute total is the owner's-device target (spec 6.6); the log makes the
            // run's wall time comparable wherever it ran (never a performance number itself).
            Log.i(TAG, "benchmark run wall time: ${SystemClock.elapsedRealtime() - runStarted} ms")
        }
    }

    /**
     * Runs one scenario, isolating failures: a thermal trip records `aborted = "thermal"`, any
     * other failure records the exception type, and the run continues with the next scenario. The
     * recorder is drained defensively so a scenario that died mid-window cannot pollute the next.
     */
    private suspend fun runScenarioGuarded(
        strategy: UploadStrategy,
        id: String,
        corpus: Map<String, File>,
        onProgress: (String) -> Unit,
    ): ScenarioOutcome =
        try {
            runScenarioBody(strategy, id, corpus, onProgress)
        } catch (e: BenchmarkAbort) {
            runCatching { recorder.stop() }
            ScenarioOutcome(aborted = "thermal")
        } catch (e: CancellationException) {
            runCatching { recorder.stop() }
            // A CancellationException here can be a real run cancellation (screen left) or an
            // isolated one from the reader's machinery (e.g. a withTimeout inside the scenario).
            // The current job being active means the run itself is alive: record an abort.
            if (!currentCoroutineContext().isActive) throw e
            Log.w(TAG, "scenario $id [$strategy] cancelled", e)
            ScenarioOutcome(aborted = e::class.simpleName ?: "cancelled")
        } catch (e: Exception) {
            runCatching { recorder.stop() }
            Log.w(TAG, "scenario $id [$strategy] failed", e)
            ScenarioOutcome(aborted = e::class.simpleName ?: "error")
        }

    private suspend fun runScenarioBody(
        strategy: UploadStrategy,
        id: String,
        corpus: Map<String, File>,
        onProgress: (String) -> Unit,
    ): ScenarioOutcome = when (id) {
        "a1" -> scenarioA1(ScenarioEnv(strategy), corpus.getValue("webtoon-long.cbz"))
        "a2" -> scenarioA2(ScenarioEnv(strategy), corpus.getValue("mixed-small.cbz"))
        "a3-screentone" -> scenarioA3(ScenarioEnv(strategy), corpus.getValue("screentone.cbz"), id)
        "a3-huge" -> scenarioA3(ScenarioEnv(strategy), corpus.getValue("huge-single.cbz"), id)
        "b" -> scenarioB(ScenarioEnv(strategy), corpus.getValue("mixed-small.cbz"), onProgress)
        else -> throw IllegalArgumentException("unknown scenario id $id")
    }

    /** Throws [BenchmarkAbort] when the guard has tripped (checked between scenario steps). */
    private fun abortCheckpoint() {
        if (guard.tripped.value) throw BenchmarkAbort("thermal limit reached")
    }

    // --- reader hosting ---------------------------------------------------------

    /**
     * Opens [file] fresh (via the production [BookOpener], off-main), attaches it to a brand-new
     * reader ViewModel under [strategy], hosts the real ReaderScreen until the block returns, and
     * releases everything afterwards — no books leak between scenarios or samples. With
     * [webtoon] the layout choice stays at Auto (a strip book auto-resolves Webtoon, a1's
     * "auto Webtoon"); otherwise the Single layout is pinned as every pager scenario assumes.
     */
    private suspend fun <T> withFreshReader(
        strategy: UploadStrategy,
        file: File,
        webtoon: Boolean = false,
        block: suspend (ReaderViewModel, ReaderAutomation) -> T,
    ): T {
        val vm = ReaderViewModel()
        val gate = CompletableDeferred<ReaderAutomation>()
        automationGate = gate
        _hostState.value = BenchmarkHostState(nextGeneration++, vm)
        try {
            val opened = BookOpener(activity.applicationContext, engine)
                .open(Uri.fromFile(file)) { _, _ -> }
            if (opened !is OpenResult.Opened) {
                throw IllegalStateException(
                    "benchmark book open failed: ${(opened as? OpenResult.Failed)?.reason ?: "unknown"}",
                )
            }
            probe.clear()
            vm.attach(opened, strategy)
            // Pin the layout every pager scenario assumes (Single; Auto would resolve it for
            // these books too, but the pin keeps that explicit) and Split Off, because a3-huge
            // opens huge-single, whose page is wide, and the scenarios read the whole-page unit.
            // a1 runs the auto-resolved Webtoon layout instead, so the pin is skipped there.
            if (!webtoon) {
                vm.setLayoutChoice(LayoutChoice.Single)
            }
            vm.setSplit(SplitChoice.Off)
            val automation = withTimeout(READER_TIMEOUT_MS) { gate.await() }
            awaitReaderReady(vm)
            return block(vm, automation)
        } finally {
            teardownReader(vm)
        }
    }

    /**
     * Waits until the reader is attached and idle at its resolved layout — "page 0 is idle" for
     * every scenario's start: the pager layouts need page 0 drawn sharp and the render window
     * idle; the Webtoon layout needs the session's first tiles ready and no render in flight.
     * Generous real-time budget.
     */
    private suspend fun awaitReaderReady(vm: ReaderViewModel) {
        val deadline = SystemClock.elapsedRealtime() + READER_TIMEOUT_MS
        while (true) {
            if (vm.state?.layout == Layout.Webtoon) {
                val tiles = vm.webtoon?.tiles?.tiles
                if (vm.viewport != null && tiles?.values?.any { it is TileSlot.Ready } == true) {
                    vm.webtoon?.tiles?.awaitIdle()
                    return
                }
            } else {
                val settled = vm.state != null &&
                    vm.viewport != null &&
                    vm.window?.slots?.get(UnitPart(0, Part.Whole)) is PageSlot.Ready &&
                    probe.hasSharpDraw(0)
                if (settled) {
                    vm.window?.awaitIdle()
                    if (vm.window?.slots?.get(UnitPart(0, Part.Whole)) is PageSlot.Ready) return
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw IllegalStateException(
                    "reader never became ready: state=${vm.state}, viewport=${vm.viewport}, " +
                        "slot=${vm.window?.slots?.get(UnitPart(0, Part.Whole))}, " +
                        "webtoon=${vm.webtoon != null}",
                )
            }
            delay(READY_POLL_MS)
        }
    }

    /**
     * Removes the reader from the composition, waits for the host composable to confirm it left
     * (base bitmaps must never be recycled while a frame could still draw them — the ZoomTest
     * teardown rule), then releases the ViewModel and its scope. Runs inside [NonCancellable] so
     * a cancelled run still releases the book.
     */
    private suspend fun teardownReader(vm: ReaderViewModel) {
        withContext(NonCancellable) {
            _hostState.value = BenchmarkHostState(nextGeneration++, null)
            automationGate = null
            try {
                withTimeout(TEARDOWN_TIMEOUT_MS) { readerActive.first { !it } }
            } catch (_: Exception) {
                // Composition already gone, or the signal missed the deadline: release anyway.
            }
            delay(TEARDOWN_SETTLE_MS)
            vm.release()
            vm.viewModelScope.cancel()
        }
    }

    // --- helpers ----------------------------------------------------------------

    /** Run counter in SharedPreferences: run 1 is odd (Software first), run 2 reversed. */
    private fun nextRunNumber(): Int {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val n = prefs.getInt(KEY_RUN_NUMBER, 0) + 1
        prefs.edit().putInt(KEY_RUN_NUMBER, n).apply()
        return n
    }

    private fun currentRefreshHz(): Float =
        (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
            .refreshRate

    /** The [ScenarioContext] handed to scenario bodies for one invocation. */
    private inner class ScenarioEnv(override val strategy: UploadStrategy) : ScenarioContext {
        override val probe: BenchProbe get() = this@BenchmarkRunner.probe
        override val recorder: FrameRecorder get() = this@BenchmarkRunner.recorder
        override fun abortCheckpoint() = this@BenchmarkRunner.abortCheckpoint()
        override suspend fun <T> withFreshReader(
            file: File,
            webtoon: Boolean,
            block: suspend (ReaderViewModel, ReaderAutomation) -> T,
        ): T = this@BenchmarkRunner.withFreshReader(strategy, file, webtoon, block)
    }

    private companion object {
        const val TAG = "BenchmarkRunner"
        const val PREFS_NAME = "benchmark"
        const val KEY_RUN_NUMBER = "run_number"

        /** Attach + ready budget (correctness, never timing; cached-app-freezer stalls). */
        const val READER_TIMEOUT_MS = 60_000L
        const val READY_POLL_MS = 25L
        const val TEARDOWN_TIMEOUT_MS = 5_000L
        const val TEARDOWN_SETTLE_MS = 100L
    }
}
