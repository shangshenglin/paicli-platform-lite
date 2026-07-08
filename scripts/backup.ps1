param(
    [string]$DataDir = (Join-Path $PSScriptRoot "..\data"),
    [string]$OutputDir = (Join-Path $PSScriptRoot "..\backups")
)

$ErrorActionPreference = "Stop"
$data = [System.IO.Path]::GetFullPath($DataDir)
$output = [System.IO.Path]::GetFullPath($OutputDir)
if (-not (Test-Path -LiteralPath $data -PathType Container)) {
    throw "Data directory does not exist: $data"
}

New-Item -ItemType Directory -Force -Path $output | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$archive = Join-Path $output "paicli-backup-$stamp.zip"
Compress-Archive -Path (Join-Path $data "*") -DestinationPath $archive -CompressionLevel Optimal
Write-Output $archive
