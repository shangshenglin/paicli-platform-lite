$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
    . (Join-Path $PSScriptRoot "load-env.ps1") -Root $root
    $port = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }
    $listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listeners) {
        $pids = ($listeners | Select-Object -ExpandProperty OwningProcess -Unique) -join ", "
        throw "Port $port is already in use by process id(s): $pids. Stop the old PaiCLI server first, for example: Stop-Process -Id $pids -Force"
    }
    & .\mvnw.cmd -pl paicli-server -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    & .\scripts\run-server.ps1 -SandboxMode local
} finally {
    Pop-Location
}
