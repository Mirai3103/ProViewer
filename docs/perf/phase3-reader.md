# Phase 3 reader record

Status: **no performance numbers exist and none were collected.** As in Phase 2 (owner decisions D1/D2, 2026-11-19),
measurement is the **in-app Benchmark screen**, run only by the owner, on any phone, without adb; Phase 3 exit is
**functional verification on the emulator**. Emulator numbers are invalid for performance, so this file records the
exit-verification evidence and the fuzz rerun only. Phase 2 record: `docs/perf/phase2-reader.md`; budgets:
`docs/perf/budgets.md`; reference device: `docs/perf/reference-device.md` (OnePlus CPH2767, 1080×2378 @ 120 Hz).

Phase 3 delivered (branch `phase-3`): reading-mode, unit and geometry models; a byte-capped image budget and a page-size
prober; two-page units with spread splitting in the pager; auto mode selection with an overlay override; a pure stacked
Webtoon layout with anchors, a tile grid and a tile manager that evicts under the cap; a self-drawn tiled scrolling
viewport with fling, whole-column zoom and sharp tile re-render; and the Benchmark additions below (scenario a1, strict
b prefetched, benchmark JSON schema 2).

## Phase 3 exit status

All commands run 2026-11-22 on the host, the emulator `emulator-5554` (AVD `ProViewer_API37`) or the WSL distro
`proviewer`, on branch `phase-3`, with `ANDROID_HOME=E:\AppDataLinks\Android\Sdk` and the Android `platform-tools`
and `emulator` directories on `PATH` (the Gradle `buildCore` task runs `scripts/check-env.ps1`, which needs `adb`).
Final engine and app code: HEAD `5422721` (the only code-adjacent changes after `bd0b09e` are two test files, below; the
docs commit that follows touches no code). Nothing ran on the owner's phone, and serial `3B65BT02H6G00000` was never
targeted (`adb devices` listed only `emulator-5554`).

| Gate | Command | Exit | Result |
|---|---|---|---|
| 1. Rust workspace tests | `cargo test --workspace` (first run), then `cargo test --workspace --no-fail-fast` after the fix below | 101, then 0 | first run: `core/tests/fuzz_regressions.rs::harness_runs_on_generated_seeds` FAILED (`left: 6, right: 5`); after the fix: 211 passed, 0 failed, 1 ignored |
| 2. Kotlin unit tests | `gradlew :app:testDebugUnitTest --rerun-tasks` | 0 | 107 tests, 0 failures, 0 errors, 0 skipped (12 classes: BenchMath 9, BenchmarkJson 9, ImageBudget 6, PageSizes 8, PageWindow 16, ReaderLogic 12, ReadingMode 6, UnitGeometry 10, Units 9, TileGrid 7, TileManager 6, WebtoonLayout 9) |
| 3. Instrumented tests | `$env:ANDROID_SERIAL='emulator-5554'; gradlew :app:connectedDebugAndroidTest` | 0 | 74 tests, 0 failures, 0 errors, 0 skipped (6 m 20 s), with the `BenchmarkRunTest` a1 row assertions in the tree |
| 4. APK builds | `gradlew :app:assembleRelease :app:assembleBenchmark` | 0 | `app-release-unsigned.apk` (7,983,232 B), `app-benchmark.apk` (11,318,129 B) |
| 5. 16 KB alignment | `check-16kb.ps1 -Apk` on both APKs | 0 / 0 | every `lib/**/*.so` LOAD align `0x4000`, zipalign `-P 16` clean, both ABIs of `libproviewer_core.so` in each APK |
| 6. Release clean | `check-release-clean.ps1 -Apk` | 0 on release, 1 on benchmark (both as expected) | release: no `lib/*/libproviewer_benchgen.so`, no `PV_BENCHMARK_MARKER` in any `classes*.dex`; the benchmark APK reports both, by design |
| 7. Fuzz | `scripts/fuzz.ps1 -Target decode` / `-Target archive`, 900 s each | 0 / 0 | table below |

**Gate 1 failure and fix (recorded, not hidden).** `cargo test --workspace` failed on the first run: the corpus gained
`two-page.cbz` (8 entries) in `8001c48`, so `gen_corpus::generate_fuzz_seeds` now yields 6 archive seeds and 45 decode
seeds (35 + 8 extracted images + the 2 damaged-JPEG forms), but the assertions in
`core/tests/fuzz_regressions.rs::harness_runs_on_generated_seeds` still said 5 and 37. This was a stale test constant,
not an engine regression (the harness itself ran clean on all seeds before the count assertion). The assertions were
updated in `3dbf9fd` (`test(core): update fuzz seed counts for two-page.cbz`); the full workspace run then passed
(first-run failure was the only failing test in the workspace; the run was repeated with `--no-fail-fast` to make sure no
later crate was masked by the stop-at-first-failure behaviour).

**Gate 3 note.** `BenchmarkRunTest` carries two new assertions that the on-screen results table shows the `a1 [Software]`
and `a1 [Hardware]` rows. They were in the working tree for the gate-3 run and were committed as `5422721`
(`test(bench): assert the a1 rows in the results table`) by a concurrent agent working in the same tree while the run was
in progress; the content is byte-identical to the edit the run covered. No other source changed between the run and
`5422721`.

The release-clean claim is re-verified formally here on the freshly built APKs: the script checks the benchmark-only `.so`
and the `PV_BENCHMARK_MARKER` ASCII bytes (the marker the benchmark JSON's `generator` field also carries).

## Fuzz record

Targets `decode` and `archive`, cargo-fuzz in the WSL distro `proviewer` (preflight inside `scripts/fuzz.ps1`; nothing
installed), 900 s per target, run sequentially on 2026-11-22 on `core/src` as of HEAD `5422721`, the final Phase 3 engine
code. Both targets needed this rerun: the engine changed since the Phase 2 rerun (`df9ad62`) in `core/src/resize.rs`
(seam-exact scaled tiles), `engine.rs` (fit-aware prefetch and scaled-tile render), `android/pixels.rs`, `handles.rs` and
`decode/jpeg.rs` (`5b15af6` and the following core edits). The seed corpus is regenerated by `fuzz.ps1` before each run and
now includes `two-page.cbz`: libFuzzer reported 45 files in `corpus-seeds/decode` (43 extracted images from the six
seed-eligible archives plus the two damaged-JPEG seeds) and 6 in `corpus-seeds/archive`. Exit 0 on both targets, "finished
cleanly" printed by the script, and no crash artifact written (`core/fuzz/artifacts/` holds no files afterwards).
`libfuzzer-sys` installs an abort-on-panic hook, so 0 crashes also means no decoder, resizer or zip-reader panic in the
fuzzed paths. The archive run continued from the accumulated local corpus (4074 files at start) in addition to the seeds.

| Target | Execs | Duration | Execs/s | Coverage | Features | Corpus | Peak RSS | Crashes |
|---|---|---|---|---|---|---|---|---|
| `decode` | 441,852 | 903 s | 489 | 3203 | 10675 | 1449 | 423 MB | 0 |
| `archive` | 1,026,086 | 902 s | 1137 | 3480 | 12234 | 936 | 452 MB | 0 |

Compared with the Phase 2 rerun (decode cov 3175, archive cov 3472 at `df9ad62`): decode coverage is up by 28 and archive
by 8, both within what run variance and the extra `two-page.cbz` seeds explain; no crash and no new limit bypass was found.
Execs/s depends on host load (this run shared the machine with an emulator and other tooling) and is not a throughput
measurement. The fuzz harnesses exercise decode and the archive reader only: the new resize and tile paths are covered
by the Rust unit and integration tests of gate 1, not by a dedicated fuzz target.

## What changed in the Benchmark screen (spec §6.6)

Phase 3 plan Task 12 (`bd0b09e`). The Phase 2 description of how the screen measures stays valid
(`docs/perf/phase2-reader.md`); these are the differences.

- **a1 — Webtoon scroll**, a new scenario: `webtoon-long.cbz` at scale 1 with the layout choice left on Auto, which
  resolves Webtoon. The scripted window: a steady descent at **4000 px/s** (one `scrollBy` per composed frame, scaled by
  the frame duration so the speed is refresh-rate independent) to `maxScroll`, then **4 upward flings** at 8000 px/s
  release velocity, each followed by a 300 ms settle. Frames are recorded over the whole window and the same jank rule as
  a2/a3 applies (`TOTAL_DURATION > DEADLINE`, first-draw frames excluded). Reported for information only, not budgeted:
  `placeholderFrames` (frames that drew a placeholder in the visible Webtoon area) and `peakImageBytes` (the image
  budget's highest held bytes in the window). The scenario throws if the flings never moved the viewport off `maxScroll`
  (a direction guard, never a timing check). Like every scenario it runs once per upload strategy in an order that alternates per run.
- **b prefetched is now strict (E5).** The turn is a **snapped (non-animated)** turn, and the sharp-draw probe fires only
  while the target is the current unit, so t1 is the first frame that draws the target page sharp as the current page.
  The Phase 2 settle-timestamp fallback (and its per-sample flag) is gone. Cold is unchanged.
- **Benchmark JSON `schemaVersion` 2**: adds the a1 result and the information fields `placeholderFrames` and
  `peakImageBytes` (null for scenarios other than a1); the `b` prefetched entries no longer carry a fallback flag.
- **a3-huge** pins the split to Off (as every scenario does), so the new auto split cannot change its geometry.

**Phase 2 caveat that stays.** The owner's only real-device run so far (vivo V2364A, 60 Hz, 2026-11-21) predates all of
this: its b-prefetched reading (~416 ms) was the scripted animation time, **not recorded as passing**, and the 60 Hz
condition differs from the budgets' "locked 120 Hz". The snapped-turn / strict-probe b has not yet been run on a real
device.

## Enforced design cap (E3)

`IMAGE_CAP_BYTES = 192 MiB` (`app/src/main/kotlin/com/proviewer/app/reader/ImageBudget.kt`): one accounting of the
bytes of decoded base bitmaps, Webtoon tiles and detail bitmaps the reader holds; every holder reserves before
allocating and releases when the image is gone, so `usedBytes` never exceeds the cap. It is a **design cap enforced in code
and covered by `ImageBudgetTest`, `PageWindowTest`, `TileManagerTest` and the instrumented `WebtoonTest`**, not a measured
budget, and not a replacement for the dropped row c. The Benchmark screen's a1 `peakImageBytes` is for information only.

## Phase 2 on-screen leftovers (Task 1, `1b717e2`)

The two Phase 2 visual checks left open were closed with regression tests against the **unchanged** reader; no production
code was touched. `ReaderScreenTest.fit_width_tall_page_starts_at_origin_and_pans_to_bottom` and
`fit_height_wide_page_starts_at_origin_and_pans_to_right` verify that a fit-width page taller than the viewport and a
fit-height page wider than it start at the alignment corner and pan to the far edge (pixel blocks compared against the base
bitmap). `ZoomTest.zoomed_pan_to_edge_in_one_drag_does_not_turn` verifies that one long drag on a zoomed page pans to the
edge without turning the page. All three passed on the emulator, and are part of gate 3's 74 tests.

## Hand-off to the owner (D2 checkpoint)

- Benchmark APK (built by gate 4, checked by gates 5–6): `E:\Code\ProViewer\app\build\outputs\apk\benchmark\app-benchmark.apk`.
- The owner installs it themselves, reads a Webtoon and a manga of their choosing, and can run the Benchmark screen
  whenever they choose. That run is the performance checkpoint (D1); a budget miss found there is fixed in a later round,
  not a Phase 3 blocker. No plan step installs onto or runs anything on the phone.

## Known limitations / notes

- **No numbers yet.** Rows a1, a2, a3 and b remain `approved` with no recorded value for the Phase 3 code; the first
  real numbers come from the owner's Benchmark run. Emulator runs produce JSON but are marked invalid for performance.
- **Short-image Webtoon chapters detect as Single.** Auto mode samples page sizes to detect a strip; a chapter whose pages
  are not tall enough (short images stacked as a Webtoon) is resolved as Single paged and needs the overlay override.
- **Recycled-bitmap draw crash (fixed in the final review wave).** `ReaderScreenTest.rotation_keeps_page_and_rerenders`
  flaked with `Canvas: trying to use a recycled bitmap`. This is a Phase 3 regression, not inherited: it first showed on
  `8001c48` (Phase 3 Task 5, which rewrote `PageWindow` and `ZoomableUnit`); the Phase 2 suite at `ff72a45` was green. The
  mechanism: `ZoomableUnit` captures a page bitmap at composition time (the base `Image` of each part and the settled
  detail `Image`), while every release path recycles it on main (`PageWindow` eviction and re-spec, the window closing
  when the layout becomes Webtoon, `details.reset`/`host.clear`). Compose applies the snapshot change only on its next
  recomposition, so any re-record of that node in between draws a recycled bitmap. Phase 3 added new triggers: Auto
  rotation flips Single <-> TwoPage and re-specs every visible base, the Webtoon switch closes the window, and every budget
  release schedules a window update. The guard: the base and detail bitmaps are now drawn by a plain draw modifier
  (`drawBehind`) that skips a recycled bitmap, instead of `Image`, because `Image` paints inside its own clip layer, which
  Compose re-records on its own without any draw modifier placed around it. Every release runs on main, so the check cannot
  race one; the stale frame shows nothing until the recomposition drops the bitmap. The Webtoon viewport's `drawPage`
  skips recycled tiles the same way. Coverage under Auto:
  `TwoPageTest.rotations_and_layout_cycles_never_draw_recycled_bitmaps` (5 portrait/landscape round trips on
  `two-page.cbz`, then 3 Single/TwoPage/Webtoon/Auto cycles); it passed in every run that reached it after the guard.
- **Known follow-up: a never-idle hang in `ReaderScreenTest.rotation_keeps_page_and_rerenders` under Auto.** With the
  Single pin removed the test hangs in roughly one run in four (the app process spins at 100 % CPU in the main looper, the
  compose rule never idles, and no crash is logged; once in the full connected suite). It also hung on the code before the
  draw guard, so it is independent of the recycled-bitmap fix, and 0 of 8 pinned runs hung, so the pin is kept. It needs its
  own investigation (an Espresso idling look, or a message/invalidation flood after the activity recreation into TwoPage).
- **Fuzz coverage caveats** are unchanged from Phase 1/2 (decode harness limits 4 MP / 16 K, seed set lacks progressive,
  CMYK, 16-bit PNG, alpha and lossy WebP files, libjpeg-turbo C code not ASAN-instrumented).
