# ProViewer performance budgets

Status: rows a1, a2, a3 and b were **approved by the owner on 2026-11-18** (Phase 0 exit gate). Rows c and d were
**dropped by the owner on 2026-11-19** (see below). Absolute budgets; there is no competitor baseline
(spec §1, §7). Measured on a real device by a Benchmark run the owner starts themselves (D1, 2026-11-19: any phone,
without adb); the reference device is the OnePlus CPH2767 (~11 GB, Android 16, 120 Hz, display locked to max refresh
rate); the emulator is never used for performance numbers.
A budget may be tightened with owner approval, never loosened silently.

## Method

Since 2026-11-19 (owner decision D1), a2, a3 and b — and since Phase 3 also a1 — are measured by the **in-app Benchmark screen** (`benchmark` build
type; Phase 2 spec §6.4): the owner starts a run by tapping a button — no adb, no script, on any phone — and the screen
measures itself while the real reader runs the scenarios. Run conditions (device model, Android version, app version and
git SHA, refresh rate before and after each scenario, thermal status, battery, emulator flag) are recorded with every
result, and results are shared as a versioned JSON file.

- **Janky frame (a1, a2, a3):** `Window.OnFrameMetricsAvailableListener` (API 31+); a frame is **janky when
  `FrameMetrics.TOTAL_DURATION > FrameMetrics.DEADLINE`**. First-draw frames (`FrameMetrics.FIRST_DRAW_FRAME`) are
  excluded. Frame-duration percentiles are recorded for information only, not budgeted. On API < 31 the screen reports
  jank as unsupported. Like `gfxinfo`, this sees HWUI frames only — which is everything the Compose reader draws.
- **a3 is scripted:** animated zoom 1×→3×, pans and zoom-out cycles, including the sharp detail re-render after each
  settle — no owner gestures.
- **a1 is scripted too (Phase 3):** the Benchmark screen opens `webtoon-long.cbz` at scale 1 with the layout on Auto (which
  resolves Webtoon), scrolls down at a constant 4000 px/s to the end of the column, then performs 4 upward flings (8000 px/s
  release velocity, 300 ms settle after each). Jank is counted over the whole window with the same
  `FrameMetrics` rule. The number of frames that drew a placeholder in the visible area and the image budget's peak bytes
  are reported for information only, not budgeted. Benchmark JSON `schemaVersion` is 2 since Phase 3.
- **Page-turn latency (b):** t0 = the moment the page turn is issued (the programmatic equivalent of the finger
  lifting); t1 = the frame in which the target page's full-quality fit bitmap is first drawn (a draw callback on the
  page plus that frame's `FrameMetrics` timing). Placeholder frames do not count. "Cold" = the page is not decoded and
  not rendered (fresh book or a far jump); "prefetched" = the prefetch and pre-render window is idle before the turn.
  Since Phase 3 (E5) the prefetched class is measured with **snapped (non-animated) turns** and a **strict sharp-draw
  probe**: the probe fires only while the unit is the current one, so t1 is a genuine sharp-draw frame and the Phase 2
  settle-timestamp fallback is no longer needed. The Phase 2 caveat from the owner's vivo V2364A run (60 Hz, not the
  120 Hz budget condition) stays: its prefetched value (~416 ms) was the scripted animation time, not latency, and is
  **not recorded as passing**; the snapped-turn method has not yet been run on a real device.

The `perf-capture.ps1` method (Perfetto FrameTimeline → `trace_processor_shell` → `framestat`; a frame is janky when
SurfaceFlinger's `jank_type` is anything other than `None` or `Buffer Stuffing`; the script refuses a row when the
active refresh rate differs from the expected one or the target produced zero frames) remains in the repository **for
reference only**. It is the method for no budget row: a1 moved to the Benchmark screen in Phase 3.

## Budgets

| # | Budget | Target | Scenario / corpus file | How measured | When enforced | State |
|---|---|---|---|---|---|---|
| a1 | Janky-frame % at locked 120 Hz | ≤ **1 %** | Webtoon scroll — `webtoon-long.cbz` | Benchmark screen scenario a1 (scripted Webtoon scroll: descent plus 4 flings; Phase 3 plan Task 12; `perf-capture.ps1` is reference only) | Phase 3 | approved |
| a2 | Janky-frame % at locked 120 Hz | ≤ **1 %** | Paged swipe — `mixed-small.cbz` | Benchmark screen scenario a2 (scripted paged swipe; Phase 2 spec §6.4) | Phase 2 | approved |
| a3 | Janky-frame % at locked 120 Hz | ≤ **1 %** | Pinch-zoom + pan — `screentone.cbz`, `huge-single.cbz` | Benchmark screen scenario a3 (scripted zoom/pan; Phase 2 spec §6.4) | Phase 2 | approved |
| b | Page turn → sharp image | prefetched **< 100 ms**, cold **< 300 ms** | Page turns on `mixed-small.cbz` | Benchmark screen scenario b (page-turn latency; t0/t1 in Method above; Phase 2 spec §6.4) | Phase 2 | approved |
| c | Peak total process PSS | **dropped, no number** | n/a | n/a | n/a | dropped by owner decision 2026-11-19 |
| d | Open time of a 500-page archive | **dropped, no number** | n/a | n/a | n/a | dropped by owner decision 2026-11-19 |

**Rows c and d: dropped by owner decision 2026-11-19** ("bỏ hết đi, ko có được đo benmark gì hết trên máy thật"). No engine
budget numbers are proposed for peak process PSS (c) or open time of a 500-page archive (d), and no formulas are needed.
Nothing was measured for them. Rows a1, a2, a3 and b are untouched and remain approved.

**D1/D2 (owner decisions 2026-11-19; replaces the earlier open conflict):** measurement is the **in-app Benchmark screen**
(D1): the real-device run is a **checkpoint the owner starts** whenever they choose, on any phone, without adb; a budget
miss found there is fixed in a later round, not a Phase 2 blocker. Phase 2 exit is **functional verification on the
emulator** (D2); emulator numbers are never performance results. Rows a1, a2, a3 and b keep their targets and stay approved.

**Enforced design cap (Phase 3, E3) — not a measured budget:** the reader's decoded-image bytes (base bitmaps, Webtoon
tiles and detail bitmaps together) are held under `IMAGE_CAP_BYTES` = **192 MiB** (`ImageBudget.kt`). It is a design
limit enforced in code and covered by unit and instrumented tests; it carries no owner-approved performance target and
is not a replacement for the dropped row c (peak process PSS). The a1 run reports the peak bytes the budget held, for
information only.

Frame-duration percentiles (p50/p90/p95/p99) are recorded for information only, **not** budgeted:
a pipelined renderer legitimately has frames longer than one vsync.

## Noise floor of the `perf-capture.ps1` method (measured 2026-11-18, reference device, locked 120 Hz)

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
