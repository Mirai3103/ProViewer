package com.proviewer.app.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.proviewer.app.engine.PageImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single-page reader: an LTR/RTL [HorizontalPager], full-screen tap zones, an overlay with the
 * title, back button, page slider and direction/fit toggles, and immersive system bars.
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

    val pagerState = rememberPagerState(initialPage = state.page, pageCount = { state.count })

    // ViewModel -> pager: animate to the logical page when it changes (tap zones, volume keys,
    // slider, automation). Skipped when the pager is already there (a settle from a user swipe).
    LaunchedEffect(state.page) {
        if (pagerState.currentPage != state.page) {
            pagerState.animateScrollToPage(state.page)
        }
    }

    // Pager -> ViewModel: report the settled page after a user swipe, but not the intermediate
    // pages a fling or a programmatic animation passes through.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (page, inProgress) ->
                if (!inProgress) vm.onPageSettled(page)
            }
    }

    val automation = remember(vm, pagerState) {
        object : ReaderAutomation {
            override suspend fun turnTo(page: Int, animate: Boolean) {
                vm.goTo(page)
                if (animate) pagerState.animateScrollToPage(page) else pagerState.scrollToPage(page)
            }

            override suspend fun awaitIdle() {
                vm.window?.awaitIdle()
            }

            override val zoom: ZoomAutomation? get() = null
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
        ) { page ->
            PageSlotView(page, vm, state.direction, probe)
        }

        if (state.overlayVisible) {
            ReaderOverlay(state, vm, onBack)
        }
    }
}

@Composable
private fun PageSlotView(page: Int, vm: ReaderViewModel, direction: Direction, probe: ReaderProbe) {
    val slot = vm.window?.pages?.get(page)
    Box(
        Modifier
            .fillMaxSize()
            .testTag("reader-page-$page")
            .pointerInput(direction) {
                detectTapGestures { offset ->
                    when (tapAction(offset.x, size.width.toFloat(), direction)) {
                        TapAction.Previous -> vm.previous()
                        TapAction.Next -> vm.next()
                        TapAction.ToggleOverlay -> vm.toggleOverlay()
                    }
                }
            },
    ) {
        when (slot) {
            is PageSlot.Ready -> {
                val bitmap = slot.image.bitmap
                if (bitmap != null) {
                    SharpPage(page, slot.image, slot.size, vm.state?.fitMode ?: FitMode.Screen, probe)
                }
            }
            is PageSlot.Failed -> ErrorSlot(page, slot.reason)
            else -> Box(Modifier.fillMaxSize().background(Color(0xFF111111)))
        }
    }
}

@Composable
private fun SharpPage(
    page: Int,
    image: PageImage,
    size: IntSize,
    mode: FitMode,
    probe: ReaderProbe,
) {
    val bitmap = image.bitmap ?: return
    val density = LocalDensity.current
    // Fire the sharp-draw probe exactly once per (page, bitmap) — not per frame while static.
    val fired = remember(page, bitmap) { AtomicBoolean(false) }
    val alignment = when (mode) {
        FitMode.Screen -> Alignment.Center
        FitMode.Width -> Alignment.TopStart
        FitMode.Height -> Alignment.TopStart
    }
    Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
                .drawWithContent {
                    drawContent()
                    if (fired.compareAndSet(false, true)) {
                        probe.onSharpPageDrawn(page, System.nanoTime())
                    }
                },
        )
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
