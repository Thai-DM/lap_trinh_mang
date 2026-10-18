param([string]$JavaHome='E:\2026-2\lap-trinh-mang\baitap')
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
& "$PSScriptRoot\Verify.ps1" -JavaHome $JavaHome -Suites @('com.smashprofs.server.game.UnarmedGameplayTest') -Label unarmed-training-check
if($LASTEXITCODE -ne 0) { throw 'Unarmed verification failed' }
$project=Join-Path $root 'final-project'
$build=Join-Path $project 'build\gameplay'
$evidence=Join-Path $root ('docs\gameplay\runs\'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')+'-unarmed-training')
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$cp="$build\tests;$build\classes;$project\assets;$project\build\cluster\*"
Push-Location $root
try {
    $savedPreference=$ErrorActionPreference;$ErrorActionPreference='Continue'
    try { & "$JavaHome\bin\java.exe" -Xmx128m -cp $cp com.smashprofs.server.game.UnarmedComboTraining "$evidence\combos.json" *> "$evidence\training.log";$status=$LASTEXITCODE }
    finally { $ErrorActionPreference=$savedPreference }
    if($status -ne 0) { Get-Content "$evidence\training.log" -Tail 20;throw 'Unarmed training failed' }
    Get-Content "$evidence\training.log" -Tail 1
} finally { Pop-Location }
