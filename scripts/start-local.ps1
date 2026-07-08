$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
    . (Join-Path $PSScriptRoot "load-env.ps1") -Root $root
    & .\mvnw.cmd -pl paicli-server -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    & .\scripts\run-server.ps1 -SandboxMode local
} finally {
    Pop-Location
}
