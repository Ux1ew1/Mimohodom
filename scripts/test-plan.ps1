$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$jsonJar = "$projectRoot/.tools/json.jar"
if (-not (Test-Path $jsonJar)) { throw 'Test dependency missing: org.json:json:20240303 (.tools/json.jar)' }
New-Item -ItemType Directory -Force "$projectRoot/.tools/plan-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/PlanTree.java",
  "$projectRoot/app/src/main/java/app/fastnotes/PlanChat.java",
  "$projectRoot/tests/PlanTreeTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d "$projectRoot/.tools/plan-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Plan test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/plan-tests;$jsonJar" app.fastnotes.PlanTreeTest
if ($LASTEXITCODE -ne 0) { throw 'Plan tests failed' }
