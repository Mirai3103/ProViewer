# ProViewer Phase 1 — Engine MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A desktop-testable Rust engine that opens image folders and CBZ/ZIP files safely, decodes JPEG/PNG/WebP, resizes in linear light, serves page tiles through an LRU cache with prefetch/cancel, plus the codec comparison, fuzzing, and on-device numbers that set the last two performance budgets.

**Architecture:** Everything lives in `proviewer_core` as small modules: `archive` (page sources), `decode`, `resize`, `cache`, `scheduler`, `engine` (public `Engine`/`Book` API). Visible tiles are rendered **synchronously on the caller's thread** (Phase 2 calls this from a Kotlin background coroutine; the caller's thread *is* the high-priority lane). Prefetch runs on a small **low-priority background pool** and only warms the decoded-page cache. Cancellation is a per-call `CancelToken` for visible work and a generation counter for prefetch. The control API is exported through uniffi now. The pixel call (`render_tile` into a caller-owned `&mut [u8]` with a stride) is Rust-only and gets its JNI wrapper in Phase 2. The JPEG backend is picked by a measured codec comparison (Task 3) before `decode` is written.

**Tech Stack:** Rust 1.100 stable (host `x86_64-pc-windows-msvc`, device `aarch64-linux-android` via `cargo-ndk` 4.1.2). Crates: `zip` (already 9.7.0), `fast_image_resize`, `png`, `image-webp`, `imagesize`, `thiserror`, `uniffi` 0.32.2, JPEG backend chosen in Task 3 from `zune-jpeg` / `jpeg-decoder` / `turbojpeg`. Dev crates: `criterion` 0.8.2, `proptest`, `tempfile`, `image`, `jpeg-encoder`, `gen-corpus` (path). Fuzzing: `cargo-fuzz` on nightly inside a dedicated WSL distro `proviewer` (Ubuntu 26.04, stored on `E:\WSL\proviewer`). Platform-decoder baseline: AndroidX Microbenchmark.

**Spec:** `docs/superpowers/specs/2026-11-18-proviewer-design.md` (§3, §5, §6, §7, §8 "Phase 1", §9). Phase 0 plan: `docs/superpowers/plans/2026-11-18-phase-0-foundations.md`. Budgets: `docs/perf/budgets.md`.

## Global Constraints

- Phase 1 has **no reader UI** (spec §8). The app must still build and pass its Phase 0 instrumented test.
- Release profile keeps **`panic = "unwind"`** (spec §5). Never add `panic = "abort"`.
- Every `.so` in the APK stays **16 KB-aligned**. `scripts/check-16kb.ps1` must still pass after the new core dependencies are added (spec §3).
- **Untrusted input:** enforce limits *before* allocating; reject or downscale on decode rather than allocate full size (spec §5).
- Resize happens in **linear light** (sRGB → linear → resize → sRGB) with a convolution filter (Lanczos3). No nearest/bilinear/mipmap shortcuts on the quality path (spec §5).
- **Performance numbers come only from the reference device** (adb serial `3B65BT02H6G00000`, OnePlus CPH2767). The emulator (`emulator-5554`) is often attached at the same time. Every device command must pass `-s <serial>`, and no script may fall back to the emulator for numbers. Host (Windows) numbers are for wiring checks only (spec §3).
- Measurement viewport is **1080×2378**, the display mode used for all measurements (`docs/perf/reference-device.md`).
- Budgets are tightened only with owner approval and never loosened silently (spec §7). Rows c and d of `docs/perf/budgets.md` get their numbers in this phase (Task 10).
- Pin exact dependency versions. Cargo.lock is committed. Versions shared by several crates go in `[workspace.dependencies]`, so move `zip`, `image`, `tempfile` there and switch `gen-corpus` to `workspace = true`. Workspace `members` stays an explicit list. `core/fuzz` goes in `exclude` because it needs nightly and Linux.
- All docs and plans are in **English** (spec §3). Dev host is Windows 11. Scripts are PowerShell, run as `powershell -NoProfile -ExecutionPolicy Bypass -File <script>`. The only exception is commands run inside WSL by `scripts/fuzz.ps1`.
- **Commit only when the owner asks.** Ask once at the start whether commits are authorized for this execution. Every task ends with a commit step that runs only if they are. Work on branch `phase-1`, created from `master`.
- Machine facts found while planning: `cmake` and `nasm` are not on PATH and the SDK has no `cmake/` package. WSL 3.0.5 (16 cores, ~7 GB RAM visible to WSL). Fuzzing uses a dedicated distro `proviewer` at `E:\WSL\proviewer`, set up **before** execution with `docs/dev/wsl-proviewer-setup.md` (not part of this plan); the owner's `Ubuntu` and `kiri` distros are not used. Python 3.13 is available. The corpus is already generated in `corpus/generated/` (`many-pages.cbz` is 238 MB).

## Review Focus

1. **Real-world CBZ junk entries:** `__MACOSX/` folders, AppleDouble `._001.jpg` files, `.DS_Store`, `Thumbs.db`, `ComicInfo.xml`, directory entries, and zero-byte files must be skipped and never shown as broken pages. Pinned in Task 2 (`zip_lists_only_pages_in_natural_order`).
2. **Non-UTF-8 or Windows-style entry names:** names without the UTF-8 flag (often raw UTF-8 or Shift-JIS) or with `\` separators must not fail `open`, and numeric order must still hold. Pinned in Task 2 (`utf8_name_without_flag_decodes_as_utf8`, `backslash_separators_sort_and_normalize`).
3. **Page turn while prefetch is decoding the same page:** expected one decode, no deadlock, and both callers get the same image. Pinned in Task 7 (`concurrent_get_or_decode_decodes_once`) and Task 8 (`render_during_prefetch_does_not_double_decode`).
4. **Format and extension mismatch, plus common JPEG variants:** a `.jpg` that is really PNG/WebP, progressive JPEG, and CMYK (Adobe) JPEG must decode correctly. Pinned in Task 4 (`decode_sniffs_content_not_extension`, `progressive_jpeg_decodes`, `cmyk_jpeg_decodes_to_rgb`).
5. **Lying ZIP sizes:** a central directory that declares 100 bytes for an entry that inflates to megabytes must end in a bounded read and an error, not an unbounded allocation. Pinned in Task 2 (`lying_declared_size_is_bounded`) and by the archive fuzz target (Task 5, `-malloc_limit_mb`).

## File Structure

```
Cargo.toml                         + workspace deps (zip, image, tempfile, …), members += tools/codec-bench, tools/pvcli; exclude core/fuzz
core/Cargo.toml                    engine deps; dev-deps gen-corpus, image, jpeg-encoder, proptest, tempfile
core/src/lib.rs                    module decls, re-exports, uniffi scaffolding, engine_version
core/src/error.rs                  EngineError (uniffi::Error)
core/src/limits.rs                 Limits (uniffi::Record) + defaults
core/src/natsort.rs                natural_cmp, natural_path_cmp
core/src/archive/mod.rs            PageSource trait, PageEntry, is_page_name, open_path
core/src/archive/zip.rs            ZipSource
core/src/archive/folder.rs         FolderSource
core/src/decode/mod.rs             ImageFormat, PageInfo, PixelLayout, DecodedImage, sniff, probe, decode, supported_denoms
core/src/decode/{jpeg,png,webp}.rs per-format backends
core/src/resize.rs                 Rect, choose_denom, render_region (linear light → RGBA8888)
core/src/cache.rs                  DecodeCache (byte-budget LRU + in-flight dedup)
core/src/scheduler.rs              PrefetchPool (generations, priorities, low OS priority)
core/src/engine.rs                 Engine, Book, EngineConfig, EngineStats, TileRequest, ReadDirection, CancelToken, fit_size
core/tests/common/mod.rs           shared small-scale corpus fixture (gen-corpus, OnceLock)
core/tests/archive_corpus.rs  core/tests/engine_api.rs  core/tests/fuzz_regressions.rs
core/tests/fixtures/lossy.webp     ≤ 8 KB lossy WebP (committed; made with Pillow)
core/benches/engine.rs             criterion benches (replaces Phase 0 skeleton)
core/fuzz/                         cargo-fuzz crate: fuzz_targets/{archive,decode}.rs (excluded from workspace)
tools/gen-corpus/src/main.rs       + `--fuzz-seeds <dir>` mode
tools/codec-bench/                 JPEG codec comparison binary (inputs generated in-process; `--export`)
tools/pvcli/                       desktop/device CLI: info, render, read
codecbench/                        Android library module: platform decoder Microbenchmark
scripts/device-run.ps1             build for arm64, push, run on the reference device, capture output
scripts/fuzz.ps1                   run cargo-fuzz inside WSL Ubuntu
docs/perf/codec-comparison.md      codec numbers + decision
docs/perf/phase1-engine.md         engine bench / pvcli numbers (host + device)
docs/perf/budgets.md               rows c, d filled (proposed → owner approves)
.gitignore                         + core/fuzz/{artifacts,corpus,target}/, codecbench inputs dir
```

---

### Task 1: Error, limits, natural sort

**Files:**
- Create: `core/src/error.rs`, `core/src/limits.rs`, `core/src/natsort.rs`
- Modify: `core/src/lib.rs`, `core/Cargo.toml`, `Cargo.toml`, `tools/gen-corpus/Cargo.toml`

**Interfaces:**
- Produces:
  - `#[derive(Debug, Clone, PartialEq, thiserror::Error, uniffi::Error)] pub enum EngineError { Io { message: String }, UnsupportedFormat { message: String }, Corrupt { message: String }, LimitExceeded { limit: String, value: u64, max: u64 }, NoPages, PageOutOfRange { index: u32, count: u32 }, InvalidRequest { message: String }, Cancelled }`, plus `impl From<std::io::Error>` (→ `Io`). `pub type Result<T> = std::result::Result<T, EngineError>`.
  - `#[derive(uniffi::Record, Clone, Copy, Debug, PartialEq, Eq)] pub struct Limits { pub max_entries: u32, pub max_entry_bytes: u64, pub max_total_bytes: u64, pub max_image_pixels: u64, pub max_image_dimension: u32 }`. `Default` = `100_000`, `256 MiB`, `16 GiB`, `100_000_000`, `65_535`. The pixel default fits `huge-single` (96 MP) and keeps the largest RGB allocation near 300 MB.
  - `pub fn natural_cmp(a: &str, b: &str) -> Ordering` compares one path component. It splits into ASCII-digit runs and other runs. Non-digit runs compare case-insensitively (per-char `to_lowercase`). Digit runs compare by value without parsing into an integer: strip leading zeros, then shorter is smaller, then lexicographic. That way 30-digit runs never overflow. If all chunks are equal, fewer leading zeros sorts first, then raw bytes. This gives a **total order**.
  - `pub fn natural_path_cmp(a: &str, b: &str) -> Ordering` splits on `/` **and** `\`, compares component-wise with `natural_cmp`, and treats a proper prefix as smaller. The final tie-break is raw bytes of the whole string.

- [ ] **Step 1: Write failing tests** (unit tests in each file):
  - `natsort`: `["10.jpg","2.jpg","1.jpg"]` sorts to `1,2,10`. `"a2" < "B1"`. `"page1" < "page01"` and they are not equal. `"x99999999999999999999999" < "x100000000000000000000000"`, with no panic. `"第3話.jpg" < "第10話.jpg"`. Paths `["b/1.jpg","a/10.jpg","a b/1.jpg","a/2.jpg"]` sort to `a/2.jpg, a/10.jpg, a b/1.jpg, b/1.jpg`. `"ch1\\2.jpg" < "ch1/10.jpg"`.
  - `natsort` proptest `natural_path_cmp_is_total_order`: for arbitrary strings (include digits, `/`, `\`, non-ASCII), the comparison is antisymmetric, `Equal` only when `a == b`, and transitive on triples.
  - `limits`: `Limits::default()` equals the values above.
  - `error`: `EngineError::from(io::Error::new(NotFound, "x"))` is `Io { .. }` and the message contains `x`.
- [ ] **Step 2: Run** `cargo test -p proviewer_core` — Expected: FAIL (items not defined).
- [ ] **Step 3: Implement** the three modules. Add `thiserror` to `core`. Add `proptest` as a dev-dependency. Move `zip`/`image`/`tempfile` versions into `[workspace.dependencies]` and point `gen-corpus` at them.
- [ ] **Step 4: Run** `cargo test --workspace` — Expected: all pass (including gen-corpus and framestat).
- [ ] **Step 5: Commit** (only if authorized): `git add Cargo.toml Cargo.lock core tools/gen-corpus/Cargo.toml && git commit -m "feat(core): errors, limits, natural sort"`

---

### Task 2: Page sources (folder, ZIP) with input limits

**Files:**
- Create: `core/src/archive/mod.rs`, `core/src/archive/zip.rs`, `core/src/archive/folder.rs`, `core/tests/common/mod.rs`, `core/tests/archive_corpus.rs`
- Modify: `core/src/lib.rs`, `core/Cargo.toml` (deps `zip`; dev-deps `gen-corpus` path, `tempfile`)

**Interfaces:**
- Consumes: `EngineError`, `Limits`, `natural_path_cmp` (Task 1).
- Produces:
  - `pub struct PageEntry { pub name: String /* '/'-separated */, pub source_index: usize, pub declared_size: u64 }`
  - `pub trait PageSource: Send + Sync { fn pages(&self) -> &[PageEntry]; fn read(&self, page: usize) -> Result<Vec<u8>>; fn read_prefix(&self, page: usize, max_len: usize) -> Result<Vec<u8>>; }`. `pages()` holds only page entries, in natural order. Limits are stored in the source.
  - `pub fn is_page_name(name: &str) -> bool` checks the extension case-insensitively against `jpg jpeg jfif png webp`. It returns false if any path component starts with `.` or equals `__MACOSX`.
  - `ZipSource::from_reader<R: Read + Seek + Send + 'static>(reader: R, limits: Limits) -> Result<ZipSource>`. Behaviour:
    - Fails with `LimitExceeded{limit:"entries"}` if the central directory count exceeds `max_entries`.
    - Fails with `LimitExceeded{limit:"total_bytes"}` if the sum of declared uncompressed sizes exceeds `max_total_bytes`.
    - Skips directories, zero declared size, and non-page names.
    - Names come from `name_raw()`: valid UTF-8 is used even without the flag, otherwise the crate's decoded name. `\` is normalized to `/`.
    - Fails with `NoPages` if nothing is left.
    - The archive sits behind a `Mutex`. Only the byte read happens under the lock; decode always happens outside it.
  - `ZipSource::read` behaviour:
    - Returns `LimitExceeded{limit:"entry_bytes"}` if the declared size exceeds `max_entry_bytes`. One bad page does not fail the whole book.
    - Otherwise reads through `take(declared + 1)` into a `Vec` pre-sized to `min(declared, max_entry_bytes)`.
    - If more than `declared` bytes arrive, returns `Corrupt` (Review Focus 5).
  - `FolderSource::open(dir: &Path, limits: Limits) -> Result<FolderSource>`: recursive to a **max depth of 4** below `dir`. It never follows symlinks, uses the same name filter and the same limits, and its names are relative paths joined with `/`.
  - `pub fn open_path(path: &Path, limits: Limits) -> Result<Arc<dyn PageSource>>`: a directory → `FolderSource`. A file whose first bytes are `PK\x03\x04` or `PK\x05\x06` → `ZipSource` (the extension is not required). Anything else → `UnsupportedFormat`.
  - Test fixture `core/tests/common/mod.rs`: `pub fn small_corpus() -> &'static Path` generates `gen_corpus::generate_with_scale(dir, 0.05)` once per test binary (`OnceLock` + a `TempDir` kept alive). Later tasks reuse it.

- [ ] **Step 1: Write failing tests** (unit tests in `archive/zip.rs` build archives in memory with `zip::ZipWriter`; byte-patch the headers where noted):
  - `zip_lists_only_pages_in_natural_order`:
    - Input entries: `ComicInfo.xml`, `__MACOSX/._001.jpg`, `._002.jpg`, `.DS_Store`, `Thumbs.db`, directory `dir/`, `10.jpg`, `2.jpg`, `1.JPG`, and a 0-byte `empty.jpg`.
    - Expected page names: `["1.JPG","2.jpg","10.jpg"]`.
  - `backslash_separators_sort_and_normalize`: entries `ch1\10.jpg`, `ch1\2.jpg` → names `["ch1/2.jpg","ch1/10.jpg"]`.
  - `utf8_name_without_flag_decodes_as_utf8`: write `第3話.jpg`, then clear general-purpose bit 11 in the local and central headers → name is `第3話.jpg`.
  - `too_many_entries_is_rejected`: `Limits{max_entries: 3, ..}` with 4 entries → `LimitExceeded{limit:"entries", value:4, max:3}`.
  - `declared_total_over_limit_is_rejected`: `max_total_bytes: 10` → `LimitExceeded{limit:"total_bytes", ..}`.
  - `entry_over_limit_fails_only_that_page`: two pages, one larger than `max_entry_bytes` → `open` succeeds; `read` of the big one → `LimitExceeded{limit:"entry_bytes", ..}`; the other reads fine.
  - `lying_declared_size_is_bounded`: a deflated entry of 1 MiB zeros with the declared uncompressed size patched to 100 in both headers → `read` returns `Err(Corrupt{..})` (implement the CRC/size check so this is deterministic).
  - `read_prefix_is_bounded`: `read_prefix(0, 16)` length is `<= 16` and equals the first bytes of `read(0)`.
  - `empty_archive_is_no_pages`: an archive with only `ComicInfo.xml` → `Err(NoPages)`.
  - `archive/mod.rs` → `open_path_rejects_non_zip_file`: a text file → `UnsupportedFormat`. `open_path_detects_zip_without_extension`: a CBZ copied to `book.bin` opens.
  - `archive/folder.rs` → `folder_is_recursive_filtered_and_depth_limited`: files `2.jpg`, `10.jpg`, `.hidden.jpg`, `sub/1.png`, `a/b/c/d/e/deep.jpg` (depth 5) → `["2.jpg","10.jpg","sub/1.png"]`.
  - `core/tests/archive_corpus.rs` → `formats_and_names_corpus_order`: `formats-and-names.cbz` → `["vol1/page1.jpg","vol1/page2.png","vol1/page10.webp","第3話.jpg"]`. `folder_and_zip_agree`: extract `mixed-small.cbz` to a temp dir; `FolderSource` names and bytes equal `ZipSource`'s.
- [ ] **Step 2: Run** `cargo test -p proviewer_core` — Expected: FAIL.
- [ ] **Step 3: Implement** the module per Interfaces.
- [ ] **Step 4: Run** `cargo test -p proviewer_core` — Expected: all pass.
- [ ] **Step 5: Commit** (only if authorized): `git add core Cargo.lock && git commit -m "feat(core): folder and zip page sources with input limits"`

---

### Task 3: JPEG codec comparison (incl. platform decoder baseline)

Spec §5 "Decoding note" and §9: compare `zune-jpeg`, `libjpeg-turbo`, and the platform decoder on speed and scaled-decode support **before** choosing a codec. `jpeg-decoder` is added because it has pure-Rust DCT scaling (`Decoder::scale`).

**Files:**
- Create: `tools/codec-bench/Cargo.toml`, `tools/codec-bench/src/main.rs`, `scripts/device-run.ps1`, `codecbench/build.gradle.kts`, `codecbench/src/main/AndroidManifest.xml`, `codecbench/src/androidTest/kotlin/com/proviewer/codecbench/PlatformJpegDecodeBenchmark.kt`, `docs/perf/codec-comparison.md`
- Modify: `Cargo.toml` (members), `settings.gradle.kts` (`:codecbench`), `gradle/libs.versions.toml` (`android-library` plugin, `androidx.benchmark` plugin + `benchmark-junit4`), `.gitignore`

**Interfaces:**
- Produces:
  - `codec-bench` binary:
    - Its inputs are generated in-process and deterministically with `gen_corpus` plus `jpeg-encoder`:
      - `gray-1600x2400.jpg`: `halftone_page(1600,2400,3.0,45.0)`, q85.
      - `color-1600x2400.jpg`: a deterministic colour page, 4:2:0, q85.
      - `progressive-1600x2400.jpg`: the same colour page, progressive.
      - `gray-12000x8000.jpg`: halftone, q85.
    - Modes:
      - `codec-bench --export <dir>` writes the inputs.
      - `codec-bench [--iters N]` prints CSV `codec,input,denom,supported,median_ms,p90_ms,out_w,out_h`.
    - Measurement: denoms 1, 2, 4. Warmup 3, `N` iterations (default 15; 5 for the 12000×8000 input).
    - Unsupported scaled decode prints `supported=false`, and `median_ms` is the full decode time (the engine would decode fully and resize).
    - Before timing, each codec's denom-1 output must match `zune-jpeg`'s within mean absolute difference ≤ 2. Otherwise the run exits 1.
    - Feature `turbo` (off by default) adds the `turbojpeg` candidate.
  - `scripts/device-run.ps1 -Crate <pkg> [-Bin <name> | -Bench <name>] [-Features <csv>] [-Push <path[]>] [-OutFile <path>] [-Serial <id>] [-- <args…>]`:
    - Set `ANDROID_NDK_HOME` from `check-env.ps1 -Quiet -PassThru`.
    - Build with `cargo ndk -t arm64-v8a -P 29 build --release --bin …` or `bench --no-run --bench …`, using `--message-format=json` to find the produced executable.
    - `-Serial` defaults to the first **non-`emulator-`** device. If there is none, exit 1 with "reference device not attached".
    - Push the executable to `/data/local/tmp/pv/bin/`. Push each `-Push` file to `/data/local/tmp/pv/corpus/`, skipping it when the remote size equals the local size.
    - Run `cd /data/local/tmp/pv && PV_CORPUS=/data/local/tmp/pv/corpus ./bin/<exe> <args>`. Tee stdout to `-OutFile`, and propagate the exit code.
  - `:codecbench` Android library module:
    - Plugins `com.android.library` and `androidx.benchmark`, `minSdk 29`.
    - A Gradle `Exec` task runs `cargo run --release -p codec-bench -- --export codecbench/build/codec-inputs`. Its output dir is added to the `androidTest` assets.
    - `PlatformJpegDecodeBenchmark` (`BenchmarkRule`) has one `@Test` per (input × method × sample size 1/2/4):
      - `BitmapFactory.decodeByteArray` with `inSampleSize` and `ARGB_8888`.
      - `ImageDecoder.decodeBitmap(createSource(ByteBuffer))` with `setTargetSampleSize` and `ALLOCATOR_SOFTWARE`.
      - Bytes are loaded once, outside the measured block. Each iteration recycles its bitmap.

- [ ] **Step 1: Write `codec-bench`** per Interfaces, without the `turbo` feature first. Run on host: `cargo run --release -p codec-bench -- --iters 3`. Expected: CSV rows for `zune-jpeg` and `jpeg-decoder` on all 4 inputs × 3 denoms, exit 0 (the correctness cross-check passed).
- [ ] **Step 2: Write `scripts/device-run.ps1`.** Run it with the emulator **and** the device attached: `scripts/device-run.ps1 -Crate codec-bench -Bin codec-bench -OutFile docs/perf/baselines/phase1-codecs-rust-<yyyy-mm-dd>.csv`. Expected: the run prints and uses serial `3B65BT02H6G00000`, produces CSV, exit 0. An explicit `-Serial emulator-5554` is allowed (correctness runs) but prints `WARNING: emulator numbers are not valid for performance`.
- [ ] **Step 3: Add the `turbo` candidate.** Install the SDK CMake (`sdkmanager "cmake;<latest>"`) and put it on `PATH` inside `device-run.ps1` when `-Features` contains `turbo`. Cross-build `turbojpeg` from source for `aarch64-linux-android` (arm64 SIMD needs no NASM). Run step 2 again with `-Features turbo`. If it still cannot be cross-compiled after this step, write the exact failure into `codec-comparison.md` and **ask the owner** whether to continue without libjpeg-turbo. The platform decoder (Skia uses libjpeg-turbo internally) stays as the reference point.
- [ ] **Step 4: Write and run the platform baseline** on the device: `.\gradlew.bat :codecbench:connectedReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=UNLOCKED` (only `UNLOCKED` may be suppressed: no root, clocks unlocked) with `ANDROID_SERIAL=3B65BT02H6G00000`. Expected: passes; copy the results JSON to `docs/perf/baselines/phase1-codecs-platform-<yyyy-mm-dd>.json`.
- [ ] **Step 5: Write `docs/perf/codec-comparison.md`:**
  - One table of median/p90 ms per codec × input × denom (device numbers only), the scaled-decode support matrix, and the release `.so` size delta of each candidate.
  - Note that platform outputs are ARGB (4 B/px) while Rust outputs Gray8/RGB8, and that Rust times exclude the Phase 2 copy into a `Bitmap`.
  - **Decision rule:**
    - Score = sum of device medians over all 12 cells (unsupported denom = full decode time).
    - Pick the lowest-scoring Rust candidate. If `turbojpeg` wins by < 15 % over the best pure-Rust codec, pick the pure-Rust one: no C build, and fuzzed memory safety.
    - If the platform decoder's score beats the chosen Rust codec by > 25 %, **stop and ask the owner** (it would move decoding to Kotlin, an architecture change) before Task 4.
  - Record the chosen backend and its supported denominators.
- [ ] **Step 6: Commit** (only if authorized): `git add tools/codec-bench scripts/device-run.ps1 codecbench settings.gradle.kts gradle/libs.versions.toml Cargo.toml Cargo.lock docs/perf .gitignore && git commit -m "perf: jpeg codec comparison incl. platform baseline"`

---

### Task 4: Decode (JPEG/PNG/WebP) with limits

**Files:**
- Create: `core/src/decode/mod.rs`, `core/src/decode/jpeg.rs`, `core/src/decode/png.rs`, `core/src/decode/webp.rs`, `core/tests/fixtures/lossy.webp`
- Modify: `core/src/lib.rs`, `core/Cargo.toml` (deps: chosen JPEG crate, `png`, `image-webp`, `imagesize`; dev-deps `image`, `jpeg-encoder`)

**Interfaces:**
- Consumes: `Limits`, `EngineError`; the backend decision in `docs/perf/codec-comparison.md` (Task 3).
- Produces:
  - `#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)] pub enum ImageFormat { Jpeg, Png, Webp }`
  - `#[derive(uniffi::Record, Clone, Copy, Debug, PartialEq, Eq)] pub struct PageInfo { pub width: u32, pub height: u32, pub format: ImageFormat }`
  - `pub enum PixelLayout { Gray8, Rgb8 }` and `pub struct DecodedImage { pub width: u32, pub height: u32, pub layout: PixelLayout, pub pixels: Vec<u8>, pub denom: u8 }` with `pub fn byte_size(&self) -> u64`. Tightly packed, no stride. `denom` = the DCT scale actually used (dims = `ceil(page_dim / denom)`).
  - `pub fn sniff(bytes: &[u8]) -> Result<ImageFormat>` uses magic bytes only (`FF D8 FF`, `\x89PNG\r\n\x1a\n`, `RIFF....WEBP`). Otherwise `UnsupportedFormat`.
  - `pub fn probe(bytes: &[u8]) -> Result<PageInfo>` is header-only (`imagesize`) and works on a prefix. A truncated header → `Corrupt`. Dimensions of 0 or above `max_image_dimension` are checked by `decode`, not here.
  - `pub fn supported_denoms(format: ImageFormat) -> &'static [u8]` returns the backend's set for JPEG (e.g. `[1,2,4,8]`) and `[1]` for PNG/WebP.
  - `pub fn decode(bytes: &[u8], denom: u8, limits: &Limits) -> Result<DecodedImage>`:
    - Sniff, then probe, then check the limits **before** any pixel allocation.
    - If `w*h/denom²` exceeds `max_image_pixels`: JPEG uses the smallest supported larger denom that fits, else `LimitExceeded{limit:"image_pixels"}`. PNG/WebP return `LimitExceeded`.
    - Gray sources → `Gray8`; everything else → `Rgb8`.
    - Alpha is **flattened onto white** (comic pages sit on paper; this avoids premultiplication in resize and on Android).
    - 16-bit → 8-bit. Palette → expanded. CMYK/YCCK → RGB.
    - The whole call is wrapped in `catch_unwind`, so a decoder panic → `Corrupt`.

- [ ] **Step 1: Write failing tests** (unit tests in `decode/mod.rs`; inputs encoded in the test with `image`/`jpeg-encoder`):
  - `sniff_detects_formats`: each magic maps to its format. `b"GIF89a"` → `UnsupportedFormat`. `b""` → `UnsupportedFormat`.
  - `probe_reads_dims_from_prefix`: 1600×2400 JPEG, first 16 KiB only → `PageInfo{1600,2400,Jpeg}`.
  - `gray_jpeg_decodes_to_gray8` / `color_jpeg_decodes_to_rgb8`: layout, dims, `pixels.len()` = `w*h*channels`.
  - `progressive_jpeg_decodes`: decoded output matches the baseline-encoded version within mean abs diff ≤ 3.
  - `cmyk_jpeg_decodes_to_rgb`: a `jpeg-encoder` CMYK image that is solid red (C0 M255 Y255 K0) → `Rgb8`, center pixel has `R > 200, G < 60, B < 60`.
  - `jpeg_scaled_decode_dims` (if `supported_denoms(Jpeg)` contains 2): 1600×2400 at denom 2 → 800×1200, `denom == 2`.
  - `png_variants_normalize`: 16-bit gray → `Gray8`. Palette → `Rgb8`. RGBA with a fully transparent pixel → that pixel is `(255,255,255)`. Gray+alpha → `Gray8`.
  - `webp_lossless_and_lossy_decode`: a lossless WebP made with `image` decodes exactly; the committed `lossy.webp` decodes to its known dims.
  - `decode_sniffs_content_not_extension`: decode is fed raw bytes with no name, so PNG bytes decode as PNG. (The extension only matters in `is_page_name`.)
  - `over_limit_png_is_rejected`: `max_image_pixels: 1000` with a 100×100 PNG → `LimitExceeded{limit:"image_pixels", value:10000, max:1000}`.
  - `over_limit_jpeg_downscales_or_rejects`: `max_image_pixels: 1_000_000`, 1600×2400 JPEG at denom 1 → with scaled decode: `denom == 2` and 800×1200; without it: `LimitExceeded`.
  - `truncated_and_garbage_input_never_panic`: the first 60 % of a JPEG, PNG and WebP each return (`Ok` or `Err(Corrupt)`); 1 KiB of `0xFF` → `Err`.
- [ ] **Step 2: Make `lossy.webp`** (≤ 8 KB): `python -c "from PIL import Image; Image.radial_gradient('L').resize((64,96)).convert('RGB').save('core/tests/fixtures/lossy.webp','WEBP',lossless=False,quality=70)"` (install Pillow with `python -m pip install --user pillow` if missing).
- [ ] **Step 3: Run** `cargo test -p proviewer_core decode` — Expected: FAIL.
- [ ] **Step 4: Implement** the decode modules with the backend chosen in Task 3.
- [ ] **Step 5: Run** `cargo test -p proviewer_core` — Expected: all pass. Run `scripts/build-core.ps1` — Expected: the Android build of core still succeeds with the new deps.
- [ ] **Step 6: Commit** (only if authorized): `git add core Cargo.lock && git commit -m "feat(core): jpeg/png/webp decode with limits"`

---

### Task 5: Fuzzing archive + decode (WSL)

**Files:**
- Create: `core/fuzz/Cargo.toml`, `core/fuzz/fuzz_targets/archive.rs`, `core/fuzz/fuzz_targets/decode.rs`, `scripts/fuzz.ps1`, `core/tests/fuzz_regressions.rs`, `core/tests/fixtures/fuzz/` (minimized crash inputs, if any)
- Modify: `Cargo.toml` (`exclude = ["core/fuzz"]`), `tools/gen-corpus/src/main.rs` (`--fuzz-seeds`), `.gitignore`

**Interfaces:**
- Consumes: `ZipSource::from_reader`, `PageSource`, `decode::{probe, decode}`, `Limits` (Tasks 1, 2, 4).
- Produces:
  - Fuzz limits `Limits { max_entries: 256, max_entry_bytes: 8 MiB, max_total_bytes: 64 MiB, max_image_pixels: 4_000_000, max_image_dimension: 16_384 }`, defined once in each target.
  - `archive` target: `ZipSource::from_reader(Cursor::new(data.to_vec()), L)`. For each page (cap 32): `read_prefix(i, 4096)`, then `read(i)`, then `probe`, then `decode(.., 1, &L)`. Results are ignored; only crashes, OOM and timeouts count.
  - `decode` target: `probe(data)`, then `decode(data, d, &L)` for every `d` in `supported_denoms(sniff(data)?)`.
  - `gen-corpus --fuzz-seeds <dir>` writes `<dir>/archive/*.cbz` (`generate_with_scale(.., 0.02)`, skipping `many-pages` and `webtoon-long`) and `<dir>/decode/<n>.<ext>`, each image entry extracted from those archives.
  - **Prerequisite, not part of this plan:** the dedicated WSL distro `proviewer` (`E:\WSL\proviewer`, user `dev`, Rust nightly, `cargo-fuzz`) is set up beforehand with `docs/dev/wsl-proviewer-setup.md`. This plan never installs or configures it.
  - `scripts/fuzz.ps1 -Target <archive|decode> [-Seconds 900]`:
    - Preflight only: if `wsl -l -q` has no `proviewer`, or `wsl -d proviewer -- bash -lc 'cargo +nightly fuzz --version'` fails, exit 1 with "WSL distro 'proviewer' not ready — see docs/dev/wsl-proviewer-setup.md". It installs nothing.
    - A run generates seeds with the Windows `gen-corpus` into `core/fuzz/corpus-seeds/` (gitignored), then runs `wsl -d proviewer -- bash -lc "…"`: `cd /mnt/e/Code/ProViewer/core/fuzz && CARGO_TARGET_DIR=$HOME/pv-fuzz-target cargo +nightly fuzz run <t> corpus/<t> corpus-seeds/<t> -- -max_total_time=<s> -rss_limit_mb=2048 -malloc_limit_mb=512 -timeout=10`.
    - The source stays in the Windows repo (via `/mnt/e`). The build dir lives inside the distro's own ext4 disk (on E:) for speed.
    - The script exits non-zero if libFuzzer reports a crash and prints the artifact path.
    - Removal, if ever needed: `wsl --unregister proviewer` (deletes the distro and its disk).

- [ ] **Step 1: Write the fuzz crate, `--fuzz-seeds`, and `scripts/fuzz.ps1`** per Interfaces. Run `scripts/fuzz.ps1 -Target decode -Seconds 60`. Expected: libFuzzer starts, loads the seeds (`INITED … corp: N`), and exits 0.
- [ ] **Step 2: Run each target for the fixed budget:** `-Target archive -Seconds 900`, then `-Target decode -Seconds 900`.
- [ ] **Step 3: For every crash:**
  - Minimize it (`cargo fuzz tmin`) and copy it to `core/tests/fixtures/fuzz/<target>-<short-hash>`.
  - Add a test to `core/tests/fuzz_regressions.rs` that feeds it through the same path and asserts it returns (`Ok` or `Err`) without panicking.
  - Run the test and watch it fail.
  - Fix by validation in our code, or by upgrading the crate. Only if the panic is inside a third-party decoder and cannot be validated away, rely on `decode`'s `catch_unwind` and note the upstream issue in the test.
  - Re-run the 900 s budget for that target from scratch.
- [ ] **Step 4: Run** `cargo test -p proviewer_core` — Expected: all pass (regressions included). Record per target: total execs, exec/s, final coverage, crash count, date. These go into `docs/perf/phase1-engine.md` in Task 10.
- [ ] **Step 5: Commit** (only if authorized): `git add core/fuzz core/tests Cargo.toml tools/gen-corpus scripts/fuzz.ps1 .gitignore && git commit -m "test(core): fuzz targets for archive and decode"`

---

### Task 6: Linear-light resize to RGBA

**Files:**
- Create: `core/src/resize.rs`
- Modify: `core/src/lib.rs`, `core/Cargo.toml` (`fast_image_resize`)

**Interfaces:**
- Consumes: `DecodedImage`, `PixelLayout` (Task 4).
- Produces:
  - `#[derive(uniffi::Record, Clone, Copy, Debug, PartialEq, Eq)] pub struct Rect { pub x: u32, pub y: u32, pub width: u32, pub height: u32 }` in **page** pixel coordinates (original, undecimated).
  - `pub fn choose_denom(region: Rect, out_w: u32, out_h: u32, supported: &[u8]) -> u8` returns the largest `d` in `supported` with `d <= min(region.width/out_w, region.height/out_h)` (as f64). It never upsamples from a decimated source and defaults to 1.
  - `pub fn render_region(src: &DecodedImage, page_w: u32, page_h: u32, region: Rect, out_w: u32, out_h: u32, dst: &mut [u8], dst_stride: usize) -> Result<()>`:
    - Writes `out_w×out_h` **RGBA8888** (bytes R,G,B,A; A = 255, which is the `ANDROID_BITMAP_FORMAT_RGBA_8888` layout) into `dst` rows `dst_stride` apart. Bytes beyond `out_w*4` in each row are left untouched.
    - Source crop = `region` × (`src.width/page_w`, `src.height/page_h`), as an f64 crop in `fast_image_resize`'s `ResizeOptions::crop`.
    - Pipeline:
      1. u8 sRGB → u16 linear (`fast_image_resize`'s sRGB `PixelComponentMapper`, or a 256-entry LUT).
      2. `ResizeAlg::Convolution(FilterType::Lanczos3)` on `U16` / `U16x3`.
      3. u16 linear → u8 sRGB.
      4. Expand to RGBA.
    - When the crop is the full source and the out size equals the source size, skip resampling (copy + expand only).
    - Errors: `InvalidRequest` if `dst` is too small, `dst_stride < out_w*4`, or either out dimension is 0.

- [ ] **Step 1: Write failing tests** (unit tests in `resize.rs`):
  - `choose_denom_table`: `(1600×2400 region → 1080×1620, [1,2,4,8]) → 1`. `(→ 400×600) → 4`. `(→ 300×450) → 4`. `(→ 1080×1620, [1]) → 1`. `(→ 3200×4800) → 1`.
  - `linear_light_checkerboard_downscale`: 64×64 `Gray8` 1-px checkerboard 0/255 → 32×32. Every pixel's R in `185..=190` (gamma-space averaging would give ≈128).
  - `uniform_gray_stays_uniform`: 37×53 of value 128 → 20×11 and → 80×120. Every channel within 128 ± 1, alpha 255.
  - `halftone_downscale_suppresses_moire`:
    - `gen_corpus::halftone_page(1600,2400,2.0,45.0)` as `Gray8`, centre region 800×800 → 200×200.
    - The std dev of the output must be `< 0.25 ×` the std dev of a nearest-neighbour downscale of the same region (computed in the test).
  - `crop_selects_region`: 100×100, left half 0, right half 255. Region `{50,0,50,100}` → 25×50. Columns 2.. are all ≥ 250.
  - `decimated_source_maps_page_coords`: a 50×50 `DecodedImage` with `denom: 2` standing for a 100×100 page, region `{0,0,100,100}` → 50×50 equals the source expanded to RGBA (identity path).
  - `rgb_source_outputs_opaque_rgba`: `Rgb8` pixel (10,200,30) uniform → every output pixel (10±1, 200±1, 30±1, 255).
  - `stride_padding_untouched`: `dst_stride = out_w*4 + 16`, padding pre-filled with `0xAB` → still `0xAB` after the call.
  - `bad_requests_are_invalid`: out size 0, short `dst`, or `dst_stride < out_w*4` → `InvalidRequest`.
- [ ] **Step 2: Run** `cargo test -p proviewer_core resize` — Expected: FAIL.
- [ ] **Step 3: Implement** `resize.rs`.
- [ ] **Step 4: Run** `cargo test -p proviewer_core` — Expected: all pass. Run `scripts/build-core.ps1` — Expected: OK (NEON paths compile for aarch64).
- [ ] **Step 5: Commit** (only if authorized): `git add core Cargo.lock && git commit -m "feat(core): linear-light lanczos resize to rgba"`

---

### Task 7: Decoded-page cache and prefetch pool

**Files:**
- Create: `core/src/cache.rs`, `core/src/scheduler.rs`
- Modify: `core/src/lib.rs`, `core/Cargo.toml` (`libc` for `cfg(unix)`)

**Interfaces:**
- Consumes: `DecodedImage`, `EngineError` (Tasks 1, 4).
- Produces:
  - `#[derive(Clone, Copy, Debug, Hash, PartialEq, Eq)] pub struct CacheKey { pub book: u64, pub page: u32, pub denom: u8 }`
  - `DecodeCache`:
    - `pub fn new(budget_bytes: u64) -> Self`.
    - `pub fn get(&self, book: u64, page: u32, max_denom: u8) -> Option<Arc<DecodedImage>>` returns the cached entry with the **largest denom ≤ `max_denom`** (smallest image that is still sharp enough) and marks it most-recently-used.
    - `pub fn get_or_decode(&self, key: CacheKey, decode: impl FnOnce() -> Result<DecodedImage>) -> Result<Arc<DecodedImage>>`:
      - First tries `get(key.book, key.page, key.denom)`.
      - Concurrent calls with the same `key` run `decode` **once**. The others block on that result: same `Arc`, or a clone of the same error. Errors are not cached.
      - The image is inserted under its *actual* `denom`.
      - Eviction is LRU by `byte_size`. An image larger than the whole budget is returned but not retained.
    - `pub fn remove_book(&self, book: u64)`, `pub fn used_bytes(&self) -> u64`, `pub fn len(&self) -> usize`.
  - `PrefetchPool`:
    - `pub fn new(threads: usize) -> Self`.
    - `pub fn submit(&self, generation: u64, priority: u32, job: Box<dyn FnOnce() + Send>)`: lower `priority` runs first, FIFO within a priority.
    - `pub fn set_generation(&self, generation: u64)`: queued jobs with an older generation are dropped without running.
    - Each job runs inside `catch_unwind`, so a panicking job never kills its worker.
    - On `cfg(unix)`, worker threads call `setpriority(PRIO_PROCESS, gettid(), 10)` once at start.
    - `Drop` stops and joins the workers.
    - `#[cfg(test)] fn new_paused(threads) -> Self` + `fn resume(&self)` make ordering tests deterministic.

- [ ] **Step 1: Write failing tests:**
  - `cache.rs`:
    - `get_prefers_largest_sufficient_denom`: insert denom 1 and 4 for the same page; `get(.., 2)` → denom 1; `get(.., 4)` → denom 4; `get(.., 8)` → denom 4.
    - `lru_evicts_by_bytes_within_budget`: budget = 3 images; insert 4; the first is gone; `used_bytes <= budget` after every insert.
    - `get_refreshes_recency`: after `get(first)`, inserting a 4th evicts the second instead.
    - `oversized_image_is_returned_not_retained`: an image larger than the budget → `Ok`, then `len() == 0`.
    - `concurrent_get_or_decode_decodes_once`: 8 threads, one key, a decode that sleeps 50 ms and bumps an `AtomicUsize` → counter 1, all `Arc::ptr_eq`.
    - `errors_are_shared_but_not_cached`: concurrent waiters all get the same `Corrupt`; a later call decodes again.
    - `remove_book_frees_bytes`.
  - `scheduler.rs`:
    - `runs_in_priority_order`: paused pool, 1 thread, submit priorities 3,1,2 → execution order 1,2,3.
    - `stale_generation_is_dropped`: paused pool, submit 5 jobs at gen 1, `set_generation(2)`, submit 1 job at gen 2, resume → only the gen-2 job runs.
    - `panicking_job_does_not_kill_worker`: 1 thread, a panicking job then a normal job → the normal job runs (wait with a channel and 5 s timeout).
    - `drop_joins_workers`.
- [ ] **Step 2: Run** `cargo test -p proviewer_core cache scheduler` — Expected: FAIL.
- [ ] **Step 3: Implement** both modules (`Mutex` + `Condvar`; no new concurrency crates needed).
- [ ] **Step 4: Run** `cargo test -p proviewer_core` — Expected: all pass. Then run it 20× in a loop (`for ($i=0;$i -lt 20;$i++){ cargo test -q -p proviewer_core cache scheduler; if($LASTEXITCODE){break} }`) — Expected: no flaky failure.
- [ ] **Step 5: Commit** (only if authorized): `git add core Cargo.lock && git commit -m "feat(core): byte-budget lru cache and prefetch pool"`

---

### Task 8: Engine / Book API (tile requests, prefetch, cancel) + uniffi export

**Files:**
- Create: `core/src/engine.rs`, `core/tests/engine_api.rs`
- Modify: `core/src/lib.rs`

**Interfaces:**
- Consumes: everything from Tasks 1–7.
- Produces (uniffi-exported unless marked Rust-only):
  - `#[derive(uniffi::Record, Clone, Debug)] pub struct EngineConfig { pub cache_bytes: u64, pub prefetch_ahead: u32, pub prefetch_behind: u32, pub worker_threads: u32, pub limits: Limits }`. `Default` = `256 MiB`, `3`, `1`, `2`, `Limits::default()`. `#[uniffi::export] pub fn default_engine_config() -> EngineConfig`.
  - `#[derive(uniffi::Record, Clone, Copy, Debug, PartialEq, Eq)] pub struct EngineStats { pub decodes: u64, pub cache_hits: u64, pub cache_bytes: u64, pub cache_entries: u32 }`
  - `#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)] pub enum ReadDirection { Forward, Backward }`
  - `#[derive(uniffi::Record, Clone, Copy, Debug, PartialEq, Eq)] pub struct TileRequest { pub page: u32, pub region: Rect, pub out_width: u32, pub out_height: u32 }`. This is the spec's `(page, region, scale)`. An exact output size replaces a float scale so the Phase 2 `Bitmap` size and the tile never disagree by rounding.
  - `#[derive(uniffi::Object)] pub struct Engine`:
    - `#[uniffi::constructor] fn new(config: EngineConfig) -> Arc<Self>`.
    - `fn open_path(&self, path: String) -> Result<Arc<Book>>`.
    - `fn stats(&self) -> EngineStats`.
    - The cache budget is shared by all open books. Each book gets a unique `u64` id.
  - `#[derive(uniffi::Object)] pub struct Book`:
    - `fn page_count(&self) -> u32`.
    - `fn page_name(&self, index: u32) -> Result<String>`.
    - `fn page_info(&self, index: u32) -> Result<PageInfo>`: lazy and memoized per page (errors are not memoized). It probes `read_prefix` 16 KiB, then 256 KiB, then the full entry, until `probe` succeeds.
    - `fn prefetch_hint(&self, current: u32, direction: ReadDirection, viewport_width: u32, viewport_height: u32)`:
      - Bumps the book's generation.
      - Submits decode jobs for `prefetch_ahead` pages in `direction` and `prefetch_behind` pages the other way. Priority = distance from `current`, and pages out of range are skipped.
      - Each job uses `denom = choose_denom(full page, fit_size(page, viewport))`, so it warms exactly what a fit render will ask for.
      - Jobs hold a `Weak` to the book's internals and do nothing once the book is dropped.
    - `fn cancel_prefetch(&self)` bumps the generation.
    - `Drop` bumps the generation and calls `cache.remove_book(id)`.
  - Rust-only on `Book`: `pub fn render_tile(&self, req: &TileRequest, dst: &mut [u8], dst_stride: usize, cancel: &CancelToken) -> Result<()>`:
    - Validates: page < count (else `PageOutOfRange`); region non-empty and inside `page_info` dims; `out_w*out_h <= max_image_pixels`; then the `render_region` checks. Failures → `InvalidRequest`.
    - `denom = choose_denom(region, out, supported_denoms(format))`.
    - Checks `cancel`, calls `cache.get_or_decode`, checks `cancel` again, then calls `render_region`. A cancel returns `Cancelled`.
    - Increments `decodes` / `cache_hits`.
  - Rust-only, for tests and diagnostics: `#[doc(hidden)] pub fn page_decode_count(&self, index: u32) -> u64` on `Book` counts decodes of that page across render and prefetch.
  - Rust-only: `#[derive(Clone, Default)] pub struct CancelToken` with `new()`, `cancel()`, `is_cancelled()`.
  - `pub fn fit_size(page_w: u32, page_h: u32, view_w: u32, view_h: u32) -> (u32, u32)` scales to fit inside the viewport, preserving aspect, rounded, min 1.
  - The panic → exception path relies on uniffi plus `panic = "unwind"`. No extra wrapping.

- [ ] **Step 1: Write failing tests** in `core/tests/engine_api.rs` (use `common::small_corpus()`; `render_full(book, page, w, h) -> Vec<u8>` helper; poll helpers with a 5 s timeout, never bare sleeps):
  - `fit_size_table`: `(1600,2400,1080,2378) → (1080,1620)`. `(800,24000,1080,2378) → (79,2378)`. `(3200,2400,1080,2378) → (1080,810)`.
  - `open_zip_and_folder_report_same_pages`: `mixed-small.cbz` and its extracted folder have the same `page_count` (20) and names.
  - `page_info_matches_generator`: `screentone.cbz` page 0 → `PageInfo{80,120,Jpeg}` (scale 0.05). `formats-and-names.cbz` page 2 → `format == Webp`.
  - `render_fit_page_is_opaque_rgba`: render page 0 of `mixed-small` at its `fit_size` into 1080×2378 → every 4th byte is 255; `stats().decodes == 1`.
  - `second_render_hits_cache`: rendering the same page again → `decodes` unchanged, `cache_hits` +1.
  - `render_rejects_bad_requests`: page 20 → `PageOutOfRange{index:20,count:20}`. Region outside the page, zero out size, or short `dst` → `InvalidRequest`.
  - `cancelled_token_returns_cancelled`: cancel before the call → `Err(Cancelled)` and `decodes == 0`.
  - `prefetch_warms_next_pages`: `prefetch_hint(0, Forward, 1080, 2378)` → poll until `stats().cache_entries >= 3`; then rendering pages 1..=3 at fit size adds 0 decodes.
  - `render_during_prefetch_does_not_double_decode` (Review Focus 3):
    - Use `EngineConfig{worker_threads:1, prefetch_ahead:1, ..}`.
    - Call `prefetch_hint(0, Forward, ..)` and immediately render page 1 at fit size from the test thread.
    - Wait until the pool is idle (poll `cache_entries`). Total `decodes == 1` for page 1: check with `page_decode_count(1)`, a `#[doc(hidden)] pub` debug counter on `Book`.
  - `cache_respects_budget_across_pages`: `cache_bytes` = 2 decoded pages of `mixed-small`; render all 20 → after each, `stats().cache_bytes <= cache_bytes`.
  - `dropping_book_releases_cache`: render 3 pages, drop the book → `stats().cache_bytes == 0`.
  - `corrupt_page_fails_only_that_page`: a CBZ (built in the test) whose page 1 is garbage `.jpg` → page 1 render `Err(Corrupt)`, pages 0 and 2 render `Ok`.
- [ ] **Step 2: Run** `cargo test -p proviewer_core --test engine_api` — Expected: FAIL.
- [ ] **Step 3: Implement** `engine.rs` and the exports in `lib.rs`. Keep `engine_version()` unchanged.
- [ ] **Step 4: Run** `cargo test --workspace` — Expected: all pass.
- [ ] **Step 5: Bindings smoke check:** `scripts/gen-bindings.ps1`, then `Select-String -Path app/build/generated/uniffi/kotlin/uniffi/proviewer_core/proviewer_core.kt -Pattern "class Engine","class Book","fun defaultEngineConfig"`. Expected: all three match. Then `.\gradlew.bat :app:assembleDebug`, `scripts/check-16kb.ps1 -Apk app/build/outputs/apk/debug/app-debug.apk`, and `:app:connectedDebugAndroidTest` on the emulator. Expected: build OK, 16 KB check exit 0, 2 tests pass.
- [ ] **Step 6: Commit** (only if authorized): `git add core Cargo.lock && git commit -m "feat(core): engine and book api with tiles, prefetch, cancel"`

---

### Task 9: `pvcli` (desktop and device CLI)

**Files:**
- Create: `tools/pvcli/Cargo.toml`, `tools/pvcli/src/main.rs`, `tools/pvcli/src/mem.rs`
- Modify: `Cargo.toml` (members)

**Interfaces:**
- Consumes: `Engine`, `Book`, `EngineConfig`, `TileRequest`, `CancelToken`, `fit_size` (Task 8).
- Produces:
  - `pvcli info <book>` prints `open_ms=<f>`, then one line per page: `index\tname\twidth\theight\tformat`, then `probe_all_ms=<f>`.
  - `pvcli render <book> <page> --fit <W>x<H> -o <out.png>` renders the fit-size page and writes an RGBA PNG (use the `png` crate). This is for eyeballing screentone quality.
  - `pvcli read <book> --fit <W>x<H> [--cache-mb N] [--dwell-ms 50] [--pages N]` simulates reading forward. For each page it calls `prefetch_hint(i, Forward, W, H)`, times a fit render, then sleeps the dwell. It prints one CSV row per page, `page,ms,decoded` (where `decoded` means a decode happened inside the render), then a summary line: `cold_first_ms,p50_ms,p95_ms,max_ms,decodes,cache_hits,peak_rss_kb`.
  - `mem::peak_rss_kb() -> Option<u64>`: `VmHWM` from `/proc/self/status` on Linux/Android; `PeakWorkingSetSize` via `windows-sys` `K32GetProcessMemoryInfo` on Windows.

- [ ] **Step 1: Write failing tests** (unit tests in `main.rs`): `parse_fit("1080x2378") == Some((1080,2378))`, `parse_fit("1080") == None`. `summary_percentiles` (nearest-rank over `[10,20,…,100]` ms → p50 50, p95 100). `peak_rss_kb_is_some_and_positive` on the host.
- [ ] **Step 2: Run** `cargo test -p pvcli` — Expected: FAIL.
- [ ] **Step 3: Implement** `pvcli`.
- [ ] **Step 4: Run** `cargo test -p pvcli`, then on the host `cargo run --release -p pvcli -- render corpus/generated/screentone.cbz 0 --fit 1080x2378 -o target/screentone-0.png`. Expected: PNG written at 1080×1620. Open it and confirm no visible moiré bands; the owner may eyeball it too. Then `cargo run --release -p pvcli -- read corpus/generated/mixed-small.cbz --fit 1080x2378`. Expected: 20 rows plus a summary, with `decoded` false for most pages after the first (prefetch working).
- [ ] **Step 5: Run on the device:** `scripts/device-run.ps1 -Crate pvcli -Bin pvcli -Push corpus/generated/mixed-small.cbz -- read corpus/mixed-small.cbz --fit 1080x2378`. Expected: same shape of output, exit 0.
- [ ] **Step 6: Commit** (only if authorized): `git add tools/pvcli Cargo.toml Cargo.lock && git commit -m "feat(tools): pvcli info/render/read"`

---

### Task 10: Engine benches, device numbers, budgets c and d

**Files:**
- Modify: `core/benches/engine.rs` (replace the skeleton), `docs/perf/budgets.md`
- Create: `docs/perf/phase1-engine.md`

**Interfaces:**
- Consumes: the public engine API and modules (Tasks 2–9), `scripts/device-run.ps1` (Task 3), `pvcli` (Task 9).
- Produces criterion benches. The corpus dir is `$PV_CORPUS`, else `<CARGO_MANIFEST_DIR>/../corpus/generated`. If the dir is missing, the bench panics with "run: cargo run --release -p gen-corpus -- corpus/generated". The benches:
  - `open/many-pages`: `Engine::open_path` on `many-pages.cbz`. This is **budget d**.
  - `open/many-pages+probe-all`: open plus `page_info` for all 500 pages (informational, for the Phase 3 Webtoon layout).
  - `decode/<input>/d<denom>`: `screentone` p0 (denom 1 and the fit denom), `mixed-small` p0, `huge-single` p0 (denom 1 and 8), `formats-and-names` webp and png. Bytes are read once, outside the timed loop.
  - `resize/gray-1600x2400-to-1080x1620`, `resize/rgb-1600x2400-to-1080x1620` (synthetic RGB input), `resize/huge-region-2160x4756-to-1080x2378` (a 2× zoom tile out of `huge-single`).
  - `render/cold-fit` (new `Engine` with an empty cache per iteration, `iter_batched`) and `render/warm-fit` (decoded page cached) for `screentone` p0 at 1080×2378.

- [ ] **Step 1: Write the benches** and run `cargo bench -p proviewer_core -- --quick` on the host. Expected: every bench runs. Host numbers are a wiring check only.
- [ ] **Step 2: Device benches:** `scripts/device-run.ps1 -Crate proviewer_core -Bench engine -Push corpus/generated/many-pages.cbz,corpus/generated/screentone.cbz,corpus/generated/mixed-small.cbz,corpus/generated/huge-single.cbz,corpus/generated/formats-and-names.cbz -OutFile docs/perf/baselines/phase1-engine-bench-<yyyy-mm-dd>.txt -- --bench`. Conditions: screen on, USB powered, battery saver off, device cool (wait 2 min idle before). Run it **3 times** and keep all three outputs.
- [ ] **Step 3: Device memory and reading runs** with `pvcli read --fit 1080x2378` (default config, 256 MiB cache) on `mixed-small.cbz`, `huge-single.cbz`, `webtoon-long.cbz`, and on `screentone.cbz` with `--cache-mb 64`. Save the outputs to `docs/perf/baselines/phase1-pvcli-<file>-<yyyy-mm-dd>.txt`.
- [ ] **Step 4: Write `docs/perf/phase1-engine.md`:**
  - The device medians (median of the 3 runs) for every bench.
  - The `pvcli read` summaries.
  - The fuzz record from Task 5.
  - The codec decision link.
  - The **engine part of page-turn latency**, cold and prefetched (from `pvcli read`), compared with budget b. This is information only, since b is enforced in Phase 2.
  - A short "what is slow" list that Phase 2 should know about (e.g. linear u16 resize cost, huge-image decode time).
- [ ] **Step 5: Propose budgets in `docs/perf/budgets.md`** and mark each `proposed — awaiting owner approval`:
  - **d**: open time of `many-pages.cbz` ≤ the device median × 1.5, rounded up to a clean number. `+probe-all` is shown but not budgeted.
  - **c**: peak total process PSS for `huge-single.cbz` and `webtoon-long.cbz` ≤ the engine's measured `peak_rss_kb` (pvcli) + displayed/prefetched bitmaps (`(1 + prefetch_ahead + prefetch_behind) × 1080×2378×4 B`, ×2 for the GPU texture copy) + the ART/Compose app baseline. That baseline is measured once with `dumpsys meminfo com.proviewer.app` TOTAL PSS of the Phase 0 app on the device and noted with its date. Round up to a clean number, and show the formula and every term.
- [ ] **Step 6: Commit** (only if authorized): `git add core/benches docs/perf && git commit -m "perf: phase 1 engine numbers and proposed budgets c, d"`

---

## Phase 1 Exit Gate

Phase 1 is complete when all of these hold and the owner approves budgets c and d:

1. `cargo test --workspace` is green on the host (core incl. fuzz regressions, gen-corpus, framestat, codec-bench, pvcli).
2. `cargo bench -p proviewer_core` runs on host and device; the device numbers are in `docs/perf/phase1-engine.md`.
3. `docs/perf/codec-comparison.md` exists with device numbers for every candidate (or the owner's recorded decision to drop libjpeg-turbo) and the platform baseline. The chosen backend is the one in `core`.
4. Fuzz targets `archive` and `decode` each ran **900 s clean** on the final code. Every crash found earlier has a regression test.
5. Android still builds: `:app:assembleDebug` OK, `scripts/check-16kb.ps1` exit 0, `:app:connectedDebugAndroidTest` passes on the emulator.
6. `docs/perf/budgets.md` rows c and d carry concrete numbers, approved by the owner.

After approval: write the **Phase 2 (Reader MVP)** plan, covering SAF + non-seekable fd fallback, the JNI pixel path into `Bitmap`, the bitmap upload strategy chosen by measurement, and the page-turn/zoom budgets.

## Self-Review Notes

- **Spec coverage (Phase 1 row):**
  - Folder/CBZ → Task 2. JPEG/PNG/WebP → Task 4. Linear-light resize → Task 6.
  - Tile API, LRU cache, prefetch/cancel → Tasks 7–8.
  - Input limits (dimensions, zip bombs) → Tasks 2 and 4, fuzzed in Task 5. Natural sort → Task 1.
  - Desktop CLI/tests → Task 9 plus all unit/integration tests.
  - Codec comparison incl. platform baseline → Task 3. Fuzz with a fixed time budget → Task 5.
  - Decode/resize/cache numbers → Task 10. Budgets c/d (left open by Phase 0) → Task 10.
- **Spec §5 "Untrusted input":** limits are checked before allocation, a panic becomes `Corrupt` (decode) or an exception (uniffi, `panic = "unwind"`), and per-entry failures do not kill the book.
- **Deliberately deferred to Phase 2:** opening from an fd and the non-seekable copy-to-cache fallback (spec §8 puts both in Phase 2), the JNI pixel path, and `Bitmap` upload. `ZipSource::from_reader` is generic, so the fd path plugs in without engine changes.
- **My calls the owner can override:**
  - Visible renders run synchronously on the caller thread instead of a priority queue, and prefetch only warms decoded pages (resized output is not cached in Rust; the Kotlin `Bitmap` is that cache).
  - Alpha is flattened onto white.
  - Cache default 256 MiB, `prefetch_ahead` 3 / `behind` 1, 2 workers.
  - `Limits` defaults; folder depth 4; `jpeg-decoder` added as a fourth codec candidate.
  - The 15 % / 25 % thresholds in the codec decision rule; 900 s per fuzz target; fuzzing in WSL rather than on Windows.
  - `TileRequest` takes an output size instead of a float scale.
