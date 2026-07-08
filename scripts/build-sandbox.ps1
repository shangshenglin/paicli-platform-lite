$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
    & .\mvnw.cmd package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

    docker build -t paicli-sandbox-agent:0.6.0 .\paicli-sandbox-agent
    if ($LASTEXITCODE -ne 0) { throw "Docker image build failed" }

    Write-Host "Built paicli-sandbox-agent:0.6.0"
} finally {
    Pop-Location
}
