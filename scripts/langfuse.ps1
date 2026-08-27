param(
    [ValidateSet("init", "start", "pull", "stop", "status", "logs", "config", "credentials")]
    [string]$Action = "start",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$composePath = Join-Path $projectRoot "deploy/langfuse/docker-compose.yml"
$environmentPath = Join-Path $projectRoot "deploy/langfuse/.env"
$dockerExecutable = $null

function Resolve-DockerExecutable {
    $candidates = [Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($env:PAICLI_DOCKER_EXECUTABLE)) {
        $candidates.Add($env:PAICLI_DOCKER_EXECUTABLE)
    }
    $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -ne $dockerCommand -and -not [string]::IsNullOrWhiteSpace($dockerCommand.Source)) {
        $candidates.Add($dockerCommand.Source)
    }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA "Programs/DockerDesktop/resources/bin/docker.exe"))
        $candidates.Add((Join-Path $env:LOCALAPPDATA "Docker/wsl/docker-desktop/cli-tools/usr/bin/docker.exe"))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $candidates.Add((Join-Path $env:ProgramFiles "Docker/Docker/resources/bin/docker.exe"))
    }
    $dockerBackend = Get-Process "com.docker.backend" -ErrorAction SilentlyContinue |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_.Path) } |
        Select-Object -First 1
    if ($null -ne $dockerBackend) {
        $resourcesDirectory = Split-Path -Parent $dockerBackend.Path
        $candidates.Add((Join-Path $resourcesDirectory "bin/docker.exe"))
    }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw "Docker CLI was not found. Start Docker Desktop, add docker to PATH, or set PAICLI_DOCKER_EXECUTABLE."
}

function New-HexSecret([int]$ByteCount) {
    $bytes = New-Object byte[] $ByteCount
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Initialize-LangfuseEnvironment {
    if ((Test-Path -LiteralPath $environmentPath) -and -not $Force) {
        Write-Host "Langfuse environment already exists: $environmentPath"
        return
    }
    $postgresPassword = New-HexSecret 16
    $publicKey = "pk-lf-$(New-HexSecret 16)"
    $secretKey = "sk-lf-$(New-HexSecret 24)"
    $adminPassword = New-HexSecret 12
    $lines = @(
        "LANGFUSE_PORT=3000",
        "LANGFUSE_MINIO_PORT=9090",
        "LANGFUSE_WEB_IMAGE=",
        "LANGFUSE_WORKER_IMAGE=",
        "LANGFUSE_CLICKHOUSE_IMAGE=",
        "LANGFUSE_REDIS_IMAGE=",
        "LANGFUSE_POSTGRES_IMAGE=",
        "LANGFUSE_MINIO_IMAGE=",
        "NEXTAUTH_SECRET=$(New-HexSecret 32)",
        "SALT=$(New-HexSecret 32)",
        "ENCRYPTION_KEY=$(New-HexSecret 32)",
        "POSTGRES_PASSWORD=$postgresPassword",
        "DATABASE_URL=postgresql://postgres:$postgresPassword@postgres:5432/postgres",
        "CLICKHOUSE_PASSWORD=$(New-HexSecret 16)",
        "REDIS_AUTH=$(New-HexSecret 16)",
        "MINIO_ROOT_PASSWORD=$(New-HexSecret 16)",
        "LANGFUSE_INIT_PROJECT_PUBLIC_KEY=$publicKey",
        "LANGFUSE_INIT_PROJECT_SECRET_KEY=$secretKey",
        "LANGFUSE_INIT_USER_EMAIL=admin@paicli.local",
        "LANGFUSE_INIT_USER_NAME=PaiCLI Admin",
        "LANGFUSE_INIT_USER_PASSWORD=$adminPassword"
    )
    $directory = Split-Path -Parent $environmentPath
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    [IO.File]::WriteAllLines($environmentPath, $lines, [Text.UTF8Encoding]::new($false))
    Write-Host "Created local-only Langfuse environment: $environmentPath"
}

function Read-LangfuseEnvironment {
    if (-not (Test-Path -LiteralPath $environmentPath)) {
        throw "Langfuse environment is missing. Run '.\scripts\langfuse.ps1 init' first."
    }
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($environmentPath)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith("#")) { continue }
        $separator = $line.IndexOf("=")
        if ($separator -le 0) { continue }
        $values[$line.Substring(0, $separator)] = $line.Substring($separator + 1)
    }
    return $values
}

function Invoke-Compose([string[]]$Arguments) {
    & $script:dockerExecutable compose --env-file $environmentPath -f $composePath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Langfuse Docker Compose command failed" }
}

function Show-Credentials {
    $values = Read-LangfuseEnvironment
    Write-Host "Langfuse UI: http://127.0.0.1:$($values['LANGFUSE_PORT'])"
    Write-Host "Admin: $($values['LANGFUSE_INIT_USER_EMAIL']) / $($values['LANGFUSE_INIT_USER_PASSWORD'])"
    Write-Host ""
    Write-Host "Configure PaiCLI in the current PowerShell session:"
    Write-Host "`$env:PAICLI_LANGFUSE_ENABLED='true'"
    Write-Host "`$env:PAICLI_LANGFUSE_BASE_URL='http://127.0.0.1:$($values['LANGFUSE_PORT'])'"
    Write-Host "`$env:PAICLI_LANGFUSE_PUBLIC_KEY='$($values['LANGFUSE_INIT_PROJECT_PUBLIC_KEY'])'"
    Write-Host "`$env:PAICLI_LANGFUSE_SECRET_KEY='$($values['LANGFUSE_INIT_PROJECT_SECRET_KEY'])'"
    Write-Host "`$env:PAICLI_LANGFUSE_CAPTURE_CONTENT='true' # required for Langfuse evaluators"
}

function Show-ConnectionSummary {
    $values = Read-LangfuseEnvironment
    Write-Host "Langfuse UI: http://127.0.0.1:$($values['LANGFUSE_PORT'])"
    Write-Host "Run '.\scripts\langfuse.ps1 credentials' to view the local login and PaiCLI environment settings."
}

Push-Location $projectRoot
try {
    if ($Action -eq "init") {
        Initialize-LangfuseEnvironment
        Show-Credentials
        return
    }
    if (-not (Test-Path -LiteralPath $environmentPath)) {
        Initialize-LangfuseEnvironment
    }
    $script:dockerExecutable = Resolve-DockerExecutable
    & $script:dockerExecutable version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Docker Desktop is not available" }
    switch ($Action) {
        "start" {
            Invoke-Compose @("up", "-d", "--wait")
            Show-ConnectionSummary
        }
        "pull" { Invoke-Compose @("pull") }
        "stop" { Invoke-Compose @("down") }
        "status" { Invoke-Compose @("ps") }
        "logs" { Invoke-Compose @("logs", "--tail", "200", "-f", "langfuse-web", "langfuse-worker") }
        "config" { Invoke-Compose @("config", "--quiet") }
        "credentials" { Show-Credentials }
    }
} finally {
    Pop-Location
}
