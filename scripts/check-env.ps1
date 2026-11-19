# Toolchain preflight for ProViewer. Reports only; installs nothing.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-env.ps1 [-Quiet] [-PassThru]
# Other scripts dot-source:  $env = & scripts/check-env.ps1 -Quiet -PassThru
param(
    [switch]$Quiet,
    [switch]$PassThru
)

$PerfettoVersion = '59.1'   # pinned; matches google/perfetto release v59.1
$repoRoot = Split-Path -Parent $PSScriptRoot
$script:failed = $false

function Report([bool]$ok, [string]$name, [string]$detail = '') {
    if (-not $ok) { $script:failed = $true }
    if (-not $Quiet -or -not $ok) {
        $tag = if ($ok) { 'OK' } else { 'MISSING' }
        Write-Host ("{0} {1}{2}" -f $tag, $name, $(if ($detail) { " ($detail)" } else { '' }))
    }
}

function Version-AtLeast([string]$have, [string]$min) {
    try { return ([version]$have) -ge ([version]$min) } catch { return $false }
}

# --- Rust ---
$rustc = Get-Command rustc -ErrorAction SilentlyContinue
$cargo = Get-Command cargo -ErrorAction SilentlyContinue
$rustVer = ''
if ($rustc) { $rustVer = (& rustc --version) -replace '^rustc (\d+\.\d+\.\d+).*', '$1' }
Report ($rustc -and $cargo -and (Version-AtLeast $rustVer '1.80.0')) 'rustc/cargo >= 1.80' $rustVer

$targets = @()
if (Get-Command rustup -ErrorAction SilentlyContinue) { $targets = & rustup target list --installed }
foreach ($t in 'aarch64-linux-android', 'x86_64-linux-android') {
    Report ($targets -contains $t) "rustup target $t"
}

$cargoNdk = (& cargo --list 2>$null) -match '^\s+ndk(\s|$)'
Report ([bool]$cargoNdk) 'cargo-ndk'

# --- Android SDK / NDK ---
$sdk = $env:ANDROID_HOME
$ndkDir = $null; $zipAlign = $null; $readElf = $null
if ($sdk -and (Test-Path "$sdk\ndk")) {
    $ndkDir = Get-ChildItem "$sdk\ndk" -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
Report ([bool]$ndkDir) 'Android NDK' $ndkDir

if ($sdk -and (Test-Path "$sdk\build-tools")) {
    $zipAlign = Get-ChildItem "$sdk\build-tools" -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
        ForEach-Object { Join-Path $_.FullName 'zipalign.exe' } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
}
Report ([bool]$zipAlign) 'zipalign.exe' $zipAlign

if ($ndkDir) {
    $c = "$ndkDir\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
    if (Test-Path $c) { $readElf = $c }
}
Report ([bool]$readElf) 'llvm-readelf.exe' $readElf

# --- Java 17+ ---
$javaOk = $false; $javaVer = ''
if (Get-Command java -ErrorAction SilentlyContinue) {
    $out = (& cmd /c 'java -version 2>&1') -join "`n"
    if ($out -match 'version "(\d+)') { $javaVer = $Matches[1]; $javaOk = [int]$javaVer -ge 17 }
}
Report $javaOk 'java >= 17' $javaVer

# --- adb ---
$adb = Get-Command adb -ErrorAction SilentlyContinue
$adbOk = $false
if ($adb) { & adb version *> $null; $adbOk = ($LASTEXITCODE -eq 0) }
Report $adbOk 'adb' $(if ($adb) { $adb.Source })

# --- MSVC linker (VS 2022) ---
$linkExe = $null
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
if (Test-Path $vswhere) {
    $vsPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if ($vsPath) {
        $linkExe = Get-ChildItem "$vsPath\VC\Tools\MSVC" -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin\Hostx64\x64\link.exe' } |
            Where-Object { Test-Path $_ } | Select-Object -First 1
    }
}
Report ([bool]$linkExe) 'MSVC link.exe' $linkExe

# --- CMake + Ninja (libjpeg-turbo is built from source by cargo; the SDK cmake package ships both) ---
function Find-BuildTool([string]$exe) {
    $c = Get-Command $exe -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    if ($sdk -and (Test-Path "$sdk\cmake")) {
        return Get-ChildItem "$sdk\cmake" -Directory |
            Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
            ForEach-Object { Join-Path $_.FullName "bin\$exe" } |
            Where-Object { Test-Path $_ } | Select-Object -First 1
    }
}
$cmakeExe = Find-BuildTool 'cmake.exe'
Report ([bool]$cmakeExe) 'cmake' $cmakeExe
$ninjaExe = Find-BuildTool 'ninja.exe'
Report ([bool]$ninjaExe) 'ninja' $ninjaExe

# --- Perfetto trace_processor_shell (pinned) ---
$tp = Join-Path $repoRoot '.tools\trace_processor_shell.exe'
$tpOk = $false; $tpVer = ''
if (Test-Path $tp) {
    $tpVer = ((& $tp --version 2>&1) -join ' ')
    $tpOk = $tpVer -match [regex]::Escape("v$PerfettoVersion")
}
Report $tpOk "trace_processor_shell v$PerfettoVersion" $(if ($tpVer) { $tpVer } else { 'missing' })

if ($PassThru) {
    [pscustomobject]@{
        NdkDir         = $ndkDir
        ZipAlign       = $zipAlign
        ReadElf        = $readElf
        Adb            = $(if ($adb) { $adb.Source })
        Cmake          = $cmakeExe
        Ninja          = $ninjaExe
        TraceProcessor = $tp
        Failed         = $script:failed
    }
}
exit $(if ($script:failed) { 1 } else { 0 })
