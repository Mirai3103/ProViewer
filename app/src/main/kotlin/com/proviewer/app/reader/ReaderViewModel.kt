package com.proviewer.app.reader

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proviewer.app.engine.NativeBook
import com.proviewer.app.engine.PageRenderer
import com.proviewer.app.engine.UploadStrategy
import com.proviewer.app.open.BookOpener
import com.proviewer.app.open.OpenResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The reader's UI state. [page], [direction] and [fitMode] live here so they survive rotation.
 * [page] stays the book page; [unit] is the pager unit currently shown ([ReaderUnit], one pager
 * page) and [layout] the resolved reading layout behind it.
 */
data class ReaderState(
    val title: String,
    val page: Int,
    val count: Int,
    val direction: Direction,
    val fitMode: FitMode,
    val overlayVisible: Boolean,
    val layoutChoice: LayoutChoice = LayoutChoice.Auto,
    val split: SplitChoice = SplitChoice.Auto,
    val shift: Boolean = false,
    val layout: Layout = Layout.Single,
    val unit: Int = 0,
)

/**
 * Owns the opened [NativeBook], its [PageRenderer], its [PageWindow] and the book's
 * [ReaderUnit]s, and survives rotation. It also runs the open itself ([open]) on
 * [viewModelScope], so a copy or open in flight survives rotation instead of being cancelled with
 * the Activity.
 *
 * Every public method must be called on the main thread.
 *
 * The window's render coroutines run on [viewModelScope] (the main dispatcher) so slot
 * publications are serialized on main, and `window.update` runs on the same dispatcher so image
 * releases (on eviction/re-size) are serialized with Compose's draw phase. The heavy
 * decode/resize stays on `PageRenderer`'s own `Dispatchers.Default` dispatcher.
 */
class ReaderViewModel : ViewModel() {

    /** The reader state, null before [attach] and after [release]. */
    var state: ReaderState? by mutableStateOf(null)
        private set

    internal var book: NativeBook? = null
        private set
    internal var renderer: PageRenderer? = null
        private set
    internal var window: PageWindow? = null
        private set
    internal var budget: ImageBudget? = null
        private set
    internal var sizes: PageSizes? = null
        private set

    /**
     * The reader's units (one pager page each), rebuilt from the current sizes, layout, split,
     * shift and direction. Snapshot state: the pager's page count is read from it. Rebuilt only
     * when those inputs change (never on page turns), keeping [ReaderState.page] fixed.
     */
    var units: List<ReaderUnit> by mutableStateOf(emptyList())
        private set

    /** Re-runs [recomputeUnits] whenever [PageSizes.version] changes. */
    private var sizesJob: Job? = null

    private var viewW = 0
    private var viewH = 0

    /** True while a [PageWindow.update] is queued but not yet run; coalesces rapid state changes. */
    private var updatePending = false

    /** The last non-positive-guarded viewport, or null before the first [onViewport]. */
    internal val viewport: IntSize? get() = if (viewW > 0 && viewH > 0) IntSize(viewW, viewH) else null

    /** Why the last [open] failed, or null. Cleared when a new open starts. */
    var openError: String? by mutableStateOf(null)
        private set

    /** Copy progress (0..1) of the open in flight, or null when idle or the size is unknown. */
    var copyProgress: Float? by mutableStateOf(null)
        private set

    /**
     * True once an [open] has attached a book that the host has not yet shown; the host switches
     * to the reader and calls [consumeOpened]. State (not a callback) so a book that finishes
     * opening during a rotation is still picked up by the recreated Activity.
     */
    var openedPending: Boolean by mutableStateOf(false)
        private set

    private var openJob: Job? = null

    /**
     * Opens [uri] through [opener] (which must hold the application context, never an Activity)
     * and attaches it on success. A newer [open] or [cancelOpen] cancels the one in flight; the
     * opener closes a book whose open completed just as it was cancelled.
     */
    fun open(opener: BookOpener, uri: Uri) {
        openJob?.cancel()
        openError = null
        copyProgress = null
        openJob = viewModelScope.launch {
            val result = try {
                opener.open(uri) { copied, total ->
                    copyProgress = if (total != null && total > 0) copied.toFloat() / total else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OpenResult.Failed(e::class.simpleName ?: "open failed")
            } finally {
                copyProgress = null
            }
            // No suspension between the open returning and attach: the book cannot be dropped.
            when (result) {
                is OpenResult.Opened -> {
                    attach(result)
                    openedPending = true
                }
                is OpenResult.Failed -> openError = result.reason
            }
        }
    }

    /** Cancels the open in flight, if any (a partial copy is deleted by the opener). */
    fun cancelOpen() {
        openJob?.cancel()
        openJob = null
    }

    /** Acknowledges [openedPending]; returns whether there was an unshown opened book. */
    fun consumeOpened(): Boolean {
        val pending = openedPending
        openedPending = false
        return pending
    }

    fun attach(opened: OpenResult.Opened, strategy: UploadStrategy = UploadStrategy.Hardware) {
        releaseResources()
        val book = opened.book
        val renderer = PageRenderer(book, strategy)
        // Bytes freed outside the window (detail images, tiles) must let budget-skipped window
        // targets retry: every release pokes a window update.
        val budget = ImageBudget(onReleased = { postWindowUpdate() })
        val window = PageWindow(viewModelScope, renderer, budget)
        val sizes = PageSizes(viewModelScope, renderer)
        this.book = book
        this.renderer = renderer
        this.budget = budget
        this.window = window
        this.sizes = sizes
        // Page sizes arrive in the background; every batch of them can change the units (split
        // decisions, pairing) and unlock more window targets.
        sizesJob = viewModelScope.launch {
            snapshotFlow { sizes.version }.collect { recomputeUnits() }
        }
        sizes.start()
        state = ReaderState(
            title = opened.title,
            page = 0,
            count = book.pageCount,
            direction = Direction.Ltr,
            fitMode = FitMode.Screen,
            overlayVisible = false,
            layout = resolvedLayout(LayoutChoice.Auto),
        )
        recomputeUnits()
    }

    /**
     * Jumps to book page [page], snapping to the first unit that contains it (the slider snaps to
     * the first half of a split page). A no-op when the state already matches.
     */
    fun goTo(page: Int) {
        val s = state ?: return
        val clamped = page.coerceIn(0, (s.count - 1).coerceAtLeast(0))
        val unit = unitIndexOf(units, clamped).coerceAtLeast(0)
        if (clamped == s.page && unit == s.unit) return
        state = s.copy(page = clamped, unit = unit)
        scheduleWindowUpdate()
    }

    /**
     * Called when the pager settles on [unit]. Only a settle after a user swipe ([fromUser])
     * moves the reader (to [unit]'s first book page); a programmatic animation's settle only
     * updates [settledUnit], so an interrupted animation can never pull the reader back to the
     * unit it was interrupted at.
     */
    fun onUnitSettled(unit: Int, fromUser: Boolean) {
        settledUnit = unit
        settledPage = units.getOrNull(unit)?.parts?.firstOrNull()?.page
        if (!fromUser) return
        val s = state ?: return
        val target = units.getOrNull(unit) ?: return
        if (unit == s.unit) return
        state = s.copy(unit = unit, page = target.parts.first().page)
        scheduleWindowUpdate()
    }

    /** The unit the pager last settled on; a test seam to await an animation's end. */
    internal var settledUnit: Int? = null
        private set

    /** The first book page of the settled unit (equal to it in Single without split). */
    internal var settledPage: Int? = null
        private set

    /** Turns to the next unit; a no-op on the last one. */
    fun next() = moveUnit(+1)

    /** Turns to the previous unit; a no-op on the first one. */
    fun previous() = moveUnit(-1)

    private fun moveUnit(delta: Int) {
        val s = state ?: return
        val target = s.unit + delta
        val targetUnit = units.getOrNull(target) ?: return
        state = s.copy(unit = target, page = targetUnit.parts.first().page)
        scheduleWindowUpdate()
    }

    /** Sets the layout choice and rebuilds the units, keeping the book page. */
    fun setLayoutChoice(c: LayoutChoice) {
        val s = state ?: return
        if (s.layoutChoice == c) return
        state = s.copy(layoutChoice = c, layout = resolvedLayout(c))
        recomputeUnits()
    }

    /** Sets the split choice (Single only) and rebuilds the units, keeping the book page. */
    fun setSplit(c: SplitChoice) {
        val s = state ?: return
        if (s.split == c) return
        state = s.copy(split = c)
        recomputeUnits()
    }

    /** Sets the two-page shift (the opening pair only) and rebuilds the units, keeping the book page. */
    fun setShift(on: Boolean) {
        val s = state ?: return
        if (s.shift == on) return
        state = s.copy(shift = on)
        recomputeUnits()
    }

    fun setDirection(d: Direction) {
        val s = state ?: return
        if (s.direction == d) return
        state = s.copy(direction = d)
        recomputeUnits()
    }

    fun setFitMode(m: FitMode) {
        val s = state ?: return
        if (s.fitMode == m) return
        state = s.copy(fitMode = m)
        scheduleWindowUpdate()
    }

    fun toggleOverlay() {
        val s = state ?: return
        state = s.copy(overlayVisible = !s.overlayVisible)
    }

    /** Records the viewport and re-renders when it changes; non-positive sizes are ignored. */
    fun onViewport(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == viewW && h == viewH) return
        viewW = w
        viewH = h
        scheduleWindowUpdate()
    }

    /** Releases the window and the book (used by back/exit and by [onCleared]). */
    fun release() {
        releaseResources()
        state = null
    }

    override fun onCleared() {
        release()
    }

    /**
     * The layout for a layout choice. Auto resolves to Single until Task 6 adds strip detection
     * ([resolveLayout] with a real `isStrip`) and the orientation re-resolution in [onViewport].
     */
    private fun resolvedLayout(choice: LayoutChoice): Layout = when (choice) {
        LayoutChoice.Auto, LayoutChoice.Single -> Layout.Single
        LayoutChoice.TwoPage -> Layout.TwoPage
        LayoutChoice.Webtoon -> Layout.Webtoon
    }

    /**
     * Rebuilds [units] from the current state, keeping the reader on the same book page (its
     * first unit). Called when the sizes version, layout choice, split, shift or direction
     * changes — never on page turns, so `buildUnits` (O(pages)) does not run per update.
     */
    private fun recomputeUnits() {
        val s = state ?: run {
            units = emptyList()
            return
        }
        val built = buildUnits(s.count, pageSizeLookup(), s.layout, s.split, s.direction, s.shift)
        val unit = unitIndexOf(built, s.page).coerceAtLeast(0)
        if (built != units) units = built
        if (unit != s.unit) state = s.copy(unit = unit)
        scheduleWindowUpdate()
    }

    /** Combines the prober's landed sizes with the renderer's cached page info. */
    private fun pageSizeLookup(): (Int) -> IntSize? {
        val sizes = this.sizes
        val renderer = this.renderer
        return { page -> sizes?.known(page) ?: renderer?.knownPageSize(page) }
    }

    /** [scheduleWindowUpdate] from any thread: budget releases happen on render threads. */
    private fun postWindowUpdate() {
        viewModelScope.launch(Dispatchers.Main) { scheduleWindowUpdate() }
    }

    /**
     * Reconciles the window to the current state. At most one update is ever queued: rapid page
     * jumps (mashing volume keys, dragging the slider) coalesce into the latest state instead of
     * queueing a render per page passed. The coroutine runs on the main dispatcher (deferred, not
     * immediate) so image releases on eviction or re-size never happen mid-frame while Compose is
     * still drawing the old bitmap; the heavy decode/resize stays off-main inside [PageRenderer].
     */
    private fun scheduleWindowUpdate() {
        if (updatePending) return
        updatePending = true
        viewModelScope.launch(Dispatchers.Main) {
            updatePending = false
            val s = state ?: return@launch
            // A closed book (release, or a close that raced a scheduled update) can serve neither
            // new targets nor a prefetch hint; its slots already carry the failure.
            if (book?.isClosed == true) return@launch
            val w = viewW
            val h = viewH
            if (w <= 0 || h <= 0) return@launch
            val window = window ?: return@launch
            val sizes = sizes ?: return@launch
            val currentUnits = units
            if (currentUnits.isEmpty()) return@launch
            val unit = s.unit.coerceIn(0, currentUnits.size - 1)
            val forward = s.direction == Direction.Ltr
            val sizeOf = pageSizeLookup()
            // The window's pages (the parts of the current unit and its neighbours), prioritized
            // so the prober lands them before the rest of the book.
            sizes.prioritize(
                windowPages(unit, currentUnits.size, WINDOW_AHEAD, WINDOW_BEHIND, forward)
                    .flatMap { u -> currentUnits[u].parts.map { it.page } }
                    .distinct(),
            )
            val targets = windowTargets(currentUnits, unit, WINDOW_AHEAD, WINDOW_BEHIND, forward) { target ->
                unitGeometry(target, sizeOf, w, h, s.fitMode, s.direction)
            }
            window.update(targets, PrefetchRequest(s.page, forward, w, h, s.fitMode))
        }
    }

    private fun releaseResources() {
        sizesJob?.cancel()
        sizesJob = null
        sizes?.close()
        sizes = null
        val window = this.window
        this.window = null
        window?.close()
        budget = null
        val book = this.book
        this.book = null
        this.renderer = null
        if (book != null) {
            // Close off the main thread on a throwaway scope so it survives viewModelScope's cancel.
            CoroutineScope(Dispatchers.IO).launch { book.closeAsync() }
        }
    }
}

/** Units kept rendered ahead of / behind the current one. */
private const val WINDOW_AHEAD = 3
private const val WINDOW_BEHIND = 1
