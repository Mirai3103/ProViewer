package com.proviewer.app.reader

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proviewer.app.engine.NativeBook
import com.proviewer.app.engine.PageRenderer
import com.proviewer.app.engine.TileSource
import com.proviewer.app.engine.UploadStrategy
import com.proviewer.app.open.BookOpener
import com.proviewer.app.open.OpenResult
import com.proviewer.app.reader.webtoon.WebtoonCommand
import com.proviewer.app.reader.webtoon.WebtoonLayout
import com.proviewer.app.reader.webtoon.WebtoonSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    /** True while the strip detection is still running (no window update happens before it). */
    val detecting: Boolean = false,
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
     * The Webtoon session while the layout is [Layout.Webtoon], else null. Snapshot state: the
     * reader swaps between the pager and the Webtoon viewport on it. Created when the layout
     * becomes Webtoon with its initial `scrollY` at the current book page's top (so switching
     * layouts keeps the book page) and closed — releasing all tiles — when the layout leaves
     * Webtoon; the pager's [PageWindow] is closed while the session owns the budget, and vice
     * versa. Null while the viewport is not known yet.
     */
    var webtoon: WebtoonSession? by mutableStateOf(null)
        private set

    /**
     * The page turns of the Webtoon viewport ([next]/[previous]/[goTo] emit these instead of
     * moving pager units while the layout is Webtoon); the viewport's collector scrolls.
     */
    private val webtoonCommandsInternal = MutableSharedFlow<WebtoonCommand>(extraBufferCapacity = 16)
    val webtoonCommands: SharedFlow<WebtoonCommand> = webtoonCommandsInternal

    /**
     * Test/benchmark seam: the [TileSource] the Webtoon session renders tiles through (defaults
     * to the renderer). Read once at session creation; set it before the session exists.
     */
    internal var webtoonTileSource: TileSource? = null

    /**
     * Test seam: a hook run (it may suspend) right before each page-size probe starts, so a test
     * can hold a chosen page's probe. Read once at [attach]; set it before the book is attached.
     */
    internal var sizesProbeHook: (suspend (Int) -> Unit)? = null

    /**
     * The reader's units (one pager page each), rebuilt from the current sizes, layout, split,
     * shift and direction. Snapshot state: the pager's page count is read from it. Rebuilt only
     * when those inputs change (never on page turns), keeping [ReaderState.page] fixed.
     */
    var units: List<ReaderUnit> by mutableStateOf(emptyList())
        private set

    /**
     * Whether the book detected as a vertical strip (webtoon), or null while the strip detection
     * is still running. Snapshot state: the overlay's resolved layout depends on it.
     */
    var isStrip: Boolean? by mutableStateOf(null)
        private set

    /** Re-runs [recomputeUnits] whenever [PageSizes.version] changes. */
    private var sizesJob: Job? = null

    /** The strip detection of the current [attach]; cancelled by [releaseResources]. */
    private var detectJob: Job? = null

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
        // targets and tile renders retry: every release pokes both update paths.
        val budget = ImageBudget(onReleased = { postWindowUpdate(); postWebtoonPoke() })
        val window = PageWindow(viewModelScope, renderer, budget)
        val sizes = PageSizes(viewModelScope, renderer, sizesProbeHook ?: {})
        this.book = book
        this.renderer = renderer
        this.budget = budget
        this.window = window
        this.sizes = sizes
        movingForward = true
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
            // While the detection is running the reader shows the Single layout's loading state;
            // Auto re-resolves (Webtoon for a strip, else by orientation) once it has finished.
            layout = resolvedLayout(LayoutChoice.Auto),
            detecting = true,
        )
        recomputeUnits()
        detectJob = viewModelScope.launch { detectStripMode() }
    }

    /**
     * The mode detection (spec 5.1): waits until the probes of the first `min(DETECT_SAMPLE_PAGES,
     * count)` pages have finished (success or failure; off-main header reads through [PageSizes],
     * already started by [attach]), decides whether the book is a vertical strip ([detectStrip]),
     * and re-resolves the layout for [LayoutChoice.Auto]. While it runs no window update happens
     * ([scheduleWindowUpdate] holds), so no page of a possibly wrong layout is rendered; the first
     * page of the settled layout is rendered right after.
     */
    private suspend fun detectStripMode() {
        val sizes = this.sizes ?: return
        val n = minOf(DETECT_SAMPLE_PAGES, state?.count ?: 0)
        val started = SystemClock.elapsedRealtime()
        sizes.awaitFirst(n)
        val strip = detectStrip(List(n) { sizes.known(it) })
        Log.i(
            TAG,
            "strip detection took ${SystemClock.elapsedRealtime() - started} ms ($n sampled): " +
                "strip=$strip",
        )
        isStrip = strip
        val s = state ?: return
        // A manual choice made during the detection wins; Auto re-resolves with the strip
        // decision (an orientation change re-resolves through [onViewport]).
        state = s.copy(layout = resolvedLayout(s.layoutChoice), detecting = false)
        recomputeUnits()
        scheduleWindowUpdate()
    }

    /**
     * Jumps to book page [page], snapping to the first unit that contains it (the slider snaps to
     * the first half of a split page). While the layout is Webtoon the jump is delivered to the
     * viewport as a [WebtoonCommand.ToPage] instead (the page updates when the scroll lands,
     * through [onWebtoonTopPage]). A no-op when the state already matches.
     */
    fun goTo(page: Int) {
        val s = state ?: return
        val clamped = page.coerceIn(0, (s.count - 1).coerceAtLeast(0))
        if (emitWebtoonCommand(WebtoonCommand.ToPage(clamped))) return
        val unit = unitIndexOf(units, clamped).coerceAtLeast(0)
        if (clamped == s.page && unit == s.unit) return
        if (unit != s.unit) movingForward = unit > s.unit
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
        movingForward = unit > s.unit
        state = s.copy(unit = unit, page = target.parts.first().page)
        scheduleWindowUpdate()
    }

    /**
     * Whether the reader last moved toward later units. Units are in reading order whatever the
     * direction (RTL only mirrors the pager), so this — not [ReaderState.direction] — says which
     * side of the window is ahead.
     */
    private var movingForward = true

    /** The unit the pager last settled on; a test seam to await an animation's end. */
    internal var settledUnit: Int? = null
        private set

    /** The first book page of the settled unit (equal to it in Single without split). */
    internal var settledPage: Int? = null
        private set

    /** Turns to the next unit; in Webtoon, scrolls one tap page down instead. */
    fun next() {
        if (emitWebtoonCommand(WebtoonCommand.Page(+1))) return
        moveUnit(+1)
    }

    /** Turns to the previous unit; in Webtoon, scrolls one tap page up instead. */
    fun previous() {
        if (emitWebtoonCommand(WebtoonCommand.Page(-1))) return
        moveUnit(-1)
    }

    private fun moveUnit(delta: Int) {
        val s = state ?: return
        val target = s.unit + delta
        val targetUnit = units.getOrNull(target) ?: return
        movingForward = delta > 0
        state = s.copy(unit = target, page = targetUnit.parts.first().page)
        scheduleWindowUpdate()
    }

    /**
     * Delivers [command] to the Webtoon viewport; returns false when the layout is not Webtoon
     * (the pager's page turns apply). Never blocks: the buffer absorbs bursts of volume presses.
     */
    private fun emitWebtoonCommand(command: WebtoonCommand): Boolean {
        val s = state ?: return false
        if (s.layout != Layout.Webtoon || webtoon == null) return false
        webtoonCommandsInternal.tryEmit(command)
        return true
    }

    /**
     * Called by the Webtoon viewport whenever the page at the viewport's top edge changed: the
     * current page for the overlay and mode switches ([spec 5.2]), the size prober's priority
     * (so estimated heights near the reading position land first) and the engine's prefetch hint
     * (a fit-width hint — Webtoon tiles render at fit width). Must be called on the main thread.
     */
    fun onWebtoonTopPage(page: Int, forward: Boolean) {
        val s = state ?: return
        val clamped = page.coerceIn(0, (s.count - 1).coerceAtLeast(0))
        if (clamped != s.page) state = s.copy(page = clamped)
        sizes?.prioritize(
            windowPages(clamped, s.count, WEBTOON_PRIORITIZE_AHEAD, WEBTOON_PRIORITIZE_BEHIND, forward),
        )
        renderer?.prefetchHint(clamped, forward, viewW, viewH, FitMode.Width)
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

    /**
     * Records the viewport and re-renders when it changes; non-positive sizes are ignored. While
     * the layout choice is Auto, a viewport change (a rotation) re-runs the orientation rule: the
     * strip decision is kept (it does not depend on the orientation), landscape resolves TwoPage
     * and portrait Single, keeping the book page (through [recomputeUnits]).
     */
    fun onViewport(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == viewW && h == viewH) return
        viewW = w
        viewH = h
        val s = state
        if (s != null && s.layoutChoice == LayoutChoice.Auto) {
            val newLayout = resolvedLayout(s.layoutChoice)
            if (newLayout != s.layout) {
                state = s.copy(layout = newLayout)
                recomputeUnits()
            }
        }
        syncWebtoon()
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
     * The layout for a layout choice: a manual choice always wins; [LayoutChoice.Auto] applies
     * the detection rules ([resolveLayout]) with the current strip decision and viewport. While
     * the detection is still running (`isStrip == null`) it resolves as if not a strip, i.e. by
     * orientation — and [detectStripMode] re-resolves once the decision is in.
     */
    private fun resolvedLayout(choice: LayoutChoice): Layout =
        resolveLayout(choice, isStrip ?: false, viewW, viewH)

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
        val built = buildUnits(s.count, pageSizeLookup(), s.layout, s.split, s.direction, s.shift) { p ->
            sizes?.failed(p) == true
        }
        // Stay on the part being read (the second half of a split spread stays there when a size
        // batch lands), not just on the first unit of the book page.
        val unit = unitIndexKeeping(built, units.getOrNull(s.unit), s.page).coerceAtLeast(0)
        if (built != units) units = built
        if (unit != s.unit) state = s.copy(unit = unit)
        syncWebtoon()
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
            // The strip detection is still running: the layout may still change (a strip book
            // flips to Webtoon, a landscape viewport to TwoPage), so no window update happens
            // before it has finished — the reader shows the Single layout's loading state.
            if (s.detecting) return@launch
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
            val forward = movingForward
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
            // The engine prefetches whole pages fitted to the viewport it is given. A split half
            // shows half the page width in the whole viewport, i.e. the whole page fitted to a
            // viewport twice as wide: hint that while reading split halves, so the prefetched
            // decode is sharp enough for them (the cache serves a sharper decode to a blurrier
            // request, never the other way round).
            val onHalf = currentUnits[unit].parts.any { it.part != Part.Whole }
            window.update(targets, PrefetchRequest(s.page, forward, if (onHalf) w * 2 else w, h, s.fitMode))
        }
    }

    /**
     * Reconciles the Webtoon session with the current state: creates it (with the session's
     * initial `scrollY` at the current book page's top and the pager's window closed) when the
     * layout is Webtoon and the viewport is known, closes it — releasing every tile — when the
     * layout leaves Webtoon (recreating the pager's window), and does nothing otherwise. Called
     * from [recomputeUnits] and [onViewport]; idempotent and cheap to repeat.
     */
    private fun syncWebtoon() {
        val s = state
        if (s == null || renderer == null || budget == null) {
            closeWebtoon()
            return
        }
        val active = s.layout == Layout.Webtoon && viewport != null
        if (active) {
            if (webtoon == null) {
                val w = viewW
                val h = viewH
                val sizeOf = pageSizeLookup()
                val layout = WebtoonLayout.build(s.count, w, h, sizeOf) { p -> sizes?.failed(p) == true }
                val session = WebtoonSession(viewModelScope, layout, webtoonTileSource ?: renderer!!, budget!!)
                // Switching layouts keeps the book page: the column opens at that page's top.
                session.scrollY = layout.top(s.page).coerceIn(0.0, layout.maxScroll(1f))
                webtoon = session
                // The pager's window is closed while the session owns the tiles (its bytes return).
                val win = window
                window = null
                win?.close()
            }
        } else {
            if (webtoon != null) closeWebtoon()
            if (window == null && renderer != null && budget != null) {
                window = PageWindow(viewModelScope, renderer!!, budget!!)
                scheduleWindowUpdate()
            }
        }
    }

    /** Closes the Webtoon session, releasing all its tiles. */
    private fun closeWebtoon() {
        val session = webtoon
        webtoon = null
        session?.close()
    }

    /** [postWebtoonPoke] from any thread: budget releases happen on render threads. */
    private fun postWebtoonPoke() {
        viewModelScope.launch(Dispatchers.Main) { webtoon?.poke() }
    }

    private fun releaseResources() {
        detectJob?.cancel()
        detectJob = null
        isStrip = null
        sizesJob?.cancel()
        sizesJob = null
        sizes?.close()
        sizes = null
        closeWebtoon()
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

/** Pages around the Webtoon viewport's top page whose size probes are prioritized. */
private const val WEBTOON_PRIORITIZE_AHEAD = 8
private const val WEBTOON_PRIORITIZE_BEHIND = 4

/** Log tag for the reader's own reconciliation (strip detection timing, later additions). */
private const val TAG = "ReaderVM"
