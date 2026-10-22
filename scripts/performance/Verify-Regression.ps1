param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$project = Join-Path $root 'final-project'
$verify = Join-Path $project 'build\scalability\verification'
$evidence = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $evidence) { throw 'Choose a new evidence directory; existing results are preserved.' }
New-Item -ItemType Directory -Path $evidence | Out-Null
& "$root\scripts\scalability\Verify-LowMemory.ps1" -JavaHome $JavaHome
Copy-Item -LiteralPath "$verify\final.log","$verify\final.stderr.log" -Destination $evidence
$cache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$dependencies = Join-Path $cache 'caches\modules-2\files-2.1'
$extra = @('junit\junit\4.13.1','org.hamcrest\hamcrest-core','org.apache.logging.log4j\log4j-api\2.17.2',
    'org.apache.logging.log4j\log4j-core\2.17.2','com.badlogicgames.gdx\gdx-backend-headless\1.11.0') |
    ForEach-Object { @(Get-ChildItem -LiteralPath (Join-Path $dependencies $_) -Filter '*.jar' -Recurse)[0].FullName }
$classpath = "$verify\classes;$project\server\build\resources\main;$project\assets;$project\build\cluster\*;$project\core\build\libs\core-1.0.jar;" + ($extra -join ';')
Push-Location $root
try {
    $suites = @(rg --files final-project/server/test -g '*Test.java' |
        Where-Object { $_ -notmatch 'ClusterProcessIntegrationTest' } |
        ForEach-Object { $_ -replace '^final-project/server/test[\\/]','' -replace '[\\/]','.' -replace '\.java$','' })
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & "$JavaHome\bin\java.exe" -Xmx128m -cp "$verify\test-classes;$classpath" org.junit.runner.JUnitCore @suites `
            2> "$evidence\server.stderr.log" | Tee-Object -FilePath "$evidence\server.log"
        $serverExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($serverExit -ne 0) { throw 'Server regression failed; inspect preserved logs.' }
    New-Item -ItemType Directory -Force -Path "$verify\client-test-classes" | Out-Null
    $clientSuites = @('com.smashprofs.tests.network.ClusterLobbyIntegrationTest',
        'com.smashprofs.tests.network.LobbyClientTcpIntegrationTest','com.smashprofs.tests.network.NetworkClientProtocolV1Test')
    @($clientSuites | ForEach-Object { 'final-project/tests/src/' + ($_ -replace '\.','/') + '.java' }) |
        Set-Content -Encoding ascii "$verify\client-test-sources.txt"
    & "$JavaHome\bin\javac.exe" -J-Xmx128m --release 17 -encoding UTF-8 -cp $classpath -d "$verify\client-test-classes" "@$verify\client-test-sources.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Client test compilation failed.' }
    $ErrorActionPreference = 'Continue'
    try {
        & "$JavaHome\bin\java.exe" -Xmx128m -cp "$verify\client-test-classes;$classpath" org.junit.runner.JUnitCore @clientSuites `
            2> "$evidence\client.stderr.log" | Tee-Object -FilePath "$evidence\client.log"
        $clientExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($clientExit -ne 0) { throw 'Client interoperability failed.' }
    $hashes = @{}
    foreach ($source in @(rg --files final-project/server/src -g '*.java')) {
        $hashes[$source] = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    }
    @{
        date=(Get-Date -Format o); javaHome=$JavaHome; status='PASS'; heapMiB=128;
        serverLog='server.log'; clientLog='client.log'; sourceSha256=$hashes;
        runtimeJarSha256=(Get-FileHash -LiteralPath "$project\build\cluster\server-1.0.jar" -Algorithm SHA256).Hash;
        excludedSuite='ClusterProcessIntegrationTest: three worker JVMs not rerun after benchmark memory stop';
        coreJarSha256=(Get-FileHash -LiteralPath "$project\core\build\libs\core-1.0.jar" -Algorithm SHA256).Hash
    } | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 "$evidence\verification.json"
} finally { Pop-Location }
