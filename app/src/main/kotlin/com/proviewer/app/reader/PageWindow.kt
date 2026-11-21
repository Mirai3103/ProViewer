package com.proviewer.app.reader

import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.proviewer.app.engine.NativeRenderException
import com.proviewer.app.engine.PageImage
import com.proviewer.app.engine.PageSourceRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The render state of a part in the window. */
sealed interface PageSlot {
    object Loading : PageSlot
    /**
     * A rendered base: [image] is [size] pixels and is laid out at [display] (its fit size), which
     * is larger than [size] only when the base was memory-capped.
     */
    data class Ready(val image: PageImage, val size: IntSize, val display: IntSize = size) : PageSlot
    data class Failed(val reason: String) : PageSlot
}

/** What the engine is told to warm up alongside a window update (see [PageSourceRenderer.prefetchHint]). */
data class PrefetchRequest(
    val page: Int,
    val forward: Boolean,
    val viewW: Int,
    val viewH: Int,
    val mode: FitMode,
)

/**
 * Keeps the given [PartTarget]s rendered: one base bitmap per [UnitPart], the part's `src` region
 * rendered at its `base` size. Runs all render work on the injected [scope]'s dispatcher (never a
 * hidden global dispatcher), so JVM tests can drive it with a `StandardTestDispatcher`.
 *
 * Every render's bytes (`rgbaBytes(base)`) are reserved from [budget] before the render starts
 * (before anything is allocated) and returned when the image is released, so the bases held here
 * never push the budget past its cap. [update] never blocks: sizes come from the targets, so it
 * never probes a page.
 */
class PageWindow(
    private val scope: CoroutineScope,
    private val renderer: PageSourceRenderer,
    private val budget: ImageBudget,
) {
    val slots: SnapshotStateMap<UnitPart, PageSlot> = mutableStateMapOf()

    /**
     * The slot of the whole page [page]: the Phase 2 shaped accessor the one-page-per-unit reader
     * uses until the unit pager (Task 5) reads [slots] by part itself.
     */
    fun wholeSlot(page: Int): PageSlot? = slots[UnitPart(page, Part.Whole)]

    /** What a slot's render is for: the part of the page, its bitmap size and its laid-out size. */
    private data class Spec(val src: IntRect, val base: IntSize, val display: IntSize)

    private class Running(val spec: Spec, val bytes: Long) {
        lateinit var job: Job

        /** [PENDING] until the job body starts; [CANCELLED_EARLY] if it was cancelled before that. */
        val state = AtomicInteger(PENDING)
    }

    private val running = ConcurrentHashMap<UnitPart, Running>()

    /** The spec each Ready slot was rendered for. */
    private val readySpecs = ConcurrentHashMap<UnitPart, Spec>()

    @Volatile private var currentPage = -1
    @Volatile private var closed = false

    /**
     * Reconciles the window to [targets] (in priority order, current unit first). Evicts parts
     * outside the targets (releasing their images and bytes), cancels renders made for another spec,
     * starts missing renders in priority order, and calls `prefetchHint` once.
     *
     * A render starts only after its bytes are reserved. When a reservation fails, that target and
     * every lower-priority target are skipped for this update; they are retried by the next one.
     */
    fun update(targets: List<PartTarget>, prefetch: PrefetchRequest) {
        if (closed) return
        currentPage = prefetch.page
        val targetParts = targets.mapTo(HashSet()) { it.part }

        // Evict parts that left the window.
        for (part in slots.keys.toList()) {
            if (part !in targetParts) {
                cancel(part)
                readySpecs.remove(part)
                (slots.remove(part) as? PageSlot.Ready)?.image?.release()
            }
        }
        // Safety: cancel any in-flight render whose part is no longer wanted.
        for (part in running.keys.toList()) {
            if (part !in targetParts) cancel(part)
        }

        // Start or keep renders in priority order, until the budget runs out.
        for (target in targets) {
            val part = target.part
            val spec = Spec(target.src, target.base, target.display)
            when (val slot = slots[part]) {
                is PageSlot.Ready -> {
                    if (readySpecs[part] == spec) continue
                    readySpecs.remove(part)
                    slot.image.release() // returns its bytes before the replacement is reserved
                    slots.remove(part)
                }
                is PageSlot.Loading -> {
                    val r = running[part]
                    if (r != null && r.spec == spec) continue
                    cancel(part)
                    slots.remove(part)
                }
                is PageSlot.Failed -> continue // keep the failure until the part is evicted
                null -> Unit
            }
            if (!startRender(part, spec)) break
        }

        renderer.prefetchHint(prefetch.page, prefetch.forward, prefetch.viewW, prefetch.viewH, prefetch.mode)
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
        for (part in running.keys.toList()) cancel(part)
        readySpecs.clear()
        slots.values.filterIsInstance<PageSlot.Ready>().forEach { it.image.release() }
        slots.clear()
    }

    /** Cancels the render of [part]; its reservation returns now if the job never started, else when it ends. */
    private fun cancel(part: UnitPart) {
        val r = running.remove(part) ?: return
        r.job.cancel()
        // A job cancelled before its body ran never allocates and never reaches its own release.
        if (r.state.compareAndSet(PENDING, CANCELLED_EARLY)) budget.release(r.bytes)
    }

    /** Reserves the bytes and launches the render; returns false (and starts nothing) if the budget is full. */
    private fun startRender(part: UnitPart, spec: Spec): Boolean {
        val bytes = rgbaBytes(spec.base.width, spec.base.height)
        if (!budget.tryReserve(bytes)) return false
        slots[part] = PageSlot.Loading
        val entry = Running(spec, bytes)
        // Lazy: the entry must be registered before the body can run, which an immediate dispatcher
        // (viewModelScope) would otherwise do inside launch.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (!entry.state.compareAndSet(PENDING, STARTED)) return@launch // cancelled early, already released
            var handedOver = false
            try {
                val image = renderer.renderRegion(
                    part.page,
                    PageRegion(spec.src.left, spec.src.top, spec.src.width, spec.src.height, spec.base.width, spec.base.height),
                )
                if (closed || running[part] !== entry) {
                    image.release()
                    return@launch
                }
                if (part.page != currentPage) image.prepareToDraw()
                val owned = budget.wrap(image, bytes)
                handedOver = true // the wrapper now returns the bytes
                readySpecs[part] = spec
                slots[part] = PageSlot.Ready(owned, spec.base, spec.display)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!closed && running[part] === entry) slots[part] = PageSlot.Failed(reasonOf(e))
            } finally {
                if (!handedOver) budget.release(bytes)
            }
        }
        entry.job = job
        running[part] = entry
        // Remove the entry only if it is still this job (a later re-render may have replaced it).
        job.invokeOnCompletion { running.remove(part, entry) }
        job.start()
        return true
    }

    private fun reasonOf(e: Throwable): String = when (e) {
        is NativeRenderException -> e.detail
        else -> {
            val name = e::class.simpleName ?: "RenderError"
            val message = e.message?.takeIf { it.isNotBlank() }
            if (message != null) "$name: $message" else name
        }
    }

    private companion object {
        const val PENDING = 0
        const val STARTED = 1
        const val CANCELLED_EARLY = 2
    }
}
