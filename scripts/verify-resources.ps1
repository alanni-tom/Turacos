# ============================================================================
# verify-resources.ps1 - Verify UDF/Redis binary resource hashes
#                        (supply-chain integrity check)
#
# Usage (from project root):
#   powershell -ExecutionPolicy Bypass -File scripts/verify-resources.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/verify-resources.ps1 -fix   # regenerate manifest
#
# Manifest: src/main/resources/RESOURCE_SHA256.txt  (path<TAB>sha256)
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 parses it correctly.
# ============================================================================
param(
    [switch]$fix
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$resDir = Join-Path $projectRoot 'src\main\resources'
$manifest = Join-Path $resDir 'RESOURCE_SHA256.txt'

# Binary extensions that must be covered
$extensions = @('.so', '.dll', '.dylib', '.so_', '.dll_')

if ($fix) {
    $lines = @()
    Get-ChildItem -Path (Join-Path $resDir 'udf'), (Join-Path $resDir 'redis') -Recurse -File |
        Where-Object { $extensions -contains $_.Extension.ToLower() } |
        Sort-Object FullName | ForEach-Object {
            $rel = $_.FullName.Substring($resDir.Length + 1) -replace '\\', '/'
            $hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            $lines += "$rel`t$hash"
        }
    Set-Content -LiteralPath $manifest -Value $lines -Encoding UTF8
    Write-Host ("[+] Manifest regenerated: {0} ({1} entries)" -f $manifest, $lines.Count)
    exit 0
}

if (-not (Test-Path $manifest)) {
    Write-Error "[-] Manifest not found: $manifest (run with -fix first)"
    exit 1
}

$expected = @{}
foreach ($line in Get-Content $manifest -Encoding UTF8) {
    if ($line.Trim() -eq '') { continue }
    $parts = $line -split "`t"
    if ($parts.Count -ne 2) { continue }
    $expected[$parts[0]] = $parts[1].ToLowerInvariant()
}

$failures = 0
foreach ($rel in ($expected.Keys | Sort-Object)) {
    $path = Join-Path $resDir ($rel -replace '/', '\')
    if (-not (Test-Path $path)) {
        Write-Warning "[?] Missing: $rel"
        $failures++
        continue
    }
    $actual = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected[$rel]) {
        Write-Warning "[!] Hash mismatch: $rel"
        $failures++
    }
}

if ($failures -gt 0) {
    Write-Error "[-] Resource integrity check FAILED: $failures item(s) missing or modified"
    exit 1
}
Write-Host ("[+] Resource integrity check passed ({0} entries)" -f $expected.Count)
exit 0
