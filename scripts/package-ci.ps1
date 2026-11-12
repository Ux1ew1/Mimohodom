$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$metadata = Get-Content (Join-Path $projectRoot 'app/build/outputs/apk/debug/output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -notin @('app.fastnotes', 'app.fastnotes.aicore') -or $metadata.elements.Count -ne 1) {
    throw 'Unexpected application or APK output count'
}
$element = $metadata.elements[0]
$version = $element.versionName
if ($version -notmatch '^\d+\.\d+\.\d+(-[a-z0-9]+)*$') { throw 'Unexpected version name' }
$destination = Join-Path $projectRoot 'dist/ci'
New-Item -ItemType Directory -Force $destination | Out-Null
$apk = Join-Path $destination "mimohodom-$version-debug.apk"
Copy-Item (Join-Path $projectRoot "app/build/outputs/apk/debug/$($element.outputFile)") $apk
Copy-Item (Join-Path $projectRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') (Join-Path $destination 'mimohodom-androidTest.apk')
$buildTools = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0'
$signature = & (Join-Path $buildTools 'apksigner.bat') verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$expectedCertificate = 'f66583eaccc9635f56f96bb0c3ec3fd68af8118155499ce0768d37abdd7ed45c'
if ($env:EXPECT_EXISTING_SIGNATURE -eq 'true' -and ($signature -join "`n") -notmatch $expectedCertificate) {
    throw 'Signing certificate differs from the installed app'
}
$badging = & (Join-Path $buildTools 'aapt.exe') dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'APK metadata verification failed' }
$packageLine = $badging | Where-Object { $_ -match '^package:' }
if ($packageLine -notmatch "name='$([regex]::Escape($metadata.applicationId))'" -or
    $packageLine -notmatch "versionName='$([regex]::Escape($version))'" -or
    $packageLine -notmatch "versionCode='$($element.versionCode)'") { throw 'APK version differs from build metadata' }
$report = @("Commit: $env:GITHUB_SHA", "Run: $env:GITHUB_SERVER_URL/$env:GITHUB_REPOSITORY/actions/runs/$env:GITHUB_RUN_ID", $packageLine)
$report += $signature
$report += 'Android instrumentation APK built; device tests NOT executed. No real AI requests made.'
if ($env:EXPECT_EXISTING_SIGNATURE -ne 'true') { $report += 'Temporary CI signature: not compatible with the locally installed APK.' }
$report | Set-Content (Join-Path $destination 'verification.txt') -Encoding utf8
Get-ChildItem -LiteralPath $destination -Filter '*.apk' | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($_.Name)"
} | Set-Content (Join-Path $destination 'SHA256SUMS.txt') -Encoding ascii
if ($env:GITHUB_STEP_SUMMARY) {
    @("### Мимоходом $version", "APK и контрольные суммы доступны в артефакте сборки.", "", '```text') + $report + '```' |
        Add-Content $env:GITHUB_STEP_SUMMARY -Encoding utf8
}
