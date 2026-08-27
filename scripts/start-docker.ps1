param([switch]$Restart)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Get-ListeningProcessId([int]$Port) {
    $match = netstat -ano -p TCP |
        Select-String -Pattern "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)\s*$" |
        Select-Object -First 1

    if (-not $match) { return $null }
    return [int]$match.Matches[0].Groups[1].Value
}

function Test-PaiCliServer {
    $headers = @{}

    if ($env:PAICLI_API_KEY) {
        $headers["X-API-Key"] = $env:PAICLI_API_KEY
    }

    try {
        $info = Invoke-RestMethod `
            -Uri "http://127.0.0.1:8080/v1/system/info" `
            -Headers $headers `
            -TimeoutSec 2

        return $info.name -eq "paicli-platform-lite"
    }
    catch {
        return $false
    }
}

function Stop-RerankerForBuild {
    $running = docker ps `
        --filter "name=^/paicli-reranker$" `
        --filter "status=running" `
        --format "{{.Names}}"

    if ($running -eq "paicli-reranker") {
        Write-Host ""
        Write-Host "Stopping paicli-reranker temporarily to release build memory..."

        docker stop paicli-reranker | Out-Null

        if ($LASTEXITCODE -ne 0) {
            throw "Unable to stop paicli-reranker"
        }

        # 等待模型内存被 WSL/Docker 回收
        Start-Sleep -Seconds 2
    }
}

function Start-Reranker {
    Write-Host ""
    Write-Host "Ensuring paicli-reranker is running..."

    # 容器是否存在
    $exists = docker ps -a `
        --filter "name=^/paicli-reranker$" `
        --format "{{.Names}}"

    if ($exists -ne "paicli-reranker") {
        throw "paicli-reranker container does not exist"
    }

    # 已经运行则不用重复 start
    $running = docker ps `
        --filter "name=^/paicli-reranker$" `
        --filter "status=running" `
        --format "{{.Names}}"

    if ($running -ne "paicli-reranker") {
        docker start paicli-reranker | Out-Null

        if ($LASTEXITCODE -ne 0) {
            throw "Unable to start paicli-reranker"
        }
    }

    # 等待并验证真的进入 Running
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $status = docker inspect `
            --format '{{.State.Status}}' `
            paicli-reranker 2>$null

        if ($status -eq "running") {
            Write-Host "paicli-reranker is running."
            return
        }

        Start-Sleep -Seconds 1
    }

    throw "paicli-reranker failed to reach running state"
}

Push-Location $root

try {
    . (Join-Path $PSScriptRoot "load-env.ps1") `
        -Root $root `
        -OverrideExisting

    #
    # 1. 处理已有 PaiCLI Server
    #
    $listenerProcessId = Get-ListeningProcessId 8080

    if ($listenerProcessId) {
        $listener = Get-Process `
            -Id $listenerProcessId `
            -ErrorAction SilentlyContinue

        if (-not (Test-PaiCliServer)) {
            $processName = if ($listener) {
                $listener.ProcessName
            }
            else {
                "unknown"
            }

            throw "Port 8080 is occupied by a non-PaiCLI process: $processName (PID $listenerProcessId)"
        }

        if (-not $Restart) {
            Write-Host "PaiCLI is already running at http://127.0.0.1:8080 (PID $listenerProcessId)."

            # 即使 PaiCLI 已经在运行，也保证 reranker 开启
            Start-Reranker

            return
        }

        Write-Host "Stopping the existing PaiCLI server (PID $listenerProcessId)..."

        Stop-Process -Id $listenerProcessId -Force

        for (
            $attempt = 0;
            $attempt -lt 25 -and
                (Get-Process -Id $listenerProcessId -ErrorAction SilentlyContinue);
            $attempt++
        ) {
            Start-Sleep -Milliseconds 200
        }

        if (
            Get-Process `
                -Id $listenerProcessId `
                -ErrorAction SilentlyContinue
        ) {
            throw "Unable to stop the existing PaiCLI server (PID $listenerProcessId)"
        }
    }

    #
    # 2. 检查 Docker
    #
    Write-Host ""
    Write-Host "Checking Docker..."

    docker version | Out-Null

    if ($LASTEXITCODE -ne 0) {
        throw "Docker Desktop is not available"
    }

    #
    # 3. 构建阶段临时关掉 reranker
    #
    Stop-RerankerForBuild

    #
    # 4. Build Server
    #
    Write-Host ""
    Write-Host "Building paicli-server..."

    & .\mvnw.cmd `
        -pl paicli-server `
        -am `
        package `
        -DskipTests

    if ($LASTEXITCODE -ne 0) {
        throw "Server build failed"
    }

    #
    # 5. Build Sandbox
    #
    Write-Host ""
    Write-Host "Building sandbox image..."

    & .\scripts\build-sandbox.ps1

    if ($LASTEXITCODE -ne 0) {
        throw "Sandbox build failed"
    }

    #
    # 6. 启动 PaiCLI
    #
    Write-Host ""
    Write-Host "Starting PaiCLI..."

    & .\scripts\run-server.ps1 -SandboxMode docker

    if ($LASTEXITCODE -ne 0) {
        throw "PaiCLI server startup failed"
    }

    #
    # 7. 项目启动完成后强制恢复 reranker
    #
    Start-Reranker

    Write-Host ""
    Write-Host "PaiCLI startup completed."
    Write-Host "Server:   http://127.0.0.1:8080"
    Write-Host "Reranker: running"
}
finally {
    Pop-Location
}