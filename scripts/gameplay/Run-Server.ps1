param([string]$JavaHome='E:\2026-2\lap-trinh-mang\baitap',[switch]$BuildOnly)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
$project=Join-Path $root 'final-project'
$classes=Join-Path $project 'build\gameplay\classes'
if(-not (Test-Path -LiteralPath "$JavaHome\bin\javac.exe")) { throw 'Set -JavaHome to a JDK 17 installation.' }
if(-not (Test-Path -LiteralPath "$project\build\cluster\server-1.0.jar")) { throw 'Prepare dependencies first: from final-project run .\gradlew.bat :server:installCluster.' }
if(-not $BuildOnly) {
    $portLine=Get-Content -LiteralPath "$root\config\gameplay\local.properties" | Where-Object { $_ -match '^\s*tcp\.port\s*=' } | Select-Object -First 1
    $port=if($portLine) { [int](($portLine -split '=',2)[1].Trim()) } else { 9000 }
    $probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port)
    $probe.Server.ExclusiveAddressUse=$true
    try { $probe.Start() }
    catch { throw "TCP $port is already occupied. Stop the existing server with Ctrl+C in its terminal, then run this script again. Starting a new client does not update an existing server." }
    finally { $probe.Stop() }
}
# Compile current production sources on every start; cached classes can belong to an older checkout.
New-Item -ItemType Directory -Force -Path $classes | Out-Null
Push-Location $root
try {
    $sourceList=Join-Path $project 'build\gameplay\server-sources.txt'
    $sourceRoots=@("$project\common\src","$project\server\src")
    $sourcePaths=@(Get-ChildItem -LiteralPath $sourceRoots -File -Recurse -Filter '*.java' | ForEach-Object { '"'+($_.FullName -replace '\\','/')+'"' })
    # Quote paths for javac and write UTF-8 without a BOM; no ripgrep or test framework is needed.
    [IO.File]::WriteAllLines($sourceList,$sourcePaths,[Text.UTF8Encoding]::new($false))
    & "$JavaHome\bin\javac.exe" -J-Xmx128m --release 17 -encoding UTF-8 -cp "$project\build\cluster\*" -d $classes "@$sourceList"
    if($LASTEXITCODE -ne 0) { throw 'Server compilation failed; server was not started.' }
    $revision=if(Get-Command git -ErrorAction SilentlyContinue) { git rev-parse --short HEAD } else { 'working-tree' }
    Write-Host "SERVER_BUILD commit=$revision compiledAt=$(Get-Date -Format o)"
} finally { Pop-Location }
if($BuildOnly) { return }
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
