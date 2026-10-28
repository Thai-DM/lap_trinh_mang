param(
    [string]$JavaHome=$(if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { $env:JAVA_HOME } elseif (Test-Path 'C:\Program Files\Java\jdk-17\bin\java.exe') { 'C:\Program Files\Java\jdk-17' } else { 'E:\2026-2\lap-trinh-mang\baitap' }),
    [ValidateRange(1,65535)][int]$Port=8088,
    [ValidateRange(1,65535)][int]$GatewayPort=8090,
    [switch]$SkipBuild,[switch]$BuildOnly,[switch]$ClientOnly
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
$project=Join-Path $root 'final-project'
$module=Join-Path $project 'web'
$web=Join-Path $module 'build\dist\webapp'
if(-not (Test-Path -LiteralPath "$JavaHome\bin\java.exe")) { throw 'Set -JavaHome to your JDK 17 installation.' }
if(-not $SkipBuild) {
    New-Item -ItemType Directory -Force -Path "$module\build" | Out-Null
    $buildLog=Join-Path $module 'build\build.log'
    $previousJava=$env:JAVA_HOME;$env:JAVA_HOME=$JavaHome
    Push-Location $project
    try {
        $savedPreference=$ErrorActionPreference;$ErrorActionPreference='Continue'
        try {
            if(-not $ClientOnly) {
                & .\gradlew.bat :server:installCluster --no-daemon '-Dorg.gradle.jvmargs=-Xmx256m' 2>&1 | Out-File -Encoding UTF8 $buildLog
                if($LASTEXITCODE -ne 0) { throw "Server build failed: $buildLog" }
            }
            $tasks=@('test','buildWeb');if(-not $ClientOnly) { $tasks+='prepareGateway' }
            & .\gradlew.bat -p web @tasks --no-daemon '-Dorg.gradle.jvmargs=-Xmx256m' 2>&1 | Out-File -Append -Encoding UTF8 $buildLog
            if($LASTEXITCODE -ne 0) { throw "Web build failed: $buildLog" }
        } finally { $ErrorActionPreference=$savedPreference }
        Write-Host "BUILD_SUCCESS $buildLog"
    } finally { Pop-Location;$env:JAVA_HOME=$previousJava }
}
if(-not (Test-Path -LiteralPath "$web\index.html")) { throw 'Web output missing. Run without -SkipBuild first.' }
if($BuildOnly) { Write-Host "WEB_OUTPUT $web";return }
if(-not (Get-Command python -ErrorAction SilentlyContinue)) { throw 'Install Python 3 to serve the client files.' }
$gatewayProcess=$null
try {
    if(-not $ClientOnly) {
        $lib=Join-Path $module 'build\gateway\lib'
        if(-not (Test-Path -LiteralPath "$lib\smashprofs-gateway.jar")) { throw 'Gateway output missing. Run without -SkipBuild first.' }
        $gatewayLog=Join-Path $module 'build\gateway.log'
        $gatewayError=Join-Path $module 'build\gateway.stderr.log'
        $gatewayProcess=Start-Process -FilePath "$JavaHome\bin\java.exe" -ArgumentList @('-Xmx192m','-Dfile.encoding=UTF-8','-cp',('"'+$lib+'\*"'),'com.smashprofs.web.GameGateway',"$GatewayPort") -WorkingDirectory $module -WindowStyle Hidden -PassThru -RedirectStandardOutput $gatewayLog -RedirectStandardError $gatewayError
        $deadline=[DateTime]::UtcNow.AddSeconds(15);$ready=$false
        while(-not $gatewayProcess.HasExited -and [DateTime]::UtcNow -lt $deadline) {
            if((Test-Path -LiteralPath $gatewayLog) -and (Select-String -LiteralPath $gatewayLog -Pattern '^PROBE_GATEWAY_READY ' -Quiet)) { $ready=$true;break }
            Start-Sleep -Milliseconds 100
        }
        if(-not $ready) { Get-Content -LiteralPath $gatewayError -Tail 30;throw "Gateway failed. Check port $GatewayPort and $gatewayLog." }
    }
    $endpoint=[Uri]::EscapeDataString("ws://127.0.0.1:$GatewayPort")
    Write-Host "PLAY http://127.0.0.1:$Port/?ws=$endpoint"
    Write-Host 'Menu -> Online -> Create / Join by room code. Choose PvP or cooperative Boss.'
    Write-Host 'A/D move; Space jump; W/S aim; J shoot; K heavy; L dodge; E pickup; Q throw; F ability; Tab inventory; H help.'
    Write-Host 'Loopback preview. Ctrl+C stops the processes launched by this script.'
    & python -m http.server $Port --bind 127.0.0.1 --directory $web
    if($LASTEXITCODE -ne 0) { throw 'HTTP preview server stopped with an error.' }
} finally {
    if($null -ne $gatewayProcess -and -not $gatewayProcess.HasExited) { $gatewayProcess.Kill();$gatewayProcess.WaitForExit(5000) | Out-Null }
}
