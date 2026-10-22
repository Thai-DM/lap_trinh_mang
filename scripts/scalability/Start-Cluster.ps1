param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Configuration = "$PSScriptRoot\..\..\config\scalability\local.properties",
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..\final-project").Path
$configurationPath = (Resolve-Path -LiteralPath $Configuration).Path
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw 'Set -JavaHome to a JDK 17 installation.' }
$env:JAVA_HOME = $JavaHome
if (-not $SkipBuild) {
    Push-Location $project
    try { & .\gradlew.bat :server:installCluster --offline --no-daemon; if ($LASTEXITCODE -ne 0) { throw 'Build failed.' } }
    finally { Pop-Location }
}
$run = Join-Path $project 'build\scalability'
New-Item -ItemType Directory -Path $run -Force | Out-Null
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$env:SMASHPROFS_CLUSTER_SECRET = [Convert]::ToBase64String($bytes)
# Refuse to overwrite an active cluster's credential.
$controlPort = 8080
Get-Content -LiteralPath $configurationPath | ForEach-Object { if ($_ -match '^http.port=(\d+)$') { $controlPort = [int]$Matches[1] } }
try { $null = Invoke-RestMethod "http://127.0.0.1:$controlPort/metrics" -TimeoutSec 1; throw 'A cluster is already listening on this port.' }
catch { if ($_.Exception.Message -eq 'A cluster is already listening on this port.') { throw } }
[IO.File]::WriteAllText((Join-Path $run '.cluster-secret'), $env:SMASHPROFS_CLUSTER_SECRET)
$arguments = @('-Xms64m', '-Xmx512m', '-cp', ('"{0}"' -f (Join-Path $project 'build\cluster\*')),
    'com.smashprofs.server.cluster.ClusterMain', 'control', ('"{0}"' -f $configurationPath))
$process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $run 'control.log') -RedirectStandardError (Join-Path $run 'control.err.log')
@{ pid = $process.Id; controlPort = $controlPort; java = $java } | ConvertTo-Json | Set-Content (Join-Path $run 'supervisor.json')
$deadline = [DateTime]::UtcNow.AddSeconds(25)
while ([DateTime]::UtcNow -lt $deadline) {
    $process.Refresh()
    if ($process.HasExited) { throw "Control plane exited; inspect $run\control.err.log and worker logs." }
    try {
        $metrics = Invoke-RestMethod "http://127.0.0.1:$controlPort/metrics" -TimeoutSec 2
        $expected = 3
        Get-Content -LiteralPath $configurationPath | ForEach-Object { if ($_ -match '^workers.initial=(\d+)$') { $expected = [int]$Matches[1] } }
        if (@($metrics.workers).Count -ge $expected) { Write-Output "Cluster ready: http://127.0.0.1:$controlPort/metrics ($expected worker JVMs)"; return }
    } catch { }
    Start-Sleep -Milliseconds 200
}
throw "Readiness timed out; inspect $run. Use Stop-Cluster.ps1 to shut down."
