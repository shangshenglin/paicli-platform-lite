param(
    [ValidateSet("start", "pull", "stop", "status", "logs")]
    [string]$Action = "start"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $root "deploy/milvus/docker-compose.yml"

function Invoke-MilvusImagePull {
    $imageSpecs = @(
        [pscustomobject]@{
            Name = "etcd"
            Variable = "PAICLI_MILVUS_ETCD_IMAGE"
            Default = "quay.io/coreos/etcd:v3.5.18"
        },
        [pscustomobject]@{
            Name = "MinIO"
            Variable = "PAICLI_MILVUS_MINIO_IMAGE"
            Default = "quay.io/minio/minio:RELEASE.2024-12-18T13-15-44Z"
        },
        [pscustomobject]@{
            Name = "Milvus"
            Variable = "PAICLI_MILVUS_IMAGE"
            Default = "milvusdb/milvus:v2.6.22"
        }
    )

    foreach ($spec in $imageSpecs) {
        $configured = [Environment]::GetEnvironmentVariable($spec.Variable, "Process")
        $image = if ($configured) { $configured } else { $spec.Default }
        $pulled = $false
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            Write-Host "Pulling $($spec.Name) image (attempt $attempt/3): $image"
            docker pull $image
            if ($LASTEXITCODE -eq 0) {
                $pulled = $true
                break
            }
            if ($attempt -lt 3) { Start-Sleep -Seconds (2 * $attempt) }
        }
        if ($pulled) { continue }

        Write-Warning @"
Milvus images could not be pulled. This is a registry network problem, not a Milvus startup problem.

Failed image: $image

For Docker Desktop, configure Settings > Resources > Proxies > Containers proxy, then restart Docker Desktop.
If your organization provides a trusted registry mirror, set exact mirrored image references in .env:
  PAICLI_MILVUS_IMAGE=<registry>/milvusdb/milvus:v2.6.22
  PAICLI_MILVUS_MINIO_IMAGE=<registry>/minio/minio:RELEASE.2024-12-18T13-15-44Z
  PAICLI_MILVUS_ETCD_IMAGE=<registry>/coreos/etcd:v3.5.18

Run '.\scripts\milvus.ps1 pull' to verify registry access before starting services.
"@
        throw "$($spec.Name) image pull failed"
    }
}

Push-Location $root
try {
    . (Join-Path $PSScriptRoot "load-env.ps1") -Root $root -OverrideExisting
    docker version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Docker Desktop is not available" }

    switch ($Action) {
        "start" {
            Invoke-MilvusImagePull
            docker compose -f $compose up -d --wait --pull never
            if ($LASTEXITCODE -ne 0) { throw "Milvus failed to start" }
            $apiPort = if ($env:PAICLI_MILVUS_PORT) { $env:PAICLI_MILVUS_PORT } else { "19530" }
            $webUiPort = if ($env:PAICLI_MILVUS_WEBUI_PORT) { $env:PAICLI_MILVUS_WEBUI_PORT } else { "9091" }
            Write-Host "Milvus REST/gRPC: http://127.0.0.1:$apiPort"
            Write-Host "Milvus WebUI: http://127.0.0.1:$webUiPort/webui/"
        }
        "pull" { Invoke-MilvusImagePull }
        "stop" { docker compose -f $compose down }
        "status" { docker compose -f $compose ps }
        "logs" { docker compose -f $compose logs --tail 200 -f standalone }
    }
    if ($LASTEXITCODE -ne 0) { throw "Milvus Docker command failed" }
} finally {
    Pop-Location
}
