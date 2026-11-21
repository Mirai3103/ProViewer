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
    /**
     * A rendered base: [image] is [size] pixels and is laid out at [display] (its fit size), which
     * is larger than [size] only when the base was memory-capped.
     */
    data class Ready(val image: PageImage, val size: IntSize, val display: IntSize = size) : PageSlot
    data class Failed(val reason: String) : PageSlot
}

/**
 * Keeps the current page plus [ahead] ahead and [behind] behind rendered at the fit size for the
 * current viewport and mode. Runs all render work on the injected [scope]'s dispatcher (never a
 * hidden global dispatcher), so JVM tests can drive it with a `StandardTestDispatcher`.
 *
 * [update] never blocks on a page-size probe: a page whose natural size is not known yet is probed
 * inside its render job (the renderer probes off the caller's thread).
 */
class PageWindow(
    private val scope: CoroutineScope,
    private val renderer: PageSourceRenderer,
    private val ahead: Int = 3,
    private val behind: Int = 1,
) {
    val pages: SnapshotStateMap<Int, PageSlot> = mutableStateMapOf()

    /** What a render is for: the inputs [baseSize] is computed from, besides the page itself. */
    private data class Spec(val viewW: Int, val viewH: Int, val mode: FitMode)

    private data class Running(val job: Job, val spec: Spec)

    private val running = ConcurrentHashMap<Int, Running>()
    @Volatile private var currentPage = -1
    @Volatile private var closed = false

    /**
     * Reconciles the window to the given view. Evicts pages outside the window (releasing their
     * images), cancels jobs sized for another viewport or mode, starts missing renders in
     * `windowPages` priority order, and calls `prefetchHint` once.
     */
    fun update(current: Int, forward: Boolean, viewW: Int, viewH: Int, mode: FitMode) {
        if (closed) return
        currentPage = current
        val target = windowPages(current, renderer.pageCount, ahead, behind, forward)
        val targetSet = target.toHashSet()
        val spec = Spec(viewW, viewH, mode)

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
            when (val slot = pages[p]) {
                is PageSlot.Ready -> {
                    // A Ready page was probed to render it, so its size is known without blocking.
                    val natural = renderer.knownPageSize(p)
                    val stale = natural == null || slot.size != baseSize(natural.width, natural.height, viewW, viewH, mode) ||
                        slot.display != fitSize(natural.width, natural.height, viewW, viewH, mode)
                    if (stale) {
                        slot.image.release()
                        startRender(p, spec)
                    }
                }
                is PageSlot.Loading -> {
                    val r = running[p]
                    if (r == null || r.spec != spec) {
                        r?.job?.cancel()
                        startRender(p, spec)
                    }
                }
                is PageSlot.Failed -> Unit // keep the failure until the page is evicted
                null -> startRender(p, spec)
            }
        }

        renderer.prefetchHint(current, forward, viewW, viewH, mode)
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
        closed = true
        for (r in running.values) r.job.cancel()
        running.clear()
        pages.values.filterIsInstance<PageSlot.Ready>().forEach { it.image.release() }
        pages.clear()
    }

    private fun startRender(page: Int, spec: Spec) {
        pages[page] = PageSlot.Loading
        val job = scope.launch {
            try {
                val natural = renderer.knownPageSize(page) ?: renderer.probePageSize(page)
                val size = baseSize(natural.width, natural.height, spec.viewW, spec.viewH, spec.mode)
                val display = fitSize(natural.width, natural.height, spec.viewW, spec.viewH, spec.mode)
                val image = renderer.renderRegion(
                    page,
                    PageRegion(0, 0, natural.width, natural.height, size.width, size.height),
                )
                if (closed) {
                    image.release()
                    return@launch
                }
                if (page != currentPage) image.prepareToDraw()
                pages[page] = PageSlot.Ready(image, size, display)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!closed) pages[page] = PageSlot.Failed(reasonOf(e))
            }
        }
        val entry = Running(job, spec)
        running[page] = entry
        // Remove the entry only if it is still this job (a later re-render may have replaced it).
        job.invokeOnCompletion { running.remove(page, entry) }
    }

    private fun reasonOf(e: Throwable): String = when (e) {
        is NativeRenderException -> e.detail
        else -> {
            val name = e::class.simpleName ?: "RenderError"
            val message = e.message?.takeIf { it.isNotBlank() }
            if (message != null) "$name: $message" else name
        }
    }
}
