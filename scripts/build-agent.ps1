# Builds the APK with the agent console bridge: dist/mimohodom-<version>-agent.apk
# Same applicationId and signing key as debug, so it installs over the regular build.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& "$PSScriptRoot/build-local.ps1" -Tasks @('assembleAgent')
$gradle = Get-Content "$projectRoot/app/build.gradle.kts" -Raw
if ($gradle -notmatch 'versionName = "([^"]+)"') { throw 'versionName not found in app/build.gradle.kts' }
$version = $Matches[1]
$built = Get-ChildItem "$projectRoot/app/build/outputs/apk/agent" -Filter *.apk | Select-Object -First 1
if (-not $built) { throw 'agent APK not found' }
New-Item -ItemType Directory -Force "$projectRoot/dist" | Out-Null
$target = "$projectRoot/dist/mimohodom-$version-agent.apk"
Copy-Item $built.FullName $target -Force
Write-Host "Done: $target"
