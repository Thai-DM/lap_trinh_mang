param(
    [string]$JavaHome='E:\2026-2\lap-trinh-mang\baitap',
    [ValidateRange(1,65535)][int]$Port=8088,
    [ValidateRange(1,65535)][int]$GatewayPort=8090,
    [ValidatePattern('^[A-Za-z0-9_-]{1,64}$')][string]$Room='TEA1',
    [switch]$Multiplayer,
    [switch]$Boss,
    [switch]$SkipBuild,
    [switch]$BuildOnly
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
$project=Join-Path $root 'final-project'
$web=Join-Path $project 'teavm-probe\build\dist\webapp'
if(-not $SkipBuild) {
    if(-not (Test-Path -LiteralPath "$JavaHome\bin\javac.exe")) { throw 'Set -JavaHome to a JDK 17 installation.' }
    New-Item -ItemType Directory -Force -Path "$project\teavm-probe\build" | Out-Null
    $buildLog=Join-Path $project 'teavm-probe\build\build.log'
    $previousJava=$env:JAVA_HOME
    $env:JAVA_HOME=$JavaHome
    Push-Location $project
    try {
        $savedPreference=$ErrorActionPreference
        $ErrorActionPreference='Continue'
        try {
            if($Multiplayer) {
                & .\gradlew.bat :server:installCluster --offline --no-daemon '-Dorg.gradle.jvmargs=-Xmx256m' 2>&1 | Out-File -Encoding UTF8 $buildLog
                if($LASTEXITCODE -ne 0) { throw "Server build failed: $buildLog" }
            }
            $taskNames=@('buildWeb')
            if($Multiplayer) { $taskNames+= 'prepareGateway' }
            & .\gradlew.bat -p teavm-probe @taskNames --no-daemon '-Dorg.gradle.jvmargs=-Xmx256m' 2>&1 | Out-File -Append -Encoding UTF8 $buildLog
            $buildExit=$LASTEXITCODE
        } finally { $ErrorActionPreference=$savedPreference }
        if($buildExit -ne 0) { Get-Content -LiteralPath $buildLog -Tail 60;throw "TeaVM build failed: $buildLog" }
        Write-Host "BUILD_SUCCESS $buildLog"
    } finally { Pop-Location;$env:JAVA_HOME=$previousJava }
}
if(-not (Test-Path -LiteralPath "$web\index.html")) { throw 'TeaVM output is missing. Run without -SkipBuild first.' }
if($BuildOnly) { Write-Host "WEB_OUTPUT $web";return }
if(-not (Get-Command python -ErrorAction SilentlyContinue)) { throw 'Install Python 3 to serve the web files, or use another static HTTP server.' }
$gatewayProcess=$null
try {
    if($Multiplayer) {
        if(-not (Test-Path -LiteralPath "$JavaHome\bin\java.exe")) { throw 'Set -JavaHome to a JDK 17 installation.' }
        $gatewayLib=Join-Path $project 'teavm-probe\build\gateway\lib'
        if(-not (Test-Path -LiteralPath "$gatewayLib\probe-gateway.jar")) { throw 'Gateway output missing. Run without -SkipBuild first.' }
        $gatewayLog=Join-Path $project 'teavm-probe\build\gateway.log'
        $gatewayError=Join-Path $project 'teavm-probe\build\gateway.stderr.log'
        $gatewayProcess=Start-Process -FilePath "$JavaHome\bin\java.exe" -ArgumentList @('-Xmx128m','-cp',('"'+$gatewayLib+'\*"'),'com.smashprofs.probe.ProbeGateway',"$GatewayPort") -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput $gatewayLog -RedirectStandardError $gatewayError
        $deadline=[DateTime]::UtcNow.AddSeconds(12)
        $ready=$false
        while(-not $gatewayProcess.HasExited -and [DateTime]::UtcNow -lt $deadline) {
            if((Test-Path -LiteralPath $gatewayLog) -and (Select-String -LiteralPath $gatewayLog -Pattern '^PROBE_GATEWAY_READY ' -Quiet)) { $ready=$true;break }
            Start-Sleep -Milliseconds 100
        }
        if(-not $ready) { Get-Content -LiteralPath $gatewayError -Tail 30;throw "Gateway did not start. Check $gatewayLog and ports $Port/$GatewayPort." }
        $endpoint=[Uri]::EscapeDataString("ws://127.0.0.1:$GatewayPort")
        $bossValue=if($Boss) { 'true' }else { 'false' }
        Write-Host "CREATE http://127.0.0.1:$Port/?mode=create&room=$Room&boss=$bossValue&ws=$endpoint"
        Write-Host "JOIN   http://127.0.0.1:$Port/?mode=join&room=$Room&ws=$endpoint"
        Write-Host 'Open CREATE once, then JOIN in another tab. Both auto-ready after loading.'
        Write-Host 'A/D move; W jump; J shoot; I/S aim; L dodge; E pickup; Q throw; F ability.'
    } else {
        Write-Host "TeaVM trial: http://127.0.0.1:$Port/"
        Write-Host 'A/D move; W jump; J animation; 1-5 character; B boss pose; F sound; R reset.'
        Write-Host 'Local asset/control fixture. Use -Multiplayer -Boss for the real Java server.'
    }
    Write-Host 'Loopback trial only. Stop with Ctrl+C.'
    & python -m http.server $Port --bind 127.0.0.1 --directory $web
    if($LASTEXITCODE -ne 0) { throw 'Local web server stopped with an error.' }
} finally {
    if($null -ne $gatewayProcess -and -not $gatewayProcess.HasExited) { $gatewayProcess.Kill();$gatewayProcess.WaitForExit(5000) | Out-Null }
}
