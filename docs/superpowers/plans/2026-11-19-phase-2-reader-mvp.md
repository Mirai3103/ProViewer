# ProViewer Phase 2 — Reader MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A reader that opens a CBZ/ZIP through SAF (with a copy fallback for non-seekable fds), pages LTR/RTL with swipe, tap zones and volume keys, zooms with a sharp detail re-render, renders through a raw JNI pixel path into software or hardware bitmaps, and ships an in-app Benchmark screen (debug and `benchmark` builds only) that measures budgets a2, a3 and b.

**Architecture:** Rust gains `open_fd` (with a `NotSeekable` error), warning-tolerant JPEG decode, a handle registry, and a few JNI functions that call the existing Rust-only `Book::render_tile` into an `android.graphics.Bitmap` or a `HardwareBuffer`. Kotlin keeps one app-wide `Engine`. A `PageWindow` renders the current page plus 3 ahead and 1 behind as fit-size bitmaps (the resized-output cache). The reader is a Compose `HorizontalPager` with Telephoto's `Modifier.zoomable` per page, and a viewport-sized detail bitmap rendered after a zoom settles. Benchmark code and a separate corpus-generator native library live in a `bench` source set that only the `debug` and `benchmark` build types compile; `release` gets a stub.

**Tech Stack:** Rust 1.100 stable + `cargo-ndk` (as Phase 1); new Rust dep `jni` `=0.22.4` (Android target only); raw `turbojpeg::raw` (turbojpeg-sys) for decode. Kotlin 2.4.21, AGP 9.5.1, Compose BOM 2026.09.00; new: `me.saket.telephoto:zoomable` `0.19.0`, `androidx.lifecycle:lifecycle-viewmodel-compose` `2.11.0`, `androidx.compose.foundation:foundation` (BOM), JUnit 4 JVM tests. JSON via `org.json` (platform). Fuzzing: `scripts/fuzz.ps1` in WSL distro `proviewer`.

**Spec:** `docs/superpowers/specs/2026-11-19-phase-2-reader-mvp-design.md` (owner-approved 2026-11-19; decisions D1–D5 in §2). Parent: `docs/superpowers/specs/2026-11-18-proviewer-design.md`. Phase 1 state: `docs/perf/phase1-engine.md`.

## Global Constraints

- **No real-device runs.** Every `adb`, `gradlew connected*` and install command sets `$env:ANDROID_SERIAL = 'emulator-5554'` (AVD `ProViewer_API37`, API 37, 1080×2378). Never target serial `3B65BT02H6G00000`, even if it is attached. `scripts/phase1-device-session.ps1`, `scripts/device-run.ps1` and `scripts/perf-capture.ps1` are not run. Emulator numbers are never recorded as performance results.
- Phase 2 exit is functional (D2). The real-device Benchmark run is the owner's own checkpoint after this plan.
- Release profile keeps **`panic = "unwind"`**. Input limits stay enforced before allocation. Linear-light Lanczos3 resize stays the only quality path.
- Every `.so` in every APK stays **16 KB-aligned** (`scripts/check-16kb.ps1`). minSdk 29, targetSdk 36, ABIs `arm64-v8a` + `x86_64`.
- **The release APK contains no benchmark code** and no `libproviewer_benchgen.so` (Task 3 check, run again in Task 11). The core `libproviewer_core.so` is the same build in every variant.
- No decode, resize or `render*` call on the main thread. Allowed there: `prefetchHint`, `cancelPrefetch`, `pageCount`, cached page info.
- Kotlin reads `EngineException` text from `detail`, never `message` (renamed in `core/uniffi.toml`).
- Pin exact versions (`=x.y.z` in Cargo, exact in `gradle/libs.versions.toml`). Cargo.lock committed.
- Docs and plans in **English**. Scripts are PowerShell, run as `powershell -NoProfile -ExecutionPolicy Bypass -File <script>`.
- **Commit only when the owner asks.** Ask once at the start whether commits are authorized. Work on branch `phase-2` from `master`. The first commit (if authorized) also adds the spec and this plan. Every task's commit step runs only if authorized.
- **Preflight (not setup):** `scripts/check-env.ps1` passes. The AVD `ProViewer_API37` exists (`emulator -list-avds`). Task 11's fuzzing needs WSL distro `proviewer` (`docs/dev/wsl-proviewer-setup.md`). No new tool is needed. If a preflight fails, stop and point to the setup doc; install nothing.

## Review Focus

1. **Cloud/virtual documents where `openFileDescriptor` throws** (for example Drive files not yet downloaded) must fall back to the `openInputStream` copy path instead of failing. Pinned in Task 5 (`provider_without_fd_falls_back_to_copy`).
2. **Rotation or any viewport change mid-render** must cancel renders sized for the old viewport and never show a stale-size bitmap as the sharp page. Pinned in Task 6 (`viewport_change_cancels_and_rerenders`) and Task 7 (`rotation_keeps_page_and_rerenders`).
3. **Rapid page jumps** (dragging the slider across a 500-page book) must not queue a render per page passed. Only the final window renders, and memory stays bounded. Pinned in Task 6 (`rapid_jumps_render_only_final_window`).
4. **Extreme aspect ratios in every fit mode** (a 800×24000 strip in fit width, a 3200×2400 spread in fit height) must stay under the base-bitmap caps and display. Pinned in Task 6 (`base_size_caps_tall_and_wide_pages`) and Task 7 (`tall_page_fit_width_displays`).
5. **Leaving the reader during a cold render** must not crash, must release native handles, and must not render into a released book. Pinned in Task 7 (`back_during_render_releases_cleanly`).

## File Structure

```
core/Cargo.toml                         + [target.'cfg(target_os = "android")'.dependencies] jni
core/src/error.rs                       + EngineError::NotSeekable
core/src/archive/mod.rs                 + open_file(File, label, Limits); open_path uses it
core/src/engine.rs                      + Engine::open_fd (uniffi), Engine::open_file (Rust-only), Book::native_handle
core/src/handles.rs                     HandleRegistry<T>; BOOKS, CANCELS statics; release_book_handle (uniffi)
core/src/decode/jpeg.rs                 raw tj3 decompress, warnings tolerated
core/src/android/{mod,ffi,pixels}.rs    cfg(android): jnigraphics/nativewindow externs, JNI exports
core/tests/jpeg_damaged.rs              damaged-JPEG fixtures (built in-test via gen-corpus helpers)
tools/gen-corpus/src/lib.rs             + generate_named, damaged-JPEG helpers, seeds include damaged JPEGs
tools/bench-corpus/                     cdylib `proviewer_benchgen`: JNI wrapper over gen-corpus
scripts/build-core.ps1                  + builds proviewer_benchgen into app/build/benchJniLibs
scripts/check-release-clean.ps1         release APK has no benchgen .so and no benchmark marker
app/build.gradle.kts                    bench/nobench source sets, deps, GIT_SHA BuildConfig, JVM tests
app/src/main/kotlin/com/proviewer/app/
  ProViewerApp.kt                       Application: engine singleton, opened-copies sweep
  MainActivity.kt                       Home | Reader | Benchmark nav, ACTION_VIEW, volume keys, immersive
  engine/NativePixels.kt                JNI externs + NativeRenderException
  engine/NativeBook.kt                  Book + native handle lifecycle
  engine/PageRenderer.kt                UploadStrategy, PageImage, render with S/H targets + fallback
  open/BookOpener.kt                    SAF fd open, NotSeekable/throwing-provider copy fallback
  home/HomeScreen.kt
  reader/ReaderLogic.kt                 pure: FitMode, Direction, baseSize, tapAction, keyAction, windowPages, visibleRegion
  reader/PageWindow.kt                  window of rendered pages, prefetch, cancellation, awaitIdle
  reader/ReaderViewModel.kt  reader/ReaderScreen.kt  reader/ZoomablePage.kt  reader/ReaderProbe.kt
app/src/bench/kotlin/com/proviewer/app/bench/   (debug + benchmark only)
  VariantFeatures.kt  CorpusGenerator.kt  FrameRecorder.kt  BenchMath.kt  RunConditions.kt
  BenchmarkJson.kt  Scenarios.kt  BenchmarkRunner.kt  BenchmarkScreen.kt
app/src/nobench/kotlin/com/proviewer/app/bench/VariantFeatures.kt   (release stub)
app/src/debug/AndroidManifest.xml, app/src/benchmark/AndroidManifest.xml   + FileProvider for JSON share
app/src/test/kotlin/...                 JVM tests (ReaderLogic, PageWindow with fakes, BenchMath, BenchmarkJson)
app/src/androidTest/...                 TestCbzProvider + instrumented tests (emulator only)
docs/perf/budgets.md, docs/perf/phase1-engine.md, docs/perf/phase2-reader.md, parent spec §7/§8
```

---

### Task 1: Engine opens from a file descriptor

**Files:**
- Modify: `core/src/error.rs`, `core/src/archive/mod.rs`, `core/src/engine.rs`
- Test: unit tests in those files, `core/tests/engine_api.rs`

**Interfaces:**
- Produces:
  - `EngineError::NotSeekable` (unit variant, `#[error("file descriptor is not seekable")]`). Kotlin: `EngineException.NotSeekable`.
  - `pub fn open_file(file: File, label: &str, limits: Limits) -> Result<Arc<dyn PageSource>>` in `archive/mod.rs`: the magic-byte check and `ZipSource::from_reader` that `open_path` used for files, with `label` in the `UnsupportedFormat` message. `open_path` calls it for non-directories.
  - `Engine::open_file(&self, file: File) -> Result<Arc<Book>>` (Rust-only, not exported) and a private `fn book_from_source(&self, source) -> Arc<Book>` shared with `open_path`.
  - `#[uniffi::export] Engine::open_fd(&self, fd: i32) -> Result<Arc<Book>>`, exported on **every** platform. Bindings are generated from the Windows host `.dll`, so a `cfg(unix)`-only method would vanish from Kotlin. On unix: take ownership (`File::from_raw_fd`), `lseek(fd, 0, SEEK_CUR)`; `ESPIPE` → drop the file (closes the fd) and return `NotSeekable`; then `open_file(file, "fd")`. Non-unix: `UnsupportedFormat { message: "open_fd needs a unix file descriptor" }`.

- [ ] **Step 1: Write failing tests:** `open_file_opens_cbz` (temp copy of `mixed-small.cbz` from `common::small_corpus()` → 20 pages, same names as `open_path`); `open_file_rejects_text` (→ `UnsupportedFormat`, message contains the label); `not_seekable_display` (`EngineError::NotSeekable.to_string() == "file descriptor is not seekable"`); `#[cfg(not(unix))] open_fd_is_unsupported_off_unix`.
- [ ] **Step 2: Run** `cargo test -p proviewer_core` — Expected: FAIL (items missing).
- [ ] **Step 3: Implement** per Interfaces. The fd and `ESPIPE` path is exercised on Android in Task 5.
- [ ] **Step 4: Run** `cargo test --workspace` and `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gen-bindings.ps1` — Expected: all pass, and the generated Kotlin has `fun openFd(fd: Int)` and `EngineException.NotSeekable`.
- [ ] **Step 5: Commit** (only if authorized): `git add core Cargo.lock docs/superpowers && git commit -m "feat(core): open books from a file descriptor"`

---

### Task 2: Tolerate libjpeg warnings (D3)

**Files:**
- Modify: `core/src/decode/jpeg.rs`, `tools/gen-corpus/src/lib.rs`, `docs/perf/phase1-engine.md` (I3 → resolved)
- Create: `core/tests/jpeg_damaged.rs`

**Interfaces:**
- Produces (gen-corpus, used by core tests and fuzz seeds):
  - `pub fn truncate_mid_scan(jpeg: &[u8], keep: f32) -> Vec<u8>`: keeps all bytes up to the first SOS segment's end plus `keep` (0..1) of the entropy-coded bytes; no EOI.
  - `pub fn insert_extraneous_before_sos(jpeg: &[u8]) -> Vec<u8>`: inserts the bytes `[0x12, 0x34]` immediately before the first `FF DA` marker.
  - `generate_fuzz_seeds` also writes both damaged forms of the first JPEG seed as the next `<n>.jpg` files (the existing seed-name test still holds).
- Behaviour in `jpeg.rs`: decode through `turbojpeg::raw` (tj3 API) in a small RAII wrapper (handle destroyed on drop). Set `TJPARAM_STOPONWARNING = 0` and `TJPARAM_SCANLIMIT = 256`. For `tj3DecompressHeader` and `tj3Decompress8`, a `-1` return whose `tj3GetErrorCode` is `TJERR_WARNING` counts as success. `TJERR_FATAL` → `Corrupt` with the tj error string. Everything else stays: lossless rejection, `plan` limits on the header size, the source-pixel cap for multi-scan files, the CMYK conversion, buffers sized by us.

- [ ] **Step 1: Write failing tests** in `core/tests/jpeg_damaged.rs`. The clean input is a 256×256 RGB gradient, baseline q90, encoded with `jpeg-encoder`. Decode through the public `decode(bytes, 1, &Limits::default())`:
  - `truncated_mid_scan_decodes_partially`: `truncate_mid_scan(clean, 0.6)` → `Ok`, 256×256. Rows 0..64 have mean absolute difference ≤ 2 against the clean decode.
  - `extraneous_bytes_decode_fully`: → `Ok`, MAE ≤ 1 against the clean decode over the whole image.
  - `truncated_header_is_corrupt`: `clean[..20]` → `Err(Corrupt{..})`.
  - `fatal_garbage_is_corrupt`: SOI followed by 64 bytes `0xAB` → `Err(Corrupt{..})`.
  - gen-corpus unit test: `damaged_helpers_shape` (truncated has no trailing `FF D9` and is shorter; extraneous is 2 bytes longer).
- [ ] **Step 2: Run** `cargo test -p proviewer_core --test jpeg_damaged` — Expected: the first two FAIL with `Corrupt`.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `cargo test --workspace` — Expected: all pass, including every Phase 1 JPEG test (progressive, CMYK, denoms 1/2/4/8, limits) and `fuzz_regressions`.
- [ ] **Step 5: Update** `docs/perf/phase1-engine.md` "Known limitations": I3 resolved by owner decision D3 (2026-11-19) and implemented here. Fuzz evidence comes in Task 11.
- [ ] **Step 6: Commit** (only if authorized): `git add core tools/gen-corpus docs/perf/phase1-engine.md Cargo.lock && git commit -m "feat(core): show partially decodable JPEGs instead of failing"`

---

### Task 3: Bench-only source set and corpus-generator library

**Files:**
- Create: `tools/bench-corpus/Cargo.toml`, `tools/bench-corpus/src/lib.rs`, `app/src/bench/kotlin/com/proviewer/app/bench/VariantFeatures.kt`, `app/src/bench/kotlin/com/proviewer/app/bench/CorpusGenerator.kt`, `app/src/nobench/kotlin/com/proviewer/app/bench/VariantFeatures.kt`, `scripts/check-release-clean.ps1`, `app/src/androidTest/kotlin/com/proviewer/app/CorpusGeneratorTest.kt`
- Modify: `Cargo.toml` (members), `tools/gen-corpus/src/lib.rs`, `scripts/build-core.ps1`, `app/build.gradle.kts`, `app/proguard-rules.pro`

**Interfaces:**
- Produces:
  - gen-corpus: `pub fn generate_named(out_dir: &Path, scale: f32, names: &[&str]) -> Result<Vec<PathBuf>>` generates only the listed files (the names from the corpus README). An unknown name is an error. `generate_with_scale` becomes `generate_named` with all seven names; outputs stay byte-identical (an existing test or a new hash test proves it).
  - `tools/bench-corpus`: `crate-type = ["cdylib"]`, `[lib] name = "proviewer_benchgen"`, deps `gen-corpus` (path) and `jni` (workspace, `=0.22.4`). JNI exports for class `com.proviewer.app.bench.CorpusGenerator`:
    - `nativeGenerate(dir: String, scale: Float, names: Array<String>): String?` returns null on success and the error text otherwise. Panics are caught and returned as text.
    - `nativeVersion(): String` = gen-corpus `CARGO_PKG_VERSION`.
  - `scripts/build-core.ps1`: after the core build, `cargo ndk -t arm64-v8a -t x86_64 -o app/build/benchJniLibs build --profile $Profile -p bench-corpus`. The `.cargo/config.toml` 16 KB rustflags already apply.
  - Gradle: source set dir `src/bench/kotlin` plus jniLibs dir `build/benchJniLibs` for the `debug` and `benchmark` build types; `src/nobench/kotlin` for `release`. Use the same `directories.add` DSL style already used for the uniffi dir.
  - `object VariantFeatures { val benchmarkAvailable: Boolean; @Composable fun BenchmarkScreen(onBack: () -> Unit) }` with package `com.proviewer.app.bench` in both source sets. The stub has `false` and an empty composable. The bench version shows a placeholder until Task 10.
  - `object CorpusGenerator { const val MARKER = "PV_BENCHMARK_MARKER"; suspend fun ensure(dir: File, scale: Float, names: List<String>): List<File> }`. It loads `proviewer_benchgen`, regenerates only when `dir/corpus.stamp` ≠ `"${nativeVersion()}@$scale"`, and runs on `Dispatchers.IO`. `MARKER` is written into the benchmark JSON (Task 9), so R8 keeps the string.
  - `scripts/check-release-clean.ps1 -Apk <path>`: exit 1 if the APK has any `lib/*/libproviewer_benchgen.so` or if any `classes*.dex` contains the ASCII bytes `PV_BENCHMARK_MARKER`; otherwise exit 0.

- [ ] **Step 1: Write failing tests:** gen-corpus `generate_named_matches_full_generation` (`mixed-small.cbz` from `generate_named` is byte-identical to the one from `generate_with_scale` at scale 0.05) and `generate_named_rejects_unknown`. Instrumented `CorpusGeneratorTest.generates_small_corpus` (scale 0.05, names `mixed-small.cbz`, `screentone.cbz`, `huge-single.cbz`: three files exist and are non-empty; a second `ensure` call does not rewrite them, same `lastModified`).
- [ ] **Step 2: Run** `cargo test -p gen-corpus` — Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces. Proguard: keep `com.proviewer.app.bench.CorpusGenerator` native methods (and later JNI-called classes) with `-keepclasseswithmembernames`.
- [ ] **Step 4: Run** `cargo test --workspace`, then `$env:ANDROID_SERIAL='emulator-5554'; .\gradlew.bat :app:connectedDebugAndroidTest` — Expected: pass, including the Phase 0 `EngineHelloTest`.
- [ ] **Step 5: Verify exclusion:** `.\gradlew.bat :app:assembleRelease :app:assembleBenchmark`. Then `check-release-clean.ps1 -Apk app/build/outputs/apk/release/app-release-unsigned.apk` must exit 0, `check-release-clean.ps1 -Apk app/build/outputs/apk/benchmark/app-benchmark.apk` must exit 1 (proves the check detects), and `check-16kb.ps1` passes on both APKs.
- [ ] **Step 6: Commit** (only if authorized): `git add Cargo.toml Cargo.lock tools scripts app && git commit -m "build: bench-only source set and on-device corpus generator"`

---

### Task 4: JNI pixel path (Bitmap and HardwareBuffer)

**Files:**
- Create: `core/src/handles.rs`, `core/src/android/mod.rs`, `core/src/android/ffi.rs`, `core/src/android/pixels.rs`, `app/src/main/kotlin/com/proviewer/app/engine/NativePixels.kt`, `app/src/androidTest/kotlin/com/proviewer/app/NativePixelsTest.kt`
- Modify: `core/Cargo.toml`, `Cargo.toml` (`jni` in workspace deps), `core/src/lib.rs`, `core/src/engine.rs`, `app/proguard-rules.pro`

**Interfaces:**
- Produces (Rust):
  - `pub struct HandleRegistry<T>` with `insert(Arc<T>) -> u64` (non-zero, never reused), `get(u64) -> Option<Arc<T>>` (clone under a short lock) and `remove(u64) -> bool`. Statics: `BOOKS: HandleRegistry<Book>` and `CANCELS: HandleRegistry<CancelToken>`.
  - `#[uniffi::export] Book::native_handle(self: Arc<Self>) -> u64` registers the book. `#[uniffi::export] pub fn release_book_handle(handle: u64) -> bool`.
  - `android/ffi.rs`: hand-written externs only for `AndroidBitmap_getInfo`, `AndroidBitmap_lockPixels`, `AndroidBitmap_unlockPixels` (`jnigraphics`), and `AHardwareBuffer_fromHardwareBuffer` (`android`), `AHardwareBuffer_describe`, `AHardwareBuffer_lock`, `AHardwareBuffer_unlock` (`nativewindow`). No `ndk-sys`.
  - `android/pixels.rs` JNI exports for `com.proviewer.app.engine.NativePixels`. Every export runs inside `catch_unwind` (a panic becomes kind `"Panic"`):
    - `renderToBitmap(book: Long, page: Int, x: Int, y: Int, w: Int, h: Int, bitmap: Bitmap, cancel: Long)`: output size = bitmap size, format must be `ANDROID_BITMAP_FORMAT_RGBA_8888`, stride from `AndroidBitmapInfo`.
    - `renderToHardwareBuffer(book, page, x, y, w, h, buffer: HardwareBuffer, cancel)`: format must be `R8G8B8A8_UNORM` with CPU-write usage. Stride = `desc.stride * 4`. Lock with `CPU_WRITE_OFTEN`, unlock on every path.
    - `newCancelToken(): Long`, `cancel(token: Long)`, `releaseCancelToken(token: Long)`. `cancel = 0` means no token.
    - Failures throw `NativeRenderException(kind, detail)`. `kind` is the `EngineError` variant name (`"Corrupt"`, `"Cancelled"`, `"InvalidRequest"`, …), or `"InvalidHandle"` or `"Panic"`. `detail` is the Display text.
- Produces (Kotlin): `object NativePixels` (calls `System.loadLibrary("proviewer_core")` in `init`; the `external fun`s above) and `class NativeRenderException(val kind: String, val detail: String) : RuntimeException("$kind: $detail")`. Proguard keeps this class and its `(String, String)` constructor.

- [ ] **Step 1: Hardware-buffer probe on the emulator first.** Add `NativePixelsTest.hardware_buffer_is_writable_and_wrappable`: `HardwareBuffer.create(64, 64, HardwareBuffer.RGBA_8888, 1, USAGE_CPU_WRITE_OFTEN or USAGE_GPU_SAMPLED_IMAGE)` and `Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB))` must be non-null. Run `$env:ANDROID_SERIAL='emulator-5554'; .\gradlew.bat :app:connectedDebugAndroidTest --tests '*NativePixelsTest*'`. **If it fails, stop and tell the owner** (spec §6.5): the H-path tests would then be skipped on the emulator.
- [ ] **Step 2: Write failing tests.** Rust host unit tests for `HandleRegistry`: ids are unique and non-zero, `get` after `remove` → `None`, and `remove` twice → `false`. Instrumented tests in `NativePixelsTest` use `formats-and-names.cbz` written by `CorpusGenerator` at scale 0.05 into the test cache, opened with `engine.openPath`:
  - `software_and_hardware_outputs_match`: full page 0 at fit 200×300 into both targets; copying the hardware bitmap to `ARGB_8888` gives identical pixels.
  - `output_is_not_blank`.
  - `region_at_source_scale_matches_crop`: region (10,10,50,50) at 50×50, max per-channel diff ≤ 2 against the same crop of a source-size render.
  - `rgb565_bitmap_is_invalid_request` (kind `"InvalidRequest"`).
  - `released_handle_is_invalid_handle`.
  - `cancelled_token_is_cancelled`.
- [ ] **Step 3: Run** both suites — Expected: FAIL.
- [ ] **Step 4: Implement** per Interfaces.
- [ ] **Step 5: Run** `cargo test --workspace` and `connectedDebugAndroidTest` (emulator) — Expected: pass. `assembleRelease` plus `check-16kb.ps1` still pass.
- [ ] **Step 6: Commit** (only if authorized): `git add core Cargo.toml Cargo.lock app && git commit -m "feat: JNI pixel path into Bitmap and HardwareBuffer"`

---

### Task 5: Opening files (SAF, Open with, copy fallback) and Home

**Files:**
- Create: `app/src/main/kotlin/com/proviewer/app/ProViewerApp.kt`, `engine/NativeBook.kt`, `open/BookOpener.kt`, `home/HomeScreen.kt`, `app/src/androidTest/AndroidManifest.xml`, `app/src/androidTest/kotlin/com/proviewer/app/TestCbzProvider.kt`, `app/src/androidTest/kotlin/com/proviewer/app/BookOpenerTest.kt`
- Modify: `app/src/main/AndroidManifest.xml` (`android:name=".ProViewerApp"`, `ACTION_VIEW` filter), `MainActivity.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`

**Interfaces:**
- Consumes: `Engine.openFd`, `Engine.openPath`, `EngineException.NotSeekable` (Task 1); `Book.nativeHandle`, `releaseBookHandle` (Task 4); `CorpusGenerator` (Task 3, tests only).
- Produces:
  - `class ProViewerApp : Application`: `val engine: Engine` (one per process, `defaultEngineConfig()`). `onCreate` deletes `cacheDir/opened/`.
  - `class NativeBook(val book: Book, val copiedFile: File?) : AutoCloseable`:
    - `val handle: Long` comes from `nativeHandle()`; `val pageCount: Int`; `fun pageInfo(i: Int): PageInfo` (memoized).
    - `close()` is idempotent and runs on `Dispatchers.IO`: release the handle, `book.destroy()`, delete `copiedFile`.
  - `class BookOpener(private val context: Context, private val engine: Engine)`, with `suspend fun open(uri: Uri, onCopyProgress: (copied: Long, total: Long?) -> Unit): OpenResult`:
    - `openFileDescriptor(uri, "r")` → `engine.openFd(pfd.detachFd())`.
    - On `NotSeekable`, or when `openFileDescriptor` throws or returns null: copy `openInputStream` into `cacheDir/opened/<uuid>.cbz`, then `openPath`. Before copying, check `StatFs` free space ≥ known size + 64 MiB when the size is known.
    - Coroutine cancellation deletes the partial copy.
  - `sealed interface OpenResult { data class Opened(val book: NativeBook, val title: String) : OpenResult; data class Failed(val reason: String) : OpenResult }`. `title` is the `OpenableColumns.DISPLAY_NAME` or the last path segment. `reason` is built from the variant name plus `detail`.
  - Manifest `ACTION_VIEW` filter on `MainActivity` (`DEFAULT`, `BROWSABLE` categories; schemes `content` and `file`; MIME types `application/zip`, `application/x-cbz`, `application/vnd.comicbook+zip`, `application/octet-stream`). Home's SAF picker uses `ActivityResultContracts.OpenDocument` with the first three MIME types.
  - `HomeScreen(onOpen: (Uri) -> Unit, error: String?, copyProgress: Float?, onCancelCopy: () -> Unit, benchmarkAvailable: Boolean, onBenchmark: () -> Unit)`. Test tags: `home-open`, `home-error`, `home-copy-progress`, `home-benchmark`.
  - `TestCbzProvider` (androidTest manifest, authority `com.proviewer.app.test.cbz`, exported) serves three URI forms. Files come from `CorpusGenerator` at scale 0.05.
    - `content://…/seekable/<name>`: a file PFD.
    - `/pipe/<name>`: through `ParcelFileDescriptor.createPipe()`, fed by a thread.
    - `/partial/<name>`: `openAssetFile` returns an `AssetFileDescriptor` with an explicit declared length. `openFileDescriptor` therefore throws ("Not a whole file"), while `openInputStream` still works, the same shape as asset-backed and some cloud providers.
  - After a successful open, `BookOpener` tries `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` in a `try`/`catch`. Nothing in Phase 2 depends on it.

- [ ] **Step 1: Write failing tests** in `BookOpenerTest`:
  - `seekable_opens_without_copy`: `mixed-small.cbz` → `Opened`, 20 pages, `copiedFile == null`.
  - `pipe_falls_back_to_copy`: `Opened`, `copiedFile` exists; after `close()` it is gone.
  - `provider_without_fd_falls_back_to_copy` (Review Focus 1).
  - `text_file_fails_with_reason` (→ `Failed`, reason contains `UnsupportedFormat`).
  - `cancel_during_copy_deletes_partial`.
  - `view_intent_opens_reader` launches `MainActivity` with `ACTION_VIEW` on a seekable URI; the reader tag `reader-root` appears (a placeholder reader node until Task 7).
- [ ] **Step 2: Run** `$env:ANDROID_SERIAL='emulator-5554'; .\gradlew.bat :app:connectedDebugAndroidTest` — Expected: new tests FAIL.
- [ ] **Step 3: Implement** per Interfaces. `MainActivity` keeps a simple screen state (`Home | Reader | Benchmark`), with no navigation library. `EngineHelloTest`'s version text may move to the Home footer; keep the tag `engine-version` (the Phase 0 macrobenchmark looks for `ProViewer core `).
- [ ] **Step 4: Run** the suite — Expected: pass.
- [ ] **Step 5: Commit** (only if authorized): `git add app gradle && git commit -m "feat(app): open CBZ through SAF and Open with, copy fallback"`

---

### Task 6: Render pipeline (upload strategies, page window, caps)

**Files:**
- Create: `app/src/main/kotlin/com/proviewer/app/engine/PageRenderer.kt`, `reader/ReaderLogic.kt`, `reader/PageWindow.kt`, `app/src/test/kotlin/com/proviewer/app/reader/ReaderLogicTest.kt`, `app/src/test/kotlin/com/proviewer/app/reader/PageWindowTest.kt`, `app/src/androidTest/kotlin/com/proviewer/app/PageRendererTest.kt`
- Modify: `app/build.gradle.kts` (`testImplementation(libs.junit)`, coroutines-test only if needed)

**Interfaces:**
- Consumes: `NativePixels` (Task 4), `NativeBook` (Task 5).
- Produces:
  - `ReaderLogic.kt` (pure, JVM-tested):
    - `enum class FitMode { Screen, Width, Height }`; `enum class Direction { Ltr, Rtl }`; `data class PageRegion(val x: Int, val y: Int, val w: Int, val h: Int, val outW: Int, val outH: Int)`.
    - `const val MAX_BASE_DIM = 4096`; `const val MAX_BASE_BYTES = 32L * 1024 * 1024`.
    - `fun baseSize(pageW: Int, pageH: Int, viewW: Int, viewH: Int, mode: FitMode): IntSize`: the fit size for `mode`, then scaled down uniformly until both dims ≤ `MAX_BASE_DIM` and `w*h*4 ≤ MAX_BASE_BYTES`; rounded, at least 1.
    - `enum class TapAction { Previous, Next, ToggleOverlay }`; `fun tapAction(x: Float, width: Float, dir: Direction): TapAction`: left third → Previous (Next in RTL), right third mirrored, middle → ToggleOverlay.
    - `fun keyAction(keyCode: Int): TapAction?`: `KEYCODE_VOLUME_DOWN` → Next, `KEYCODE_VOLUME_UP` → Previous, else null.
    - `fun windowPages(current: Int, count: Int, ahead: Int = 3, behind: Int = 1, forward: Boolean = true): List<Int>`: priority order is current, then ahead pages nearest first, then behind pages nearest first, all in range. `forward=false` swaps which side counts as ahead.
    - `fun visibleRegion(pageW: Int, pageH: Int, baseW: Int, baseH: Int, zoom: Float, contentLeft: Float, contentTop: Float, viewW: Int, viewH: Int): PageRegion?`. `contentLeft/Top` is the on-screen top-left of the base content after transform. The result is null when `zoom * baseW <= baseW * 1.001` and the base already matches the screen. Otherwise: the screen ∩ transformed content, mapped to page pixels (floor/ceil, clamped to the page), with out size = the on-screen size of that intersection.
  - `enum class UploadStrategy { Software, Hardware }`.
  - `sealed class PageImage { abstract val bitmap: Bitmap; abstract fun release() }` with `Software(bitmap)` and `Hardware(buffer: HardwareBuffer, bitmap)` (release closes the buffer).
  - `class PageRenderer(book: NativeBook, var strategy: UploadStrategy)`:
    - `suspend fun render(page: Int, region: PageRegion): PageImage` runs on a dispatcher of parallelism 2. Coroutine cancellation calls `NativePixels.cancel` on the call's token and releases a half-made target.
    - Hardware: if allocation or wrap fails, render Software and set `var fellBack: Boolean`.
  - `interface PageSourceRenderer { suspend fun renderBase(page: Int, size: IntSize): PageImage; fun pageSize(page: Int): IntSize?; fun prefetchHint(current: Int, forward: Boolean, viewW: Int, viewH: Int) }`: the seam that `PageWindow` uses, faked in JVM tests. `PageRenderer` implements it:
    - `renderBase` = `render(page, PageRegion(0, 0, pageW, pageH, size.width, size.height))`.
    - `pageSize` comes from `NativeBook.pageInfo`; null when the probe fails.
    - `prefetchHint` maps to `Book.prefetchHint`.
  - `class PageWindow(scope: CoroutineScope, renderer: PageSourceRenderer, ahead: Int = 3, behind: Int = 1)`:
    - `val pages: SnapshotStateMap<Int, PageSlot>`, with `sealed interface PageSlot { object Loading; data class Ready(val image: PageImage, val size: IntSize); data class Failed(val reason: String) }`.
    - `fun update(current: Int, forward: Boolean, viewW: Int, viewH: Int, mode: FitMode)`:
      - cancels jobs for pages outside the window, or sized for another viewport or mode;
      - releases evicted images;
      - starts the missing ones in `windowPages` order;
      - calls `prefetchHint` once.
    - Non-current Software images get `prepareToDraw()` when ready.
    - `suspend fun awaitIdle()`; `fun close()` releases everything.

- [ ] **Step 1: Write failing JVM tests:**
  - `ReaderLogicTest`:
    - `baseSize`: a 800×1200 page in 1080×2378 Screen → 1080×1620. `base_size_caps_tall_and_wide_pages`: 800×24000 in Width → max dim ≤ 4096 and bytes ≤ 32 MiB; 3200×2400 in Height on a 2378×1080 viewport → within caps.
    - `tapAction` for all three zones × both directions.
    - `keyAction`.
    - `windowPages(5, 20)` = `[5,6,7,8,4]`; `windowPages(5, 20, forward=false)` = `[5,4,3,2,6]`; `windowPages(0, 2)` = `[0,1]`.
    - `visibleRegion`: zoom 1 → null; zoom 2 centred on a 1080×1620 base of a 1600×2400 page in a 1080×2378 view → region 400×≈593 around the centre, out size 1080 wide.
  - `PageWindowTest` (fake renderer with controllable completion):
    - `renders_window_in_priority_order`.
    - `rapid_jumps_render_only_final_window`: update 0..99 in a tight loop on a 500-page fake; only pages of the final window complete; at most 5 slots held.
    - `viewport_change_cancels_and_rerenders`: in-flight jobs for the old size are cancelled, and no slot is `Ready` with the old size afterwards.
    - `evicted_images_are_released`.
    - `failed_page_is_failed_slot`.
- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest` — Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Instrumented** `PageRendererTest` (emulator): `hardware_strategy_renders` (not `fellBack` on the emulator; Task 4 Step 1 proved support), `software_strategy_renders`, and `cancel_releases_target` (a cancelled render leaves no open `HardwareBuffer`, counted by a debug counter in `PageRenderer`).
- [ ] **Step 5: Run** JVM and instrumented suites — Expected: pass.
- [ ] **Step 6: Commit** (only if authorized): `git add app && git commit -m "feat(app): render pipeline with page window and upload strategies"`

---

### Task 7: Reader UI (pager, tap zones, keys, overlay, fit modes)

**Files:**
- Create: `reader/ReaderViewModel.kt`, `reader/ReaderScreen.kt`, `reader/ReaderProbe.kt`, `app/src/androidTest/kotlin/com/proviewer/app/ReaderScreenTest.kt`
- Modify: `MainActivity.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts` (viewmodel-compose, foundation)

**Interfaces:**
- Consumes: `PageWindow`, `PageRenderer`, `ReaderLogic` (Task 6); `OpenResult.Opened` (Task 5).
- Produces:
  - `data class ReaderState(val title: String, val page: Int, val count: Int, val direction: Direction, val fitMode: FitMode, val overlayVisible: Boolean)`.
  - `class ReaderViewModel : ViewModel` owns `NativeBook`, `PageRenderer` and `PageWindow`, and survives rotation. Methods: `fun attach(opened: OpenResult.Opened, strategy: UploadStrategy = UploadStrategy.Hardware)`, `fun goTo(page: Int)`, `fun next()`, `fun previous()`, `fun setDirection(d)`, `fun setFitMode(m)`, `fun toggleOverlay()`, `fun onViewport(w, h)`. `onCleared` closes the window and the book on `Dispatchers.IO`.
  - `interface ReaderProbe { fun onSharpPageDrawn(page: Int, drawNanos: Long) {}; fun onDetailRender(page: Int, event: String) {} }` with `val LocalReaderProbe = staticCompositionLocalOf<ReaderProbe> { object : ReaderProbe {} }`. The draw callback fires for the page's `Ready` base bitmap only, never for a placeholder.
  - `interface ReaderAutomation { suspend fun turnTo(page: Int, animate: Boolean); suspend fun awaitIdle(); val zoom: ZoomAutomation? }` delivered through `ReaderScreen(onAutomation: (ReaderAutomation) -> Unit = {})`. Also in `ReaderProbe.kt`: `interface ZoomAutomation { suspend fun zoomTo(factor: Float, centroid: Offset, animate: Boolean); suspend fun panBy(delta: Offset, animate: Boolean); suspend fun reset() }`. In this task `zoom` is null; Task 8 implements the interface.
  - `ReaderScreen(vm: ReaderViewModel, onBack: () -> Unit, onAutomation: …)`:
    - `HorizontalPager` with `reverseLayout = (direction == Rtl)` and `beyondViewportPageCount = 1`.
    - Tap zones through `tapAction`; overlay with title, back, slider, `n / total`, direction and fit toggles.
    - Immersive bars hidden except while the overlay shows, `keepScreenOn`.
    - A `Failed` slot shows a placeholder with the page number and reason. A `Loading` slot shows a neutral background.
    - Test tags: `reader-root`, `reader-page-<n>`, `reader-overlay`, `reader-slider`, `reader-dir`, `reader-fit`, `reader-error-<n>`.
  - `MainActivity` forwards volume keys to `keyAction` only while the reader is shown, and consumes them there.

- [ ] **Step 1: Write failing tests** in `ReaderScreenTest` (`mixed-small.cbz` at scale 0.05, opened through `TestCbzProvider`):
  - `tap_zones_ltr` and `tap_zones_rtl` (right third → next in LTR, previous in RTL).
  - `volume_keys_turn_pages` (down → page 1, up → page 0, any direction).
  - `swipe_turns_page_in_reading_direction`.
  - `centre_tap_toggles_overlay`.
  - `slider_jumps`.
  - `fit_mode_change_rerenders_base`: switching to Width changes the `Ready` size to `baseSize(…, Width)`.
  - `corrupt_page_shows_placeholder_and_next_loads`: a test CBZ built with `java.util.zip` holding a valid JPEG and a garbage `002.jpg`.
  - `rotation_keeps_page_and_rerenders` (`ActivityScenario.recreate()` plus a viewport swap via `requestedOrientation`; page kept, base size matches the new viewport).
  - `tall_page_fit_width_displays` (`webtoon-long.cbz` at scale 0.1).
  - `back_during_render_releases_cleanly`: open `huge-single.cbz` at scale 0.25, press back immediately; no crash, the handle registry has no entry for the book (check through `releaseBookHandle(handle) == false` after close), and no `InvalidHandle` surfaces in the UI.
- [ ] **Step 2: Run** `connectedDebugAndroidTest` (emulator) — Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces. Default strategy: `UploadStrategy.Hardware` (spec §6.3).
- [ ] **Step 4: Run** the suite — Expected: pass.
- [ ] **Step 5: Commit** (only if authorized): `git add app gradle && git commit -m "feat(app): single-page reader with LTR/RTL, tap zones, volume keys, fit modes"`

---

### Task 8: Zoom and detail re-render

**Files:**
- Create: `reader/ZoomablePage.kt`, `app/src/androidTest/kotlin/com/proviewer/app/ZoomTest.kt`
- Modify: `reader/ReaderScreen.kt`, `gradle/libs.versions.toml` (`telephoto = "0.19.0"`), `app/build.gradle.kts`

**Interfaces:**
- Consumes: `visibleRegion`, `PageRenderer.render` (Task 6), `ReaderProbe`, `ReaderAutomation` (Task 7).
- Produces:
  - `ZoomablePage(slot: PageSlot.Ready, pageSize: IntSize, renderer: PageRenderer, probe: ReaderProbe)`. It uses `rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = max(4f, pageW / baseW.toFloat())))` and `Modifier.zoomable(state, onDoubleClick = …)`. Content location = the base size, centred (Screen) or top/left aligned (Width/Height).
  - Double tap: zoom to 2× around the tap point, or back to 1× if already zoomed.
  - Detail render: `snapshotFlow` of the content transformation, debounced **150 ms** (the settle). Then `visibleRegion` → null clears the detail; otherwise it cancels the previous detail job and renders the new region into one detail `PageImage`, drawn at the region's on-screen rect over the base. Probe events: `"start"`, `"done"`, `"cancelled"`. A new transformation cancels a pending or running detail job.
  - Telephoto's pager integration gives pan-to-edge-then-turn; the transforms are draw-phase only, with no recomposition per frame.
  - Implements `ZoomAutomation` (Task 7) for the current page, exposed through `ReaderAutomation.zoom`.
  - Telephoto API names are those of 0.19.0. If a name differs (for example how the zoom limit is expressed), use that version's equivalent; the behaviour above is what the tests pin.

- [ ] **Step 1: Write failing tests** in `ZoomTest` (`screentone.cbz` at scale 0.25; probe events recorded):
  - `fit_scale_has_no_detail_render`.
  - `double_tap_zooms_and_renders_one_detail`: exactly one `"start"`/`"done"` pair after the settle, and the detail bitmap size equals the screen size of the visible region.
  - `new_gesture_cancels_pending_detail`: two `zoomTo` calls 50 ms apart → one `"done"`, and the first is `"cancelled"` or never started.
  - `double_tap_again_returns_to_fit`: zoom 1, detail cleared.
  - `pan_at_edge_turns_page`: zoomed 2× and panned to the right edge, a further swipe turns the page.
  - `max_zoom_reaches_source_pixels` on `huge-single.cbz` at scale 0.25.
- [ ] **Step 2: Run** `connectedDebugAndroidTest` (emulator) — Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** the suite — Expected: pass, Task 7 tests included.
- [ ] **Step 5: Commit** (only if authorized): `git add app gradle && git commit -m "feat(app): zoom with sharp detail re-render"`

---

### Task 9: Benchmark measurement core (frames, latency, conditions, JSON)

**Files:**
- Create: `app/src/bench/kotlin/com/proviewer/app/bench/FrameRecorder.kt`, `BenchMath.kt`, `RunConditions.kt`, `BenchmarkJson.kt`, `app/src/test/kotlin/com/proviewer/app/bench/BenchMathTest.kt`, `BenchmarkJsonTest.kt`
- Modify: `app/build.gradle.kts` (`buildConfig = true`; `GIT_SHA` from `git rev-parse --short HEAD` via `providers.exec`, `"unknown"` on failure; `testDebugUnitTest` sees `src/bench/kotlin` because the debug variant compiles it)

**Interfaces:**
- Produces:
  - `data class FrameSample(val intendedVsyncNs: Long, val totalNs: Long, val deadlineNs: Long, val firstDraw: Boolean)`.
  - `class FrameRecorder(window: Window)` with `fun start()` and `fun stop(): List<FrameSample>`. It uses `addOnFrameMetricsAvailableListener` on a `HandlerThread`, and `val supported = Build.VERSION.SDK_INT >= 31`.
  - `BenchMath.kt` (pure):
    - `data class JankSummary(val frames: Int, val janky: Int, val jankyPct: Double, val p50Ms: Double, val p95Ms: Double, val p99Ms: Double)`; `fun jankSummary(samples: List<FrameSample>): JankSummary`. First-draw frames are excluded, and a frame is janky when `totalNs > deadlineNs`. Percentiles use nearest-rank on `totalNs`.
    - `fun frameEndContaining(drawNs: Long, frames: List<FrameSample>): Long?`: the end (`intendedVsyncNs + totalNs`) of the first frame with `intendedVsyncNs ≤ drawNs ≤ intendedVsyncNs + totalNs`, else null.
    - `data class LatencySummary(val n: Int, val p50Ms: Double, val p95Ms: Double, val maxMs: Double)`; `fun latencySummary(ms: List<Double>): LatencySummary`.
  - `RunConditions.kt`:
    - `data class Conditions(val model: String, val manufacturer: String, val sdkInt: Int, val release: String, val isEmulator: Boolean, val refreshHz: Float, val maxRefreshHz: Float, val thermalStatus: Int, val batteryPct: Int, val charging: Boolean)`; `fun captureConditions(activity: Activity): Conditions`. `isEmulator` = `Build.HARDWARE` in `ranchu`/`goldfish`, or `Build.FINGERPRINT` contains `generic`.
    - `class ThermalGuard(context)`: `fun canStart(): Boolean` (status < `THERMAL_STATUS_MODERATE`) and `val tripped: StateFlow<Boolean>` from `addThermalStatusListener`.
  - `BenchmarkJson.kt`:
    - `data class ScenarioResult(val id: String /* "a2" | "a3-screentone" | "a3-huge" | "b" */, val strategy: UploadStrategy, val order: Int, val refreshBeforeHz: Float, val refreshAfterHz: Float, val thermalEnd: Int, val frames: JankSummary?, val cold: LatencySummary?, val prefetched: LatencySummary?, val fellBackToSoftware: Boolean, val aborted: String?)`.
    - `data class BenchmarkResult(val conditions: Conditions, val appVersion: String, val gitSha: String, val corpusScale: Float, val scenarios: List<ScenarioResult>)`.
    - `fun toJson(r: BenchmarkResult): String`. Schema: `schemaVersion: 1` and `generator: CorpusGenerator.MARKER`. Per scenario, derived flags: `rateChanged = before != after` and `belowMaxRate = before < maxRefreshHz - 0.5`. Top-level `validForPerformance = !isEmulator && corpusScale == 1f && no scenario rateChanged or aborted`.

- [ ] **Step 1: Write failing JVM tests:**
  - `BenchMathTest`:
    - 100 frames with 1 over deadline → 1.0 %; first-draw frames excluded; percentiles on a known list.
    - `frameEndContaining` inside, at the boundaries, and no match → null.
    - `latencySummary` on `[10, 20, 30, 40]` gives n=4, p50=20, max=40.
  - `BenchmarkJsonTest`: a round trip through `JSONObject` has every field above. `validForPerformance` is false for an emulator, false for scale 0.25, and false when a scenario has `rateChanged`.
- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest` — Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** — Expected: pass.
- [ ] **Step 5: Commit** (only if authorized): `git add app && git commit -m "feat(bench): frame, latency and run-condition measurement"`

---

### Task 10: Benchmark scenarios and screen

**Files:**
- Create: `app/src/bench/kotlin/com/proviewer/app/bench/Scenarios.kt`, `BenchmarkRunner.kt`, `BenchmarkScreen.kt`, `app/src/debug/AndroidManifest.xml` (FileProvider), `app/src/debug/res/xml/bench_paths.xml`, `app/src/benchmark/res/xml/bench_paths.xml`, `app/src/androidTest/kotlin/com/proviewer/app/BenchmarkRunTest.kt`
- Modify: `app/src/bench/kotlin/com/proviewer/app/bench/VariantFeatures.kt`, `app/src/benchmark/AndroidManifest.xml` (FileProvider), `MainActivity.kt` (Benchmark screen state, `onAutomation` wiring)

**Interfaces:**
- Consumes: `ReaderAutomation`, `ZoomAutomation`, `LocalReaderProbe` (Tasks 7–8); `ReaderViewModel` with an explicit `UploadStrategy`; everything from Task 9; `CorpusGenerator.ensure` (Task 3).
- Produces:
  - `class BenchmarkRunner(activity: ComponentActivity, scale: Float = 1f)`, with `suspend fun run(onProgress: (String) -> Unit): BenchmarkResult`:
    - Refuses to start when `!ThermalGuard.canStart()`; aborts the current scenario (sets `aborted`) when the guard trips.
    - Order: Software then Hardware on odd runs, reversed on even runs (run counter in `SharedPreferences`).
    - Hosts the real `ReaderScreen` full screen for each scenario.
  - Scenarios (`Scenarios.kt`), each run per strategy:
    - `a2`: open `mixed-small.cbz`, fit Screen, `awaitIdle`; record frames during page turns `turnTo(p+1, animate=true)` every **450 ms**, 0→19, then back 19→0.
    - `a3-screentone`, `a3-huge`: 3 cycles of `zoomTo(3f, centre, animate)` (then wait 300 ms), `panBy` to 3 positions (300 ms each), and `reset()`. Frames are recorded across the whole cycle.
    - `b` cold: 10 samples. Each opens a fresh book, waits until page 0 is idle, then `turnTo(k, animate=false)` with `k` cycling 5, 10, 15. t0 = `System.nanoTime()` just before the call. t1 = `frameEndContaining(drawNs)` of the first `onSharpPageDrawn(k)`; if null, the draw time is used and the sample is flagged.
    - `b` prefetched: 19 samples of `turnTo(p+1, animate=true)` after `awaitIdle()`.
    - Target total ≤ 3 min at scale 1 (spec §6.4). Log scenario durations so the owner run can check it.
  - `BenchmarkScreen`:
    - A Start button (disabled with the reason when the thermal check fails) and live progress.
    - A results table per scenario × strategy (frames, janky %, p50/p95/p99, latency classes), with "emulator, not valid for performance" or "rate changed" flags shown.
    - "Share JSON": writes `filesDir/bench/result-<timestamp>.json` and shares it through `FileProvider` (authority `${applicationId}.benchfiles`) with `ACTION_SEND`, type `application/json`.
  - Test tags: `bench-start`, `bench-progress`, `bench-results`, `bench-share`.

- [ ] **Step 1: Write failing test** `BenchmarkRunTest.full_run_produces_valid_json` (emulator, `scale = 0.25`):
  - Run the whole runner.
  - The JSON parses with `schemaVersion == 1`, has all 4 scenario ids × 2 strategies, `isEmulator == true` and `validForPerformance == false`.
  - Every a2/a3 entry has `frames.frames > 0` (on API 37). The `b` entries have `cold.n == 10` and `prefetched.n == 19`.
  - Plus `thermal_refusal_blocks_start`, using an injectable `ThermalGuard` fake.
- [ ] **Step 2: Run** `connectedDebugAndroidTest` (emulator) — Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** the suite — Expected: pass (emulator numbers are ignored).
- [ ] **Step 5: Commit** (only if authorized): `git add app && git commit -m "feat(bench): in-app benchmark scenarios, results and JSON share"`

---

### Task 11: Docs, fuzz reruns, final verification

**Files:**
- Create: `docs/perf/phase2-reader.md`
- Modify: `docs/perf/budgets.md`, `docs/superpowers/specs/2026-11-18-proviewer-design.md` (§7, §8 Phase 2 row)

- [ ] **Step 1: Update `budgets.md`:**
  - a2, a3 and b keep their targets and stay `approved`. "How measured" becomes the in-app Benchmark screen (spec §6.4). Janky means `TOTAL_DURATION > DEADLINE` (FrameMetrics, first-draw frames excluded). a3 is scripted. b's t0/t1 definitions go in too.
  - Replace the "Open conflict" note with D1/D2: the real-device run is a checkpoint the owner starts, and Phase 2 exit is functional.
  - Note that the `perf-capture.ps1` method remains for reference only.
- [ ] **Step 2: Update parent spec** §7 (in-app method for a2/a3/b, with a pointer to the Phase 2 spec) and the §8 Phase 2 completion cell ("functional on emulator; device benchmark is an owner checkpoint").
- [ ] **Step 3: Fuzz** (WSL preflight first, nothing installed): `scripts/fuzz.ps1 -Target decode -Seconds 900 -LogFile <tmp>` and `scripts/fuzz.ps1 -Target archive -Seconds 900 -LogFile <tmp>`, sequentially. Regenerate the seed corpus first (`cargo run --release -p gen-corpus -- --fuzz-seeds <seed dir used by fuzz.ps1>`), so the damaged JPEG seeds are included. Expected: both exit 0 with no artifact. Record execs, duration, coverage, features, corpus size, peak RSS and crashes, plus the commit hash, in `docs/perf/phase2-reader.md` (same table as `phase1-engine.md`).
- [ ] **Step 4: Full verification** (emulator only):
  - `cargo test --workspace`.
  - `.\gradlew.bat :app:testDebugUnitTest`.
  - `$env:ANDROID_SERIAL='emulator-5554'; .\gradlew.bat :app:connectedDebugAndroidTest`.
  - `.\gradlew.bat :app:assembleRelease :app:assembleBenchmark`.
  - `check-16kb.ps1` on both APKs.
  - `check-release-clean.ps1` exits 0 on release and 1 on benchmark.
  - Expected: all green. Record the results and the exit status per spec §6.5 in `phase2-reader.md`.
- [ ] **Step 5: Hand-off to the owner:** report the absolute path of `app/build/outputs/apk/benchmark/app-benchmark.apk`. The owner installs it themselves to read a real CBZ, and can run the Benchmark screen whenever they choose. **Do not install or run anything on the phone.**
- [ ] **Step 6: Commit** (only if authorized): `git add docs && git commit -m "docs: phase 2 measurement method, fuzz record, exit status"`
