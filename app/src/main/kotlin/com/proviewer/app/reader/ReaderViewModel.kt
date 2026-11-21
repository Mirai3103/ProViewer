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

/** The reader's UI state. [page], [direction] and [fitMode] live here so they survive rotation. */
data class ReaderState(
    val title: String,
    val page: Int,
    val count: Int,
    val direction: Direction,
    val fitMode: FitMode,
    val overlayVisible: Boolean,
)

/**
 * Owns the opened [NativeBook], its [PageRenderer] and its [PageWindow], and survives rotation.
 * It also runs the open itself ([open]) on [viewModelScope], so a copy or open in flight survives
 * rotation instead of being cancelled with the Activity.
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

    /** Re-runs [scheduleWindowUpdate] whenever [PageSizes.version] changes. */
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
        val budget = ImageBudget()
        val window = PageWindow(viewModelScope, renderer, budget)
        val sizes = PageSizes(viewModelScope, renderer)
        this.book = book
        this.renderer = renderer
        this.budget = budget
        this.window = window
        this.sizes = sizes
        // Page sizes arrive in the background; every batch of them can unlock more window targets.
        sizesJob = viewModelScope.launch {
            snapshotFlow { sizes.version }.collect { scheduleWindowUpdate() }
        }
        sizes.start()
        state = ReaderState(
            title = opened.title,
            page = 0,
            count = book.pageCount,
            direction = Direction.Ltr,
            fitMode = FitMode.Screen,
            overlayVisible = false,
        )
        scheduleWindowUpdate()
    }

    fun goTo(page: Int) {
        val s = state ?: return
        val clamped = page.coerceIn(0, (s.count - 1).coerceAtLeast(0))
        if (clamped == s.page) return
        state = s.copy(page = clamped)
        scheduleWindowUpdate()
    }

    /**
     * Called when the pager settles on a page. Only a settle after a user swipe ([fromUser])
     * moves the reader ([goTo], a no-op when unchanged); a programmatic animation's settle only
     * updates [settledPage], so an interrupted animation can never pull the reader back to the
     * page it was interrupted at.
     */
    fun onPageSettled(page: Int, fromUser: Boolean = true) {
        settledPage = page
        if (fromUser) goTo(page)
    }

    /** The page the pager last settled on; a test seam to await an animation's end. */
    internal var settledPage: Int? = null
        private set

    fun next() = state?.let { goTo(it.page + 1) }

    fun previous() = state?.let { goTo(it.page - 1) }

    fun setDirection(d: Direction) {
        val s = state ?: return
        if (s.direction == d) return
        state = s.copy(direction = d)
        scheduleWindowUpdate()
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
            val w = viewW
            val h = viewH
            if (w <= 0 || h <= 0) return@launch
            val window = window ?: return@launch
            val renderer = renderer ?: return@launch
            val sizes = sizes ?: return@launch
            val forward = s.direction == Direction.Ltr
            // Temporary one-page-per-unit driving of the part-keyed window (the unit pager is Task 5).
            val sizeOf = { page: Int -> sizes.known(page) ?: renderer.knownPageSize(page) }
            sizes.prioritize(windowPages(s.page, s.count, WINDOW_AHEAD, WINDOW_BEHIND, forward))
            val units = buildUnits(s.count, sizeOf, Layout.Single, SplitChoice.Off, s.direction, shift = false)
            val targets = windowTargets(units, s.page, WINDOW_AHEAD, WINDOW_BEHIND, forward) { unit ->
                unitGeometry(unit, sizeOf, w, h, s.fitMode, s.direction)
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

/** Pages kept rendered ahead of / behind the current one. */
private const val WINDOW_AHEAD = 3
private const val WINDOW_BEHIND = 1
