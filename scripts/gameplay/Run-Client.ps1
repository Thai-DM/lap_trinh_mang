param([string]$JavaHome='E:\2026-2\lap-trinh-mang\baitap',[switch]$SkipBuild,
      [string]$ServerHost='', [ValidateRange(1,65535)][int]$ServerPort=54555)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
$project=Join-Path $root 'final-project'
if(-not (Test-Path -LiteralPath "$JavaHome\bin\java.exe")) { throw 'Set -JavaHome to a JDK 17 installation.' }
$previousJava=$env:JAVA_HOME
$env:JAVA_HOME=$JavaHome
Push-Location $project
try {
    # Gradle exits before the game opens, so a second client does not wait on a running Gradle task.
    if(-not $SkipBuild) {
        $savedPreference=$ErrorActionPreference;$ErrorActionPreference='Continue'
        try { & .\gradlew.bat :desktop:dist --offline --no-daemon '-Dorg.gradle.jvmargs=-Xmx256m';$buildExit=$LASTEXITCODE }
        finally { $ErrorActionPreference=$savedPreference }
        if($buildExit -ne 0) { throw 'Client build failed. Check the JDK and cached Gradle dependencies.' }
    }
    $jar=Join-Path $project 'desktop\build\libs\desktop-1.0.jar'
    if(-not (Test-Path -LiteralPath $jar)) { throw "Client archive not found: $jar" }
    Set-Location (Join-Path $project 'assets')
    $connectionOptions=@()
    if(-not [string]::IsNullOrWhiteSpace($ServerHost)) {
        $connectionOptions=@("-Dsmashprofs.server.host=$($ServerHost.Trim())","-Dsmashprofs.server.port=$ServerPort")
    }
    & "$JavaHome\bin\java.exe" -Xms32m -Xmx192m @connectionOptions -cp $jar com.smashprofs.game.DesktopLauncher --online
    if($LASTEXITCODE -ne 0) { throw 'Game exited with an error.' }
} finally { Pop-Location;$env:JAVA_HOME=$previousJava }
