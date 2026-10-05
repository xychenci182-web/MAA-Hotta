param(
    [Parameter(Mandatory = $true)][string]$ApkUrl,
    [string]$Notes = "Bug fixes and task flow improvements",
    [string]$OutputDirectory = "dist/update",
    [string]$ApkDirectory = "app/build/outputs/apk/debug"
)
$ErrorActionPreference = 'Stop'
$downloadUri = [Uri]$ApkUrl
if (-not $downloadUri.IsAbsoluteUri -or $downloadUri.Scheme -ne 'https') {
    throw 'ApkUrl must be an absolute HTTPS URL.'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceDirectory = if ([IO.Path]::IsPathFullyQualified($ApkDirectory)) { $ApkDirectory } else { Join-Path $projectRoot $ApkDirectory }
$metadata = Get-Content -LiteralPath (Join-Path $sourceDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
$build = $metadata.elements[0]
$apk = Join-Path $sourceDirectory $build.outputFile
$destination = if ([IO.Path]::IsPathFullyQualified($OutputDirectory)) { $OutputDirectory } else { Join-Path $projectRoot $OutputDirectory }
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$publishedApk = Join-Path $destination 'mah.apk'
Copy-Item -LiteralPath $apk -Destination $publishedApk -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$publishedZip = [System.IO.Compression.ZipFile]::OpenRead($publishedApk)
try {
    $bundledAccounts = $publishedZip.Entries | Where-Object { ($_.FullName -replace '\\','/') -eq 'assets/default_accounts.txt' }
    if ($bundledAccounts) {
        throw "Refusing to publish $publishedApk because it contains assets/default_accounts.txt"
    }
} finally {
    $publishedZip.Dispose()
}
$manifest = [ordered]@{
    versionCode = $build.versionCode
    versionName = $build.versionName
    apkUrl = $ApkUrl
    sha256 = (Get-FileHash -LiteralPath (Join-Path $destination 'mah.apk') -Algorithm SHA256).Hash.ToLowerInvariant()
    notes = $Notes
}
$jsonPath = Join-Path $destination 'update.json'
[IO.File]::WriteAllText($jsonPath, ($manifest | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Output "Prepared: $destination (upload mah.apk and update.json to your HTTPS server)"
