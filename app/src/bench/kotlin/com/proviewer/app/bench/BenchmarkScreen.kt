package com.proviewer.app.bench

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.proviewer.app.reader.LocalReaderProbe
import com.proviewer.app.reader.ReaderScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "BenchmarkScreen"

/** Shown (and polled) when the device is too warm to start a run. */
private const val THERMAL_REASON = "Device is too warm — wait for it to cool down before benchmarking"

/**
 * The Benchmark screen (Task 10): a Start button (disabled with the reason while the thermal check
 * fails), live progress, a results table per scenario x strategy, and JSON sharing through a
 * FileProvider. While a scenario runs, the host composable puts the REAL ReaderScreen on top,
 * full screen.
 *
 * The screen owns the runner for its composition lifetime; [runner] lets tests inject one built
 * with a chosen scale/guard. Must be hosted by a [ComponentActivity].
 */
@Composable
fun BenchmarkScreen(
    onBack: () -> Unit,
    scale: Float = 1f,
    guard: ThermalGate? = null,
    runner: BenchmarkRunner? = null,
) {
    val activity = checkNotNull(LocalContext.current.findComponentActivity()) {
        "BenchmarkScreen must be hosted by a ComponentActivity"
    }
    val owned = remember { runner ?: BenchmarkRunner(activity, scale, guard ?: ThermalGuard(activity)) }
    val scope = rememberCoroutineScope()
    val running by owned.running.collectAsState()
    val result by owned.result.collectAsState()
    var progress by remember { mutableStateOf("Idle") }
    var thermalReason by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }

    // The thermal status can change at any moment; re-check periodically so the Start button and
    // its reason stay current. The first check runs before the first frame.
    LaunchedEffect(owned) {
        while (isActive) {
            thermalReason = if (owned.canStart()) null else THERMAL_REASON
            delay(2_000)
        }
    }

    // Back leaves the screen while idle; during a run the hosted reader's own BackHandler wins
    // (it is composed deeper), so back never tears down a measurement.
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Benchmark", style = MaterialTheme.typography.headlineSmall)
            if (result?.conditions?.isEmulator == true) {
                Text(
                    "Emulator — not valid for performance",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        try {
                            owned.run(onProgress = { progress = it })
                            progress = "Run complete"
                        } catch (e: ThermalRefusalException) {
                            progress = e.message ?: "start refused"
                        } catch (e: CancellationException) {
                            Log.w(TAG, "benchmark run cancelled", e)
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "benchmark run failed", e)
                            progress = "Run failed: ${e::class.simpleName}"
                        }
                    }
                },
                enabled = !running && thermalReason == null,
                modifier = Modifier.testTag("bench-start"),
            ) { Text(if (running) "Running" else "Start benchmark run") }
            thermalReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(progress, modifier = Modifier.testTag("bench-progress"))
            result?.let { ResultsTable(it) }
            Button(
                onClick = {
                    val current = result
                    if (current != null) {
                        scope.launch {
                            sharing = true
                            try {
                                shareResult(activity, current)
                            } finally {
                                sharing = false
                            }
                        }
                    }
                },
                enabled = result != null && !sharing,
                modifier = Modifier.testTag("bench-share"),
            ) { Text("Share JSON") }
        }
        BenchmarkReaderHost(owned)
    }
}

/**
 * The architectural heart of the task: hosts the REAL ReaderScreen full screen while the runner
 * drives a scenario, feeding it the runner's probe and reporting its automation back. The
 * generation key forces a fresh reader composition (fresh pager, fresh probe state) for every
 * hosted book, and nothing is composed while the benchmark UI is visible.
 */
@Composable
private fun BenchmarkReaderHost(runner: BenchmarkRunner) {
    val host by runner.hostState.collectAsState()
    val vm = host.vm ?: return
    key(host.generation) {
        DisposableEffect(vm) {
            runner.readerActive.value = true
            onDispose { runner.readerActive.value = false }
        }
        CompositionLocalProvider(LocalReaderProbe provides runner.probe) {
            ReaderScreen(
                vm = vm,
                // Back during a run must not navigate away; the run owns the screen.
                onBack = {},
                onAutomation = { runner.onAutomationDelivered(it) },
            )
        }
    }
}

/** Results table: one row per scenario x strategy, with the derived flags shown inline. */
@Composable
private fun ResultsTable(result: BenchmarkResult) {
    Column(Modifier.testTag("bench-results"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Results", style = MaterialTheme.typography.titleMedium)
        result.scenarios.forEach { s ->
            val framesPart = s.frames?.let {
                "frames=${it.frames}, janky=%.1f%%, p50/p95/p99=%.1f/%.1f/%.1f ms"
                    .format(it.jankyPct, it.p50Ms, it.p95Ms, it.p99Ms)
            } ?: "frames: n/a"
            val latencyPart = listOfNotNull(
                s.cold?.let { "cold(n=${it.n}) p50/p95/max=%.0f/%.0f/%.0f ms".format(it.p50Ms, it.p95Ms, it.maxMs) },
                s.prefetched?.let { "pref(n=${it.n}) p50/p95/max=%.0f/%.0f/%.0f ms".format(it.p50Ms, it.p95Ms, it.maxMs) },
            ).joinToString(" | ", prefix = " — ").takeIf { it.isNotBlank() } ?: ""
            val flags = buildList {
                if (s.refreshBeforeHz != s.refreshAfterHz) add("rate changed")
                if (s.fellBackToSoftware) add("fell back to software")
                s.aborted?.let { add("aborted: $it") }
            }.joinToString("; ", prefix = " — ").takeIf { it.isNotBlank() } ?: ""
            Text("${s.id} [${s.strategy.name}] #${s.order} — $framesPart$latencyPart$flags")
        }
    }
}

/**
 * Writes the run's JSON to `filesDir/bench/result-<timestamp>.json` and shares it through the
 * `${applicationId}.benchfiles` FileProvider with ACTION_SEND, type application/json.
 */
private suspend fun shareResult(activity: ComponentActivity, result: BenchmarkResult) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(activity.filesDir, "bench").apply { mkdirs() }
        File(dir, "result-${System.currentTimeMillis()}.json").apply { writeText(toJson(result)) }
    }
    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.benchfiles", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    activity.startActivity(Intent.createChooser(send, "Share benchmark JSON"))
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
