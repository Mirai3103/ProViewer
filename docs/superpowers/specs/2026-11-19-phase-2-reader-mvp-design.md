# ProViewer Phase 2: Reader MVP design

Date: 2026-11-19 · Status: **draft, awaiting owner approval**
Parent spec: `docs/superpowers/specs/2026-11-18-proviewer-design.md` (§5, §7, §8 Phase 2). Phase 1 state:
`docs/perf/phase1-engine.md`. Budgets: `docs/perf/budgets.md`.

## 1. Intent and why

Phase 2 turns the Phase 1 engine into something the owner can actually read with: open a CBZ from the phone, page
through it LTR or RTL, and zoom into it with image quality that beats other readers (linear-light Lanczos resize to the
exact screen size, re-rendered sharp after a zoom). The phase also has to decide, by measurement, how page bitmaps reach the
GPU (§5 "Bitmap upload" of the parent spec). The owner forbids benchmark runs on their phone unless they start them. So
measurement moves **into the app**, as a Benchmark screen that the owner (or anyone with an identical phone) runs by tapping
a button, with no adb.

## 2. Decisions taken with the owner (2026-11-19)

| # | Decision | Owner's choice |
|---|---|---|
| D1 | How performance is measured | **In-app Benchmark screen** that measures itself, run only by the owner, on any phone, without adb |
| D2 | Phase 2 exit | **Functional verification on the emulator.** A real-device Benchmark run is a separate checkpoint the owner triggers; a budget miss found there is fixed in a later round, not a Phase 2 blocker |
| D3 | Phase 1 open item I3 (libjpeg warnings) | **Tolerate warnings**: show whatever libjpeg-turbo decoded instead of failing the page |
| D4 | Reader interaction (sketch below) | Accepted as proposed, including: **CBZ/ZIP only** in Phase 2, image folders move to Phase 4 |
| D5 | Rendering architecture | **Approach A** (§4): one fit bitmap per page plus one detail bitmap while zoomed. Owner delegated the choice ("theo ý bạn") |

## 3. Goals, non-goals, anti-goals

**Goals**
- Open a CBZ/ZIP through the SAF picker or through "Open with" from a file manager, including providers that return a
  non-seekable fd.
- Single-page reading, LTR and RTL; swipe, tap zones and volume keys turn pages; fit width / fit height / fit screen.
- Pinch zoom and double-tap zoom with a sharp re-render of the visible region after the gesture settles.
- Pixels go Rust → Android bitmap memory directly over a small raw JNI surface (parent spec §5 "Data path").
- Both bitmap upload strategies implemented behind one interface, compared by the Benchmark screen.
- Benchmark screen: scripted paged swipe (a2), scripted zoom/pan (a3), page-turn latency (b), for each upload strategy;
  results on screen and as a shareable JSON file.
- Slightly damaged JPEGs show the decoded part instead of an error page (D3).

**Non-goals (later phases)**
- Image folders via SAF (Phase 4, with the library scan). Library, recents, remembering position or settings (Phase 4).
- Webtoon vertical scroll, tiling, two-page mode, spread splitting (Phase 3).
- Settings screen, Material You polish, predictive back, tablet/foldable layouts (Phase 5).
- Page-turn animations other than the pager slide. RAR/7z/PDF/AVIF.

**Anti-goals (failure even if it works)**
- Anything runs a benchmark, or any long job, on a real device without the owner starting it from the Benchmark screen.
  No script, test or plan step may target the owner's phone (serial `3B65BT02H6G00000`).
- Benchmark code or the corpus generator ends up in the release APK.
- A seekable file gets copied before opening (copy is the fallback, never the default path).
- Decoding or resizing on the main thread; a malformed file that crashes or OOM-aborts the process.
- The Benchmark screen heats the phone for minutes (see §6.4 for the run-length and thermal rules).

## 4. Approaches considered (D5)

| | Approach | Trade-offs |
|---|---|---|
| **A (chosen)** | Each page is one bitmap rendered by Rust at the exact fit size. While zoomed, the fit bitmap is scaled as a placeholder; when the gesture settles, Rust renders exactly the visible region at the new scale into one **detail bitmap** drawn on top | Fewest moving parts; full control of resize quality; easy to instrument for the Benchmark screen. Very deep zoom on a 96 MP page is bounded by one viewport-sized detail render, not by tiles |
| B | Tile grid per zoom level from day one (subsampling-scale-image-view style) | Best deep-zoom memory and smoothness, and Phase 3 needs tiling for Webtoon anyway; about twice the work of A for a phase that only shows single pages |
| C | Telephoto `SubSamplingImage` with Rust as its region decoder | Least code. The library decides tile sizes and timing, which fights the engine's denom/resize/cache strategy, and it owns the bitmaps, which makes comparing upload strategies awkward |

A is the parent spec's §5 zoom description. Tiling is deferred to Phase 3, where Webtoon needs it. Using only the
*gesture* part of Telephoto (`Modifier.zoomable`) inside approach A is allowed (builder's choice, §9).

## 5. Reader behaviour (D4)

```
┌──────────── Home ─────────────────┐   ┌──────────── Reader (tap centre → overlay) ─────┐
│                                   │   │ ← file-name.cbz                                │
│        [ Open CBZ/ZIP ]           │   │                                                │
│                                   │   │  ◀ left 1/3 │  centre: menu  │  right 1/3 ▶    │
│                                   │   │  previous   │                │  next (swapped  │
│                                   │   │             │                │  in RTL)        │
│                                   │   │ ──────●──────────────  12 / 180                │
└───────────────────────────────────┘   │ [LTR|RTL]  [Fit width|Fit height|Fit screen]   │
                                        └────────────────────────────────────────────────┘
```

- **Opening:** SAF `ACTION_OPEN_DOCUMENT` for ZIP/CBZ MIME types. The app also registers an `ACTION_VIEW` intent filter
  so it appears in file managers' "Open with". The engine recognises ZIP by magic bytes, so the MIME filter only decides
  where the app is offered.
- **Turning pages:** horizontal swipe (pager slide); tap left/right third (mirrored in RTL); volume down = next,
  volume up = previous, only while the reader is visible. In RTL, the pager's layout direction is reversed so a swipe to
  the right goes forward.
- **Overlay** (centre tap toggles): file name and back at the top; page slider with "n / total" and LTR/RTL and fit-mode
  toggles at the bottom. Hidden by default.
- **Zoom:** pinch; double-tap zooms 2× around the tapped point, double-tap again returns to fit. While zoomed, a drag
  pans the page until its edge and only then turns the page.
- **Fit modes:** fit screen (whole page visible), fit width (page width = viewport width, vertical pan if taller), fit
  height (page height = viewport height, horizontal pan if wider).
- **Immersive** full screen with system bars hidden (bars come back with the overlay); screen kept on while reading.
- **State:** direction, fit mode and current page live in memory for the reading session and survive rotation. They are
  not persisted (Phase 4). After process death the app may return to Home.
- **Errors:** a page that fails to decode shows an in-page error placeholder (page number + short reason from
  `EngineException`'s `detail`, not `message`, see Phase 1 open item 4), and reading continues. A book that fails to open
  (unsupported, limit exceeded, I/O) shows a message on Home.

## 6. Design

### 6.1 Engine (Rust) changes

1. **Open from a file descriptor.** New open entry point taking an owned fd (Android `ParcelFileDescriptor.detachFd()`;
   Rust takes ownership and closes it). It reuses the ZIP path of `open_path` (magic-byte check, `ZipSource::from_reader`).
   The fd-to-`File` part is `cfg(unix)`. The part that takes a `File` must be testable on the Windows host, and the fd part
   in the Android instrumented tests.
2. **Non-seekable fds are detected and reported**, not copied by Rust: if `lseek` fails with `ESPIPE`, return a distinct
   error (a new `EngineError` variant or a documented `UnsupportedFormat` message; builder's choice) so Kotlin runs the
   copy fallback (§6.2).
3. **Raw JNI pixel path.** A handful of JNI functions in the core `.so` (crate choice is the builder's) that call the
   existing Rust-only `Book::render_tile`:
   - render a `TileRequest` into an `android.graphics.Bitmap` (`AndroidBitmap_lockPixels`, `RGBA_8888`, stride from
     `AndroidBitmapInfo`);
   - render a `TileRequest` into an `AHardwareBuffer` (`AHardwareBuffer_lock` with CPU-write usage, stride from
     `AHardwareBuffer_describe`);
   - create / cancel / release a `CancelToken` handle.
   The JNI side must obtain the `Book` through an **explicit handle API** (for example a uniffi method that returns an
   opaque `u64` holding an `Arc<Book>` clone, plus a release call), never by relying on uniffi's internal object-pointer
   layout. Errors cross as a Kotlin exception carrying the same variant/detail information as `EngineException`.
   `render_tile` already validates the request and stride before decoding; JNI adds checks that the bitmap/buffer
   format is RGBA_8888 and its size matches the request.
4. **Tolerate libjpeg warnings (D3).** Decode through `turbojpeg::raw` (turbojpeg-sys, re-exported by `turbojpeg` 1.5.1)
   or a patched wrapper, with stop-on-warning off. When the decompress call fails and the error code is `TJERR_WARNING`,
   keep the output buffer (libjpeg-turbo has already filled the undecoded rows; for baseline JPEGs that is a flat gray).
   Any `TJERR_FATAL` is still `Corrupt`. The size used for limits and allocation stays the header size, as now. The
   source-pixel cap for progressive/multi-scan files and every existing limit stay in force.
   - New fixtures: a baseline JPEG truncated mid-scan, one with extraneous bytes before a marker, one truncated in the
     header (must stay `Corrupt`). Add them to the decode fuzz seed set.
   - **Re-run the `decode` fuzz target for 900 s** on the final decode code (same WSL setup as Phase 1 Gate 4). The
     `archive` target is re-run too if the archive code changes for item 1.
5. **Benchmark corpus generator in the app, benchmark build only.** The Benchmark screen generates `mixed-small.cbz`,
   `screentone.cbz` and `huge-single.cbz` on the device by calling `gen-corpus` (deterministic, already Rust). It must not
   be linked into the release `.so`. **Call (designer's, not the owner's):** build it as a **separate benchmark-only
   native library** packaged only in the `benchmark` variant, so the measured core `.so` is byte-identical to release
   (`scripts/build-core.ps1` builds one core `.so` for every variant today). The release `.so` size and the 16 KB
   alignment check must be unaffected.

The `Engine` stays one app-wide instance (Phase 1 note: dropping it or a book can block on an in-flight decode). Books
are closed off the main thread. `EngineConfig` defaults stay as in Phase 1 unless a test shows a reason; cache memory is
native memory and does not count against the Java heap limit.

### 6.2 Opening a file (Kotlin)

1. `contentResolver.openFileDescriptor(uri, "r")`. Check seekability (`Os.lseek`, or the engine's `ESPIPE` error).
2. Seekable: `detachFd()` and open in the engine. No copy.
3. Not seekable: copy the stream into `cacheDir/opened/` with visible progress and a cancel button, check free space
   first, then open the copy by path. Copies are deleted when the book closes and swept at app start.
4. Take a persistable URI permission only if it is offered. Persistence itself is Phase 4, and nothing in Phase 2 depends
   on it.

### 6.3 Rendering, prefetch and zoom (Kotlin, approach A)

- **Page bitmaps.** For each visible or prefetched page, Kotlin allocates the target (software `Bitmap` or
  `AHardwareBuffer`, depending on the active upload strategy) at the fit size for the current fit mode and viewport, then
  asks the engine to render the whole page into it on a background dispatcher with bounded parallelism. Rust caches only
  decoded pages (Phase 1), so **Kotlin's bitmap set is the resized-output cache**: keep the current page plus
  `prefetch_ahead`/`prefetch_behind` neighbours in the reading direction (with the Phase 1 defaults 3 + 1, five
  1080×2378 RGBA bitmaps ≈ 50 MB), render them ahead of time, and call `prefetch_hint` on every page change. Bitmaps for
  pages that leave the window are released (hardware buffers explicitly).
- **Base bitmap size cap.** A fit-width render of a very tall page would allocate a huge bitmap (an 800×24000 strip at
  1080 wide is about 140 MB). The base bitmap is capped (no larger than the GPU max texture size and a fixed byte cap;
  builder picks the values). A page over the cap gets a lower-resolution base, and the detail bitmap supplies sharpness.
  Proper tall-page support is Phase 3.
- **Upload strategies** behind one interface, selectable at runtime by the Benchmark screen:
  - **S (software):** `Bitmap.createBitmap(ARGB_8888)` rendered through `AndroidBitmap_lockPixels`; on prefetched pages
    call `prepareToDraw()` so the RenderThread uploads before the page is shown.
  - **H (hardware):** `AHardwareBuffer` (RGBA_8888, CPU write + GPU sampled) rendered while locked, shown with
    `Bitmap.wrapHardwareBuffer(buffer, sRGB)`; no separate upload.
  - Default for normal reading until a real-device run decides: **H**, falling back to S if allocation or wrapping fails.
    The device run may flip the default; that is a one-line change recorded in `budgets.md`.
- **Zoom.** During a gesture the current bitmaps are only transformed in the draw phase (`graphicsLayer`/draw
  transforms, no recomposition per frame). When the gesture and any fling have settled (short debounce, builder picks the
  value), compute the visible page region, request it at viewport size (clipped to the page) into a single detail bitmap
  with its own `CancelToken`, and draw it on top of the base. Starting a new gesture cancels the pending detail render.
  At fit scale no detail render happens. Maximum zoom is the builder's choice, but it must allow at least 1:1 source pixels
  on `huge-single`.
- **Threading.** No engine call on the main thread except `prefetch_hint`, `cancel_prefetch`, `page_count` and cached
  `page_info` lookups. A page turn cancels stale renders.

### 6.4 Benchmark screen (`benchmark` build type only)

**Packaging.** The code lives in a `bench` source set compiled by the `debug` and `benchmark` build types (debug too,
so the emulator instrumented tests can drive it) and adds a "Benchmark" entry on Home. The `release` variant contains
none of it and gets a stub, and a check asserts this. The `benchmark` build type already exists: minified, not debuggable,
debug-signed, installable as a plain APK on a phone without adb.

**Corpus.** On the first run, generate the three files into app-private storage, with a version stamp so they are
regenerated only when the generator changes. Show progress. Generation time is reported but is not a benchmark.

**Scenarios.** Each scenario runs once per upload strategy (S then H, then H then S on the next run so order effects
show). The whole run must stay short; target ≤ 3 minutes in total:
| Id | Budget | What runs |
|---|---|---|
| a2 | ≤ 1 % janky | `mixed-small.cbz`: scripted animated page turns through the real pager (forward through the book, then back), fit screen, about one turn per 450 ms (the old `perf-capture.ps1` cadence; designer's call) |
| a3 | ≤ 1 % janky | `screentone.cbz` and `huge-single.cbz`: scripted animated zoom 1×→3×, pans, zoom out, several cycles, including the detail re-render after each settle |
| b | prefetched < 100 ms, cold < 300 ms | `mixed-small.cbz`: page-turn latency, "cold" = page not decoded and not rendered (fresh book or a far jump), "prefetched" = after the prefetch and pre-render window is idle. Report n, p50, p95, max per class |

**Frame metric.** Perfetto FrameTimeline is not readable in-app, so jank comes from `Window.OnFrameMetricsAvailableListener`:
a frame is **janky when `TOTAL_DURATION` > `DEADLINE`** (`FrameMetrics.DEADLINE`, API 31+). Frame-duration
percentiles are recorded for information only. On API < 31 the screen reports jank as unsupported. This only sees HWUI
frames, which is everything the Compose reader draws. It replaces the `perf-capture.ps1` method for a2/a3 in
`budgets.md` (see §7).

**Latency metric (b).** t0 = the moment the page turn is issued (programmatic equivalent of the finger lifting).
t1 = the frame in which the target page's full-quality fit bitmap is first drawn (a draw callback on the page plus the
frame's `FrameMetrics` timing). Placeholder frames do not count.

**Run conditions recorded with every result:** device model, Android version, app version and git SHA, active refresh
rate (`Display.getRefreshRate()` before and after each scenario; a scenario whose rate is not the display's max mode is
flagged), thermal status (`PowerManager.getCurrentThermalStatus()`; the run refuses to start at `MODERATE` or worse and
aborts if it reaches it), battery level and charging state, and whether the device is an emulator (emulator results are
marked invalid for performance).

**Output.** A results table on screen (per scenario × strategy: frames, janky %, p50/p95/p99 frame time; latency
classes) and a "Share JSON" button (FileProvider + `ACTION_SEND`). The JSON schema is versioned. The JSON from a real
device is what the owner sends back, and `budgets.md` / `phase1-engine.md`-style records are written from it.

### 6.5 Verification for Phase 2 exit (D2)

All on the host or the **emulator**; no real-device runs. Every connected test and install sets
`ANDROID_SERIAL=emulator-5554` (AVD `ProViewer_API37`) explicitly, because the owner's phone may also be attached to adb.
Early in the plan, a small emulator check confirms that a CPU-writable, GPU-sampled `AHardwareBuffer` can be allocated
and wrapped as a `Bitmap`. If it cannot, the H path is tested on the host side only, and the owner is told before going on.
- Rust: workspace tests green, including fd open (Android instrumented for the fd part), non-seekable detection, the
  three new JPEG fixtures, and JNI render tests (bitmap and hardware-buffer targets, wrong format/size rejected,
  cancellation). Fuzz `decode` (and `archive` if touched) 900 s clean on the final code.
- Android instrumented tests on the emulator: open a CBZ through a content URI (seekable) and through a pipe-backed
  provider (copy fallback); rendered page pixels match the engine's reference render; page turns in LTR and RTL go the
  right way for swipe, tap zones and volume keys; zoom triggers exactly one detail render after settling and a new
  gesture cancels it; a corrupt page shows the placeholder and the next page still loads; rotation keeps the page.
- Benchmark screen runs every scenario end to end on the emulator and produces a valid JSON (numbers ignored).
- Release APK: contains no benchmark classes and no corpus generator code (checked by a test or build task); 16 KB
  alignment check passes for every `.so`.
- Owner's manual check: the plan ends by handing the owner the debug-signed `benchmark` APK path. The owner installs it
  and reads a real CBZ of their choosing on their own device. This is a short functional check by the owner, not a
  benchmark; no plan step installs onto or runs anything on the phone.

## 7. Documents to update in Phase 2

- `docs/perf/budgets.md`: a2, a3 and b keep their approved targets. Their "How measured" column becomes the in-app
  Benchmark screen with the §6.4 definitions; a3 becomes scripted instead of owner gestures. The open conflict note is
  replaced by D1/D2. The real-device run is a checkpoint the owner starts.
- Parent spec §7/§8: note the in-app method and that Phase 2 completion is functional (D2).
- `docs/perf/phase1-engine.md` "Known limitations": I3 resolved by D3.

## 8. Constraints carried over

SAF only, no `MANAGE_EXTERNAL_STORAGE`; minSdk 29, arm64-v8a (+ x86_64 for the emulator); 16 KB-aligned native
libraries; `panic = "unwind"` in release; input limits enforced before allocation; specs and plans in English;
environment setup stays out of the plan (a separate setup doc if any new tool is needed. None is expected: the NDK,
emulator and WSL fuzz setup from Phase 1 cover this phase); commit only when the owner asks.

## 9. Left to the builder

JNI crate and function names; handle API shape; whether the non-seekable signal is a new error variant; Compose
structure, navigation and DI; using Telephoto's `Modifier.zoomable` or own gesture code; debounce, max zoom, base
bitmap caps, render dispatcher size; JSON schema details; exact scenario lengths within the ≤ 3 minute target; MIME
types in the intent filter; how the release-APK exclusion is checked.
