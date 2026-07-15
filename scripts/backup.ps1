param(
    [string]$DataDir = (Join-Path $PSScriptRoot "..\data"),
    [string]$OutputDir = (Join-Path $PSScriptRoot "..\backups"),
    [int]$ServerPort = 8080,
    [switch]$SkipServerCheck
)

$ErrorActionPreference = "Stop"
$data = [System.IO.Path]::GetFullPath($DataDir)
$output = [System.IO.Path]::GetFullPath($OutputDir)
if (-not (Test-Path -LiteralPath $data -PathType Container)) {
    throw "Data directory does not exist: $data"
}
if (-not (Test-Path -LiteralPath (Join-Path $data "paicli.db") -PathType Leaf)) {
    throw "SQLite database does not exist below the data directory"
}
if (-not $SkipServerCheck) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        try { $connected = $client.ConnectAsync("127.0.0.1", $ServerPort).Wait(300) }
        catch { $connected = $false }
        if ($connected -and $client.Connected) {
            throw "PaiCLI Server appears to be running on port $ServerPort. Stop it before backup."
        }
    } finally {
        $client.Dispose()
    }
}

New-Item -ItemType Directory -Force -Path $output | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$archive = Join-Path $output "paicli-backup-$stamp.zip"
Compress-Archive -Path (Join-Path $data "*") -DestinationPath $archive -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
$checksum = "$archive.sha256"
Set-Content -LiteralPath $checksum -Encoding ascii -NoNewline -Value "$hash  $([System.IO.Path]::GetFileName($archive))"
Write-Output $archive
Write-Output $checksum
