# ProViewer — Overall Design

Date: 2026-11-18 · Status: **approved** (revised 2026-11-18 after review: measurement method, bitmap upload, linear-light resize, input safety)

## 1. Intent and why

Build an Android comic/manga reader with the ambition of being a **"Perfect Viewer killer"**: higher image quality, smoother, and more modern UI than Perfect Viewer. Perfect Viewer is the inspiration, **not a benchmark target**: there is no side-by-side measurement against it (owner decision, 2026-11-18). We do our best against **absolute** budgets instead (§7). It will be published on Google Play; if the app turns out good enough, it will be sold as a paid app.

"Better" must be **measurable**, not a feeling (see section 7).

## 2. Goals / Non-goals / Anti-goals

**Goals**
- Superior image quality: no moiré on screentone when downscaling, sharp zoom.
- Smooth: page turns feel instant (prefetch + cache); zoom/pan/scroll never drop frames at the device's refresh rate.
- Modern UI: Material 3 / Material You, predictive back, tablet/foldable support.
- An architecture that lets the renderer be upgraded later without rewriting the engine.

**Non-goals (for now)**
- Support for old devices, low-RAM devices, or unusual GPU drivers (**out of scope by the project owner's decision**; revisit later).
- Online source integration / scrapers (Mihon-style).
- iOS, desktop, web.
- EPUB / text-book support.

**Anti-goals (failure even if it "works")**
- The app misses the absolute frame budget (§7) in any standard scenario of the benchmark suite.
- Doing everything a→z in one pass and being unable to verify any of it.
- Being rejected/removed by Play for policy violations (content, file-access permissions).

## 3. Constraints

- Reads **local files only** (OPDS/SMB/WebDAV possibly later). No pirate-source integration.
- File access through **SAF** (`content://`), no `MANAGE_EXTERNAL_STORAGE`. For Rust: obtain an fd from `ParcelFileDescriptor` and pass it down for reading/mmap. Some providers (cloud, some third-party) return **non-seekable fds** (pipes); ZIP needs seeking, so the engine must detect this and fall back to copying the file into app cache first.
- Native libraries must be **16 KB page-aligned** (Play requires this for apps targeting Android 15+). Verify from Phase 0 (use a recent NDK; the implementer must confirm the current requirement).
- **RAR/CBR**: not in v1; license must be reviewed (`unrar` vs `libarchive`) before selling.
- Dev environment: Windows 11. The Rust engine must be testable/benchmarkable on desktop, not only on-device.
- The project uses **git** (owner approved `git init`; done). Commit only when the owner asks.
- **Reference device (primary test device)**: OnePlus CPH2767 (SoC `SM8845`), ~11 GB usable RAM, Android 16, display locked to **120 Hz** (frame budget ≈ 8.3 ms; the panel supports up to 165 Hz, owner chose 120 Hz on 2026-11-18). Other configurations are tested on **emulator** only.
  - Emulator is adequate for **correctness** (runs, 16 KB, screen sizes, rotation/fold) but **not trustworthy for performance numbers**.
  - A flagship gives optimistic numbers; budgets for weaker devices come when scope expands.
- **Language**: all specs and plans are written in **English**. (Conversation with the owner is in Vietnamese.)

## 4. Approaches considered

| | Approach | Trade-offs |
|---|---|---|
| A | Compose draws; Rust only returns pixels | Lowest risk; keeps Android gestures/accessibility; no custom shaders |
| B | Rust + `wgpu` draws everything on a `SurfaceView` | Maximum control; driver risk, must reimplement fling/overscroll/back; most work |
| **C (chosen)** | **Start with A, but make the engine API renderer-agnostic** | Keeps A's benefits; leaves a path up to B (optional mode, enabled per device once benchmarks justify it) without a rewrite |

**Why C**: image quality lives mostly in the CPU resize step (achievable in A). Compose already hits high refresh rates when state is read in the draw phase (`graphicsLayer`), so Rust is not needed to avoid recomposition. B should only be built if benchmarks prove A is insufficient.

## 5. Architecture

```
┌────────────────────────── app/ (Kotlin, Compose, M3) ──────────────────────────┐
│ Library UI · Reader UI · Settings · SAF picker · Gestures (Compose)            │
│              │  uniffi (control API)          │  dedicated pixel path          │
└──────────────┼─────────────────────────────────┼───────────────────────────────┘
               ▼                                 ▼
┌──────────────────────────── core/ (Rust, Cargo workspace) ────────────────────┐
│ archive (folder, zip) → decode (jpeg/png/webp) → resize (Lanczos/Area)        │
│ tile scheduler · LRU cache (RAM budget) · prefetch · cancel stale work        │
└───────────────────────────────────────────────────────────────────────────────┘
```

**Engine contract (renderer-agnostic)** — key ideas only; concrete names are the implementer's choice:
- `open(fd/path)` → book (page count, per-page info: dimensions, format).
- `request_tile(page, region, scale, priority)` → pixel buffer already resized to the target size.
- `prefetch_hint(current page, reading direction)`; `cancel(...)` when the user jumps.
- Cache budget configuration (max RAM).

**Data path**: `uniffi` for control calls (few, small). Pixels travel on a separate path (Rust writes into a locked `Bitmap` via `AndroidBitmap_lockPixels`, or a direct `ByteBuffer`) to avoid serialization and extra copies. This means two FFI mechanisms coexist (uniffi/JNA for control, raw JNI for pixels); keep the JNI surface to a handful of functions.

**Bitmap upload to the GPU**: a software `Bitmap` is uploaded on the RenderThread the first time it is drawn (a 1600×2400 RGBA page ≈ 15 MB). At 120 Hz (8.3 ms per frame) this is the most likely source of dropped frames on page turn. Phase 2 must measure and choose between: `Bitmap.prepareToDraw()` on prefetched pages (async upload), and `AHardwareBuffer` + `Bitmap.wrapHardwareBuffer` (API 29 = our minSdk; no separate upload). This is evaluated in Phase 2, not deferred to "later".

**Image quality**: resize on CPU (`fast_image_resize`, Lanczos or Area) to the exact screen size, then display 1:1. Resize in **linear light** (sRGB → linear → resize → sRGB): resizing gamma-encoded values darkens and shifts screentone, which is as visible as moiré. On zoom: temporarily show the existing bitmap, then re-render the visible region at the new scale once the gesture settles. This is the primary defense against moiré; default trilinear mipmaps are not used.

**Decoding note**: JPEG scaled decode (1/2, 1/4, 1/8) is very valuable for fit-to-screen. The implementer must **compare `zune-jpeg`, `libjpeg-turbo`, and the platform decoder** (`ImageDecoder` / `BitmapFactory` with `inSampleSize`, as the baseline to beat) on speed and scaled-decode support before choosing a codec.

**Untrusted input**: archives and images are untrusted. Rust prevents memory corruption but not OOM, and an allocation failure aborts the process (uniffi cannot turn it into an exception). The engine must enforce limits before allocating: max image dimensions / pixel count, max total uncompressed archive size and entry count (zip bombs), and must reject or downscale-on-decode rather than allocate full size. Release builds keep `panic = "unwind"` so uniffi can turn panics into Kotlin exceptions.

## 6. Technical decisions (my proposals, not agreed commitments)

- minSdk **29**; build **arm64-v8a** only (+ x86_64 for emulator during dev).
- Cargo workspace `core/` + Gradle `app/`.
- v1 formats: **image folders, CBZ/ZIP; JPEG/PNG/WebP**. Deferred: RAR/CBR, 7z, AVIF, PDF.
- v1 reading modes: single page LTR/RTL; vertical scroll for Webtoon (vertical tiling); two-page with double-spread splitting; zoom/pan; fit modes (width, height, fit).
- v1 persistence: remember reading position, basic library, basic brightness/color settings.
- Sharpening shaders (Anime4K/FSR), `wgpu`: **deferred**, built only when benchmarks demand them. (`AHardwareBuffer` is evaluated in Phase 2 — see §5 "Bitmap upload".)

## 7. Measuring "smoother" (performance budget)

A benchmark suite exists from Phase 0. Metrics:
- Page turn → sharp image: first time (cold cache) and after prefetch.
- Dropped frames during zoom/pan and Webtoon scrolling, at the device refresh rate (120 Hz on the reference device).
- Peak memory when reading many pages / very large images — **total process memory** (Rust cache + `Bitmap` pixels + GPU textures), not only the Rust cache budget.
- Time to open a book (large CBZ).

**Frame measurement method**: Perfetto **FrameTimeline** (`android.surfaceflinger.frametimeline`), analysed with `trace_processor`. Not `dumpsys gfxinfo framestats`, because: it only keeps the last ~120 frames (≈ 1 s at 120 Hz); it only sees HWUI-drawn frames (blind to SurfaceView/GL, e.g. a future `wgpu` renderer); and "frame duration > 1 vsync" is not jank on a pipelined renderer. A frame is janky when FrameTimeline marks a `jank_type` other than `None` / `Buffer Stuffing`. Budgets are stated as **janky-frame %**; frame-duration percentiles are reported for information only.

Concrete targets are **absolute** (no competitor baseline). They are proposed in Phase 0 as provisional numbers, approved by the owner, then used as completion criteria for later phases; a target is tightened when measurements show we can do better, never loosened silently. Starting proposals: janky frames ≤ 1 % per scenario at the locked max refresh rate; prefetched page turn < 100 ms, cold < 300 ms. Also needed: **a fixed test corpus** (manga with screentone, long Webtoon, very large images, double-page spreads).

## 8. Phased roadmap (each phase = its own implementation round)

Each phase has its **own plan** and **one approval point** from the owner before the next. Phases are not merged.

| Phase | Content | Completion criteria |
|---|---|---|
| **0 — Foundations** | Repo setup, Cargo workspace + Gradle, uniffi "hello", build arm64 `.so`, **16 KB alignment check**, benchmark skeleton, test corpus, set performance budgets on the reference device | Empty app runs on device and calls Rust; 16 KB check passes; baseline numbers recorded |
| **1 — Engine MVP** | Open folder/CBZ, decode JPEG/PNG/WebP, linear-light resize, tile API, LRU cache, prefetch/cancel, input limits (dimensions, zip bombs), natural sort of entry names. Runs via CLI/tests on desktop | Rust tests + benches green; codec comparison (incl. platform decoder baseline) done; fuzz targets for archive + decode run clean for a fixed time budget; decode/resize/cache numbers |
| **2 — Reader MVP** | Open files via SAF (incl. non-seekable fd fallback), single-page LTR/RTL reading, zoom/pan, fit modes, wired to the engine; bitmap upload strategy chosen by measurement | Reads a real CBZ on device; page-turn & zoom benchmarks meet budget |
| **3 — Webtoon + two-page** | Vertical scroll with tiling, two-page mode, spread splitting | Long Webtoon scrolls with no dropped frames; memory within budget |
| **4 — Persistence & library** | Remember position, library, brightness/color settings | Quit/reopen returns to the correct position; library usable |
| **5 — Release prep** | Material You, predictive back, tablet/foldable, release signing, privacy policy, paid/Play Billing decision, store listing | Release AAB passes Play pre-launch checks |
| **Later** | RAR/7z/AVIF/PDF; sharpening shaders; optional `wgpu` renderer per device; old-device support | Each item gets its own brainstorm |

Phases 0–1 deliberately have **no reader UI**; the purpose is to get real measurements before building the interface.

## 9. Known risks

- **RAR license / weak Rust RAR ecosystem** → deferred, handled separately.
- **SAF is slow** on large directories → use `DocumentsContract` queries directly, not `DocumentFile`.
- **`zune-jpeg` may lack scaled decode** → may need `libjpeg-turbo` via FFI.
- **Play policy** on content and file permissions → stay on SAF, no pirate-source integration.
- **JNI/uniffi complexity** → keep the control API small; keep the pixel path separate.
- **Flagship-only measurement** → numbers are optimistic; emulator can't validate performance on weaker hardware (accepted for now, out of scope).
- **GPU upload jank at 120 Hz** → see §5 "Bitmap upload"; measured in Phase 2.
- **Non-seekable SAF fds** → copy-to-cache fallback (§3).
- **Malicious/corrupt files cause OOM abort** → input limits + fuzzing in Phase 1 (§5 "Untrusted input").
- **Absolute budgets may be set too loose or too tight without a competitor reference** → mark provisional, tighten as soon as Phase 1–3 numbers exist; the owner approves every change.

## 10. Left to the implementer

Concrete API names; concrete crates (after comparison); module structure; tile size; cache eviction policy; Kotlin libraries (DI, DB, navigation); benchmarking tooling.

## 11. Open questions

Resolved (2026-11-18):
1. Reference device: OnePlus CPH2767, ~11 GB RAM, Android 16, locked to 120 Hz (see section 3). Concrete budget numbers are still set in Phase 0 by measurement.
2. `git init`: approved and done.
3. Rust/NDK experience: owner is experienced → per-phase plans can be concise, with no foundational explanations.

Still open: none.
