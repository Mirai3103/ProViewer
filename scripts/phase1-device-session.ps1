# DO NOT RUN unless the owner reinstates device benchmarking: on 2026-11-19 the owner dropped all real-device
# benchmarks for Phase 1 and budgets c and d (docs/perf/budgets.md). The owner's phone must not be used.
# Kept for reference: one-command driver for the former single reference-device session of Phase 1 (Task 10
# Steps 2-3, plus the Phase 0 app memory baseline and an optional codec platform baseline). It never runs by
# itself; nothing in this repository calls it. The device numbers it would record would be the source for
# docs/perf/phase1-engine.md.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/phase1-device-session.ps1 -Serial <adb serial>
#       [-Only bench | pvcli | meminfo | platform]   (comma-separated list allowed; default: bench,pvcli,meminfo)
#       [-IncludePlatform]                           (adds the optional, long, flagged platform baseline)
#       [-Date yyyy-MM-dd] [-CoolDownSeconds 120] [-BetweenRunsSeconds 60] [-MaxTempC 38] [-AllowOtherModel]
#       [-BenchArgs '--bench']                       (program arguments of the bench run; e.g. '--bench --quick')
#       [-SkipMeminfo]                               (do not require / capture the Phase 0 app memory baseline)
#       [-DryRun]                                    (print what would run; touches no device)
#       [-Rehearsal -OutDir <dir>]                   (wiring check on an emulator ONLY; see below)
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/phase1-device-session.ps1 -SelfTest
#
# -Serial is required and must NOT be an emulator serial (exit 1): performance numbers come only from the
# reference device (OnePlus CPH2767, docs/perf/reference-device.md). Every adb call uses `-s <serial>`.
#
# BEFORE THE SESSION (this script changes no device setting): set the screen timeout to a long value, or turn on
# "Stay awake" (while charging) in Developer options. The screen must stay on and unlocked for the whole ~25
# minutes; the check below runs before every run and aborts if the screen went off.
#
# -Rehearsal: runs the same flow on an `emulator-*` serial to prove the wiring (child device-run.ps1 calls, output
# validation). It is emulator-only (a real serial with -Rehearsal is refused), requires -OutDir (it must resolve
# OUTSIDE <repo>/docs/perf: nothing is ever written to committed baselines), skips the cool-down waits and the
# model/temperature/screen guards, and prints "REHEARSAL - numbers invalid". Without -Rehearsal an emulator
# serial is refused exactly as before.
#
# Conditions (print and check, abort when broken): screen on and unlocked by you, USB powered, battery saver
# off, device cool. Before the first run the script waits -CoolDownSeconds idle; before and between runs it
# waits until the battery temperature is at most -MaxTempC (at most 10 minutes, then aborts).
#
# Steps, in this order:
#   bench    3 x `device-run.ps1 -Crate proviewer_core -Bench engine ... -- --bench`
#            -> docs/perf/baselines/phase1-engine-bench-<date>-run{1,2,3}.txt  (every bench id must appear)
#   pvcli    4 x `pvcli read --fit 1080x2378` (mixed-small, huge-single, webtoon-long, screentone --cache-mb 64)
#            -> docs/perf/baselines/phase1-pvcli-<name>-<date>.txt. The mixed-small run is also Task 9 Step 5.
#   meminfo  `dumpsys meminfo com.proviewer.app` TOTAL PSS of the Phase 0 app (read only; installs nothing)
#            -> docs/perf/baselines/phase0-app-meminfo-<date>.txt. The app's presence is checked FIRST (before the
#            long runs); if it is missing the script prints how to install it and exits 1 (unless -SkipMeminfo), and
#            it exits 1 at the end if the capture is missing.
#   platform OPTIONAL, FLAGGED: Task 3 platform JPEG baseline (`:codecbench:connectedReleaseAndroidTest`).
#            Installs the benchmark APK on the device and ran longer than an hour on this phone because it
#            throttles: only with -Only platform / -IncludePlatform, and an owner call.
# Arguments are parsed by hand in the child scripts; `--` passes program arguments through device-run.ps1.
param(
    [string]$Serial,
    [string[]]$Only,
    [string]$Date = (Get-Date -Format 'yyyy-MM-dd'),
    [int]$CoolDownSeconds = 120,
    [int]$BetweenRunsSeconds = 60,
    [double]$MaxTempC = 38.0,
    [switch]$IncludePlatform,
    [switch]$AllowOtherModel,
    [switch]$DryRun,
    [switch]$Rehearsal,
    [string]$OutDir,
    [string]$BenchArgs = '--bench',
    [switch]$SkipMeminfo,
    [switch]$SelfTest
)
$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot
$ReferenceModel = 'CPH2767'
$AppPackage = 'com.proviewer.app'
$Viewport = '1080x2378'
$BenchPush = 'corpus/generated/many-pages.cbz,corpus/generated/screentone.cbz,corpus/generated/mixed-small.cbz,corpus/generated/huge-single.cbz,corpus/generated/formats-and-names.cbz'
$BenchRuns = 3
$BenchIds = @(
    'open/many-pages', 'open/many-pages+probe-all',
    'decode/screentone-p0/d1', 'decode/mixed-small-p0/d1', 'decode/huge-single-p0/d1', 'decode/huge-single-p0/d8',
    'decode/formats-webp/d1', 'decode/formats-png/d1',
    'resize/gray-1600x2400-to-1080x1620', 'resize/rgb-1600x2400-to-1080x1620',
    'resize/huge-region-2160x4756-to-1080x2378',
    'render/cold-fit', 'render/warm-fit')

# ------------------------------------------------------------------------------------------------
# Pure helpers (covered by -SelfTest; no adb)
# ------------------------------------------------------------------------------------------------

# Returns an error message, or $null when the serial may be used for performance numbers.
function Test-DeviceSerial([string]$s) {
    if ([string]::IsNullOrWhiteSpace($s)) {
        return '-Serial is required: pass the reference device serial from `adb devices` (never an emulator).'
    }
    if ($s.Trim() -like 'emulator-*') {
        return "refusing emulator serial '$s': performance numbers come only from the reference device (spec section 3)."
    }
    return $null
}

# True if $Path resolves inside (or is) directory $Dir: case-insensitive, `..` resolved, a sibling with the same
# name prefix is not inside.
function Test-PathInside([string]$Path, [string]$Dir) {
    $p = [IO.Path]::GetFullPath($Path).TrimEnd([char]92, [char]47)
    $d = [IO.Path]::GetFullPath($Dir).TrimEnd([char]92, [char]47)
    return $p.Equals($d, [StringComparison]::OrdinalIgnoreCase) -or
        $p.StartsWith($d + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}

# Serial / rehearsal / output-directory rules. Returns an error message, or $null when the combination is allowed.
# Without -Rehearsal this is exactly Test-DeviceSerial (an emulator is refused).
function Test-SessionConfig([string]$Serial, [bool]$Rehearsal, [string]$OutDir, [string]$RepoRoot, [string[]]$Steps = @()) {
    if (-not $Rehearsal) {
        $e = Test-DeviceSerial $Serial
        if ($e) { return $e }
        if ($OutDir) { return '-OutDir is only valid together with -Rehearsal (real sessions write to docs/perf/baselines).' }
        return $null
    }
    if ([string]::IsNullOrWhiteSpace($Serial)) { return '-Serial is required: pass the emulator serial from `adb devices`.' }
    if ($Serial.Trim() -notlike 'emulator-*') {
        return "-Rehearsal is emulator-only: refusing serial '$Serial'. A real device session must run without -Rehearsal."
    }
    if ([string]::IsNullOrWhiteSpace($OutDir)) { return '-Rehearsal requires -OutDir <dir> (outside docs/perf).' }
    if (Test-PathInside $OutDir (Join-Path $RepoRoot 'docs\perf')) {
        return "refusing -OutDir '$OutDir': a rehearsal must never write under docs/perf (committed baselines)."
    }
    if ($Steps -contains 'platform') { return 'the platform step installs an APK and is not part of a rehearsal.' }
    return $null
}

# Program arguments for the bench run, split on spaces and commas (PowerShell -File gives one string).
function Resolve-BenchArgs([string]$Text) {
    $a = @($Text -split '[,\s]+' | Where-Object { $_ })
    if ($a.Count -eq 0) { throw '-BenchArgs must not be empty (default: --bench)' }
    return $a
}

# 1 when the meminfo step was requested for a real run but nothing was captured.
function Get-SessionExitCode([string[]]$Steps, [bool]$MeminfoCaptured, [bool]$DryRun) {
    if (-not $DryRun -and ($Steps -contains 'meminfo') -and -not $MeminfoCaptured) { return 1 }
    return 0
}

# Ordered step names for -Only / -IncludePlatform. Throws on an unknown name.
function Resolve-Steps([string[]]$Only, [bool]$IncludePlatform) {
    $order = 'bench', 'pvcli', 'meminfo', 'platform'
    $names = @(@($Only) | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim().ToLowerInvariant() } | Where-Object { $_ })
    if ($names.Count -eq 0) { $names = @('bench', 'pvcli', 'meminfo') }
    foreach ($n in $names) { if ($order -notcontains $n) { throw "unknown step '$n' (use: $($order -join ', '))" } }
    if ($IncludePlatform -and $names -notcontains 'platform') { $names += 'platform' }
    return @($order | Where-Object { $names -contains $_ })
}

# $OutBase is the committed baselines directory unless a rehearsal redirects it.
function Join-OutPath([string]$OutBase, [string]$Leaf) {
    if ($OutBase -eq 'docs/perf/baselines') { return "$OutBase/$Leaf" }
    return Join-Path $OutBase $Leaf
}

function Get-BenchPlan([string]$Date, [int]$Runs, [string]$OutBase = 'docs/perf/baselines') {
    return @(1..$Runs | ForEach-Object { @{ Run = $_; OutFile = (Join-OutPath $OutBase "phase1-engine-bench-$Date-run$_.txt") } })
}

# One entry per pvcli read run. Paths in Args are relative to the device working dir (device-run pushes to corpus/).
function Get-PvcliPlan([string]$Date, [string]$Viewport, [string]$OutBase = 'docs/perf/baselines') {
    $runs = @(
        @{ File = 'mixed-small'; Name = 'mixed-small'; Extra = @() },
        @{ File = 'huge-single'; Name = 'huge-single'; Extra = @() },
        @{ File = 'webtoon-long'; Name = 'webtoon-long'; Extra = @() },
        @{ File = 'screentone'; Name = 'screentone-cache64'; Extra = @('--cache-mb', '64') })
    return @($runs | ForEach-Object {
        @{
            Push    = "corpus/generated/$($_.File).cbz"
            OutFile = (Join-OutPath $OutBase "phase1-pvcli-$($_.Name)-$Date.txt")
            Args    = @('read', "corpus/$($_.File).cbz", '--fit', $Viewport) + $_.Extra
        }
    })
}

# Argument list for a child `powershell -File scripts/device-run.ps1` call.
function Get-DeviceRunArgs([string]$Serial, [hashtable]$Spec) {
    $a = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'device-run.ps1'), '-Crate', $Spec.Crate)
    if ($Spec.Bench) { $a += @('-Bench', $Spec.Bench) } else { $a += @('-Bin', $Spec.Bin) }
    $a += @('-Serial', $Serial, '-Push', $Spec.Push, '-OutFile', $Spec.OutFile, '--') + @($Spec.Args)
    return $a
}

function Get-MissingBenchIds([string]$Text, [string[]]$Ids) {
    return @($Ids | Where-Object { $Text -notmatch ('(?m)^\s*' + [regex]::Escape($_) + '(\s|$)') })
}

function Test-PvcliOutput([string]$Text) {
    return ($Text -match '(?m)^page,ms,decoded\s*$') -and ($Text -match '(?m)^cold_first_ms,p50_ms,p95_ms,max_ms,decodes,cache_hits,peak_rss_kb\s*$')
}

# `dumpsys battery` -> degrees Celsius (the field is in tenths), or $null.
function ConvertFrom-BatteryTemp([string]$Text) {
    if ($Text -match '(?m)^\s*temperature:\s*(-?\d+)') { return [double]$Matches[1] / 10.0 }
    return $null
}

function ConvertFrom-BatteryLevel([string]$Text) {
    if ($Text -match '(?m)^\s*level:\s*(\d+)') { return [int]$Matches[1] }
    return $null
}

# True when any "<type> powered: true" line is present (AC, USB, wireless).
function Test-UsbPowered([string]$Text) {
    return [bool]($Text -match '(?m)^\s*(AC|USB|Wireless|Dock) powered:\s*true')
}

function Test-ScreenAwake([string]$PowerText) {
    return [bool]($PowerText -match 'mWakefulness=Awake')
}

# 'wait' while hotter than the ceiling, 'go' when cool enough, 'unknown' when the temperature is unreadable.
function Get-CoolDecision($TempC, [double]$MaxC) {
    if ($null -eq $TempC) { return 'unknown' }
    if ($TempC -gt $MaxC) { return 'wait' }
    return 'go'
}

function Get-TotalPss([string]$Text) {
    if ($Text -match 'TOTAL PSS:\s+(\d+)') { return [int]$Matches[1] }
    if ($Text -match '(?m)^\s*TOTAL\s+(\d+)') { return [int]$Matches[1] }
    return $null
}

function Test-ModelAllowed([string]$Model, [string]$Reference, [bool]$AllowOther) {
    return $AllowOther -or ($Model.Trim() -eq $Reference)
}

function Invoke-SelfTest {
    $script:fails = 0
    function Check([bool]$cond, [string]$what) {
        if ($cond) { Write-Host "ok   $what" } else { Write-Host "FAIL $what"; $script:fails++ }
    }
    function Throws([scriptblock]$b) { try { & $b | Out-Null; return $false } catch { return $true } }

    Check ($null -ne (Test-DeviceSerial 'emulator-5554')) 'serial: emulator-5554 refused'
    Check ($null -ne (Test-DeviceSerial 'EMULATOR-5556')) 'serial: emulator check is case-insensitive'
    Check ((Test-DeviceSerial 'emulator-5554') -like '*emulator*') 'serial: message names the problem'
    Check ($null -ne (Test-DeviceSerial '')) 'serial: empty refused'
    Check ($null -ne (Test-DeviceSerial $null)) 'serial: missing refused'
    Check ($null -eq (Test-DeviceSerial '3B65BT02H6G00000')) 'serial: reference device serial accepted'

    Check (((Resolve-Steps $null $false) -join ',') -eq 'bench,pvcli,meminfo') 'steps: default is bench,pvcli,meminfo (no platform)'
    Check ((Resolve-Steps @('meminfo') $false) -join ',' -eq 'meminfo') 'steps: -Only meminfo'
    Check ((Resolve-Steps @('pvcli,bench') $false) -join ',' -eq 'bench,pvcli') 'steps: comma list, fixed order'
    Check (((Resolve-Steps $null $true) -join ',') -eq 'bench,pvcli,meminfo,platform') 'steps: -IncludePlatform appends platform'
    Check ((Resolve-Steps @('platform') $false) -join ',' -eq 'platform') 'steps: -Only platform'
    Check (Throws { Resolve-Steps @('everything') $false }) 'steps: unknown step rejected'

    $bp = Get-BenchPlan '2026-12-01' 3
    Check ($bp.Count -eq 3) 'bench plan: 3 runs'
    Check ($bp[2].OutFile -eq 'docs/perf/baselines/phase1-engine-bench-2026-12-01-run3.txt') 'bench plan: out file name'
    $pp = Get-PvcliPlan '2026-12-01' '1080x2378'
    Check ($pp.Count -eq 4) 'pvcli plan: 4 runs'
    Check ((($pp | ForEach-Object { $_.Push }) -join ',') -eq 'corpus/generated/mixed-small.cbz,corpus/generated/huge-single.cbz,corpus/generated/webtoon-long.cbz,corpus/generated/screentone.cbz') 'pvcli plan: files in order'
    Check (($pp[3].Args -join ' ') -eq 'read corpus/screentone.cbz --fit 1080x2378 --cache-mb 64') 'pvcli plan: screentone uses --cache-mb 64'
    Check (@($pp[0..2] | Where-Object { $_.Args -contains '--cache-mb' }).Count -eq 0) 'pvcli plan: others use the default cache'
    Check ($pp[0].OutFile -eq 'docs/perf/baselines/phase1-pvcli-mixed-small-2026-12-01.txt') 'pvcli plan: out file name'

    $spec = @{ Crate = 'proviewer_core'; Bench = 'engine'; Push = 'a,b'; OutFile = 'o.txt'; Args = @('--bench') }
    $a = Get-DeviceRunArgs '3B65BT02H6G00000' $spec
    Check (($a -join ' ') -match '-Bench engine -Serial 3B65BT02H6G00000 -Push a,b -OutFile o.txt -- --bench$') 'device-run args: bench, serial, push, out, program args after --'
    $a2 = Get-DeviceRunArgs 'S' @{ Crate = 'pvcli'; Bin = 'pvcli'; Push = 'p'; OutFile = 'o'; Args = @('read', 'x') }
    Check (($a2 -join ' ') -match '-Bin pvcli -Serial S ' -and $a2 -notcontains '-Bench') 'device-run args: bin mode'

    $good = ($BenchIds | ForEach-Object { "$_  time:   [1 ms 2 ms 3 ms]" }) -join "`n"
    Check ((Get-MissingBenchIds $good $BenchIds).Count -eq 0) 'bench output: all ids present'
    $wrapped = ($BenchIds | ForEach-Object { "$_`n                        time:   [1 ms 2 ms 3 ms]" }) -join "`n"
    Check ((Get-MissingBenchIds $wrapped $BenchIds).Count -eq 0) 'bench output: ids on their own line (long names wrap)'
    $partial = $good -replace 'render/warm-fit  time:[^\n]*', ''
    Check ((Get-MissingBenchIds $partial $BenchIds) -contains 'render/warm-fit') 'bench output: a missing id is reported'
    Check ((Get-MissingBenchIds 'open/many-pages+probe-all time: x' @('open/many-pages')).Count -eq 1) 'bench output: id prefix is not a match'

    $pv = "page,ms,decoded`n0,15.3,true`ncold_first_ms,p50_ms,p95_ms,max_ms,decodes,cache_hits,peak_rss_kb`n15.3,5.2,15.3,15.3,8,4,27848"
    Check (Test-PvcliOutput $pv) 'pvcli output: rows and summary recognised'
    Check (-not (Test-PvcliOutput "page,ms,decoded`n0,1,true")) 'pvcli output: missing summary rejected'

    Check ((ConvertFrom-BatteryTemp "Current Battery Service state:`n  temperature: 285`n  level: 51") -eq 28.5) 'battery: temperature in tenths'
    Check ($null -eq (ConvertFrom-BatteryTemp 'nothing here')) 'battery: unreadable temperature -> null'
    Check ((ConvertFrom-BatteryLevel "  level: 51") -eq 51) 'battery: level'
    Check (Test-UsbPowered "  AC powered: false`n  USB powered: true") 'battery: USB powered detected'
    Check (-not (Test-UsbPowered "  AC powered: false`n  USB powered: false")) 'battery: unpowered detected'
    Check (Test-ScreenAwake 'mWakefulness=Awake') 'screen: awake'
    Check (-not (Test-ScreenAwake 'mWakefulness=Asleep')) 'screen: asleep detected'
    Check (-not (Test-ScreenAwake 'mWakefulness=Dozing')) 'screen: dozing is not awake'
    Check ((Get-CoolDecision 41.0 38.0) -eq 'wait') 'cool: too hot waits'
    Check ((Get-CoolDecision 38.0 38.0) -eq 'go') 'cool: at the ceiling is ok'
    Check ((Get-CoolDecision $null 38.0) -eq 'unknown') 'cool: unreadable temperature is unknown'
    Check ((Get-TotalPss "   TOTAL PSS:   123456   TOTAL RSS: 234567") -eq 123456) 'meminfo: TOTAL PSS (new format)'
    Check ((Get-TotalPss "          TOTAL    98765    1 2 3") -eq 98765) 'meminfo: TOTAL row (old format)'
    Check ($null -eq (Get-TotalPss 'No process found for: com.proviewer.app')) 'meminfo: app not running -> null'
    Check (Test-ModelAllowed 'CPH2767' 'CPH2767' $false) 'model: reference model allowed'
    Check (-not (Test-ModelAllowed 'sdk_gphone64_x86_64' 'CPH2767' $false)) 'model: other model refused'
    Check (Test-ModelAllowed 'Pixel' 'CPH2767' $true) 'model: -AllowOtherModel overrides'

    # Rehearsal (emulator wiring check; numbers invalid) versus the real session.
    $perf = Join-Path $repoRoot 'docs\perf'
    $scratch = Join-Path ([IO.Path]::GetTempPath()) 'pv-rehearsal'
    Check ($null -ne (Test-SessionConfig 'emulator-5554' $false $null $repoRoot)) 'session: emulator without -Rehearsal refused'
    Check ((Test-SessionConfig 'emulator-5554' $false $scratch $repoRoot) -like '*emulator*') 'session: emulator without -Rehearsal refused even with -OutDir'
    Check ($null -ne (Test-SessionConfig 'emulator-5554' $true $null $repoRoot)) 'session: -Rehearsal without -OutDir refused'
    Check ($null -ne (Test-SessionConfig 'emulator-5554' $true (Join-Path $perf 'baselines') $repoRoot)) 'session: -Rehearsal with -OutDir inside docs/perf refused'
    Check ($null -ne (Test-SessionConfig 'emulator-5554' $true $perf $repoRoot)) 'session: -Rehearsal with -OutDir = docs/perf refused'
    Check ($null -ne (Test-SessionConfig 'emulator-5554' $true (Join-Path $perf '..\perf\x') $repoRoot)) 'session: -OutDir with .. into docs/perf refused'
    Check ($null -ne (Test-SessionConfig '3B65BT02H6G00000' $true $scratch $repoRoot)) 'session: real serial with -Rehearsal refused (emulator only)'
    Check ($null -ne (Test-SessionConfig '' $true $scratch $repoRoot)) 'session: -Rehearsal without a serial refused'
    Check ($null -eq (Test-SessionConfig 'emulator-5554' $true $scratch $repoRoot)) 'session: emulator + -Rehearsal + scratch -OutDir accepted'
    Check ($null -eq (Test-SessionConfig '3B65BT02H6G00000' $false $null $repoRoot)) 'session: real serial without -Rehearsal accepted'
    Check ($null -ne (Test-SessionConfig '3B65BT02H6G00000' $false $scratch $repoRoot)) 'session: -OutDir without -Rehearsal refused'
    Check ($null -ne (Test-SessionConfig 'emulator-5554' $true $scratch $repoRoot @('bench', 'platform'))) 'session: rehearsal never runs the platform step'
    Check (Test-PathInside $perf $perf) 'path: a directory counts as inside itself'
    $rb = Get-BenchPlan '2026-12-01' 2 $scratch
    Check ($rb[0].OutFile -eq (Join-Path $scratch 'phase1-engine-bench-2026-12-01-run1.txt')) 'bench plan: output goes to the given directory'
    $rp = Get-PvcliPlan '2026-12-01' '1080x2378' $scratch
    Check ($rp[3].OutFile -eq (Join-Path $scratch 'phase1-pvcli-screentone-cache64-2026-12-01.txt')) 'pvcli plan: output goes to the given directory'
    Check ((@(Resolve-BenchArgs '--bench') -join '|') -eq '--bench') 'bench args: default'
    Check ((@(Resolve-BenchArgs '--bench --quick') -join '|') -eq '--bench|--quick') 'bench args: space separated'
    Check ((@(Resolve-BenchArgs '--bench,--quick') -join '|') -eq '--bench|--quick') 'bench args: comma separated'
    Check (Throws { Resolve-BenchArgs '' }) 'bench args: empty rejected'
    Check ((Get-SessionExitCode @('bench', 'meminfo') $false $false) -eq 1) 'exit: meminfo step without a capture fails the session'
    Check ((Get-SessionExitCode @('bench', 'meminfo') $true $false) -eq 0) 'exit: meminfo captured is ok'
    Check ((Get-SessionExitCode @('bench') $false $false) -eq 0) 'exit: no meminfo step, no meminfo requirement'
    Check ((Get-SessionExitCode @('bench', 'meminfo') $false $true) -eq 0) 'exit: dry run never fails on the missing capture'

    if ($script:fails) { Write-Host "$($script:fails) self-test check(s) failed"; return 1 }
    Write-Host 'self-test passed'
    return 0
}

if ($SelfTest) { exit (Invoke-SelfTest) }

# ------------------------------------------------------------------------------------------------
# Main
# ------------------------------------------------------------------------------------------------

try { $steps = Resolve-Steps $Only ([bool]$IncludePlatform) } catch { Write-Host "phase1-device-session: $_"; exit 1 }
if ($SkipMeminfo) { $steps = @($steps | Where-Object { $_ -ne 'meminfo' }) }
$callerDir = (Get-Location).Path
$outDirFull = $null
if ($OutDir) { $outDirFull = if ([IO.Path]::IsPathRooted($OutDir)) { [IO.Path]::GetFullPath($OutDir) } else { [IO.Path]::GetFullPath((Join-Path $callerDir $OutDir)) } }
$configError = Test-SessionConfig $Serial ([bool]$Rehearsal) $outDirFull $repoRoot $steps
if ($configError) { Write-Host "phase1-device-session: $configError"; exit 1 }
try { $benchProgramArgs = Resolve-BenchArgs $BenchArgs } catch { Write-Host "phase1-device-session: $_"; exit 1 }
Set-Location $repoRoot
# Where the step outputs go: the committed baselines, or (rehearsal only) the scratch directory.
$outBase = if ($Rehearsal) { $outDirFull } else { 'docs/perf/baselines' }
New-Item -ItemType Directory -Force $outBase | Out-Null
$script:MeminfoCaptured = $false

if ($Rehearsal) {
    Write-Host ('REHEARSAL ' + [char]0x2014 + ' numbers invalid. Emulator wiring check only; output goes to ' + $outBase + ', never to docs/perf.')
} else {
    Write-Host 'BEFORE YOU START: set the device screen timeout to a long value (or enable "Stay awake" while charging in'
    Write-Host '  Developer options). This script changes no device setting; the screen must stay on and unlocked for the'
    Write-Host '  whole session and the per-run check aborts if it went off.'
}
Write-Host "phase1-device-session: serial $Serial, date $Date, steps: $($steps -join ', ')$(if ($DryRun) { ' (DRY RUN: nothing is executed)' })"
if (-not $DryRun) {
    $tools = & (Join-Path $PSScriptRoot 'check-env.ps1') -Quiet -PassThru
    if ($tools.Failed -or -not $tools.Adb) { Write-Host 'toolchain preflight failed; run scripts/check-env.ps1'; exit 1 }
    $adb = $tools.Adb
    $attached = @(& $adb devices) | ForEach-Object { if ($_ -match '^(\S+)\s+device(\s|$)') { $Matches[1] } }
    if ($attached -notcontains $Serial) { Write-Host "device '$Serial' is not attached (adb devices)"; exit 1 }
}

# Every adb call goes through here and carries -s <serial>.
function Invoke-Adb([string[]]$AdbArgs) { return @(& $adb -s $Serial @AdbArgs) -join "`n" }

# Prints serial / model / battery temperature and aborts (exit 1) when the device is not in a measurable state.
function Assert-DeviceReady([string]$Why) {
    if ($DryRun) { Write-Host "[dry run] device check before: $Why"; return }
    $model = (Invoke-Adb @('shell', 'getprop', 'ro.product.model')).Trim()
    $battery = Invoke-Adb @('shell', 'dumpsys', 'battery')
    $temp = ConvertFrom-BatteryTemp $battery
    $level = ConvertFrom-BatteryLevel $battery
    $saver = (Invoke-Adb @('shell', 'settings', 'get', 'global', 'low_power')).Trim()
    $awake = Test-ScreenAwake (Invoke-Adb @('shell', 'dumpsys', 'power'))
    Write-Host ("--- {0}: serial {1}, model {2}, battery {3} %, temperature {4} C, USB powered {5}, battery saver {6}, screen {7}" -f
        $Why, $Serial, $model, $level, $temp, (Test-UsbPowered $battery), $saver, $(if ($awake) { 'on' } else { 'OFF' }))
    if ($Rehearsal) { Write-Host '    (rehearsal: model, temperature, screen and battery guards skipped)'; return }
    if (-not (Test-ModelAllowed $model $ReferenceModel ([bool]$AllowOtherModel))) {
        Write-Host "ABORT: model '$model' is not the reference device ($ReferenceModel). Numbers from another device must not be recorded."; exit 1
    }
    if (-not $awake) { Write-Host 'ABORT: the screen is off. Wake and unlock the device, then run again.'; exit 1 }
    if ($saver -eq '1') { Write-Host 'ABORT: battery saver is on. Turn it off, then run again.'; exit 1 }
    if (-not (Test-UsbPowered $battery)) { Write-Host 'WARNING: the device is not USB powered (the conditions ask for USB power).' }
}

# Waits until the battery temperature is at most -MaxTempC (polls every 30 s, gives up after 10 minutes).
function Wait-DeviceCool([int]$SleepSeconds, [string]$Why) {
    if ($Rehearsal) { Write-Host "[rehearsal] cool-down before $Why skipped"; return }
    if ($DryRun) { Write-Host "[dry run] cool-down before: $Why (sleep $SleepSeconds s, then wait for <= $MaxTempC C)"; return }
    if ($SleepSeconds -gt 0) { Write-Host "Cool-down before $Why : leave the device idle, screen on, for $SleepSeconds s ..."; Start-Sleep -Seconds $SleepSeconds }
    $deadline = (Get-Date).AddMinutes(10)
    while ($true) {
        $temp = ConvertFrom-BatteryTemp (Invoke-Adb @('shell', 'dumpsys', 'battery'))
        $decision = Get-CoolDecision $temp $MaxTempC
        if ($decision -eq 'go') { return }
        if ($decision -eq 'unknown') { Write-Host 'ABORT: cannot read the battery temperature.'; exit 1 }
        if ((Get-Date) -gt $deadline) { Write-Host "ABORT: still $temp C after 10 minutes (ceiling $MaxTempC C). Let the phone cool and run again."; exit 1 }
        Write-Host "  $temp C > $MaxTempC C, waiting 30 s ..."
        Start-Sleep -Seconds 30
    }
}

# Runs device-run.ps1 as a child process (it has its own exit codes) and returns its exit code.
function Invoke-DeviceRun([hashtable]$Spec, [string]$Label) {
    $a = Get-DeviceRunArgs $Serial $Spec
    Write-Host "> powershell $($a -join ' ')"
    if ($DryRun) { return 0 }
    & powershell @a
    $code = $LASTEXITCODE
    if ($code -ne 0) { Write-Host "ABORT: $Label failed with exit code $code"; exit 1 }
    return 0
}

function Step-Bench {
    Wait-DeviceCool $CoolDownSeconds 'the first bench run'
    foreach ($run in (Get-BenchPlan $Date $BenchRuns $outBase)) {
        if ($run.Run -gt 1) { Wait-DeviceCool $BetweenRunsSeconds "bench run $($run.Run)" }
        Assert-DeviceReady "bench run $($run.Run) of $BenchRuns"
        $spec = @{ Crate = 'proviewer_core'; Bench = 'engine'; Push = $BenchPush; OutFile = $run.OutFile; Args = $benchProgramArgs }
        Invoke-DeviceRun $spec "bench run $($run.Run)" | Out-Null
        if ($DryRun) { continue }
        $text = Get-Content -Raw -LiteralPath $run.OutFile
        $missing = Get-MissingBenchIds $text $BenchIds
        if ($missing.Count) { Write-Host "ABORT: $($run.OutFile) lacks bench id(s): $($missing -join ', ')"; exit 1 }
        Write-Host "ok: all $($BenchIds.Count) bench ids in $($run.OutFile)"
    }
}

function Step-Pvcli {
    Wait-DeviceCool $CoolDownSeconds 'the pvcli reading runs'
    $first = $true
    foreach ($run in (Get-PvcliPlan $Date $Viewport $outBase)) {
        if (-not $first) { Wait-DeviceCool $BetweenRunsSeconds "pvcli $($run.Args[1])" }
        $first = $false
        Assert-DeviceReady "pvcli $($run.Args -join ' ')"
        $spec = @{ Crate = 'pvcli'; Bin = 'pvcli'; Push = $run.Push; OutFile = $run.OutFile; Args = $run.Args }
        Invoke-DeviceRun $spec "pvcli $($run.Args[1])" | Out-Null
        if ($DryRun) { continue }
        if (-not (Test-PvcliOutput (Get-Content -Raw -LiteralPath $run.OutFile))) {
            Write-Host "ABORT: $($run.OutFile) has no pvcli rows and summary"; exit 1
        }
        Write-Host "ok: $($run.OutFile)"
    }
}

# Runs before the long steps: the Phase 0 app must be installed for the meminfo capture. Installs nothing.
function Assert-AppInstalled {
    if ($DryRun -or $steps -notcontains 'meminfo') { return }
    $installed = Invoke-Adb @('shell', 'pm', 'list', 'packages', $AppPackage)
    if ($installed -match [regex]::Escape("package:$AppPackage")) { Write-Host "ok: $AppPackage is installed on $Serial"; return }
    Write-Host "ABORT: the Phase 0 app ($AppPackage) is not installed on $Serial. This script installs nothing."
    Write-Host "  Install it yourself, for example:  `$env:ANDROID_SERIAL='$Serial'; .\gradlew.bat :app:installDebug"
    Write-Host '  then open it, leave it on its first screen for ~10 s and run again; or pass -SkipMeminfo to skip the memory baseline.'
    exit 1
}

# Read only: nothing is installed or started. The app must already be running to have a process to measure.
function Step-Meminfo {
    Assert-DeviceReady 'Phase 0 app memory baseline'
    $out = Join-OutPath $outBase "phase0-app-meminfo-$Date.txt"
    if ($DryRun) { Write-Host "[dry run] adb -s $Serial shell dumpsys meminfo $AppPackage > $out"; return }
    $installed = Invoke-Adb @('shell', 'pm', 'list', 'packages', $AppPackage)
    if ($installed -notmatch [regex]::Escape("package:$AppPackage")) {
        Write-Host "The Phase 0 app ($AppPackage) is not installed on $Serial. This script installs nothing."
        Write-Host "  Install it yourself, for example:  `$env:ANDROID_SERIAL='$Serial'; .\gradlew.bat :app:installDebug"
        Write-Host '  then open it, leave it on its first screen for ~10 s and run this step again:  -Only meminfo'
        return
    }
    $text = Invoke-Adb @('shell', 'dumpsys', 'meminfo', $AppPackage)
    $pss = Get-TotalPss $text
    if ($null -eq $pss) {
        Write-Host "No TOTAL PSS for $AppPackage (is it running? open the app, wait ~10 s, run '-Only meminfo' again)."
        Write-Host $text
        return
    }
    [IO.File]::WriteAllText((Join-Path $repoRoot $out), $text + "`n")
    $script:MeminfoCaptured = $true
    Write-Host "Phase 0 app TOTAL PSS: $pss kB (device $Serial, $Date); raw dumpsys saved to $out"
}

# OPTIONAL and FLAGGED (Task 3 open item): long, installs the benchmark APK. Only with -Only platform / -IncludePlatform.
function Step-Platform {
    Write-Host 'FLAG: optional Task 3 platform JPEG baseline. It installs the :codecbench test APK on the device and ran'
    Write-Host '      for over an hour on this phone (thermal throttling). Run it only on purpose; it is an owner call.'
    Wait-DeviceCool $CoolDownSeconds 'the platform baseline'
    Assert-DeviceReady 'platform baseline'
    $cmd = '.\gradlew.bat :codecbench:connectedReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=UNLOCKED'
    Write-Host "> `$env:ANDROID_SERIAL='$Serial'; $cmd"
    if ($DryRun) { return }
    $env:ANDROID_SERIAL = $Serial
    try { & powershell -NoProfile -Command $cmd } finally { Remove-Item Env:\ANDROID_SERIAL -ErrorAction SilentlyContinue }
    if ($LASTEXITCODE -ne 0) { Write-Host "ABORT: platform baseline failed with exit code $LASTEXITCODE"; exit 1 }
    Write-Host 'Copy the results JSON from codecbench/build/outputs/connected_android_test_additional_output/ to'
    Write-Host "docs/perf/baselines/phase1-codecs-platform-$Date.json and add the platform column per docs/perf/codec-comparison.md."
}

# Device checks first, then the app presence, all before the long work.
if (-not $DryRun) { Assert-DeviceReady 'session start' }
Assert-AppInstalled

foreach ($s in $steps) {
    Write-Host "=== step: $s ==="
    switch ($s) {
        'bench' { Step-Bench }
        'pvcli' { Step-Pvcli }
        'meminfo' { Step-Meminfo }
        'platform' { Step-Platform }
    }
}
$exitCode = Get-SessionExitCode $steps $script:MeminfoCaptured ([bool]$DryRun)
if ($exitCode -ne 0) { Write-Host 'phase1-device-session: FAILED: the Phase 0 app memory baseline (meminfo) was not captured.'; exit $exitCode }
if ($Rehearsal) { Write-Host ('phase1-device-session: rehearsal done (REHEARSAL ' + [char]0x2014 + ' numbers invalid). Files are under ' + $outBase) }
else { Write-Host 'phase1-device-session: done. Next: fill docs/perf/phase1-engine.md (PENDING markers) and the budgets c/d proposals.' }
exit 0
