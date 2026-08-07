param([switch]$RunTests)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
    $mavenArgs = @("-pl", "paicli-sandbox-agent", "-am", "package")
    if (-not $RunTests) { $mavenArgs += "-DskipTests" }
    & .\mvnw.cmd @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

    docker build -t paicli-sandbox-agent:0.6.0 .\paicli-sandbox-agent
    if ($LASTEXITCODE -ne 0) { throw "Docker image build failed" }

    Write-Host "Built paicli-sandbox-agent:0.6.0"
} finally {
    Pop-Location
}
