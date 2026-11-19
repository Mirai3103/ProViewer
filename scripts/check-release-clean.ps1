# Verify a release APK contains no benchmark code: no lib/*/libproviewer_benchgen.so and no
# "PV_BENCHMARK_MARKER" ASCII bytes in any classes*.dex.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-release-clean.ps1 -Apk <path>
# Exit 0 if clean, exit 1 if benchmark code is present.
param([Parameter(Mandatory)][string]$Apk)
$ErrorActionPreference = 'Stop'

$Apk = (Resolve-Path $Apk).Path
$failures = New-Object System.Collections.Generic.List[string]

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($Apk)
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("check-release-clean-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $tmp | Out-Null
try {
    $entries = @($zip.Entries)

    # (a) the benchmark-only native library must be absent
    $benchSo = @($entries | Where-Object { $_.FullName -like 'lib/*/libproviewer_benchgen.so' })
    if ($benchSo.Count -gt 0) {
        $failures.Add((($benchSo | ForEach-Object { $_.FullName }) -join ', '))
    }

    # (b) the benchmark marker string must be absent from every classes*.dex
    $marker = 'PV_BENCHMARK_MARKER'
    $dexEntries = @($entries | Where-Object { $_.FullName -like 'classes*.dex' })
    foreach ($e in $dexEntries) {
        $dest = Join-Path $tmp ($e.FullName -replace '/', '__')
        [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest)
        $text = [System.Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($dest))
        if ($text.Contains($marker)) {
            $failures.Add("$($e.FullName): contains $marker")
        }
    }
} finally {
    $zip.Dispose()
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "BENCHMARK CODE PRESENT: $_" }
    exit 1
}
Write-Host 'OK release APK contains no benchmark code'
exit 0
