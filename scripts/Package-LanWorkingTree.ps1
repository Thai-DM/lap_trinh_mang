param()
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outputDirectory = Join-Path $workspaceRoot 'final-project/build/lan52g'
[void][System.IO.Directory]::CreateDirectory($outputDirectory)
$archivePath = Join-Path $outputDirectory ('smashprofs-52g-working-tree-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssfff') + '.zip')
$excludedDirectories = @('.git', '.gradle', 'build', 'bin', 'out', 'dist', '.idea', '.settings', '.metadata', 'logs')
function Get-PackageFiles([string]$directory) {
    foreach ($item in Get-ChildItem -LiteralPath $directory -Force) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
        if ($item.PSIsContainer) {
            if ($item.Name -notin $excludedDirectories) { Get-PackageFiles $item.FullName }
        } elseif ($item.Extension -notin @('.log', '.tmp', '.class') -and $item.Name -notin @('local.properties', '.env')) {
            $item
        }
    }
}
# Read the filesystem, not git archive: includes untracked sources and unstaged modifications.
$packageFiles = @(Get-PackageFiles (Join-Path $workspaceRoot 'final-project'))
$packageFiles += @(Get-PackageFiles (Join-Path $workspaceRoot 'docs'))
$packageFiles += @(Get-PackageFiles $PSScriptRoot)
$manifestFiles = [Collections.Generic.List[object]]::new()
$archiveStream = [IO.File]::Open($archivePath, [IO.FileMode]::CreateNew)
$archive = [IO.Compression.ZipArchive]::new($archiveStream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in ($packageFiles | Sort-Object FullName)) {
        $relativePath = $file.FullName.Substring($workspaceRoot.Length + 1).Replace('\', '/')
        $digest = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $relativePath, [IO.Compression.CompressionLevel]::Optimal)
        $manifestFiles.Add([ordered]@{ path=$relativePath; bytes=$file.Length; sha256=$digest })
    }
    $manifest = [ordered]@{ protocolVersion=1; createdUtc=[DateTime]::UtcNow.ToString('o'); files=$manifestFiles }
    $entry = $archive.CreateEntry('LAN-PACKAGE-MANIFEST.json')
    $writer = [IO.StreamWriter]::new($entry.Open(), [Text.UTF8Encoding]::new($false))
    try { $writer.Write(($manifest | ConvertTo-Json -Depth 5)) } finally { $writer.Dispose() }
} finally { $archive.Dispose(); $archiveStream.Dispose() }
$archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
Set-Content -LiteralPath ($archivePath + '.sha256.txt') -Value ($archiveHash + '  ' + [IO.Path]::GetFileName($archivePath)) -Encoding ASCII
[pscustomobject]@{ archive=$archivePath; fileCount=$manifestFiles.Count; bytes=(Get-Item -LiteralPath $archivePath).Length; sha256=$archiveHash } | ConvertTo-Json
