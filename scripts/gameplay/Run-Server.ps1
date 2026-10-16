param([string]$JavaHome='E:\2026-2\lap-trinh-mang\baitap')
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
$project=Join-Path $root 'final-project'
$classes=Join-Path $project 'build\gameplay\classes'
if(-not (Test-Path -LiteralPath "$classes\com\smashprofs\server\game\GameplayMatch.class")) { throw 'Run scripts/gameplay/Verify-Gameplay.ps1 first.' }
if(-not $env:SMASHPROFS_CLUSTER_SECRET) {
    $bytes=New-Object byte[] 32;$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $env:SMASHPROFS_CLUSTER_SECRET=[Convert]::ToBase64String($bytes)
}
Push-Location $project
try {
    & "$JavaHome\bin\java.exe" -Xms32m -Xmx128m -cp "$classes;$project\assets;$project\build\cluster\*" com.smashprofs.server.cluster.ClusterMain standalone "$root\config\gameplay\local.properties"
    if($LASTEXITCODE -ne 0) { throw 'Server exited with failure.' }
} finally { Pop-Location }
