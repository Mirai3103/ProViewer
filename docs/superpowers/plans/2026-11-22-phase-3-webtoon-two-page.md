# ProViewer Phase 3 — Webtoon, Two-page and Spread Splitting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The reader auto-selects Single / Two-page / Webtoon on open (manual override in the overlay), splits wide pages in Single, shows two-page units in the pager, and scrolls long Webtoon strips through a self-drawn, tiled, zoomable viewport under a hard image-byte cap; the Benchmark screen gains a1 and a correct b-prefetched metric.

**Architecture:** Rust gains a fit-mode-aware `prefetch_hint` and a seam-exact `render_scaled_tile` (a sub-rectangle of a virtual full-page render) with JNI entry points. Kotlin gains pure models (mode detection, units/pairing/split, unit geometry, Webtoon layout, tile grid), a shared `ImageBudget` (192 MiB cap), a `PageSizes` background prober, a part-keyed `PageWindow`, a `TileManager`, a generalized `ZoomableUnit` for the pager, and a `WebtoonViewport` that draws tiles in the draw phase from one scroll/zoom/pan transform.

**Tech Stack:** unchanged from Phase 2 (Rust 1.100 stable + cargo-ndk, uniffi, `jni` `=0.22.4`; Kotlin 2.4.21, AGP 9.5.1, Compose BOM 2026.09.00, Telephoto `zoomable` `0.19.0`, JUnit 4). No new dependency.

**Spec:** `docs/superpowers/specs/2026-11-22-phase-3-webtoon-two-page-design.md` (owner-approved 2026-11-22; decisions E1–E5 in §2). Previous: `docs/superpowers/specs/2026-11-19-phase-2-reader-mvp-design.md`, `docs/perf/phase2-reader.md`. Budgets: `docs/perf/budgets.md`.

## Global Constraints

- **No real-device runs.** Every `adb`, `gradlew connected*` and install command sets `$env:ANDROID_SERIAL = 'emulator-5554'` (AVD `ProViewer_API37`, API 37, 1080×2378). Never target serial `3B65BT02H6G00000`. `scripts/phase1-device-session.ps1`, `scripts/device-run.ps1`, `scripts/perf-capture.ps1` are not run. Emulator numbers are never performance results.
- Phase 3 exit is functional on host + emulator (D2 model). The real-device Benchmark run is the owner's checkpoint after this plan.
- No decode, resize or `render*` call on the main thread. Allowed on main: `prefetchHint`, `cancelPrefetch`, `pageCount`, cached page info.
- Webtoon scroll/zoom/pan state is read **only in the draw phase and gesture handlers** — no recomposition per scroll or zoom frame.
- Bytes held by `PageWindow` bases, `ZoomableUnit` details and `TileManager` tiles never exceed **`IMAGE_CAP_BYTES = 192 MiB`** (reserved *before* allocation).
- No visible seam/gap/1-px jump between adjacent tiles or adjacent Webtoon pages.
- Release profile keeps `panic = "unwind"`; input limits before allocation; linear-light Lanczos3 stays the only resize path; every `.so` 16 KB-aligned (`scripts/check-16kb.ps1`); release APK has no benchmark code (`scripts/check-release-clean.ps1`).
- Kotlin reads `EngineException`/`NativeRenderException` text from `detail`, never `message`.
- Pin exact versions; Cargo.lock committed. Docs/plans in **English**. Scripts: `powershell -NoProfile -ExecutionPolicy Bypass -File <script>`.
- **Commit only when the owner asks.** Ask once at the start whether commits are authorized during execution; every commit step runs only if so. Branch: fast-forward local `master` to `origin/master` (`ff72a45`), then create `phase-3` from it. The first commit (if authorized) adds the spec and this plan.
- **Preflight (not setup):** `scripts/check-env.ps1` passes; `emulator -list-avds` lists `ProViewer_API37`; Task 13 fuzzing needs WSL distro `proviewer` (`docs/dev/wsl-proviewer-setup.md`). If a preflight fails, stop and point to the setup doc; install nothing.
- Existing tests that open `huge-single.cbz` (12000×8000, wide) or `webtoon-long.cbz` must pin the layout they assume (`SplitChoice.Off` / `LayoutChoice.Single`) once Task 7 adds auto-selection; the a3-huge benchmark scenario pins `SplitChoice.Off` so it stays comparable with Phase 2.

## Review Focus

1. **Far slider jump in Webtoon on a long book** (500 pages, most heights still estimated) must land with the target page's top at the viewport top and must not queue renders for passed pages. Pinned in Task 10 (`slider_far_jump_lands_on_page_top`).
2. **Height corrections above the viewport while reading** (background probing finishes) must not move the content on screen. Pinned in Task 7 (`correction_above_anchor_keeps_content_fixed`).
3. **Rotation in Webtoon while zoomed** resets zoom to 1× *(designer's call)* and keeps the anchor page and its relative offset. Pinned in Task 10 (`rotation_keeps_anchor_page`).
4. **Detection on books with failed probes** (corrupt entries) ignores failed pages; all probes failing → Single. Pinned in Task 3 (`detect_ignores_failed_probes`, `detect_all_failed_is_not_strip`).
5. **A fling faster than rendering** may show placeholders but must cancel tiles that left the working set and never exceed the cap. Pinned in Task 9 (`rapid_view_changes_cancel_stale_tiles`, `random_sequence_never_exceeds_cap`).

## File Structure

```
core/src/engine.rs                 + FitTarget (uniffi enum), fit_size_for, prefetch_hint(.., fit), ScaledTileRequest, Book::render_scaled_tile
core/src/resize.rs                 render_region_with_budget takes a float crop; + render_scaled_region, MAX_SCALED_FULL_DIM
core/src/android/pixels.rs         + JNI renderScaledToBitmap / renderScaledToHardwareBuffer
core/tests/engine_api.rs           prefetch-fit and scaled-tile tests
tools/gen-corpus/{Cargo.toml,src/lib.rs}, corpus/README.md   + two-page.cbz, version 0.2.0
app/src/main/kotlin/com/proviewer/app/
  engine/NativePixels.kt           + renderScaledToBitmap / renderScaledToHardwareBuffer
  engine/PageRenderer.kt           renderRegion, renderScaled, prefetchHint(.., mode); TileSource seam
  reader/ImageBudget.kt            NEW cap accounting + budgeted PageImage wrapper
  reader/PageSizes.kt              NEW background page-size prober
  reader/ReadingMode.kt            NEW pure: Layout, LayoutChoice, SplitChoice, detection, resolution
  reader/Units.kt                  NEW pure: Part, UnitPart, ReaderUnit, buildUnits, unitIndexOf, windowTargets
  reader/UnitGeometry.kt           NEW pure: PartPlacement, UnitGeometry, unitGeometry, visiblePartRegion
  reader/PageWindow.kt             keyed by UnitPart, PartTarget list, budget
  reader/ZoomableUnit.kt           RENAMED from ZoomablePage.kt, draws 1–2 parts, per-part detail
  reader/ReaderLogic.kt            visibleRegion delegates to visiblePartRegion; tapAction gains vertical variant
  reader/ReaderViewModel.kt        layout/split/shift state, units, PageSizes, webtoon session, commands
  reader/ReaderScreen.kt           pager over units | WebtoonViewport; overlay controls
  reader/ReaderProbe.kt            current-only sharp probe; + onPlaceholderDrawn; + WebtoonAutomation
  reader/webtoon/WebtoonLayout.kt  NEW pure: stacked fit-width layout, anchors
  reader/webtoon/TileGrid.kt       NEW pure: TileKey, TileSpec, WebtoonView, wantedTiles
  reader/webtoon/TileManager.kt    NEW tile working set, rendering, eviction under the cap
  reader/webtoon/WebtoonViewport.kt NEW composable: gestures, fling, zoom, draw-phase tiles
  MainActivity.kt                  volume keys route through vm.next()/previous() (unchanged API)
app/src/bench/kotlin/com/proviewer/app/bench/  Scenarios.kt (+a1, b fix), BenchmarkRunner.kt, BenchmarkJson.kt (schema 2), BenchmarkScreen.kt
app/src/test/kotlin/com/proviewer/app/reader/  ReadingModeTest, UnitsTest, UnitGeometryTest, ImageBudgetTest, PageSizesTest, PageWindowTest, webtoon/WebtoonLayoutTest, webtoon/TileGridTest, webtoon/TileManagerTest
app/src/androidTest/kotlin/com/proviewer/app/  ZoomTest, ReaderScreenTest, PageRendererTest, + TwoPageTest, WebtoonTest
docs/perf/budgets.md, docs/perf/phase3-reader.md (NEW), parent spec §8
```

---

### Task 1: Branch, and close the Phase 2 on-screen leftovers

**Files:**
- Modify: `app/src/androidTest/kotlin/com/proviewer/app/ZoomTest.kt`, `app/src/androidTest/kotlin/com/proviewer/app/ReaderScreenTest.kt`
- Modify if a test fails: `app/src/main/kotlin/com/proviewer/app/reader/ZoomablePage.kt`, `ReaderScreen.kt`

**Interfaces:** Consumes the Phase 2 reader as is. Produces two regression tests later tasks keep green.

- [ ] **Step 1: Preflight and branch.** Run the preflight from Global Constraints. `git fetch origin; git checkout master; git merge --ff-only origin/master` (expect `ff72a45`), `git checkout -b phase-3`. Ask the owner whether commits are authorized; if yes, commit the spec and this plan (`docs: phase 3 spec and plan`).
- [ ] **Step 2: Write test `fit_width_tall_page_starts_at_origin_and_pans_to_bottom` (ReaderScreenTest).** Build a CBZ in-test with one 1000×3000 JPEG (fit width 1080×3240, below the base caps, so `display == size`). Set `FitMode.Width`, wait Ready. `composeRule.onRoot().captureToImage()`; assert the 8×8 block at screen (0,0) equals the base bitmap's (0,0) block (copy the bitmap to `ARGB_8888` software first; per-channel tolerance 2). Pan via `ZoomAutomation.panBy(Offset(0f, -10_000f), animate = false)`; capture; assert the screen's bottom 8×8 block at x=0 equals the base bitmap's bottom-left block.
- [ ] **Step 3: Write test `fit_height_wide_page_starts_at_origin_and_pans_to_right` (ReaderScreenTest).** In-test CBZ with one 1600×1200 JPEG (fit height 3171×2378, not capped). Same assertions horizontally (left edge at x=0, after `panBy(Offset(-10_000f, 0f))` the right edge block matches).
- [ ] **Step 4: Write test `zoomed_pan_to_edge_in_one_drag_does_not_turn` (ZoomTest).** `screentone.cbz`, zoom 2× at the viewport centre (content overflows ~540 px each side), then **one** `performTouchInput { swipe(start = Offset(0.9f*w, h/2), end = Offset(0.05f*w, h/2), durationMillis = 400) }` (≈ 920 px, more than the 540 px of pan room). Wait for idle; assert `state.page == 0` and that the detail region now touches the page's right edge (`region.x + region.w == pageW`). The existing `pan_at_edge_turns_page` still proves a *new* drag turns.
- [ ] **Step 5: Run** `$env:ANDROID_SERIAL='emulator-5554'; ./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.proviewer.app.ReaderScreenTest,com.proviewer.app.ZoomTest"`. Record which of the three pass on the unchanged code.
- [ ] **Step 6: Fix any failing behaviour.** Overflow: the base must be placed at the alignment corner and `BaseContentLocation` must match it. One-drag turn: a drag whose gesture started while zoomed must never reach the pager in that same gesture (for example, disable `userScrollEnabled` on the pager from `DragInteraction.Start` while the current page's zoom > 1.001 until the pointer is up; mechanism is the builder's). Re-run Step 5 until all pass, plus the full ZoomTest/ReaderScreenTest classes.
- [ ] **Step 7: Commit** `fix(reader): verify fit overflow and pan-edge behaviour` (tests + any fix).

### Task 2: Engine — fit-aware prefetch and seam-exact scaled tiles

**Files:**
- Modify: `core/src/engine.rs`, `core/src/resize.rs`, `core/src/android/pixels.rs`, `app/src/main/kotlin/com/proviewer/app/engine/NativePixels.kt`, `PageRenderer.kt`, `reader/PageWindow.kt` (call site only), JVM test fakes
- Test: `core/src/resize.rs` (unit), `core/tests/engine_api.rs`, `app/src/androidTest/.../PageRendererTest.kt`

**Interfaces:**
- Produces (Rust, uniffi): `enum FitTarget { Screen, Width, Height }`; `Book::prefetch_hint(&self, current: u32, direction: ReadDirection, viewport_width: u32, viewport_height: u32, fit: FitTarget)`; `pub fn fit_size_for(page_w: u32, page_h: u32, view_w: u32, view_h: u32, fit: FitTarget) -> (u32, u32)` (Width: `(view_w, round(page_h*view_w/page_w))`, Height symmetric, Screen = `fit_size`; at least 1, no clamp to the view for Width/Height).
- Produces (Rust-only): `pub struct ScaledTileRequest { pub page: u32, pub full_width: u32, pub full_height: u32, pub out_x: u32, pub out_y: u32, pub out_width: u32, pub out_height: u32 }`; `Book::render_scaled_tile(&self, req: &ScaledTileRequest, dst: &mut [u8], dst_stride: usize, cancel: &CancelToken) -> Result<()>`; `resize::render_scaled_region(src: &DecodedImage, page_w: u32, page_h: u32, full_w: u32, full_h: u32, out_x: u32, out_y: u32, out_w: u32, out_h: u32, dst: &mut [u8], dst_stride: usize) -> Result<()>`; `pub const MAX_SCALED_FULL_DIM: u32 = 1 << 20`.
- Produces (Kotlin): `NativePixels.renderScaledToBitmap(book: Long, page: Int, fullW: Int, fullH: Int, x: Int, y: Int, w: Int, h: Int, bitmap: Bitmap, cancel: Long)` and `renderScaledToHardwareBuffer(..., buffer: HardwareBuffer, cancel: Long)`; `interface TileSource { suspend fun renderScaled(page: Int, full: IntSize, rect: IntRect): PageImage }` implemented by `PageRenderer`; `PageSourceRenderer.prefetchHint(current: Int, forward: Boolean, viewW: Int, viewH: Int, mode: FitMode)`; `PageSourceRenderer.renderRegion(page: Int, region: PageRegion): PageImage` replaces `renderBase` (Task 4 makes `PageWindow` render half/whole parts through it; here `PageWindow` calls it with the whole-page region).

- [ ] **Step 1: Write failing Rust tests.**
  - `resize.rs`: `scaled_bands_match_full_render` — for `noise_gray(80, 2400)` and `noise_rgb(80, 2400)` with full 108×3240: render the full image once with `render_scaled_region(.., out 0,0,108,3240)`, then as bands of 1000/1000/1000/240 rows; each band's rows must equal the matching rows of the full render **byte for byte** (both compute the same page↔output mapping). Also a 2-D variant at full 216×6480 with 100×1000 tiles.
  - `resize.rs`: `render_scaled_region_rejects_bad_requests` — out rect outside full, zero sizes, `full_w > MAX_SCALED_FULL_DIM` → `InvalidRequest`.
  - `engine_api.rs`: `scaled_whole_tile_equals_render_tile` — `render_scaled_tile` of the whole full size equals `render_tile` of the whole page at the same output size.
  - `engine_api.rs`: `prefetch_width_fit_warms_full_decode` — JPEG page 400×12000 (`gen_corpus::webtoon_strip` + encode), viewport 540×1189: `prefetch_hint(0, Forward, 540, 1189, FitTarget::Width)`, wait until `pending_prefetch_jobs() == 0`, then `render_tile` of the whole page at 540×16200; assert `page_decode_count(0) == 1`. Control `prefetch_screen_fit_does_not_warm_width`: same with `FitTarget::Screen` → count `== 2`.
- [ ] **Step 2: Run** `cargo test -p proviewer_core` — the new tests fail to compile/fail.
- [ ] **Step 3: Implement.** Refactor `render_region_with_budget` to take a float page-space crop (`left, top, width, height: f64`); `render_region` passes the integer `Rect`, `render_scaled_region` passes `out_x * page_w / full_w` etc. Denom for scaled tiles: `choose_denom(whole page rect, full_w, full_h, supported)`. Validate before decoding: full dims in `1..=MAX_SCALED_FULL_DIM`, out rect inside full, `out_w*out_h <= max_image_pixels`, stride/len as `validate_tile`. `prefetch_page` uses `fit_size_for`. Add the two JNI exports following `render_to_bitmap`/`render_to_hardware_buffer` (same format/size checks against `out_width × out_height`).
- [ ] **Step 4: Run** `cargo test --workspace` and `cargo clippy --workspace -- -D warnings` — PASS.
- [ ] **Step 5: Kotlin plumbing.** Add the externs; `PageRenderer.renderScaled` shares the S/H target + fallback + cancellation path of `render` (one private `renderInto(target size, nativeCall)`); `prefetchHint` maps `FitMode` → `FitTarget`; update `PageWindow` to pass its mode and call `renderRegion`; update JVM fakes. Instrumented `scaled_tiles_match_one_render` (PageRendererTest): `webtoon-long.cbz` at scale 0.1 (80×2400 strips), full size 216×6480: render two vertically adjacent 216×1000 tiles with `renderScaled` and one 216×2000 tile covering both; pixels equal, for both upload strategies.
- [ ] **Step 6: Run** `./gradlew :app:testDebugUnitTest` and the PageRendererTest class on the emulator — PASS.
- [ ] **Step 7: Commit** `feat(core): fit-aware prefetch and seam-exact scaled tiles`.

### Task 3: Pure reading-mode, units and unit geometry

**Files:**
- Create: `app/src/main/kotlin/com/proviewer/app/reader/ReadingMode.kt`, `Units.kt`, `UnitGeometry.kt`
- Modify: `reader/ReaderLogic.kt` (`visibleRegion` delegates to `visiblePartRegion`)
- Test: `app/src/test/kotlin/com/proviewer/app/reader/ReadingModeTest.kt`, `UnitsTest.kt`, `UnitGeometryTest.kt`

**Interfaces (Produces):**
```kotlin
enum class Layout { Single, TwoPage, Webtoon }
enum class LayoutChoice { Auto, Single, TwoPage, Webtoon }
enum class SplitChoice { Auto, On, Off }
const val DETECT_SAMPLE_PAGES = 20; const val STRIP_RATIO = 2.5f; const val STRIP_SHARE = 0.6f
fun detectStrip(sampled: List<IntSize?>): Boolean           // null = failed probe, ignored
fun resolveLayout(choice: LayoutChoice, isStrip: Boolean, viewW: Int, viewH: Int): Layout
fun isWide(size: IntSize): Boolean                           // width > height
fun shouldSplit(choice: SplitChoice, size: IntSize?): Boolean // null size -> false

enum class Part { Whole, LeftHalf, RightHalf }
data class UnitPart(val page: Int, val part: Part)
data class ReaderUnit(val parts: List<UnitPart>)              // reading order, 1..2 parts
fun buildUnits(count: Int, sizeOf: (Int) -> IntSize?, layout: Layout, split: SplitChoice, direction: Direction, shift: Boolean): List<ReaderUnit>
fun unitIndexOf(units: List<ReaderUnit>, page: Int): Int      // first unit containing page
fun srcRect(part: Part, size: IntSize): IntRect               // halves: [0, w/2) and [w/2, w)

data class PartPlacement(val part: UnitPart, val src: IntRect, val dst: IntRect, val base: IntSize)
data class UnitGeometry(val content: IntSize, val placements: List<PartPlacement>)
fun unitGeometry(unit: ReaderUnit, sizeOf: (Int) -> IntSize?, viewW: Int, viewH: Int, mode: FitMode, direction: Direction): UnitGeometry? // null if any size unknown
fun visiblePartRegion(src: IntRect, dst: IntRect, base: IntSize, zoom: Float, contentLeft: Float, contentTop: Float, viewW: Int, viewH: Int, baseCapped: Boolean): PageRegion?
fun windowTargets(units: List<ReaderUnit>, current: Int, ahead: Int, behind: Int, forward: Boolean, geometryOf: (ReaderUnit) -> UnitGeometry?): List<PartTarget>
```
(`PartTarget` is defined in Task 4's `PageWindow.kt`; write Task 3's `windowTargets` test against it by creating `data class PartTarget(val part: UnitPart, val src: IntRect, val base: IntSize, val display: IntSize)` in `Units.kt` and having Task 4 use it.)

Rules to encode: `buildUnits` — Webtoon returns one Whole unit per page (unused by the pager but keeps one code path). Single: Whole per page, or two half units when `shouldSplit` (LTR LeftHalf then RightHalf; RTL RightHalf then LeftHalf). TwoPage: page 0 alone unless `shift`; wide pages alone; otherwise consecutive portrait pages pair; after a wide page pairing restarts; a trailing unpaired page is alone; unknown sizes count as portrait. `unitGeometry` — Single: the part's src size fitted with `fitSize`. TwoPage: each part scaled to the common height `max(h0, h1)` (aspect kept), combined width = sum, combined size fitted with `fitSize(mode)`, placements left→right in LTR, right→left in RTL, each vertically centred; `base` = `baseSize`-style cap applied per part (`MAX_BASE_DIM`, `MAX_BASE_BYTES`).

- [ ] **Step 1: Write failing tests.**
  - ReadingModeTest: `detect_majority_tall_is_strip` (13 of 20 at 800×2400 → true; 11 of 20 → false), `detect_ignores_failed_probes` (10 tall + 10 null → true), `detect_all_failed_is_not_strip`, `resolve_auto_rules` (strip → Webtoon in any orientation; not strip + landscape → TwoPage; portrait → Single), `resolve_manual_wins`, `split_auto_only_wide`.
  - UnitsTest (sizes: portrait 1–4 = 1600×2400, 5 = 3200×2400, 6–8 portrait; indices 0-based in code): `two_page_pairing` → `[0][1,2][3][4][5,6][7]`; `two_page_shift` → `[0,1][2,3][4][5,6][7]`; `two_page_rtl_same_units`; `single_split_ltr_order` (wide page 4 → `(4,LeftHalf),(4,RightHalf)`); `single_split_rtl_order`; `split_off_keeps_whole`; `unknown_size_counts_as_portrait`; `unit_index_of_half_and_pair`; `window_targets_priority` (current unit's parts first, then ahead nearest first, then behind).
  - UnitGeometryTest: `two_page_ltr_places_lower_page_left`, `two_page_rtl_places_lower_page_right`, `two_page_mixed_heights_common_height_centred`, `split_half_src_rects` (w=3201 → `[0,1600)`, `[1600,3201)`), `visible_part_region_maps_into_src` (zoom 2 on a right half yields x ≥ 1600), `visible_region_unchanged_for_whole_page` (all existing `ReaderLogicTest` cases still pass).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest` — new tests FAIL.
- [ ] **Step 3: Implement** the three files; make `visibleRegion` call `visiblePartRegion(src = whole page, dst = (0,0,baseW,baseH), …)`.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` — PASS.
- [ ] **Step 5: Commit** `feat(reader): pure reading-mode, unit and geometry models`.

### Task 4: ImageBudget, PageSizes and a part-keyed PageWindow

**Files:**
- Create: `reader/ImageBudget.kt`, `reader/PageSizes.kt`
- Modify: `reader/PageWindow.kt`
- Test: `app/src/test/.../reader/ImageBudgetTest.kt`, `PageSizesTest.kt`, `PageWindowTest.kt` (rewrite)

**Interfaces (Produces):**
```kotlin
const val IMAGE_CAP_BYTES: Long = 192L * 1024 * 1024
class ImageBudget(val capBytes: Long = IMAGE_CAP_BYTES) {
    fun tryReserve(bytes: Long): Boolean; fun release(bytes: Long)
    val usedBytes: Long; val peakBytes: Long; fun resetPeak()
    fun wrap(image: PageImage, bytes: Long): PageImage   // release() releases image and bytes once
}
fun rgbaBytes(w: Int, h: Int): Long = w.toLong() * h * 4

class PageSizes(scope: CoroutineScope, renderer: PageSourceRenderer) {
    val version: Int                       // snapshot state; bumps when sizes land (batched, at most every 16 probes or when a prioritized page lands)
    fun known(page: Int): IntSize?; fun failed(page: Int): Boolean
    fun start(); fun prioritize(pages: List<Int>); suspend fun awaitFirst(n: Int); fun close()
}

class PageWindow(scope: CoroutineScope, renderer: PageSourceRenderer, budget: ImageBudget) {
    val slots: SnapshotStateMap<UnitPart, PageSlot>
    fun update(targets: List<PartTarget>, prefetch: PrefetchRequest)
    suspend fun awaitIdle(); fun close()
}
data class PrefetchRequest(val page: Int, val forward: Boolean, val viewW: Int, val viewH: Int, val mode: FitMode)
```
`PageWindow` keeps Phase 2 semantics (evict outside targets, cancel stale, re-render on size change, `prepareToDraw` off-current, Failed kept until evicted) but keys by `UnitPart` and renders `PageRegion(src…, base.w, base.h)`. Before each render it reserves `rgbaBytes(base)`; if the reservation fails the target and all lower-priority targets are skipped for this update (they retry on the next update). `PageSizes` probes prioritized pages first, then 0..count-1, on `Dispatchers.IO` through `renderer.probePageSize`, at most 2 in flight.

- [ ] **Step 1: Write failing tests.** ImageBudgetTest: `reserve_until_cap`, `release_restores`, `peak_tracks_max`, `wrap_releases_once`, `concurrent_reserve_never_exceeds_cap` (8 threads × 10 000 random reserve/release). PageSizesTest (fake renderer, `StandardTestDispatcher`): `prioritized_pages_probe_first`, `await_first_n`, `failed_probe_recorded`, `version_bumps_batched`. PageWindowTest: port every Phase 2 case to part keys, plus `half_parts_render_their_src_region`, `budget_exhaustion_skips_low_priority`, `released_images_return_bytes` (budget `usedBytes == 0` after `close()`).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest` — FAIL.
- [ ] **Step 3: Implement.** Keep the VM compiling by temporarily driving `PageWindow` with Single/Whole targets built from `windowTargets` (full integration is Task 5).
- [ ] **Step 4: Run** unit tests and the existing ReaderScreenTest on the emulator — PASS (Single behaviour unchanged).
- [ ] **Step 5: Commit** `feat(reader): image byte cap, page-size prober, part-keyed window`.

### Task 5: Pager over units — ZoomableUnit for Single (split) and Two-page

**Files:**
- Rename/modify: `reader/ZoomablePage.kt` → `reader/ZoomableUnit.kt`
- Modify: `reader/ReaderViewModel.kt`, `reader/ReaderScreen.kt`, `reader/ReaderProbe.kt`, `tools/gen-corpus/src/lib.rs`, `tools/gen-corpus/Cargo.toml` (version `0.2.0`), `corpus/README.md`
- Test: `app/src/androidTest/.../TwoPageTest.kt` (new), ZoomTest/ReaderScreenTest updates

**Interfaces:**
- Consumes: Task 3 models, Task 4 `PageWindow`/`ImageBudget`/`PageSizes`.
- Produces: `ReaderState` gains `layoutChoice: LayoutChoice = Auto`, `split: SplitChoice = Auto`, `shift: Boolean = false`, `layout: Layout = Single`, `unit: Int = 0` (`page` stays the book page). VM: `val units: List<ReaderUnit>` (snapshot state), `fun setLayoutChoice(c: LayoutChoice)`, `fun setSplit(c: SplitChoice)`, `fun setShift(on: Boolean)`, `fun onUnitSettled(unit: Int, fromUser: Boolean)`; `goTo(page)` maps to `unitIndexOf`; `next()/previous()` move by one unit; `val budget: ImageBudget`, `val sizes: PageSizes`. `@Composable fun ZoomableUnit(unitIndex: Int, geometry: UnitGeometry, slots: Map<UnitPart, PageSlot>, renderer: PageRenderer, budget: ImageBudget, probe: ReaderProbe, isCurrent: Boolean, mode: FitMode, onTap: (Offset) -> Unit, onZoom: (ZoomAutomation?) -> Unit)`. `ReaderProbe.onSharpPageDrawn(page, drawNanos)` now fires once per (part bitmap) **only while `isCurrent`** (E5). Corpus `two-page.cbz`: portrait 1–4 (1600×2400), spread 5 (3200×2400), portrait 6–8 (scaled by `scale`).

- [ ] **Step 1: Corpus.** Add `two-page.cbz` to `ALL_CORPUS_NAMES`/`archive_entries` (JPEG, `simple_page`), bump gen-corpus to `0.2.0` (the on-device stamp regenerates), update `corpus/README.md` and gen-corpus's own count/name tests. `cargo test -p gen-corpus` PASS.
- [ ] **Step 2: Write failing instrumented tests (TwoPageTest).** Layout set programmatically (Task 6 adds the controls): `two_page_units_follow_pairing` (`two-page.cbz` scale 0.25, `setLayoutChoice(TwoPage)`: `vm.units` = `[0][1,2][3][4][5,6][7]`; next() walks units; label page = first page of unit), `two_page_shift`, `two_page_rtl_draws_lower_page_right` (capture: the lower page's base corner block appears in the right half), `single_split_halves_in_order` (`double-spread.cbz` scale 0.25, Split On: page 1 shows LeftHalf then RightHalf in LTR, reversed in RTL; base bitmap sizes = half fit), `zoom_two_page_renders_detail_per_visible_part` (zoom 2× at the unit centre → one detail per intersecting part, both via `ReaderProbe.onDetailImage`), `sharp_probe_only_for_current_unit` (after idle on unit 0 no event for unit 1's pages; after snap `turnTo(1)` the event arrives).
- [ ] **Step 3: Run** the TwoPageTest class on the emulator — FAIL.
- [ ] **Step 4: Implement.** VM: units recomputed (keeping `page`) whenever sizes version, layout, split, shift or direction changes; window targets from `windowTargets`; `sizes.prioritize` with the window's pages; `PrefetchRequest` with the current fit mode. Screen: `HorizontalPager(pageCount = units.size)` with `ZoomableUnit`; tap/drag routing and the Task 1 one-drag rule kept. `ZoomableUnit` draws each placement's base at `dst` and runs the Phase 2 settle/detail pipeline per placement through `visiblePartRegion`, reserving detail bytes in `budget` (no detail if the reservation fails).
- [ ] **Step 5: Run** TwoPageTest, ZoomTest, ReaderScreenTest on the emulator and `:app:testDebugUnitTest` — PASS.
- [ ] **Step 6: Commit** `feat(reader): two-page units and spread splitting in the pager`.

### Task 6: Mode selection — auto-detect, overlay controls, rotation

**Files:**
- Modify: `reader/ReaderViewModel.kt`, `reader/ReaderScreen.kt`
- Test: TwoPageTest, ReaderScreenTest, ZoomTest, `BenchmarkRunTest` pins

**Interfaces:** Produces `ReaderState.detecting: Boolean` (true until the first `min(DETECT_SAMPLE_PAGES, count)` probes finish; no window update before that), `val isStrip: Boolean?` on the VM. Overlay tags: `reader-layout` (cycles Auto→Single→TwoPage→Webtoon→Auto), `reader-split` (Auto→On→Off, Single only), `reader-shift` (TwoPage only), `reader-fit` hidden in Webtoon. Label: Single "n / total", pair "n–m / total".

- [ ] **Step 1: Write failing tests.** `auto_portrait_opens_single_and_splits_wide` (`double-spread.cbz`), `auto_landscape_opens_two_page` (set `requestedOrientation = LANDSCAPE` before open), `auto_rotation_switches_and_keeps_page` (portrait Single on page 3 → landscape → TwoPage unit containing page 3 → back), `manual_layout_wins_over_rotation`, `overlay_controls_cycle` (click tags, assert state), `auto_strip_opens_webtoon_layout` (`webtoon-long.cbz` scale 0.1 → `state.layout == Webtoon`; the Webtoon UI arrives in Task 10, so this asserts state only).
- [ ] **Step 2: Pin existing tests:** tests on `huge-single.cbz` call `vm.setSplit(SplitChoice.Off)`; `tall_page_fit_width_displays` and others on `webtoon-long.cbz` call `vm.setLayoutChoice(LayoutChoice.Single)` before asserting.
- [ ] **Step 3: Run** — new tests FAIL.
- [ ] **Step 4: Implement** detection in `attach` (`sizes.start()`, `awaitFirst`, `detectStrip`, `resolveLayout`), re-resolution in `onViewport` when `layoutChoice == Auto`, and the overlay controls. Log the detection time (`Log.i`); on the emulator with `many-pages.cbz` it must not visibly delay the first page — if it exceeds ~300 ms, lower `DETECT_SAMPLE_PAGES` and record why.
- [ ] **Step 5: Run** all instrumented classes touched + unit tests — PASS.
- [ ] **Step 6: Commit** `feat(reader): auto mode selection with overlay override`.

### Task 7: Pure Webtoon layout

**Files:** Create `reader/webtoon/WebtoonLayout.kt`; Test `app/src/test/.../reader/webtoon/WebtoonLayoutTest.kt`

**Interfaces (Produces):**
```kotlin
data class Anchor(val page: Int, val offsetPx: Double)        // px below the page's top at 1x
class WebtoonLayout private constructor(...) {
    companion object { fun build(count: Int, viewW: Int, viewH: Int, sizeOf: (Int) -> IntSize?, failed: (Int) -> Boolean): WebtoonLayout }
    val count: Int; val viewW: Int; val viewH: Int; val totalHeight: Double
    fun top(page: Int): Double; fun height(page: Int): Int; fun isEstimated(page: Int): Boolean
    fun pageAt(y: Double): Int; fun anchorAt(y: Double): Anchor; fun yOf(anchor: Anchor): Double
    fun visiblePages(y0: Double, y1: Double): IntRange
    fun maxScroll(zoom: Float): Double                       // totalHeight - viewH/zoom, >= 0
}
fun rescaleAnchor(a: Anchor, oldViewW: Int, newViewW: Int): Anchor
const val ERROR_PAGE_HEIGHT_FRACTION = 0.5f                   // failed page height = viewH * 0.5
```
Heights: known `round(h * viewW / w)`; unknown = median of known heights, else `viewH`; failed = `viewH * ERROR_PAGE_HEIGHT_FRACTION`. Positions are `Double` (500 pages × 30 000 px exceeds Float precision).

- [ ] **Step 1: Write failing tests:** `tops_are_cumulative_no_gap`, `unknown_uses_median_then_viewport`, `failed_page_fixed_height`, `page_at_boundaries` (y exactly at a top belongs to that page), `visible_pages_range`, `correction_above_anchor_keeps_content_fixed` (build with page 0 estimated, anchor on page 3 at offset 120; rebuild with page 0 known taller; `yOf(anchor)` moved by exactly the height delta, i.e. the screen content is unchanged when scrollY is reset to `yOf(anchor)`), `rescale_anchor_on_rotation`, `long_book_precision` (500 × 30 000 px pages, `pageAt(top(499)+1) == 499`).
- [ ] **Step 2: Run** unit tests — FAIL. **Step 3: Implement** (prefix sums). **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(webtoon): pure stacked layout with anchors`.

### Task 8: Pure tile grid and wanted-tile selection

**Files:** Create `reader/webtoon/TileGrid.kt`; Test `.../webtoon/TileGridTest.kt`

**Interfaces (Produces):**
```kotlin
const val TILE_EDGE = 1024                                  // builder may tune; <= GPU max texture size
const val PREFETCH_AHEAD_VIEWPORTS = 1.0; const val PREFETCH_BEHIND_VIEWPORTS = 0.5
data class TileKey(val page: Int, val level: Int, val col: Int, val row: Int)   // level = zoom*1000
data class TileSpec(val key: TileKey, val full: IntSize, val rect: IntRect)     // rect in full-size page px
data class WebtoonView(val scrollY: Double, val zoom: Float, val panX: Float)  // scrollY: 1x content px at viewport top; panX: screen px
fun zoomLevel(zoom: Float): Int
fun fullSize(layout: WebtoonLayout, page: Int, level: Int): IntSize          // round(viewW*level/1000) x round(height(page)*level/1000)
fun pageTiles(page: Int, full: IntSize, level: Int): List<TileSpec>          // level 1000: one column of full width, TILE_EDGE rows; else TILE_EDGE grid; edges clamp
data class Wanted(val ordered: List<TileSpec>, val visible: Set<TileKey>)
fun wantedTiles(layout: WebtoonLayout, view: WebtoonView, settledLevel: Int, forward: Boolean): Wanted
```
Screen mapping: screen y = `(Y − scrollY) × zoom`, screen x = `X × zoom − panX`. `wantedTiles`: visible tiles at `settledLevel` (top→bottom, left→right), then tiles at `settledLevel` within the ahead margin (direction-aware), then behind margin; when `settledLevel != 1000`, the 1× tiles of the visible area are appended (fallback). Pages that are estimated or failed produce no tiles. Zoomed margins are horizontal-clipped to the visible columns.

- [ ] **Step 1: Write failing tests:** `level_1000_single_column`, `tiles_cover_page_exactly_no_overlap` (union of rects == full, pairwise disjoint), `visible_first_then_ahead_then_behind`, `direction_flips_margins`, `zoomed_visible_tiles_follow_pan`, `zoomed_appends_1x_fallback`, `estimated_and_failed_pages_have_no_tiles`.
- [ ] **Step 2–4:** run (FAIL) → implement → run (PASS).
- [ ] **Step 5: Commit** `feat(webtoon): tile grid and wanted-tile selection`.

### Task 9: TileManager under the byte cap

**Files:** Create `reader/webtoon/TileManager.kt`; Test `.../webtoon/TileManagerTest.kt`

**Interfaces:**
- Consumes: `TileSource` (Task 2), `ImageBudget` (Task 4), `Wanted`/`TileSpec` (Task 8).
- Produces:
```kotlin
sealed interface TileSlot { object Loading : TileSlot; data class Ready(val image: PageImage, val spec: TileSpec) : TileSlot; data class Failed(val reason: String) : TileSlot }
class TileManager(scope: CoroutineScope, source: TileSource, budget: ImageBudget) {
    val tiles: SnapshotStateMap<TileKey, TileSlot>
    val failedPages: SnapshotStateMap<Int, String>          // a page whose tile failed is drawn as an error placeholder
    fun update(wanted: Wanted)                              // main thread
    suspend fun awaitIdle(); fun close()
}
```
Behaviour: start renders for wanted tiles in order; cancel in-flight renders not in `wanted`; keep Ready tiles not wanted until space is needed. Before a render: `tryReserve(rgbaBytes(rect))`; on failure evict, in order, Ready tiles not wanted (farthest page distance from the first visible tile first), then not-visible tiles of other levels; never a visible tile; if still failing, skip this and lower-priority tiles until next update. A failed render marks `failedPages[page]` with `detail`.

- [ ] **Step 1: Write failing tests (fake source with controllable completion, `StandardTestDispatcher`):** `renders_in_wanted_order`, `cancels_tiles_leaving_wanted`, `rapid_view_changes_cancel_stale_tiles` (100 updates scrolling down without completing renders → at most `limitedParallelism` renders started beyond the final wanted set, no leaked bytes), `evicts_unwanted_before_other_levels_never_visible`, `random_sequence_never_exceeds_cap` (cap 64 tiles' worth, 2 000 random scroll/zoom/complete steps; assert `budget.usedBytes <= capBytes` after every step and `usedBytes == 0` after `close()`), `failed_tile_marks_page`.
- [ ] **Step 2–4:** run (FAIL) → implement → run (PASS).
- [ ] **Step 5: Commit** `feat(webtoon): tile manager with eviction under the cap`.

### Task 10: WebtoonViewport — scrolling at 1×

**Files:**
- Create: `reader/webtoon/WebtoonViewport.kt`
- Modify: `reader/ReaderViewModel.kt`, `reader/ReaderScreen.kt`, `reader/ReaderLogic.kt`, `reader/ReaderProbe.kt`
- Test: `app/src/androidTest/.../WebtoonTest.kt` (new)

**Interfaces:**
- Produces: `fun verticalTapAction(y: Float, height: Float): TapAction` (top third Previous, middle ToggleOverlay, bottom Next) in ReaderLogic; `const val WEBTOON_PAGE_SCROLL = 0.85f`. VM: `val webtoon: WebtoonSession?` (owns `WebtoonLayout` snapshot, `TileManager`; created when `layout == Webtoon` with its initial `scrollY = layout.top(state.page)` (so switching layout keeps the book page), closed — releasing all tiles — when leaving it; the pager's `PageWindow` is closed while in Webtoon and vice versa); `sealed interface WebtoonCommand { data class Page(val delta: Int) : WebtoonCommand; data class ToPage(val page: Int) : WebtoonCommand }` delivered through `val webtoonCommands: SharedFlow<WebtoonCommand>`; in Webtoon `next()/previous()` emit `Page(±1)`, `goTo(p)` emits `ToPage(p)`; `fun onWebtoonTopPage(page: Int, forward: Boolean)` updates `state.page`, `sizes.prioritize` and `prefetchHint(page, forward, viewW, viewH, FitMode.Width)`. `ReaderProbe.onPlaceholderDrawn()` once per frame that drew a placeholder in the visible area. `interface WebtoonAutomation { val scrollY: Double; val maxScroll: Double; suspend fun scrollBy(dy: Float); suspend fun fling(velocityY: Float); suspend fun zoomTo(factor: Float, focal: Offset, animate: Boolean) }` exposed as `ReaderAutomation.webtoon: WebtoonAutomation?` (`zoomTo` lands in Task 11).
- Viewport: holds `scrollY` (Double), `zoom`, `panX` in snapshot state; draws inside `Modifier.drawBehind`/`Canvas` only; a `snapshotFlow` over the view computes `wantedTiles` and calls `tileManager.update` only when the wanted set changes; drag scrolls 1:1 (÷ zoom); fling uses `rememberSplineBasedDecay()`; scroll clamps to `[0, maxScroll(zoom)]`; layout rebuilds (sizes version, viewport) keep the anchor; rotation resets zoom to 1× and applies `rescaleAnchor`. Draw order per visible page: settled-level tiles, 1× tiles, dark placeholder (`0xFF111111`); failed page: placeholder with "Page n" + reason. Test semantics: tag `webtoon-viewport`, `SemanticsPropertyKey`s for `scrollY` and `topPage` (names builder's).

- [ ] **Step 1: Write failing tests (WebtoonTest; `webtoon-long.cbz` at scale 0.25 unless noted):**
  - `opens_in_webtoon_and_draws_first_tiles` (auto → viewport shown, first visible tile Ready, `onSharpPageDrawn` not required).
  - `scroll_to_bottom_and_back_under_cap` (scroll by automation to `maxScroll` in 30 steps and back; `vm.budget.peakBytes <= IMAGE_CAP_BYTES`; `usedBytes` returns to the working-set size).
  - `tile_and_page_boundaries_match_reference` (capture the screen across a tile boundary and across a page boundary; compare rows with a `renderScaled` reference of the same full size; per-channel tolerance 2, no gap row).
  - `tap_zones_and_volume_scroll_85_percent` (bottom-third tap and volume-down each add `0.85 × viewH` ± 1 px; top third subtracts; middle toggles overlay).
  - `slider_far_jump_lands_on_page_top` (`many-pages.cbz`, `setLayoutChoice(Webtoon)`, `goTo(400)` → once idle, the viewport's top page is 400 and `scrollY == layout.top(400)` within 1 px; render count observed through a counting `TileSource` wrapper is bounded by the final wanted set + in-flight).
  - `rotation_keeps_anchor_page` (zoom 2×, scroll into page 1, rotate → zoom 1×, top page 1, relative offset within 2 %).
  - `corrupt_page_shows_placeholder_and_scroll_continues` (in-test CBZ: strip, corrupt JPEG, strip).
- [ ] **Step 2: Run** WebtoonTest — FAIL.
- [ ] **Step 3: Implement** the session, viewport, commands and probe.
- [ ] **Step 4: Run** WebtoonTest + every other instrumented class + unit tests — PASS.
- [ ] **Step 5: Commit** `feat(webtoon): self-drawn tiled scrolling viewport`.

### Task 11: Webtoon zoom and sharp re-render

**Files:** Modify `reader/webtoon/WebtoonViewport.kt`, `WebtoonSession` (in VM file or its own file); Test `WebtoonTest.kt`

**Interfaces:** Produces `const val WEBTOON_MAX_ZOOM = 3f`, `const val WEBTOON_SETTLE_MS = 150L`; `WebtoonAutomation.zoomTo` implemented; `ReaderProbe.onDetailRender(page, event)` reused with events `"tiles-start"`, `"tiles-done"`, `"tiles-cancelled"` for the settled level.

Behaviour: pinch zooms about the focal point within `[1, 3]` (scrollY and panX adjusted so the focal content point stays under the fingers); a pinch never scrolls the list by itself; double-tap toggles 2× ↔ 1× around the tap; horizontal drag pans within `[0, (zoom − 1) × viewW]`; while the transform changes, `settledLevel` stays the previous level (tiles drawn scaled); after `WEBTOON_SETTLE_MS` of no change, `settledLevel = zoomLevel(zoom)` (1000 at 1×) and `wantedTiles` requests that level's visible tiles; a new gesture before completion cancels them (TileManager `update`).

- [ ] **Step 1: Write failing tests:** `double_tap_zooms_and_rerenders_visible_tiles_only` (after settle, every Ready tile at level 2000 intersects the visible area ± margin; first level-2000 tile drawn), `new_gesture_cancels_pending_level`, `pan_stays_in_bounds` (`panBy` −1e5 / +1e5 clamps to `[0, viewW]` at 2×), `pinch_keeps_focal_point` (automation `zoomTo(2f, focal)` → the content point under `focal` before is under `focal` after, ± 1 px), `zoomed_scroll_under_cap` (2× scroll through the book; peak ≤ cap).
- [ ] **Step 2–4:** run (FAIL) → implement → run WebtoonTest + ZoomTest (PASS).
- [ ] **Step 5: Commit** `feat(webtoon): whole-column zoom with sharp tile re-render`.

### Task 12: Benchmark — a1 scenario, b prefetched fix, schema 2

**Files:** Modify `app/src/bench/kotlin/com/proviewer/app/bench/Scenarios.kt`, `BenchmarkRunner.kt`, `BenchmarkJson.kt`, `BenchmarkScreen.kt`; Test `app/src/test/.../bench/BenchmarkJsonTest.kt`, `app/src/androidTest/.../BenchmarkRunTest.kt`

**Interfaces:** `BENCH_CORPUS_FILES += "webtoon-long.cbz"`; `BENCH_SCENARIO_IDS = listOf("a1", "a2", "a3-screentone", "a3-huge", "b")`; `ScenarioOutcome` gains `placeholderFrames: Int?`, `peakImageBytes: Long?`; JSON `schemaVersion: 2`, per-scenario `placeholderFrames`, `peakImageBytes`; b prefetched samples carry no fallback flag.

- [ ] **Step 1: Write failing tests.** BenchmarkJsonTest: `schema_2_has_a1_and_info_fields`, `b_prefetched_has_no_fallback_flag`. BenchmarkRunTest (emulator, existing end-to-end test): asserts the a1 rows exist for both strategies and the JSON parses.
- [ ] **Step 2: Implement a1** (`webtoon-long.cbz` at scale 1, auto Webtoon): `budget.resetPeak()`, start recorder, scroll down with `WebtoonAutomation.scrollBy` once per frame (`withFrameNanos`) at **4 000 px/s** until `maxScroll`, then **4 flings at −8 000 px/s** separated by settles; stop; report `jankSummary`, `placeholderFrames` (count of `onPlaceholderDrawn` during the window), `peakImageBytes`. Total run must stay ≤ 4 min (check the emulator run's wall time and print it).
- [ ] **Step 3: Fix b prefetched:** `turnTo(p + 1, animate = false)`; t1 = `probe.awaitSharpDraw(p + 1, t0, ACTION_TIMEOUT_MS)` (now strict: the current-only probe from Task 5 guarantees a post-turn event) → `frameEndContaining`; a missing event is a failure like cold; delete the settle fallback and its flag. a3-huge pins `vm.setSplit(SplitChoice.Off)`.
- [ ] **Step 4: Run** `:app:testDebugUnitTest` and BenchmarkRunTest on the emulator — PASS; the on-screen table shows a1.
- [ ] **Step 5: Commit** `feat(bench): a1 webtoon scroll and strict b prefetched`.

### Task 13: Docs and exit verification

**Files:** Modify `docs/perf/budgets.md`, `docs/superpowers/specs/2026-11-18-proviewer-design.md` (§8 Phase 3 row); Create `docs/perf/phase3-reader.md`

- [ ] **Step 1: budgets.md:** a1 "How measured" → Benchmark screen scenario a1 (Task 12 script; `perf-capture.ps1` reference only); add the enforced design cap `IMAGE_CAP_BYTES = 192 MiB` (E3; not a measured budget; peak reported for information); b note: prefetched measured with snapped turns (E5); Phase 2 vivo 60 Hz caveat kept.
- [ ] **Step 2: Parent spec §8 Phase 3 row:** "memory within budget" → "tile/bitmap byte cap enforced and tested (E3)".
- [ ] **Step 3: Exit gates** (record command, exit code and result in `phase3-reader.md`, same table format as `phase2-reader.md`): `cargo test --workspace`; `./gradlew :app:testDebugUnitTest`; `$env:ANDROID_SERIAL='emulator-5554'; ./gradlew :app:connectedDebugAndroidTest`; `./gradlew :app:assembleRelease :app:assembleBenchmark`; `scripts/check-16kb.ps1` on both APKs; `scripts/check-release-clean.ps1` (0 on release, 1 on benchmark by design); `scripts/fuzz.ps1 -Target decode` and `-Target archive`, 900 s each, on the final code (engine/resize changed).
- [ ] **Step 4: phase3-reader.md** also records: the a1 scenario description, the cap value, the E5 change to b, the two Phase 2 leftovers' outcome (Task 1), known limitations (short-image Webtoon chapters detect as Single; emulator numbers invalid), and the owner hand-off: benchmark APK path `E:\Code\ProViewer\app\build\outputs\apk\benchmark\app-benchmark.apk`; the owner installs it and reads a Webtoon and a manga of their choosing; no plan step touches the phone.
- [ ] **Step 5: Update memory/handoff notes only if asked;** commit `docs: phase 3 record, budgets and exit status`.
