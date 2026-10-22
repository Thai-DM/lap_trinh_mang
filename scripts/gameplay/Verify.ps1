param(
    [string]$JavaHome='E:\2026-2\lap-trinh-mang\baitap',
    [string[]]$Suites=@('com.smashprofs.server.room.HostRulesTest'),
    [string[]]$ClientSuites=@(),
    [string]$Label='targeted'
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
$project=Join-Path $root 'final-project'
$build=Join-Path $project 'build\gameplay'
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$evidence=Join-Path $root "docs\gameplay\runs\$stamp-$Label"
New-Item -ItemType Directory -Force -Path "$build\classes","$build\tests",$evidence | Out-Null
$cache=Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$jars=@('junit\junit\4.13.1','org.hamcrest\hamcrest-core','org.apache.logging.log4j\log4j-api\2.17.2','org.apache.logging.log4j\log4j-core\2.17.2','log4j\log4j\1.2.17') |
    ForEach-Object { @(Get-ChildItem -LiteralPath (Join-Path $cache $_) -Filter '*.jar' -Recurse)[0].FullName }
if($ClientSuites.Count -gt 0) {
    $jars+=@('com.badlogicgames.gdx-controllers\gdx-controllers-core\2.2.1','com.crashinvaders.vfx\gdx-vfx-core\0.5.0','com.crashinvaders.vfx\gdx-vfx-effects\0.5.0','com.badlogicgames.gdx\gdx-backend-lwjgl3\1.10.0') |
        ForEach-Object { @(Get-ChildItem -LiteralPath (Join-Path $cache $_) -Filter '*.jar' -Recurse)[0].FullName }
}
$cp="$build\classes;$project\assets;$project\build\cluster\*;"+($jars -join ';')
Push-Location $root
try {
    @(rg --files final-project/common/src final-project/server/src -g '*.java') | Set-Content -Encoding ascii "$build\sources.txt"
    & "$JavaHome\bin\javac.exe" -J-Xmx128m --release 17 -encoding UTF-8 -cp $cp -d "$build\classes" "@$build\sources.txt"
    if($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    @(rg --files final-project/server/test -g '*.java') | Set-Content -Encoding ascii "$build\test-sources.txt"
    & "$JavaHome\bin\javac.exe" -J-Xmx128m --release 17 -encoding UTF-8 -cp "$build\tests;$cp" -d "$build\tests" "@$build\test-sources.txt"
    if($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    if($ClientSuites.Count -gt 0) {
        @(rg --files final-project/core/src -g '*.java') | Set-Content -Encoding ascii "$build\client-sources.txt"
        & "$JavaHome\bin\javac.exe" -J-Xmx128m --release 17 -encoding UTF-8 -cp $cp -d "$build\classes" "@$build\client-sources.txt"
        if($LASTEXITCODE -ne 0) { throw 'Client compilation failed' }
        @(rg --files final-project/desktop/src -g '*.java') | Set-Content -Encoding ascii "$build\desktop-sources.txt"
        & "$JavaHome\bin\javac.exe" -J-Xmx128m --release 17 -encoding UTF-8 -cp $cp -d "$build\classes" "@$build\desktop-sources.txt"
        if($LASTEXITCODE -ne 0) { throw 'Desktop compilation failed' }
        @($ClientSuites | ForEach-Object { 'final-project/tests/src/'+($_ -replace '\.','/')+'.java' }) | Set-Content -Encoding ascii "$build\client-test-sources.txt"
        & "$JavaHome\bin\javac.exe" -J-Xmx128m --release 17 -encoding UTF-8 -cp "$build\tests;$cp" -d "$build\tests" "@$build\client-test-sources.txt"
        if($LASTEXITCODE -ne 0) { throw 'Client test compilation failed' }
    }
    $saved=$ErrorActionPreference; $ErrorActionPreference='Continue'
    try {
        & "$JavaHome\bin\java.exe" -Xmx128m -cp "$build\tests;$cp" org.junit.runner.JUnitCore @Suites @ClientSuites 2> "$evidence\stderr.log" | Tee-Object "$evidence\tests.log"
        $status=$LASTEXITCODE
    } finally { $ErrorActionPreference=$saved }
    if($status -ne 0) { throw "Tests failed: $evidence" }
    @{date=(Get-Date -Format o);label=$Label;suites=@($Suites)+@($ClientSuites);status='PASS';heapMiB=128} | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 "$evidence\verification.json"
} finally { Pop-Location }
