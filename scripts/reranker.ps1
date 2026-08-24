param(
    [ValidateSet("start", "pull", "stop", "status", "logs", "test")]
    [string]$Action = "start"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $root "deploy/reranker/docker-compose.yml"

function Get-RerankerImage {
    if ($env:PAICLI_RAG_RERANKER_IMAGE) { return $env:PAICLI_RAG_RERANKER_IMAGE }
    return "ghcr.io/huggingface/text-embeddings-inference:cpu-1.9"
}

function Get-RerankerEndpoint {
    if ($env:PAICLI_RAG_RERANKER_ENDPOINT) { return $env:PAICLI_RAG_RERANKER_ENDPOINT.TrimEnd("/") }
    $port = if ($env:PAICLI_RAG_RERANKER_PORT) { $env:PAICLI_RAG_RERANKER_PORT } else { "8090" }
    return "http://127.0.0.1:$port"
}

function Invoke-RerankerImagePull {
    $image = Get-RerankerImage
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Write-Host "Pulling TEI reranker image (attempt $attempt/3): $image"
        docker pull $image
        if ($LASTEXITCODE -eq 0) { return }
        if ($attempt -lt 3) { Start-Sleep -Seconds (2 * $attempt) }
    }
    Write-Warning @"
The TEI image could not be pulled. Configure Docker Desktop's container proxy or set an exact
trusted mirror reference in .env:
  PAICLI_RAG_RERANKER_IMAGE=<registry>/huggingface/text-embeddings-inference:cpu-1.9
"@
    throw "TEI reranker image pull failed"
}

function Wait-RerankerReady {
    $endpoint = Get-RerankerEndpoint
    $timeoutSeconds = if ($env:PAICLI_RAG_RERANKER_STARTUP_TIMEOUT_SECONDS) {
        [int]$env:PAICLI_RAG_RERANKER_STARTUP_TIMEOUT_SECONDS
    } else { 900 }
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    Write-Host "Waiting for the model to load at $endpoint (first start downloads model weights)..."
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "$endpoint/health" -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                Write-Host "Local Cross-Encoder reranker is ready: $endpoint"
                return
            }
        } catch {
            Start-Sleep -Seconds 5
        }
        $container = docker inspect -f "{{.State.Status}}" paicli-reranker 2>$null
        if ($LASTEXITCODE -eq 0 -and $container -eq "exited") { break }
    }
    docker compose -f $compose logs --tail 100 reranker
    throw "TEI reranker did not become ready within $timeoutSeconds seconds"
}

function Test-Reranker {
    $endpoint = Get-RerankerEndpoint
    $body = @{
        query = "Milvus 如何存储向量"
        texts = @(
            "Milvus 是面向向量检索的数据库。"
            "Docker Desktop 是容器管理工具。"
            "今天的天气很好。"
        )
        truncate = $true
        raw_scores = $false
        return_text = $false
    } | ConvertTo-Json -Depth 3
    $result = Invoke-RestMethod -Method Post -Uri "$endpoint/rerank" -ContentType "application/json" `
        -Body $body -TimeoutSec 60
    $result | ConvertTo-Json -Depth 5
}

Push-Location $root
try {
    . (Join-Path $PSScriptRoot "load-env.ps1") -Root $root -OverrideExisting
    docker version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Docker Desktop is not available" }

    switch ($Action) {
        "start" {
            Invoke-RerankerImagePull
            docker compose -f $compose up -d --pull never
            if ($LASTEXITCODE -ne 0) { throw "TEI reranker failed to start" }
            Wait-RerankerReady
            Test-Reranker
        }
        "pull" { Invoke-RerankerImagePull }
        "stop" { docker compose -f $compose down }
        "status" { docker compose -f $compose ps }
        "logs" { docker compose -f $compose logs --tail 200 -f reranker }
        "test" { Test-Reranker }
    }
    if ($LASTEXITCODE -ne 0) { throw "TEI reranker Docker command failed" }
} finally {
    Pop-Location
}
