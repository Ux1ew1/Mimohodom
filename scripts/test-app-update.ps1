$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$javaRoot = $env:JAVA_HOME
if (-not $javaRoot) {
    $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName
}
$jsonJar = "$projectRoot/.tools/json.jar"
$out = "$projectRoot/.tools/update-tests"
$sources = @("$projectRoot/app/src/main/java/app/fastnotes/AppUpdateManifest.java", "$projectRoot/tests/AppUpdateTest.java")
New-Item -ItemType Directory -Force $out | Out-Null
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'Android update test compilation failed' }
& "$javaRoot/bin/java.exe" -cp "$out;$jsonJar" app.fastnotes.AppUpdateTest
if ($LASTEXITCODE -ne 0) { throw 'Android update tests failed' }
