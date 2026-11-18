# APK-wide 16 KB page-alignment check (Play requirement for targetSdk 35+).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-16kb.ps1 -Apk <path>
# Exit 0 only if: (a) zipalign -P 16 passes, (b) every LOAD segment of every lib/**/*.so
# has Align >= 0x4000, (c) both ABIs of libproviewer_core.so are present.
param([Parameter(Mandatory)][string]$Apk)
$ErrorActionPreference = 'Stop'

$tools = & (Join-Path $PSScriptRoot 'check-env.ps1') -Quiet -PassThru
if ($tools.Failed) { Write-Host 'FAIL toolchain preflight failed'; exit 1 }
$Apk = (Resolve-Path $Apk).Path
$failures = New-Object System.Collections.Generic.List[string]

# (a) zip entry alignment
$zaOut = & $tools.ZipAlign -c -P 16 -v 4 $Apk 2>&1
if ($LASTEXITCODE -ne 0) { $failures.Add("zipalign -P 16 failed: $(($zaOut | Select-Object -Last 3) -join ' | ')") }

# (b) ELF LOAD segment alignment, every .so in the APK
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("check16kb-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $tmp | Out-Null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Apk)
    $soEntries = @($zip.Entries | Where-Object { $_.FullName -like 'lib/*.so' })
    $names = $soEntries | ForEach-Object { $_.FullName }
    foreach ($e in $soEntries) {
        $dest = Join-Path $tmp ($e.FullName -replace '/', '__')
        [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest)
        $aligns = @(& $tools.ReadElf -l $dest | Where-Object { $_ -match '^\s*LOAD\s' } |
            ForEach-Object { [Convert]::ToInt64((($_ -split '\s+')[-1] -replace '^0x', ''), 16) })
        if ($aligns.Count -eq 0) {
            $failures.Add("$($e.FullName): no LOAD segments found")
            continue
        }
        $min = [long]($aligns | Measure-Object -Minimum).Minimum
        $hex = '0x' + $min.ToString('x')
        Write-Host ("{0,-45} align {1}" -f $e.FullName, $hex)
        if ($min -lt 0x4000) { $failures.Add("$($e.FullName): LOAD align $hex < 0x4000") }
    }
    $zip.Dispose()

    # (c) our own library for both ABIs
    foreach ($abi in 'arm64-v8a', 'x86_64') {
        if ($names -notcontains "lib/$abi/libproviewer_core.so") {
            $failures.Add("missing lib/$abi/libproviewer_core.so in APK")
        }
    }
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "FAIL $_" }
    exit 1
}
Write-Host 'OK all native libraries are 16 KB aligned'
exit 0
