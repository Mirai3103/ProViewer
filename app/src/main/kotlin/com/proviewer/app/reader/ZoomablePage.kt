@file:OptIn(FlowPreview::class)

package com.proviewer.app.reader

import androidx.compose.animation.core.SnapSpec
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.roundToIntSize
import androidx.compose.ui.unit.toOffset
import com.proviewer.app.engine.PageImage
import com.proviewer.app.engine.PageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/** How long the content transformation must stay still before a detail render starts (the settle). */
private const val DETAIL_SETTLE_MS = 150L

/** A rendered detail image and the on-screen rect (in the page slot) it is drawn at. */
private class ShownDetail(val image: PageImage, val rect: Rect)

/**
 * The one detail image of a page (D5): either a rendered [ShownDetail] is displayed over the base
 * bitmap, or nothing. A new transformation cancels the pending or running detail job, and a
 * settled null region clears the detail.
 */
private class DetailHost {
    var job: Job? = null
    var shown by mutableStateOf<ShownDetail?>(null)

    fun clear(probe: ReaderProbe, page: Int) {
        job?.cancel()
        job = null
        shown?.let {
            it.image.release()
            probe.onDetailImage(page, null)
        }
        shown = null
    }
}

/**
 * The visual bounds of the page's base bitmap within the viewport: the base size, centred
 * ([FitMode.Screen]) or top-left aligned ([FitMode.Width], [FitMode.Height]). [Modifier.zoomable]
 * uses it for edge detection, and it must match where this composable actually draws the base.
 */
private class BaseContentLocation(private val base: Size, private val alignment: Alignment) :
    ZoomableContentLocation {

    override fun location(layoutSize: Size, direction: LayoutDirection): Rect {
        val topLeft = alignment
            .align(base.roundToIntSize(), layoutSize.roundToIntSize(), direction)
            .toOffset()
        return Rect(topLeft, base)
    }
}

/**
 * A page's zoomable rendering (spec D5, approach A). The fit base bitmap is scaled by Telephoto's
 * draw-phase [Modifier.zoomable] while a gesture runs - the transforms never recompose per frame -
 * and once the content transformation has settled for [DETAIL_SETTLE_MS], the region visible on
 * screen ([visibleRegion]) is re-rendered by [PageRenderer.render] at screen resolution into one
 * detail [PageImage], drawn at the region's on-screen rect over the base.
 *
 * The maximum zoom lets every base pixel be blown up until at least one page pixel covers one
 * screen pixel (`pageW / baseW`, at least 4x), so deep zooms always reach source pixels.
 * Double-tap zooms to 2x around the tap point, or back to fit when already zoomed. Panning stops
 * at the content's edges, where the gesture falls through to the surrounding pager and turns the
 * page. Taps are routed to [onTap] (tap zones); the page's [ZoomAutomation] is published through
 * [onZoom] for `ReaderAutomation.zoom`.
 */
@Composable
fun ZoomablePage(
    page: Int,
    slot: PageSlot.Ready,
    pageSize: IntSize,
    renderer: PageRenderer,
    probe: ReaderProbe,
    mode: FitMode,
    onTap: (Offset) -> Unit,
    onZoom: (ZoomAutomation?) -> Unit,
) {
    val bitmap = slot.image.bitmap ?: return
    val base = slot.size
    val alignment = when (mode) {
        FitMode.Screen -> Alignment.Center
        FitMode.Width, FitMode.Height -> Alignment.TopStart
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // The detail pipeline outlives recompositions, so it reads the current geometry through
    // updated-state holders instead of capturing this recomposition's values.
    val currentBase by rememberUpdatedState(base)
    val currentAlignment by rememberUpdatedState(alignment)
    val currentPageSize by rememberUpdatedState(pageSize)
    val currentLayoutDirection by rememberUpdatedState(layoutDirection)

    val state = rememberZoomableState(
        zoomSpec = ZoomSpec(maxZoomFactor = max(4f, pageSize.width / base.width.toFloat())),
    ).also {
        // The base is drawn at its own pixel size, so telephoto's zoom factor - and the
        // ZoomSpec limit - are screen pixels per base pixel, exactly what visibleRegion expects.
        it.contentScale = ContentScale.None
        it.contentAlignment = alignment
        it.setContentLocation(BaseContentLocation(Size(base.width.toFloat(), base.height.toFloat()), alignment))
    }

    val detail = remember { DetailHost() }

    val doubleTap = DoubleClickToZoomListener { zoomableState, centroid ->
        val scale = zoomableState.contentTransformation.takeIf { it.isSpecified }?.scale
        if (scale != null && max(scale.scaleX, scale.scaleY) > 1.001f) {
            zoomableState.resetZoom()
        } else {
            zoomableState.zoomTo(2f, centroid)
        }
    }

    // Telephoto animates through the composition's frame clock, which arbitrary callers (tests,
    // the benchmark) do not have: every automation call is dispatched into the composition
    // scope's context before touching the state.
    val composition = rememberCoroutineScope()
    val pageZoom = remember(state, composition) {
        object : ZoomAutomation {
            override suspend fun zoomTo(factor: Float, centroid: Offset, animate: Boolean) =
                withContext(composition.coroutineContext) {
                    state.zoomTo(
                        zoomFactor = factor,
                        centroid = centroid,
                        animationSpec = if (animate) {
                            ZoomableState.DefaultZoomAnimationSpec
                        } else {
                            SnapSpec()
                        },
                    )
                }

            override suspend fun panBy(delta: Offset, animate: Boolean) =
                withContext(composition.coroutineContext) {
                    state.panBy(
                        offset = delta,
                        animationSpec = if (animate) ZoomableState.DefaultPanAnimationSpec else SnapSpec(),
                    )
                }

            override suspend fun reset() = withContext(composition.coroutineContext) {
                state.resetZoom(animationSpec = SnapSpec())
            }
        }
    }
    DisposableEffect(pageZoom) {
        onZoom(pageZoom)
        onDispose { onZoom(null) }
    }

    // A re-rendered base (viewport, fit-mode or page change) re-fits: the composition itself
    // stays stable so gestures in flight (and the tap handler) survive the swap.
    LaunchedEffect(slot.image) {
        detail.clear(probe, page)
        state.resetZoom(animationSpec = SnapSpec())
    }

    LaunchedEffect(state) {
        val effectScope = this
        snapshotFlow { state.contentTransformation }
            .debounce(DETAIL_SETTLE_MS)
            .collect { transformation ->
                if (!transformation.isSpecified || viewport.width <= 0) return@collect
                val topLeft = currentAlignment.align(currentBase, viewport, currentLayoutDirection)
                val contentLeft = topLeft.x * transformation.scale.scaleX + transformation.offset.x
                val contentTop = topLeft.y * transformation.scale.scaleY + transformation.offset.y
                val region = visibleRegion(
                    pageW = currentPageSize.width,
                    pageH = currentPageSize.height,
                    baseW = currentBase.width,
                    baseH = currentBase.height,
                    zoom = max(transformation.scale.scaleX, transformation.scale.scaleY),
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    viewW = viewport.width,
                    viewH = viewport.height,
                )
                if (region == null) {
                    detail.clear(probe, page) // the base already shows everything there is
                    return@collect
                }
                detail.job?.cancel()
                detail.job = effectScope.launch {
                    try {
                        probe.onDetailRender(page, "start")
                        val image = renderer.render(page, region)
                        if (!isActive) {
                            image.release()
                            probe.onDetailRender(page, "cancelled")
                            return@launch
                        }
                        image.prepareToDraw()
                        detail.shown?.image?.release()
                        detail.shown = ShownDetail(
                            image = image,
                            // The region's on-screen rect: the intersection of the transformed
                            // content with the viewport, matching visibleRegion's x0/y0.
                            rect = Rect(
                                left = contentLeft.coerceAtLeast(0f),
                                top = contentTop.coerceAtLeast(0f),
                                right = contentLeft.coerceAtLeast(0f) + region.outW,
                                bottom = contentTop.coerceAtLeast(0f) + region.outH,
                            ),
                        )
                        probe.onDetailImage(page, region)
                        probe.onDetailRender(page, "done")
                    } catch (e: CancellationException) {
                        probe.onDetailRender(page, "cancelled")
                    }
                }
            }
    }
    DisposableEffect(state) {
        onDispose {
            detail.job?.cancel()
            detail.shown?.image?.release()
            detail.shown = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it }
                .zoomable(state = state, onClick = onTap, onDoubleClick = doubleTap),
            contentAlignment = alignment,
        ) {
            // Fire the sharp-draw probe exactly once per (page, bitmap) - once the bitmap is
            // actually drawn, not while telephoto still hides the unmeasured content.
            val fired = remember(slot.image) { AtomicBoolean(false) }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(with(density) { base.width.toDp() }, with(density) { base.height.toDp() })
                    .drawWithContent {
                        drawContent()
                        // isSpecified first: while telephoto still hides the unmeasured content,
                        // the draw must not consume the once-only flag.
                        if (state.contentTransformation.isSpecified && fired.compareAndSet(false, true)) {
                            probe.onSharpPageDrawn(page, System.nanoTime())
                        }
                    },
            )
        }

        detail.shown?.let { shown ->
            val detailBitmap = shown.image.bitmap ?: return@let
            Image(
                bitmap = detailBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.None,
                modifier = Modifier
                    .offset {
                        IntOffset(shown.rect.left.roundToInt(), shown.rect.top.roundToInt())
                    }
                    .size(
                        with(density) { shown.rect.width.toDp() },
                        with(density) { shown.rect.height.toDp() },
                    ),
            )
        }
    }
}
