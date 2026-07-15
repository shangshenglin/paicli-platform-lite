param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [string]$DataDir = (Join-Path $PSScriptRoot "..\data"),
    [switch]$Force,
    [switch]$RequireChecksum
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
$checksum = "$source.sha256"
if (Test-Path -LiteralPath $checksum -PathType Leaf) {
    $expected = ((Get-Content -LiteralPath $checksum -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expected -ne $actual) { throw "Backup checksum does not match: $source" }
} elseif ($RequireChecksum) {
    throw "Backup checksum file does not exist: $checksum"
} else {
    Write-Warning "Restoring a legacy backup without a SHA-256 checksum"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archiveHandle = [System.IO.Compression.ZipFile]::OpenRead($source)
try {
    $root = $data.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    foreach ($entry in $archiveHandle.Entries) {
        $entryPath = [System.IO.Path]::GetFullPath((Join-Path $data $entry.FullName))
        if (-not $entryPath.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Backup contains an unsafe path: $($entry.FullName)"
        }
    }
} finally {
    $archiveHandle.Dispose()
}

$previous = "$data.before-restore.$(Get-Date -Format 'yyyyMMdd-HHmmss')"
if (Test-Path -LiteralPath $data) {
    Move-Item -LiteralPath $data -Destination $previous
}

try {
    New-Item -ItemType Directory -Force -Path $data | Out-Null
    Expand-Archive -LiteralPath $source -DestinationPath $data -Force
    $database = Join-Path $data "paicli.db"
    if (-not (Test-Path -LiteralPath $database -PathType Leaf)) {
        throw "Restored archive does not contain paicli.db"
    }
    $stream = [System.IO.File]::OpenRead($database)
    try {
        $header = New-Object byte[] 16
        $read = $stream.Read($header, 0, $header.Length)
        $signature = [System.Text.Encoding]::ASCII.GetString($header, 0, $read)
        if ($signature -ne "SQLite format 3`0") { throw "Restored paicli.db has an invalid SQLite header" }
    } finally {
        $stream.Dispose()
    }
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
