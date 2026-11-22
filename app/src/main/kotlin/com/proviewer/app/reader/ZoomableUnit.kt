@file:OptIn(FlowPreview::class)

package com.proviewer.app.reader

import androidx.compose.animation.core.SnapSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach
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

/** A rendered detail image and the on-screen rect (in the unit slot) it is drawn at. */
private class ShownDetail(val image: PageImage, val rect: Rect)

/** The part of a content transformation a detail render depends on. */
private data class TransformKey(val scale: ScaleFactor, val offset: Offset)

/**
 * One part's detail pipeline state (D5): either a rendered [ShownDetail] is displayed over the
 * part's base bitmap, or nothing. A new transformation cancels the pending or running detail job,
 * and a settled null region clears the detail.
 */
private class PartDetailHost(val part: UnitPart) {
    var job: Job? = null
    var madeFor: TransformKey? = null
    var shown: ShownDetail? by mutableStateOf(null)

    /** Cancels any pending or running render and drops the shown detail, reporting it cleared. */
    fun clear(probe: ReaderProbe) {
        job?.cancel()
        job = null
        madeFor = null
        hide(probe)
    }

    /** Drops the shown detail (if any) and reports it cleared, without touching the job. */
    fun hide(probe: ReaderProbe) {
        shown?.let {
            it.image.release()
            probe.onDetailImage(part.page, null)
        }
        shown = null
    }
}

/**
 * All parts' detail hosts of one unit. The map is snapshot state so a host added by the settle
 * pipeline (a part that became worth detailing) is picked up by the next composition; its
 * `shown` state is tracked there too.
 */
private class UnitDetailHost {
    val parts = mutableStateMapOf<UnitPart, PartDetailHost>()

    fun host(part: UnitPart): PartDetailHost = parts.getOrPut(part) { PartDetailHost(part) }

    /** Clears every part's detail and forgets parts the current geometry no longer has. */
    fun reset(probe: ReaderProbe) {
        for (host in parts.values) host.clear(probe)
        parts.clear()
    }

    /** Cancels every pending render and releases every shown detail (final teardown). */
    fun dispose() {
        for (host in parts.values) {
            host.job?.cancel()
            host.shown?.image?.release()
            host.shown = null
        }
        parts.clear()
    }
}

/**
 * The visual bounds of the unit's content within the viewport: its laid-out (fit) size, centred
 * ([FitMode.Screen]) or top-left aligned ([FitMode.Width], [FitMode.Height]). [Modifier.zoomable]
 * uses it for edge detection, and it must match where this composable actually draws the content.
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
 * One reader unit's zoomable rendering (spec D5 approach A, generalized from one page to the
 * unit's placements, spec 6.5). Each placement's base bitmap is drawn at its `dst` rectangle
 * inside the unit's fitted content; the whole content is scaled by Telephoto's draw-phase
 * [Modifier.zoomable] while a gesture runs - the transforms never recompose per frame - and once
 * the content transformation has settled for [DETAIL_SETTLE_MS], every part whose page area
 * intersects the screen ([visiblePartRegion]) is re-rendered by [PageRenderer.render] at screen
 * resolution into one detail [PageImage] per part, drawn at the region's on-screen rect over its
 * base. Detail bytes are reserved in [budget] first; when the reservation fails no detail is
 * rendered (the next settle retries).
 *
 * At fit scale the base already provides every pixel (unless a base was memory-capped and is
 * drawn upscaled), so the detail pass is skipped while `zoom <= 1.001`, no placement is capped
 * and the content fills the viewport in the fitted dimension - without this gate,
 * [visiblePartRegion] would return a needless region for width-constrained two-page parts.
 *
 * The maximum zoom lets every base pixel be blown up until at least one page pixel covers one
 * screen pixel (at least 4x), so deep zooms always reach source pixels. Double-tap zooms to 2x
 * around the tap point, or back to fit when already zoomed. Panning stops at the content's edges,
 * where the gesture falls through to the surrounding pager and turns the unit. Taps are routed to
 * [onTap] (tap zones); the unit's [ZoomAutomation] is published through [onZoom] for
 * `ReaderAutomation.zoom`. [probe] fires [ReaderProbe.onSharpPageDrawn] once per drawn part
 * bitmap, but only while [isCurrent] (E5) - a page drawn at rest as a neighbour does not fire, so
 * a later turn to it still produces a fresh event.
 */
@Composable
fun ZoomableUnit(
    unitIndex: Int,
    geometry: UnitGeometry,
    slots: Map<UnitPart, PageSlot>,
    renderer: PageRenderer,
    budget: ImageBudget,
    probe: ReaderProbe,
    isCurrent: Boolean,
    mode: FitMode,
    onTap: (Offset) -> Unit,
    onZoom: (ZoomAutomation?) -> Unit,
) {
    val alignment = when (mode) {
        FitMode.Screen -> Alignment.Center
        FitMode.Width, FitMode.Height -> Alignment.TopStart
    }
    val content = geometry.content
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // The detail pipeline outlives recompositions, so it reads the current geometry through
    // updated-state holders instead of capturing this recomposition's values.
    val currentAlignment by rememberUpdatedState(alignment)
    val currentLayoutDirection by rememberUpdatedState(layoutDirection)
    val currentGeometry by rememberUpdatedState(geometry)
    val currentSlots by rememberUpdatedState(slots)
    val currentRenderer by rememberUpdatedState(renderer)
    val currentBudget by rememberUpdatedState(budget)

    val state = rememberZoomableState(
        zoomSpec = ZoomSpec(
            maxZoomFactor = geometry.placements.maxOf { placement ->
                max(4f, placement.src.width.toFloat() / placement.base.width.coerceAtLeast(1))
            },
        ),
    ).also {
        // The content is drawn at its fit size, so telephoto's zoom factor - and the ZoomSpec
        // limit - are screen pixels per fit-size pixel, exactly what visiblePartRegion expects.
        it.contentScale = ContentScale.None
        it.contentAlignment = alignment
        it.setContentLocation(BaseContentLocation(Size(content.width.toFloat(), content.height.toFloat()), alignment))
    }

    val details = remember { UnitDetailHost() }

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
    val unitZoom = remember(state, composition) {
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
    DisposableEffect(unitZoom) {
        onZoom(unitZoom)
        onDispose { onZoom(null) }
    }

    // A re-laid-out unit (viewport, fit-mode or unit change) re-fits: the composition itself
    // stays stable so gestures in flight (and the tap handler) survive the swap.
    LaunchedEffect(geometry) {
        details.reset(probe)
        state.resetZoom(animationSpec = SnapSpec())
    }

    LaunchedEffect(state) {
        val effectScope = this
        snapshotFlow { state.contentTransformation }
            .onEach { transformation ->
                // A new transformation invalidates every detail at once: its rect no longer
                // matches where the base is drawn, so a stale sharp patch must not float over a
                // moving base until the next settle (and a render for the old one must not land).
                val key = transformation.takeIf { it.isSpecified }?.let { TransformKey(it.scale, it.offset) }
                if (key != null) {
                    for (host in details.parts.values) {
                        if (key != host.madeFor) host.clear(probe)
                    }
                }
            }
            .debounce(DETAIL_SETTLE_MS)
            .collect { transformation ->
                if (!transformation.isSpecified || viewport.width <= 0) return@collect
                val geometryNow = currentGeometry
                val slotsNow = currentSlots
                val zoom = max(transformation.scale.scaleX, transformation.scale.scaleY)
                val topLeft = currentAlignment.align(
                    geometryNow.content,
                    viewport,
                    currentLayoutDirection,
                )
                val contentLeft = topLeft.x * transformation.scale.scaleX + transformation.offset.x
                val contentTop = topLeft.y * transformation.scale.scaleY + transformation.offset.y

                // The fit-scale gate: a base drawn upscaled (memory-capped) still needs a detail
                // render at fit; otherwise, with the content filling the view, no part does.
                fun isCapped(placement: PartPlacement): Boolean {
                    val slot = slotsNow[placement.part] as? PageSlot.Ready ?: return false
                    return slot.size.width.toLong() * slot.size.height <
                        placement.dst.width.toLong() * placement.dst.height
                }
                val anyCapped = geometryNow.placements.any { isCapped(it) }
                val contentFillsView =
                    geometryNow.content.width >= viewport.width || geometryNow.content.height >= viewport.height
                if (zoom <= 1.001f && !anyCapped && contentFillsView) {
                    for (host in details.parts.values) host.clear(probe)
                    return@collect
                }

                for (placement in geometryNow.placements) {
                    val slot = slotsNow[placement.part] as? PageSlot.Ready ?: continue
                    if (slot.image.bitmap == null) continue
                    val host = details.host(placement.part)
                    val region = visiblePartRegion(
                        src = placement.src,
                        dst = placement.dst,
                        base = slot.size,
                        zoom = zoom,
                        contentLeft = contentLeft,
                        contentTop = contentTop,
                        viewW = viewport.width,
                        viewH = viewport.height,
                        baseCapped = isCapped(placement),
                    )
                    if (region == null) {
                        host.clear(probe) // the base already shows everything there is
                        continue
                    }
                    host.job?.cancel()
                    host.madeFor = TransformKey(transformation.scale, transformation.offset)
                    host.job = effectScope.launch {
                        renderPartDetail(
                            host = host,
                            placement = placement,
                            region = region,
                            contentLeft = contentLeft,
                            contentTop = contentTop,
                            zoom = zoom,
                            renderer = currentRenderer,
                            budget = currentBudget,
                            probe = probe,
                        )
                    }
                }
            }
    }
    DisposableEffect(state) {
        onDispose { details.dispose() }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it }
                .zoomable(state = state, onClick = onTap, onDoubleClick = doubleTap),
            contentAlignment = alignment,
        ) {
            // The unit's content at zoom 1: one placement's base per part, edge to edge, exactly
            // where BaseContentLocation says the content is.
            Box(
                Modifier
                    .wrapContentSize(alignment, unbounded = true)
                    .size(with(density) { content.width.toDp() }, with(density) { content.height.toDp() }),
            ) {
                for (placement in geometry.placements) {
                    when (val slot = slots[placement.part]) {
                        is PageSlot.Ready -> {
                            val bitmap = slot.image.bitmap ?: continue
                            PartBaseImage(
                                placement = placement,
                                bitmap = bitmap,
                                state = state,
                                isCurrent = isCurrent,
                                probe = probe,
                                density = density,
                            )
                        }
                        is PageSlot.Failed -> UnitErrorBox(
                            placement = placement,
                            reason = slot.reason,
                            density = density,
                        )
                        else -> Box(
                            Modifier
                                .offset { IntOffset(placement.dst.left, placement.dst.top) }
                                .size(
                                    with(density) { placement.dst.width.toDp() },
                                    with(density) { placement.dst.height.toDp() },
                                )
                                .background(Color(0xFF111111)),
                        )
                    }
                }
            }
        }

        // Every part's settled detail image, drawn in screen space over the bases.
        for (host in details.parts.values) {
            val shown = host.shown ?: continue
            val bitmap = shown.image.bitmap ?: continue
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            // Drawn by hand instead of through Image: Image paints inside its own clip layer,
            // which Compose re-records on its own (without this node's draw modifiers), and a
            // re-record after the bitmap was recycled throws. A draw modifier on a node without
            // a layer of its own runs on every record of the enclosing layer, so the guard holds.
            Spacer(
                Modifier
                    .offset {
                        IntOffset(shown.rect.left.roundToInt(), shown.rect.top.roundToInt())
                    }
                    .size(
                        with(density) { shown.rect.width.toDp() },
                        with(density) { shown.rect.height.toDp() },
                    )
                    .drawBehind {
                        // Released on main while this composition still holds it (until the next
                        // recomposition drops it): draw nothing instead of throwing.
                        if (!bitmap.isRecycled) drawImage(image)
                    },
            )
        }
    }
}

/**
 * Renders one part's detail region off-main into a budget-reserved image and shows it over the
 * part's base at the region's on-screen rect (the intersection of the transformed part with the
 * viewport, matching [visiblePartRegion]'s x0/y0). A failed or cancelled render degrades the way
 * the base pipeline degrades a failed render: drop any stale detail and keep the scaled base
 * visible instead of crashing the reader; the next settled transformation simply tries again.
 */
private suspend fun renderPartDetail(
    host: PartDetailHost,
    placement: PartPlacement,
    region: PageRegion,
    contentLeft: Float,
    contentTop: Float,
    zoom: Float,
    renderer: PageRenderer,
    budget: ImageBudget,
    probe: ReaderProbe,
) {
    val bytes = rgbaBytes(region.outW, region.outH)
    // No detail if the reservation fails; the next settle retries.
    if (!budget.tryReserve(bytes)) return
    var handedOver = false
    try {
        probe.onDetailRender(host.part.page, "start")
        val image = renderer.render(host.part.page, region)
        if (!currentCoroutineContext().isActive) {
            image.release()
            probe.onDetailRender(host.part.page, "cancelled")
            return
        }
        image.prepareToDraw()
        val owned = budget.wrap(image, bytes)
        handedOver = true // the wrapper now returns the bytes
        host.shown?.image?.release()
        host.shown = ShownDetail(
            image = owned,
            rect = Rect(
                left = (contentLeft + zoom * placement.dst.left).coerceAtLeast(0f),
                top = (contentTop + zoom * placement.dst.top).coerceAtLeast(0f),
                right = (contentLeft + zoom * placement.dst.left).coerceAtLeast(0f) + region.outW,
                bottom = (contentTop + zoom * placement.dst.top).coerceAtLeast(0f) + region.outH,
            ),
        )
        probe.onDetailImage(host.part.page, region)
        probe.onDetailRender(host.part.page, "done")
    } catch (e: CancellationException) {
        probe.onDetailRender(host.part.page, "cancelled")
        throw e
    } catch (e: Throwable) {
        host.hide(probe)
        probe.onDetailRender(host.part.page, "cancelled")
    } finally {
        if (!handedOver) budget.release(bytes)
    }
}

/**
 * One placement's base bitmap, drawn at its `dst` rectangle inside the unit's content and scaled
 * by the surrounding telephoto node. Fires the sharp-draw probe exactly once per bitmap - once
 * the bitmap is actually drawn while the unit is current, not while telephoto still hides the
 * unmeasured content.
 */
@Composable
private fun PartBaseImage(
    placement: PartPlacement,
    bitmap: android.graphics.Bitmap,
    state: ZoomableState,
    isCurrent: Boolean,
    probe: ReaderProbe,
    density: Density,
) {
    val fired = remember(bitmap) { AtomicBoolean(false) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // Drawn by hand instead of through Image, whose own clip layer Compose re-records without this
    // node's draw modifiers: a re-record after the bitmap was recycled (on main: window eviction,
    // a re-spec, the window closing for Webtoon — while this composition still holds the bitmap
    // until its next recomposition) throws "trying to use a recycled bitmap". A draw modifier on a
    // node without a layer of its own runs on every record of the enclosing layer, and every
    // release runs on main, so this check cannot race one; the stale frame shows nothing.
    Spacer(
        Modifier
            .offset { IntOffset(placement.dst.left, placement.dst.top) }
            .size(
                with(density) { placement.dst.width.toDp() },
                with(density) { placement.dst.height.toDp() },
            )
            .drawBehind {
                if (bitmap.isRecycled) return@drawBehind
                // Fill the bounds (the painter's ContentScale.FillBounds), default Low filtering.
                drawImage(
                    image,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Low,
                )
                // The sharp-draw probe fires only after a real draw. isSpecified first: while
                // telephoto still hides the unmeasured content, the draw must not consume the
                // once-only flag. It fires only while this unit is the current one (E5), so a page
                // drawn at rest as a neighbour still fires when the reader later turns to it.
                if (isCurrent && state.contentTransformation.isSpecified && fired.compareAndSet(false, true)) {
                    probe.onSharpPageDrawn(placement.part.page, System.nanoTime())
                }
            },
    )
}

/** A part whose render failed shows the error placeholder inside its own `dst` rectangle. */
@Composable
private fun UnitErrorBox(placement: PartPlacement, reason: String, density: Density) {
    Box(
        Modifier
            .offset { IntOffset(placement.dst.left, placement.dst.top) }
            .size(
                with(density) { placement.dst.width.toDp() },
                with(density) { placement.dst.height.toDp() },
            )
            .background(Color(0xFF111111))
            .testTag("reader-error-${placement.part.page}"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Page ${placement.part.page + 1}", color = Color.LightGray)
            Text(reason, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}
