package com.proviewer.app.reader

import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.unit.IntSize
import com.proviewer.app.engine.NativeRenderException
import com.proviewer.app.engine.PageImage
import com.proviewer.app.engine.PageSourceRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** The render state of a page in the window. */
sealed interface PageSlot {
    object Loading : PageSlot
    data class Ready(val image: PageImage, val size: IntSize) : PageSlot
    data class Failed(val reason: String) : PageSlot
}

/**
 * Keeps the current page plus [ahead] ahead and [behind] behind rendered at the fit size for the
 * current viewport and mode. Runs all render work on the injected [scope]'s dispatcher (never a
 * hidden global dispatcher), so JVM tests can drive it with a `StandardTestDispatcher`.
 */
class PageWindow(
    private val scope: CoroutineScope,
    private val renderer: PageSourceRenderer,
    private val ahead: Int = 3,
    private val behind: Int = 1,
) {
    val pages: SnapshotStateMap<Int, PageSlot> = mutableStateMapOf()

    private data class Running(val job: Job, val size: IntSize)

    private val running = ConcurrentHashMap<Int, Running>()
    @Volatile private var currentPage = -1

    /**
     * Reconciles the window to the given view. Evicts pages outside the window (releasing their
     * images), cancels jobs sized for another viewport or mode, starts missing renders in
     * `windowPages` priority order, and calls `prefetchHint` once.
     */
    fun update(current: Int, forward: Boolean, viewW: Int, viewH: Int, mode: FitMode) {
        currentPage = current
        val target = windowOrder(current, forward)
        val targetSet = target.toHashSet()

        // Evict pages that left the window.
        for (p in pages.keys.toList()) {
            if (p !in targetSet) {
                running.remove(p)?.job?.cancel()
                (pages.remove(p) as? PageSlot.Ready)?.image?.release()
            }
        }
        // Safety: cancel any in-flight render whose page is no longer wanted.
        for (p in running.keys.toList()) {
            if (p !in targetSet) running.remove(p)?.job?.cancel()
        }

        // Start or keep renders in priority order.
        for (p in target) {
            val natural = renderer.pageSize(p) ?: continue // out of range
            val size = baseSize(natural.width, natural.height, viewW, viewH, mode)
            when (val slot = pages[p]) {
                is PageSlot.Ready -> if (slot.size != size) {
                    slot.image.release()
                    startRender(p, size)
                }
                is PageSlot.Loading -> {
                    val r = running[p]
                    if (r == null || r.size != size) {
                        r?.job?.cancel()
                        startRender(p, size)
                    }
                }
                is PageSlot.Failed -> Unit // keep the failure until the page is evicted
                null -> startRender(p, size)
            }
        }

        renderer.prefetchHint(current, forward, viewW, viewH)
    }

    /** Waits until every in-flight render has completed (or failed). */
    suspend fun awaitIdle() {
        while (true) {
            val jobs = running.values.map { it.job }.toList()
            if (jobs.isEmpty()) return
            jobs.forEach { it.join() }
        }
    }

    /** Cancels all work and releases every held image. */
    fun close() {
        for (r in running.values) r.job.cancel()
        running.clear()
        pages.values.filterIsInstance<PageSlot.Ready>().forEach { it.image.release() }
        pages.clear()
    }

    private fun startRender(page: Int, size: IntSize) {
        pages[page] = PageSlot.Loading
        val job = scope.launch {
            try {
                val image = renderer.renderBase(page, size)
                if (page != currentPage) image.prepareToDraw()
                pages[page] = PageSlot.Ready(image, size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                pages[page] = PageSlot.Failed(reasonOf(e))
            }
        }
        running[page] = Running(job, size)
        // Remove the entry only if it is still this job (a later re-render may have replaced it).
        job.invokeOnCompletion { running.remove(page, Running(job, size)) }
    }

    /** Priority order: current, then ahead nearest-first, then behind nearest-first. */
    private fun windowOrder(current: Int, forward: Boolean): List<Int> {
        val out = ArrayList<Int>(1 + ahead + behind)
        out.add(current)
        val step = if (forward) 1 else -1
        for (i in 1..ahead) out.add(current + step * i)
        for (i in 1..behind) out.add(current - step * i)
        return out
    }

    private fun reasonOf(e: Throwable): String = when (e) {
        is NativeRenderException -> e.detail
        else -> e::class.simpleName ?: "RenderError"
    }
}
