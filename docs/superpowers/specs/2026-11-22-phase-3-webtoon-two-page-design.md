# ProViewer Phase 3: Webtoon, two-page and spread splitting design

Date: 2026-11-22 · Status: **approved by the owner on 2026-11-22**
Parent spec: `docs/superpowers/specs/2026-11-18-proviewer-design.md` (§5, §7, §8 Phase 3). Previous phase:
`docs/superpowers/specs/2026-11-19-phase-2-reader-mvp-design.md`, record `docs/perf/phase2-reader.md`. Budgets:
`docs/perf/budgets.md`.

## 1. Intent and why

Phase 2 reads one page at a time. Phase 3 makes the reader handle the two other common kinds of comic: **Webtoon**
(long vertical strips read by continuous scrolling) and **manga with double-page spreads** (shown as two pages side by
side, or a spread cut into two single pages). The bar is the product's bar: a long Webtoon scrolls with no dropped
frames at the locked refresh rate, with image quality equal to the paged reader (linear-light resize at the exact
on-screen size) and a bounded memory footprint. Because nothing is persisted until Phase 4, the reader picks a sensible
mode on its own when a book opens, so the owner does not have to set it on every open.

## 2. Decisions taken with the owner (2026-11-22)

| # | Decision | Owner's choice |
|---|---|---|
| E1 | How the reading mode is chosen | **Auto-detect on open, with manual override** in the overlay (§5.1) |
| E2 | Zoom in Webtoon mode | **Zoom the whole column** (pinch / double-tap), pan horizontally while zoomed, sharp re-render of visible tiles after settling |
| E3 | Memory criterion (budget row c was dropped on 2026-11-19) | **Hard cap in code**: the app enforces a byte cap on the bitmaps/tiles it holds; an emulator test proves it is never exceeded; the Benchmark screen records the peak for information only |
| E4 | Webtoon architecture | **Approach 2** (§4): one self-drawn viewport with its own scroll/zoom transform and a Kotlin tile manager |
| E5 | What b prefetched measures (Phase 2 caveat) | **Snapped (non-animated) prefetched turns**; t1 = first frame drawing the target sharp as the current page (§6.6) |

Everything marked *(designer's call)* below was proposed by the designer and not contested; the owner may still change it.

## 3. Goals, non-goals, anti-goals

**Goals**
- Webtoon mode: continuous vertical scroll through all pages of a book, no gaps, each page fitted to the viewport width,
  pages taller than the GPU texture limit drawn as tiles. Fling, tap-zone and volume-key scrolling. Whole-column zoom.
- Two-page mode: two pages side by side as one unit, in reading order, with cover-alone pairing and a manual shift.
- Spread splitting in single-page mode: a wide page is shown as two consecutive virtual pages, in reading order.
- Auto mode selection on open, recomputed on rotation while in Auto; manual override in the overlay.
- A Benchmark scenario for **a1** (Webtoon scroll) in the in-app Benchmark screen, replacing the adb method.
- A tile/bitmap byte cap, enforced and tested.
- Close the Phase 2 leftovers (§6.7).

**Non-goals**
- Persisting mode, position or settings (Phase 4). Library, image folders (Phase 4).
- Replacing Phase 2's "one fit bitmap + one detail bitmap" in Single/Two-page with tiles. The tile manager is built so
  it *could* serve them later, but Single/Two-page keep approach A in this phase.
- Horizontal (LTR/RTL) continuous scroll mode; gaps/margins between Webtoon pages; auto-crop of borders; page-turn
  animations other than the pager slide; Material You polish, tablet/foldable layouts (Phase 5).

**Anti-goals (failure even if it works)**
- Any benchmark or long job running on a real device without the owner starting it from the Benchmark screen. No
  script, test or plan step targets the owner's phone (serial `3B65BT02H6G00000`); every device command pins
  `ANDROID_SERIAL=emulator-5554`.
- Recomposition on every scroll or zoom frame in Webtoon mode (state must be read in the draw phase).
- Visible seams, gaps or one-pixel jumps between adjacent tiles or adjacent pages.
- A blank (never-drawn) area during a normal-speed scroll when the tile for it was within the prefetch distance; a
  placeholder is acceptable only for a cold page or a fling faster than rendering can follow.
- The held tile/bitmap bytes exceeding the cap at any moment, or an `OutOfMemoryError` / native OOM on `webtoon-long.cbz`.
- Decoding or resizing on the main thread. Benchmark code in the release APK.

## 4. Approaches considered (E4)

| | Approach | Trade-offs |
|---|---|---|
| 1 | `LazyColumn` of tile bands, whole list scaled with `graphicsLayer` for zoom | Compose handles item recycling, scroll, fling, semantics. But the list does not know it is scaled: at 3× drags must be divided by the zoom, horizontal pan is extra, the list composes more items than are visible, and pinch vs list scroll gesture conflicts are hard to make robust |
| **2 (chosen)** | **One self-drawn viewport**: a single composable owns `scrollY`, `zoom`, `panX`; the book layout (pages stacked, heights from `page_info`) is a pure model; each frame draws only the visible tiles on a Canvas in the draw phase; fling via Compose decay animation; a Kotlin **tile manager** renders tiles through `render_tile`, prefetches in the scroll direction and enforces the byte cap | Zoom and scroll are one transform, matching E2; no per-frame recomposition; the tile set is deterministic, so the cap is exactly testable and the benchmark easy to instrument. More code: own fling, test semantics, scroll/zoom gesture arbitration |
| 3 | Telephoto zoomable image per item in a `LazyColumn` | Least code, but zoom is per image, not the whole column; contradicts E2. Rejected |

Two-page mode has one sensible approach: the existing `HorizontalPager`, where each pager page is a *spread unit*
(one or two book pages). Spread splitting likewise maps onto the pager as *virtual pages* (§6.5).

## 5. Reader behaviour

### 5.1 Mode selection (E1)

- Layouts: **Single**, **Two-page**, **Webtoon**. Split setting: **Auto / On / Off** (applies to Single only).
- On open, the reader reads `page_info` for the first **min(20, count)** pages *(designer's call)*, off the main thread
  (header-only reads; already memoized by the engine). The reader shows the Single layout's loading state until
  detection finishes; detection must not visibly delay the first page *(builder measures on the emulator; if it does,
  the builder may sample fewer pages)*.
- **Auto rules** *(designer's call; thresholds are constants in pure, unit-tested code)*:
  1. If ≥ 60 % of the sampled pages that probed successfully have `height / width ≥ 2.5` → **Webtoon**.
  2. Else if the viewport is landscape (`viewW > viewH`) → **Two-page**.
  3. Else → **Single**, with split Auto meaning: a page with `width > height` is split.
- Known limitation: a Webtoon chapter that was cut into short images (h/w < 2.5) is detected as Single; the owner
  switches by hand. This is accepted for Phase 3.
- The overlay gains a **Layout** control (Auto / Single / Two-page / Webtoon) and a **Split** control (Auto / On / Off,
  shown in Single). A manual choice wins over detection until the book is closed. While Layout is Auto, a rotation
  re-runs rule 2/3 (rule 1's result is kept, it does not depend on orientation).
- Switching layout keeps the reader on the same **book page**: Webtoon scrolls so that page's top is at the top of
  the viewport; Two-page shows the unit containing it; split Single shows its first virtual half.
- As in Phase 2, mode and position live in the ViewModel (survive rotation), are not persisted, and process death may
  return to Home or the reader's restore path from `4722c2b`, unchanged.

### 5.2 Webtoon mode (E2)

```
┌────── Webtoon (overlay hidden) ──────┐
│  top third: scroll up ~85 %          │
│ ──────────────────────────────────── │
│  middle third: toggle overlay        │   pages stacked with no gap, each at viewport width;
│ ──────────────────────────────────── │   drag = scroll, fling = decay; pinch / double-tap = zoom
│  bottom third: scroll down ~85 %     │   whole column (max 3×), drag pans horizontally while zoomed
└──────────────────────────────────────┘
```

- **Layout:** every page is scaled to the viewport width (`fit width`) and stacked top to bottom with no gap. Fit-mode
  controls are hidden in Webtoon. Page heights come from `page_info`; a page whose size is not known yet uses an
  estimated height (the median of known pages, else the viewport height) and the layout is corrected when it becomes
  known, **without moving the content the user is looking at** (anchor: the page and offset at the viewport top).
  A page that fails to probe or decode gets a fixed-height error placeholder (page number + `detail` reason) and
  scrolling continues past it.
- **Scrolling:** drag scrolls 1:1 (divided by the zoom when zoomed so content follows the finger); fling uses Compose's
  default decay. Tap top third / volume up = scroll up by 85 % of the viewport height, tap bottom third / volume down =
  scroll down 85 %, animated *(designer's call; tap zones are vertical thirds in Webtoon, horizontal thirds elsewhere)*;
  tap middle third toggles the overlay. Scrolling stops at the top of the first and the bottom of the last page.
- **Zoom:** pinch zooms the whole column around the focal point, range 1×–3× *(designer's call)*. Double-tap zooms to 2×
  around the tap, double-tap again returns to 1×. While zoomed, horizontal drags pan within the zoomed column width;
  vertical scroll works as at 1×. During a gesture or fling, already-rendered tiles are scaled in the draw phase; when
  the transform has been still for a settle delay (Phase 2 uses 150 ms), visible tiles are re-rendered at the new scale
  (§6.3). At 1× no re-render happens.
- **Overlay:** title/back as now; the slider jumps to the top of page *n*, and the label shows the page at the viewport
  top: "n / total".
- **Current page** for the overlay, the prefetch hint and mode switches = the page under the viewport's top edge
  *(designer's call)*.

### 5.3 Two-page mode

- Two book pages side by side as one **unit**, fitted as a unit with the existing fit modes (Screen / Width / Height
  apply to the unit's combined size). LTR: lower page on the left; RTL: lower page on the right. Pages of different
  heights are each scaled to the unit's common height *(designer's call)* and centred vertically.
- **Pairing** *(designer's call)*: page 1 is alone (cover). From page 2 on, pages pair 2–3, 4–5, … A page with
  `width > height` (already a spread) is always a unit by itself, and pairing restarts after it (the next portrait page
  starts a new pair). The overlay's **Shift** toggle changes **only the opening**: page 1 pairs with page 2 instead of
  standing alone; restarts after spreads are unaffected *(designer's call)*. Example, portrait 1–4, spread 5, portrait
  6–8: normal [1][2-3][4][5][6-7][8]; Shift [1-2][3-4][5][6-7][8].
- Turning, tap zones, volume keys, zoom (pinch, double-tap, detail re-render after settle) behave as in Phase 2, applied
  to the unit. Panning a zoomed unit to its edge and then turning follows the same rule as Single (§6.7).
- No spread splitting in Two-page.
- Slider and label work on book pages ("n–n+1 / total" for a pair).

### 5.4 Spread splitting (Single)

- With Split On, or Auto and `width > height`, a wide page becomes **two virtual pages**, each half of the page
  (`x ∈ [0, ⌊w/2⌋)` and `[⌊w/2⌋, w)`). LTR order: left half then right half; RTL: right half then left half.
- Each half behaves like a normal page (fit modes, zoom, detail render on the half's region).
- With Split Off, a wide page is shown whole as in Phase 2.
- Slider and label use book pages: both halves show "n / total" *(designer's call)*; the slider snaps to the first
  half of the chosen page.

## 6. Design

### 6.1 Engine (Rust)

No new decode features are needed: `Book::render_tile` already renders any page region to any output size, decoded
pages are cached by `DecodeCache` (256 MB default) and `resize.rs` already makes adjacent regions agree at their seam
(`adjacent_regions_match_wider_render_*`). Expected changes, small:

1. **Seam correctness with fractional scales.** `TileRequest.region` is in integer page pixels. Webtoon tiles map an
   output band to a page band at a non-integer scale (e.g. 800 → 1080 is 1.35×). The builder chooses tile boundaries
   so neighbouring tiles agree exactly (for example: fix tile boundaries in **page** rows and derive each tile's output
   height by rounding the cumulative scaled position, so output bands abut with no gap or overlap), and adds a Rust test
   that a stack of such tiles equals one tall render within the existing tolerance. If that cannot be made exact
   through the current `TileRequest`, a sub-pixel source offset may be added to the Rust-only API *(builder's call)*.
2. **Prefetch must warm the right decode size** *(designer's call, found by the builder check)*. Today
   `prefetch_page` always picks the denom of a **fit-screen** render (`fit_size` → `choose_denom`). For an 800×24000
   strip on a 1080×2378 screen that is the 1/8 decode, while Webtoon tiles render at fit width and need 1/1, so
   prefetch does nothing useful for Webtoon. The same bug affects Single's Fit Width / Fit Height today. Fix: the
   exported uniffi `prefetch_hint` gains a fit-mode parameter (Screen / Width / Height, Webtoon passes Width), and
   `prefetch_page` chooses the denom of that fit. This is an exported API change (bindings regenerated) and triggers
   the fuzz rerun of item 3. In Webtoon the hint is issued with `current` = the page at the viewport top, whenever that
   page changes. A strip is large (800×24000 RGBA ≈ 77 MB decoded), so with `prefetch_ahead = 3` up to ~4 strips can
   occupy the decode cache; this fits the 256 MB budget and needs no change unless a test shows thrashing. If it does,
   Webtoon may pass a smaller look-ahead *(builder's call)*.
3. Any change to `core/src/archive/**`, `decode/**`, `resize.rs` or `engine.rs` triggers the 900 s fuzz rerun of the
   affected target(s), as in Phase 2.

### 6.2 Book layout model (Kotlin, pure)

A pure, JVM-unit-tested model turns page sizes + viewport + layout into geometry:
- **Webtoon:** cumulative page tops at fit-width scale (with estimated heights for unknown pages and anchor-preserving
  correction), lookup of the page at a given content Y, visible page range for a viewport rectangle at a given zoom/pan.
- **Units:** the list of pager units for Single (with virtual split halves) and Two-page (with cover/spread/shift
  rules), mapping unit ↔ book page in both directions, for LTR and RTL.

### 6.3 Tile manager (Kotlin)

- **Tile key:** (page, scale level, tile column, tile row). At 1× Webtoon tiles are full viewport width and about one
  viewport tall *(designer's call; final size is the builder's, at most the GPU max texture size in either dimension)*.
  When zoomed, the visible area is covered by tiles at the settled zoom's scale, gridded in both directions.
- **Working set:** tiles intersecting the viewport, plus a prefetch margin in the scroll direction (about one viewport
  ahead and half behind at 1×, *builder tunes*). Renders go through `PageRenderer` (both upload strategies; `H` default
  with `S` fallback, as Phase 2), on its bounded dispatcher, priority by distance to the viewport, with cancellation of
  tiles that leave the working set.
- **Zoom transitions:** during a gesture the existing 1× tiles are drawn scaled; after the settle, tiles at the new
  scale are rendered for the visible area only and replace the 1× tiles where ready. The 1× tiles of the visible area
  stay as the fallback underneath until replaced.
- **Byte cap (E3):** a single accounting of bytes held by the tile manager (`w × h × 4` per tile, software or
  hardware). Cap = **192 MiB** *(designer's call, ≈ 18 full-screen 1080×2378 tiles; final number set by the builder
  and recorded in `budgets.md`)*. Eviction order when a new tile would exceed it: tiles outside the working set farthest
  from the viewport first, then off-scale tiles, never a tile currently visible. A render that would still exceed the
  cap is not started (the area shows the best available lower-scale tile or the placeholder). Pooling/reusing tile
  buffers of the same size is allowed *(builder's call)*.
- The same accounting includes Single/Two-page bitmaps held by `PageWindow` while the reader is in those layouts, so one
  number bounds the reader's own image memory in every layout. (The engine's decode cache is a separate, already
  bounded native budget.)

### 6.4 Webtoon viewport (Compose)

- One composable; scroll offset, zoom and pan are snapshot state read **only in the draw phase** (and in gesture
  handlers). Drawing: for each visible page, the best available tiles (settled-scale first, then 1×, then a dark
  placeholder), transformed by the current zoom/pan.
- Gestures: one pointer-input handler arbitrates drag (scroll/pan), fling (decay), pinch (zoom about focal point),
  double-tap, and single taps (zones). Pinch must never also scroll.
- Exposes test semantics (scroll position, current page, zoom) and a `ReaderAutomation`-style hook for the Benchmark
  screen (scripted scroll and fling).
- Viewport size changes (rotation) keep the anchor page and its relative offset.

### 6.5 Two-page and split in the pager

- `HorizontalPager` pages become **units** from §6.2. `PageWindow` keys stay *book pages* (so rendered bitmaps are
  reused when switching between Single and Two-page if the size matches); a unit draws one or two page slots.
- Each page in a unit gets its base bitmap at its size inside the unit's fit; the zoom/detail logic of `ZoomablePage`
  operates on the unit's combined content, producing one detail render per page that intersects the visible area.
- A split half is a unit whose content is one page's half region: base and detail renders request only that half.

### 6.6 Benchmark screen

- New scenario **a1** (`webtoon-long.cbz`, generated on device like the other corpus files): scripted Webtoon scroll
  through the real viewport, per upload strategy, at 1×: steady drags at a constant speed down the whole book, then
  flings back up *(designer's call; the run stays short — total Benchmark run target rises from ≤ 3 to ≤ 4 minutes)*.
  Jank metric and run conditions are exactly Phase 2's (§6.4 of the Phase 2 spec). Also reported, for information
  only: frames that drew a placeholder for a visible area, and the tile manager's **peak held bytes**.
- **Fix b prefetched (Phase 2 caveat; owner decision E5).** The sharp-draw probe currently fires for the neighbour
  page *before* the turn because the pager draws adjacent pages at rest, so t1 falls back to the settle time (~416 ms
  on the owner's run, an animation time, not a latency). Fix: the **prefetched** turns in scenario b are issued
  **without animation** (`turnTo(p, animate = false)`, a snap), and t1 = the first frame after t0 that draws the target
  page sharp as the current page (the probe reports a sharp draw only for the current page, not for a neighbour drawn
  at rest). This measures what the budget is for: the bitmap is ready and its upload does not stall the frame. Cold
  turns keep their current script. `budgets.md` notes that b prefetched is measured with snapped turns. The JSON schema
  version is bumped.
- JSON gains the a1 rows and the information-only fields; the release-clean check is unchanged.

### 6.7 Phase 2 leftovers closed early in this phase

Verified on the emulator with screenshots or instrumented assertions (not a device):
1. **Fit Width/Height overflow:** a page taller (Width) or wider (Height) than the viewport is laid out from the
   correct origin, can be panned to every edge, and is not clipped (current code uses `wrapContentSize(unbounded = true)`).
2. **Pan-to-edge then turn:** while zoomed, dragging a page to its edge must stop at the edge, and only a *new* drag
   that continues past the edge turns the page (Phase 2 spec §5). Current behaviour may turn on the same drag because the
   pager settles only move the VM after a `DragInteraction.Start`; the builder confirms and fixes if needed. The same
   rule applies to Two-page units and split halves.

## 7. Verification for Phase 3 exit

Same model as Phase 2 (D2): functional on the host and emulator; the real-device run is an owner checkpoint (D1); a
budget miss found there is fixed in a later round.
- Rust: workspace tests green, including the tile-stack seam test (§6.1). Fuzz 900 s on any changed target.
- JVM unit tests: auto-detection rules; Webtoon layout (tops, estimated-height correction keeps the anchor, page at Y,
  visible range under zoom/pan); unit/pairing/split mapping for LTR and RTL incl. cover, wide page, shift; tile working
  set and eviction order; byte-cap accounting never exceeds the cap under a random scroll/zoom sequence.
- Instrumented (emulator `emulator-5554`):
  - `webtoon-long.cbz` opens in Webtoon automatically; scroll top to bottom and back; the tile manager's peak held bytes
    stays ≤ cap (E3); rendered pixels across a tile boundary and across a page boundary match a reference render.
  - zoom in Webtoon triggers a re-render of only visible tiles after settle; a new gesture cancels it; horizontal pan
    stays within bounds.
  - tap zones and volume keys scroll by ~85 %; overlay slider jumps to the right page.
  - `double-spread.cbz`: Single + Split Auto shows halves in the right order for LTR and RTL. A **new corpus file**
    `two-page.cbz` *(designer's call; added to `tools/gen-corpus`, existing files unchanged, version stamp bumped)*
    with portrait 1–4, spread 5, portrait 6–8 checks pairing (cover alone, adjacent portrait pairs, spread alone,
    restart, Shift) because `double-spread.cbz` never has two adjacent portrait pages. Rotation in Auto switches
    Single ↔ Two-page and keeps the book page.
  - the two Phase 2 leftovers (§6.7).
  - a corrupt page in Webtoon shows the placeholder and scrolling continues.
- Benchmark screen runs every scenario (a1, a2, a3, b) end to end on the emulator and produces a valid JSON (numbers
  ignored).
- Release APK: no benchmark code; 16 KB alignment passes.
- Owner hand-off: the `benchmark` APK path; the owner reads a Webtoon and a manga of their choosing and may run the
  Benchmark screen. No plan step installs onto or runs anything on the phone.

## 8. Documents to update

- `docs/perf/budgets.md`: a1's "How measured" becomes Benchmark screen scenario a1 (§6.6); the `perf-capture.ps1`
  method stays for reference only. Add the tile/bitmap byte cap (E3) as an **enforced design cap**, not a measured
  budget, with its value. Note the b-prefetched metric fix.
- Parent spec §8 Phase 3 row: "memory within budget" → "tile/bitmap byte cap enforced and tested (E3)".
- New record `docs/perf/phase3-reader.md` (exit status, fuzz record if any, what the a1 scenario does).

## 9. Constraints carried over

SAF only; minSdk 29, arm64-v8a (+ x86_64 for the emulator); 16 KB-aligned native libraries; `panic = "unwind"` in
release; input limits before allocation; specs and plans in English; environment setup stays out of the plan (no new
tool is expected); commit only when the owner asks; branch `phase-3` from `origin/master` (`ff72a45`; local `master` is still at
`dc8b700` and is fast-forwarded first).

## 10. Left to the builder

Tile size and prefetch margins; final byte-cap value (recorded); settle delay; fling tuning; how the viewport exposes
test semantics; whether `PageWindow` and the tile manager share one class or one accounting object; buffer pooling;
how the seam-exact tiling is achieved (§6.1); Webtoon look-ahead if the decode cache thrashes; a1 scroll speed and
length within the ≤ 4 minute total; JSON field names.
