# Phase 2 reader record

Status: **no performance numbers exist and none were collected.** Per owner decisions D1/D2 (2026-11-19), measurement is
the **in-app Benchmark screen**, run only by the owner, on any phone, without adb; Phase 2 exit is **functional
verification on the emulator** (D2). Emulator numbers are invalid for performance, so this file records the fuzz rerun and
the exit-verification evidence only (spec §6.5). Phase 1 record: `docs/perf/phase1-engine.md`; budgets:
`docs/perf/budgets.md`; reference device: `docs/perf/reference-device.md` (OnePlus CPH2767, 1080×2378 @ 120 Hz).

## Phase 2 exit status (spec §6.5, D2)

All commands run 2026-11-21 on the host or the emulator `emulator-5554` (AVD `ProViewer_API37`) on branch `phase-2` at
commit `df9ad62`. One timing note for the record: an uncommitted bench-source fix batch (not made by this task) landed in
the working tree mid-verification; gates 1 and 3–7 verified `df9ad62` code (the batch touches only the `bench` source set,
which `release` does not compile), while gate 2's forced rerun compiled the newer bench sources. Both states pass: the
first unit-test invocation was UP-TO-DATE against the `df9ad62`-era build and the rerun executed clean. Nothing ran on the
owner's phone, and serial `3B65BT02H6G00000` was never targeted.

| Gate | Command | Exit | Result |
|---|---|---|---|
| 1. Rust workspace tests | `cargo test --workspace` | 0 | 204 passed, 0 failed, 1 ignored |
| 2. Kotlin unit tests | `gradlew :app:testDebugUnitTest` | 0 | first invocation UP-TO-DATE (inputs unchanged since the Task 10 run); a forced `--rerun` then executed it: 31 tests, 0 failures (BenchMath 9, BenchmarkJson 7, PageWindow 7, ReaderLogic 8) |
| 3. Instrumented tests | `ANDROID_SERIAL=emulator-5554 gradlew :app:connectedDebugAndroidTest` | 0 | 43 tests, 0 failures, 0 errors, 0 skipped (2 m 58 s) |
| 4. APK builds | `gradlew :app:assembleRelease :app:assembleBenchmark` | 0 | `app-release-unsigned.apk`, `app-benchmark.apk` |
| 5. 16 KB alignment | `check-16kb.ps1` on both APKs | 0 / 0 | every `lib/**/*.so` LOAD align `0x4000`, zipalign `-P 16` clean, both ABIs of `libproviewer_core.so` in each APK |
| 6. Release clean | `check-release-clean.ps1` | 0 on release, 1 on benchmark (both as expected) | release: no `lib/*/libproviewer_benchgen.so`, no `PV_BENCHMARK_MARKER` in any `classes*.dex`; the benchmark APK reports both, by design |
| 7. Fuzz | `scripts/fuzz.ps1` `-Target decode` / `-Target archive`, 900 s each | 0 / 0 | table below |

The release-clean claim closes formally here: Task 10 verified it structurally (bench source set only in the
`debug`/`benchmark` variants, the `.benchfiles` FileProvider authority absent from the merged release manifest) plus one
implementer run; gate 6 re-ran the script on the freshly built APKs of this task and recorded the exits above. The script
itself checks the benchmark-only `.so` and the `PV_BENCHMARK_MARKER` ASCII bytes (the marker the benchmark code embeds;
the benchmark JSON schema's `generator` field carries the same string).

## Fuzz record (Task 11)

Targets `decode` and `archive`, cargo-fuzz in the WSL distro `proviewer` (preflight: distro present, `cargo +nightly fuzz
--version` → cargo-fuzz 0.13.2; nothing installed), 900 s per target, run sequentially on 2026-11-21 on `core/src` as of
commit `df9ad62` — the final Phase 2 engine code. The seed corpus was regenerated first (`cargo run --release -p
gen-corpus -- --fuzz-seeds core/fuzz/corpus-seeds`; `fuzz.ps1` regenerates the same set again before each run): 37 decode
seeds including the two damaged-JPEG seeds that exercise the D3 libjpeg-warning-tolerance path (baseline JPEG truncated
mid-scan, extraneous bytes before SOS), and 5 archive seeds. Exit 0 on both targets and no crash artifact written
(`core/fuzz/artifacts/*/` empty afterwards). `libfuzzer-sys` installs an abort-on-panic hook, so 0 crashes also means no
decoder or zip-reader panic in the fuzzed paths.

Both targets needed this rerun on the final code: decode changed for D3 (Task 2), and the archive side changed too —
`core/src/archive/mod.rs`, `engine.rs` and `error.rs` in `f7bc202` (open from a file descriptor) and `core/src/android/ffi.rs`
in `b21a390` (JNI pixel path) — unlike the Phase 1 rerun, where only decode had moved.

| Target | Execs | Duration | Execs/s | Coverage | Features | Corpus | Peak RSS | Crashes |
|---|---|---|---|---|---|---|---|---|
| `decode` | 559,154 | 903 s | 619 | 3175 | 10164 | 1356 | 383 MB | 0 |
| `archive` | 1,157,524 | 901 s | 1284 | 3472 | 12077 | 931 | 462 MB | 0 |

Compared with the Phase 1 rerun (decode cov 3109, archive cov 3483 at `09045be`): decode coverage is up (the
warning-tolerance path is now seeded and covered); archive moved slightly either way, which is run variance, not a
regression signal — no crash and no new limit bypass was found. Execs/s depends on host load and is not a throughput
measurement. Phase 1's fuzz coverage caveats still hold: the decode harness uses 4 MP / 16 K limits, the seed set lacks
progressive, CMYK, 16-bit PNG, alpha and lossy WebP files, and the libjpeg-turbo C code is not ASAN-instrumented.

## How the Benchmark screen measures (spec §6.4)

What the owner's device run will produce. The screen exists only in the `benchmark` build type (minified, not
debuggable, debug-signed, installable as a plain APK without adb; the bench source set also compiles into `debug` so the
emulator instrumented tests can drive it; `release` gets a stub, checked by gate 6). On first use it generates
`mixed-small.cbz`, `screentone.cbz` and `huge-single.cbz` into app-private storage with a generator version stamp
(generation time is reported but is not a benchmark), then runs, per upload strategy (S then H; the order alternates by
run number):

- **a2** — `mixed-small.cbz`, fit screen: scripted animated page turns through the real pager, forward then back, about
  one turn per 450 ms.
- **a3** — `screentone.cbz` and `huge-single.cbz`: scripted animated zoom 1×→3×, pans, zoom out, several cycles,
  including the sharp detail re-render after each settle.
- **b** — `mixed-small.cbz` page-turn latency: cold = the page is not decoded and not rendered (fresh book or a far
  jump, 10 samples); prefetched = the turn starts with the prefetch and pre-render window idle (19 samples). t0 = the
  moment the turn is issued; t1 = the frame in which the target page's full-quality fit bitmap is first drawn (a draw
  callback plus that frame's `FrameMetrics` timing); placeholder frames do not count.

**Jank metric** (a2, a3): `Window.OnFrameMetricsAvailableListener` (API 31+); a frame is janky when
`FrameMetrics.TOTAL_DURATION > FrameMetrics.DEADLINE`, and `FIRST_DRAW_FRAME` samples are excluded. Frame-duration
percentiles are recorded for information only. On API < 31 the screen reports jank as unsupported.

**Run conditions recorded with every result:** device model, Android version, app version and git SHA, active refresh
rate before and after each scenario (a rate that is not the display's max mode is flagged), thermal status (the run
refuses to start at `MODERATE` or worse and aborts if it reaches it), battery level and charging state, and an emulator
flag — emulator results are marked `validForPerformance = false`. Output is a results table on screen and a shareable
versioned JSON (`schemaVersion` 1, `generator` = `PV_BENCHMARK_MARKER`).

**Known deviation in the b prefetched class (documented in the Task 10 report):** with Compose drawing adjacent pager
pages at rest, the once-per-bitmap sharp-draw probe fires for the prefetched target *before* the turn, so there is no
post-turn probe event to serve as t1. The implementation prefers a probe event at/after t0 when one exists; otherwise the
sample is flagged in the run log and t1 falls back to the frame containing the pager-settle timestamp. Sample counts are
unaffected; on this emulator all 19 prefetched samples took the flagged fallback, so the owner's device run may report
settle latency, not sharp-draw latency, for that class.

## Hand-off to the owner (D2 checkpoint)

- Benchmark APK (built by gate 4, checked by gates 5–6): `E:\Code\ProViewer\app\build\outputs\apk\benchmark\app-benchmark.apk`.
- The owner installs it themselves, reads a real CBZ of their choosing, and can run the Benchmark screen whenever they
  choose. That run is the performance checkpoint (D1); a budget miss found there is fixed in a later round, not a
  Phase 2 blocker (D2). No plan step installs onto or runs anything on the phone.

## Known limitations / notes

- **No numbers yet.** Every budget row a2/a3/b is still `approved` with no recorded value; the first real numbers come
  from the owner's Benchmark run. Emulator runs produce JSON but are marked invalid for performance.
- **a1 (Webtoon scroll) keeps `perf-capture.ps1` as its reference method**; its final method is revisited when Phase 3
  starts (`budgets.md`).
- **b prefetched t1 fallback** as described above; making the strict metric observable on all Compose versions is a
  reader-side change (per-visible-draw probe or not drawing off-screen pages), not a benchmark change.
- **Fuzz coverage caveats** as listed under the fuzz record (harness limits, missing seed shapes, no ASAN inside
  libjpeg-turbo).
