@file:OptIn(FlowPreview::class)

package com.proviewer.app.reader.webtoon

import android.graphics.Paint
import androidx.compose.animation.SplineBasedFloatDecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.proviewer.app.engine.TileSource
import com.proviewer.app.reader.ImageBudget
import com.proviewer.app.reader.ReaderProbe
import com.proviewer.app.reader.ReaderViewModel
import com.proviewer.app.reader.TapAction
import com.proviewer.app.reader.WEBTOON_PAGE_SCROLL
import com.proviewer.app.reader.WebtoonAutomation
import com.proviewer.app.reader.verticalTapAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A command the reader sends the Webtoon viewport. Emitted by the ViewModel's page-turn API while
 * the layout is Webtoon (where there are no pager units to turn): [Page] scrolls by one tap/volume
 * page ([WEBTOON_PAGE_SCROLL] of the viewport height, in the turn's direction), [ToPage] jumps to
 * a page's top (the overlay slider).
 */
sealed interface WebtoonCommand {
    data class Page(val delta: Int) : WebtoonCommand
    data class ToPage(val page: Int) : WebtoonCommand
}

/** Test semantics: the viewport's settled scroll offset (1x content px). */
val WebtoonScrollYKey = SemanticsPropertyKey<Double>("WebtoonScrollY")

/** Test semantics: the page currently shown at the viewport's top edge. */
val WebtoonTopPageKey = SemanticsPropertyKey<Int>("WebtoonTopPage")

/** The whole-column zoom's upper bound (spec 5.2: the pinch range is 1x-3x, designer's call). */
const val WEBTOON_MAX_ZOOM = 3f

/**
 * How long the transform must be still before the view settles: the zoom level sharpens to
 * [zoomLevel] (the visible tiles re-render at that scale) and the settle trim drops tiles outside
 * the working set.
 */
const val WEBTOON_SETTLE_MS = 150L

/** Duration of a tap/volume-key page scroll and of a double-tap zoom (designer's call). */
private const val TAP_SCROLL_MS = 200
private const val ZOOM_MS = 200

/** Flings below this screen-px/s velocity (e.g. the tail of a tap) do not start a decay. */
private const val MIN_FLING_VELOCITY = 50f

/** The viewport's background while no tile covers an area, and the error placeholder colour. */
private val PLACEHOLDER_COLOR = Color(0xFF111111)

/**
 * The Webtoon session: the layout snapshot and the tile manager of an active `Layout.Webtoon`
 * reading, owned by [ReaderViewModel] (created when the layout becomes Webtoon with its initial
 * `scrollY` at the current book page's top, closed — releasing all tiles — when the layout
 * leaves Webtoon). The pager's `PageWindow` is closed while the session owns the budget, and vice
 * versa.
 *
 * The scroll/zoom/pan transform lives here as snapshot state so it survives the activity
 * recreation a rotation causes; the [WebtoonViewport] composable reads it in its draw phase and
 * writes it in gesture handlers — never during composition, so no scroll or zoom frame ever
 * recomposes. [rebuild] swaps in a rebuilt [WebtoonLayout] (viewport size, size-probe batches,
 * failed pages); the viewport re-anchors the transform around the anchor the old layout reports.
 */
class WebtoonSession(
    scope: CoroutineScope,
    initialLayout: WebtoonLayout,
    source: TileSource,
    budget: ImageBudget,
) {
    /** The current stacked layout; snapshot state: the viewport observes rebuilds. */
    var layout: WebtoonLayout by mutableStateOf(initialLayout)
        private set

    /** The tile manager rendering the wanted tiles (main-confined [TileManager.update]). */
    val tiles: TileManager = TileManager(scope, source, budget)

    /** The 1x content px shown at the viewport's top edge. */
    var scrollY: Double by mutableDoubleStateOf(0.0)

    /** The zoom factor (1x..3x). */
    var zoom: Float by mutableFloatStateOf(1f)

    /** The horizontal pan in screen px while zoomed (0 at 1x). */
    var panX: Float by mutableFloatStateOf(0f)

    /** Bumped when budget bytes were released outside a scroll change: forces a tile retry. */
    var refreshTick: Int by mutableIntStateOf(0)
        private set

    /** Rebuilds [layout] for [viewW] x [viewH] and returns it. */
    fun rebuild(
        viewW: Int,
        viewH: Int,
        sizeOf: (Int) -> IntSize?,
        failed: (Int) -> Boolean,
    ): WebtoonLayout {
        val next = WebtoonLayout.build(layout.count, viewW, viewH, sizeOf, failed)
        layout = next
        return next
    }

    /** Requests a forced tile update (retries renders skipped while the budget was full). */
    fun poke() {
        refreshTick++
    }

    /** Cancels all tile work and releases every held image. */
    fun close() {
        tiles.close()
    }
}

/**
 * The Webtoon viewport (spec 6.4): one self-drawn composable scrolling the whole column at 1x.
 * The scroll/zoom/pan transform is the session's snapshot state, read only in the draw phase
 * ([Modifier.drawBehind]) and in gesture handlers — no recomposition happens per scroll or zoom
 * frame; recomposition happens on slot-map changes (tiles landing, pages failing) and on the
 * coarse states tests and the overlay read (top page, settled scroll).
 *
 * Drawing per visible page: the 1x tiles underneath while zoomed, the settled-level tiles over
 * them, and the dark placeholder ([PLACEHOLDER_COLOR]) wherever no ready tile covers the area;
 * a failed page ([TileManager.failedPages], sticky for the session) draws the placeholder with
 * "Page n" and the failure's reason, and scrolling continues past it. Tile placement reuses the
 * page-relative scales with one shared rounding per boundary, so neighbouring tiles and adjacent
 * pages abut exactly — no seam, gap or 1-px jump.
 *
 * Gestures: drag scrolls 1:1 (divided by the zoom) and pans horizontally while zoomed; a release
 * flings through a spline decay; pinch zooms the whole column about the fingers' centroid within
 * `[1, WEBTOON_MAX_ZOOM]` (it latches until all fingers are up — a pinch never scrolls the list by
 * itself and its release never flings); single taps map through the vertical thirds
 * ([verticalTapAction] via the ViewModel's page turns); double-tap zooms to 2x about the tap and
 * back. While the transform changes the settled level stays at the previous level (tiles drawn
 * scaled); after [WEBTOON_SETTLE_MS] of stillness it sharpens to [zoomLevel] and the visible tiles
 * re-render at that scale — a new gesture before they are done cancels them ([TileManager.update]).
 * A new gesture or command cancels the running scroll/zoom/fling animation; the far `ToPage` jump
 * snaps instead of animating through the column, so passed pages never queue renders.
 *
 * A snapshotFlow over the view computes [wantedTiles] and calls [TileManager.update] (main
 * thread) only when the wanted set changes; after the view has been still for the settle delay
 * another update evicts everything outside the working set, returning the held bytes to it. The
 * page at the viewport's top edge drives [ReaderViewModel.onWebtoonTopPage] (current page,
 * size-probe priority and the engine's prefetch hint) and the [WebtoonTopPageKey] semantics; the
 * settled scroll drives [WebtoonScrollYKey].
 */
@Composable
fun WebtoonViewport(
    vm: ReaderViewModel,
    session: WebtoonSession,
    probe: ReaderProbe,
    onAutomation: (WebtoonAutomation?) -> Unit,
) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var topPage by remember { mutableIntStateOf(0) }
    var shownScrollY by remember { mutableDoubleStateOf(0.0) }
    var settledLevel by remember { mutableIntStateOf(1000) }
    val forwardState = remember { mutableStateOf(true) }
    // Compose's spline-based fling decay (what rememberSplineBasedDecay wraps): this Compose
    // version's animateDecay overload takes the FloatDecayAnimationSpec directly, so the spec is
    // remembered per density exactly like rememberSplineBasedDecay does.
    val decay = remember(density.density) { SplineBasedFloatDecayAnimationSpec(density) }
    val scope = rememberCoroutineScope()
    val animJob = remember { mutableStateOf<Job?>(null) }

    val titlePaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color(0xFFCCCCCC).toArgb()
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 16.sp.toPx() }
        }
    }
    val reasonPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color(0xFF999999).toArgb()
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 12.sp.toPx() }
        }
    }
    val lineStep = with(density) { 20.sp.toPx() }

    fun cancelAnim() {
        animJob.value?.cancel()
        animJob.value = null
    }

    /**
     * Starts a spline decay continuing a drag released with the finger velocity [velocityY]
     * (screen px/s, positive = the finger moved down): the content follows the finger, so a
     * positive release flings toward the column's top (scrollY decreases). The same convention
     * serves the gesture's velocity tracker and [WebtoonAutomation.fling] (whose contract is
     * "positive scrolls up").
     */
    fun startFling(velocityY: Float) {
        cancelAnim()
        animJob.value = scope.launch {
            animateDecay(
                initialValue = session.scrollY.toFloat(),
                // animateDecay grows the value with a positive velocity, while the drag grows
                // scrollY with a negative finger velocity: negate to continue the drag.
                initialVelocity = -velocityY / session.zoom,
                animationSpec = decay,
            ) { value, _ ->
                session.scrollY = value.toDouble()
                    .coerceIn(0.0, session.layout.maxScroll(session.zoom))
            }
        }
    }

    /** Animates the scroll to [target] (clamped), one tap/volume page. */
    suspend fun animateScrollTo(target: Double) {
        val max = session.layout.maxScroll(session.zoom)
        val to = target.coerceIn(0.0, max)
        val from = session.scrollY
        if (to == from) return
        animate(
            initialValue = from.toFloat(),
            targetValue = to.toFloat(),
            animationSpec = tween(TAP_SCROLL_MS),
        ) { value, _ ->
            session.scrollY = value.toDouble().coerceIn(0.0, max)
        }
    }

    /**
     * Sets the zoom to [z], keeping the content point that was under [focal] when its content
     * coordinates ([contentX], [contentY]) were captured under it: screen x = `X*z - panX` and
     * screen y = `(Y - scrollY)*z` give `panX = X*z - focal.x` and `scrollY = Y - focal.y/z`,
     * clamped to the pan and scroll ranges (at a range the focal point drifts — the view cannot
     * show past the column's edge).
     */
    fun applyZoom(z: Float, focal: Offset, contentX: Float, contentY: Double) {
        session.zoom = z
        val maxPan = session.layout.viewW * (z - 1f)
        session.panX = (contentX * z - focal.x).coerceIn(0f, maxPan)
        session.scrollY = (contentY - focal.y / z)
            .coerceIn(0.0, session.layout.maxScroll(z))
    }

    /**
     * Zooms to [factor] (clamped to `[1, WEBTOON_MAX_ZOOM]`) about [focal] (a viewport position):
     * the content point under [focal] stays under it. Without [animate] it applies immediately;
     * with, it animates over [ZOOM_MS]. Cancels any running scroll/zoom/fling animation first.
     */
    fun startZoomTo(factor: Float, focal: Offset, animate: Boolean) {
        cancelAnim()
        val target = factor.coerceIn(1f, WEBTOON_MAX_ZOOM)
        val startZoom = session.zoom
        val contentX = (focal.x + session.panX) / startZoom
        val contentY = focal.y / startZoom + session.scrollY
        if (!animate || target == startZoom) {
            applyZoom(target, focal, contentX, contentY)
        } else {
            animJob.value = scope.launch {
                animate(
                    initialValue = startZoom,
                    targetValue = target,
                    animationSpec = tween(ZOOM_MS),
                ) { z, _ ->
                    applyZoom(z, focal, contentX, contentY)
                }
            }
        }
    }

    /** Double-tap: zoom to 2x about the tap, or back to 1x; the focal content point stays put. */
    fun toggleZoom(focal: Offset) {
        startZoomTo(if (session.zoom > 1.001f) 1f else 2f, focal, animate = true)
    }

    val automation = remember(session, scope, decay) {
        object : WebtoonAutomation {
            override val scrollY: Double get() = session.scrollY
            override val maxScroll: Double get() = session.layout.maxScroll(session.zoom)

            override suspend fun scrollBy(dy: Float) = withContext(scope.coroutineContext) {
                session.scrollY = (session.scrollY + dy)
                    .coerceIn(0.0, session.layout.maxScroll(session.zoom))
            }

            override suspend fun fling(velocityY: Float) = withContext(scope.coroutineContext) {
                startFling(velocityY)
                val job = animJob.value
                if (job != null) {
                    try {
                        job.join()
                    } catch (e: CancellationException) {
                        job.cancel()
                        throw e
                    }
                }
            }

            override suspend fun zoomTo(factor: Float, focal: Offset, animate: Boolean) {
                withContext(scope.coroutineContext) {
                    startZoomTo(factor, focal, animate)
                    // An animated zoom suspends until it has finished, like a fling.
                    animJob.value?.join()
                }
            }
        }
    }
    DisposableEffect(automation) {
        onAutomation(automation)
        onDispose { onAutomation(null) }
    }

    LaunchedEffect(vm, session) {
        // Layout rebuilds (viewport size, size-probe batches, failed pages) keep the anchor.
        launch {
            snapshotFlow { RebuildKey(size, vm.sizes?.version ?: 0, session.tiles.failedPages.size) }
                .distinctUntilChanged()
                .collect {
                    val s = size
                    if (s.width <= 0 || s.height <= 0) return@collect
                    Snapshot.withMutableSnapshot {
                        val old = session.layout
                        val rotated = old.viewW != s.width
                        val anchor = old.anchorAt(session.scrollY)
                        val newLayout = session.rebuild(
                            s.width,
                            s.height,
                            { page -> vm.sizes?.known(page) ?: vm.renderer?.knownPageSize(page) },
                            { page -> vm.sizes?.failed(page) == true || page in session.tiles.failedPages },
                        )
                        if (rotated) {
                            // Rotation resets zoom to 1x (designer's call); the anchor's offset
                            // rescales with the width so the same relative position is shown.
                            session.zoom = 1f
                            session.panX = 0f
                        }
                        val rescaled = if (rotated) {
                            rescaleAnchor(anchor, old.viewW, newLayout.viewW)
                        } else {
                            anchor
                        }
                        session.scrollY = newLayout.yOf(rescaled)
                            .coerceIn(0.0, newLayout.maxScroll(session.zoom))
                    }
                }
        }

        // The settled zoom level feeds the wanted tiles' level: while the zoom changes the level
        // stays where it was (the tiles of the previous level are drawn scaled), and after the
        // settle delay it sharpens to zoomLevel(zoom) — at 1x it never leaves 1000 and nothing
        // re-renders.
        launch {
            snapshotFlow { session.zoom }
                .debounce(WEBTOON_SETTLE_MS)
                .collect { settledLevel = zoomLevel(it) }
        }

        // The sharp re-render's lifecycle, reported through the probe's pinned vocabulary per
        // rendered tile above level 1000 (the settled level's tiles): "tiles-start" when its
        // render starts, "tiles-done" when it lands ready, "tiles-cancelled" when it is cancelled
        // before that (a new gesture or level supersedes it — TileManager.update drops it). At 1x
        // the tiles ARE the 1x render — nothing re-renders and no events fire.
        launch {
            var previous: Map<TileKey, TileSlot> = emptyMap()
            snapshotFlow { session.tiles.tiles.toMap() }
                .collect { tiles ->
                    for ((key, slot) in tiles) {
                        if (key.level == 1000) continue
                        val before = previous[key]
                        when (slot) {
                            is TileSlot.Loading -> if (before !is TileSlot.Loading) {
                                probe.onDetailRender(key.page, "tiles-start")
                            }
                            is TileSlot.Ready -> if (before !is TileSlot.Ready) {
                                probe.onDetailRender(key.page, "tiles-done")
                            }
                            is TileSlot.Failed -> Unit
                        }
                    }
                    for ((key, slot) in previous) {
                        if (slot is TileSlot.Loading && key.level != 1000 && key !in tiles) {
                            probe.onDetailRender(key.page, "tiles-cancelled")
                        }
                    }
                    previous = tiles
                }
        }

        // Wanted tiles and the page at the viewport's top edge, per view change.
        launch {
            var lastWanted: Wanted? = null
            var lastTick = -1
            var lastScroll = session.scrollY
            snapshotFlow {
                Triple(
                    WebtoonView(session.scrollY, session.zoom, session.panX),
                    settledLevel,
                    session.refreshTick,
                )
            }.collect { (view, level, tick) ->
                if (view.scrollY != lastScroll) forwardState.value = view.scrollY > lastScroll
                lastScroll = view.scrollY
                val forward = forwardState.value
                val wanted = wantedTiles(session.layout, view, level, forward)
                if (wanted != lastWanted || tick != lastTick) {
                    lastWanted = wanted
                    lastTick = tick
                    session.tiles.update(wanted)
                }
                val top = session.layout.pageAt(view.scrollY)
                if (top != topPage) {
                    topPage = top
                    shownScrollY = view.scrollY
                    vm.onWebtoonTopPage(top, forward)
                }
            }
        }

        // Once the view has been still for the settle delay, the held bytes return to exactly the
        // working set: tiles outside it are evicted (during scrolling they are kept as the first
        // eviction candidates instead).
        launch {
            snapshotFlow { Triple(session.scrollY, session.zoom, session.panX) }
                .debounce(WEBTOON_SETTLE_MS)
                .collect {
                    val view = WebtoonView(session.scrollY, session.zoom, session.panX)
                    session.tiles.update(
                        wantedTiles(session.layout, view, settledLevel, forwardState.value),
                        evictNotWanted = true,
                    )
                }
        }

        // The reader's page turns (taps, volume keys, slider) as scroll commands.
        launch {
            vm.webtoonCommands.collect { command ->
                when (command) {
                    is WebtoonCommand.Page -> {
                        val h = size.height
                        if (h <= 0) return@collect
                        cancelAnim()
                        animJob.value = scope.launch {
                            animateScrollTo(session.scrollY + command.delta * WEBTOON_PAGE_SCROLL * h)
                        }
                    }
                    is WebtoonCommand.ToPage -> {
                        // The slider jumps to the page's top; snapping (not animating through the
                        // column) keeps passed pages from ever queueing renders.
                        cancelAnim()
                        session.scrollY = session.layout.top(command.page)
                            .coerceIn(0.0, session.layout.maxScroll(session.zoom))
                    }
                }
            }
        }

        // The semantics-reported scroll trails the live value so tests read a settled position.
        launch {
            snapshotFlow { session.scrollY }
                .debounce(WEBTOON_SETTLE_MS)
                .collect { shownScrollY = it }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .testTag("webtoon-viewport")
            .semantics {
                this[WebtoonScrollYKey] = shownScrollY
                this[WebtoonTopPageKey] = topPage
            }
            .pointerInput(session) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    cancelAnim()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    // The pinch keeps two separate pieces of state: the latch (this gesture is a
                    // pinch — it never drag-scrolls and never flings on release, and a finger
                    // left behind does nothing) and the ratio basis (the last good two-finger
                    // span and the zoom it mapped to). The basis is dropped whenever fewer than
                    // two fingers are pressed, so a finger swapped in mid-gesture (2->1->2 grip
                    // change) re-baselines against the CURRENT zoom and its own first span
                    // instead of ratioing against the old pair's stale span — which would snap
                    // the zoom by the span ratio in one event. A degenerate span (fingers on the
                    // same spot) never becomes the basis: the last good one stays.
                    var pinchLatched = false
                    var basis: PinchBasis? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        val first = changes.firstOrNull() ?: break
                        val pressed = changes.filter { it.pressed }
                        // Feed the tracker on real movement and on the up (the last sample). A
                        // pinch's tracked velocity is unused: its release never flings.
                        if (first.positionChange() != Offset.Zero || first.pressed != first.previousPressed) {
                            tracker.addPosition(first.uptimeMillis, first.position)
                        }
                        if (pressed.size >= 2) {
                            // Pinch: zoom about the fingers' centroid within [1, WEBTOON_MAX_ZOOM],
                            // keeping the focal content point under the centroid (scrollY and panX
                            // adjusted about it).
                            changes.forEach { change -> if (change.pressed) change.consume() }
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val span = (a - b).getDistance()
                            val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                            val prev = basis
                            if (prev != null && span > 0.5f) {
                                applyZoom(
                                    (prev.zoom * span / prev.span).coerceIn(1f, WEBTOON_MAX_ZOOM),
                                    centroid,
                                    (centroid.x + session.panX) / session.zoom,
                                    centroid.y / session.zoom + session.scrollY,
                                )
                            }
                            if (span > 0.5f) basis = PinchBasis(span, session.zoom)
                            pinchLatched = true
                        } else {
                            // Fewer than two fingers: the pair's span is stale, so the next pair
                            // re-baselines; the latch stays until all fingers are up.
                            basis = null
                            if (pressed.size == 1) {
                                val change = pressed[0]
                                val drag = change.positionChange()
                                if (drag != Offset.Zero) {
                                    change.consume()
                                    if (!pinchLatched) {
                                        // Drag scrolls 1:1 (divided by the zoom); horizontal drags
                                        // pan while zoomed (the pan range collapses to zero at 1x).
                                        session.scrollY = (session.scrollY - drag.y / session.zoom)
                                            .coerceIn(0.0, session.layout.maxScroll(session.zoom))
                                        val maxPan = session.layout.viewW * (session.zoom - 1f)
                                        session.panX = (session.panX - drag.x).coerceIn(0f, maxPan)
                                    }
                                }
                            }
                        }
                        if (pressed.isEmpty()) {
                            if (!pinchLatched) {
                                val velocity = tracker.calculateVelocity()
                                if (abs(velocity.y) > MIN_FLING_VELOCITY) startFling(velocity.y)
                            }
                            break
                        }
                    }
                }
            }
            .pointerInput(vm) {
                detectTapGestures(
                    onTap = { position ->
                        when (verticalTapAction(position.y, size.height.toFloat())) {
                            TapAction.Previous -> vm.previous()
                            TapAction.Next -> vm.next()
                            TapAction.ToggleOverlay -> vm.toggleOverlay()
                        }
                    },
                    onDoubleTap = { focal -> toggleZoom(focal) },
                )
            }
            .drawBehind {
                drawWebtoon(session, settledLevel, titlePaint, reasonPaint, lineStep) {
                    probe.onPlaceholderDrawn()
                }
            },
    ) {
        // Error placeholders live in the draw phase (their position follows the scroll); expose
        // each failed page as a tag node so tests can address it (the visible-area proof is the
        // probe's onPlaceholderDrawn).
        session.tiles.failedPages.forEach { (page, _) ->
            Box(Modifier.testTag("reader-error-$page"))
        }
    }
}

/** Inputs a layout rebuild depends on; a change re-runs the rebuild. */
private data class RebuildKey(val size: IntSize, val sizesVersion: Int, val failedCount: Int)

/** A pinch's last good two-finger span and the zoom it mapped to (the next event's ratio basis). */
private class PinchBasis(val span: Float, val zoom: Float)

/**
 * The draw-phase body: failed pages' error placeholders first, then the dark under-layer for
 * visible areas no ready tile covers, then the tiles (1x underneath while zoomed, settled level
 * over them). [onPlaceholder] fires once per frame that drew a placeholder in the visible area.
 */
private fun DrawScope.drawWebtoon(
    session: WebtoonSession,
    settledLevel: Int,
    titlePaint: Paint,
    reasonPaint: Paint,
    lineStep: Float,
    onPlaceholder: () -> Unit,
) {
    val layout = session.layout
    if (layout.count == 0 || layout.viewW <= 0) return
    val zoom = session.zoom
    val scrollY = session.scrollY
    val panX = session.panX
    val viewHpx = size.height
    if (viewHpx <= 0) return
    val y0 = scrollY
    val y1 = scrollY + viewHpx / zoom
    val visible = layout.visiblePages(y0, y1)
    if (visible.isEmpty()) return
    val tileSlots = session.tiles.tiles
    val failedPages = session.tiles.failedPages
    var drewPlaceholder = false

    fun screenX(contentX: Double): Float = (contentX * zoom - panX).toFloat()
    fun screenY(contentY: Double): Float = ((contentY - scrollY) * zoom).toFloat()

    fun drawBand(top: Double, bottom: Double) {
        val bandTop = screenY(maxOf(top, y0))
        val bandBottom = screenY(minOf(bottom, y1))
        if (bandBottom > bandTop) {
            drawRect(
                PLACEHOLDER_COLOR,
                topLeft = Offset(0f, bandTop),
                size = Size(size.width, bandBottom - bandTop),
            )
        }
    }

    /** Draws the ready tiles of [page] at [level] (nothing for estimated/failed pages). */
    fun drawPage(page: Int, level: Int) {
        if (layout.isEstimated(page)) return
        val full = fullSize(layout, page, level)
        if (full.width <= 0 || full.height <= 0) return
        val top = layout.top(page)
        val h = layout.height(page).toDouble()
        // Page-relative scales (full.width/viewW, h/full.height); each boundary is mapped and
        // rounded once through the shared functions below, so tiles and pages abut exactly.
        val scaleX = layout.viewW.toDouble() / full.width
        val scaleY = h / full.height
        for (spec in pageTiles(page, full, level)) {
            val slot = tileSlots[spec.key] as? TileSlot.Ready ?: continue
            val bitmap = slot.image.bitmap ?: continue
            val r = spec.rect
            val left = if (r.left == 0) 0.0 else r.left * scaleX
            val right = if (r.right == full.width) layout.viewW.toDouble() else r.right * scaleX
            val topY = top + (if (r.top == 0) 0.0 else r.top * scaleY)
            val bottomY = top + (if (r.bottom == full.height) h else r.bottom * scaleY)
            val x0 = screenX(left).roundToInt()
            val x1 = screenX(right).roundToInt()
            val yTop = screenY(topY).roundToInt()
            val yBottom = screenY(bottomY).roundToInt()
            if (x1 > x0 && yBottom > yTop) {
                slot.image.prepareToDraw()
                drawImage(
                    bitmap.asImageBitmap(),
                    dstOffset = IntOffset(x0, yTop),
                    dstSize = IntSize(x1 - x0, yBottom - yTop),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
    }

    /** Whether ready tiles cover the page's visible band at [level]. */
    fun coveredAt(page: Int, level: Int): Boolean {
        if (layout.isEstimated(page)) return false
        val full = fullSize(layout, page, level)
        if (full.width <= 0 || full.height <= 0) return false
        val top = layout.top(page)
        val h = layout.height(page).toDouble()
        val scaleY = h / full.height
        for (spec in pageTiles(page, full, level)) {
            val tileTop = top + (if (spec.rect.top == 0) 0.0 else spec.rect.top * scaleY)
            val tileBottom = top + (if (spec.rect.bottom == full.height) h else spec.rect.bottom * scaleY)
            if (tileBottom <= y0 || tileTop >= y1) continue
            if (tileSlots[spec.key] !is TileSlot.Ready) return false
        }
        return true
    }

    // Failed pages: the sticky error placeholder with the page number and the failure's reason.
    for (page in visible) {
        val reason = failedPages[page] ?: continue
        drewPlaceholder = true
        val top = layout.top(page)
        val bottom = top + layout.height(page)
        drawBand(top, bottom)
        val cy = (screenY(maxOf(top, y0)) + screenY(minOf(bottom, y1))) / 2f
        drawContext.canvas.nativeCanvas.drawText("Page ${page + 1}", size.width / 2f, cy - lineStep / 2, titlePaint)
        drawContext.canvas.nativeCanvas.drawText(reason, size.width / 2f, cy + lineStep, reasonPaint)
    }

    // Dark under-layer wherever no ready tile covers the visible area.
    for (page in visible) {
        if (failedPages.containsKey(page)) continue
        val covered = coveredAt(page, settledLevel) || (settledLevel != 1000 && coveredAt(page, 1000))
        if (!covered) {
            drewPlaceholder = true
            drawBand(layout.top(page), layout.top(page) + layout.height(page))
        }
    }

    // Tiles: the 1x fallback underneath while zoomed, the settled-level tiles over it.
    for (page in visible) {
        if (failedPages.containsKey(page)) continue
        if (settledLevel != 1000) drawPage(page, 1000)
        drawPage(page, settledLevel)
    }

    if (drewPlaceholder) onPlaceholder()
}
