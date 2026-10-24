param([string]$JavaHome='E:\2026-2\lap-trinh-mang\baitap')
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
$project=Join-Path $root 'final-project'
$evidence=Join-Path $root 'docs\gameplay\runs\20261022-boss-native'
& "$PSScriptRoot\Verify.ps1" -JavaHome $JavaHome -Suites @('com.smashprofs.server.game.BossModeTest') -ClientSuites @('com.smashprofs.server.game.BossTcpIntegrationTest') -Label boss-desktop-prerequisites
$cp="$project\build\gameplay\classes;$project\build\gameplay\tests;$project\desktop\build\libs\desktop-1.0.jar;$project\build\cluster\*"
if(-not (Test-Path -LiteralPath "$project\desktop\build\libs\desktop-1.0.jar")) { throw 'Build the desktop archive first with Run-Client.ps1.' }
& "$JavaHome\bin\javac.exe" -J-Xmx128m --release 17 -encoding UTF-8 -cp $cp -d "$project\build\gameplay\tests" "$project\tests\src\com\smashprofs\server\game\BossDesktopSmoke.java"
if($LASTEXITCODE -ne 0) { throw 'Boss desktop helper compilation failed.' }
$runs=@(
    @{players=2;width=1280;height=720;win='true';folder='two-players'},
    @{players=4;width=960;height=540;win='false';folder='four-players-small'}
)
foreach($run in $runs) {
    $folder=Join-Path $evidence $run.folder
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    Push-Location "$project\assets"
    try {
        $saved=$ErrorActionPreference;$ErrorActionPreference='Continue'
        try {
            & "$JavaHome\bin\java.exe" -Xmx192m -cp $cp com.smashprofs.server.game.BossDesktopSmoke $run.players $run.width $run.height $run.win $folder 2> "$folder\stderr.log" | Tee-Object "$folder\smoke.log"
            $status=$LASTEXITCODE
        } finally { $ErrorActionPreference=$saved }
        if($status -ne 0) { throw "Boss desktop smoke failed: $folder" }
        if(@(Get-ChildItem -LiteralPath $folder -Filter '*.png').Count -ne 8) { throw "Missing boss screenshots: $folder" }
    } finally { Pop-Location }
}
@{date='2026-10-22';status='PASS';kind='Hidden native GL windows, real TCP, controlled server fixtures';runs=$runs;heapMiB=192;screenshots=16} | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 "$evidence\verification.json"
