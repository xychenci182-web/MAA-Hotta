param(
    [Parameter(Mandatory = $true)][string]$ApkUrl,
    [string]$Notes = "Bug fixes and task flow improvements",
    [string]$OutputDirectory = "dist/update"
)
$ErrorActionPreference = 'Stop'
$downloadUri = [Uri]$ApkUrl
if (-not $downloadUri.IsAbsoluteUri -or $downloadUri.Scheme -ne 'https') {
    throw 'ApkUrl must be an absolute HTTPS URL.'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$apkDirectory = Join-Path $projectRoot 'app/build/outputs/apk/debug'
$metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
$build = $metadata.elements[0]
$apk = Join-Path $apkDirectory $build.outputFile
$destination = Join-Path $projectRoot $OutputDirectory
New-Item -ItemType Directory -Path $destination -Force | Out-Null
Copy-Item -LiteralPath $apk -Destination (Join-Path $destination 'mah.apk') -Force
$manifest = [ordered]@{
    versionCode = $build.versionCode
    versionName = $build.versionName
    apkUrl = $ApkUrl
    sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    notes = $Notes
}
$jsonPath = Join-Path $destination 'update.json'
[IO.File]::WriteAllText($jsonPath, ($manifest | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Output "Prepared: $destination (upload mah.apk and update.json to your HTTPS server)"
