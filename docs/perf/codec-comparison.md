# JPEG codec comparison (Phase 1, Task 3)

Status: **PARTIAL. The Rust-candidate comparison is done; the platform-decoder baseline and the release `.so`
size deltas are NOT MEASURED — dropped by owner decision 2026-11-19 (no device benchmarks, see "Open items"). The codec
choice below (`turbojpeg`, the owner's 2026-11-18 choice) is provisional — it will not be verified on device.**

Spec §5 "Decoding note" and §9 ask for a speed and scaled-decode comparison of the JPEG decoders before one is
chosen for the engine (Task 4). Candidates: `zune-jpeg`, `jpeg-decoder` (pure Rust, has DCT scaling), `turbojpeg`
(Rust bindings over libjpeg-turbo, a C library), and the platform decoder (`BitmapFactory` / `ImageDecoder`) as a
reference point.

## What was measured and how

- **Tool:** `tools/codec-bench` (`cargo run --release -p codec-bench`), cross-built for `aarch64-linux-android`
  (API 29) with `cargo ndk` and run on the phone by `scripts/device-run.ps1`:
  `scripts/device-run.ps1 -Crate codec-bench -Bin codec-bench [-Features turbo] -OutFile <csv>`.
- **Inputs** are generated in-process and deterministically (`gen-corpus` halftone page + `jpeg-encoder`, quality
  85), so the host, the device and the platform benchmark decode identical bytes (`codec-bench --export <dir>`):

  | Input | Description |
  |---|---|
  | `gray-1600x2400.jpg` | `halftone_page(1600, 2400, 3.0, 45.0)`, grayscale, baseline |
  | `color-1600x2400.jpg` | deterministic colour page, 4:2:0, baseline |
  | `progressive-1600x2400.jpg` | same colour page, progressive |
  | `gray-12000x8000.jpg` | halftone, grayscale, 96 MP |

- **Cells:** 3 codecs x 4 inputs x denominators 1, 2, 4 (decode at 1/denom size). Per cell: 3 warm-up decodes, then
  15 timed iterations (5 for the 12000x8000 input). Single thread, no `rayon`. Median and nearest-rank p90 of the
  per-iteration wall time, in ms.
- **Correctness cross-check before timing:** each codec's denominator-1 output must match `zune-jpeg`'s within a mean
  absolute difference of 2, otherwise the run exits 1. Both runs below passed it.
- **Unsupported scaled decode** (`zune-jpeg` at 1/2 and 1/4): the CSV row has `supported=false` and the time of the
  full decode (the engine would decode fully and then resize). Those cells are marked `*` below.
- Rust outputs are Gray8 / RGB8; the platform decoder outputs ARGB_8888 (4 B/px). Rust times exclude the Phase 2 copy
  into an Android `Bitmap`.

### Provenance caveat (read this)

The two CSVs were produced via `scripts/device-run.ps1` on 2026-11-18. The CSVs do **not** record which device they
came from. The script's default serial is the first attached non-`emulator-` device (the reference device is
`3B65BT02H6G00000`, OnePlus CPH2767), and it never falls back to the emulator, but the serial that was actually used
is not stored in the files. Treat the numbers as "reference-device class, serial not recorded". The thermal state of
the phone during these runs was not recorded either.

Raw data (committed, unmodified):

- `docs/perf/baselines/phase1-codecs-rust-turbo-2026-11-18.csv`: all three Rust-side codecs, built with `--features turbo`.
  **Used for the table and the decision below** (one run, so the codecs are compared under the same conditions).
- `docs/perf/baselines/phase1-codecs-rust-2026-11-18.csv`: `zune-jpeg` and `jpeg-decoder` only, a second, independent
  run of the same two codecs (no `turbo` feature). Of the 24 cells the two runs share, 8 (`zune-jpeg` at 1/2 and 1/4 on
  the four inputs) are copies of that input's single full-decode time, so only 16 cells are independent. The two runs
  agree within about 2 % (largest difference 1.85 %) on 15 of those 16, and differ on one:
  `jpeg-decoder` on `progressive-1600x2400.jpg` at denominator 1 took 98.7 ms in this run and 137.1 ms in the turbo
  run (a ~39 % gap; the p90 of 135.4 ms in this run suggests a bimodal cell). That is the only cell that changes any
  ranking input, and it does not affect the winner.

## Results (median / p90 ms per cell)

`*` = no scaled decode for this denominator; the value is the full-decode time (`supported=false` in the CSV).
Source: `phase1-codecs-rust-turbo-2026-11-18.csv`.

| Input | Codec | 1/1 | 1/2 | 1/4 |
|---|---|---|---|---|
| gray-1600x2400.jpg | zune-jpeg | 56.9 / 57.0 | 56.9 / 57.0* | 56.9 / 57.0* |
| gray-1600x2400.jpg | jpeg-decoder | 66.6 / 67.0 | 67.4 / 67.6 | 67.7 / 91.8 |
| gray-1600x2400.jpg | turbojpeg | 38.4 / 53.4 | 35.7 / 48.0 | 34.6 / 34.6 |
| color-1600x2400.jpg | zune-jpeg | 60.9 / 61.0 | 60.9 / 61.0* | 60.9 / 61.0* |
| color-1600x2400.jpg | jpeg-decoder | 95.7 / 96.1 | 74.0 / 74.4 | 68.8 / 69.1 |
| color-1600x2400.jpg | turbojpeg | 43.3 / 43.8 | 37.4 / 37.4 | 35.3 / 38.7 |
| progressive-1600x2400.jpg | zune-jpeg | 72.4 / 73.0 | 72.4 / 73.0* | 72.4 / 73.0* |
| progressive-1600x2400.jpg | jpeg-decoder | 137.1 / 143.6 † | 78.1 / 78.6 | 73.3 / 73.6 |
| progressive-1600x2400.jpg | turbojpeg | 56.0 / 56.1 | 49.7 / 49.8 | 47.4 / 47.5 |
| gray-12000x8000.jpg | zune-jpeg | 1384.9 / 1412.6 | 1384.9 / 1412.6* | 1384.9 / 1412.6* |
| gray-12000x8000.jpg | jpeg-decoder | 1564.7 / 1566.3 | 1575.9 / 1578.0 | 1580.7 / 1582.8 |
| gray-12000x8000.jpg | turbojpeg | 947.0 / 948.5 | 881.6 / 883.5 | 858.5 / 860.0 |

† Outlier cell: the independent second run (non-turbo CSV) measured 98.7 / 135.4 ms for this cell (the p90 suggests a
bimodal cell). With 98.7 ms the `jpeg-decoder` progressive scaling gain below shrinks from 137.1 -> 73.3 ms to
98.7 -> 73.3 ms. The table keeps the turbo-run value for consistency; it does not change the winner.

Observations (from the table only):

- `turbojpeg` is fastest in every cell, and its scaled decodes get faster with the denominator (96 MP: 947 -> 859 ms).
- `jpeg-decoder`'s scaling helps on colour and progressive inputs (95.7 -> 68.8 ms, 137.1 -> 73.3 ms, see the † outlier note) but gives
  nothing on grayscale inputs (66.6 -> 67.7 ms; 1565 -> 1581 ms at 96 MP). Its full decode is the slowest of the three.
- `zune-jpeg` is the faster pure-Rust decoder at full size, but a scaled page costs a full decode.
- The 12000x8000 input is about 87 % of every codec's score, so the score is dominated by one input (see the check
  below).

### Scaled-decode support matrix

| Codec | 1/1 | 1/2 | 1/4 | 1/8 |
|---|---|---|---|---|
| zune-jpeg 0.5.15 | yes | no | no | no |
| jpeg-decoder 0.3.2 (`Decoder::scale`) | yes | yes | yes | accepted by the API, not measured |
| turbojpeg 1.5.1 (libjpeg-turbo 3.1.0 bundled by turbojpeg-sys 1.2.0) | yes | yes | yes | accepted by the API (`ScalingFactor`), not measured |

The benchmark only exercised denominators 1, 2 and 4. The `supports()` functions in `codec-bench` also return true
for 1/8 on `jpeg-decoder` and `turbojpeg`; this was not run, so treat 1/8 as "declared by the crate, unverified here".

## Decision rule and score

Rule (plan Task 3): score = sum of the 12 device medians of a codec (an unsupported denominator counts as the full
decode time, as already in the CSV). Lowest score wins among the Rust candidates. If `turbojpeg` wins by less than
15 % over the best pure-Rust codec, the pure-Rust codec is picked (no C build, fuzzed memory safety). If the platform
decoder beats the chosen Rust codec by more than 25 %, stop and ask the owner before Task 4.

| Codec | Score, same run (turbo CSV) | Score, second run (non-turbo CSV) |
|---|---|---|
| zune-jpeg | 4725.0 ms | 4804.6 ms |
| jpeg-decoder | 5450.0 ms | 5417.7 ms |
| turbojpeg | 3065.0 ms | not in this run |

(Recomputed from the CSVs; the 4805 / 5418 / 3065 ms figures quoted earlier mix the two runs: `zune-jpeg` and
`jpeg-decoder` from the non-turbo CSV, `turbojpeg` from the turbo CSV.)

- Best pure-Rust codec: `zune-jpeg`.
- `turbojpeg` is **35.1 % lower** than `zune-jpeg` within the same run (3065.0 vs 4725.0 ms), and 36.2 % lower against
  the second run's 4804.6 ms. Both are >= 15 %, so the rule selects **`turbojpeg`**.
- Robustness check (not part of the rule): without the 96 MP input the three scores are 570.3 (`zune-jpeg`), 728.7
  (`jpeg-decoder`) and 377.8 ms (`turbojpeg`), so `turbojpeg` is still ~34 % lower. The ranking does not hinge on the huge input.

### Chosen backend (provisional)

```
status:        provisional, will not be verified on device (platform baseline NOT MEASURED, dropped by owner decision 2026-11-19; rule's last check cannot be evaluated)
crate:         turbojpeg
version:       1.5.1   (Cargo.lock; workspace pin "=1.5.1"; turbojpeg-sys 1.2.0, bundles libjpeg-turbo 3.1.0)
denominators:  1, 2, 4 measured and supported (8 declared by the API, unmeasured)
build:         C library built from source by cargo via CMake (needs the SDK CMake package + Ninja on the host)
fallback:      zune-jpeg 0.5.15 (denominator 1 only; scaled pages are decoded fully, then resized)
```

Task 4 may read the block above. Because the decision is provisional, Task 4 should keep the decoder behind a small
trait so the backend can still change if the owner revisits the choice.

## Open items

These were **not done and will not be done**: NOT MEASURED — dropped by owner decision 2026-11-19. Do not treat the
exit gate item "codec chosen from a measured comparison" as fully met.

1. **Platform decoder baseline: NOT MEASURED — dropped by owner decision 2026-11-19.** `phase1-codecs-platform-<date>.json` does not exist.
   The `:codecbench` module (`PlatformJpegDecodeBenchmark`: `BitmapFactory` and `ImageDecoder`, one test per input x
   method x sample size 1/2/4) is written (an earlier implementer built it; this session did not re-run the build) but was never completed on the device and will not be run. The
   owner's reference phone was thermally throttling during the Microbenchmark run (longer than an hour) and the owner
   stopped it and closed Task 3 partially. Consequence: the rule's final check ("platform beats the chosen Rust codec
   by more than 25 % -> stop and ask the owner; decoding would move to Kotlin") **cannot be evaluated**. If the owner ever reinstates device benchmarking and the baseline then shows the platform
   decoder more than 25 % faster, the provisional choice and Task 4 must be revisited with the owner.
   Per the source comment in the benchmark (written by an earlier implementer, not re-verified): a full-size ARGB_8888 decode of the 96 MP
   input is a 384 MB bitmap and the first attempt got the benchmark process killed by lmkd, so those two cells
   (`gray-12000x8000` at sample size 1, both methods) are skipped unless `hugeFull=true` is passed.
2. **Release `.so` size delta per candidate: NOT MEASURED — dropped by owner decision 2026-11-19.** No release `.so` was built for any candidate, so the
   per-candidate delta is unknown. Known absolute sizes, from the Task 8 release build with `turbojpeg` linked (a host
   build measurement, not a performance number): `libproviewer_core.so` is 2,030,016 bytes for arm64-v8a and
   2,046,672 bytes for x86_64. Without a build per candidate, the share libjpeg-turbo contributes cannot be separated out.
3. **Trade-off of the provisional pick.** `turbojpeg` is a C library (libjpeg-turbo built via CMake during `cargo build`,
   needs the SDK CMake package on the host), while `zune-jpeg` is pure Rust with fuzzing-backed memory safety. The
   plan's rule resolves in favour of Rust only when `turbojpeg` wins by less than 15 %; it wins by about 35 %, so the
   rule picks `turbojpeg`. The owner may still weigh the memory-safety and build-complexity cost against the speed.
   A C decoder is only as safe as libjpeg-turbo itself.
4. **Provenance.** The CSVs carry no device serial, thermal state or timestamp (see the caveat above). Since the owner
   saw throttling on this phone, the Rust numbers may also have been taken under uneven thermal conditions; the two
   runs agreeing within ~2 % on 15 of the 16 independent comparable cells (the other 8 of the 24 are copies of a
   `zune-jpeg` full-decode time, and the one differing cell is the † outlier) is mild evidence against a large effect,
   not proof.

## Implementation notes (deviations from the brief)

- `device-run.ps1` finds the built executable from cargo's fixed output layout instead of `--message-format=json`,
  because cargo-ndk 4.x forces its own message format and consumes the artifact messages itself (see the comment in
  the script). It passes `-s <serial>` on every `adb` call except `adb devices`, defaults to the first non-`emulator-`
  device, exits 1 with "reference device not attached" otherwise, and prints
  `WARNING: emulator numbers are not valid for performance` for an explicit `-Serial emulator-*`. Its pure logic has
  `-SelfTest` checks.
- For `-Features turbo` it puts the newest SDK CMake (`$ANDROID_HOME/cmake/<version>/bin`) on `PATH` and selects the
  Ninja generator and the NDK toolchain file for `aarch64-linux-android`.
- The `turbo` Cargo feature is off by default, so a plain `cargo build --workspace` builds no C code.

## How to finish later (instructions only, nothing here was run; NOT to be run unless the owner reinstates device benchmarking)

Dropped by owner decision 2026-11-19: the owner's phone must not be used for benchmarks. If that ever changes, use a **cool** device (let it rest, screen off, not charging) and close other apps.
The owner may want to drop or shrink the `gray-12000x8000.jpg` cells (remove it from `INPUTS` in
`tools/codec-bench/src/main.rs` and from the Kotlin test list) to keep the run short; if so, say so in the table.

Step 4, platform baseline, on the reference device:

```powershell
$env:ANDROID_SERIAL = '3B65BT02H6G00000'
.\gradlew.bat :codecbench:connectedReleaseAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=UNLOCKED
# optional, only if memory allows (384 MB bitmap): add
#   -Pandroid.testInstrumentationRunnerArguments.hugeFull=true
```

Only `UNLOCKED` may be suppressed (no root, clocks unlocked). Copy the benchmark results JSON from
`codecbench/build/outputs/connected_android_test_additional_output/` to
`docs/perf/baselines/phase1-codecs-platform-<yyyy-mm-dd>.json`, then add a platform column to the table above, compute
the platform score with the same rule (12 cells; the 96 MP full-size cells need `hugeFull=true`), and evaluate the
25 % check against the chosen Rust codec. Also record the device serial and thermal state next to the numbers.

Release `.so` size deltas: build the release library once per candidate (with the candidate wired into `core`) and record
the size difference of the stripped `.so`; then re-run `scripts/check-16kb.ps1`.

To re-run the Rust side with the serial recorded in the log:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-run.ps1 -Crate codec-bench -Bin codec-bench `
  -Features turbo -Serial 3B65BT02H6G00000 -OutFile docs/perf/baselines/phase1-codecs-rust-turbo-<yyyy-mm-dd>.csv
```
