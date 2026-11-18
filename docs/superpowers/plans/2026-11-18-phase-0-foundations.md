# ProViewer Phase 0 — Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Rust + Android toolchain end to end (empty Compose app that calls a Rust function through uniffi, 16 KB-aligned native libs, benchmark skeleton, synthetic test corpus) and record the reference-device baseline and provisional performance budgets that later phases are measured against.

**Architecture:** Cargo workspace (`core/` = engine crate exposing a uniffi API; `tools/*` = host-side helper binaries) plus a Gradle project (`app/` = Compose app, `benchmark/` = Macrobenchmark). PowerShell scripts in `scripts/` glue them: cross-compile with `cargo-ndk`, generate Kotlin bindings from the built library (uniffi library mode, proc-macro style, no UDL), verify 16 KB alignment across the whole APK, capture frame timing with Perfetto FrameTimeline.

**Tech Stack:** Rust (stable, host `x86_64-pc-windows-msvc`; Android targets `aarch64-linux-android`, `x86_64-linux-android`), `uniffi`, `cargo-ndk`, `criterion`, `image`, `zip`; Kotlin, Jetpack Compose Material 3, Gradle (wrapper pinned to the cached 9.6.0), JNA (uniffi's Kotlin runtime), AndroidX Macrobenchmark; Perfetto (on-device `perfetto` + host `trace_processor_shell.exe`, pinned release); NDK 30.0.16248370, build-tools 36.0.0, JDK 17.

**Spec:** `docs/superpowers/specs/2026-11-18-proviewer-design.md` (sections 3, 5, 6, 7, 8 "Phase 0").

## Global Constraints

- minSdk **29**; build **arm64-v8a** only, plus **x86_64** for emulator during dev. (spec §6)
- Cargo workspace `core/` + Gradle `app/`; `uniffi` for the control API. (spec §5, §6)
- Native libraries must be **16 KB page-aligned** — every `.so` in the APK, including third-party ones. (spec §3)
- Release profile keeps **`panic = "unwind"`** (never `abort`) so uniffi can convert panics into Kotlin exceptions. (spec §5)
- **Reference device**: Snapdragon 8 Elite, 12 GB RAM, Android 16, **185 Hz** (frame budget ≈ 5.4 ms). Emulator is for correctness only, never for performance numbers. (spec §3)
- **Frame metrics come from Perfetto FrameTimeline**, never from `dumpsys gfxinfo framestats` (120-frame ring buffer, HWUI-only, wrong jank definition). Budgets are janky-frame %. (spec §7)
- Phase 0 has **no reader UI**. (spec §8)
- All specs, plans and docs are written in **English**. (spec §3)
- **Commit only when the owner asks.** Every task ends with a commit step: run it only if the owner has authorized commits for this execution (ask once, at the start); otherwise skip it and leave changes uncommitted.
- Dev host is Windows 11; scripts are PowerShell and run as `powershell -NoProfile -ExecutionPolicy Bypass -File <script>`.
- Pin exact dependency versions (Cargo.lock committed; `libs.versions.toml` exact versions; Perfetto release version pinned in `scripts/check-env.ps1`). `uniffi` and the bindgen crate use the same version via `[workspace.dependencies]`.
- Workspace `members` is an **explicit list** (no `tools/*` glob), so non-crate directories never break `cargo`.
- Machine facts found while planning (re-verified by Task 1): Rust 1.100, only `x86_64-pc-windows-msvc` installed; SDK at `E:\AppDataLinks\Android\Sdk` (`ANDROID_HOME` set, `ANDROID_NDK_HOME` unset); AVDs `Medium_Phone_API_37.0` and `Medium_Tablet`; Gradle 9.6.0 distribution cached under `%USERPROFILE%\.gradle\wrapper\dists`; no `gradle` on PATH; no `cargo-ndk`; no `trace_processor_shell`; no physical device attached at planning time.

## Review Focus

1. **Stale native lib / bindings mismatch** (Rust API changed, old `.so` or generated Kotlin still in the APK → crash on first call). Pinned by Task 4's instrumented test plus Gradle tasks that always rebuild core and bindings before `preBuild`.
2. **R8 strips JNA/uniffi classes in a release-like build** → crash only in minified builds. Pinned by Task 7's Macrobenchmark startup test, which runs the minified `benchmark` build type and asserts the engine version text is on screen.
3. **A third-party `.so` (JNA's `libjnidispatch.so`) is not 16 KB-aligned** even though our own library is. Pinned by Task 5's APK-wide check, with a negative self-test.
4. **Emulator (x86_64) crashes with `UnsatisfiedLinkError`** because only arm64 was built. Pinned by Task 5 asserting both ABIs of `libproviewer_core.so` are in the APK.
5. **Adaptive refresh rate makes "185 Hz" frame stats meaningless** (display drops to a lower rate mid-test, or the measured app requests a lower rate). Pinned by Task 8: the capture script checks the active refresh rate **before and after** the action and refuses to record a row on mismatch; `framestat refresh` is tested against fixtures.
6. **Frame capture silently covers only a fraction of the scenario, or counts pipelined frames as jank.** Pinned by Task 8: Perfetto FrameTimeline over the whole action duration; jank comes from SurfaceFlinger's `jank_type`, with unit tests for the classification; the script fails if a scenario yields 0 frames for the target process.

## File Structure

```
Cargo.toml                          workspace (explicit members), shared dependency versions, release profile
.cargo/config.toml                  16 KB max-page-size link flag for Android targets
.gitignore                          target/, build/, .gradle/, app/src/main/jniLibs/, corpus/generated/, corpus/local/, .tools/, docs/perf/traces/, local.properties
core/Cargo.toml  core/src/lib.rs    crate `proviewer_core` (cdylib+lib), uniffi scaffolding, `engine_version`
core/benches/engine.rs              criterion skeleton
tools/uniffi-bindgen/               host binary that runs uniffi's bindgen CLI
tools/gen-corpus/                   synthetic corpus generator (lib + bin)
tools/framestat/                    FrameTimeline CSV + dumpsys display parsers, frame statistics (lib + bin)
scripts/check-env.ps1               toolchain preflight (installs nothing silently; reports)
scripts/build-core.ps1              cargo-ndk cross-build → app/src/main/jniLibs/<abi>/
scripts/gen-bindings.ps1            uniffi Kotlin generation → app/build/generated/uniffi/kotlin
scripts/check-16kb.ps1              APK-wide 16 KB alignment check
scripts/perf-capture.ps1            Perfetto FrameTimeline + peak PSS capture for any package
scripts/perfetto/frametimeline.pbtx Perfetto text config (FrameTimeline + process names)
scripts/perfetto/frames.sql         trace_processor query: app frames of one package
.tools/                             pinned trace_processor_shell.exe (gitignored)
settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml  gradlew(.bat)
app/                                Compose app (package com.proviewer.app), androidTest
benchmark/                          Macrobenchmark module (startup + frame timing)
docs/perf/reference-device.md       measured device facts
docs/perf/budgets.md                provisional performance budgets (owner approves)
docs/perf/baselines/                CSV/JSON captures
docs/perf/traces/                   raw .pftrace files (gitignored; large)
corpus/README.md                    how corpus is generated; real manga goes in corpus/local/ (never committed)
```

---

### Task 1: Toolchain preflight and Android Rust targets

**Files:**
- Create: `scripts/check-env.ps1`, `.gitignore`

**Interfaces:**
- Produces: `scripts/check-env.ps1` exits 0 only if all of the following hold, printing one `OK`/`MISSING <tool>` line each: `rustc`/`cargo` ≥ 1.80; rustup targets `aarch64-linux-android` and `x86_64-linux-android` installed; `cargo-ndk` installed; `ANDROID_HOME` points at an SDK containing at least one NDK (it picks the highest version and prints its path); build-tools contain `zipalign.exe`; NDK contains `llvm-readelf.exe`; `java` is 17+; `adb` runs; `link.exe` resolvable via the VS 2022 install; `.tools/trace_processor_shell.exe` exists and `--version` reports the pinned Perfetto version (`$PerfettoVersion` at the top of the script). Later scripts dot-source `scripts/check-env.ps1 -Quiet -PassThru` to obtain `@{ NdkDir=...; ZipAlign=...; ReadElf=...; Adb=...; TraceProcessor=... }`.

- [ ] **Step 1: Write `scripts/check-env.ps1`** with the contract above (the script is the test). Parameters: `[switch]$Quiet`, `[switch]$PassThru`. Pin `$PerfettoVersion` to the latest stable Perfetto release at implementation time.
- [ ] **Step 2: Run it before installing anything**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-env.ps1`
Expected: exit code 1 with `MISSING rustup target aarch64-linux-android`, `MISSING rustup target x86_64-linux-android`, `MISSING cargo-ndk`, `MISSING trace_processor_shell`.
- [ ] **Step 3: Install the missing pieces** — `rustup target add aarch64-linux-android x86_64-linux-android`; `cargo install cargo-ndk --locked`; download the pinned Perfetto release's `windows-amd64` archive from the `google/perfetto` GitHub releases and extract `trace_processor_shell.exe` into `.tools/`.
- [ ] **Step 4: Re-run the script** — Expected: exit 0, every line `OK`, NDK path printed (`...\ndk\30.0.16248370`).
- [ ] **Step 5: Write `.gitignore`** with the ignore list from File Structure.
- [ ] **Step 6: Commit** (only if authorized): `git add scripts/check-env.ps1 .gitignore && git commit -m "chore: toolchain preflight"`

---

### Task 2: Cargo workspace and `proviewer_core` with a uniffi export

**Files:**
- Create: `Cargo.toml`, `.cargo/config.toml`, `core/Cargo.toml`, `core/src/lib.rs`

**Interfaces:**
- Produces (Rust, exported through uniffi): `pub fn engine_version() -> String` returning `env!("CARGO_PKG_VERSION")`. Crate name `proviewer_core`, `crate-type = ["cdylib", "lib"]`, version `0.1.0`. Uses `uniffi::setup_scaffolding!()` and `#[uniffi::export]` (proc-macro mode, no UDL). Kotlin namespace will be `uniffi.proviewer_core`.
- Root `Cargo.toml`: `members = ["core"]` (later tasks append explicitly); `[profile.release]` sets `panic = "unwind"` explicitly with a comment pointing at the uniffi requirement.
- `.cargo/config.toml` adds `rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]` under `[target.aarch64-linux-android]` and `[target.x86_64-linux-android]` (defense in depth; Task 5 is the authority).

- [ ] **Step 1: Write the failing test** in `core/src/lib.rs`: `#[test] fn engine_version_matches_cargo_pkg() { assert_eq!(engine_version(), env!("CARGO_PKG_VERSION")); }`
- [ ] **Step 2: Run** `cargo test -p proviewer_core` — Expected: FAIL (`engine_version` not found / crate missing).
- [ ] **Step 3: Create the workspace and crate**; implement `engine_version` with the uniffi proc-macro export. Choose and pin the current stable `uniffi` release in `[workspace.dependencies]`.
- [ ] **Step 4: Run** `cargo test -p proviewer_core` — Expected: 1 passed.
- [ ] **Step 5: Commit** (only if authorized): `git add Cargo.toml Cargo.lock .cargo core && git commit -m "feat(core): workspace and uniffi engine_version"`

---

### Task 3: Kotlin bindings generation and Android cross-build scripts

**Files:**
- Create: `tools/uniffi-bindgen/Cargo.toml`, `tools/uniffi-bindgen/src/main.rs`, `scripts/gen-bindings.ps1`, `scripts/build-core.ps1`
- Modify: `Cargo.toml` (add `tools/uniffi-bindgen` to `members`)

**Interfaces:**
- Consumes: `scripts/check-env.ps1 -Quiet -PassThru` (Task 1); `proviewer_core` cdylib (Task 2).
- Produces:
  - `tools/uniffi-bindgen`: `fn main() { uniffi::uniffi_bindgen_main() }` (same uniffi version as `core`, `cli` feature).
  - `scripts/gen-bindings.ps1 [-OutDir <path>]` (default `app/build/generated/uniffi/kotlin`): builds the host `proviewer_core` and runs `cargo run -p uniffi-bindgen -- generate --library <host dll> --language kotlin --out-dir <OutDir>`; result contains `uniffi/proviewer_core/proviewer_core.kt`.
  - `scripts/build-core.ps1 [-Profile release]`: sets `ANDROID_NDK_HOME` from the preflight, runs `cargo ndk -t arm64-v8a -t x86_64 -o app/src/main/jniLibs build --profile <Profile> -p proviewer_core`; result: `app/src/main/jniLibs/arm64-v8a/libproviewer_core.so` and `.../x86_64/libproviewer_core.so`.

- [ ] **Step 1: Write both scripts and the bindgen crate** per the contracts above.
- [ ] **Step 2: Run** `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gen-bindings.ps1`, then `Select-String -Path app/build/generated/uniffi/kotlin/uniffi/proviewer_core/proviewer_core.kt -Pattern "fun engineVersion"` — Expected: one match.
- [ ] **Step 3: Run** `scripts/build-core.ps1`, then `<NdkDir>\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe -h app/src/main/jniLibs/arm64-v8a/libproviewer_core.so` — Expected: `Machine: AArch64`; the x86_64 file reports `Advanced Micro Devices X86-64`.
- [ ] **Step 4: Commit** (only if authorized): `git add tools scripts Cargo.toml Cargo.lock && git commit -m "build: uniffi bindings and cargo-ndk scripts"`

---

### Task 4: Android app scaffold calling Rust

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, wrapper files, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/proviewer/app/MainActivity.kt`, `app/src/androidTest/kotlin/com/proviewer/app/EngineHelloTest.kt`

**Interfaces:**
- Consumes: `scripts/gen-bindings.ps1`, `scripts/build-core.ps1` (Task 3); Kotlin `uniffi.proviewer_core.engineVersion(): String`.
- Produces: Gradle module `:app`, `applicationId = "com.proviewer.app"` (**placeholder: permanent once uploaded to Play — owner must confirm before first upload**), `minSdk = 29`, `targetSdk = 36`, `compileSdk` = the installed platform (37.0), `ndk.abiFilters = ["arm64-v8a", "x86_64"]`, Kotlin sources include `app/build/generated/uniffi/kotlin`. Gradle `Exec` tasks `genBindings` and `buildCore` wrap the scripts; `preBuild` depends on both. `MainActivity` shows `Text("ProViewer core ${engineVersion()}")` with test tag `engine-version`. Dependency `net.java.dev.jna:jna` as an **`@aar`** (pick a version whose `libjnidispatch.so` is 16 KB-aligned — Task 5 is the authority). ProGuard rules keep `com.sun.jna.**` and `uniffi.**`.

- [ ] **Step 1: Write the failing instrumented test** `EngineHelloTest` (Compose test rule on `MainActivity`):
  - `engine_version_is_exposed_to_kotlin`: `assertTrue(Regex("""\d+\.\d+\.\d+""").matches(engineVersion()))`
  - `main_screen_shows_engine_version`: `onNodeWithTag("engine-version").assertTextContains(engineVersion(), substring = true)`
- [ ] **Step 2: Generate the Gradle wrapper** from the cached distribution: `& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.6.0-bin\<hash>\gradle-9.6.0\bin\gradle.bat" wrapper --gradle-version 9.6.0` (run in repo root). Commit-ready wrapper must not depend on that cache afterwards.
- [ ] **Step 3: Run** `.\gradlew.bat :app:assembleDebug` — Expected: FAIL (module/app code missing).
- [ ] **Step 4: Create the Gradle project and app** per Interfaces. Pick the latest stable AGP and Kotlin that support Gradle 9.6.0 and JDK 17, Compose BOM, and Material 3; pin exact versions in `libs.versions.toml`.
- [ ] **Step 5: Build** `.\gradlew.bat :app:assembleDebug` — Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/debug/app-debug.apk` containing `lib/arm64-v8a/libproviewer_core.so` and `lib/x86_64/libproviewer_core.so`.
- [ ] **Step 6: Run the instrumented test on the emulator** — start `Medium_Phone_API_37.0` (`emulator -avd Medium_Phone_API_37.0`), wait for boot, then `.\gradlew.bat :app:connectedDebugAndroidTest` — Expected: 2 tests passed.
- [ ] **Step 7: Staleness check (Review Focus 1)**: change the `core` crate version to `0.1.1`, re-run `:app:connectedDebugAndroidTest` without any manual script call — Expected: passes and the screen text shows `0.1.1`. Revert to `0.1.0`.
- [ ] **Step 8: Commit** (only if authorized): `git add settings.gradle.kts build.gradle.kts gradle gradlew gradlew.bat gradle.properties app && git commit -m "feat(app): compose scaffold calling rust via uniffi"`

---

### Task 5: APK-wide 16 KB alignment check

**Files:**
- Create: `scripts/check-16kb.ps1`

**Interfaces:**
- Consumes: preflight tool paths (Task 1); APK from Task 4.
- Produces: `scripts/check-16kb.ps1 -Apk <path>` exits 0 only if **all** hold: (a) `zipalign -c -P 16 -v 4 <apk>` passes; (b) for every `lib/**/*.so` in the APK, each `LOAD` program header (via `llvm-readelf -l`) has `Align >= 0x4000`; (c) the APK contains `lib/arm64-v8a/libproviewer_core.so` and `lib/x86_64/libproviewer_core.so`. Prints one line per `.so` with its alignment, and the failing reason otherwise.

- [ ] **Step 1: Write the script** per the contract.
- [ ] **Step 2: Run on the debug APK** — `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-16kb.ps1 -Apk app/build/outputs/apk/debug/app-debug.apk`. Expected: exit 0, lists `libproviewer_core.so` ×2 and `libjnidispatch.so` ×2 (or the JNA ones present) all `>= 0x4000`. If JNA's library fails, bump JNA in `libs.versions.toml` until it passes (this is the authority for the JNA version).
- [ ] **Step 3: Negative self-test (Review Focus 3/4)**: set **target-scoped** flags only, so the host build used by `genBindings` is untouched (a global `RUSTFLAGS` would also hit the MSVC host link and force a full host rebuild):
  `$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS = "-C link-arg=-Wl,-z,max-page-size=4096"` and the same for `CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS`. Whether Cargo replaces or appends to the `.cargo/config.toml` value, the 4096 flag ends up last and `lld` uses the last `max-page-size`; the observed `0x1000` below confirms which. Rebuild the APK (`.\gradlew.bat --stop` first so the daemon picks up the env), run the check — Expected: exit 1 naming `libproviewer_core.so` with align `0x1000`. Then remove both env vars, `.\gradlew.bat --stop`, rebuild, re-run — Expected: exit 0 again.
- [ ] **Step 4: Runtime page-size note**: when the reference device is attached, run `adb shell getconf PAGE_SIZE` and record the value in `docs/perf/reference-device.md` (Task 7). Optionally, if `sdkmanager --list` offers an x86_64 16 KB-page system image, create an AVD from it and re-run `:app:connectedDebugAndroidTest` there; if none is offered, skip and note it.
- [ ] **Step 5: Commit** (only if authorized): `git add scripts/check-16kb.ps1 && git commit -m "build: apk-wide 16kb alignment check"`

---

### Task 6: Synthetic test corpus generator

**Files:**
- Create: `tools/gen-corpus/Cargo.toml`, `tools/gen-corpus/src/lib.rs`, `tools/gen-corpus/src/main.rs`, `corpus/README.md`
- Modify: `Cargo.toml` (add `tools/gen-corpus` to `members`)

**Interfaces:**
- Produces (`gen_corpus` lib, uses `image` with `jpeg`/`png`/`webp` features, `zip`, `anyhow`; dev-dep `tempfile`):
  - `pub fn halftone_page(width: u32, height: u32, dot_period_px: f32, angle_deg: f32) -> image::GrayImage` — rotated halftone dot screen (moiré stress test when downscaled).
  - `pub fn webtoon_strip(width: u32, height: u32) -> image::GrayImage` — tall strip with panel-like content and fine line art.
  - `pub fn write_cbz(path: &std::path::Path, entries: &[(String, Vec<u8>)]) -> anyhow::Result<()>` — entries stored in the given order; **stored** (no compression) for `.jpg/.png/.webp`, deflate otherwise; non-ASCII names written with the UTF-8 flag.
  - `pub fn generate_with_scale(out_dir: &std::path::Path, scale: f32) -> anyhow::Result<Vec<std::path::PathBuf>>` — all pixel dimensions multiplied by `scale` (min 1 px); entry counts and names never change with scale.
  - `pub fn generate_all(out_dir: &std::path::Path) -> anyhow::Result<Vec<std::path::PathBuf>>` — `generate_with_scale(out_dir, 1.0)`. Writes exactly these seven archives (dimensions at scale 1.0):

    | File | Content | Purpose |
    |---|---|---|
    | `screentone.cbz` | 6 pages 1600×2400, periods 2.0–6.0 px, JPEG | moiré / downscale quality |
    | `webtoon-long.cbz` | 3 strips 800×24000, JPEG | vertical scroll, tiling past max texture size |
    | `huge-single.cbz` | 1 image 12000×8000, JPEG | memory peak, zoom |
    | `double-spread.cbz` | 4 pages: 2 portrait 1600×2400 + 2 spreads 3200×2400 (one spread PNG) | spread splitting |
    | `mixed-small.cbz` | 20 pages 800×1200, JPEG | paged swipe |
    | `many-pages.cbz` | 500 pages 800×1200, JPEG | open-time benchmark (spec §7) |
    | `formats-and-names.cbz` | 5 entries in this stored order: `ComicInfo.xml` (small XML, deflate), `vol1/page10.webp` (lossless WebP), `vol1/page2.png` (8-bit grayscale PNG), `vol1/page1.jpg`, `第3話.jpg` | WebP/gray PNG decode, natural sort, nested dirs, UTF-8 names, non-image skip (consumed by Phase 1 tests) |

    Entry names elsewhere: `001.jpg`, `002.jpg`, … (`.png` where PNG).
  - `main`: `gen-corpus <out_dir>` (default `corpus/generated`), prints the written paths.
- `corpus/README.md`: states that real manga/comics must never be committed; the owner's own files go in `corpus/local/` (gitignored); generated files are deterministic (no RNG without a fixed seed); lists the table above.

- [ ] **Step 1: Write the failing tests** in `tools/gen-corpus/src/lib.rs`:
  - `halftone_page_has_requested_dimensions`: `halftone_page(320, 480, 3.0, 45.0)` has `dimensions() == (320, 480)`.
  - `halftone_period_changes_pixels`: pages with periods `3.0` and `4.0` are not equal.
  - `halftone_is_deterministic`: two calls with identical arguments are equal.
  - `webtoon_strip_has_requested_height`: `webtoon_strip(100, 5000).height() == 5000`.
  - `cbz_roundtrip_preserves_order_and_count`: write 3 entries (one with a non-ASCII name), reopen with `zip::ZipArchive`, names equal input order byte-for-byte.
  - `cbz_stores_images_and_deflates_others`: `.jpg/.png/.webp` entries have `CompressionMethod::Stored`, `ComicInfo.xml` has `Deflated`.
  - `generate_writes_expected_archives`: `generate_with_scale(tempdir, 0.05)` — file names equal the seven above; entry counts: screentone 6, webtoon-long 3, huge-single 1, double-spread 4, mixed-small 20, many-pages 500, formats-and-names 5; `formats-and-names.cbz` entry names equal the table's order exactly, and its `.webp` entry decodes. (Dimensions are **not** asserted here, since they are scaled.)
  - `huge_single_full_scale_dimensions` — `#[ignore]`, run manually in Step 4: `generate_all` into a tempdir; `huge-single.cbz` first entry reports 12000×8000 via `image::ImageReader::with_guessed_format` + `into_dimensions` (no full decode).
- [ ] **Step 2: Run** `cargo test -p gen-corpus` — Expected: FAIL (items not defined).
- [ ] **Step 3: Implement** the functions and `main`.
- [ ] **Step 4: Run** `cargo test -p gen-corpus` — Expected: all non-ignored pass. Then `cargo test -p gen-corpus --release -- --ignored` — Expected: 1 passed. Then `cargo run --release -p gen-corpus -- corpus/generated` — Expected: 7 `.cbz` files written.
- [ ] **Step 5: Commit** (only if authorized): `git add tools/gen-corpus corpus/README.md Cargo.toml Cargo.lock && git commit -m "feat(tools): synthetic test corpus generator"`

---

### Task 7: Benchmark skeleton and reference-device facts

**Files:**
- Create: `core/benches/engine.rs`, `benchmark/build.gradle.kts`, `benchmark/src/main/AndroidManifest.xml`, `benchmark/src/main/kotlin/com/proviewer/benchmark/StartupBenchmark.kt`, `docs/perf/reference-device.md`
- Modify: `core/Cargo.toml` (criterion dev-dependency + `[[bench]] name = "engine", harness = false`), `settings.gradle.kts` (include `:benchmark`), `app/build.gradle.kts` (add `benchmark` build type), `app/proguard-rules.pro`

**Interfaces:**
- Produces: criterion bench `engine_version` (skeleton proving wiring; real benches arrive in Phase 1). App build type `benchmark`: `initWith(release)`, `isMinifyEnabled = true`, `isDebuggable = false`, signed with the debug keystore, `<profileable android:shell="true"/>`. Macrobenchmark `StartupBenchmark` with `startup()` — `StartupTimingMetric()` + `FrameTimingMetric()`, `iterations = 10`, `StartupMode.COLD`, `CompilationMode.None()`, after launch asserting via UiAutomator that text starting with `ProViewer core ` is visible.

- [ ] **Step 1: Write the criterion bench** `core/benches/engine.rs` (one `bench_function("engine_version", ...)`) and run `cargo bench -p proviewer_core` — Expected: runs and prints a time (host numbers are only a wiring check).
- [ ] **Step 2: Write `StartupBenchmark` and the module/build-type wiring** per Interfaces. **Owner dependency:** connect the reference device (USB or wireless debugging, screen unlocked, display locked to its max refresh rate); confirm `adb devices` lists it.
- [ ] **Step 3: Run on the device** — `.\gradlew.bat :benchmark:connectedBenchmarkAndroidTest` — Expected: passes; results JSON produced. A failure to find the text means R8 broke uniffi/JNA (Review Focus 2): fix the keep rules in `proguard-rules.pro` and re-run until it passes.
- [ ] **Step 4: Copy the results JSON** to `docs/perf/baselines/phase0-startup-<yyyy-mm-dd>.json`.
- [ ] **Step 5: Fill `docs/perf/reference-device.md`** with output of: `adb shell getprop ro.product.model`, `ro.product.manufacturer`, `ro.build.version.release`, `ro.build.version.sdk`, `ro.soc.model`; `adb shell getconf PAGE_SIZE`; `adb shell dumpsys display` (supported modes with fps and the active mode id); `adb shell cat /proc/meminfo | Select-String MemTotal`; `adb shell perfetto --version`; plus the exact settings used for testing (refresh rate locked to max, battery saver off, performance mode state, brightness).
- [ ] **Step 6: Commit** (only if authorized): `git add core benchmark app settings.gradle.kts docs/perf && git commit -m "test: benchmark skeleton and reference device facts"`

---

### Task 8: Frame-stat tooling (Perfetto FrameTimeline) and provisional absolute budgets

**Files:**
- Create: `tools/framestat/Cargo.toml`, `tools/framestat/src/lib.rs`, `tools/framestat/src/main.rs`, `tools/framestat/tests/fixtures/frames.csv`, `tools/framestat/tests/fixtures/display.txt`, `scripts/perfetto/frametimeline.pbtx`, `scripts/perfetto/frames.sql`, `scripts/perf-capture.ps1`, `docs/perf/budgets.md`
- Modify: `Cargo.toml` (add `tools/framestat` to `members`)

**Why Perfetto, not `dumpsys gfxinfo framestats`:** framestats keeps only the last ~120 frames (≈ 0.65 s at 185 Hz), sees only HWUI-drawn frames (blind to SurfaceView/GL renderers), and "duration > 1 vsync" is not jank on a pipelined renderer. FrameTimeline records every frame for the whole trace and SurfaceFlinger classifies jank itself. (spec §7)

**Interfaces:**
- `scripts/perfetto/frametimeline.pbtx` (Perfetto text config): one buffer (64 MB, `RING_BUFFER`); data sources `android.surfaceflinger.frametimeline` and `linux.process_stats` (`scan_all_processes_on_start: true`, needed to map frames to a package); `duration_ms: {{DURATION_MS}}` substituted by the capture script.
- `scripts/perfetto/frames.sql`: returns the app's own frames in time order, one row per frame:
  ```sql
  SELECT a.ts, a.dur, a.jank_type, a.present_type, a.layer_name
  FROM actual_frame_timeline_slice a
  JOIN process p USING (upid)
  WHERE p.name = '{{PACKAGE}}'
  ORDER BY a.ts;
  ```
  (`{{PACKAGE}}` substituted by the script into a temp copy.)
- `framestat` lib (dependencies: `std`, `thiserror` only):
  - `pub struct Frame { pub dur_ms: f64, pub jank_type: String }`
  - `pub struct FrameStats { pub frames: usize, pub janky: usize, pub janky_pct: f64, pub p50_ms: f64, pub p90_ms: f64, pub p95_ms: f64, pub p99_ms: f64 }`
  - `pub fn frame_budget_ms(hz: f64) -> f64` (`1000.0 / hz`; informational)
  - `pub fn parse_frames_csv(csv: &str) -> Result<Vec<Frame>, ParseError>` — CSV as printed by `trace_processor_shell -q`; finds columns **by header name** (`dur` in ns, `jank_type`), tolerates double-quoted values and any column order; header-only input → `Ok(vec![])`; missing header or required column → `Err(ParseError::MissingColumn(name))`.
  - `pub fn is_janky(jank_type: &str) -> bool` — split on `,`, trim; `true` if any part is not `None` and not `Buffer Stuffing` (buffer stuffing alone is back-pressure, not a missed frame). Empty string → `false`.
  - `pub fn summarize(frames: &[Frame]) -> FrameStats` — `janky` via `is_janky`; `janky_pct = 100 * janky / frames` (0 when empty); duration percentiles by nearest-rank.
  - `pub fn parse_active_refresh_hz(dumpsys_display: &str) -> Option<f64>` — finds `mActiveModeId=<id>` and the mode with that `id=` and returns its `fps=` value; `None` if absent.
  - `main`: `framestat frames <frames.csv>` prints `frames,janky,janky_pct,p50,p90,p95,p99` as one CSV line; `framestat refresh <display.txt>` prints the Hz or exits 2.
- `scripts/perf-capture.ps1 -Package <id> -Scenario <name> -Action <webtoon-scroll|paged-swipe|manual> -ExpectedHz <double> [-Repeat 30] [-ManualSeconds 15]`:
  1. `dumpsys display` → `framestat refresh`; **exit 1** with "lock the display to its max refresh rate" if active Hz ≠ `-ExpectedHz`.
  2. Push the config with `DURATION_MS` = estimated action time + 3 s to `/data/local/tmp/`, start tracing in the background via stdin (Android 12+ SELinux blocks `perfetto -c <file>` from `/data/local/tmp`): `adb shell "cat /data/local/tmp/pv-ft.pbtx | perfetto --txt -c - -o /data/misc/perfetto-traces/pv.pftrace --background"`; wait 1 s for data sources to start.
  3. Perform the action (`adb shell input swipe` loops for scripted ones; wait `-ManualSeconds` for `manual`, e.g. pinch-zoom by the owner). During the action, sample `dumpsys meminfo <pkg>` TOTAL PSS about once per second and keep the **max**.
  4. Wait until the `perfetto` process has exited, `adb pull` the trace to `docs/perf/traces/<yyyy-mm-dd>-<package>-<scenario>.pftrace`.
  5. Re-check the active refresh rate; **exit 1 without writing a row** if it changed during the run (Review Focus 5).
  6. `trace_processor_shell -q <frames.sql> <trace>` → frames CSV → `framestat frames`; **exit 1** if `frames == 0` (wrong package name or a renderer FrameTimeline cannot attribute — Review Focus 6).
  7. Append `date,package,scenario,hz,frames,janky,janky_pct,p50,p90,p95,p99,peak_pss_kb` to `docs/perf/baselines/<yyyy-mm-dd>-<package>.csv`.

- [ ] **Step 1: Write fixtures and failing tests** in `tools/framestat/src/lib.rs` (tests module):
  - Fixtures: `frames.csv` — header `"ts","dur","jank_type","present_type","layer_name"` (quoted, as `trace_processor_shell` prints it) with 6 rows: jank types `None`, `None`, `Buffer Stuffing`, `App Deadline Missed`, `App Deadline Missed, Buffer Stuffing`, `None`. `display.txt` — a `dumpsys display` excerpt containing `mActiveModeId=3` and modes with `id=2 … fps=120.0` and `id=3 … fps=185.0`. Replace/add real samples in Step 6.
  - `frame_budget_for_185hz`: `(frame_budget_ms(185.0) - 5.405).abs() < 0.001`.
  - `parse_frames_csv_reads_by_header`: fixture yields 6 frames, `dur_ms == dur / 1e6`; the same data with columns reordered parses to the same result.
  - `parse_frames_csv_header_only_is_empty`: header line alone → `Ok` with 0 frames.
  - `parse_frames_csv_errors_without_required_column`: CSV without `jank_type` → `Err(ParseError::MissingColumn("jank_type"))`.
  - `is_janky_classification`: `None`→false, `Buffer Stuffing`→false, `""`→false, `App Deadline Missed`→true, `App Deadline Missed, Buffer Stuffing`→true, `Prediction Error`→true.
  - `summarize_counts_jank_and_percentiles`: fixture → `frames == 6`, `janky == 2`, `janky_pct ≈ 33.33`; separately, 100 non-janky frames with durations `1.0..=100.0` ms → `p50 == 50.0`, `p90 == 90.0`, `p95 == 95.0`, `p99 == 99.0`.
  - `parse_active_refresh_picks_active_mode`: `display.txt` → `Some(185.0)`; `""` → `None`.
- [ ] **Step 2: Run** `cargo test -p framestat` — Expected: FAIL (items not defined).
- [ ] **Step 3: Implement** the lib functions and `main` per Interfaces.
- [ ] **Step 4: Run** `cargo test -p framestat` — Expected: all pass.
- [ ] **Step 5: Write the Perfetto config, SQL and `scripts/perf-capture.ps1`** per Interfaces. Smoke-test on a package that certainly produces frames: `scripts/perf-capture.ps1 -Package com.android.settings -Scenario smoke-scroll -Action webtoon-scroll -Repeat 10 -ExpectedHz 185` with Settings open on its main list — Expected: one CSV row with `frames > 0`, a `.pftrace` in `docs/perf/traces/`. Running it with `-ExpectedHz 120` while the display is at 185 Hz exits 1 with the lock-refresh message. Running with `-Package com.proviewer.app` (static screen) exits 1 with the zero-frames message.
- [ ] **Step 6: Real-sample fixtures and device sanity capture.** From the Step 5 Settings trace, export the frames CSV into `tools/framestat/tests/fixtures/frames-real.csv` and the real `dumpsys display` output into `display-real.txt`; add tests that they parse (non-empty frames; Hz = 185). Run the same Settings capture 3 times and record the rows in `docs/perf/baselines/` — this is the **measurement-noise floor** of the method on the reference device (janky % of a well-optimized system app), used to judge whether the 1 % budget is achievable and meaningful.
- [ ] **Step 7: Write `docs/perf/budgets.md`** with **absolute, provisional** budgets for later phases (no competitor baseline — spec §1, §7), each with the scenario, corpus file, and how it is measured:
  - (a) Frames — janky-frame % ≤ **1 %** at the locked max refresh rate (185 Hz) in each scenario: `webtoon-long.cbz` → `webtoon-scroll`, `mixed-small.cbz` → `paged-swipe`, `screentone.cbz` / `huge-single.cbz` → `manual` pinch-zoom + pan. Measured from Phase 2 (paged, zoom) and Phase 3 (Webtoon) with `scripts/perf-capture.ps1`. Frame-duration percentiles are reported for information only, **not** a budget (pipelined frames legitimately exceed one vsync). If the Step 6 noise floor is already near 1 %, say so and propose a revised number.
  - (b) Page turn to sharp image — prefetched < 100 ms, cold < 300 ms (measurement method decided in Phase 2 when the reader exists, since frame timing cannot measure it).
  - (c) Memory — peak PSS proposed in Phase 1 from the engine's measured cache + decode footprint, then enforced in Phase 2 on `huge-single.cbz` and `webtoon-long.cbz`.
  - (d) Open time for `many-pages.cbz` — measured in Phase 1 benches, budget set then.
  Each line is marked `provisional` until the owner approves; later phases may tighten a budget with owner approval, never loosen it silently.
- [ ] **Step 8: Commit** (only if authorized): `git add tools/framestat scripts/perfetto scripts/perf-capture.ps1 docs/perf Cargo.toml Cargo.lock && git commit -m "test: frametimeline tooling and provisional budgets"`

---

## Phase 0 Exit Gate

Phase 0 is complete when all hold, and the owner approves `docs/perf/budgets.md`:

1. Hello app (debug, and the minified `benchmark` build) runs on the reference device and shows the Rust engine version.
2. `scripts/check-16kb.ps1` passes on the APK, with the negative self-test having failed as expected once.
3. `cargo test --workspace` is green (core, gen-corpus, framestat).
4. `docs/perf/reference-device.md`, the startup baseline JSON, and the Settings noise-floor CSV exist.

After approval, brainstorm-free: write the **Phase 1 (Engine MVP)** plan from the design spec, informed by the numbers recorded here.

## Self-Review Notes

- **Spec coverage (Phase 0 row):** repo setup (Tasks 1–2), Cargo + Gradle (2, 4), uniffi hello (2–4), arm64 `.so` (3, 4), 16 KB check (5), benchmark skeleton (7), test corpus (6), reference device + budgets (7, 8). The spec's open "git init" was done before this plan.
- **Spec §7 alignment:** frame metrics use Perfetto FrameTimeline and janky-frame %, as the spec now requires; memory is total process PSS (peak sampled during the action).
- **No competitor baseline (owner decision 2026-11-18):** Perfect Viewer is not measured; budgets are absolute. Page-turn latency budget stays provisional until Phase 2, when our reader exists to measure it.
- **Corpus serves Phase 1 too:** `formats-and-names.cbz` (WebP, gray PNG, natural sort, UTF-8, non-image entry) and `many-pages.cbz` (open time) are generated now so Phase 1 tests and benches have fixed inputs.
- **Revision 2026-11-18 (after review):** replaced the `gfxinfo framestats` pipeline (`gfxstat`) with Perfetto FrameTimeline (`framestat`); dropped the "p95 ≤ frame budget" rule; refresh rate checked before and after; target-scoped flags for the 16 KB negative test; explicit `panic = "unwind"`; explicit workspace members; corpus test inconsistency (scaled vs. full-size `huge-single`) fixed; corpus extended.
- **My calls the owner can override:** `applicationId` placeholder, targetSdk 36, synthetic (not real) corpus, Windows-only PowerShell scripts, Macrobenchmark with `CompilationMode.None()`, defensive linker flag, janky = any `jank_type` other than `None`/`Buffer Stuffing`.
