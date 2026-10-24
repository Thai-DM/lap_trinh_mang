param([Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$project = Join-Path $root 'final-project'
$verify = Join-Path $project 'build\scalability\verification'
$cache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$junit = @(Get-ChildItem -LiteralPath (Join-Path $cache 'caches\modules-2\files-2.1\junit\junit\4.13.1') -Filter '*.jar' -Recurse)[0].FullName
$hamcrest = @(Get-ChildItem -LiteralPath (Join-Path $cache 'caches\modules-2\files-2.1\org.hamcrest\hamcrest-core') -Filter '*.jar' -Recurse)[0].FullName
if (-not (Test-Path -LiteralPath "$project\build\cluster\server-1.0.jar")) { throw 'Run :server:installCluster first to prepare dependency JARs.' }
New-Item -ItemType Directory -Force -Path "$verify\classes","$verify\test-classes" | Out-Null
Push-Location $root
try {
    @(rg --files final-project/server/src -g '*.java') | Set-Content -Encoding ascii "$verify\server-sources.txt"
    & "$JavaHome\bin\javac.exe" -J-Xmx128m --release 17 -encoding UTF-8 -cp "$project\build\cluster\*" -d "$verify\classes" "@$verify\server-sources.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Source compilation failed.' }
    @(rg --files final-project/server/test -g '*.java') | Set-Content -Encoding ascii "$verify\test-sources.txt"
    $classpath = "$verify\classes;$project\build\cluster\*;$junit;$hamcrest"
    & "$JavaHome\bin\javac.exe" -J-Xmx128m --release 17 -encoding UTF-8 -cp $classpath -d "$verify\test-classes" "@$verify\test-sources.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    $suites = @(
        'com.smashprofs.server.cluster.ControlPlaneTest',
        'com.smashprofs.server.cluster.WorkerMetricsFreshnessTest',
        'com.smashprofs.server.cluster.HttpJsonTest',
        'com.smashprofs.server.cluster.PerformanceMetricsTest',
        'com.smashprofs.server.cluster.LoadWorkloadTest',
        'com.smashprofs.server.network.SharedSnapshotCacheTest',
        'com.smashprofs.server.network.TokenBucketTest',
        'com.smashprofs.server.network.HeartbeatManagerTest',
        'com.smashprofs.server.game.MatchRuntimeTest',
        'com.smashprofs.server.cluster.ScalingPoliciesTest',
        'com.smashprofs.server.cluster.FileMatchHistoryTest',
        'com.smashprofs.server.cluster.MatchmakingRoomGateTest',
        'com.smashprofs.server.room.GameRoomTest',
        'com.smashprofs.server.room.GameRoomLifecycleTest',
        'com.smashprofs.server.room.GameRoomCountdownTest',
        'com.smashprofs.server.room.RoomManagerCreateTest',
        'com.smashprofs.server.network.TcpProtocolV1Test',
        'com.smashprofs.server.network.TcpSnapshotBackpressureTest',
        'com.smashprofs.server.network.StateBroadcasterTest',
        'com.smashprofs.server.game.FixedTickSchedulerTest',
        'com.smashprofs.server.game.FourClientTcpMatchEndToEndTest',
        'com.smashprofs.server.game.RankingGatewayTcpIntegrationTest',
        'com.smashprofs.server.game.FileHistoryTcpIntegrationTest',
        'com.smashprofs.server.game.RepeatedRoomResourceTest',
        'com.smashprofs.server.game.GameEndTcpDeliveryTest'
    )
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue' # Expected lifecycle-failure fixtures log to native stderr.
    try {
        & "$JavaHome\bin\java.exe" -Xmx128m -cp "$verify\test-classes;$classpath" org.junit.runner.JUnitCore @suites `
            2> "$verify\final.stderr.log" | Tee-Object -FilePath "$verify\final.log"
        $testExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($testExit -ne 0) { throw "Focused tests failed; inspect $verify." }
    & "$JavaHome\bin\jar.exe" --update --file "$project\build\cluster\server-1.0.jar" -C "$verify\classes" .
    if ($LASTEXITCODE -ne 0) { throw 'Runtime packaging failed.' }
} finally { Pop-Location }
