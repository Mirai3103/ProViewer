# ProViewer performance budgets

Status: **approved by the owner on 2026-11-18** (Phase 0 exit gate). Rows c and d still need their numbers, set in Phase 1 and approved then. Absolute budgets; there is no competitor baseline
(spec §1, §7). Measured on the reference device only (OnePlus CPH2767, ~11 GB, Android 16, 120 Hz,
display locked to max refresh rate); the emulator is never used for performance numbers.
A budget may be tightened with owner approval, never loosened silently.

## Method

Frame metrics come from **Perfetto FrameTimeline** (`scripts/perf-capture.ps1` → `trace_processor_shell`
→ `framestat`), not `dumpsys gfxinfo framestats` (120-frame ring buffer, HWUI-only, wrong jank
definition). A frame is janky when SurfaceFlinger's `jank_type` is anything other than `None` or
`Buffer Stuffing`. The capture script refuses to record a row if the active refresh rate differs from
the expected one before or after the run, or if the target package produced zero frames.

## Budgets

| # | Budget | Target | Scenario / corpus file | How measured | When enforced | State |
|---|---|---|---|---|---|---|
| a1 | Janky-frame % at locked 120 Hz | ≤ **1 %** | Webtoon scroll — `webtoon-long.cbz` | `perf-capture.ps1 -Action webtoon-scroll` | Phase 3 | approved |
| a2 | Janky-frame % at locked 120 Hz | ≤ **1 %** | Paged swipe — `mixed-small.cbz` | `perf-capture.ps1 -Action paged-swipe` | Phase 2 | approved |
| a3 | Janky-frame % at locked 120 Hz | ≤ **1 %** | Pinch-zoom + pan — `screentone.cbz`, `huge-single.cbz` | `perf-capture.ps1 -Action manual` (owner gestures) | Phase 2 | approved |
| b | Page turn → sharp image | prefetched **< 100 ms**, cold **< 300 ms** | Page turns on `mixed-small.cbz` | Method decided in Phase 2 when the reader exists (frame timing cannot measure it) | Phase 2 | approved |
| c | Peak total process PSS (Rust cache + Bitmaps + GPU) | proposed in Phase 1 from the engine's measured cache + decode footprint | `huge-single.cbz`, `webtoon-long.cbz` | `peak_pss_kb` column of `perf-capture.ps1` (max of ~1 Hz `dumpsys meminfo` samples) | Phase 2 | approved rule; number TBD |
| d | Open time of a 500-page archive | set in Phase 1 from bench numbers | `many-pages.cbz` | Phase 1 criterion bench | Phase 1 | approved rule; number TBD |

Frame-duration percentiles (p50/p90/p95/p99) are recorded for information only, **not** budgeted:
a pipelined renderer legitimately has frames longer than one vsync.

## Noise floor of the method (measured 2026-11-18, reference device, locked 120 Hz)

Settings app, 10 scripted swipes (~4 s, ~540–590 frames per run), three runs
(`scripts/perf-capture.ps1 -Package com.android.settings -Scenario smoke-scroll-runN -Action webtoon-scroll -Repeat 10 -ExpectedHz 120`;
rows in `docs/perf/baselines/2026-11-18-com.android.settings.csv`):

| Run | Frames | Janky | Janky % | p50 ms | p99 ms | Peak PSS kB |
|---|---|---|---|---|---|---|
| 1 | 586 | 0 | 0.00 | 2.71 | 4.40 | 191 766 |
| 2 | 545 | 0 | 0.00 | 2.87 | 4.58 | 150 949 |
| 3 | 536 | 0 | 0.00 | 2.74 | 4.85 | 150 722 |

One further run whose row was rejected by a then-buggy rate check (see below) had 1 janky frame in 537 (0.19 %, a
`Prediction Error`; one `Buffer Stuffing` was correctly not counted). So the noise floor of a well-optimized system app is
**0–0.2 % janky**, comfortably below the proposed **1 %** budget: 1 % is achievable and meaningful on this device.
Caveat: 4 s runs are short — 1 % of ~550 frames is only ~5 frames, so a budget capture should use longer scenarios
(more swipes / minutes of Webtoon scroll) to get a stable percentage. Peak PSS ≈ 150–190 MB is the Settings baseline,
not a ProViewer number.

## Method notes learned on the device

- The display must be awake and unlocked; otherwise FrameTimeline yields 0 frames and the script exits 1 (it did, three times).
- `expected_frame_timeline_slice.dur` is the app's deadline (13.67 ms here), **not** the vsync period, so it cannot be used
  to verify the refresh rate. The script instead uses the median spacing of consecutive frame timestamps from the same
  trace (8.35 ms here ≈ 120 Hz) and refuses the row if it is more than 5 % off `-ExpectedHz`. A first version used the
  expected duration, reported 73 Hz on a correct 120 Hz run and was replaced (test first).
- The capture window is stopped explicitly after the action; actions here took ~4 s of a 23.5 s safety ceiling.
