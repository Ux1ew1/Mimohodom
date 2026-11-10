$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolsDirectory = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force $toolsDirectory | Out-Null
$jsonJar = Join-Path $toolsDirectory 'json.jar'
Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $jsonJar
$expected = '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'
if ((Get-FileHash -LiteralPath $jsonJar -Algorithm SHA256).Hash -ne $expected) {
    throw 'org.json dependency checksum mismatch'
}
