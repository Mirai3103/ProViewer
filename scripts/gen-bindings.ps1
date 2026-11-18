# Generate Kotlin bindings from the built host library (uniffi library mode).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gen-bindings.ps1 [-OutDir <path>]
param([string]$OutDir)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $repoRoot 'app\build\generated\uniffi\kotlin' }

Push-Location $repoRoot
try {
    cargo build -p proviewer_core
    if ($LASTEXITCODE -ne 0) { throw 'cargo build failed' }
    $dll = Join-Path $repoRoot 'target\debug\proviewer_core.dll'
    if (-not (Test-Path $dll)) { throw "host library not found: $dll" }

    New-Item -ItemType Directory -Force $OutDir | Out-Null
    cargo run -q -p uniffi-bindgen -- generate --library $dll --language kotlin --out-dir $OutDir
    if ($LASTEXITCODE -ne 0) { throw 'uniffi-bindgen failed' }
    Write-Host "Kotlin bindings written to $OutDir"
} finally { Pop-Location }
