package com.proviewer.app.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * The paged reader over [ReaderUnit]s (spec 6.5): an LTR/RTL [HorizontalPager] whose pages are
 * the book's units (a whole page, a split half or a two-page spread), full-screen tap zones, an
 * overlay with the title, back button, page slider and direction/fit toggles, and immersive
 * system bars. Slider and label keep working on book pages.
 */
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    onBack: () -> Unit,
    onAutomation: (ReaderAutomation) -> Unit = {},
) {
    val state = vm.state
    val probe = LocalReaderProbe.current
    val context = LocalContext.current

    if (state == null) {
        Box(Modifier.fillMaxSize().testTag("reader-root").background(Color.Black))
        return
    }

    // Immersive + keep-screen-on while reading; system bars return while the overlay is shown.
    DisposableEffect(state.overlayVisible) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setSystemBarsVisible(window, state.overlayVisible)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setSystemBarsVisible(window, true)
        }
    }

    val pagerState = rememberPagerState(initialPage = state.unit, pageCount = { vm.units.size })

    // True from the start of a user drag on the pager until its scroll settles. Only such a
    // scroll may move the ViewModel: a programmatic animation that is interrupted by the next one
    // (two quick volume presses) briefly reports "not scrolling" at an intermediate unit, and
    // reporting that would snap the reader back to it.
    val userScroll = remember { AtomicBoolean(false) }
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) userScroll.set(true)
        }
    }

    // ViewModel -> pager: animate to the current unit when it changes (tap zones, volume keys,
    // slider, layout switches, automation). Skipped when the pager is already there (a settle
    // from a user swipe).
    LaunchedEffect(state.unit) {
        if (pagerState.currentPage != state.unit) {
            userScroll.set(false) // the VM now owns the target; a leftover fling must not report
            pagerState.animateScrollToPage(state.unit)
        }
    }

    // Pager -> ViewModel: report the settled unit after a user swipe, but not the intermediate
    // units a fling or a programmatic animation passes through.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (unit, inProgress) ->
                if (!inProgress) vm.onUnitSettled(unit, fromUser = userScroll.getAndSet(false))
            }
    }

    // Each rendered unit publishes its ZoomableUnit automation; the current unit's is exposed.
    val unitZooms = remember { mutableStateMapOf<Int, ZoomAutomation>() }
    val automation = remember(vm, pagerState) {
        object : ReaderAutomation {
            override suspend fun turnTo(page: Int, animate: Boolean) {
                vm.goTo(page)
                val unit = vm.state?.unit ?: return
                if (animate) pagerState.animateScrollToPage(unit) else pagerState.scrollToPage(unit)
            }

            override suspend fun awaitIdle() {
                vm.window?.awaitIdle()
            }

            override val zoom: ZoomAutomation? get() = unitZooms[vm.state?.unit]
        }
    }
    LaunchedEffect(automation) { onAutomation(automation) }

    BackHandler(onBack = onBack)

    Box(
        Modifier
            .fillMaxSize()
            .testTag("reader-root")
            .background(Color.Black)
            .onSizeChanged { vm.onViewport(it.width, it.height) },
    ) {
        HorizontalPager(
            state = pagerState,
            reverseLayout = state.direction == Direction.Rtl,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { unitIndex ->
            UnitSlotView(
                unitIndex = unitIndex,
                vm = vm,
                direction = state.direction,
                fitMode = state.fitMode,
                probe = probe,
                onZoom = { zoomAutomation ->
                    if (zoomAutomation == null) unitZooms.remove(unitIndex) else unitZooms[unitIndex] = zoomAutomation
                },
            )
        }

        if (state.overlayVisible) {
            ReaderOverlay(state, vm, onBack)
        }
    }
}

@Composable
private fun UnitSlotView(
    unitIndex: Int,
    vm: ReaderViewModel,
    direction: Direction,
    fitMode: FitMode,
    probe: ReaderProbe,
    onZoom: (ZoomAutomation?) -> Unit,
) {
    val state = vm.state ?: return
    val unit = vm.units.getOrNull(unitIndex)
    val viewport = vm.viewport
    val renderer = vm.renderer
    val budget = vm.budget
    val sizes = vm.sizes

    // Read the snapshot-tracked inputs before the geometry branch: the size prober's maps and the
    // viewport are not snapshot state, so this composable must subscribe to the window's slots
    // and the prober's version to recompose once they land (otherwise a unit whose page sizes
    // were unknown at the first composition would stay a placeholder forever).
    val slotMap: Map<UnitPart, PageSlot> =
        unit?.parts?.associateWith { part -> vm.window?.slots?.get(part) ?: PageSlot.Loading } ?: emptyMap()
    // Subscription read: `version` is snapshot state while the size maps are not; its value is
    // not used beyond triggering this recomposition.
    sizes?.version

    // Telephoto 0.19.0 captures zoomable's onClick once (its tap node only swaps the handler when
    // the parameter's nullability flips), so the handler must resolve the live values itself
    // instead of capturing this recomposition's direction and size.
    val slotSize = remember { mutableStateOf(IntSize.Zero) }
    val onUnitTap: (Offset) -> Unit = remember(vm) {
        { offset: Offset ->
            when (tapAction(offset.x, slotSize.value.width.toFloat(), vm.state?.direction ?: Direction.Ltr)) {
                TapAction.Previous -> vm.previous()
                TapAction.Next -> vm.next()
                TapAction.ToggleOverlay -> vm.toggleOverlay()
            }
        }
    }

    val geometry = if (unit != null && viewport != null) {
        unitGeometry(
            unit = unit,
            sizeOf = { page -> sizes?.known(page) ?: renderer?.knownPageSize(page) },
            viewW = viewport.width,
            viewH = viewport.height,
            mode = fitMode,
            direction = direction,
        )
    } else {
        null
    }

    Box(
        Modifier
            .fillMaxSize()
            // The pager page is the unit; in Single without split it is the book page itself.
            .testTag("reader-page-$unitIndex")
            .onSizeChanged { slotSize.value = it }
            .then(
                if (geometry != null) {
                    // The rendered unit handles taps through its zoomable gestures.
                    Modifier
                } else {
                    Modifier.pointerInput(direction) { detectTapGestures(onTap = onUnitTap) }
                },
            ),
    ) {
        if (unit != null && geometry != null && renderer != null && budget != null) {
            ZoomableUnit(
                unitIndex = unitIndex,
                geometry = geometry,
                slots = slotMap,
                renderer = renderer,
                budget = budget,
                probe = probe,
                isCurrent = unitIndex == state.unit,
                mode = fitMode,
                onTap = onUnitTap,
                onZoom = onZoom,
            )
        } else if (unit != null) {
            // No geometry: at least one part's page size is unknown (not probed yet, or its size
            // probe failed). Probe-failed parts never get a window target, so they would
            // otherwise render as a blank box: show the error placeholder for them.
            RowFillPlaceholders(unit, sizes)
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF111111)))
        }
    }
}

/** Whole-slot placeholders for a unit whose geometry is unknown (a part's size is unknown). */
@Composable
private fun RowFillPlaceholders(unit: ReaderUnit, sizes: PageSizes?) {
    Row(Modifier.fillMaxSize()) {
        for (part in unit.parts) {
            Box(Modifier.weight(1f).fillMaxSize()) {
                if (sizes?.failed(part.page) == true) {
                    ErrorSlot(part.page, "size probe failed")
                } else {
                    Box(Modifier.fillMaxSize().background(Color(0xFF111111)))
                }
            }
        }
    }
}

@Composable
private fun ErrorSlot(page: Int, reason: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF111111))
            .testTag("reader-error-$page"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Page ${page + 1}", color = Color.LightGray)
            Text(reason, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReaderOverlay(state: ReaderState, vm: ReaderViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("reader-overlay")) {
        // Top bar (back + title). Taps here are handled by the buttons.
        Row(
            Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back", color = Color.White) }
            Text(state.title, color = Color.White, modifier = Modifier.padding(start = 8.dp))
        }

        // Transparent middle: has no pointer input, so a centre tap still reaches the page below
        // and toggles the overlay back off.
        Spacer(Modifier.weight(1f))

        // Bottom controls (slider + n/total + direction and fit toggles).
        Column(Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(16.dp)) {
            var sliderValue by remember(state.page) { mutableFloatStateOf(state.page.toFloat()) }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { vm.goTo(sliderValue.roundToInt()) },
                valueRange = 0f..(state.count - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.testTag("reader-slider"),
            )
            Text("${state.page + 1} / ${state.count}", color = Color.White)
            Row {
                TextButton(
                    onClick = {
                        vm.setDirection(if (state.direction == Direction.Ltr) Direction.Rtl else Direction.Ltr)
                    },
                    modifier = Modifier.testTag("reader-dir"),
                ) {
                    Text(if (state.direction == Direction.Ltr) "LTR" else "RTL", color = Color.White)
                }
                TextButton(
                    onClick = { vm.setFitMode(nextFitMode(state.fitMode)) },
                    modifier = Modifier.testTag("reader-fit"),
                ) {
                    Text(state.fitMode.name, color = Color.White)
                }
            }
        }
    }
}

private fun nextFitMode(mode: FitMode): FitMode = when (mode) {
    FitMode.Screen -> FitMode.Width
    FitMode.Width -> FitMode.Height
    FitMode.Height -> FitMode.Screen
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun setSystemBarsVisible(window: Window?, visible: Boolean) {
    if (window == null) return
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (visible) {
        controller.show(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
