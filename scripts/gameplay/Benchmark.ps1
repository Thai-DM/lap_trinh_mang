param([string]$JavaHome='E:\2026-2\lap-trinh-mang\baitap',[int]$Seconds=8)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
$project=Join-Path $root 'final-project'
$classes=Join-Path $project 'build\gameplay\classes'
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$runtime=Join-Path $project "build\gameplay\runtimes\$stamp"
if(-not (Test-Path -LiteralPath "$classes\com\smashprofs\server\game\GameplayMatch.class")) { throw 'Run scripts/gameplay/Verify.ps1 first.' }
$output=Join-Path $root "docs\gameplay\runs\$stamp-performance"
New-Item -ItemType Directory -Path $output | Out-Null
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
Get-ChildItem -LiteralPath "$project\build\cluster" -Filter '*.jar' | Where-Object { $_.Name -notin @('server-1.0.jar','common-1.0.jar') } | Copy-Item -Destination $runtime
& "$JavaHome\bin\jar.exe" --create --file "$runtime\common-1.0.jar" -C $classes com/smashprofs/common
if($LASTEXITCODE -ne 0) { throw 'Common packaging failed' }
& "$JavaHome\bin\jar.exe" --create --file "$runtime\server-1.0.jar" -C $classes com/smashprofs/server -C "$project\assets" 1/Map1New2Remake.tmx
if($LASTEXITCODE -ne 0) { throw 'Server packaging failed' }
Push-Location $root
try {
    foreach($items in @('NONE','HIGH')) {
        $case=Join-Path $output $items.ToLowerInvariant()
        python -B scripts/performance/run.py --java-home $JavaHome --runtime $runtime --output $case --gameplay --items $items --clients 4 --profiles COMBAT --variants final --workers 1 --warmup 5 --seconds $Seconds --repeats 1
        if($LASTEXITCODE -ne 0) { throw 'Benchmark runner failed' }
        $stops=@(Get-ChildItem -LiteralPath $case -Recurse -File | Where-Object { $_.Name -in @('safety-stop.json','failure.json') })
        if($stops.Count -gt 0) { Write-Output 'Stopped safely; no further workload starts.';break }
    }
    Write-Output "Evidence: $output"
} finally { Pop-Location }
