package com.proviewer.app.reader.webtoon

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.proviewer.app.engine.NativeRenderException
import com.proviewer.app.engine.PageImage
import com.proviewer.app.engine.TileSource
import com.proviewer.app.reader.ImageBudget
import com.proviewer.app.reader.rgbaBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** The render state of one tile ([TileKey]). */
sealed interface TileSlot {
    /** The tile is being rendered; the viewport draws its fallback (or a placeholder) meanwhile. */
    object Loading : TileSlot

    /** A rendered tile: [image] is exactly `spec.rect`'s size, rendered for `spec.full`. */
    data class Ready(val image: PageImage, val spec: TileSpec) : TileSlot

    /** The render failed; [reason] is the engine's detail text. */
    data class Failed(val reason: String) : TileSlot
}

/**
 * Keeps the tiles of a Webtoon view rendered through [TileSource.renderScaled] (which runs the
 * native render on its own bounded dispatcher, never on the caller's thread). All work runs on the
 * injected [scope]'s dispatcher, so JVM tests drive it with a `StandardTestDispatcher`; [update]
 * itself never blocks and is called on the main thread.
 *
 * [update] reconciles the held tiles to the given [Wanted]: renders start in `wanted.ordered`
 * order, in-flight renders that the wanted set no longer asks for at their exact spec are
 * cancelled, and Ready tiles that are not wanted are kept until space is needed. Every render's
 * bytes (`rgbaBytes(rect)`) are reserved from [budget] before the render starts and returned when
 * the image is released, so the tiles held here never push the budget past its cap. When a
 * reservation fails, Ready tiles are evicted in order — not-wanted tiles first, farthest page
 * distance from the first visible tile first, then wanted tiles that are not visible at another
 * level; never a visible tile — and if that is still not enough, this and every lower-priority
 * tile are skipped until the next update.
 *
 * A failed render marks `tiles[key]` as [TileSlot.Failed] and `failedPages[page]` with the engine's
 * detail: the viewport draws such a page as an error placeholder, and the manager skips every
 * further render for it (the failure is sticky for the session).
 */
class TileManager(
    private val scope: CoroutineScope,
    private val source: TileSource,
    private val budget: ImageBudget,
) {
    val tiles: SnapshotStateMap<TileKey, TileSlot> = mutableStateMapOf()

    /** Pages (a [TileKey]'s [TileKey.page]) whose tile render failed, with the failure's detail. */
    val failedPages: SnapshotStateMap<Int, String> = mutableStateMapOf()

    private class Running(val spec: TileSpec, val bytes: Long) {
        lateinit var job: Job

        /** [PENDING] until the job body starts; [CANCELLED_EARLY] if it was cancelled before that. */
        val state = AtomicInteger(PENDING)
    }

    private val running = ConcurrentHashMap<TileKey, Running>()

    @Volatile private var closed = false

    /**
     * Reconciles the held tiles to [wanted]. Cancels in-flight renders that are no longer wanted
     * at their exact spec, drops Ready tiles whose key is wanted at a different spec (their bytes
     * would be wrong), starts the missing renders in priority order, and never probes or blocks.
     *
     * [evictNotWanted] additionally releases every Ready tile the wanted set does not ask for,
     * so the held bytes return to exactly the working set. During scrolling the default (false)
     * keeps them as the first eviction candidates; the viewport passes true once the view has
     * settled (the scroll has been still for the settle delay), which is when idle leftovers
     * would otherwise be held forever.
     */
    fun update(wanted: Wanted, evictNotWanted: Boolean = false) {
        if (closed) return
        val wantedByKey = wanted.ordered.associateBy { it.key }
        val settledLevel = wanted.ordered.firstOrNull()?.key?.level ?: 1000

        // Cancel in-flight renders that the wanted set no longer asks for at their exact spec.
        for (key in running.keys.toList()) {
            val entry = running[key] ?: continue
            if (entry.spec != wantedByKey[key]) cancel(key)
        }

        // Drop Ready tiles whose key is wanted at a different spec (e.g. after a viewport resize):
        // their bytes return before anything new is reserved. Ready tiles that are not wanted at
        // all stay until space is needed (they are the first eviction candidates) unless
        // [evictNotWanted] asks for the exact working set.
        for (spec in wanted.ordered) {
            val slot = tiles[spec.key]
            if (slot is TileSlot.Ready && slot.spec != spec) dropSlot(spec.key)
        }
        if (evictNotWanted) {
            for ((key, slot) in tiles) {
                if (slot is TileSlot.Ready && key !in wantedByKey) dropSlot(key)
            }
        }

        // Start the missing renders in wanted order. A tile of a failed page is skipped for good
        // (the viewport draws the error placeholder). On a reservation that fails even after
        // eviction, this and every lower-priority tile are skipped until the next update.
        for (spec in wanted.ordered) {
            val key = spec.key
            if (key.page in failedPages) continue
            if (running.containsKey(key)) continue
            val slot = tiles[key]
            if (slot is TileSlot.Ready && slot.spec == spec) continue
            // TileGrid rules out degenerate specs; never call the renderer with an empty rect.
            require(spec.rect.width > 0 && spec.rect.height > 0) {
                "empty tile rect for $key: ${spec.rect}"
            }
            if (!startRender(spec, wanted, wantedByKey, settledLevel)) break
        }
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
        for (key in running.keys.toList()) cancel(key)
        for ((_, slot) in tiles) (slot as? TileSlot.Ready)?.image?.release()
        tiles.clear()
        failedPages.clear()
    }

    /**
     * Reserves the bytes and launches the render; returns false (and starts nothing) when the
     * budget cannot be made to fit. The reservation is returned when the image is released: the
     * ready image is wrapped in the budget, so eviction or close returns the bytes, and a render
     * that never delivers releases them itself.
     */
    private fun startRender(spec: TileSpec, wanted: Wanted, wantedByKey: Map<TileKey, TileSpec>, settledLevel: Int): Boolean {
        val bytes = rgbaBytes(spec.rect.width, spec.rect.height)
        if (!reserveFor(bytes, wanted, wantedByKey, settledLevel)) return false
        val key = spec.key
        tiles[key] = TileSlot.Loading
        val entry = Running(spec, bytes)
        // Lazy: the entry must be registered before the body can run, which an immediate dispatcher
        // (viewModelScope) would otherwise do inside launch.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (!entry.state.compareAndSet(PENDING, STARTED)) return@launch // cancelled early, already released
            var handedOver = false
            try {
                val image = source.renderScaled(spec.key.page, spec.full, spec.rect)
                if (closed || running[key] !== entry) {
                    image.release()
                    return@launch
                }
                val owned = budget.wrap(image, bytes)
                handedOver = true // the wrapper now returns the bytes
                tiles[key] = TileSlot.Ready(owned, spec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!closed && running[key] === entry) {
                    val reason = reasonOf(e)
                    tiles[key] = TileSlot.Failed(reason)
                    failedPages[spec.key.page] = reason
                }
            } finally {
                if (!handedOver) budget.release(bytes)
            }
        }
        entry.job = job
        running[key] = entry
        // Remove the entry only if it is still this job (a later re-render may have replaced it).
        job.invokeOnCompletion { running.remove(key, entry) }
        job.start()
        return true
    }

    /**
     * Reserves [bytes], evicting held tiles while the budget is full: first Ready tiles that are
     * not wanted, farthest page distance from the first visible tile first, then wanted tiles that
     * are not visible at another level. Visible tiles and wanted tiles of the settled level are
     * never evicted. Returns false when even evicting everything disposable is not enough.
     */
    private fun reserveFor(
        bytes: Long,
        wanted: Wanted,
        wantedByKey: Map<TileKey, TileSpec>,
        settledLevel: Int,
    ): Boolean {
        if (bytes > budget.capBytes) return false // no eviction can ever make room
        if (budget.tryReserve(bytes)) return true
        val anchorPage = wanted.visible.firstOrNull()?.page
        data class Candidate(val disposable: Boolean, val distance: Int, val key: TileKey)
        val candidates = ArrayList<Candidate>()
        for ((key, slot) in tiles) {
            if (slot !is TileSlot.Ready) continue
            if (key in wanted.visible) continue // never a visible tile
            val wantedSpec = wantedByKey[key]
            val notWanted = wantedSpec == null || slot.spec != wantedSpec
            if (!notWanted && key.level == settledLevel) continue // wanted prefetch of the settled level stays
            val distance = if (anchorPage == null) 0 else abs(key.page - anchorPage)
            candidates.add(Candidate(notWanted, distance, key))
        }
        candidates.sortWith(
            compareByDescending<Candidate> { it.disposable } // not-wanted tiles first
                .thenByDescending { it.distance } // farthest page distance first
                .thenByDescending { it.key.level } // other (usually larger) levels on ties
                .thenBy { it.key.page }.thenBy { it.key.col }.thenBy { it.key.row }, // determinism
        )
        for (candidate in candidates) {
            dropSlot(candidate.key)
            if (budget.tryReserve(bytes)) return true
        }
        return false
    }

    /** Cancels the render of [key]; its reservation returns now if the job never started, else when it ends. */
    private fun cancel(key: TileKey) {
        val entry = running.remove(key) ?: return
        entry.job.cancel()
        // A job cancelled before its body ran never allocates and never reaches its own release.
        if (entry.state.compareAndSet(PENDING, CANCELLED_EARLY)) budget.release(entry.bytes)
        dropSlot(key)
    }

    /** Removes the tile's slot, releasing a Ready image (so its bytes return) if one is held. */
    private fun dropSlot(key: TileKey) {
        (tiles.remove(key) as? TileSlot.Ready)?.image?.release()
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
