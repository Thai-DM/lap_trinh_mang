param([string]$JavaHome='E:\2026-2\lap-trinh-mang\baitap')
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
$project=Join-Path $root 'final-project'
if(-not (Test-Path -LiteralPath "$JavaHome\bin\java.exe")) { throw 'Set -JavaHome to a JDK 17 installation.' }
$previousJava=$env:JAVA_HOME
$env:JAVA_HOME=$JavaHome
Push-Location $project
try {
    $savedPreference=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    try { & .\gradlew.bat :server:installCluster --offline --no-daemon '-Dorg.gradle.jvmargs=-Xmx256m';$buildExit=$LASTEXITCODE }
    finally { $ErrorActionPreference=$savedPreference }
    if($buildExit -ne 0) { throw 'Server build failed; no deployment archive was created.' }
} finally { Pop-Location;$env:JAVA_HOME=$previousJava }
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$deployment=Join-Path $project "build\deploy\smashprofs-server-$stamp"
New-Item -ItemType Directory -Path "$deployment\lib","$deployment\config" -Force | Out-Null
Copy-Item -Path "$project\build\cluster\*.jar" -Destination "$deployment\lib"
Copy-Item -LiteralPath "$root\config\gameplay\public.properties" -Destination "$deployment\config\public.properties"
Copy-Item -LiteralPath "$PSScriptRoot\smashprofs.service" -Destination $deployment
Copy-Item -LiteralPath "$root\docs\gameplay\16-AZURE-DEPLOY.md" -Destination "$deployment\DEPLOY.md"
# Write Linux launcher without BOM/CRLF, even on a Windows checkout with autocrlf enabled.
$launcher=[IO.File]::ReadAllText("$PSScriptRoot\start-server.sh").Replace("`r`n","`n")
[IO.File]::WriteAllText("$deployment\start-server.sh",$launcher,[Text.UTF8Encoding]::new($false))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive="$deployment.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($deployment,$archive)
Write-Host "SERVER_ARCHIVE $archive"
Write-Host "SHA256 $((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash)"
