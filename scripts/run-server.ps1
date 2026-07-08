param(
    [ValidateSet("local", "docker")]
    [string]$SandboxMode = "docker"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "load-env.ps1") -Root $root

Push-Location $root
try {
    java -jar .\paicli-server\target\paicli-server-0.6.0-SNAPSHOT.jar `
        "--paicli.sandbox-mode=$SandboxMode"
} finally {
    Pop-Location
}
