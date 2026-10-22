param([int]$WaitSeconds = 40)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..\final-project").Path
$run = Join-Path $project 'build\scalability'
$manifest = Get-Content -LiteralPath (Join-Path $run 'supervisor.json') -Raw | ConvertFrom-Json
$secret = [IO.File]::ReadAllText((Join-Path $run '.cluster-secret'))
$url = "http://127.0.0.1:$($manifest.controlPort)"
$null = Invoke-RestMethod "$url/admin/shutdown" -Method Post -Body '{}' -ContentType 'application/json' -Headers @{ Authorization = $secret } -TimeoutSec 3
$deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
while ([DateTime]::UtcNow -lt $deadline) {
    try { $null = Invoke-RestMethod "$url/metrics" -TimeoutSec 1 }
    catch { Write-Output 'Control plane stopped; workers drained or ended after the configured shutdown grace period.'; return }
    Start-Sleep -Milliseconds 300
}
throw 'Graceful shutdown is still running. Inspect the control and worker logs.'
