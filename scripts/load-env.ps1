param([Parameter(Mandatory = $true)][string]$Root)

$envFile = Join-Path $Root ".env"
if (-not (Test-Path -LiteralPath $envFile)) { return }

foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
    $value = $line.Trim()
    if (-not $value -or $value.StartsWith("#")) { continue }
    $parts = $value.Split("=", 2)
    if ($parts.Count -ne 2) { continue }
    $name = $parts[0].Trim()
    $setting = $parts[1].Trim()
    if ($setting.Length -ge 2 -and
        (($setting.StartsWith('"') -and $setting.EndsWith('"')) -or
         ($setting.StartsWith("'") -and $setting.EndsWith("'")))) {
        $setting = $setting.Substring(1, $setting.Length - 2)
    }
    if ($name -match '^[A-Za-z_][A-Za-z0-9_]*$' -and
        -not [Environment]::GetEnvironmentVariable($name, 'Process')) {
        [Environment]::SetEnvironmentVariable($name, $setting, 'Process')
    }
}
