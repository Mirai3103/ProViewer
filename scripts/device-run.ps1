# Cross-build a Rust binary or bench for arm64 Android, push it to the reference device and run it there.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-run.ps1 `
#          -Crate <pkg> [-Bin <name> | -Bench <name>] [-Features <csv>] [-Push <path[,path...]>] `
#          [-OutFile <path>] [-Serial <id>] [-- <args...>]
#        powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-run.ps1 -SelfTest
#
# -Serial defaults to the first attached device that is NOT an emulator; with none attached the script
# exits 1 ("reference device not attached"). It never falls back to the emulator: an explicit
# -Serial emulator-* is allowed for correctness runs only and prints a warning.
# -OutFile: written only when the run exits 0 (otherwise <out>.partial), together with a <out>.meta sidecar
# (serial, model, battery temperature, UTC date, git HEAD). With an emulator serial, an -OutFile under
# docs/perf is refused (exit 1): emulator results never go into committed baselines.
# Arguments are parsed by hand because Windows PowerShell's -File mode treats `--` as a parameter name.
$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot
$RemoteRoot = '/data/local/tmp/pv'

# ------------------------------------------------------------------------------------------------
# Pure helpers (covered by -SelfTest)
# ------------------------------------------------------------------------------------------------

# Parse the raw argument list into a hashtable. Throws on invalid input.
function ConvertFrom-DeviceRunArgs([object[]]$RawArgs) {
    # An element may itself be an array (`& device-run.ps1 -Push @('a', 'b')`); -File mode gives 'a,b'.
    $flat = @($RawArgs)
    $o = @{ Crate = $null; Bin = $null; Bench = $null; Features = @(); Push = @(); OutFile = $null
            Serial = $null; Rest = @(); SelfTest = $false }
    $valueFlags = @{ '-crate' = 'Crate'; '-bin' = 'Bin'; '-bench' = 'Bench'; '-features' = 'Features'
                     '-push' = 'Push'; '-outfile' = 'OutFile'; '-serial' = 'Serial' }
    $i = 0
    while ($i -lt $flat.Count) {
        $a = $flat[$i]
        if ($a -is [array]) { throw "unexpected list argument '$($a -join ',')'" }
        $a = [string]$a
        if ($a -eq '--') { $o.Rest = @($flat | Select-Object -Skip ($i + 1) | ForEach-Object { [string]$_ }); break }
        $key = $a.ToLowerInvariant()
        if ($key -eq '-selftest') { $o.SelfTest = $true; $i++; continue }
        if (-not $valueFlags.ContainsKey($key)) { throw "unknown argument '$a' (pass program arguments after --)" }
        if ($i + 1 -ge $flat.Count) { throw "$a needs a value" }
        $name = $valueFlags[$key]; $v = $flat[$i + 1]
        if ($name -in 'Features', 'Push') {
            $o[$name] = @($o[$name]) + @(@($v) | ForEach-Object { [string]$_ -split ',' } |
                ForEach-Object { $_.Trim() } | Where-Object { $_ })
        } else {
            if ($v -is [array]) { throw "$a takes a single value" }
            $o[$name] = [string]$v
        }
        $i += 2
    }
    if ($o.SelfTest) { return $o }
    if (-not $o.Crate) { throw '-Crate is required' }
    if ($o.Bin -and $o.Bench) { throw 'pass either -Bin or -Bench, not both' }
    if (-not $o.Bin -and -not $o.Bench) { $o.Bin = $o.Crate }
    return $o
}

# Pick the device serial from `adb devices` output. Returns @{ Serial; Warning; Error }.
function Select-DeviceSerial([string[]]$AdbDevicesLines, [string]$Explicit) {
    $attached = @(foreach ($l in $AdbDevicesLines) {
        if ($l -match '^(\S+)\s+device(\s|$)') { $Matches[1] }
    })
    if ($Explicit) {
        if ($attached -notcontains $Explicit) { return @{ Error = "device '$Explicit' not attached" } }
        $warn = $null
        if ($Explicit -like 'emulator-*') { $warn = 'WARNING: emulator numbers are not valid for performance' }
        return @{ Serial = $Explicit; Warning = $warn }
    }
    $real = @($attached | Where-Object { $_ -notlike 'emulator-*' })
    if ($real.Count -eq 0) { return @{ Error = 'reference device not attached' } }
    return @{ Serial = $real[0]; Warning = $null }
}

# Locate the executable cargo produced in <ProfileDir> (target/aarch64-linux-android/release).
# cargo-ndk 4.x forces `--message-format json-render-diagnostics` and consumes the artifact messages
# itself (cargo-ndk src/cargo.rs), so the path is resolved from cargo's fixed output layout instead:
# a bin is <ProfileDir>/<name> (re-uplifted by every build); a bench is deps/<name>-<16 hex> (or build/<pkg>/<hash>/out/<name>-<16 hex> with newer cargo), of which
# the newest is taken. Main deletes old bench executables before building so only this build's remains.
function Get-BenchExecutables([string]$ProfileDir, [string]$Name) {
    # Older cargo: <ProfileDir>/deps/<name>-<hash>. Newer cargo (build-dir layout v2, seen with cargo 1.100):
    # <ProfileDir>/build/<package>/<hash>/out/<name>-<hash>. Look in both.
    $dirs = @()
    $deps = Join-Path $ProfileDir 'deps'
    if (Test-Path -LiteralPath $deps) { $dirs += $deps }
    $build = Join-Path $ProfileDir 'build'
    if (Test-Path -LiteralPath $build) {
        $dirs += @(Get-ChildItem -LiteralPath $build -Directory | ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Directory } |
            ForEach-Object { Join-Path $_.FullName 'out' } | Where-Object { Test-Path -LiteralPath $_ })
    }
    $pattern = '^' + [regex]::Escape($Name.Replace('-', '_')) + '-[0-9a-f]{16}$'
    return @($dirs | ForEach-Object { Get-ChildItem -LiteralPath $_ -File } | Where-Object { $_.Name -match $pattern } |
        Sort-Object LastWriteTime -Descending)
}

function Resolve-BuiltExecutable([string]$ProfileDir, [string]$Name, [bool]$IsBench) {
    if (-not $IsBench) {
        $p = Join-Path $ProfileDir $Name
        if (Test-Path -LiteralPath $p -PathType Leaf) { return $p }
        return $null
    }
    $hit = Get-BenchExecutables $ProfileDir $Name | Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}

# Absolute paths are kept, relative ones are resolved against $Base (the caller's directory).
function Resolve-CallerPath([string]$Base, [string]$Path) {
    if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
    return [IO.Path]::GetFullPath((Join-Path $Base $Path))
}

# True if $Path resolves inside directory $Dir (case-insensitive, `..` resolved, a sibling with the same prefix is not inside).
function Test-PathInside([string]$Path, [string]$Dir) {
    $p = [IO.Path]::GetFullPath($Path)
    $d = [IO.Path]::GetFullPath($Dir).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    return $p.StartsWith($d, [StringComparison]::OrdinalIgnoreCase)
}

# Emulator results must never land in committed baselines. Returns an error message, or $null when allowed.
function Test-OutFileAllowed([string]$Serial, [string]$OutFile, [string]$RepoRoot) {
    if (-not $OutFile -or $Serial -notlike 'emulator-*') { return $null }
    if (Test-PathInside $OutFile (Join-Path $RepoRoot 'docs\perf')) {
        return "refusing -OutFile '$OutFile': emulator results (-Serial $Serial) must never be written under docs/perf (committed baselines); pick a scratch path"
    }
    return $null
}

# A run that did not exit 0 must not look like a finished result: it goes to <out>.partial.
function Get-OutFileTarget([string]$OutFile, [int]$ExitCode) {
    if ($ExitCode -eq 0) { return $OutFile }
    return "$OutFile.partial"
}

# `dumpsys battery` reports the temperature in tenths of a degree Celsius.
function Get-BatteryTempC([string[]]$DumpsysBatteryLines) {
    foreach ($l in $DumpsysBatteryLines) {
        if ($l -match '^\s*temperature:\s*(-?\d+)\s*$') {
            return ([double]$Matches[1] / 10).ToString('0.0', [Globalization.CultureInfo]::InvariantCulture)
        }
    }
    return 'unknown'
}

# Sidecar (<out>.meta) lines: where and when a result was measured.
function Format-RunMeta([string]$Serial, [string]$Model, [string]$BatteryTempC, [datetime]$DateUtc, [string]$GitHead) {
    return @("serial=$Serial", "model=$Model", "battery_temp_c=$BatteryTempC",
        ('date_utc=' + $DateUtc.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture)),
        "git_head=$GitHead")
}

function Invoke-SelfTest {
    $script:fails = 0
    function Check([bool]$cond, [string]$what) {
        if ($cond) { Write-Host "ok   $what" } else { Write-Host "FAIL $what"; $script:fails++ }
    }
    function Throws([scriptblock]$b) { try { & $b | Out-Null; return $false } catch { return $true } }

    $p = ConvertFrom-DeviceRunArgs @('-Crate', 'codec-bench', '-Push', 'a.jpg,b.jpg', '-Features', 'turbo',
        '-OutFile', 'o.csv', '--', '--iters', '3', '-Serial', 'x')
    Check ($p.Crate -eq 'codec-bench') 'args: crate'
    Check ($p.Bin -eq 'codec-bench') 'args: -Bin defaults to the crate name'
    Check (($p.Push -join '|') -eq 'a.jpg|b.jpg') 'args: comma-separated -Push'
    Check (($p.Features -join '|') -eq 'turbo') 'args: features'
    Check (($p.Rest -join '|') -eq '--iters|3|-Serial|x') 'args: everything after -- is passed through'
    Check ($null -eq $p.Serial) 'args: -Serial after -- is not ours'
    $q = ConvertFrom-DeviceRunArgs @('-crate', 'core', '-Bench', 'engine', '-Push', @('x', 'y'), '-Push', 'z')
    Check ($q.Bench -eq 'engine' -and -not $q.Bin) 'args: bench mode, case-insensitive flags'
    Check (($q.Push -join '|') -eq 'x|y|z') 'args: array and repeated -Push'
    Check (Throws { ConvertFrom-DeviceRunArgs @('-Bin', 'x') }) 'args: -Crate required'
    Check (Throws { ConvertFrom-DeviceRunArgs @('-Crate', 'c', '-Bin', 'x', '-Bench', 'y') }) 'args: -Bin and -Bench exclusive'
    Check (Throws { ConvertFrom-DeviceRunArgs @('-Crate', 'c', '--iters', '3') }) 'args: unknown flag before -- rejected'
    Check (Throws { ConvertFrom-DeviceRunArgs @('-Crate') }) 'args: missing value rejected'

    $both = @('List of devices attached', 'emulator-5554          device product:sdk_gphone64_x86_64',
        '3B65BT02H6G00000       device product:CPH2767IN model:CPH2767', '')
    $r = Select-DeviceSerial $both $null
    Check ($r.Serial -eq '3B65BT02H6G00000' -and -not $r.Warning) 'serial: default skips the emulator listed first'
    $r = Select-DeviceSerial @('List of devices attached', 'emulator-5554 device', 'ABC unauthorized') $null
    Check ($r.Error -eq 'reference device not attached' -and -not $r.Serial) 'serial: emulator only -> error, no fallback'
    $r = Select-DeviceSerial @('List of devices attached', 'R1 offline') $null
    Check ($r.Error -eq 'reference device not attached') 'serial: offline device is not used'
    $r = Select-DeviceSerial $both 'emulator-5554'
    Check ($r.Serial -eq 'emulator-5554' -and $r.Warning -eq 'WARNING: emulator numbers are not valid for performance') 'serial: explicit emulator warns'
    $r = Select-DeviceSerial $both 'NOPE'
    Check ($r.Error -and -not $r.Serial) 'serial: explicit serial must be attached'

    $base = Join-Path ([IO.Path]::GetTempPath()) 'base-dir'
    Check ((Resolve-CallerPath $base 'out/x.txt') -eq [IO.Path]::GetFullPath((Join-Path $base 'out/x.txt'))) 'path: relative is resolved against the caller directory'
    $abs = Join-Path ([IO.Path]::GetTempPath()) 'abs-dir/x.txt'
    Check ((Resolve-CallerPath $base $abs) -eq [IO.Path]::GetFullPath($abs)) 'path: absolute path is kept (it used to throw and silently drop -OutFile)'

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ('device-run-selftest-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force (Join-Path $tmp 'deps') | Out-Null
    try {
        $t0 = Get-Date
        $old = Join-Path $tmp 'deps\my_bench-00000000000000aa'
        Set-Content $old 'x'; (Get-Item $old).LastWriteTime = $t0.AddMinutes(-5)
        foreach ($f in 'deps\my_bench-0123456789abcdef', 'deps\my_bench-0123456789abcdef.d', 'deps\other-0123456789abcdef', 'codec-bench') {
            Set-Content (Join-Path $tmp $f) 'x'
        }
        Check ((Resolve-BuiltExecutable $tmp 'codec-bench' $false) -eq (Join-Path $tmp 'codec-bench')) 'exe: bin path'
        Check ((Resolve-BuiltExecutable $tmp 'my-bench' $true) -eq (Join-Path $tmp 'deps\my_bench-0123456789abcdef')) 'exe: newest bench binary, - mapped to _, .d skipped'
        Check ((Get-BenchExecutables $tmp 'my_bench').Count -eq 2) 'exe: both bench binaries found for cleanup, not other-*'
        # Newer cargo (build-dir layout v2) puts bench executables in build/<package>/<hash>/out/ instead of deps/.
        $out = Join-Path $tmp 'build\my_pkg\0123456789abcdef\out'
        New-Item -ItemType Directory -Force $out | Out-Null
        $v2 = Join-Path $out 'my_bench-fedcba9876543210'
        Set-Content $v2 'x'; Set-Content (Join-Path $out 'my_bench-fedcba9876543210.d') 'x'
        (Get-Item $v2).LastWriteTime = $t0.AddMinutes(5)
        Check ((Get-BenchExecutables $tmp 'my_bench').Count -eq 3) 'exe: bench binaries in build/<pkg>/<hash>/out are found too'
        Check ((Resolve-BuiltExecutable $tmp 'my-bench' $true) -eq $v2) 'exe: newest bench binary wins across both layouts'
        Remove-Item -Recurse -Force (Join-Path $tmp 'build')
        Check ($null -eq (Resolve-BuiltExecutable $tmp 'missing' $false)) 'exe: missing bin -> null'
        Check ($null -eq (Resolve-BuiltExecutable $tmp 'nobench' $true)) 'exe: missing bench -> null'
    } finally { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }

    # Provenance rules for -OutFile (pure helpers, no adb needed).
    $perf = Join-Path $repoRoot 'docs\perf'
    Check (Test-PathInside (Join-Path $perf 'baselines\x.csv') $perf) 'outfile: a file under docs/perf is inside it'
    Check (Test-PathInside (Join-Path $perf '..\perf\y.csv') $perf) 'outfile: .. segments are resolved before the check'
    Check (-not (Test-PathInside (Join-Path $repoRoot 'docs\perf-other\x.csv') $perf)) 'outfile: docs/perf-other is not inside docs/perf'
    Check (-not (Test-PathInside (Join-Path ([IO.Path]::GetTempPath()) 'x.csv') $perf)) 'outfile: temp is not inside docs/perf'
    $e = Test-OutFileAllowed 'emulator-5554' (Join-Path $perf 'baselines\x.csv') $repoRoot
    Check ($e -and $e -match 'emulator') 'outfile: emulator + docs/perf is refused with a message'
    Check ($null -eq (Test-OutFileAllowed 'emulator-5554' (Join-Path ([IO.Path]::GetTempPath()) 'x.csv') $repoRoot)) 'outfile: emulator + scratch path is allowed'
    Check ($null -eq (Test-OutFileAllowed '3B65BT02H6G00000' (Join-Path $perf 'baselines\x.csv') $repoRoot)) 'outfile: real device + docs/perf is allowed'
    Check ($null -eq (Test-OutFileAllowed 'emulator-5554' $null $repoRoot)) 'outfile: no -OutFile is always allowed'
    Check ((Get-OutFileTarget 'a\o.csv' 0) -eq 'a\o.csv') 'outfile: exit 0 writes the real file'
    Check ((Get-OutFileTarget 'a\o.csv' 1) -eq 'a\o.csv.partial') 'outfile: non-zero exit writes <out>.partial'
    Check ((Get-OutFileTarget 'a\o.csv' 137) -eq 'a\o.csv.partial') 'outfile: any non-zero exit is partial'
    Check ((Get-BatteryTempC @('Current Battery Service state:', '  level: 80', '  temperature: 285', '  voltage: 4100')) -eq '28.5') 'meta: battery temperature is tenths of a degree'
    Check ((Get-BatteryTempC @('  level: 80')) -eq 'unknown') 'meta: missing battery temperature is unknown'
    $meta = Format-RunMeta -Serial 'S1' -Model 'CPH2767' -BatteryTempC '28.5' -DateUtc ([datetime]'2026-01-02T03:04:05Z') -GitHead 'abc1234'
    Check ($meta -contains 'serial=S1' -and $meta -contains 'model=CPH2767' -and $meta -contains 'battery_temp_c=28.5' -and $meta -contains 'date_utc=2026-01-02T03:04:05Z' -and $meta -contains 'git_head=abc1234') 'meta: sidecar carries serial, model, battery temperature, UTC date and git HEAD'

    if ($script:fails) { Write-Host "$($script:fails) self-test check(s) failed"; return 1 }
    Write-Host 'self-test passed'
    return 0
}

# ------------------------------------------------------------------------------------------------
# Main
# ------------------------------------------------------------------------------------------------

try { $opt = ConvertFrom-DeviceRunArgs $args } catch { Write-Host "device-run: $_"; exit 2 }
if ($opt.SelfTest) { exit (Invoke-SelfTest) }

# Resolve -OutFile and -Push against the caller's directory before changing to the repo root.
$outFile = $null
if ($opt.OutFile) { $outFile = Resolve-CallerPath (Get-Location).Path $opt.OutFile }
$pushFiles = @(foreach ($f in $opt.Push) { Resolve-CallerPath (Get-Location).Path $f })
foreach ($f in $pushFiles) { if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { Write-Host "no such file: $f"; exit 1 } }

$tools = & (Join-Path $PSScriptRoot 'check-env.ps1') -Quiet -PassThru
if ($tools.Failed -or -not $tools.NdkDir -or -not $tools.Adb) {
    Write-Host 'toolchain preflight failed; run scripts/check-env.ps1'; exit 1
}
$env:ANDROID_NDK_HOME = $tools.NdkDir
$adb = $tools.Adb

# 1. Device selection (never the emulator by default).
$sel = Select-DeviceSerial @(& $adb devices) $opt.Serial
if ($sel.Error) { Write-Host $sel.Error; exit 1 }
$serial = $sel.Serial
Write-Host "device-run: using serial $serial"
if ($sel.Warning) { Write-Host $sel.Warning }
$refusal = Test-OutFileAllowed $serial $outFile $repoRoot
if ($refusal) { Write-Host "device-run: $refusal"; exit 1 }

# 2. libjpeg-turbo (feature turbo) is built from source with the SDK CMake + Ninja and the NDK toolchain file.
if ($opt.Features -contains 'turbo') {
    $cmakeRoot = Join-Path $env:ANDROID_HOME 'cmake'
    $cmakeBin = $null
    if (Test-Path $cmakeRoot) {
        $cmakeBin = Get-ChildItem $cmakeRoot -Directory |
            Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin' } |
            Where-Object { Test-Path (Join-Path $_ 'cmake.exe') } | Select-Object -First 1
    }
    if (-not $cmakeBin) { Write-Host 'feature turbo needs the SDK CMake: sdkmanager "cmake;<version>"'; exit 1 }
    $env:PATH = "$cmakeBin;$env:PATH"
    $env:CMAKE_GENERATOR = 'Ninja'
    $env:CMAKE_TOOLCHAIN_FILE_aarch64_linux_android = Join-Path $tools.NdkDir 'build\cmake\android.toolchain.cmake'
    Write-Host "device-run: CMake from $cmakeBin"
}

# 3. Cross-build and locate the executable.
Set-Location $repoRoot
$isBench = [bool]$opt.Bench
$cargoArgs = @('ndk', '-t', 'arm64-v8a', '-P', '29')
if ($isBench) { $cargoArgs += @('bench', '--no-run', '-p', $opt.Crate, '--bench', $opt.Bench) }
else { $cargoArgs += @('build', '--release', '-p', $opt.Crate, '--bin', $opt.Bin) }
if ($opt.Features.Count) { $cargoArgs += @('--features', ($opt.Features -join ',')) }
$meta = (& cargo metadata --format-version 1 --no-deps) | ConvertFrom-Json
$profileDir = Join-Path $meta.target_directory 'aarch64-linux-android\release'
$targetName = $(if ($isBench) { $opt.Bench } else { $opt.Bin })
# A bench executable's name carries a hash: remove old ones so the newest is from this build.
if ($isBench) { Get-BenchExecutables $profileDir $targetName | Remove-Item -Force }
Write-Host "device-run: cargo $($cargoArgs -join ' ')"
& cargo @cargoArgs | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { Write-Host 'cargo build failed'; exit 1 }
$exe = Resolve-BuiltExecutable $profileDir $targetName $isBench
if (-not $exe) { Write-Host "could not find the built executable for '$targetName' in $profileDir"; exit 1 }
$exeName = Split-Path -Leaf $exe
Write-Host "device-run: built $exe"

# 4. Push the executable and corpus files.
& $adb -s $serial shell "mkdir -p $RemoteRoot/bin $RemoteRoot/corpus" | Out-Null
& $adb -s $serial push $exe "$RemoteRoot/bin/$exeName" | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host 'adb push of the executable failed'; exit 1 }
& $adb -s $serial shell "chmod 755 $RemoteRoot/bin/$exeName" | Out-Null
foreach ($f in $pushFiles) {
    $leaf = Split-Path -Leaf $f
    $remote = "$RemoteRoot/corpus/$leaf"
    $localSize = (Get-Item -LiteralPath $f).Length
    $remoteSize = ((& $adb -s $serial shell "stat -c %s '$remote' 2>/dev/null") -join '').Trim()
    if ($remoteSize -eq "$localSize") { Write-Host "device-run: $leaf up to date"; continue }
    & $adb -s $serial push $f $remote | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "adb push of $leaf failed"; exit 1 }
}

# 5. Run, tee stdout to -OutFile, propagate the exit code.
$quoted = @($opt.Rest | ForEach-Object { "'" + ($_ -replace "'", "'\''") + "'" }) -join ' '
# adb shell merges the remote stderr into stdout, which would corrupt a CSV: stderr goes to a file on
# the device and is printed (not teed) after the run.
$remoteCmd = "cd $RemoteRoot && PV_CORPUS=$RemoteRoot/corpus ./bin/$exeName $quoted 2>$RemoteRoot/last-stderr.txt"
Write-Host "device-run: adb -s $serial shell $remoteCmd"
$lines = New-Object System.Collections.Generic.List[string]
& $adb -s $serial shell $remoteCmd | ForEach-Object { Write-Host $_; $lines.Add($_) }
$code = $LASTEXITCODE
Write-Host '--- stderr ---'
& $adb -s $serial shell "cat $RemoteRoot/last-stderr.txt" | ForEach-Object { Write-Host $_ }
Write-Host '--- end stderr ---'
if ($outFile) {
    # Only a successful run (exit 0) gets the real file name; anything else is <out>.partial and no sidecar.
    $target = Get-OutFileTarget $outFile $code
    New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
    [IO.File]::WriteAllLines($target, $lines)
    Write-Host "device-run: stdout written to $target"
    if ($code -eq 0) {
        $model = ((& $adb -s $serial shell 'getprop ro.product.model') -join '').Trim()
        $temp = Get-BatteryTempC @(& $adb -s $serial shell 'dumpsys battery')
        $head = ((& git -C $repoRoot rev-parse HEAD) -join '').Trim()
        [IO.File]::WriteAllLines("$outFile.meta", (Format-RunMeta $serial $model $temp ([datetime]::UtcNow) $head))
        Write-Host "device-run: provenance written to $outFile.meta"
    } else {
        Write-Host "device-run: run failed (exit $code); output kept as $target, no result file or .meta written"
    }
}
Write-Host "device-run: exit code $code"
exit $code
