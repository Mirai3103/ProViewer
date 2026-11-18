# Reference device

Captured 2026-11-18 over adb from the owner's device. Emulator results are never used for performance
numbers (spec §3).

## Owner decision (2026-11-18): reference refresh rate is 120 Hz

The spec and plan assumed a 185 Hz display. The attached device supports 60 / 90 / 120 / 144 / 165 Hz. The owner
confirmed this is the reference device and chose to **lock it to 120 Hz** ("max 120 is good enough for this phone").
Reference rate = 120 Hz, frame budget 1000/120 ≈ 8.33 ms. Spec §3/§5/§7/§9/§11 were updated accordingly; the plan's
`-ExpectedHz 185` examples are superseded.

## Measured facts

| Item | Value |
|---|---|
| Manufacturer / model | OnePlus / CPH2767 (device `OP612DL1`) |
| Build fingerprint | `OnePlus/CPH2767IN/OP612DL1:16/BP2A.250605.015/B.R4T3.3a1658b-172d9ef-176ba9e:user/release-keys` |
| Android release / SDK | 16 / 36 |
| SoC (`ro.soc.model`) | SM8845 (the owner describes it as Snapdragon 8 Elite; marketing name not verified from the device) |
| CPU cores | 8 |
| MemTotal | 11 366 272 kB (≈ 10.8 GiB usable of the nominal 12 GB) |
| Page size (`getconf PAGE_SIZE`) | **4096** — the device runs 4 KB pages, so 16 KB runtime behaviour is NOT exercised here; 16 KB compliance rests on `scripts/check-16kb.ps1` |
| On-device Perfetto | v49.0 (host `trace_processor_shell` pinned at v59.1; traces from v49 parse with it) |
| Display modes | 1272×2800: 120 / 60 / 90 / 144 / 165 Hz (ids 1–5); 1080×2378: 120 / 60 / 90 / 144 / 165 Hz (ids 6–10) |
| Display mode used for all measurements | id 6 = 1080×2378 @ 120 Hz (user peak-refresh cap 120.00001 Hz; adaptive drops are caught by `perf-capture.ps1`) |

## Test settings (state at capture time)

- Refresh rate: capped at 120 Hz by the user setting; `perf-capture.ps1 -ExpectedHz 120` verifies it before, after and from the trace.
- Battery saver (`settings get global low_power`): 0 (off)
- Brightness: manual mode, `screen_brightness` = 1606
- Battery 51 %, USB powered during runs
- Performance mode: not verified (left at default)
- Build type measured: `benchmark` (minified, non-debuggable, profileable), CompilationMode.None, 10 cold starts

## Phase 0 baseline: cold start of the Hello app

Macrobenchmark `StartupBenchmark.startup`, no error suppression, on this device:
`docs/perf/baselines/phase0-startup-2026-11-18.json`

| Metric | min | median | max |
|---|---|---|---|
| `timeToInitialDisplayMs` | 153.8 | 170.0 | 226.7 |
| `frameCount` | 3 | 3 | 3 |

The same test also proves R8 does not strip uniffi/JNA in the minified build (the engine-version text was on screen
in all 10 iterations).

## Emulator notes (correctness only)

- Emulator `Medium_Phone_API_37.0` reports `PAGE_SIZE` = 4096. `sdkmanager --list` offered no x86_64 16 KB-page
  system image, so the optional 16 KB emulator run was skipped.

## Phase 0 noise floor and fixtures

Settings-app noise-floor captures (0 janky frames in 3 runs at 120 Hz) are in `docs/perf/budgets.md` and
`docs/perf/baselines/2026-11-18-com.android.settings.csv`. Real-device fixtures:
`tools/framestat/tests/fixtures/frames-real.csv`, `display-real.txt`.
