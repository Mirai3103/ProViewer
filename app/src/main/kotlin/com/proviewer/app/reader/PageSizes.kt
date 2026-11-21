package com.proviewer.app.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.proviewer.app.engine.PageSourceRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** How many page-size probes run at once. */
private const val MAX_PROBES_IN_FLIGHT = 2

/** [PageSizes.version] is bumped at most this often (in probes) unless a prioritized page lands. */
private const val VERSION_BATCH = 16

/**
 * Probes the natural size of every page in the background, so reading modes can lay pages out
 * without blocking. Prioritized pages are probed first, then pages `0..count-1` in order, with at
 * most [MAX_PROBES_IN_FLIGHT] probes in flight. The workers run on [scope] and probe through
 * [PageSourceRenderer.probePageSize], which does the blocking archive read on `Dispatchers.IO`.
 *
 * [version] is snapshot state: it is bumped when sizes land, batched (every [VERSION_BATCH] probes,
 * when a prioritized page lands, and once when the last probe finishes) so observers recompute rarely.
 */
class PageSizes(
    private val scope: CoroutineScope,
    private val renderer: PageSourceRenderer,
) {
    /** Bumps when new sizes (or failures) became visible through [known] / [failed]. */
    var version: Int by mutableIntStateOf(0)
        private set

    private val sizes = ConcurrentHashMap<Int, IntSize>()
    private val failures = ConcurrentHashMap.newKeySet<Int>()

    private val lock = Any()
    private val priority = ArrayDeque<Int>()
    private val prioritized = HashSet<Int>()
    private val claimed = HashSet<Int>() // in flight or finished: never probed again
    private var cursor = 0
    private var landedSinceBump = 0

    /** Counts every finished probe (success or failure); [awaitFirst] re-checks on each. */
    private val finished = MutableStateFlow(0)

    private val workers = ArrayList<Job>()
    private var closed = false

    /** The natural size of [page] if its probe succeeded, else null. */
    fun known(page: Int): IntSize? = sizes[page]

    /** Whether the probe of [page] failed. */
    fun failed(page: Int): Boolean = page in failures

    /** Starts the background probing. Idempotent. */
    fun start() {
        synchronized(lock) {
            if (closed || workers.isNotEmpty()) return
            repeat(MAX_PROBES_IN_FLIGHT) { workers.add(scope.launch { work() }) }
        }
    }

    /** Probes [pages] before the rest of the book (replacing any earlier prioritization). */
    fun prioritize(pages: List<Int>) {
        synchronized(lock) {
            priority.clear()
            prioritized.clear()
            for (p in pages) {
                if (p in 0 until renderer.pageCount && p !in claimed) {
                    priority.addLast(p)
                    prioritized.add(p)
                }
            }
        }
    }

    /**
     * Suspends until the probes of pages `0 until n` (bounded by the page count) have finished,
     * successfully or not. Needs [start] to have been called.
     */
    suspend fun awaitFirst(n: Int) {
        val last = minOf(n, renderer.pageCount)
        finished.first { (0 until last).all { sizes.containsKey(it) || it in failures } }
    }

    /** Cancels the probing. */
    fun close() {
        val toCancel = synchronized(lock) {
            closed = true
            workers.toList()
        }
        toCancel.forEach { it.cancel() }
    }

    private suspend fun work() {
        while (true) {
            val page = nextPage() ?: return
            try {
                sizes[page] = renderer.probePageSize(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures.add(page)
            }
            landed(page)
        }
    }

    private fun nextPage(): Int? = synchronized(lock) {
        if (closed) return null
        while (priority.isNotEmpty()) {
            val p = priority.removeFirst()
            if (claimed.add(p)) return p
        }
        while (cursor < renderer.pageCount) {
            val p = cursor++
            if (claimed.add(p)) return p
        }
        null
    }

    private fun landed(page: Int) {
        synchronized(lock) {
            landedSinceBump++
            val prioritizedLanded = prioritized.remove(page)
            val all = sizes.size + failures.size >= renderer.pageCount
            if (prioritizedLanded || landedSinceBump >= VERSION_BATCH || all) {
                landedSinceBump = 0
                version++
            }
        }
        finished.update { it + 1 }
    }
}
