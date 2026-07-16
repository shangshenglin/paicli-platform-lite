param([switch]$Restart)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Get-ListeningProcessId([int]$Port) {
    $match = netstat -ano -p TCP | Select-String -Pattern "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)\s*$" | Select-Object -First 1
    if (-not $match) { return $null }
    return [int]$match.Matches[0].Groups[1].Value
}

function Test-PaiCliServer {
    $headers = @{}
    if ($env:PAICLI_API_KEY) { $headers["X-API-Key"] = $env:PAICLI_API_KEY }
    try {
        $info = Invoke-RestMethod -Uri "http://127.0.0.1:8080/v1/system/info" -Headers $headers -TimeoutSec 2
        return $info.name -eq "paicli-platform-lite"
    } catch {
        return $false
    }
}

Push-Location $root
try {
    . (Join-Path $PSScriptRoot "load-env.ps1") -Root $root
    $listenerProcessId = Get-ListeningProcessId 8080
    if ($listenerProcessId) {
        $listener = Get-Process -Id $listenerProcessId -ErrorAction SilentlyContinue
        if (-not (Test-PaiCliServer)) {
            $processName = if ($listener) { $listener.ProcessName } else { "unknown" }
            throw "Port 8080 is occupied by a non-PaiCLI process: $processName (PID $listenerProcessId)"
        }
        if (-not $Restart) {
            Write-Host "PaiCLI is already running at http://127.0.0.1:8080 (PID $listenerProcessId)."
            Write-Host "To rebuild and restart, run: .\scripts\start-local.ps1 -Restart"
            return
        }
        Write-Host "Stopping the existing PaiCLI server (PID $listenerProcessId)..."
        Stop-Process -Id $listenerProcessId -Force
        for ($attempt = 0; $attempt -lt 25 -and (Get-Process -Id $listenerProcessId -ErrorAction SilentlyContinue); $attempt++) {
            Start-Sleep -Milliseconds 200
        }
        if (Get-Process -Id $listenerProcessId -ErrorAction SilentlyContinue) {
            throw "Unable to stop the existing PaiCLI server (PID $listenerProcessId)"
        }
    }
    & .\mvnw.cmd -pl paicli-server -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    & .\scripts\run-server.ps1 -SandboxMode local
} finally {
    Pop-Location
}
