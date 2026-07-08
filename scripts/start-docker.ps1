$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
    . (Join-Path $PSScriptRoot "load-env.ps1") -Root $root
    docker version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Docker Desktop is not available" }

    & .\scripts\build-sandbox.ps1
    & .\scripts\run-server.ps1 -SandboxMode docker
} finally {
    Pop-Location
}
