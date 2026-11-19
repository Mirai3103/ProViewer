# Phase 1 engine numbers

Status: **no device numbers exist.** On 2026-11-19 the owner dropped all real-device benchmarking for Phase 1
(Vietnamese verbatim: "bỏ hết đi, ko có được đo benmark gì hết trên máy thật"). Every engine timing, `pvcli` summary and
memory figure below is therefore `not measured (device benchmarks dropped by owner decision 2026-11-19)`.
Nothing here comes from the host or the emulator (spec §3), and the owner's phone must not be used for benchmarks.

Reference device (facts only, nothing was run on it for this file): OnePlus CPH2767, display mode 1080×2378 @ 120 Hz
(`docs/perf/reference-device.md`). Budgets: `docs/perf/budgets.md` (rows c and d dropped, no engine budget numbers).

## Phase 1 exit status

| Gate | Status |
|---|---|
| 1. Workspace tests | green at `7f274b0` |
| 2. Benches | benches are runnable on the host only; device numbers dropped by owner decision 2026-11-19 |
| 3. Codec comparison | platform baseline and per-candidate `.so` deltas not measured (owner decision 2026-11-19); backend stays `turbojpeg` by the owner's 2026-11-18 choice |
| 4. Fuzz | 900 s per target clean on the final decode code `09045be` (record below) |
| 5. Android build | build, `check-16kb` and `connectedDebugAndroidTest` 2/2 on `emulator-5554` (2026-11-19) |
| 6. Budgets | rows c and d dropped by owner decision 2026-11-19; a1, a2, a3, b unchanged (see the open conflict in `docs/perf/budgets.md`) |

## How the benches run (no numbers are produced for the device)

- The criterion benches in `core/benches/engine.rs` stay runnable on the host with `cargo bench -p proviewer_core`
  (corpus read from `$PV_CORPUS`). Use them for **relative before/after comparison only**. Host numbers are never device
  numbers and are not committed as baselines.
- `scripts/phase1-device-session.ps1` and `scripts/device-run.ps1` remain in the repository. They are **not to be run**
  unless the owner reinstates device benchmarking, and the owner's phone must not be used. (`device-run.ps1` and the
  driver's `-Rehearsal` mode with an `emulator-*` serial exist as correctness or wiring checks only, with emulator
  numbers invalid for performance.)
- Every bench checks its input before timing (page count 500, decoded sizes, output not blank and opaque, one decode for
  `cold-fit`, zero extra decodes for `warm-fit`), so a wrong bench fails instead of timing nothing.
- `screentone` page 0 fits at denom 1, so its "full size" and "fit denom" decodes are the same bench, `d1`; `huge-single`
  fits at denom 8, hence `d1` and `d8` (both fit denoms are asserted in the bench setup).

## Device numbers

Benches (`core/benches/engine.rs`), all `not measured (device benchmarks dropped by owner decision 2026-11-19)` (device median, runs 1 to 3):
`open/many-pages`, `open/many-pages+probe-all`, `decode/screentone-p0/d1`, `decode/mixed-small-p0/d1`,
`decode/huge-single-p0/d1`, `decode/huge-single-p0/d8`, `decode/formats-webp/d1`, `decode/formats-png/d1`,
`resize/gray-1600x2400-to-1080x1620`, `resize/rgb-1600x2400-to-1080x1620`,
`resize/huge-region-2160x4756-to-1080x2378`, `render/cold-fit`, `render/warm-fit`.

`pvcli read --fit 1080x2378` summaries (`cold_first_ms`, `p50_ms`, `p95_ms`, `max_ms`, `decodes`, `cache_hits`,
`peak_rss_kb`) for `mixed-small.cbz`, `huge-single.cbz`, `webtoon-long.cbz` and `screentone.cbz` (`--cache-mb 64`): all
`not measured (device benchmarks dropped by owner decision 2026-11-19)`.

Phase 0 app baseline (`dumpsys meminfo com.proviewer.app` TOTAL PSS): `not measured (device benchmarks dropped by owner decision 2026-11-19)`.

Engine share of page-turn latency versus budget b (cold first page, cold later pages, prefetched pages, warm render of a
cached page): `not measured (device benchmarks dropped by owner decision 2026-11-19)`. Budget b itself is approved and unchanged; how it is verified is part of the open conflict in
`docs/perf/budgets.md`.

Caveat that still holds for any future run: the `webtoon-long` pages are 800×24000, so a whole-page fit render shrinks each
page to about 79×2378 and does not represent Webtoon scrolling (tile reads of a strip are Phase 3).

## Fuzz record (Task 5)

Targets `archive` and `decode`, cargo-fuzz in the WSL distro `proviewer`, 900 s each, run on 2026-11-18 with `core/src`
as of commit `d44972f` (before Tasks 6 to 10). **Historical:** these numbers are for the code at `d44972f`, not for the
final code. `libfuzzer-sys` installs an abort-on-panic hook, so 0 crashes also means no decoder panic in the fuzzed paths.

| Target | Execs | Duration | Execs/s | Coverage | Features | Corpus | Crashes |
|---|---|---|---|---|---|---|---|
| `archive` | 2,676,141 | 901 s | 2970 | 3417 | 11487 | 1125 | 0 |
| `decode` | 1,308,049 | 901 s | 1451 | 3006 | 8962 | 1400 | 0 |

Decode code changed after `d44972f` only by the final review fixes: `core/src/decode/jpeg.rs` now also applies the
source-pixel cap to sequential multi-scan (non-interleaved) JPEGs (its Adobe-marker check now shares the new marker
walker), and `core/src/decode/mod.rs` moved the `catch_unwind` into a small helper (no behavior change). The archive
code, `limits.rs`, `error.rs` and `natsort.rs` are unchanged. Because decode changed, Gate 4 needs a rerun on the final code.

**Final rerun, 2026-11-19, on `core/src` as of commit `09045be`** (no code change after `ee3f432`; the later commits are
scripts and docs). Both targets ran 900 s sequentially in the same WSL distro, exit 0, no artifact written. The host was
shared with review and build work while they ran, so Execs/s is lower than the historical run and is not a
throughput measurement.

| Target | Execs | Duration | Execs/s | Coverage | Features | Corpus | Peak RSS | Crashes |
|---|---|---|---|---|---|---|---|---|
| `archive` | 1,590,202 | 903 s | 1761 | 3483 | 12032 | 892 | 460 MB | 0 |
| `decode` | 855,492 | 903 s | 947 | 3109 | 9471 | 1283 | 394 MB | 0 |


## Codec decision

See `docs/perf/codec-comparison.md`. Backend in `core`: `turbojpeg` 1.5.1 (libjpeg-turbo 3.1.0), kept as the owner's
2026-11-18 choice. The Rust-candidate comparison (evidence in `codec-comparison.md`) selects `turbojpeg` by the plan's rule.
The platform-decoder baseline and the per-candidate release `.so` size deltas will **not** be measured, by owner decision
2026-11-19, so the rule's last check (platform more than 25 % faster than the chosen Rust codec) stays unevaluated. This
document claims no approval of a final decision beyond the owner's choice of `turbojpeg`.

## What is slow: things Phase 2 should know

## What is slow: things Phase 2 should know

All of these follow from the code. They are **expected, unmeasured**: no device numbers exist (device benchmarks dropped by
owner decision 2026-11-19), so nothing here is confirmed or refuted.

- **Linear-light resize cost.** `render_region` converts the source rows it needs to linear `u16` (a 256-entry table),
  runs a Lanczos3 convolution on `U16` or `U16x3`, then maps back to sRGB through a 65536-entry table. The work scales
  with the source pixels the region covers plus the output pixels, and RGB does three times the channel work of gray.
  Expected, unmeasured.
- **Banded rendering.** Output is produced in bands so the linear buffer stays within 16 MiB (`BAND_BUDGET_BYTES`).
  Large sources therefore take several bands, each re-converting its filter-margin rows. Expected, unmeasured.
- **Resized output is not cached in Rust.** Prefetch only warms decoded pages, so even a cached page pays a full
  resize on every render (`render/warm-fit` is that cost). The Kotlin `Bitmap` is the cache for resized output.
  Expected, unmeasured.
- **A region at exactly the decode scale still runs the convolution.** There is no 1:1 shortcut except the whole page
  at source size. Expected, unmeasured.
- **Huge-image decode.** A 96 MP JPEG is read and entropy-decoded in full even when only 1/8 of the pixels is kept, so
  `decode/huge-single-p0/d8` is expected to be much closer to `d1` than 1/8 of it. Expected, unmeasured.
- **A decoded page larger than `cache_bytes` is returned but not retained.** For example an RGB page of 96 MP is
  about 288 MB against the 256 MiB default (the gray `huge-single` page is about 96 MB and fits), so every zoomed
  tile of such a page decodes again. The `screentone` run with `--cache-mb 64` shows how a smaller budget changes
  `decodes` and `cache_hits` for pages that do fit. Expected, unmeasured.
- **Lazy `page_info` probe.** The first render of a page reads the start of the entry and parses the header before
  decoding; `open/many-pages+probe-all` shows the cost of doing that for 500 pages (Webtoon layout needs every page size).
  Expected, unmeasured.
- **Prefetch workers run at lower priority** (nice 10 on Linux and Android) while a visible render runs on the caller
  thread, so a page turn that joins a decode started by a worker waits at the worker's priority. Expected, unmeasured.
- **Dropping the last handle to a book or engine can block** while a decode of it is still running; Phase 2 should keep
  the `Engine` app-wide and close books off the main thread. Expected, unmeasured.

## Known limitations / decisions pending

- **I3: libjpeg warnings are fatal — resolved by owner decision D3 (2026-11-19).** libjpeg-turbo reports a recoverable
  warning (extraneous bytes before a marker, premature end of data) as a `-1` return with `TJERR_WARNING`; the Phase 1
  `turbojpeg-rs` wrapper turned every `-1` into `Corrupt`, so such files showed an error page instead of their decodable
  part. The owner decided (D3) to tolerate warnings, and the decode backend now goes straight to the `tj3` API
  (`turbojpeg::raw`) with `TJPARAM_STOPONWARNING = 0` and `TJPARAM_SCANLIMIT = 256`, treating a `-1` whose
  `tj3GetErrorCode` is `TJERR_WARNING` as success and `TJERR_FATAL` as `Corrupt`. Implemented in Task 2; fuzz evidence
  for the new path comes in Task 11.
- **Fuzz coverage caveats.** The decode harness uses 4 MP / 16 K limits, so the 100 MP default limit and the
  progressive/multi-scan source-pixel cap path are not fuzzed at default scale. The seeds lack progressive, CMYK, 16-bit
  PNG, alpha and lossy WebP files. The libjpeg-turbo C code is not ASAN-instrumented, so memory errors inside it may
  go unseen.
- **`resize/huge-region-2160x4756-to-1080x2378` is not the production zoom-tile cost.** The bench decodes the 96 MP page
  at denom 1 and does a true 2:1 downscale. The engine would decode that zoom tile at denom 2 and then resize 1:1. The
  production cost of that path is unmeasured.
- **Cache key after a denom escalation.** When `max_image_pixels` forces a decode to a larger denom than the one requested,
  the page is cached under the larger denom, so a later request for the smaller denom decodes again. This only affects
  pages above 100 MP.
