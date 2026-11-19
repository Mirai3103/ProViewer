# Fuzz one engine target inside the dedicated WSL distro `proviewer`.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fuzz.ps1 -Target <archive|decode> [-Seconds 900] [-LogFile <path>]
#
# Preflight only: this script installs and configures nothing. The distro is set up beforehand with
# docs/dev/wsl-proviewer-setup.md. Source stays in this (Windows) repo, reached through /mnt/e; the cargo build
# directory lives on the distro's own ext4 disk ($HOME/pv-fuzz-target) for speed.
# Exit code: 0 when libFuzzer finishes without findings, non-zero on a crash (the artifact path is printed) or any failure.
# Removal, if ever needed: wsl --unregister proviewer   (deletes the distro and its disk)
param(
    [ValidateSet('archive', 'decode')][string]$Target,
    [int]$Seconds = 900,
    [string]$LogFile = '',
    [switch]$SelfTest
)

$ErrorActionPreference = 'Continue'

# ------------------------------------------------------------------------------------------------
# Pure helper (covered by -SelfTest)
# ------------------------------------------------------------------------------------------------

# From one libFuzzer output line, return the Windows path of the written crash artifact, or $null if the line
# is not a "Test unit written to" line. cargo-fuzz passes an absolute /mnt/<drive>/... artifact prefix; a relative
# path (./artifacts/...) is taken relative to the fuzz crate directory.
function Resolve-CrashArtifact([string]$Line, [string]$FuzzDir) {
    if ($Line -notmatch 'Test unit written to (.+)$') { return $null }
    $path = $Matches[1].Trim()
    if ($path -match '^/mnt/([A-Za-z])/(.*)$') {
        return $Matches[1].ToUpperInvariant() + ':\' + ($Matches[2] -replace '/', '\')
    }
    return Join-Path $FuzzDir (($path -replace '^\./', '') -replace '/', '\')
}

function Invoke-SelfTest {
    $fuzz = 'E:\Code\ProViewer\core\fuzz'
    $cases = @(
        @('Test unit written to /mnt/e/Code/ProViewer/core/fuzz/artifacts/decode/crash-abc', 'E:\Code\ProViewer\core\fuzz\artifacts\decode\crash-abc'),
        @('Test unit written to ./artifacts/archive/crash-def', 'E:\Code\ProViewer\core\fuzz\artifacts\archive\crash-def'),
        @('Test unit written to /mnt/d/x/y/oom-1', 'D:\x\y\oom-1'),
        @('SUMMARY: libFuzzer: deadly signal', $null),
        @('#12 NEW cov: 1 ft: 2', $null)
    )
    $failed = 0
    foreach ($c in $cases) {
        $got = Resolve-CrashArtifact $c[0] $fuzz
        if ($got -eq $c[1]) { Write-Host "ok   $($c[0]) -> $got" }
        else { Write-Host "FAIL $($c[0]) -> '$got' (want '$($c[1])')"; $failed++ }
    }
    if ($failed) { Write-Host "$failed self-test case(s) failed"; return 1 }
    Write-Host 'fuzz.ps1 self-test passed'
    return 0
}

if ($SelfTest) { exit (Invoke-SelfTest) }
if (-not $Target) { Write-Host 'usage: fuzz.ps1 -Target <archive|decode> [-Seconds 900] [-LogFile <path>]'; exit 1 }
$repoRoot = Split-Path -Parent $PSScriptRoot
$notReady = "WSL distro 'proviewer' not ready - see docs/dev/wsl-proviewer-setup.md"

# --- Preflight (reports only) ---
$distros = (& wsl.exe -l -q 2>$null) -replace "`0", '' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
if (-not ($distros -contains 'proviewer')) {
    Write-Host $notReady
    exit 1
}
& wsl.exe -d proviewer -- bash -lc 'cargo +nightly fuzz --version' | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host $notReady
    exit 1
}

# --- Seeds from the Windows gen-corpus ---
$fuzzDir = Join-Path $repoRoot 'core/fuzz'
$seedDir = Join-Path $fuzzDir 'corpus-seeds'
if (Test-Path $seedDir) { Remove-Item -Recurse -Force $seedDir }
Push-Location $repoRoot
try {
    & cargo run -q -p gen-corpus --release -- --fuzz-seeds $seedDir | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'gen-corpus --fuzz-seeds failed'
        exit 1
    }
}
finally {
    Pop-Location
}
New-Item -ItemType Directory -Force -Path (Join-Path $fuzzDir "corpus/$Target") | Out-Null

# --- Run libFuzzer (single process: no -jobs / -workers) ---
$inner = 'cd /mnt/e/Code/ProViewer/core/fuzz && CARGO_TARGET_DIR=$HOME/pv-fuzz-target ' +
    "cargo +nightly fuzz run $Target corpus/$Target corpus-seeds/$Target -- " +
    "-max_total_time=$Seconds -rss_limit_mb=2048 -malloc_limit_mb=512 -timeout=10 -print_final_stats=1 2>&1"
Write-Host "fuzz $Target for $Seconds s (seeds: $seedDir)"

$crash = $null
$lines = New-Object System.Collections.Generic.List[string]
& wsl.exe -d proviewer -- bash -lc $inner | ForEach-Object {
    $line = [string]$_
    $lines.Add($line)
    Write-Host $line
    $written = Resolve-CrashArtifact $line $fuzzDir
    if ($written) { $crash = $written }
}
$code = $LASTEXITCODE
if ($LogFile) { $lines | Set-Content -Path $LogFile -Encoding UTF8 }

if ($crash) {
    Write-Host "CRASH: libFuzzer wrote $crash"
    if ($code -eq 0) { $code = 1 }
}
if ($code -ne 0) {
    Write-Host "fuzz $Target failed (exit $code)"
    exit $code
}
Write-Host "fuzz $Target finished cleanly"
exit 0
