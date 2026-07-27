param(
    [Parameter(Mandatory = $true)][string]$Root,
    [switch]$OverrideExisting
)

function Repair-ProcessEnvironmentCaseDuplicates {
    $entries = @([Environment]::GetEnvironmentVariables('Process').GetEnumerator())
    $groups = $entries | Group-Object { ([string]$_.Key).ToUpperInvariant() }
    foreach ($group in $groups) {
        if ($group.Count -lt 2) { continue }

        $canonicalName = if ($group.Name -eq 'PATH') { 'Path' } else { $group.Name }
        $preferred = $group.Group |
            Where-Object { [string]$_.Key -ceq $canonicalName } |
            Select-Object -First 1
        if (-not $preferred) { $preferred = $group.Group | Select-Object -First 1 }
        $preservedValue = [string]$preferred.Value

        foreach ($entry in $group.Group) {
            [Environment]::SetEnvironmentVariable([string]$entry.Key, $null, 'Process')
        }
        [Environment]::SetEnvironmentVariable($canonicalName, $preservedValue, 'Process')
    }
}

# Windows treats environment names case-insensitively, while some launchers inject
# both upper- and lower-case proxy/PATH variants. Start-Process rejects that block.
Repair-ProcessEnvironmentCaseDuplicates

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
        ($OverrideExisting -or -not [Environment]::GetEnvironmentVariable($name, 'Process'))) {
        [Environment]::SetEnvironmentVariable($name, $setting, 'Process')
    }
}
