param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [string]$DataDir = (Join-Path $PSScriptRoot "..\data"),
    [switch]$Force
)

$ErrorActionPreference = "Stop"
if (-not $Force) {
    throw "Restore replaces the active data directory. Stop PaiCLI Server and rerun with -Force."
}

$source = [System.IO.Path]::GetFullPath($Archive)
$data = [System.IO.Path]::GetFullPath($DataDir)
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    throw "Backup archive does not exist: $source"
}

$previous = "$data.before-restore.$(Get-Date -Format 'yyyyMMdd-HHmmss')"
if (Test-Path -LiteralPath $data) {
    Move-Item -LiteralPath $data -Destination $previous
}

try {
    New-Item -ItemType Directory -Force -Path $data | Out-Null
    Expand-Archive -LiteralPath $source -DestinationPath $data -Force
    Write-Output "Restored to $data"
    if (Test-Path -LiteralPath $previous) {
        Write-Output "Previous data preserved at $previous"
    }
} catch {
    if (Test-Path -LiteralPath $data) {
        Remove-Item -LiteralPath $data -Recurse -Force
    }
    if (Test-Path -LiteralPath $previous) {
        Move-Item -LiteralPath $previous -Destination $data
    }
    throw
}
