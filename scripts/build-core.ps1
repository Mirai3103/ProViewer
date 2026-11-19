# Cross-build proviewer_core for Android into app/src/main/jniLibs/<abi>/.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-core.ps1 [-Profile release]
param([string]$Profile = 'release')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

$tools = & (Join-Path $PSScriptRoot 'check-env.ps1') -Quiet -PassThru
if ($tools.Failed) { throw 'toolchain preflight failed; run scripts/check-env.ps1' }
$env:ANDROID_NDK_HOME = $tools.NdkDir

# libjpeg-turbo (turbojpeg-sys) is built from source by CMake + Ninja with the NDK toolchain file.
# Put cmake/ninja on PATH for this process only if they are not already there.
foreach ($exe in $tools.Cmake, $tools.Ninja) {
    $dir = Split-Path -Parent $exe
    if (($env:PATH -split ';') -notcontains $dir) { $env:PATH = "$dir;$env:PATH" }
}
$env:CMAKE_GENERATOR = 'Ninja'
$toolchain = Join-Path $tools.NdkDir 'build\cmake\android.toolchain.cmake'
$env:CMAKE_TOOLCHAIN_FILE_aarch64_linux_android = $toolchain
$env:CMAKE_TOOLCHAIN_FILE_x86_64_linux_android = $toolchain

Push-Location $repoRoot
try {
    cargo ndk -t arm64-v8a -t x86_64 -o app/src/main/jniLibs build --profile $Profile -p proviewer_core
    if ($LASTEXITCODE -ne 0) { throw 'cargo ndk build failed' }
} finally { Pop-Location }
