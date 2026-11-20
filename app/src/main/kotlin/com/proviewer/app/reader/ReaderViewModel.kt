package com.proviewer.app.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proviewer.app.engine.NativeBook
import com.proviewer.app.engine.PageRenderer
import com.proviewer.app.engine.UploadStrategy
import com.proviewer.app.open.OpenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private var viewW = 0
    private var viewH = 0

    /** True while a [PageWindow.update] is queued but not yet run; coalesces rapid state changes. */
    private var updatePending = false

    /** The last non-positive-guarded viewport, or null before the first [onViewport]. */
    internal val viewport: IntSize? get() = if (viewW > 0 && viewH > 0) IntSize(viewW, viewH) else null

    fun attach(opened: OpenResult.Opened, strategy: UploadStrategy = UploadStrategy.Hardware) {
        releaseResources()
        val book = opened.book
        val renderer = PageRenderer(book, strategy)
        val window = PageWindow(viewModelScope, renderer)
        this.book = book
        this.renderer = renderer
        this.window = window
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

    /** Called when the pager settles on a page; delegates to [goTo] (a no-op when unchanged). */
    fun onPageSettled(page: Int) {
        settledPage = page
        goTo(page)
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
            window.update(s.page, s.direction == Direction.Ltr, w, h, s.fitMode)
        }
    }

    private fun releaseResources() {
        val window = this.window
        this.window = null
        window?.close()
        val book = this.book
        this.book = null
        this.renderer = null
        if (book != null) {
            // Close off the main thread on a throwaway scope so it survives viewModelScope's cancel.
            CoroutineScope(Dispatchers.IO).launch { book.closeAsync() }
        }
    }
}
