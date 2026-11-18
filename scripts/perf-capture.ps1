# Capture Perfetto FrameTimeline + peak PSS for one scenario and append a baseline row.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/perf-capture.ps1 `
#          -Package <id> -Scenario <name> -Action <webtoon-scroll|paged-swipe|manual> `
#          -ExpectedHz <double> [-Repeat 30] [-ManualSeconds 15]
# Exit 1 if the refresh rate is not ExpectedHz (before or after), or if zero frames were found.
param(
    [Parameter(Mandatory)][string]$Package,
    [Parameter(Mandatory)][string]$Scenario,
    [Parameter(Mandatory)][ValidateSet('webtoon-scroll', 'paged-swipe', 'manual')][string]$Action,
    [Parameter(Mandatory)][double]$ExpectedHz,
    [int]$Repeat = 30,
    [int]$ManualSeconds = 15
)
$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$tools = & (Join-Path $PSScriptRoot 'check-env.ps1') -Quiet -PassThru
if ($tools.Failed) { Write-Host 'toolchain preflight failed; run scripts/check-env.ps1'; exit 1 }
$adb = $tools.Adb
$tp = $tools.TraceProcessor
$date = Get-Date -Format 'yyyy-MM-dd'
$work = Join-Path ([IO.Path]::GetTempPath()) ("perfcap-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $work | Out-Null

function Invoke-Framestat { & cargo run -q --release -p framestat -- @args }

function Get-ActiveHz {
    $f = Join-Path $work 'display.txt'
    & $adb shell dumpsys display | Set-Content -Encoding ascii $f
    $hz = Invoke-Framestat refresh $f 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $hz) { return $null }
    return [double]$hz
}

function Get-PssKb {
    $out = (& $adb shell dumpsys meminfo $Package) -join "`n"
    if ($out -match 'TOTAL PSS:\s+(\d+)') { return [int]$Matches[1] }
    if ($out -match '(?m)^\s*TOTAL\s+(\d+)') { return [int]$Matches[1] }
    return 0
}

# 1. Refresh rate must match before we start (Review Focus 5).
$hzBefore = Get-ActiveHz
if ($null -eq $hzBefore -or [Math]::Abs($hzBefore - $ExpectedHz) -gt 0.5) {
    Write-Host "Active refresh rate is '$hzBefore' Hz, expected $ExpectedHz Hz: lock the display to its max refresh rate."
    exit 1
}

# 2. Push config and start Perfetto in the background (stdin: SELinux blocks -c <file> from /data/local/tmp).
$sizeOut = (& $adb shell wm size) -join ' '
if ($sizeOut -notmatch '(\d+)x(\d+)\s*$') { Write-Host "cannot read screen size: $sizeOut"; exit 1 }
$w = [int]$Matches[1]; $h = [int]$Matches[2]

$perSwipeMs = 450
$actionMs = if ($Action -eq 'manual') { $ManualSeconds * 1000 } else { $Repeat * $perSwipeMs }
# Safety ceiling only: the trace is stopped explicitly right after the action (see step 4).
$durationMs = $actionMs * 3 + 10000
$cfg = (Get-Content (Join-Path $PSScriptRoot 'perfetto\frametimeline.pbtx') -Raw) -replace '\{\{DURATION_MS\}\}', $durationMs
$cfgLocal = Join-Path $work 'pv-ft.pbtx'
Set-Content -Encoding ascii $cfgLocal $cfg
& $adb push $cfgLocal /data/local/tmp/pv-ft.pbtx | Out-Null
& $adb shell rm -f /data/misc/perfetto-traces/pv.pftrace | Out-Null
& $adb shell "cat /data/local/tmp/pv-ft.pbtx | perfetto --txt -c - -o /data/misc/perfetto-traces/pv.pftrace --background" | Out-Null
Start-Sleep -Seconds 1

# 3. Perform the action, sampling peak PSS about once per second.
$peakPss = 0
$sw = [Diagnostics.Stopwatch]::StartNew()
$lastSample = -1000
function Sample-Pss {
    $script:peakPss = [Math]::Max($script:peakPss, (Get-PssKb))
    $script:lastSample = $sw.ElapsedMilliseconds
}
if ($Action -eq 'manual') {
    Write-Host "Perform the gesture now (pinch-zoom / pan) for $ManualSeconds s..."
    while ($sw.ElapsedMilliseconds -lt $actionMs) {
        if ($sw.ElapsedMilliseconds - $lastSample -ge 1000) { Sample-Pss }
        Start-Sleep -Milliseconds 200
    }
} else {
    for ($i = 0; $i -lt $Repeat; $i++) {
        if ($Action -eq 'webtoon-scroll') {
            & $adb shell input swipe ([int]($w / 2)) ([int]($h * 0.75)) ([int]($w / 2)) ([int]($h * 0.25)) 300
        } else {
            & $adb shell input swipe ([int]($w * 0.8)) ([int]($h / 2)) ([int]($w * 0.2)) ([int]($h / 2)) 200
        }
        if ($sw.ElapsedMilliseconds - $lastSample -ge 1000) { Sample-Pss }
    }
}

$actionElapsedMs = $sw.ElapsedMilliseconds
if ($actionElapsedMs + 1000 -gt $durationMs) {
    Write-Host "Trace window ($durationMs ms) is shorter than the action ($actionElapsedMs ms): the capture would cover only part of the scenario."
    exit 1
}

Write-Host "action took $actionElapsedMs ms (trace window $durationMs ms)"
Start-Sleep -Milliseconds 500
& $adb shell pkill -TERM perfetto | Out-Null   # graceful stop: perfetto flushes and writes the trace

# 4. Wait for perfetto to exit, then pull the trace.
for ($i = 0; $i -lt 60; $i++) {
    $pid1 = ((& $adb shell pidof perfetto) -join '').Trim()
    if (-not $pid1) { break }
    Start-Sleep -Seconds 1
}
$tracesDir = Join-Path $repoRoot 'docs\perf\traces'
New-Item -ItemType Directory -Force $tracesDir | Out-Null
$trace = Join-Path $tracesDir "$date-$Package-$Scenario.pftrace"
& $adb pull /data/misc/perfetto-traces/pv.pftrace $trace | Out-Null
if (-not (Test-Path $trace)) { Write-Host 'failed to pull trace'; exit 1 }

# 5. Refresh rate must not have changed during the run (Review Focus 5).
$hzAfter = Get-ActiveHz
if ($null -eq $hzAfter -or [Math]::Abs($hzAfter - $ExpectedHz) -gt 0.5) {
    Write-Host "Refresh rate changed during the run ($ExpectedHz -> '$hzAfter' Hz); no row written."
    exit 1
}

# 6. Frames of the target package -> statistics (Review Focus 6).
$sql = (Get-Content (Join-Path $PSScriptRoot 'perfetto\frames.sql') -Raw) -replace '\{\{PACKAGE\}\}', $Package
$sqlLocal = Join-Path $work 'frames.sql'
Set-Content -Encoding ascii $sqlLocal $sql
$csvLocal = Join-Path $work 'frames.csv'
& $tp -q $sqlLocal $trace 2>$null | Set-Content -Encoding ascii $csvLocal
$line = Invoke-Framestat frames $csvLocal
if ($LASTEXITCODE -ne 0 -or -not $line) { Write-Host 'framestat failed to parse the frames CSV'; exit 1 }
$parts = $line.Split(',')
if ([int]$parts[0] -eq 0) {
    Write-Host "0 frames for '$Package': wrong package name, or a renderer FrameTimeline cannot attribute (or nothing was drawn)."
    exit 1
}

# 6b. The rate the app's frames were actually presented at must match too (a per-app or panel rate
# change mid-run is invisible to the idle display mode check): median spacing of frame timestamps.
$traceHz = Invoke-Framestat period $csvLocal
if ($LASTEXITCODE -ne 0 -or -not $traceHz) { Write-Host 'cannot derive the effective refresh rate from the trace; no row written.'; exit 1 }
if ([Math]::Abs([double]$traceHz - $ExpectedHz) -gt ($ExpectedHz * 0.05)) {
    Write-Host "Trace shows frames presented at $traceHz Hz, expected $ExpectedHz Hz (adaptive refresh or app frame-rate override); no row written."
    exit 1
}

# 7. Append the baseline row.
$baselines = Join-Path $repoRoot 'docs\perf\baselines'
New-Item -ItemType Directory -Force $baselines | Out-Null
$csvOut = Join-Path $baselines "$date-$Package.csv"
if (-not (Test-Path $csvOut)) {
    Set-Content -Encoding ascii $csvOut 'date,package,scenario,hz,frames,janky,janky_pct,p50,p90,p95,p99,peak_pss_kb'
}
$row = ($date, $Package, $Scenario, $ExpectedHz) + $parts + @($peakPss)
Add-Content -Encoding ascii $csvOut ($row -join ',')
Write-Host ('row: ' + ($row -join ','))
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
exit 0
