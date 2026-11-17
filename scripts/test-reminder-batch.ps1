$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$jsonJar = "$projectRoot/.tools/json.jar"
if (-not (Test-Path $jsonJar)) { throw 'Test dependency missing: org.json:json:20240303 (.tools/json.jar)' }
New-Item -ItemType Directory -Force "$projectRoot/.tools/reminder-batch-tests" | Out-Null
$sources = @(
  "$projectRoot/app/src/main/java/app/fastnotes/ReminderBatch.java",
  "$projectRoot/tests/ReminderBatchTest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d "$projectRoot/.tools/reminder-batch-tests" $sources
if ($LASTEXITCODE -ne 0) { throw 'Reminder batch test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$projectRoot/.tools/reminder-batch-tests;$jsonJar" app.fastnotes.ReminderBatchTest
if ($LASTEXITCODE -ne 0) { throw 'Reminder batch tests failed' }
